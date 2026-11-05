use super::*;
use crate::engine::{CancelToken, Source};
use crate::parser::parse;
use crate::CypherError;
use graphite_storage::Graph;
use std::sync::Arc;

fn admitted(query: &str) -> bool {
    OrderedPropertyPlan::build(&parse(query).unwrap()).is_some()
}

#[test]
fn admission_resolves_aliases_directions_and_literal_bounds() {
    for key in CALL_SITE_PROPERTY_NAMES {
        for direction in ["ASC", "DESC"] {
            let query = format!("MATCH (n:cAlLsItE:CallSiteNode) RETURN n.{key} AS value, n.id AS id, n.graphId AS graph ORDER BY value {direction} SKIP 3 LIMIT 20");
            let clauses = parse(&query).unwrap();
            let plan = OrderedPropertyPlan::build(&clauses).unwrap();
            assert_eq!(
                plan.property,
                CALL_SITE_PROPERTY_NAMES
                    .iter()
                    .position(|p| *p == key)
                    .unwrap()
            );
            assert_eq!(plan.descending, direction == "DESC");
            assert_eq!(plan.columns, ["value", "id", "graph"]);
            assert_eq!(plan.projections, [key, "id", "graphId"]);
            assert_eq!((plan.skip, plan.limit), (3, 20));
        }
    }
    assert!(admitted(
        "MATCH (n:CallSite) RETURN n.caller_class ORDER BY n.caller_class LIMIT 0"
    ));
    assert!(admitted(
        "MATCH (n:CallSite) RETURN n.caller_class ORDER BY n.caller_class LIMIT 65536"
    ));
    for query in [
        "MATCH (n:CallSite:Field) RETURN n.caller_class ORDER BY n.caller_class LIMIT 1",
        "MATCH (n:CallSite {id: 1}) RETURN n.caller_class ORDER BY n.caller_class LIMIT 1",
        "MATCH (n:CallSite) WHERE true RETURN n.caller_class ORDER BY n.caller_class LIMIT 1",
        "MATCH (n:CallSite) RETURN DISTINCT n.caller_class ORDER BY n.caller_class LIMIT 1",
        "MATCH (n:CallSite) RETURN * ORDER BY n.caller_class LIMIT 1",
        "MATCH (n:CallSite) RETURN n.caller_class AS x, n.id AS x ORDER BY x LIMIT 1",
        "MATCH (n:CallSite) RETURN n.caller_class ORDER BY n.caller_class LIMIT -1",
        "MATCH (n:CallSite) RETURN n.caller_class ORDER BY n.caller_class LIMIT $limit",
        "MATCH (n:CallSite) RETURN n.caller_class ORDER BY n.caller_class SKIP -1 LIMIT 1",
        "MATCH (n:CallSite) RETURN n.caller_class ORDER BY n.caller_class SKIP 1 LIMIT 65536",
        "MATCH (n:CallSite) RETURN n.caller_class ORDER BY n.caller_class, n.id LIMIT 1",
        "MATCH (n:CallSite) RETURN unknown() ORDER BY n.caller_class LIMIT 1",
        "MATCH (n:CallSite) RETURN n.caller_class ORDER BY n.caller_signature LIMIT 1",
        "MATCH (n:CallSite) RETURN n.caller_class WITH n ORDER BY n.caller_class LIMIT 1",
        "OPTIONAL MATCH (n:CallSite) RETURN n.caller_class ORDER BY n.caller_class LIMIT 1",
        "MATCH (n:CallSite)-[]->(m) RETURN n.caller_class ORDER BY n.caller_class LIMIT 1",
    ] {
        assert!(!admitted(query), "{query}");
    }
}

#[test]
fn heap_keeps_utf16_and_source_encounter_ties_in_both_directions() {
    for descending in [false, true] {
        let values = ["\u{e000}", "\u{10000}", "a", "a", "a"];
        let mut heap = BinaryHeap::new();
        for (i, value) in values.iter().enumerate() {
            heap.push(Head {
                cursor: i,
                source: if i == 4 { 0 } else { 1 },
                node: i as u32,
                value,
                descending,
            });
        }
        let actual: Vec<_> = std::iter::from_fn(|| heap.pop())
            .map(|h| (h.value, h.source, h.node))
            .collect();
        let mut expected: Vec<_> = values
            .iter()
            .enumerate()
            .map(|(i, value)| (*value, if i == 4 { 0 } else { 1 }, i as u32))
            .collect();
        expected.sort_by(|a, b| {
            let value = compare_utf16(a.0, b.0);
            (if descending { value.reverse() } else { value })
                .then(a.1.cmp(&b.1))
                .then(a.2.cmp(&b.2))
        });
        assert_eq!(actual, expected);
        let equal: Vec<_> = actual
            .iter()
            .filter(|r| r.0 == "a")
            .map(|r| (r.1, r.2))
            .collect();
        assert_eq!(equal, [(0, 4), (1, 2), (1, 3)]);
    }
}

