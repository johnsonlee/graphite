//! Correctness only: identical ASTs through the original Partition/generic path.
use super::*;
use crate::engine::{CancelToken, Source};
use crate::parser::parse;
use graphite_storage::{graph::Graph, node::NODE_HEADER_BYTES};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

fn fixture() -> Option<Arc<Graph>> {
    let Some(path) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
        assert!(std::env::var_os("GRAPHITE_REQUIRE_ALL_TYPES_FIXTURES").is_none());
        return None;
    };
    Some(Arc::new(Graph::load(Path::new(&path)).unwrap()))
}

fn sources(graph: &Arc<Graph>, ids: &[&str]) -> Vec<Source> {
    ids.iter()
        .map(|id| Source {
            id: Arc::from(*id),
            graph: graph.clone(),
        })
        .collect()
}

fn original<T>(run: impl FnOnce() -> T) -> T {
    struct Reset(bool);
    impl Drop for Reset {
        fn drop(&mut self) {
            DISABLED.with(|flag| flag.set(self.0));
        }
    }
    let _reset = Reset(DISABLED.with(|flag| flag.replace(true)));
    run()
}

fn same(actual: &QueryResult, expected: &QueryResult, query: &str) {
    assert_eq!(actual.columns, expected.columns, "{query}");
    assert_eq!(
        format!("{:?}", actual.rows),
        format!("{:?}", expected.rows),
        "{query}"
    );
    assert_eq!(actual.more, expected.more, "{query}");
    assert!(actual.compact.is_none());
}

fn compare(ex: &Executor, query: &str) -> QueryResult {
    let clauses = parse(query).unwrap();
    assert!(GroupedWithPlan::build(&clauses).is_some(), "{query}");
    assert!(try_execute(ex, &clauses).unwrap().is_some(), "{query}");
    let actual = ex.execute_clauses(&clauses, None).unwrap();
    let expected = original(|| ex.execute_clauses(&clauses, None)).unwrap();
    same(&actual, &expected, query);
    actual
}

#[test]
fn csr_with_all_fields_match_original_partition_and_generic_order_columns_and_provenance() {
    let Some(graph) = fixture() else { return };
    for cross in [false, true] {
        for ids in [["z-first", "a-last"], ["same-id", "same-id"]] {
            let input = sources(&graph, &ids);
            let ex = Executor::new(input.clone(), cross);
            let generic = Executor::new(input, cross).without_partitioning();
            for property in CALL_SITE_PROPERTY_NAMES {
                for predicate in ["", " WHERE k > 5"] {
                    let query = format!("MATCH(n:CallSite) WITH n.{property} AS c, count(*) AS k{predicate} RETURN c,k LIMIT 9223372036854775807");
                    let result = compare(&ex, &query);
                    let expected = original(|| generic.execute(&query, None)).unwrap();
                    same(&result, &expected, &query);
                    if cross {
                        let mut expected_ids = ids.to_vec();
                        expected_ids.sort();
                        expected_ids.dedup();
                        assert!(result
                            .rows
                            .iter()
                            .all(|row| QueryResult::graph_ids(row) == expected_ids));
                    }
                    assert!(result.rows.iter().all(|row| row["c"].as_str().is_some()
                        && matches!(row["k"], Value::Int(n) if n > 0)));
                }
            }
        }
    }
}

