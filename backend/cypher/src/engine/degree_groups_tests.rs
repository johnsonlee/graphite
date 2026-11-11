//! Synthetic states are correctness checks only; persisted fixture parity exercises traversal.
use super::*;
use crate::engine::{CancelToken, Source};
use crate::parser::parse;
use crate::CypherDialect;

const QUERY: &str = "MATCH (a:CallSite)-[:DATAFLOW]->(b) WITH a.graphId AS graph, a AS source, count(*) AS degree RETURN graph, degree, count(*) AS sources ORDER BY graph, degree LIMIT 100";

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

fn admits(ex: &Executor, query: &str, seeds: &[Row]) -> bool {
    let clauses = parse(query).unwrap();
    let Clause::Match { patterns, .. } = &clauses[0] else {
        panic!("match")
    };
    let Some((shape, _, _)) = FusedShape::detect_with(&clauses) else {
        return false;
    };
    DegreeGroups::build(ex, shape.items.as_deref().unwrap(), &shape, patterns, seeds).is_some()
}

#[test]
fn degree_shape_declines_unproved_aggregate_binding_filter_and_budget() {
    let ex = Executor::new(vec![], true);
    assert!(admits(&ex, QUERY, &[Row::new()]));
    for query in [
        QUERY.replace("count(*) AS degree", "count(b) AS degree"),
        QUERY.replace("a.graphId", "b.graphId"),
        QUERY.replace("a AS source", "b AS source"),
        QUERY.replace("(b)", "(a)"),
        QUERY.replace(":DATAFLOW", ":CALL"),
        QUERY.replace("[:DATAFLOW]", "[r:DATAFLOW]"),
        QUERY.replace("[:DATAFLOW]", "[:DATAFLOW*1..2]"),
        QUERY.replace("WITH a.graphId", "WHERE a.id > 0 WITH a.graphId"),
        QUERY.replace("a AS source", "a AS graph"),
    ] {
        assert!(!admits(&ex, &query, &[Row::new()]), "{query}");
    }
    assert!(!admits(
        &ex,
        QUERY,
        &[Row::from([("x".into(), Value::Int(1))])]
    ));
    let parent = CancelToken::child_with_limits(CancelToken::new(), 60_000, 100);
    let ex = ex.with_cancel(Arc::new(CancelToken {
        parent: Some(parent),
        ..CancelToken::default()
    }));
    assert!(!admits(&ex, QUERY, &[Row::new()]));
}

#[test]
fn degree_states_keep_node_identity_order_counts_and_provenance() {
    let ex = Executor::new(vec![], true);
    let clauses = parse(QUERY).unwrap();
    let Clause::Match { patterns, .. } = &clauses[0] else {
        unreachable!()
    };
    let (shape, _, _) = FusedShape::detect_with(&clauses).unwrap();
    let mut groups = DegreeGroups::build(
        &ex,
        shape.items.as_deref().unwrap(),
        &shape,
        patterns,
        &[Row::new()],
    )
    .unwrap();
    for (source, count) in [(1, 3), (0, 2)] {
        let mut provenance = Row::new();
        add_provenance_id(&mut provenance, Arc::from("same-external-id"));
        let mut state = DeferredCount::default();
        state.add(count);
        groups.groups.insert(
            NodeRef { source, id: 7 },
            DegreeGroup {
                graph: Value::str("same-external-id"),
                count: state,
                provenance,
            },
        );
    }
    let (columns, rows) = groups.finish(&ex).unwrap();
    assert_eq!(columns, ["graph", "source", "degree"]);
    assert_eq!(rows.len(), 2);
    for (row, (source, count)) in rows.iter().zip([(1, 3), (0, 2)]) {
        assert!(
            matches!(row["source"], Value::Node(node) if node.source == source && node.id == 7)
        );
        assert!(matches!(row["degree"], Value::Int(value) if value == count));
        assert_eq!(QueryResult::graph_ids(row), ["same-external-id"]);
    }
}

#[test]
fn degree_finalization_retains_overflow_and_cancellation_boundaries() {
    let ex = Executor::new(vec![], true);
    let clauses = parse(QUERY).unwrap();
    let Clause::Match { patterns, .. } = &clauses[0] else {
        unreachable!()
    };
    let (shape, _, _) = FusedShape::detect_with(&clauses).unwrap();
    let make = || {
        let mut groups = DegreeGroups::build(
            &ex,
            shape.items.as_deref().unwrap(),
            &shape,
            patterns,
            &[Row::new()],
        )
        .unwrap();
        let mut count = DeferredCount::default();
        count.add(i64::MAX);
        count.add(1);
        groups.groups.insert(
            NodeRef { source: 0, id: 1 },
            DegreeGroup {
                graph: Value::Null,
                count,
                provenance: Row::new(),
            },
        );
        groups
    };
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| make().finish(&ex)));
    if cfg!(debug_assertions) {
        assert!(result.is_err());
    } else {
        assert!(matches!(
            result.unwrap().unwrap().1[0]["degree"],
            Value::Int(i64::MIN)
        ));
    }
    let cancel = CancelToken::new();
    cancel.cancel();
    let cancelled = Executor::new(vec![], true).with_cancel(cancel);
    assert!(matches!(
        make().finish(&cancelled),
        Err(CypherError::Cancelled)
    ));
}

#[test]
fn native_multi_source_degree_histogram_matches_retained_streaming() {
    let Some(path) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
        assert!(std::env::var_os("GRAPHITE_REQUIRE_ALL_TYPES_FIXTURES").is_none());
        return;
    };
    let graph = Arc::new(graphite_storage::Graph::load(std::path::Path::new(&path)).unwrap());
    for dialect in [CypherDialect::Graphite, CypherDialect::OpenCypherM23] {
        for cross in [false, true] {
            let sources = ["z", "a"]
                .into_iter()
                .map(|id| Source {
                    id: Arc::from(id),
                    graph: graph.clone(),
                })
                .collect();
            let ex = Executor::new(sources, cross).with_dialect(dialect);
            for query in [
                QUERY.to_owned(),
                QUERY.replace("LIMIT 100", "LIMIT 0"),
                QUERY.replace("LIMIT 100", "SKIP 1 LIMIT 2"),
            ] {
                let before = HITS.with(|hits| hits.get());
                let actual = ex.execute(&query, None).unwrap();
                assert!(
                    HITS.with(|hits| hits.get()) > before,
                    "compact degree path not exercised"
                );
                let expected = original(|| ex.execute(&query, None)).unwrap();
                assert_eq!(actual.columns, expected.columns);
                assert_eq!(format!("{:?}", actual.rows), format!("{:?}", expected.rows));
                assert_eq!(actual.more, expected.more);
                if query == QUERY {
                    assert!(
                        !actual.rows.is_empty(),
                        "fixture must contain DATAFLOW edges"
                    );
                    assert!(actual
                        .rows
                        .iter()
                        .all(|row| matches!(row["degree"], Value::Int(n) if n > 0)));
                    assert!(actual
                        .rows
                        .iter()
                        .all(|row| matches!(row["sources"], Value::Int(n) if n > 0)));
                }
            }
            let cancel = CancelToken::new();
            cancel.cancel();
            assert!(matches!(
                ex.with_cancel(cancel).execute(QUERY, None),
                Err(CypherError::Cancelled)
            ));
        }
    }
}
