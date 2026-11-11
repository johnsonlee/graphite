//! Deterministic correctness fixtures only, never performance measurements.
use super::*;
use crate::engine::{CancelToken, Source};
use crate::parser::parse;
use crate::property_graph::PropertyGraph;
use crate::CypherDialect;
use std::sync::atomic::Ordering as AtomicOrdering;

const WITH: &str =
    "OPTIONAL MATCH (a)-[:DATAFLOW]->(b) WITH a.graphId AS graph, a AS source, count(b) AS degree";

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

fn fixture(dialect: CypherDialect) -> Executor {
    let ex = Executor::property_graph(Arc::new(PropertyGraph::new())).with_dialect(dialect);
    ex.execute(
        "CREATE (a:CallSite {graphId:'g'}), (b:CallSite {graphId:'g'}),
        (c:CallSite {graphId:'g'}), (d:CallSite {graphId:'g'}),
        (a)-[:DATAFLOW]->(b), (a)-[:DATAFLOW]->(b), (a)-[:DATAFLOW]->(c),
        (b)-[:DATAFLOW]->(b), (d)-[:CALL]->(a)",
        None,
    )
    .unwrap();
    ex
}

fn same(actual: &QueryResult, expected: &QueryResult) {
    assert_eq!(actual.columns, expected.columns);
    assert_eq!(format!("{:?}", actual.rows), format!("{:?}", expected.rows));
    assert_eq!(actual.more, expected.more);
    assert!(actual.compact.is_none());
}

fn compare(ex: &Executor, query: &str, admitted: bool) -> QueryResult {
    let before = HITS.with(|hits| hits.get());
    let actual = ex.execute(query, None).unwrap();
    assert_eq!(HITS.with(|hits| hits.get()) > before, admitted, "{query}");
    same(&actual, &original(|| ex.execute(query, None)).unwrap());
    actual
}

#[test]
fn optional_counts_keep_parallel_edges_self_loops_and_zero_degree_nodes() {
    for dialect in [CypherDialect::Graphite, CypherDialect::OpenCypherM23] {
        let ex = fixture(dialect);
        let result = compare(
            &ex,
            &format!("MATCH(a:CallSite) {WITH} RETURN graph, source, degree"),
            true,
        );
        assert_eq!(result.rows.len(), 4);
        let degree: Vec<_> = result
            .rows
            .iter()
            .map(|row| {
                assert_eq!(row["graph"].as_str(), Some("g"));
                let Value::Int(n) = row["degree"] else {
                    panic!("count must be integer")
                };
                n
            })
            .collect();
        assert_eq!(degree, [3, 1, 0, 0]);
        let query = format!("MATCH(a:CallSite) {WITH} RETURN graph, count(*) AS sources, sum(degree) AS edges, sum(CASE WHEN degree = 0 THEN 1 ELSE 0 END) AS sinks");
        let result = compare(&ex, &query, true);
        assert_eq!(result.rows.len(), 1);
        assert!(matches!(result.rows[0]["sources"], Value::Int(4)));
        match dialect {
            CypherDialect::Graphite => {
                assert!(matches!(result.rows[0]["edges"], Value::Float(4.0)));
                assert!(matches!(result.rows[0]["sinks"], Value::Float(2.0)));
            }
            CypherDialect::OpenCypherM23 => {
                assert!(matches!(result.rows[0]["edges"], Value::Int(4)));
                assert!(matches!(result.rows[0]["sinks"], Value::Int(2)));
            }
        }
        let result = compare(&ex, "MATCH(a:CallSite) OPTIONAL MATCH(a)-[:DATAFLOW]->(b) WITH count(*) AS rows, count(b) AS edges RETURN rows, edges", true);
        assert!(matches!(result.rows[0]["rows"], Value::Int(6)));
        assert!(matches!(result.rows[0]["edges"], Value::Int(4)));
    }
}

