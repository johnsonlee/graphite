use super::*;
use crate::engine::{QueryResult, Source};
use crate::error::CypherError;
use graphite_storage::Graph;
use std::sync::atomic::Ordering;

fn executor() -> Option<Executor> {
    let Some(path) = std::env::var_os("GRAPHITE_TYPES_FIXTURE") else {
        assert!(
            std::env::var_os("GRAPHITE_REQUIRE_ALL_TYPES_FIXTURES").is_none(),
            "GRAPHITE_TYPES_FIXTURE required"
        );
        return None;
    };
    let graph = Arc::new(Graph::load(std::path::Path::new(&path)).unwrap());
    assert!(graph.method_count() >= 3);
    Some(Executor::new(
        ["first", "second"]
            .into_iter()
            .map(|id| Source {
                id: Arc::from(id),
                graph: graph.clone(),
            })
            .collect(),
        true,
    ))
}

fn method(name: &str) -> Pattern {
    Pattern {
        path_variable: None,
        nodes: vec![NodePattern {
            variable: Some(name.into()),
            labels: vec!["Method".into()],
            properties: vec![],
        }],
        rels: vec![],
    }
}

fn method_ref(row: &Row, key: &str) -> (SourceIdx, u32) {
    match &row[key] {
        Value::Method(m) => (m.source, m.index),
        other => panic!("expected Method reference, got {other:?}"),
    }
}

#[test]
fn empty_patterns_preserve_ordered_seed_and_allow_consumer_mutation() {
    let ex = Executor::new(vec![], true);
    let ev = Evaluator::new(&ex, &ex.params);
    let matcher = Matcher { ex: &ex, ev: &ev };
    let seed = Row::from([
        ("answer".into(), Value::Int(42)),
        (
            "nested".into(),
            Value::list(vec![Value::str("keep"), Value::Null]),
        ),
    ]);
    let before = format!("{seed:?}");
    let mut seen = Vec::new();
    assert!(!matcher
        .match_patterns(&seed, &[], &mut |mut row| {
            seen.push(format!("{row:?}"));
            row.clear();
            Ok(false)
        })
        .unwrap());
    assert_eq!(seen.as_slice(), std::slice::from_ref(&before));
    assert_eq!(format!("{seed:?}"), before);
    assert_eq!(ex.poll.load(Ordering::Relaxed), 0);
}

#[test]
fn final_pattern_preserves_bound_method_order_provenance_and_early_stop() {
    let Some(ex) = executor() else { return };
    let ev = Evaluator::new(&ex, &ex.params);
    let matcher = Matcher { ex: &ex, ev: &ev };
    let seed = Row::from([
        ("keep".into(), Value::str("unchanged")),
        (
            "left".into(),
            Value::Method(MethodRef {
                source: 1,
                index: 1,
            }),
        ),
    ]);
    let before = format!("{seed:?}");
    let mut rows = Vec::new();
    assert!(!matcher
        .match_patterns(&seed, &[method("left"), method("right")], &mut |row| {
            rows.push(row);
            Ok(rows.len() < 3)
        })
        .unwrap());
    assert_eq!(
        rows.iter()
            .map(|r| method_ref(r, "left"))
            .collect::<Vec<_>>(),
        [(1, 1); 3]
    );
    assert_eq!(
        rows.iter()
            .map(|r| method_ref(r, "right"))
            .collect::<Vec<_>>(),
        [(0, 0), (0, 1), (0, 2)]
    );
    for row in &rows {
        assert_eq!(row["keep"].as_str(), Some("unchanged"));
        assert_eq!(QueryResult::graph_ids(row), ["first", "second"]);
        assert_eq!(
            row.keys()
                .filter(|k| !k.starts_with('\0'))
                .map(String::as_str)
                .collect::<Vec<_>>(),
            ["keep", "left", "right"]
        );
    }
    assert_eq!(format!("{seed:?}"), before);
    assert_eq!(
        ex.poll.load(Ordering::Relaxed),
        3,
        "stop after three unbound Method candidates; prebound left adds none"
    );
}

#[test]
fn final_pattern_propagates_consumer_error_without_more_candidates() {
    let Some(ex) = executor() else { return };
    let ev = Evaluator::new(&ex, &ex.params);
    let matcher = Matcher { ex: &ex, ev: &ev };
    let mut seen = Vec::new();
    let error = CypherError::Runtime("consumer failure".into());
    let result = matcher.match_patterns(&Row::new(), &[method("m")], &mut |row| {
        seen.push(method_ref(&row, "m"));
        if seen.len() == 2 {
            Err(error.clone())
        } else {
            Ok(true)
        }
    });
    assert_eq!(result, Err(error));
    assert_eq!(seen, [(0, 0), (0, 1)]);
    assert_eq!(ex.poll.load(Ordering::Relaxed), 2);
}