#[test]
fn global_counts_are_merged_before_with_predicate_and_limit_or_probe() {
    let Some(graph) = fixture() else { return };
    // A repeated graph has a value with at most five local matches. It must pass
    // k > 5 after six sources merge, even though no individual source passes.
    let mut chosen = None;
    for (field, property) in CALL_SITE_PROPERTY_NAMES.iter().enumerate() {
        let index = graph
            .validated_ordered_call_site(field, || Ok::<_, ()>(()))
            .unwrap()
            .unwrap();
        for (sid, nodes) in index.property_values(field, false).unwrap() {
            if (1..=5).contains(&nodes.len()) {
                chosen = Some((*property, graph.str(sid).to_owned(), nodes.len() as i64));
                break;
            }
        }
        if chosen.is_some() {
            break;
        }
    }
    let (property, value, local) = chosen.expect("fixture needs a small local group");
    let ex = Executor::new(sources(&graph, &["f", "e", "d", "c", "b", "a"]), true);
    let query = format!("MATCH(n:CallSite) WITH n.{property} AS c,count(*) AS k WHERE k > 5 RETURN c,k LIMIT 9223372036854775807");
    let complete = compare(&ex, &query);
    let row = complete
        .rows
        .iter()
        .find(|row| row["c"].as_str() == Some(&value))
        .unwrap();
    assert!(matches!(row["k"], Value::Int(n) if n == local * 6));
    assert_eq!(QueryResult::graph_ids(row), ["a", "b", "c", "d", "e", "f"]);
    for probe in [false, true] {
        let mut limited = Executor::new(ex.sources.clone(), true);
        if probe {
            limited = limited.with_probe();
        }
        for keep in [0, 1, 13, 200] {
            let bounded = query.replace("9223372036854775807", &keep.to_string());
            let result = compare(&limited, &bounded);
            assert_eq!(result.rows.len(), complete.rows.len().min(keep));
            assert_eq!(result.more, probe && complete.rows.len() > keep);
        }
    }
    let quoted = "MATCH(n:CallSite) WITH n.caller_name AS `caller value`,count(*) AS `total count` WHERE `total count` > 0 RETURN `caller value`,`total count` LIMIT 2";
    compare(&ex, quoted);
}

#[test]
fn only_exact_total_shape_is_admitted_and_single_source_stays_original() {
    let prefix = "MATCH(n:CallSite) WITH n.caller_name AS c,count(*) AS k";
    for query in [
        format!("{prefix} RETURN c,k"),
        format!("{prefix} RETURN k,c LIMIT 2"),
        format!("{prefix} RETURN c AS value,k LIMIT 2"),
        format!("{prefix} RETURN c,k ORDER BY k DESC LIMIT 2"),
        format!("{prefix} RETURN c,k SKIP 1 LIMIT 2"),
        format!("{prefix} WHERE k >= 5 RETURN c,k LIMIT 2"),
        format!("{prefix} WHERE 5 < k RETURN c,k LIMIT 2"),
        format!("{prefix} WHERE k > -1 RETURN c,k LIMIT 2"),
        format!("{prefix} WHERE k > 5.0 RETURN c,k LIMIT 2"),
        format!("{prefix} WHERE k > 5 AND true RETURN c,k LIMIT 2"),
        format!("{prefix} RETURN missing_function(c),k LIMIT 0"),
        format!("{prefix} RETURN c,k LIMIT $limit"),
        format!("{prefix} RETURN c,k LIMIT -1"),
        "MATCH(n:CallSite) WHERE true WITH n.caller_name AS c,count(*) AS k RETURN c,k LIMIT 2".into(),
        "MATCH(n:CallSite) WITH n.caller_name AS n,count(*) AS k RETURN n,k LIMIT 2".into(),
        "MATCH(n:CallSite) WITH n.caller_name AS c,count(*) AS c RETURN c,c LIMIT 2".into(),
        "MATCH(n:CallSite) WITH n.caller_name AS c,count(n) AS k RETURN c,k LIMIT 2".into(),
        "MATCH(n:CallSite) WITH n.id AS c,count(*) AS k RETURN c,k LIMIT 2".into(),
        "MATCH(n:Method) WITH n.name AS c,count(*) AS k RETURN c,k LIMIT 2".into(),
        "OPTIONAL MATCH(n:CallSite) WITH n.caller_name AS c,count(*) AS k RETURN c,k LIMIT 2".into(),
        "MATCH(n:CallSite) WITH DISTINCT n.caller_name AS c,count(*) AS k RETURN c,k LIMIT 2".into(),
        "MATCH(n:CallSite {caller_name:'x'}) WITH n.caller_name AS c,count(*) AS k RETURN c,k LIMIT 2".into(),
    ] { assert!(GroupedWithPlan::build(&parse(&query).unwrap()).is_none(), "{query}"); }
    let query = format!("{prefix} RETURN c,k LIMIT 2");
    let mut internal = parse(&query).unwrap();
    if let Clause::With {
        items: Some(items), ..
    } = &mut internal[1]
    {
        items[0].alias = Some("\0internal".into());
    }
    assert!(GroupedWithPlan::build(&internal).is_none());
    let Some(graph) = fixture() else { return };
    let ex = Executor::single("single", graph);
    assert!(try_execute(&ex, &parse(&query).unwrap()).unwrap().is_none());
    same(
        &ex.execute(&query, None).unwrap(),
        &original(|| ex.execute(&query, None)).unwrap(),
        &query,
    );
}

