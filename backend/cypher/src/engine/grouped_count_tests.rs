//! Correctness and source-access assertions only, never performance measurements.
use super::*;
use crate::engine::{CancelToken, Source};
use crate::parser::parse;
use crate::CypherError;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

fn fixture() -> Option<Arc<Graph>> {
    let Some(path) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
        assert!(std::env::var_os("GRAPHITE_REQUIRE_ALL_TYPES_FIXTURES").is_none());
        return None;
    };
    let graph = Arc::new(Graph::load(Path::new(&path)).unwrap());
    assert!(!graph.ids_by_tag(TAG_CALL_SITE_NODE).is_empty());
    Some(graph)
}

fn sources(graph: Arc<Graph>) -> Vec<Source> {
    ["z-first", "a-second"]
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
    let actual = grouped_call_site_property(ex, &clauses).unwrap().unwrap();
    let expected =
        grouped_call_site_property_with_counts(ex, &clauses, raw_call_site_property_counts)
            .unwrap()
            .unwrap();
    same(&actual, &expected, query);
    actual
}

#[test]
fn grouped_csr_counts_match_complete_raw_rows_first_encounters_and_stable_count_ties() {
    let Some(graph) = fixture() else { return };
    let ex = Executor::new(sources(graph.clone()), true);
    for (field, property) in CALL_SITE_FIELDS.iter().enumerate() {
        assert!(graph
            .validated_ordered_call_site(field, || Ok::<_, ()>(()))
            .unwrap()
            .is_some());
        let raw = raw_call_site_property_counts(&ex, &graph, field).unwrap();
        let first = raw.first().unwrap();
        let query = format!("MATCH (n:CallSite) RETURN n.{property} AS value, count(*) AS c");
        let actual = compare(&ex, &query);
        assert_eq!(actual.rows[0]["value"].as_str(), Some(graph.str(*first.0)));
        assert!(matches!(actual.rows[0]["c"], Value::Int(n) if n == first.1 * 2));
        assert_eq!(
            QueryResult::graph_ids(&actual.rows[0]),
            ["a-second", "z-first"]
        );
        let total: i64 = actual
            .rows
            .iter()
            .map(|row| match row["c"] {
                Value::Int(n) => n,
                _ => panic!("integer count"),
            })
            .sum();
        assert_eq!(total, graph.count_by_tag(TAG_CALL_SITE_NODE) as i64 * 2);
        let sorted = compare(&ex, &format!("{query} ORDER BY c DESC"));
        let mut first_by_count = std::collections::HashMap::new();
        if let Some((a, b)) = raw.iter().find_map(|(key, count)| {
            first_by_count
                .insert(*count, *key)
                .map(|earlier| (earlier, *key))
        }) {
            let position = |key| {
                sorted
                    .rows
                    .iter()
                    .position(|row| row["value"].as_str() == Some(graph.str(key)))
                    .unwrap()
            };
            assert!(
                position(a) < position(b),
                "equal-count groups keep raw first-seen order"
            );
        }
        for limit in [0, 1, 7] {
            compare(&ex, &format!("{query} ORDER BY c DESC LIMIT {limit}"));
        }
    }
    let single = Executor::new(vec![ex.sources[0].clone()], false);
    let result = compare(
        &single,
        "MATCH (n:CallSite) RETURN n.callee_name AS value, count(*) AS c ORDER BY c DESC LIMIT 7",
    );
    assert!(result
        .rows
        .iter()
        .all(|row| QueryResult::graph_ids(row).is_empty()));
}

#[test]
fn grouped_csr_reads_group_headers_without_enumerating_warm_posting_runs() {
    let Some(graph) = fixture() else { return };
    for field in 0..CALL_SITE_FIELDS.len() {
        assert!(graph
            .validated_ordered_call_site(field, || Ok::<_, ()>(()))
            .unwrap()
            .is_some());
        let indexed = Executor::single("indexed", graph.clone());
        let actual = indexed_call_site_property_counts(&indexed, &graph, field).unwrap();
        assert_eq!(indexed.poll.load(Ordering::Relaxed), actual.len() as u64);
        let raw = Executor::single("raw", graph.clone());
        let expected = raw_call_site_property_counts(&raw, &graph, field).unwrap();
        assert_eq!(
            raw.poll.load(Ordering::Relaxed),
            graph.count_by_tag(TAG_CALL_SITE_NODE) as u64
        );
        assert_eq!(actual, expected);
    }
}

