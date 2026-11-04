//! Correctness/source-access assertions only. No fixture timing is performance evidence.
use super::*;
use crate::engine::{CancelToken, Source};
use crate::parser::parse;
use crate::CypherError;
use graphite_storage::node::{NODE_HEADER_BYTES, TAG_FIELD_NODE, TAG_STRING_CONSTANT};
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

fn sources(graph: &Arc<Graph>) -> Vec<Source> {
    ["z-first", "a-last"]
        .into_iter()
        .map(|id| Source {
            id: Arc::from(id),
            graph: graph.clone(),
        })
        .collect()
}

fn same(actual: &QueryResult, expected: &QueryResult, query: &str) {
    assert_eq!(actual.columns, expected.columns, "{query}");
    assert_eq!(
        format!("{:?}", actual.rows),
        format!("{:?}", expected.rows),
        "{query}"
    );
    assert_eq!(actual.more, expected.more, "{query}");
}

fn compare(ex: &Executor, query: &str) -> QueryResult {
    let clauses = parse(query).unwrap();
    let actual = distinct_string_property(ex, &clauses).unwrap().unwrap();
    let expected = distinct_string_property_with_ids(ex, &clauses, raw_distinct_string_ids)
        .unwrap()
        .unwrap();
    same(&actual, &expected, query);
    actual
}

#[test]
fn multigraph_csr_distinct_preserves_first_seen_rows_order_all_provenance_and_alias_sort() {
    let Some(graph) = fixture() else { return };
    for cross in [false, true] {
        let ex = Executor::new(sources(&graph), cross);
        for (field, property) in CALL_SITE_FIELDS.iter().enumerate() {
            assert!(graph
                .validated_ordered_call_site(field, || Ok::<_, ()>(()))
                .unwrap()
                .is_some());
            let raw = raw_distinct_string_ids(
                &ex,
                &graph,
                TAG_CALL_SITE_NODE,
                StringField::CallSite(field),
                false,
                None,
            )
            .unwrap();
            for limit in [None, Some(0), Some(1), Some(13)] {
                let suffix = limit.map(|n| format!(" LIMIT {n}")).unwrap_or_default();
                let query =
                    format!("MATCH (n:CallSite) RETURN DISTINCT n.{property} AS value{suffix}");
                let result = compare(&ex, &query);
                if limit != Some(0) {
                    assert_eq!(result.rows[0]["value"].as_str(), Some(graph.str(raw[0])));
                    assert_eq!(
                        QueryResult::graph_ids(&result.rows[0]),
                        if cross {
                            vec!["a-last", "z-first"]
                        } else {
                            vec![]
                        }
                    );
                }
                let sorted = compare(&ex, &format!("MATCH (n:CallSite) RETURN DISTINCT n.{property} AS value ORDER BY value ASC{suffix}"));
                assert!(sorted
                    .rows
                    .windows(2)
                    .all(|rows| graphite_storage::strings::java_cmp(
                        rows[0]["value"].as_str().unwrap(),
                        rows[1]["value"].as_str().unwrap()
                    ) != std::cmp::Ordering::Greater));
            }
        }
    }
}

#[test]
fn csr_distinct_limits_never_drop_later_sources_and_executor_probe_matches_raw_oracle() {
    let Some(graph) = fixture() else { return };
    let ex = Executor::new(sources(&graph), true).with_probe();
    let prefix = "MATCH (n:CallSite) RETURN DISTINCT n.callee_class AS value";
    for keep in [0, 1, 7] {
        let actual = ex.execute(&format!("{prefix} LIMIT {keep}"), None).unwrap();
        let clauses = parse(&format!("{prefix} LIMIT {}", keep + 1)).unwrap();
        let mut expected =
            distinct_string_property_with_ids(&ex, &clauses, raw_distinct_string_ids)
                .unwrap()
                .unwrap();
        expected.more = expected.rows.len() > keep;
        expected.rows.truncate(keep);
        same(&actual, &expected, prefix);
        assert!(actual
            .rows
            .iter()
            .all(|row| QueryResult::graph_ids(row) == ["a-last", "z-first"]));
    }
    let cancel = CancelToken::new();
    cancel.cancel();
    assert!(matches!(
        Executor::new(sources(&graph), true)
            .with_cancel(cancel)
            .execute(prefix, None),
        Err(CypherError::Cancelled)
    ));
    assert!(matches!(
        Executor::new(sources(&graph), true)
            .with_cancel(CancelToken::with_timeout(0))
            .execute(prefix, None),
        Err(CypherError::Timeout(0))
    ));
}