fn fixture() -> Option<Arc<Graph>> {
    let Some(path) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
        assert!(std::env::var_os("GRAPHITE_REQUIRE_ALL_TYPES_FIXTURES").is_none());
        return None;
    };
    Some(Arc::new(Graph::load(std::path::Path::new(&path)).unwrap()))
}

fn compare(ex: &Executor, query: &str) -> QueryResult {
    let clauses = parse(query).unwrap();
    assert!(OrderedPropertyPlan::build(&clauses)
        .unwrap()
        .run(ex)
        .unwrap()
        .is_some());
    let actual = ex.execute(query, None).unwrap();
    let mut oracle = clauses.clone();
    oracle.insert(1, Clause::Where(Expr::Literal(Literal::Bool(true))));
    assert!(OrderedPropertyPlan::build(&oracle).is_none());
    let expected = ex.execute_clauses(&oracle, None).unwrap();
    assert_eq!(actual.columns, expected.columns, "{query}");
    assert_eq!(
        format!("{:?}", actual.rows),
        format!("{:?}", expected.rows),
        "{query}"
    );
    assert_eq!(actual.more, expected.more, "{query}");
    actual
}

#[test]
fn real_multigraph_ordered_rows_ties_provenance_and_probe_match_full_scan() {
    let Some(graph) = fixture() else { return };
    let sources = vec![
        Source {
            id: Arc::from("z-first"),
            graph: graph.clone(),
        },
        Source {
            id: Arc::from("a-second"),
            graph: graph.clone(),
        },
    ];
    for cross in [false, true] {
        let ex = Executor::new(
            if cross {
                sources.clone()
            } else {
                vec![sources[0].clone()]
            },
            cross,
        )
        .with_probe();
        for key in CALL_SITE_PROPERTY_NAMES {
            for direction in ["ASC", "DESC"] {
                let query = format!("MATCH (n:CallSite) RETURN n.{key} AS value, n.id AS id, n.graphId AS graph, n.line AS line, n.ordinal AS ordinal ORDER BY value {direction} SKIP 2 LIMIT 13");
                let result = compare(&ex, &query);
                assert_eq!(result.rows.len(), 13);
                assert!(result.more);
                assert!(result.rows.iter().all(|row| if cross {
                    !QueryResult::graph_ids(row).is_empty()
                } else {
                    QueryResult::graph_ids(row).is_empty() && row["graph"].is_null()
                }));
            }
        }
        let empty = compare(
            &ex,
            "MATCH (n:CallSite) RETURN n.callee_name ORDER BY n.callee_name LIMIT 0",
        );
        assert!(empty.rows.is_empty());
        assert!(empty.more); // The route's probe still consumes one extra row.
    }
    let cancel = CancelToken::new();
    cancel.cancel();
    let ex = Executor::new(sources, true).with_cancel(cancel);
    assert!(matches!(
        ex.execute(
            "MATCH (n:CallSite) RETURN n.caller_class ORDER BY n.caller_class LIMIT 1",
            None
        ),
        Err(CypherError::Cancelled)
    ));
}