#[test]
fn grouped_csr_executor_limit_probe_and_cancellation_keep_existing_contracts() {
    let Some(graph) = fixture() else { return };
    let ex = Executor::new(sources(graph), true).with_probe();
    let prefix = "MATCH (n:CallSite) RETURN n.callee_class AS value, count(*) AS c ORDER BY c DESC";
    for keep in [0, 1, 7] {
        let actual = ex.execute(&format!("{prefix} LIMIT {keep}"), None).unwrap();
        let clauses = parse(&format!("{prefix} LIMIT {}", keep + 1)).unwrap();
        let mut expected =
            grouped_call_site_property_with_counts(&ex, &clauses, raw_call_site_property_counts)
                .unwrap()
                .unwrap();
        expected.more = expected.rows.len() > keep;
        expected.rows.truncate(keep);
        same(&actual, &expected, prefix);
    }
    let cancel = CancelToken::new();
    cancel.cancel();
    assert!(matches!(
        Executor::new(ex.sources.clone(), true)
            .with_cancel(cancel)
            .execute(prefix, None),
        Err(CypherError::Cancelled)
    ));
    assert!(matches!(
        Executor::new(ex.sources.clone(), true)
            .with_cancel(CancelToken::with_timeout(0))
            .execute(prefix, None),
        Err(CypherError::Timeout(0))
    ));
    let invalid =
        "MATCH (n:CallSite) RETURN n.callee_class, count(*) ORDER BY count(*) DESC LIMIT 1";
    assert!(grouped_call_site_property(&ex, &parse(invalid).unwrap())
        .unwrap()
        .is_none());
    // The Graphite default preserves its pre-TCK aggregate-expression error.
    assert_eq!(
        ex.execute(invalid, None).unwrap_err(),
        CypherError::Aggregation("count".into())
    );
    // M23 permits aggregate ORDER BY expressions and must retain the complete
    // grouped fallback result, independently of the optimized alias form.
    let standard =
        Executor::new(ex.sources.clone(), true).with_dialect(crate::CypherDialect::OpenCypherM23);
    let actual = standard.execute(invalid, None).unwrap();
    let expected = standard
        .execute(
            "MATCH (n:CallSite) RETURN n.callee_class AS c, count(*) AS k ORDER BY k DESC LIMIT 1",
            None,
        )
        .unwrap();
    assert_eq!(actual.rows.len(), expected.rows.len());
    assert_eq!(
        format!("{:?}", actual.rows[0]["n.callee_class"]),
        format!("{:?}", expected.rows[0]["c"])
    );
    assert_eq!(
        format!("{:?}", actual.rows[0]["count(*)"]),
        format!("{:?}", expected.rows[0]["k"])
    );
}