struct Variant(PathBuf);
impl Drop for Variant {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}
#[derive(Clone, Copy)]
enum Damage {
    None,
    Index,
    BadSid(usize),
    Truncated(bool),
}
fn variant(graph: &Graph, ids: &[u32], damage: Damage) -> (Variant, Arc<Graph>) {
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let path = Variant(std::env::temp_dir().join(format!(
        "graphite-grouped-with-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    )));
    std::fs::create_dir(&path.0).unwrap();
    for entry in std::fs::read_dir(&graph.dir).unwrap() {
        let entry = entry.unwrap();
        if !entry.file_type().unwrap().is_file() {
            continue;
        }
        let name = entry.file_name();
        if name == "graph.typeindex"
            || (matches!(damage, Damage::Index) && name == "graph.callsite-string-index")
            || (matches!(damage, Damage::BadSid(_) | Damage::Truncated(_))
                && name == "graph.nodedata")
        {
            continue;
        }
        std::fs::hard_link(entry.path(), path.0.join(name)).unwrap();
    }
    let original = std::fs::read(graph.dir.join("graph.typeindex")).unwrap();
    let mut bytes = original[..4].to_vec();
    bytes.extend(1i32.to_be_bytes());
    bytes.push(TAG_CALL_SITE_NODE);
    bytes.extend((ids.len() as i32).to_be_bytes());
    bytes.extend(21i64.to_be_bytes());
    for id in ids {
        bytes.extend(id.to_be_bytes());
    }
    std::fs::write(path.0.join("graph.typeindex"), bytes).unwrap();
    match damage {
        Damage::None => {}
        Damage::Index => {
            let mut bytes = std::fs::read(graph.dir.join("graph.callsite-string-index")).unwrap();
            bytes[76..80].copy_from_slice(&u32::MAX.to_be_bytes());
            std::fs::write(path.0.join("graph.callsite-string-index"), bytes).unwrap();
        }
        Damage::BadSid(field) => {
            let offset = graph.node_offset(*ids.last().unwrap()).unwrap();
            let mut bytes = graph.nodedata().to_vec();
            let at = offset + NODE_HEADER_BYTES + field * 4;
            bytes[at..at + 4].copy_from_slice(&u32::MAX.to_be_bytes());
            std::fs::write(path.0.join("graph.nodedata"), bytes).unwrap();
        }
        Damage::Truncated(keep_strings) => {
            let offset = graph.node_offset(*ids.last().unwrap()).unwrap();
            let bytes = graph.nodedata();
            let start = offset + NODE_HEADER_BYTES;
            let count = i32::from_be_bytes(bytes[start + 8..start + 12].try_into().unwrap()).max(0)
                as usize;
            let end = if keep_strings {
                start + 16 + count * 4 + 8
            } else {
                start + 4
            };
            std::fs::write(path.0.join("graph.nodedata"), &bytes[..end]).unwrap();
        }
    }
    let loaded = Arc::new(Graph::load(&path.0).unwrap());
    (path, loaded)
}

fn outcome(ex: &Executor, query: &str, disable: bool) -> String {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if disable {
            original(|| ex.execute(query, None))
        } else {
            ex.execute(query, None)
        }
    }));
    match result {
        Ok(result) => format!("{result:?}"),
        Err(panic) => format!(
            "panic:{}",
            panic
                .downcast_ref::<String>()
                .cloned()
                .unwrap_or_else(|| panic.downcast_ref::<&str>().unwrap().to_string())
        ),
    }
}