/// Optional full-source correctness gate. It intentionally records no timings and
/// supplies no performance evidence; local acceptance runs retain its receipt.
#[test]
fn real64_ordered_sources_match_global_generic_sort() {
    let Some(manifest) = std::env::var_os("GRAPHITE_ORDERED_REAL64_MANIFEST") else {
        return;
    };
    let manifest = std::path::Path::new(&manifest);
    let text = std::fs::read_to_string(manifest).unwrap();
    let mut sources = Vec::new();
    let mut paths = std::collections::BTreeSet::new();
    let mut ids = std::collections::BTreeSet::new();
    let mut fingerprints = std::collections::BTreeSet::new();
    for line in text
        .lines()
        .filter(|line| !line.trim().is_empty() && !line.starts_with('#'))
    {
        let fields: Vec<_> = line.split('\t').collect();
        assert_eq!(fields.len(), 6);
        let path = std::path::Path::new(fields[1]);
        let path = if path.is_absolute() {
            path.to_path_buf()
        } else {
            manifest.parent().unwrap().join(path)
        }
        .canonicalize()
        .unwrap();
        assert!(
            paths.insert(path.clone()),
            "each source must be a distinct persisted graph"
        );
        assert!(ids.insert(fields[0].to_owned()));
        assert!(fingerprints.insert(fields[5].to_owned()));
        let graph = Arc::new(Graph::load(&path).unwrap());
        assert!(graph.count_by_tag(TAG_CALL_SITE_NODE) > 0);
        sources.push(Source {
            id: Arc::from(fields[0]),
            graph,
        });
        if sources.len() % 16 == 0 {
            eprintln!(
                "ordered correctness: loaded {}/64 real sources",
                sources.len()
            );
        }
    }
    assert_eq!(sources.len(), 64);
    let ex = Executor::new(sources, true).with_probe();
    let mut cases = Vec::new();
    for (property, direction) in [(0, "ASC"), (3, "DESC")] {
        let key = CALL_SITE_PROPERTY_NAMES[property];
        eprintln!("ordered correctness: proving all 64 {key} sources");
        for source in &ex.sources {
            assert!(
                source
                    .graph
                    .validated_ordered_call_site(property, || ex.cancel.check())
                    .unwrap()
                    .is_some(),
                "{} must admit the ordered source proof",
                source.id
            );
        }
        eprintln!(
            "ordered correctness: {key} proofs complete; comparing full indexed/generic results"
        );
        let query = format!("MATCH (n:CallSite) RETURN n.{key} AS value, n.id AS id, n.graphId AS graph, n.line AS line, n.ordinal AS ordinal ORDER BY value {direction} SKIP 3 LIMIT 200");
        // compare requires the actual plan to return Some, then independently
        // compares complete typed rows against a WHERE-true generic scan/sort.
        let result = compare(&ex, &query);
        eprintln!("ordered correctness: {key} {direction} typed results match");
        assert_eq!(result.rows.len(), 200);
        assert!(result.more);
        cases.push(serde_json::json!({
            "query": query,
            "columns": result.columns,
            "rowCount": result.rows.len(),
            "more": result.more,
            "validatedOrderedSources": 64,
            "typedRows": format!("{:?}", result.rows),
            "rowGraphIds": result.rows.iter().map(QueryResult::graph_ids).collect::<Vec<_>>(),
            "matchesGenericTypedRowsAndProvenance": true,
        }));
    }
    let receipt = serde_json::json!({
        "correctnessOnly": true,
        "performanceSamplesCollected": false,
        "manifest": manifest,
        "sourceIds": ids,
        "distinctGraphPaths": paths,
        "distinctWorkloadFingerprints": fingerprints.len(),
        "cases": cases,
    });
    if let Some(output) = std::env::var_os("GRAPHITE_ORDERED_SMOKE_OUTPUT") {
        std::fs::write(output, serde_json::to_vec_pretty(&receipt).unwrap()).unwrap();
    }
    eprintln!("64 distinct real sources admitted ordered proofs; both complete global results match the generic oracle");
}