#[test]
fn terminal_emission_preserves_where_errors_and_cancellation() {
    let Some(ex) = executor() else { return };
    let result = ex.execute(
        "MATCH (m:Method) WHERE false AND unknownFunction() RETURN m",
        None,
    );
    assert!(
        matches!(result, Err(CypherError::Runtime(ref s)) if s == "Unknown function: unknownFunction"),
        "{result:?}"
    );
    let Some(ex) = executor() else { return };
    ex.cancel.cancel();
    let ev = Evaluator::new(&ex, &ex.params);
    let matcher = Matcher { ex: &ex, ev: &ev };
    let mut emitted = false;
    assert_eq!(
        matcher.match_patterns(&Row::new(), &[method("m")], &mut |_| {
            emitted = true;
            Ok(true)
        }),
        Err(CypherError::Cancelled)
    );
    assert!(!emitted);
}

#[test]
fn shared_start_paths_use_distinct_edges_and_restore_state_after_stop() {
    let Some(path) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
        assert!(
            std::env::var_os("GRAPHITE_REQUIRE_ALL_TYPES_FIXTURES").is_none(),
            "GRAPHITE_INDEX_FIXTURE required for stateful matcher coverage"
        );
        return;
    };
    let graph = Arc::new(Graph::load(std::path::Path::new(&path)).unwrap());
    // Derive the oracle from persisted adjacency, not from matcher output.
    let (start, edges) = (0..TAG_COUNT as u8)
        .flat_map(|tag| graph.ids_by_tag(tag).iter().copied())
        .find_map(|id| {
            let edges: Vec<_> = graph.outgoing(id).collect();
            (edges.len() == 2
                && edges[0] != edges[1]
                && edges.iter().all(|edge| graph.node_tag(edge.to).is_some()))
            .then_some((id, edges))
        })
        .expect("mandatory persisted fixture needs a node with two distinct outgoing edges");
    let ex = Executor::new(
        vec![Source {
            id: Arc::from("paths"),
            graph,
        }],
        true,
    );
    let ev = Evaluator::new(&ex, &ex.params);
    let matcher = Matcher { ex: &ex, ev: &ev };
    let pattern = |path: &str, edge: &str, target: &str, variable_length| Pattern {
        path_variable: Some(path.into()),
        nodes: vec![
            NodePattern {
                variable: Some("start".into()),
                labels: vec![],
                properties: vec![],
            },
            NodePattern {
                variable: Some(target.into()),
                labels: vec![],
                properties: vec![],
            },
        ],
        rels: vec![RelPattern {
            variable: Some(edge.into()),
            types: vec![],
            direction: Direction::Outgoing,
            variable_length,
            min_hops: Some(1),
            max_hops: Some(1),
            properties: vec![],
        }],
    };
    let patterns = [
        pattern("p", "left", "x", false),
        pattern("q", "right", "y", true),
    ];
    let seed = Row::from([(
        "start".into(),
        Value::Node(NodeRef {
            source: 0,
            id: start,
        }),
    )]);
    let original_seed = format!("{seed:?}");
    let expected = [(edges[0], edges[1]), (edges[1], edges[0])];
    let check = |row: &Row| {
        let Value::Rel(left) = row["left"] else {
            panic!("fixed relationship")
        };
        let Some([Value::Rel(right)]) = row["right"].as_list() else {
            panic!("one-hop variable relationship")
        };
        assert_ne!(
            left.edge, right.edge,
            "comma patterns must not reuse an edge"
        );
        assert_eq!((left.source, right.source), (0, 0));
        for (key, target, edge) in [("p", "x", left.edge), ("q", "y", right.edge)] {
            let Value::Path(path) = &row[key] else {
                panic!("named path {key}")
            };
            assert_eq!(
                path.as_ref(),
                &PathValue {
                    source: 0,
                    nodes: vec![start, edge.to],
                    edges: vec![edge]
                }
            );
            assert!(
                matches!(row[target], Value::Node(n) if n == NodeRef { source: 0, id: edge.to })
            );
        }
        assert_eq!(QueryResult::graph_ids(row), ["paths"]);
        (left.edge, right.edge)
    };
    let mut state = MatchState {
        used: vec![],
        tracking: true,
    };
    let mut first = Vec::new();
    assert!(!matcher
        .match_from(&seed, &patterns, 0, &mut state, &mut |row| {
            first.push(check(&row));
            Ok(false)
        })
        .unwrap());
    assert_eq!(first, [expected[0]]);
    assert!(
        state.used.is_empty(),
        "early false must unwind all edge tracking"
    );
    let mut all = Vec::new();
    assert!(matcher
        .match_from(&seed, &patterns, 0, &mut state, &mut |row| {
            all.push(check(&row));
            Ok(true)
        })
        .unwrap());
    assert_eq!(
        all, expected,
        "fresh enumeration must recover both ordered distinct-edge permutations"
    );
    assert!(state.used.is_empty());
    assert_eq!(format!("{seed:?}"), original_seed);
}
