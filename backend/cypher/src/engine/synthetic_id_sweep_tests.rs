//! Correctness and source-access checks only; fixture timings are not benchmarks.
use super::*;
use crate::engine::Source;
use std::sync::Arc;

fn fixture() -> Option<Arc<Graph>> {
    let path = std::env::var_os("GRAPHITE_TYPES_FIXTURE")
        .or_else(|| std::env::var_os("GRAPHITE_INDEX_FIXTURE"));
    let Some(path) = path else {
        assert!(std::env::var_os("GRAPHITE_REQUIRE_ALL_TYPES_FIXTURES").is_none());
        return None;
    };
    let graph = Arc::new(Graph::load(std::path::Path::new(&path)).unwrap());
    assert!(!graph.ids_by_tag(TAG_CALL_SITE_NODE).is_empty());
    Some(graph)
}

fn raw(graph: &Graph, gid: &str, predicate: &str) -> RawTree {
    let clauses = crate::parser::parse(&format!(
        "MATCH (n:CallSite) WHERE {predicate} RETURN id(n)"
    ))
    .unwrap();
    let crate::ast::Clause::Match { patterns, .. } = &clauses[0] else {
        panic!("MATCH required")
    };
    let crate::ast::Clause::Where(where_clause) = &clauses[1] else {
        panic!("WHERE required")
    };
    let plan = ScanPlan::build(patterns, Some(where_clause)).unwrap();
    RawTree::build(
        graph,
        gid,
        usable_index(graph),
        &plan.tree,
        &mut Memo::new(plan.tests),
        &mut 0,
    )
}

fn state() -> RawSweepState {
    RawSweepState {
        memos: vec![],
        text: String::new(),
    }
}

fn synthetic_predicate(expression: &str) -> StringPredicate {
    let clauses = crate::parser::parse(&format!(
        "MATCH (n:CallSite) WHERE {expression} RETURN id(n)"
    ))
    .unwrap();
    let crate::ast::Clause::Match { patterns, .. } = &clauses[0] else {
        panic!("MATCH required")
    };
    let crate::ast::Clause::Where(where_clause) = &clauses[1] else {
        panic!("WHERE required")
    };
    let PredTree::Leaf(predicate) = ScanPlan::build(patterns, Some(where_clause)).unwrap().tree
    else {
        panic!("single synthetic leaf required")
    };
    predicate
}

#[test]
fn compiled_decimal_contains_matches_formatted_identity() {
    let mut ids: Vec<u32> = (0..=255).collect();
    for exponent in 1..=9 {
        let boundary = 10_u32.pow(exponent);
        ids.extend([boundary - 1, boundary, boundary + 1]);
    }
    ids.extend([1001, 10100, 100_000_001, u32::MAX - 1, u32::MAX]);
    for property in ["n.qualifiedId", "n.elementId", "toLower(n.qualifiedId)"] {
        for literal in [
            "0",
            "00",
            "01",
            "001",
            "10",
            "11",
            "101",
            "000",
            "0000000000",
            "0000000001",
            "4294967295",
            "4294967296",
            "9999999999",
            "10000000000",
            "18446744073709551616",
        ] {
            let pred = synthetic_predicate(&format!("{property} CONTAINS '{literal}'"));
            for gid in ["app", "APP类型01", "10000000000", "18446744073709551616"] {
                let matcher = SyntheticMatcher::new(gid, &pred);
                assert!(matcher.decimal_contains.is_some());
                let mut text = String::from("untouched row buffer");
                for &id in &ids {
                    let expected = format!("{gid}:{id}").contains(literal);
                    assert_eq!(
                        matcher.matches(&pred, id, &mut text),
                        expected,
                        "{property}, {gid}:{id}, needle={literal}"
                    );
                    assert_eq!(text, "untouched row buffer");
                }
            }
        }
    }
}