struct Variant(PathBuf);
impl Drop for Variant {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn variant(
    graph: &Graph,
    ids: &[u32],
    broken_index: bool,
    bad_field: Option<usize>,
) -> (Variant, Arc<Graph>) {
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let path = Variant(std::env::temp_dir().join(format!(
        "graphite-group-count-{}-{}",
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
            || (broken_index && name == "graph.callsite-string-index")
            || (bad_field.is_some() && name == "graph.nodedata")
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
    if broken_index {
        let mut bytes = std::fs::read(graph.dir.join("graph.callsite-string-index")).unwrap();
        // The first property's first used SID: fixed v2 header is 76 bytes.
        bytes[76..80].copy_from_slice(&u32::MAX.to_be_bytes());
        std::fs::write(path.0.join("graph.callsite-string-index"), bytes).unwrap();
    }
    if let Some(field) = bad_field {
        let id = *ids.last().unwrap();
        let offset = graph.node_offset(id).unwrap();
        let mut bytes = graph.nodedata().to_vec();
        assert!(field < 2);
        let at = offset + graphite_storage::node::NODE_HEADER_BYTES + field * 4;
        bytes[at..at + 4].copy_from_slice(&u32::MAX.to_be_bytes());
        std::fs::write(path.0.join("graph.nodedata"), bytes).unwrap();
    }
    let loaded = Arc::new(Graph::load(&path.0).unwrap());
    (path, loaded)
}

#[test]
fn malformed_domains_and_index_keep_the_original_raw_counts_and_source_order() {
    let Some(graph) = fixture() else { return };
    let ids = graph.ids_by_tag(TAG_CALL_SITE_NODE);
    let mut duplicate = ids.to_vec();
    duplicate.insert(0, ids[0]);
    let mut phantom = ids.to_vec();
    phantom.push(u32::MAX);
    let mut unsorted = ids.to_vec();
    unsorted.reverse();
    let omitted = ids[..ids.len() - 1].to_vec();
    for (changed, broken_index) in [
        (duplicate, false),
        (phantom, false),
        (unsorted, false),
        (omitted, false),
        (vec![], false),
        (ids.to_vec(), true),
    ] {
        let (_path, changed) = variant(&graph, &changed, broken_index, None);
        assert!(changed
            .validated_ordered_call_site(0, || Ok::<_, ()>(()))
            .unwrap()
            .is_none());
        let ex = Executor::new(
            vec![
                Source {
                    id: Arc::from("z-first-raw"),
                    graph: changed,
                },
                Source {
                    id: Arc::from("a-later-indexed"),
                    graph: graph.clone(),
                },
            ],
            true,
        );
        for property in ["caller_class", "callee_class"] {
            let prefix = format!("MATCH (n:CallSite) RETURN n.{property} AS value, count(*) AS c");
            compare(&ex, &prefix);
            compare(&ex, &format!("{prefix} ORDER BY c DESC LIMIT 11"));
        }
    }
}

#[test]
fn invalid_raw_string_ids_keep_selected_field_panic_and_unselected_field_results() {
    let Some(graph) = fixture() else { return };
    let ids = graph.ids_by_tag(TAG_CALL_SITE_NODE);
    let query =
        "MATCH (n:CallSite) RETURN n.caller_class AS value, count(*) AS c ORDER BY c DESC LIMIT 1";
    for field in [0, 1] {
        let (_path, changed) = variant(&graph, ids, false, Some(field));
        assert!(changed
            .validated_ordered_call_site(0, || Ok::<_, ()>(()))
            .unwrap()
            .is_none());
        let ex = Executor::new(
            vec![
                Source {
                    id: Arc::from("a-valid"),
                    graph: graph.clone(),
                },
                Source {
                    id: Arc::from("b-bad"),
                    graph: changed,
                },
            ],
            true,
        );
        if field == 1 {
            compare(&ex, query);
            continue;
        }
        let clauses = parse(query).unwrap();
        let actual = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            grouped_call_site_property(&ex, &clauses)
        }))
        .expect_err("selected malformed SID must keep its old panic");
        let expected = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            grouped_call_site_property_with_counts(&ex, &clauses, raw_call_site_property_counts)
        }))
        .expect_err("raw oracle must panic");
        let text = |panic: Box<dyn std::any::Any + Send>| {
            panic
                .downcast_ref::<String>()
                .cloned()
                .unwrap_or_else(|| panic.downcast_ref::<&str>().unwrap().to_string())
        };
        assert_eq!(text(actual), text(expected));
    }
}

