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
            properties_present: false,
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
    let ex = ex.with_params([("step".into(), Value::Int(0))].into());
    let result = ex.execute(
        "MATCH (m:Method) WHERE false AND size(range(0, 1, $step)) > 0 RETURN m",
        None,
    );
    assert_eq!(
        result.unwrap_err(),
        CypherError::Runtime("Step cannot be zero in range()".into())
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
                properties_present: false,
                variable: Some("start".into()),
                labels: vec![],
                properties: vec![],
            },
            NodePattern {
                properties_present: false,
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
                    property_path: None,
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

// Synthetic fixtures exercise correctness only, never performance.
fn adjacency_fixture() -> (std::path::PathBuf, Graph) {
    let path = std::env::temp_dir().join(format!(
        "graphite-adjacency-{}-{}",
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
        {"from":1,"to":0,"kind":"ASSIGN"},
        {"from":1,"to":2,"kind":"ASSIGN"}]}"#;
    graphite_storage::interchange::import_json(&data[..], &path).unwrap();
    let mut graph = Graph::load(&path).unwrap();
    // The current importer deduplicates endpoint pairs. Exercise legacy CSR
    // duplicate entries directly without changing persisted-format validation.
    let outgoing = graph.forward.offsets[1] as usize;
    let label = graph.forward.labels[outgoing - 1];
    graph.forward.targets.insert(outgoing, 1);
    graph.forward.labels.insert(outgoing, label);
    for offset in &mut graph.forward.offsets[1..] {
        *offset += 1;
    }
    let incoming = graph.backward.offsets[2] as usize;
    let label = graph.backward.labels[incoming - 1];
    graph.backward.targets.insert(incoming, 0);
    graph.backward.labels.insert(incoming, label);
    for offset in &mut graph.backward.offsets[2..] {
        *offset += 1;
    }
    (path, graph)
}

#[test]
fn adjacency_iterator_preserves_direction_multiplicity_and_order() {
    let (path, graph) = adjacency_fixture();
    let graph = Arc::new(graph);
    let ex = Executor::new(
        ["first", "second"]
            .into_iter()
            .map(|id| Source {
                id: Arc::from(id),
                graph: graph.clone(),
            })
            .collect(),
        true,
    );
    let ev = Evaluator::new(&ex, &ex.params);
    let matcher = Matcher { ex: &ex, ev: &ev };
    for source in [0, 1] {
        for id in [0, 1, 2, 99] {
            for direction in [Direction::Outgoing, Direction::Incoming, Direction::Both] {
                let n = NodeRef { source, id };
                let expected = matcher.edges_of(n, direction);
                assert_eq!(
                    matcher.edge_iter(n, direction).collect::<Vec<_>>(),
                    expected
                );
                assert_eq!(
                    matcher.edge_iter(n, direction).take(1).collect::<Vec<_>>(),
                    expected.into_iter().take(1).collect::<Vec<_>>()
                );
            }
        }
    }
    let both = matcher
        .edge_iter(NodeRef { source: 0, id: 0 }, Direction::Both)
        .collect::<Vec<_>>();
    assert_eq!(
        both.iter().map(|e| (e.from, e.to)).collect::<Vec<_>>(),
        [(0, 0), (0, 1), (0, 1), (1, 0)]
    );
    // Both fixed and variable-length matching retain trail uniqueness, direction
    // and distinct endpoint identity; results are concrete, not mere admission.
    for query in [
        "MATCH (a)-[:DATAFLOW]->(b)-[:DATAFLOW]->(c) RETURN count(*) AS paths, count(DISTINCT a) AS starts, count(DISTINCT c) AS ends",
        "MATCH (a)-[:DATAFLOW*2..2]->(c) RETURN count(*) AS paths, count(DISTINCT a) AS starts, count(DISTINCT c) AS ends",
    ] {
        let result = ex.execute(query, None).unwrap();
        assert!(matches!(result.rows[0]["paths"], Value::Int(18)));
        assert!(matches!(result.rows[0]["starts"], Value::Int(4)));
        assert!(matches!(result.rows[0]["ends"], Value::Int(6)));
        assert_eq!(QueryResult::graph_ids(&result.rows[0]), ["first", "second"]);
    }
    drop((ex, graph));
    std::fs::remove_dir_all(path).unwrap();
}

#[test]
fn adjacency_iterator_checks_both_slices_before_early_stop() {
    let (path, mut graph) = adjacency_fixture();
    // Corrupt only the incoming range. Outgoing has a valid first edge, but BOTH
    // must still expose the invalid incoming slice before an early consumer exits.
    graph.backward.offsets[1] = u32::MAX;
    let ex = Executor::new(
        vec![Source {
            id: Arc::from("g"),
            graph: Arc::new(graph),
        }],
        false,
    );
    let ev = Evaluator::new(&ex, &ex.params);
    let matcher = Matcher { ex: &ex, ev: &ev };
    for collected in [false, true] {
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            if collected {
                matcher
                    .edges_of(NodeRef { source: 0, id: 0 }, Direction::Both)
                    .into_iter()
                    .next()
            } else {
                matcher
                    .edge_iter(NodeRef { source: 0, id: 0 }, Direction::Both)
                    .next()
            }
        }));
        assert!(result.is_err());
    }
    drop(ex);
    std::fs::remove_dir_all(path).unwrap();
}