#[test]
fn only_multigraph_callsite_properties_use_csr_other_fields_and_single_sources_keep_raw_work() {
    let Some(graph) = fixture() else { return };
    let multi = Executor::new(sources(&graph), true);
    for (tag, field, label, property) in [
        (
            TAG_STRING_CONSTANT,
            StringField::Fixed(NODE_HEADER_BYTES),
            "StringConstant",
            "value",
        ),
        (
            TAG_FIELD_NODE,
            StringField::Fixed(NODE_HEADER_BYTES + 4),
            "FieldNode",
            "name",
        ),
    ] {
        assert!(!graph.ids_by_tag(tag).is_empty());
        let ex = Executor::new(sources(&graph), true);
        let actual = indexed_distinct_string_ids(&ex, &graph, tag, field, false, Some(1)).unwrap();
        assert_eq!(
            ex.poll.load(Ordering::Relaxed),
            graph.ids_by_tag(tag).len() as u64
        );
        let expected = raw_distinct_string_ids(&multi, &graph, tag, field, false, Some(1)).unwrap();
        assert_eq!(actual, expected);
        compare(
            &ex,
            &format!("MATCH(n:{label}) RETURN DISTINCT n.{property} LIMIT 1"),
        );
    }
    let single = Executor::single("single", graph.clone());
    let actual = indexed_distinct_string_ids(
        &single,
        &graph,
        TAG_CALL_SITE_NODE,
        StringField::CallSite(2),
        false,
        Some(1),
    )
    .unwrap();
    assert_eq!(
        single.poll.load(Ordering::Relaxed),
        1,
        "single-source LIMIT keeps the original first-record early stop"
    );
    let oracle = Executor::single("single", graph.clone());
    assert_eq!(
        actual,
        raw_distinct_string_ids(
            &oracle,
            &graph,
            TAG_CALL_SITE_NODE,
            StringField::CallSite(2),
            false,
            Some(1)
        )
        .unwrap()
    );
    assert!(graph
        .validated_ordered_call_site(2, || Ok::<_, ()>(()))
        .unwrap()
        .is_some());
    let warm = Executor::new(sources(&graph), true);
    let ids = indexed_distinct_string_ids(
        &warm,
        &graph,
        TAG_CALL_SITE_NODE,
        StringField::CallSite(2),
        false,
        Some(1),
    )
    .unwrap();
    assert_eq!(
        warm.poll.load(Ordering::Relaxed),
        ids.len() as u64,
        "warm valid CSR touches one group header, not each posting ID"
    );
}