#[test]
fn empty_input_aliases_order_probe_and_downstream_clauses_keep_original_results() {
    for dialect in [CypherDialect::Graphite, CypherDialect::OpenCypherM23] {
        let ex = fixture(dialect).with_probe();
        let query =
            format!("MATCH(a:CallSite) {WITH} ORDER BY degree DESC RETURN graph, degree LIMIT 2");
        let result = compare(&ex, &query, true);
        assert_eq!(result.rows.len(), 2);
        assert!(result.more);
        assert!(matches!(result.rows[0]["degree"], Value::Int(3)));
        compare(
            &ex,
            &format!("MATCH(a:CallSite) {WITH} RETURN degree LIMIT 0"),
            true,
        );
        let result = compare(&ex, "MATCH(a:CallSite) WHERE false OPTIONAL MATCH(a)-[:DATAFLOW]->(b) WITH count(*) AS n RETURN n", true);
        assert!(matches!(result.rows[0]["n"], Value::Int(0)));
        let result = compare(
            &ex,
            &format!("MATCH(a:CallSite) WHERE false {WITH} RETURN degree"),
            true,
        );
        assert!(result.rows.is_empty());
        let result = compare(&ex, "MATCH(a:CallSite) OPTIONAL MATCH(a)-[:DATAFLOW]->(b) WITH a AS b, count(b) AS a RETURN a ORDER BY a DESC", true);
        assert!(matches!(result.rows[0]["a"], Value::Int(3)));
    }
}

#[test]
fn unsafe_shapes_and_late_errors_keep_original_clause_boundaries() {
    let ex = fixture(CypherDialect::Graphite);
    for query in [
        "MATCH(a:CallSite) OPTIONAL MATCH(a)-[:DATAFLOW]->(b) WHERE false WITH a, count(b) AS n RETURN n",
        "MATCH(a:CallSite) OPTIONAL MATCH(a)-[:DATAFLOW]->(b) WITH a, count(b) AS n WHERE n > 0 RETURN n",
        "MATCH(a:CallSite) OPTIONAL MATCH(a)-[:DATAFLOW]->(b:CallSite) WITH a, count(b) AS n RETURN n",
        "MATCH(a:CallSite) OPTIONAL MATCH(a)-[:CALL]->(b) WITH a, count(b) AS n RETURN n",
        "MATCH(a:CallSite) OPTIONAL MATCH(a)-[:DATAFLOW]->(b) WITH a, count(DISTINCT b) AS n RETURN n",
        "MATCH(a:CallSite), (b:CallSite) OPTIONAL MATCH(a)-[:DATAFLOW]->(b) WITH a, count(b) AS n RETURN n",
        "WITH null AS a OPTIONAL MATCH(a)-[:DATAFLOW]->(b) WITH a, count(b) AS n RETURN n",
    ] { compare(&ex, query, false); }
    for query in [
        "MATCH(a:CallSite) OPTIONAL MATCH(a)-[:DATAFLOW]->(b) WITH a, count(1 / 0) AS n RETURN n LIMIT 0",
        "MATCH(a:CallSite) OPTIONAL MATCH(a)-[:DATAFLOW]->(b) WITH a, count(b) AS n RETURN 1 / 0 AS error LIMIT 0",
    ] {
        let actual = ex.execute(query, None);
        let expected = original(|| ex.execute(query, None));
        assert_eq!(format!("{actual:?}"), format!("{expected:?}"));
        assert!(actual.is_err());
    }
}

#[test]
fn duplicated_seeds_and_provenance_are_not_deduplicated_before_aggregation() {
    let ex = fixture(CypherDialect::OpenCypherM23);
    let nodes = ex
        .execute("MATCH(a:CallSite) RETURN a LIMIT 1", None)
        .unwrap();
    let mut first = nodes.rows[0].clone();
    first.insert(
        INTERNAL_PROVENANCE_KEY.into(),
        Value::list(vec![Value::str("z")]),
    );
    let mut second = first.clone();
    second.insert(
        INTERNAL_PROVENANCE_KEY.into(),
        Value::list(vec![Value::str("a")]),
    );
    let clauses = parse(&format!("{WITH} RETURN graph, degree")).unwrap();
    let before = HITS.with(|hits| hits.get());
    let actual = ex
        .run_segment_seeded(&clauses, vec![first.clone(), second.clone()])
        .unwrap();
    assert!(HITS.with(|hits| hits.get()) > before);
    let expected = original(|| ex.run_segment_seeded(&clauses, vec![first, second])).unwrap();
    same(&actual, &expected);
    assert_eq!(actual.rows.len(), 1);
    assert!(matches!(actual.rows[0]["degree"], Value::Int(6)));
    assert_eq!(QueryResult::graph_ids(&actual.rows[0]), ["a", "z"]);
}