#[test]
fn other_synthetic_predicates_keep_the_original_text_path() {
    for expression in [
        "n.qualifiedId CONTAINS ''",
        "n.qualifiedId CONTAINS ':01'",
        "n.qualifiedId CONTAINS 'app:1'",
        "n.qualifiedId CONTAINS 'APP'",
        "n.qualifiedId CONTAINS '类型'",
        "n.qualifiedId CONTAINS '１２'",
        "n.qualifiedId STARTS WITH '1'",
        "n.qualifiedId ENDS WITH '01'",
        "n.elementId = 'app:101'",
        "n.qualifiedId =~ '.*01.*'",
        "n.graphId CONTAINS '01'",
        "toLower(n.qualifiedId) CONTAINS 'app'",
    ] {
        let pred = synthetic_predicate(expression);
        for gid in ["app", "APP类型01"] {
            let matcher = SyntheticMatcher::new(gid, &pred);
            assert!(matcher.decimal_contains.is_none(), "{expression}");
            for id in [0, 1, 101, u32::MAX] {
                let mut expected = synthetic_prefix(gid, &pred);
                if pred.prop() != "graphId" {
                    expected.push_str(&id.to_string());
                }
                let mut text = String::from("old row");
                assert_eq!(
                    matcher.matches(&pred, id, &mut text),
                    pred.matches_raw(&expected),
                    "{expression} on {gid}:{id}"
                );
                assert_eq!(text, expected);
            }
        }
    }
}

#[test]
fn column_and_raw_trees_use_compiled_digits_without_record_or_text_reads() {
    let Some(graph) = fixture() else { return };
    for property in ["qualifiedId", "elementId"] {
        for gid in ["app", "app01"] {
            let expression = format!("n.{property} CONTAINS '01'");
            let pred = synthetic_predicate(&expression);
            let column = ColumnTree::resolve(
                &graph,
                gid,
                &PredTree::Leaf(pred),
                TAG_CALL_SITE_NODE,
                &mut 0,
            )
            .unwrap();
            let raw = raw(&graph, gid, &expression);
            for id in [0, 1, 101, 1001, u32::MAX] {
                let expected = format!("{gid}:{id}").contains("01");
                let mut row_state = RowState {
                    text: "column sentinel".into(),
                    ..Default::default()
                };
                let mut raw_state = state();
                raw_state.text = "raw sentinel".into();
                assert_eq!(
                    column.matches(&graph, usize::MAX, id, &mut row_state),
                    expected
                );
                assert_eq!(
                    raw.matches_at(&graph, &[], Some(usize::MAX), id, &mut raw_state),
                    expected
                );
                assert!(!raw.matches_at(&graph, &[], None, id, &mut raw_state));
                assert_eq!(row_state.text, "column sentinel");
                assert_eq!(raw_state.text, "raw sentinel");
            }
        }
    }
}

#[test]
fn synthetic_leaf_reads_no_record_bytes_and_skips_missing_offsets() {
    let Some(graph) = fixture() else { return };
    let tree = raw(&graph, "app", "n.qualifiedId CONTAINS '938826'");
    // An empty body and an unusable offset make any RawRecord read fail. Neither
    // a matching nor a nonmatching identity may touch that body.
    assert!(tree.matches_at(&graph, &[], Some(usize::MAX), 938826, &mut state()));
    assert!(!tree.matches_at(&graph, &[], Some(usize::MAX), 42, &mut state()));
    assert!(!tree.matches_at(&graph, &[], None, 938826, &mut state()));
}

#[test]
fn synthetic_leaf_matches_original_record_sweep_values_and_order() {
    let Some(graph) = fixture() else { return };
    for gid in ["app", "APP类型12"] {
        for predicate in [
            "n.qualifiedId CONTAINS '1'",
            "n.qualifiedId CONTAINS ''",
            "n.qualifiedId CONTAINS '93882699'",
            "n.qualifiedId STARTS WITH 'app:'",
            "n.elementId ENDS WITH '0'",
            "n.graphId = 'app'",
            "toLower(n.qualifiedId) CONTAINS 'app'",
            "n.qualifiedId =~ '.*app.*'",
        ] {
            let tree = raw(&graph, gid, predicate);
            let mut old_state = state();
            let mut new_state = state();
            let ids = graph.ids_by_tag(TAG_CALL_SITE_NODE);
            let expected: Vec<_> = ids
                .iter()
                .copied()
                .filter(|&id| {
                    graph.node_offset(id).is_some_and(|offset| {
                        tree.eval(
                            &graph,
                            graph.nodedata(),
                            &RawRecord::read(graph.nodedata(), offset, id),
                            &mut old_state,
                        )
                    })
                })
                .collect();
            let actual: Vec<_> = ids
                .iter()
                .copied()
                .filter(|&id| {
                    tree.matches_at(
                        &graph,
                        graph.nodedata(),
                        graph.node_offset(id),
                        id,
                        &mut new_state,
                    )
                })
                .collect();
            assert_eq!(actual, expected, "{gid}: {predicate}");
        }
    }
}