#[test]
fn any_unproved_source_declines_entire_plan_preserving_original_malformed_and_limit_zero_errors() {
    let Some(graph) = fixture() else { return };
    let ids = graph.ids_by_tag(TAG_CALL_SITE_NODE);
    let mut duplicate = ids.to_vec();
    duplicate.insert(0, ids[0]);
    let mut phantom = ids.to_vec();
    phantom.push(u32::MAX);
    let mut reverse = ids.to_vec();
    reverse.reverse();
    for (ids, damage) in [
        (duplicate, Damage::None),
        (phantom, Damage::None),
        (reverse, Damage::None),
        (ids[..ids.len() - 1].to_vec(), Damage::None),
        (ids.to_vec(), Damage::Index),
        (ids.to_vec(), Damage::BadSid(0)),
        (ids.to_vec(), Damage::BadSid(1)),
        (ids.to_vec(), Damage::Truncated(true)),
        (ids.to_vec(), Damage::Truncated(false)),
    ] {
        let (_path, changed) = variant(&graph, &ids, damage);
        for changed_first in [false, true] {
            let mut input = sources(&graph, &["z-first", "a-last"]);
            input[usize::from(!changed_first)].graph = changed.clone();
            let ex = Executor::new(input, true);
            for keep in [0, 1] {
                let query = format!("MATCH(n:CallSite) WITH n.caller_class AS c,count(*) AS k WHERE k > 5 RETURN c,k LIMIT {keep}");
                assert!(try_execute(&ex, &parse(&query).unwrap()).unwrap().is_none());
                assert_eq!(
                    outcome(&ex, &query, false),
                    outcome(&ex, &query, true),
                    "{query}"
                );
            }
        }
    }
}

#[test]
fn empty_domains_ignore_stale_csr_and_cancellation_and_unsupported_errors_stay_visible() {
    let Some(graph) = fixture() else { return };
    let (_path, empty) = variant(&graph, &[], Damage::Index);
    let ex = Executor::new(sources(&empty, &["z", "a"]), true);
    let query =
        "MATCH(n:CallSite) WITH n.caller_name AS c,count(*) AS k WHERE k > 5 RETURN c,k LIMIT 0";
    assert!(compare(&ex, query).rows.is_empty());
    let ex = Executor::new(sources(&graph, &["z", "a"]), true);
    let error_query = "MATCH(n:CallSite) WITH n.caller_name AS c,count(*) AS k WHERE missing_function(k) RETURN c,k LIMIT 0";
    let result = outcome(&ex, error_query, false);
    assert_eq!(result, outcome(&ex, error_query, true));
    assert!(result.contains("missing_function"));
    let cancel = CancelToken::new();
    cancel.cancel();
    assert!(matches!(
        Executor::new(sources(&graph, &["z", "a"]), true)
            .with_cancel(cancel)
            .execute(query, None),
        Err(CypherError::Cancelled)
    ));
    assert!(matches!(
        Executor::new(sources(&graph, &["z", "a"]), true)
            .with_cancel(CancelToken::with_timeout(0))
            .execute(query, None),
        Err(CypherError::Timeout(0))
    ));
}