#[test]
fn parent_budgets_and_cancellation_keep_general_behavior() {
    let query = format!("MATCH(a:CallSite) {WITH} RETURN degree");
    for budget in [1, 10, 100_000] {
        let parent = || CancelToken::child_with_limits(CancelToken::new(), 60_000, budget);
        let fast_parent = parent();
        let slow_parent = parent();
        let executor = |parent| {
            fixture(CypherDialect::Graphite).with_cancel(Arc::new(CancelToken {
                parent: Some(parent),
                ..CancelToken::default()
            }))
        };
        let fast = executor(fast_parent.clone());
        let slow = executor(slow_parent.clone());
        let before = HITS.with(|hits| hits.get());
        let actual = fast.execute(&query, None);
        let expected = original(|| slow.execute(&query, None));
        assert_eq!(format!("{actual:?}"), format!("{expected:?}"));
        assert_eq!(HITS.with(|hits| hits.get()), before);
        assert_eq!(
            fast_parent
                .work
                .as_ref()
                .unwrap()
                .consumed
                .load(AtomicOrdering::Relaxed),
            slow_parent
                .work
                .as_ref()
                .unwrap()
                .consumed
                .load(AtomicOrdering::Relaxed)
        );
    }
    let token = CancelToken::new();
    token.cancel();
    let ex = fixture(CypherDialect::Graphite).with_cancel(token);
    assert!(matches!(
        ex.execute(&query, None),
        Err(CypherError::Cancelled)
    ));
}

#[test]
fn native_optional_counts_preserve_multi_source_identity_and_provenance() {
    let Some(path) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
        assert!(std::env::var_os("GRAPHITE_REQUIRE_ALL_TYPES_FIXTURES").is_none());
        return;
    };
    let graph = Arc::new(graphite_storage::Graph::load(std::path::Path::new(&path)).unwrap());
    for dialect in [CypherDialect::Graphite, CypherDialect::OpenCypherM23] {
        let ex = Executor::new(
            ["z", "a"]
                .into_iter()
                .map(|id| Source {
                    id: Arc::from(id),
                    graph: graph.clone(),
                })
                .collect(),
            true,
        )
        .with_dialect(dialect);
        // Bound only the correctness fixture; the benchmark catalog has no such limit.
        let rows: Vec<_> = ["z", "a"]
            .into_iter()
            .flat_map(|id| {
                ex.execute(
                    &format!("MATCH(a:CallSite) WHERE a.graphId = '{id}' RETURN a LIMIT 4"),
                    None,
                )
                .unwrap()
                .rows
            })
            .collect();
        let clauses = parse(&format!("{WITH} RETURN graph, source, degree")).unwrap();
        assert!(
            Plan::build(&clauses)
                .unwrap()
                .total_inputs(&ex, &rows)
                .unwrap(),
            "rows={rows:?}"
        );
        let before = HITS.with(|hits| hits.get());
        let compact_before = COMPACT_HITS.with(|hits| hits.get());
        let actual = ex.run_segment_seeded(&clauses, rows.clone()).unwrap();
        assert!(HITS.with(|hits| hits.get()) > before);
        assert!(COMPACT_HITS.with(|hits| hits.get()) > compact_before);
        same(
            &actual,
            &streamed(|| ex.run_segment_seeded(&clauses, rows.clone())).unwrap(),
        );
        let expected = original(|| ex.run_segment_seeded(&clauses, rows)).unwrap();
        same(&actual, &expected);
        assert_eq!(actual.rows.len(), 8);
        for row in actual.rows {
            assert_eq!(
                QueryResult::graph_ids(&row),
                [row["graph"].as_str().unwrap()]
            );
        }
    }
}

fn streamed<T>(run: impl FnOnce() -> T) -> T {
    struct Reset(bool);
    impl Drop for Reset {
        fn drop(&mut self) {
            COMPACT_DISABLED.with(|flag| flag.set(self.0));
        }
    }
    let _reset = Reset(COMPACT_DISABLED.with(|flag| flag.replace(true)));
    run()
}

