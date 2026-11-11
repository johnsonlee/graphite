//! Allocation-path correctness; all performance evidence must use real multi-graph load.
use super::*;
use crate::engine::{CancelToken, Source};
use crate::parser::parse;
use crate::CypherDialect;

const QUERY: &str = "MATCH(a:CallSite)-[:DATAFLOW]->(b)-[:DATAFLOW]->(c) RETURN a.graphId AS graph, count(*) AS paths, count(DISTINCT a) AS starts, count(DISTINCT c) AS destinations ORDER BY paths DESC, graph LIMIT 100";

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

fn admitted(ex: &Executor, query: &str) -> bool {
    let clauses = parse(query).unwrap();
    let Clause::Match { patterns, .. } = &clauses[0] else {
        unreachable!()
    };
    let Some(shape) = FusedShape::detect(&clauses) else {
        return false;
    };
    TwoHopGroups::build(
        ex,
        shape.items.as_deref().unwrap(),
        &shape,
        patterns,
        &[Row::new()],
    )
    .is_some()
}

#[test]
fn two_hop_admission_requires_exact_total_counts_and_retains_budget_fallback() {
    let ex = Executor::new(vec![], true);
    assert!(admitted(&ex, QUERY));
    for query in [
        QUERY.replace("count(DISTINCT c)", "count(c)"),
        QUERY.replace("count(DISTINCT a)", "count(DISTINCT b)"),
        QUERY.replace("a.graphId", "c.graphId"),
        QUERY.replace("(c)", "(a)"),
        QUERY.replace("[:DATAFLOW]", "[r:DATAFLOW]"),
        QUERY.replace("[:DATAFLOW]", "[:DATAFLOW*1..2]"),
        QUERY.replace("RETURN a.graphId", "WHERE a.id > 0 RETURN a.graphId"),
        QUERY.replace("ORDER BY paths DESC, graph", "ORDER BY count(*) DESC"),
        QUERY.replace("AS starts", "AS paths"),
    ] {
        assert!(!admitted(&ex, &query), "{query}");
    }
    let parent = CancelToken::child_with_limits(CancelToken::new(), 60_000, 100);
    let ex = ex.with_cancel(Arc::new(CancelToken {
        parent: Some(parent),
        ..CancelToken::default()
    }));
    assert!(!admitted(&ex, QUERY));
}

#[test]
fn source_cache_merges_equal_graph_values_without_collapsing_node_identity() {
    let ex = Executor::new(vec![], true);
    let clauses = parse(QUERY).unwrap();
    let Clause::Match { patterns, .. } = &clauses[0] else {
        unreachable!()
    };
    let shape = FusedShape::detect(&clauses).unwrap();
    let mut groups = TwoHopGroups::build(
        &ex,
        shape.items.as_deref().unwrap(),
        &shape,
        patterns,
        &[Row::new()],
    )
    .unwrap();
    groups.by_source.resize(4, None);
    assert!(groups.groups.is_empty());
    assert_eq!(groups.register_source(2, Value::str("same")), 0);
    assert_eq!(groups.register_source(0, Value::str("same")), 0);
    assert_eq!(groups.register_source(3, Value::str("other")), 1);
    assert_eq!(groups.by_source, [Some(0), None, Some(0), Some(1)]);
    let group = groups.groups.get_index_mut(0).unwrap().1;
    for source in [2, 0, 2] {
        group.paths.add(1);
        if group.starts.insert(NodeRef { source, id: 7 }) {
            group.start_count.add(1);
        }
        if group.destinations.insert(NodeRef { source, id: 9 }) {
            group.destination_count.add(1);
        }
    }
    add_provenance_id(&mut group.provenance, Arc::from("same"));
    let (_, rows) = groups.finish(&ex).unwrap();
    assert_eq!(rows[0]["graph"].as_str(), Some("same"));
    assert!(matches!(rows[0]["paths"], Value::Int(3)));
    assert!(matches!(rows[0]["starts"], Value::Int(2)));
    assert!(matches!(rows[0]["destinations"], Value::Int(2)));
    assert_eq!(QueryResult::graph_ids(&rows[0]), ["same"]);
    assert_eq!(rows[1]["graph"].as_str(), Some("other"));
}

#[test]
fn empty_two_hop_state_and_overflow_finish_without_early_limit_shortcut() {
    let ex = Executor::new(vec![], true);
    let clauses = parse(QUERY).unwrap();
    let Clause::Match { patterns, .. } = &clauses[0] else {
        unreachable!()
    };
    let shape = FusedShape::detect(&clauses).unwrap();
    let make = || {
        TwoHopGroups::build(
            &ex,
            shape.items.as_deref().unwrap(),
            &shape,
            patterns,
            &[Row::new()],
        )
        .unwrap()
    };
    assert!(make().finish(&ex).unwrap().1.is_empty());
    let mut groups = make();
    groups.by_source.resize(1, None);
    groups.register_source(0, Value::Null);
    let count = &mut groups.groups.get_index_mut(0).unwrap().1.paths;
    count.add(i64::MAX);
    count.add(1);
    let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| groups.finish(&ex)));
    if cfg!(debug_assertions) {
        assert!(outcome.is_err());
    } else {
        assert!(matches!(
            outcome.unwrap().unwrap().1[0]["paths"],
            Value::Int(i64::MIN)
        ));
    }
}

#[test]
fn native_two_hop_compact_counts_match_retained_matcher_for_duplicate_graph_ids() {
    let Some(path) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
        assert!(std::env::var_os("GRAPHITE_REQUIRE_ALL_TYPES_FIXTURES").is_none());
        return;
    };
    let graph = Arc::new(graphite_storage::Graph::load(std::path::Path::new(&path)).unwrap());
    for dialect in [CypherDialect::Graphite, CypherDialect::OpenCypherM23] {
        for ids in [["z", "a"], ["same", "same"]] {
            let sources = ids
                .iter()
                .map(|id| Source {
                    id: Arc::from(*id),
                    graph: graph.clone(),
                })
                .collect();
            let ex = Executor::new(sources, true)
                .with_dialect(dialect)
                .with_probe();
            for query in [
                QUERY.to_owned(),
                QUERY.replace("LIMIT 100", "SKIP 1 LIMIT 1"),
                QUERY.replace("LIMIT 100", "LIMIT 0"),
            ] {
                let before = HITS.with(|hits| hits.get());
                let actual = ex.execute(&query, None).unwrap();
                assert!(HITS.with(|hits| hits.get()) > before);
                let expected = original(|| ex.execute(&query, None)).unwrap();
                assert_eq!(actual.columns, expected.columns);
                assert_eq!(format!("{:?}", actual.rows), format!("{:?}", expected.rows));
                assert_eq!(actual.more, expected.more);
                if query == QUERY {
                    assert_eq!(actual.rows.len(), if ids[0] == ids[1] { 1 } else { 2 });
                    assert!(actual
                        .rows
                        .iter()
                        .all(|row| matches!(row["paths"], Value::Int(n) if n > 0)));
                    assert!(actual
                        .rows
                        .iter()
                        .all(|row| matches!(row["starts"], Value::Int(n) if n > 0)));
                    assert!(actual
                        .rows
                        .iter()
                        .all(|row| matches!(row["destinations"], Value::Int(n) if n > 0)));
                }
            }
            let token = CancelToken::new();
            token.cancel();
            assert!(matches!(
                ex.with_cancel(token).execute(QUERY, None),
                Err(CypherError::Cancelled)
            ));
        }
    }
}