#[test]
fn mixed_and_other_leaves_still_read_the_record() {
    let Some(graph) = fixture() else { return };
    for predicate in [
        "n.qualifiedId CONTAINS '1' AND n.line = 5",
        "n.qualifiedId CONTAINS '1' OR n.line = 5",
        "n.line = 5",
        "n.id = 1",
    ] {
        let tree = raw(&graph, "app", predicate);
        // The fallback still tries the original raw read, even when the first
        // synthetic OR leaf could be true. This is a source-access assertion.
        assert!(
            std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                tree.matches_at(&graph, &[], Some(0), 1, &mut state())
            }))
            .is_err(),
            "{predicate}"
        );
    }
}

#[test]
fn complete_multigraph_identity_rows_limit_and_cancellation_are_preserved() {
    let Some(graph) = fixture() else { return };
    let ex = Executor::new(
        ["first", "last"]
            .into_iter()
            .map(|id| Source {
                id: id.into(),
                graph: graph.clone(),
            })
            .collect(),
        true,
    );
    let first = graph.ids_by_tag(TAG_CALL_SITE_NODE)[0].to_string();
    for literal in [&first[..1], "93882699"] {
        let predicate = format!("n.qualifiedId CONTAINS '{literal}'");
        let expected: Vec<_> = ex
            .sources
            .iter()
            .flat_map(|source| {
                let tree = raw(&graph, &source.id, &predicate);
                let mut st = state();
                graph
                    .ids_by_tag(TAG_CALL_SITE_NODE)
                    .iter()
                    .copied()
                    .filter_map(move |id| {
                        let offset = source.graph.node_offset(id)?;
                        tree.eval(
                            &source.graph,
                            source.graph.nodedata(),
                            &RawRecord::read(source.graph.nodedata(), offset, id),
                            &mut st,
                        )
                        .then(|| {
                            (
                                id as i64,
                                source.id.to_string(),
                                format!("{}:{id}", source.id),
                            )
                        })
                    })
            })
            .collect();
        for limit in [None, Some(1), Some(expected.len() + 1)] {
            let suffix = limit.map(|n| format!(" LIMIT {n}")).unwrap_or_default();
            let query = format!(
                "MATCH (n:CallSite) WHERE {predicate} RETURN id(n) AS id, n.graphId AS graphId, n.qualifiedId AS qualifiedId{suffix}"
            );
            let rows = ex.execute(&query, None).unwrap().rows;
            let actual: Vec<_> = rows
                .iter()
                .map(|row| {
                    let Value::Int(id) = row["id"] else {
                        panic!("integer id required")
                    };
                    (
                        id,
                        row["graphId"].as_str().unwrap().to_owned(),
                        row["qualifiedId"].as_str().unwrap().to_owned(),
                    )
                })
                .collect();
            assert_eq!(
                actual,
                expected
                    .iter()
                    .take(limit.unwrap_or(usize::MAX))
                    .cloned()
                    .collect::<Vec<_>>(),
                "{query}"
            );
        }
    }
    ex.cancel.cancel();
    assert_eq!(
        ex.execute(
            "MATCH (n:CallSite) WHERE n.qualifiedId CONTAINS '1' RETURN id(n)",
            None
        )
        .unwrap_err(),
        crate::CypherError::Cancelled
    );
}