struct Variant(PathBuf);
impl Drop for Variant {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

enum Damage {
    None,
    Index,
    BadSid(usize),
    Truncated(bool),
}

fn variant(graph: &Graph, ids: &[u32], damage: Damage) -> (Variant, Arc<Graph>) {
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let path = Variant(std::env::temp_dir().join(format!(
        "graphite-distinct-csr-{}-{}",
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
            assert!(field < 2);
            let offset = graph.node_offset(*ids.last().unwrap()).unwrap();
            let mut bytes = graph.nodedata().to_vec();
            let at = offset + NODE_HEADER_BYTES + field * 4;
            bytes[at..at + 4].copy_from_slice(&u32::MAX.to_be_bytes());
            std::fs::write(path.0.join("graph.nodedata"), bytes).unwrap();
        }
        Damage::Truncated(keep_string_fields) => {
            let offset = graph.node_offset(*ids.last().unwrap()).unwrap();
            let bytes = graph.nodedata();
            let start = offset + NODE_HEADER_BYTES;
            let count = i32::from_be_bytes(bytes[start + 8..start + 12].try_into().unwrap()).max(0)
                as usize;
            let end = if keep_string_fields {
                start + 16 + count * 4 + 8
            } else {
                start + 4
            };
            std::fs::write(path.0.join("graph.nodedata"), &bytes[..end]).unwrap();
        }
    }
    let graph = Arc::new(Graph::load(&path.0).unwrap());
    (path, graph)
}

#[test]
fn malformed_empty_unsorted_duplicate_and_stale_index_sources_keep_raw_distinct_semantics() {
    let Some(graph) = fixture() else { return };
    let ids = graph.ids_by_tag(TAG_CALL_SITE_NODE);
    let mut duplicate = ids.to_vec();
    duplicate.insert(0, ids[0]);
    let mut phantom = ids.to_vec();
    phantom.push(u32::MAX);
    let mut reversed = ids.to_vec();
    reversed.reverse();
    let omitted = ids[..ids.len() - 1].to_vec();
    for (ids, damage) in [
        (duplicate, Damage::None),
        (phantom, Damage::None),
        (reversed, Damage::None),
        (omitted, Damage::None),
        (vec![], Damage::None),
        (ids.to_vec(), Damage::Index),
    ] {
        let (_path, changed) = variant(&graph, &ids, damage);
        assert!(changed
            .validated_ordered_call_site(0, || Ok::<_, ()>(()))
            .unwrap()
            .is_none());
        let ex = Executor::new(
            vec![
                Source {
                    id: Arc::from("z-raw-first"),
                    graph: changed,
                },
                Source {
                    id: Arc::from("a-indexed-last"),
                    graph: graph.clone(),
                },
            ],
            true,
        );
        for tail in ["", " LIMIT 1", " ORDER BY value LIMIT 11"] {
            compare(
                &ex,
                &format!("MATCH(n:CallSite) RETURN DISTINCT n.caller_class AS value{tail}"),
            );
        }
    }
}

fn panic_text(panic: Box<dyn std::any::Any + Send>) -> String {
    panic
        .downcast_ref::<String>()
        .cloned()
        .unwrap_or_else(|| panic.downcast_ref::<&str>().unwrap().to_string())
}

#[test]
fn malformed_sid_and_truncated_late_records_preserve_raw_results_or_original_panic() {
    let Some(graph) = fixture() else { return };
    let ids = graph.ids_by_tag(TAG_CALL_SITE_NODE);
    let query = "MATCH(n:CallSite) RETURN DISTINCT n.caller_class AS value LIMIT 1";
    for (damage, panics) in [
        (Damage::BadSid(0), true),
        (Damage::BadSid(1), false),
        (Damage::Truncated(true), false),
        (Damage::Truncated(false), true),
    ] {
        let (_path, changed) = variant(&graph, ids, damage);
        assert!(changed
            .validated_ordered_call_site(0, || Ok::<_, ()>(()))
            .unwrap()
            .is_none());
        // A finite single-source scan never validates or reaches the malformed tail.
        let single = Executor::single("single", changed.clone());
        assert_eq!(compare(&single, query).rows.len(), 1);
        let ex = Executor::new(
            vec![
                Source {
                    id: Arc::from("first-valid"),
                    graph: graph.clone(),
                },
                Source {
                    id: Arc::from("later-malformed"),
                    graph: changed,
                },
            ],
            true,
        );
        if !panics {
            compare(&ex, query);
            continue;
        }
        let clauses = parse(query).unwrap();
        let actual = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            distinct_string_property(&ex, &clauses)
        }))
        .expect_err("all later sources must still be observed despite global LIMIT");
        let expected = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            distinct_string_property_with_ids(&ex, &clauses, raw_distinct_string_ids)
        }))
        .expect_err("raw oracle must expose the same malformed tail");
        assert_eq!(panic_text(actual), panic_text(expected));
    }
}