/// One real64 load, one original Partition/generic complete oracle per field.
/// No request latency or resource samples are retained by this correctness gate.
#[test]
fn real64_csr_with_matches_original_complete_typed_rows_then_predicate_limits_and_probes() {
    let Some(manifest) = std::env::var_os("GRAPHITE_GROUPED_WITH_REAL64_MANIFEST") else {
        return;
    };
    let manifest = Path::new(&manifest);
    let mut paths = std::collections::BTreeSet::new();
    let mut fingerprints = std::collections::BTreeSet::new();
    let mut source_ids = std::collections::BTreeSet::new();
    let mut input = Vec::new();
    for line in std::fs::read_to_string(manifest)
        .unwrap()
        .lines()
        .filter(|line| !line.trim().is_empty() && !line.starts_with('#'))
    {
        let fields: Vec<_> = line.split('\t').collect();
        assert_eq!(fields.len(), 6);
        let path = Path::new(fields[1]);
        let path = if path.is_absolute() {
            path.to_path_buf()
        } else {
            manifest.parent().unwrap().join(path)
        }
        .canonicalize()
        .unwrap();
        assert!(paths.insert(path.clone()));
        assert!(fingerprints.insert(fields[5].to_owned()));
        assert!(source_ids.insert(fields[0].to_owned()));
        let graph = Arc::new(Graph::load(&path).unwrap());
        assert!(graph.count_by_tag(TAG_CALL_SITE_NODE) > 0);
        input.push(Source {
            id: Arc::from(fields[0]),
            graph,
        });
        if input.len().is_multiple_of(16) {
            eprintln!("WITH correctness: loaded {}/64 real sources", input.len());
        }
    }
    assert_eq!(input.len(), 64);
    let ex = Executor::new(input.clone(), true);
    let mut receipts = Vec::new();
    for (field, property) in CALL_SITE_PROPERTY_NAMES.iter().enumerate() {
        eprintln!("WITH correctness: original full Partition/generic oracle for {property}");
        let prefix = format!("MATCH (n:CallSite) WITH n.{property} AS c, count(*) AS k");
        let full_query = format!("{prefix} RETURN c,k LIMIT 9223372036854775807");
        let oracle = original(|| ex.execute(&full_query, None)).unwrap();
        assert!(oracle.rows.len() > 200);
        let contributing: std::collections::BTreeSet<_> = oracle
            .rows
            .iter()
            .flat_map(QueryResult::graph_ids)
            .collect();
        assert_eq!(contributing, source_ids);
        let full = ex.execute(&full_query, None).unwrap();
        same(&full, &oracle, &full_query);
        for source in &input {
            assert!(source
                .graph
                .validated_ordered_call_site(field, || ex.cancel.check())
                .unwrap()
                .is_some());
        }
        let mut cases = Vec::new();
        // The full no-predicate case above checks every typed row/count/order/id.
        // Finite results derive from that independent oracle, without rescanning
        // all original node rows for every LIMIT/probe combination.
        let filtered: Vec<Row> = oracle
            .rows
            .iter()
            .filter(|row| matches!(row["k"], Value::Int(n) if n > 5))
            .cloned()
            .collect();
        for (limit, probe) in [(0, false), (1, true), (50, false), (200, true)] {
            let query = format!("{prefix} WHERE k > 5 RETURN c,k LIMIT {limit}");
            let actual_ex = if probe {
                Executor::new(input.clone(), true).with_probe()
            } else {
                Executor::new(input.clone(), true)
            };
            assert!(GroupedWithPlan::build(&parse(&query).unwrap()).is_some());
            let actual = actual_ex.execute(&query, None).unwrap();
            let expected = QueryResult {
                columns: oracle.columns.clone(),
                rows: filtered.iter().take(limit).cloned().collect(),
                compact: None,
                more: probe && filtered.len() > limit,
            };
            same(&actual, &expected, &query);
            cases.push(serde_json::json!({"query":query,"probe":probe,"rows":actual.rows.len(),"more":actual.more,"completeTypedRowsCountsOrderProvenanceMatchOriginalOracle":true}));
        }
        use std::hash::{Hash, Hasher};
        let mut hash = std::collections::hash_map::DefaultHasher::new();
        oracle.columns.hash(&mut hash);
        for row in &oracle.rows {
            format!("{row:?}").hash(&mut hash);
        }
        receipts.push(serde_json::json!({"property":property,"validatedSources":64,"originalCompleteRows":oracle.rows.len(),"afterGlobalCountGreaterThanFiveRows":filtered.len(),"originalCompleteTypedRowsDefaultHasher":format!("{:016x}", hash.finish()),"fullIndexedResultMatchesOriginal":true,"cases":cases}));
        eprintln!("WITH correctness: {property} full typed rows/counts/order/64-source provenance, predicate and LIMIT/probe match original");
    }
    if let Some(output) = std::env::var_os("GRAPHITE_GROUPED_WITH_SMOKE_OUTPUT") {
        std::fs::write(output, serde_json::to_vec_pretty(&serde_json::json!({"correctnessOnly":true,"performanceSamplesCollected":false,"manifest":manifest,"sourceIds":source_ids,"distinctGraphPaths":paths,"distinctWorkloadFingerprints":fingerprints.len(),"properties":receipts})).unwrap()).unwrap();
    }
}