#[test]
fn compact_native_counts_keep_sinks_duplicates_source_identity_and_provenance() {
    // Tiny persisted graph solely for semantic parity; never a timing fixture.
    let path = std::env::temp_dir().join(format!(
        "graphite-optional-compact-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    let data = br#"{"version":1,"methods":[],"nodes":[
        {"id":0,"kind":"IntConstant","value":0},
        {"id":1,"kind":"IntConstant","value":1},
        {"id":2,"kind":"IntConstant","value":2}],"edges":[
        {"from":0,"to":0,"kind":"ASSIGN"},
        {"from":0,"to":1,"kind":"ASSIGN"},
        {"from":1,"to":2,"kind":"ASSIGN"}]}"#;
    graphite_storage::interchange::import_json(&data[..], &path).unwrap();
    let mut graph = graphite_storage::Graph::load(&path).unwrap();
    // Persisted legacy adjacency can contain duplicate entries, whereas the
    // importer normalizes them. Preserve that multiplicity in the original walk.
    let end = graph.forward.offsets[1] as usize;
    let label = graph.forward.labels[end - 1];
    graph.forward.targets.insert(end, 1);
    graph.forward.labels.insert(end, label);
    for offset in &mut graph.forward.offsets[1..] {
        *offset += 1;
    }
    let graph = Arc::new(graph);
    for dialect in [CypherDialect::Graphite, CypherDialect::OpenCypherM23] {
        let ex = Executor::new(
            (0..2)
                .map(|_| Source {
                    id: Arc::from("same"),
                    graph: graph.clone(),
                })
                .collect(),
            true,
        )
        .with_dialect(dialect);
        let seed = |source, id, provenance: Option<&str>| {
            let mut row = Row::from([("a".into(), Value::Node(NodeRef { source, id }))]);
            if let Some(id) = provenance {
                add_provenance_id(&mut row, Arc::from(id));
            }
            row
        };
        let seeds = vec![
            seed(1, 0, Some("z")),
            seed(0, 0, None),
            seed(1, 0, Some("a")),
            seed(1, 2, None),
            seed(1, 2, None),
        ];
        let query = format!("{WITH} RETURN graph, source, degree");
        let clauses = parse(&query).unwrap();
        let before = COMPACT_HITS.with(|hits| hits.get());
        let result = ex.run_segment_seeded(&clauses, seeds.clone()).unwrap();
        assert!(COMPACT_HITS.with(|hits| hits.get()) > before);
        same(
            &result,
            &streamed(|| ex.run_segment_seeded(&clauses, seeds.clone())).unwrap(),
        );
        same(
            &result,
            &original(|| ex.run_segment_seeded(&clauses, seeds.clone())).unwrap(),
        );
        assert_eq!(result.rows.len(), 3);
        for (row, (source, id, degree)) in result.rows.iter().zip([(1, 0, 6), (0, 0, 3), (1, 2, 0)])
        {
            assert_eq!(row["graph"].as_str(), Some("same"));
            assert!(
                matches!(row["source"], Value::Node(node) if node.source == source && node.id == id)
            );
            assert!(matches!(row["degree"], Value::Int(n) if n == degree));
        }
        assert_eq!(QueryResult::graph_ids(&result.rows[0]), ["a", "same", "z"]);
        assert_eq!(QueryResult::graph_ids(&result.rows[1]), ["same"]);
        assert!(!result.rows[2].contains_key(INTERNAL_PROVENANCE_KEY));
        for tail in [
            "RETURN degree LIMIT 0",
            "RETURN graph, source, degree ORDER BY degree DESC LIMIT 1",
        ] {
            let clauses = parse(&format!("{WITH} {tail}")).unwrap();
            let actual = ex.run_segment_seeded(&clauses, seeds.clone()).unwrap();
            same(
                &actual,
                &original(|| ex.run_segment_seeded(&clauses, seeds.clone())).unwrap(),
            );
        }
        let clauses = parse(&format!("{WITH} RETURN degree")).unwrap();
        let actual = ex.run_segment_seeded(&clauses, vec![]).unwrap();
        assert!(actual.rows.is_empty());
        same(
            &actual,
            &original(|| ex.run_segment_seeded(&clauses, vec![])).unwrap(),
        );
        let aliases = WITH
            .replace("AS graph", "AS a")
            .replace("AS source", "AS b");
        let clauses = parse(&format!("{aliases} RETURN a, b, degree")).unwrap();
        let actual = ex.run_segment_seeded(&clauses, seeds.clone()).unwrap();
        same(
            &actual,
            &original(|| ex.run_segment_seeded(&clauses, seeds.clone())).unwrap(),
        );
        for limit in [1, 1000] {
            let token = || CancelToken::child_with_limits(CancelToken::new(), 60_000, limit);
            let fast_token = token();
            let slow_token = token();
            let bounded = |parent| {
                Executor::new(
                    (0..2)
                        .map(|_| Source {
                            id: Arc::from("same"),
                            graph: graph.clone(),
                        })
                        .collect(),
                    true,
                )
                .with_dialect(dialect)
                .with_cancel(Arc::new(CancelToken {
                    parent: Some(parent),
                    ..CancelToken::default()
                }))
            };
            let before = COMPACT_HITS.with(|hits| hits.get());
            let actual = bounded(fast_token.clone()).run_segment_seeded(&clauses, seeds.clone());
            let expected = original(|| {
                bounded(slow_token.clone()).run_segment_seeded(&clauses, seeds.clone())
            });
            assert_eq!(format!("{actual:?}"), format!("{expected:?}"));
            assert_eq!(COMPACT_HITS.with(|hits| hits.get()), before);
            assert_eq!(
                fast_token
                    .work
                    .as_ref()
                    .unwrap()
                    .consumed
                    .load(AtomicOrdering::Relaxed),
                slow_token
                    .work
                    .as_ref()
                    .unwrap()
                    .consumed
                    .load(AtomicOrdering::Relaxed)
            );
        }
        let token = CancelToken::new();
        token.cancel();
        assert!(matches!(
            ex.with_cancel(token).run_segment_seeded(&clauses, seeds),
            Err(CypherError::Cancelled)
        ));
    }
    drop(graph);
    std::fs::remove_dir_all(path).unwrap();
}

#[test]
fn compact_admission_preserves_other_streamed_optional_shapes() {
    let ex = Executor::new(vec![], true);
    let clauses = parse(&format!("{WITH} RETURN degree")).unwrap();
    assert!(CompactGroups::build(&ex, &Plan::build(&clauses).unwrap()).is_some());
    for query in [
        WITH.replace("count(b)", "count(*)"),
        WITH.replace(
            "a.graphId AS graph, a AS source",
            "a AS source, a.graphId AS graph",
        ),
        format!("{WITH} ORDER BY degree"),
        WITH.replace("a.graphId AS graph, ", ""),
    ] {
        let clauses = parse(&query).unwrap();
        let plan = Plan::build(&clauses).unwrap();
        assert!(CompactGroups::build(&ex, &plan).is_none(), "{query}");
    }
    let ex = fixture(CypherDialect::Graphite);
    assert!(CompactGroups::build(&ex, &Plan::build(&clauses).unwrap()).is_none());
}

#[test]
fn compact_optional_finalizes_zero_and_overflow_groups_and_checks_cancellation() {
    let ex = Executor::new(vec![], true);
    let clauses = parse(&format!("{WITH} RETURN degree LIMIT 0")).unwrap();
    let plan = Plan::build(&clauses).unwrap();
    let mut groups = CompactGroups::build(&ex, &plan).unwrap();
    let mut count = DeferredCount::default();
    count.add(i64::MAX);
    count.add(1);
    groups.groups.insert(
        NodeRef { source: 0, id: 0 },
        CompactGroup {
            graph: Value::str("g"),
            count: DeferredCount::default(),
            provenance: Row::new(),
        },
    );
    groups.groups.insert(
        NodeRef { source: 0, id: 1 },
        CompactGroup {
            graph: Value::str("g"),
            count,
            provenance: Row::new(),
        },
    );
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| groups.finish(&ex)));
    if cfg!(debug_assertions) {
        assert!(result.is_err());
    } else {
        let rows = result.unwrap().unwrap().1;
        assert!(matches!(rows[0]["degree"], Value::Int(0)));
        assert!(matches!(rows[1]["degree"], Value::Int(i64::MIN)));
    }
    let token = CancelToken::new();
    token.cancel();
    let cancelled = Executor::new(vec![], true).with_cancel(token);
    let groups = CompactGroups::build(&cancelled, &plan).unwrap();
    assert!(matches!(
        groups.finish(&cancelled),
        Err(CypherError::Cancelled)
    ));
}