/// Complete multi-source correctness only. The actual server pressure suite is
/// the performance boundary; this test retains no latency/resource samples.
#[test]
fn real64_distinct_all_properties_preserve_raw_order_provenance_limits_and_probes() {
    let Some(manifest) = std::env::var_os("GRAPHITE_DISTINCT_REAL64_MANIFEST") else {
        return;
    };
    let manifest = Path::new(&manifest);
    let text = std::fs::read_to_string(manifest).unwrap();
    let mut paths = std::collections::BTreeSet::new();
    let mut fingerprints = std::collections::BTreeSet::new();
    let mut source_ids = std::collections::BTreeSet::new();
    let mut sources = Vec::new();
    for line in text
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
        sources.push(Source {
            id: Arc::from(fields[0]),
            graph,
        });
        if sources.len().is_multiple_of(16) {
            eprintln!(
                "distinct correctness: loaded {}/64 real sources",
                sources.len()
            );
        }
    }
    assert_eq!(sources.len(), 64);
    let ex = Executor::new(sources.clone(), true);
    let mut receipts = Vec::new();
    for (property, name) in CALL_SITE_FIELDS.iter().enumerate() {
        eprintln!("distinct correctness: proving property {property} on all 64 sources");
        for source in &sources {
            assert!(
                source
                    .graph
                    .validated_ordered_call_site(property, || ex.cancel.check())
                    .unwrap()
                    .is_some(),
                "{}: {name}",
                source.id
            );
        }
        let prefix = format!("MATCH (n:CallSite) RETURN DISTINCT n.{name} AS value");
        let raw = distinct_string_property_with_ids(
            &ex,
            &parse(&prefix).unwrap(),
            raw_distinct_string_ids,
        )
        .unwrap()
        .unwrap();
        assert!(
            raw.rows.len() > 200,
            "the matrix must exercise finite limits for {name}"
        );
        let first = raw_distinct_string_ids(
            &ex,
            &sources[0].graph,
            TAG_CALL_SITE_NODE,
            StringField::CallSite(property),
            false,
            None,
        )
        .unwrap()[0];
        assert_eq!(
            raw.rows[0]["value"].as_str(),
            Some(sources[0].graph.str(first))
        );
        let contributed: std::collections::BTreeSet<_> =
            raw.rows.iter().flat_map(QueryResult::graph_ids).collect();
        assert_eq!(
            contributed, source_ids,
            "all actual sources contribute to the complete oracle"
        );
        let mut cases = Vec::new();
        for sorted in [false, true] {
            let suffix = if sorted { " ORDER BY value ASC" } else { "" };
            let mut ordered = raw.rows.clone();
            if sorted {
                ordered.sort_by(|a, b| {
                    graphite_storage::strings::java_cmp(
                        a["value"].as_str().unwrap(),
                        b["value"].as_str().unwrap(),
                    )
                });
            }
            for limit in [None, Some(0usize), Some(1), Some(200)] {
                for probe in [false, true] {
                    let query = format!(
                        "{prefix}{suffix}{}",
                        limit.map(|n| format!(" LIMIT {n}")).unwrap_or_default()
                    );
                    let mut expected = QueryResult {
                        columns: raw.columns.clone(),
                        rows: ordered.clone(),
                        compact: None,
                        more: false,
                    };
                    if let Some(limit) = limit {
                        expected.more = probe && expected.rows.len() > limit;
                        expected.rows.truncate(limit);
                    }
                    let actual_ex = if probe {
                        Executor::new(sources.clone(), true).with_probe()
                    } else {
                        Executor::new(sources.clone(), true)
                    };
                    let actual = actual_ex.execute(&query, None).unwrap();
                    same(&actual, &expected, &query);
                    assert!(
                        distinct_string_property(&actual_ex, &parse(&query).unwrap())
                            .unwrap()
                            .is_some(),
                        "{query} must use DISTINCT's source producer"
                    );
                    cases.push(serde_json::json!({"query":query,"probe":probe,"rowCount":actual.rows.len(),"more":actual.more,"completeTypedRowsAndProvenanceMatchRawOracle":true}));
                }
            }
        }
        eprintln!("distinct correctness: property {property} full rows/order/64-source provenance/limit/probe match raw oracle");
        receipts.push(serde_json::json!({"property":property,"name":name,"validatedSources":64,"globalDistinctCount":raw.rows.len(),"completeRawOracleTypedRows":format!("{:?}",raw.rows),"cases":cases}));
    }
    if let Some(output) = std::env::var_os("GRAPHITE_DISTINCT_SMOKE_OUTPUT") {
        let receipt = serde_json::json!({"correctnessOnly":true,"performanceSamplesCollected":false,"manifest":manifest,"sourceIds":source_ids,"distinctGraphPaths":paths,"distinctWorkloadFingerprints":fingerprints.len(),"properties":receipts});
        std::fs::write(output, serde_json::to_vec_pretty(&receipt).unwrap()).unwrap();
    }
}