/// Full-source correctness only. No timing or resource observations from this
/// test are performance evidence; measured comparisons use the server workload.
#[test]
fn real64_grouped_counts_preserve_raw_counts_sources_first_ties_and_probes() {
    let Some(manifest) = std::env::var_os("GRAPHITE_GROUPED_REAL64_MANIFEST") else {
        return;
    };
    let manifest = Path::new(&manifest);
    let text = std::fs::read_to_string(manifest).unwrap();
    let mut sources = Vec::new();
    let mut paths = std::collections::BTreeSet::new();
    let mut source_ids = std::collections::BTreeSet::new();
    let mut fingerprints = std::collections::BTreeSet::new();
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
        assert!(source_ids.insert(fields[0].to_owned()));
        assert!(fingerprints.insert(fields[5].to_owned()));
        let graph = Arc::new(Graph::load(&path).unwrap());
        assert!(graph.count_by_tag(TAG_CALL_SITE_NODE) > 0);
        sources.push(Source {
            id: Arc::from(fields[0]),
            graph,
        });
        if sources.len().is_multiple_of(16) {
            eprintln!(
                "grouped correctness: loaded {}/64 real sources",
                sources.len()
            );
        }
    }
    assert_eq!(sources.len(), 64);
    let ex = Executor::new(sources.clone(), true);
    eprintln!("grouped correctness: proving property 2 on all 64 sources");
    for source in &sources {
        assert!(
            source
                .graph
                .validated_ordered_call_site(2, || ex.cancel.check())
                .unwrap()
                .is_some(),
            "{}",
            source.id
        );
    }
    let prefix = "MATCH (n:CallSite) RETURN n.callee_class AS value, count(*) AS c";
    let raw = grouped_call_site_property_with_counts(
        &ex,
        &parse(prefix).unwrap(),
        raw_call_site_property_counts,
    )
    .unwrap()
    .unwrap();
    let expected_total = sources
        .iter()
        .map(|source| source.graph.count_by_tag(TAG_CALL_SITE_NODE) as i64)
        .sum::<i64>();
    let count = |row: &Row| match row["c"] {
        Value::Int(count) => count,
        _ => panic!("integer count required"),
    };
    assert_eq!(raw.rows.iter().map(count).sum::<i64>(), expected_total);
    assert!(raw
        .rows
        .iter()
        .all(|row| !QueryResult::graph_ids(row).is_empty()));
    let first_source = raw_call_site_property_counts(&ex, &sources[0].graph, 2).unwrap();
    let (&first_key, _) = first_source.first().unwrap();
    assert_eq!(
        raw.rows[0]["value"].as_str(),
        Some(sources[0].graph.str(first_key))
    );
    let mut first_by_count = std::collections::HashMap::new();
    let tie = raw
        .rows
        .iter()
        .enumerate()
        .find_map(|(at, row)| {
            first_by_count
                .insert(count(row), at)
                .map(|earlier| (earlier, at))
        })
        .expect("the real workload must exercise equal-count groups");
    let mut receipts = Vec::new();
    for (suffix, order) in [
        ("", "encounter"),
        (" ORDER BY c ASC", "count-asc"),
        (" ORDER BY c DESC", "count-desc"),
    ] {
        let mut ordered = raw.rows.clone();
        match order {
            "count-asc" => ordered.sort_by_key(count),
            "count-desc" => ordered.sort_by_key(|row| std::cmp::Reverse(count(row))),
            _ => {}
        }
        for limit in [None, Some(0usize), Some(1), Some(20)] {
            for probe in [false, true] {
                let query = format!(
                    "{prefix}{suffix}{}",
                    limit.map(|n| format!(" LIMIT {n}")).unwrap_or_default()
                );
                let clauses = parse(&query).unwrap();
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
                // ASC deliberately retains the old general fallback. Other shapes
                // must enter the new CSR counter rather than pass by falling back.
                assert_eq!(
                    grouped_call_site_property(&actual_ex, &clauses)
                        .unwrap()
                        .is_some(),
                    order != "count-asc"
                );
                if limit.is_none() {
                    let position = |original: usize| {
                        actual
                            .rows
                            .iter()
                            .position(|row| {
                                row["value"].as_str() == raw.rows[original]["value"].as_str()
                            })
                            .unwrap()
                    };
                    assert!(
                        position(tie.0) < position(tie.1),
                        "equal counts retain the raw first encounter order"
                    );
                }
                receipts.push(serde_json::json!({
                    "query": query,
                    "probe": probe,
                    "rowCount": actual.rows.len(),
                    "more": actual.more,
                    "order": order,
                    "completeTypedRowsMatchRawOracle": true,
                    "firstEncounterTiesChecked": limit.is_none(),
                }));
            }
        }
        eprintln!(
            "grouped correctness: {order} rows/counts/provenance/ties/probes match raw oracle"
        );
    }
    let receipt = serde_json::json!({
        "correctnessOnly": true,
        "performanceSamplesCollected": false,
        "manifest": manifest,
        "sourceIds": source_ids,
        "distinctGraphPaths": paths,
        "distinctWorkloadFingerprints": fingerprints.len(),
        "validatedProperty": 2,
        "validatedSources": 64,
        "globalGroupCount": raw.rows.len(),
        "totalCount": expected_total,
        "completeRawOracleTypedRows": format!("{:?}", raw.rows),
        "cases": receipts,
    });
    if let Some(output) = std::env::var_os("GRAPHITE_GROUPED_SMOKE_OUTPUT") {
        std::fs::write(output, serde_json::to_vec_pretty(&receipt).unwrap()).unwrap();
    }
}