#[test]
fn malformed_later_source_falls_back_without_changing_rows_or_provenance() {
    let Some(graph) = fixture() else { return };
    let source = std::path::Path::new(&graph.dir);
    let ids = graph.ids_by_tag(TAG_CALL_SITE_NODE);
    let mut duplicate = ids.to_vec();
    duplicate.insert(0, ids[0]);
    let mut phantom = ids.to_vec();
    *phantom.last_mut().unwrap() = u32::MAX;
    let mut omitted = ids.to_vec();
    omitted.pop();
    let mut unsorted = ids.to_vec();
    unsorted.reverse();
    for (variant, changed) in [duplicate, phantom, omitted, unsorted, vec![]]
        .into_iter()
        .enumerate()
    {
        let directory = std::env::temp_dir().join(format!(
            "graphite-ordered-fallback-{}-{variant}",
            std::process::id()
        ));
        std::fs::create_dir(&directory).unwrap();
        for entry in std::fs::read_dir(source).unwrap() {
            let entry = entry.unwrap();
            if entry.file_type().unwrap().is_file() && entry.file_name() != "graph.typeindex" {
                std::fs::hard_link(entry.path(), directory.join(entry.file_name())).unwrap();
            }
        }
        let original = std::fs::read(source.join("graph.typeindex")).unwrap();
        let mut bytes = original[..4].to_vec();
        bytes.extend(1i32.to_be_bytes());
        bytes.push(TAG_CALL_SITE_NODE);
        bytes.extend((changed.len() as i32).to_be_bytes());
        bytes.extend(21i64.to_be_bytes());
        let empty_domain = changed.is_empty();
        for id in changed {
            bytes.extend(id.to_be_bytes());
        }
        std::fs::write(directory.join("graph.typeindex"), bytes).unwrap();
        let malformed = Arc::new(Graph::load(&directory).unwrap());
        let ex = Executor::new(
            vec![
                Source {
                    id: Arc::from("first-valid"),
                    graph: graph.clone(),
                },
                Source {
                    id: Arc::from("later-malformed"),
                    graph: malformed,
                },
            ],
            true,
        )
        .with_probe();
        let query = "MATCH (n:CallSite) RETURN n.caller_class AS value, n.id AS id, n.graphId AS graph ORDER BY value LIMIT 7";
        let clauses = parse(query).unwrap();
        assert_eq!(
            OrderedPropertyPlan::build(&clauses)
                .unwrap()
                .run(&ex)
                .unwrap()
                .is_some(),
            empty_domain
        );
        let actual = ex.execute(query, None).unwrap();
        let mut oracle = clauses;
        oracle.insert(1, Clause::Where(Expr::Literal(Literal::Bool(true))));
        let expected = ex.execute_clauses(&oracle, None).unwrap();
        assert_eq!(
            format!("{:?}", actual.rows),
            format!("{:?}", expected.rows),
            "variant {variant}"
        );
        assert_eq!(actual.more, expected.more);
        assert_eq!(actual.rows.len(), 7);
        drop(ex);
        std::fs::remove_dir_all(directory).unwrap();
    }
}

#[test]
fn late_invalid_extra_projection_id_preserves_the_generic_panic() {
    let Some(graph) = fixture() else { return };
    let directory = std::env::temp_dir().join(format!(
        "graphite-ordered-invalid-sid-{}",
        std::process::id()
    ));
    std::fs::create_dir(&directory).unwrap();
    for entry in std::fs::read_dir(&graph.dir).unwrap() {
        let entry = entry.unwrap();
        if entry.file_type().unwrap().is_file() && entry.file_name() != "graph.nodedata" {
            std::fs::hard_link(entry.path(), directory.join(entry.file_name())).unwrap();
        }
    }
    let id = *graph.ids_by_tag(TAG_CALL_SITE_NODE).last().unwrap();
    let offset = graph.node_offset(id).unwrap();
    let mut bytes = graph.nodedata().to_vec();
    bytes[offset + graphite_storage::node::NODE_HEADER_BYTES + 4
        ..offset + graphite_storage::node::NODE_HEADER_BYTES + 8]
        .copy_from_slice(&u32::MAX.to_be_bytes());
    std::fs::write(directory.join("graph.nodedata"), bytes).unwrap();
    let bad = Arc::new(Graph::load(&directory).unwrap());
    let ex = Executor::new(
        vec![
            Source {
                id: Arc::from("first-valid"),
                graph,
            },
            Source {
                id: Arc::from("later-invalid"),
                graph: bad,
            },
        ],
        true,
    );
    let query = "MATCH (n:CallSite) RETURN n.caller_class AS key, n.caller_name AS name ORDER BY key LIMIT 1";
    let clauses = parse(query).unwrap();
    assert!(OrderedPropertyPlan::build(&clauses)
        .unwrap()
        .run(&ex)
        .unwrap()
        .is_none());
    let actual = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| ex.execute(query, None)))
        .expect_err("late malformed field must not be hidden by LIMIT");
    let mut oracle = clauses;
    oracle.insert(1, Clause::Where(Expr::Literal(Literal::Bool(true))));
    let expected = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        ex.execute_clauses(&oracle, None)
    }))
    .expect_err("oracle must encounter the same malformed field");
    let text = |panic: Box<dyn std::any::Any + Send>| {
        panic
            .downcast_ref::<String>()
            .cloned()
            .unwrap_or_else(|| panic.downcast_ref::<&str>().unwrap().to_string())
    };
    let actual = text(actual);
    let expected = text(expected);
    assert_eq!(actual, expected);
    assert!(actual.contains("4294967295"));
    drop(ex);
    std::fs::remove_dir_all(directory).unwrap();
}
