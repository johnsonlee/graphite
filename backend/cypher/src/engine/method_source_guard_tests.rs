use super::*;
use crate::ast::Clause;
use crate::engine::{QueryResult, Source};
use crate::materialize::materialize;
use crate::parser::parse;
use crate::CypherError;
use graphite_storage::Graph;
use std::sync::atomic::Ordering;
use std::sync::Arc;

const WHERE: &str = "(m.graphId = 'g3' AND m.class STARTS WITH 'fixture.types.') \
    OR (m.graphId = 'g61' AND m.class STARTS WITH 'fixture.types.')";

fn parts(clauses: &[Clause]) -> (&[Pattern], &Expr) {
    let [Clause::Match {
        patterns,
        optional: false,
        where_clause: None,
    }, Clause::Where(predicate), Clause::Return { .. }, ..] = clauses
    else {
        panic!("expected real parser's separate MATCH/WHERE/RETURN: {clauses:?}")
    };
    (patterns, predicate)
}

fn parsed(predicate: &str) -> Vec<Clause> {
    parse(&format!("MATCH (m:Method) WHERE {predicate} RETURN m")).unwrap()
}

fn fixture() -> Option<Graph> {
    let Some(path) = std::env::var_os("GRAPHITE_TYPES_FIXTURE") else {
        assert!(
            std::env::var_os("GRAPHITE_REQUIRE_ALL_TYPES_FIXTURES").is_none(),
            "GRAPHITE_TYPES_FIXTURE required for Method source guard correctness"
        );
        return None;
    };
    let mut graph = Graph::load(std::path::Path::new(&path)).unwrap();
    let echo = graph
        .methods()
        .iter()
        .find(|m| {
            graph.str(m.declaring_class) == "fixture.types.Holder" && graph.str(m.name) == "echo"
        })
        .unwrap()
        .clone();
    assert_eq!(graph.str(echo.return_type), "java.util.List");
    assert!(graph
        .declared_types()
        .unwrap()
        .method(&echo, graph.strings())
        .is_some());
    // Same class/name/parameters, different return descriptor: both identities must survive.
    let mut alternate = echo.clone();
    alternate.return_type = echo.declaring_class;
    graph.metadata.methods = vec![echo.clone(), echo, alternate];
    Some(graph)
}

fn source(id: &str, graph: Arc<Graph>) -> Source {
    Source {
        id: Arc::from(id),
        graph,
    }
}

fn multi(graph: Graph) -> Executor {
    let graph = Arc::new(graph);
    Executor::new(
        (0..64)
            .map(|i| source(&format!("g{i}"), graph.clone()))
            .collect(),
        true,
    )
}

fn refs(rows: &[Row]) -> Vec<(SourceIdx, u32)> {
    rows.iter()
        .map(|row| match row["m"] {
            Value::Method(m) => (m.source, m.index),
            ref other => panic!("expected Method: {other:?}"),
        })
        .collect()
}

#[test]
fn standalone_where_is_bounded_and_requires_the_entire_safe_shape() {
    let clauses = parsed(WHERE);
    let (patterns, predicate) = parts(&clauses);
    assert_eq!(patterns.len(), 1);
    assert_eq!(patterns[0].nodes[0].variable.as_deref(), Some("m"));
    assert_eq!(patterns[0].nodes[0].labels, ["Method"]);
    let Expr::Or(left, right) = predicate else {
        panic!("OR expected")
    };
    for (branch, id) in [(left, "g3"), (right, "g61")] {
        let Expr::And(graph, class) = branch.as_ref() else {
            panic!("AND expected")
        };
        assert_eq!(
            graph.as_ref(),
            &Expr::Comparison {
                op: CmpOp::Eq,
                left: Box::new(Expr::Property {
                    expr: Box::new(Expr::Variable("m".into())),
                    key: "graphId".into()
                }),
                right: Box::new(Expr::Literal(Literal::Str(id.into()))),
            }
        );
        assert_eq!(
            class.as_ref(),
            &Expr::StringOp {
                op: StrOp::StartsWith,
                left: Box::new(Expr::Property {
                    expr: Box::new(Expr::Variable("m".into())),
                    key: "class".into()
                }),
                right: Box::new(Expr::Literal(Literal::Str("fixture.types.".into()))),
            }
        );
    }
    let ex = Executor::new(vec![], true);
    assert!(MethodSourceGuard::build(&ex, patterns, Some(predicate), &Row::new()).is_some());
    assert!(MethodSourceGuard::build(
        &Executor::new(vec![], false),
        patterns,
        Some(predicate),
        &Row::new()
    )
    .is_none());
    assert!(MethodSourceGuard::build(
        &ex,
        patterns,
        Some(predicate),
        &Row::from([("seed".into(), Value::Int(1))])
    )
    .is_none());
    assert!(MethodSourceGuard::build(&ex, patterns, None, &Row::new()).is_none());
    for query in [
        "MATCH (m:Method) WHERE m.class STARTS WITH 'fixture.types.' RETURN m",
        "MATCH (m) WHERE m.graphId = 'g3' RETURN m",
        "MATCH (:Method) WHERE m.graphId = 'g3' RETURN m",
        "MATCH (m:Method:Other) WHERE m.graphId = 'g3' RETURN m",
        "MATCH (m:Method {class: 'fixture.types.Holder'}) WHERE m.graphId = 'g3' RETURN m",
        "MATCH p=(m:Method) WHERE m.graphId = 'g3' RETURN m",
        "MATCH (m:Method), (n:Method) WHERE m.graphId = 'g3' RETURN m",
        "MATCH (m:Method)-[r]->(n) WHERE m.graphId = 'g3' RETURN m",
    ] {
        let clauses = parse(query).unwrap();
        let (patterns, predicate) = parts(&clauses);
        assert!(
            MethodSourceGuard::build(&ex, patterns, Some(predicate), &Row::new()).is_none(),
            "{query}"
        );
    }
}

#[test]
fn unknown_error_and_over_budget_predicates_fall_back_before_enumeration() {
    let ex = Executor::new(vec![], true);
    for unsafe_leaf in [
        "m.graphId = $id",
        "m.graphId <> 'g3'",
        "n.graphId = 'g3'",
        "m.class CONTAINS 'fixture'",
        "m.class =~ '['",
        "m['class'] STARTS WITH 'f'",
        "m.name = 'echo'",
        "unknown(m)",
        "1 / 0 = 1",
        "NOT m.graphId = 'g3'",
        "m.graphId = 'g3' XOR m.graphId = 'g61'",
        "null",
        "true",
    ] {
        for predicate in [
            format!("m.graphId = 'absent' AND ({unsafe_leaf})"),
            format!("({unsafe_leaf}) OR m.graphId = 'absent'"),
        ] {
            let clauses = parsed(&predicate);
            let (patterns, predicate) = parts(&clauses);
            assert!(
                MethodSourceGuard::build(&ex, patterns, Some(predicate), &Row::new()).is_none(),
                "{unsafe_leaf}"
            );
        }
    }
    let clauses = parsed("m.graphId = 'g3'");
    let (patterns, leaf) = parts(&clauses);
    let mut deep = leaf.clone();
    for _ in 0..MAX_DEPTH {
        deep = Expr::And(Box::new(leaf.clone()), Box::new(deep));
    }
    assert!(MethodSourceGuard::build(&ex, patterns, Some(&deep), &Row::new()).is_none());
    let mut broad = leaf.clone();
    for _ in 0..8 {
        broad = Expr::Or(Box::new(broad.clone()), Box::new(broad));
    }
    assert!(MethodSourceGuard::build(&ex, patterns, Some(&broad), &Row::new()).is_none());
    // Empty input still returns normally for predicates outside the planner budget.
    let mut empty = clauses;
    empty[1] = Clause::Where(deep);
    assert!(ex.execute_clauses(&empty, None).unwrap().rows.is_empty());
}

#[test]
fn source_guard_keeps_64_source_indices_duplicate_methods_and_every_tick() {
    let Some(graph) = fixture() else { return };
    let ex = multi(graph);
    let clauses = parsed(WHERE);
    let (patterns, predicate) = parts(&clauses);
    let plan = MethodSourceGuard::build(&ex, patterns, Some(predicate), &Row::new()).unwrap();
    let mut rows = vec![];
    assert!(plan
        .run(&ex, &mut |row| {
            rows.push(row);
            Ok(true)
        })
        .unwrap());
    assert_eq!(
        refs(&rows),
        [(3, 0), (3, 1), (3, 2), (61, 0), (61, 1), (61, 2)]
    );
    assert_eq!(ex.sources.len(), 64);
    assert_eq!(ex.poll.load(Ordering::Relaxed), 64 * 3);
    for (row, expected) in rows.iter().zip(["g3", "g3", "g3", "g61", "g61", "g61"]) {
        assert_eq!(QueryResult::graph_ids(row), [expected]);
        assert_eq!(
            row.keys().map(String::as_str).collect::<Vec<_>>(),
            ["m", super::super::INTERNAL_PROVENANCE_KEY]
        );
    }
    let duplicate = Executor::new(
        vec![
            ex.sources[3].clone(),
            ex.sources[0].clone(),
            ex.sources[3].clone(),
        ],
        true,
    );
    let mut rows = vec![];
    assert!(plan
        .run(&duplicate, &mut |row| {
            rows.push(row);
            Ok(true)
        })
        .unwrap());
    assert_eq!(
        refs(&rows),
        [(0, 0), (0, 1), (0, 2), (2, 0), (2, 1), (2, 2)]
    );
}

#[test]
fn conservative_or_keeps_class_matches_and_rechecks_target_prefixes() {
    let Some(graph) = fixture() else { return };
    let ex = multi(graph);
    let result = ex.execute("MATCH (m:Method) WHERE m.graphId = 'absent' OR m.class STARTS WITH 'fixture.types.' RETURN m.graphId AS g", None).unwrap();
    assert_eq!(result.rows.len(), 192);
    assert_eq!(result.rows[0]["g"].as_str(), Some("g0"));
    assert_eq!(result.rows[191]["g"].as_str(), Some("g63"));
    assert!(ex.execute("MATCH (m:Method) WHERE m.graphId = 'g3' AND m.class STARTS WITH 'not.a.match' RETURN m", None).unwrap().rows.is_empty());
    assert!(ex
        .execute(
            "MATCH (m:Method) WHERE m.graphId = 'g3' AND m.graphId = 'g61' RETURN m",
            None
        )
        .unwrap()
        .rows
        .is_empty());
}

#[test]
fn stopping_errors_and_cancelled_rejected_candidates_keep_original_polling() {
    let Some(graph) = fixture() else { return };
    let ex = multi(graph);
    let clauses = parsed(WHERE);
    let (patterns, predicate) = parts(&clauses);
    let plan = MethodSourceGuard::build(&ex, patterns, Some(predicate), &Row::new()).unwrap();
    let mut seen = vec![];
    assert!(!plan
        .run(&ex, &mut |mut row| {
            seen.push(refs(&[row.clone()])[0]);
            row.clear();
            Ok(false)
        })
        .unwrap());
    assert_eq!(seen, [(3, 0)]);
    assert_eq!(ex.poll.load(Ordering::Relaxed), 10);
    ex.poll.store(0, Ordering::Relaxed);
    let err = plan
        .run(&ex, &mut |_| Err(CypherError::BudgetExceeded(99)))
        .unwrap_err();
    assert_eq!(err, CypherError::BudgetExceeded(99));
    assert_eq!(ex.poll.load(Ordering::Relaxed), 10);
    let Some(mut graph) = fixture() else { return };
    graph.metadata.methods = vec![graph.metadata.methods[0].clone(); 1100];
    let graph = Arc::new(graph);
    let cancelled = Executor::new(
        vec![
            source("g3", ex.sources[0].graph.clone()),
            source("rejected", graph),
        ],
        true,
    );
    let mut consumed = 0;
    assert!(matches!(
        plan.run(&cancelled, &mut |_| {
            consumed += 1;
            cancelled.cancel.cancel();
            Ok(true)
        }),
        Err(CypherError::Cancelled)
    ));
    assert_eq!(consumed, 3);
    assert_eq!(cancelled.poll.load(Ordering::Relaxed), 1025);
}

#[test]
fn invalid_class_ids_fall_through_only_when_where_reads_class() {
    let Some(mut graph) = fixture() else { return };
    graph.metadata.methods.truncate(1);
    graph.metadata.methods[0].declaring_class = graph.strings().len() as u32;
    let ex = Executor::new(vec![source("rejected", Arc::new(graph))], true);
    for predicate in [
        "m.graphId = 'g3' AND m.class STARTS WITH 'f'",
        "m.class STARTS WITH 'f' AND m.graphId = 'g3'",
        "m.graphId = 'g3' OR (m.class STARTS WITH 'f' AND m.graphId = 'g61')",
    ] {
        let clauses = parsed(predicate);
        let (patterns, predicate) = parts(&clauses);
        let plan = MethodSourceGuard::build(&ex, patterns, Some(predicate), &Row::new()).unwrap();
        let mut rows = vec![];
        plan.run(&ex, &mut |row| {
            rows.push(row);
            Ok(true)
        })
        .unwrap();
        assert_eq!(refs(&rows), [(0, 0)]);
        assert!(std::panic::catch_unwind(std::panic::AssertUnwindSafe(
            || ex.execute_clauses(&clauses, None)
        ))
        .is_err());
    }
    assert!(ex
        .execute("MATCH (m:Method) WHERE m.graphId = 'g3' RETURN m", None)
        .unwrap()
        .rows
        .is_empty());
    let Some(good) = fixture() else { return };
    let before_bad = Executor::new(
        vec![source("g3", Arc::new(good)), ex.sources[0].clone()],
        true,
    );
    let clauses = parsed("m.graphId = 'g3' AND m.class STARTS WITH 'fixture.types.'");
    let (patterns, predicate) = parts(&clauses);
    let plan =
        MethodSourceGuard::build(&before_bad, patterns, Some(predicate), &Row::new()).unwrap();
    let mut first = vec![];
    assert!(!plan
        .run(&before_bad, &mut |row| {
            first.push(row);
            Ok(false)
        })
        .unwrap());
    assert_eq!(refs(&first), [(0, 0)]);
    assert_eq!(before_bad.poll.load(Ordering::Relaxed), 1);
    before_bad.poll.store(0, Ordering::Relaxed);
    let result = before_bad.execute("MATCH (m:Method) WHERE m.graphId = 'g3' AND m.class STARTS WITH 'fixture.types.' RETURN m LIMIT 1", None).unwrap();
    assert_eq!(refs(&result.rows), [(0, 0)]);
    assert_eq!(before_bad.poll.load(Ordering::Relaxed), 1);
}

// The child runs the same complete output contract in a fresh process, because the
// production disable flag is intentionally process-wide OnceLock state.
fn output_contract() -> Option<serde_json::Value> {
    let ex = multi(fixture()?).with_probe();
    let mut snapshots = vec![];
    for projection in [
        "m AS value",
        "properties(m) AS value",
        "m.class AS class, m.return_type AS returned, m.graphId AS graph",
    ] {
        let query = format!("MATCH (m:Method) WHERE {WHERE} RETURN {projection} ORDER BY m.graphId, m.signature, m.return_type");
        let result = ex.execute(&query, None).unwrap();
        assert_eq!(result.rows.len(), 6);
        assert!(!result.more);
        let bodies: Vec<_> = result
            .rows
            .iter()
            .map(|row| {
                row.iter()
                    .map(|(key, val)| (key.clone(), materialize(val, &ex)))
                    .collect::<serde_json::Map<_, _>>()
            })
            .collect();
        assert_eq!(QueryResult::graph_ids(&result.rows[0]), ["g3"]);
        assert_eq!(QueryResult::graph_ids(&result.rows[5]), ["g61"]);
        if projection == "m AS value" {
            let values: Vec<_> = bodies.iter().map(|row| &row["value"]).collect();
            assert_eq!(values[0]["class"], "fixture.types.Holder");
            assert_eq!(values[0]["name"], "echo");
            assert_eq!(values[0]["return_type"], "fixture.types.Holder");
            assert_eq!(values[1]["return_type"], "java.util.List");
            assert_eq!(
                values[1]["generic_return_type"],
                "java.util.List<java.lang.String>"
            );
            assert_eq!(
                values[1]["parameter_types"],
                serde_json::json!(["java.util.List"])
            );
            assert_eq!(values[1], values[2]);
        }
        snapshots.push(serde_json::json!({"columns":result.columns,"rows":bodies,"typed":format!("{:?}",result.rows)}));
        let limited = ex.execute(&query, Some(2)).unwrap();
        assert_eq!(limited.rows.len(), 2);
        assert!(limited.more);
        snapshots.push(serde_json::json!({"limited":format!("{limited:?}")}));
    }
    // Without LIMIT, DISTINCT sorts projected rows. The original m binding is absent,
    // so sorting by it retains encounter order. Keep that existing behavior explicit.
    let hidden_order = ex.execute(&format!("MATCH (m:Method) WHERE {WHERE} RETURN DISTINCT properties(m) AS value ORDER BY m.graphId, m.return_type"), None).unwrap();
    assert_eq!(hidden_order.rows.len(), 4);
    for (row, graph, returned) in [
        (&hidden_order.rows[0], "g3", "java.util.List"),
        (&hidden_order.rows[1], "g3", "fixture.types.Holder"),
        (&hidden_order.rows[2], "g61", "java.util.List"),
        (&hidden_order.rows[3], "g61", "fixture.types.Holder"),
    ] {
        let value = materialize(&row["value"], &ex);
        assert_eq!(value["graphId"], graph);
        assert_eq!(value["return_type"], returned);
        assert_eq!(QueryResult::graph_ids(row), [graph]);
    }
    snapshots.push(serde_json::json!({"distinctHiddenBindingOrder":format!("{hidden_order:?}")}));
    let distinct = ex.execute(&format!("MATCH (m:Method) WHERE {WHERE} RETURN DISTINCT properties(m) AS value ORDER BY value.graphId, value.return_type"), None).unwrap();
    assert_eq!(distinct.rows.len(), 4);
    for (row, graph, returned) in [
        (&distinct.rows[0], "g3", "fixture.types.Holder"),
        (&distinct.rows[1], "g3", "java.util.List"),
        (&distinct.rows[2], "g61", "fixture.types.Holder"),
        (&distinct.rows[3], "g61", "java.util.List"),
    ] {
        let value = materialize(&row["value"], &ex);
        assert_eq!(value["graphId"], graph);
        assert_eq!(value["return_type"], returned);
        // properties contains graphId, so DISTINCT cannot merge these two sources.
        assert_eq!(QueryResult::graph_ids(row), [graph]);
    }
    snapshots.push(serde_json::json!({"distinctProperties":format!("{distinct:?}")}));
    let merged = ex
        .execute(
            &format!("MATCH (m:Method) WHERE {WHERE} RETURN DISTINCT m.class AS value"),
            None,
        )
        .unwrap();
    assert_eq!(merged.rows.len(), 1);
    assert_eq!(
        merged.rows[0]["value"].as_str(),
        Some("fixture.types.Holder")
    );
    assert_eq!(QueryResult::graph_ids(&merged.rows[0]), ["g3", "g61"]);
    snapshots.push(serde_json::json!({"mergedProvenance":format!("{merged:?}")}));
    let skipped = ex.execute(&format!("MATCH (m:Method) WHERE {WHERE} RETURN m ORDER BY m.graphId, m.signature, m.return_type SKIP 1 LIMIT 3"), None).unwrap();
    assert_eq!(refs(&skipped.rows), [(3, 0), (3, 1), (61, 2)]);
    assert!(skipped.more);
    assert_eq!(QueryResult::graph_ids(&skipped.rows[2]), ["g61"]);
    snapshots.push(serde_json::json!({"orderedSkip":format!("{skipped:?}")}));
    for query in [
        "MATCH (m:Method) WHERE m.graphId = 'absent' AND unknown(m) RETURN m",
        "MATCH (m:Method) WHERE unknown(m) OR m.graphId = 'absent' RETURN m",
    ] {
        let error = ex.execute(query, None).unwrap_err().to_string();
        assert!(error.contains("Unknown function"));
        snapshots.push(serde_json::json!({"error":error}));
    }
    // Bound rows, paths and OPTIONAL retain the generic matcher; projection remains identical.
    for query in [
        "MATCH (m:Method) WITH m WHERE m.graphId = 'g3' RETURN m.class AS c",
        "MATCH (m:Method) WITH m MATCH (m:Method) WHERE m.graphId = 'g3' RETURN m.class AS c",
        "MATCH p=(m:Method) WHERE m.graphId = 'g3' RETURN p",
        "OPTIONAL MATCH (m:Method) WHERE m.graphId = 'absent' RETURN m",
    ] {
        let result = ex.execute(query, None).unwrap();
        let expected = if query.starts_with("OPTIONAL") { 1 } else { 3 };
        assert_eq!(result.rows.len(), expected);
        snapshots.push(serde_json::json!({"fallback":format!("{result:?}")}));
    }
    let single = Executor::new(vec![ex.sources[0].clone()], false);
    assert!(single
        .execute("MATCH (m:Method) WHERE m.graphId = 'g0' RETURN m", None)
        .unwrap()
        .rows
        .is_empty());
    let null_graph_ids = single.execute("MATCH (m:Method) WHERE m.graphId = 'g0' OR m.class STARTS WITH 'fixture.types.' RETURN m.graphId AS g", None).unwrap();
    assert_eq!(null_graph_ids.rows.len(), 3);
    for row in &null_graph_ids.rows {
        assert!(row["g"].is_null());
        assert!(QueryResult::graph_ids(row).is_empty());
    }
    snapshots.push(serde_json::json!({"singleGraphNulls": format!("{null_graph_ids:?}")}));
    let clauses = parsed(WHERE);
    let (patterns, predicate) = parts(&clauses);
    assert_eq!(
        MethodSourceGuard::build(&ex, patterns, Some(predicate), &Row::new()).is_none(),
        super::super::optimizations_disabled()
    );
    Some(serde_json::Value::Array(snapshots))
}

#[test]
fn disabled_fastpaths_child_output_contract() {
    let Some(path) = std::env::var_os("GRAPHITE_METHOD_GUARD_CHILD_OUTPUT") else {
        return;
    };
    assert!(super::super::optimizations_disabled());
    std::fs::write(
        path,
        serde_json::to_vec(&output_contract().expect("child fixture required")).unwrap(),
    )
    .unwrap();
}

#[test]
fn full_properties_aliases_errors_and_limits_match_disabled_fastpaths() {
    let Some(expected) = output_contract() else {
        return;
    };
    assert!(!super::super::optimizations_disabled());
    let path =
        std::env::temp_dir().join(format!("graphite-method-guard-{}.json", std::process::id()));
    assert!(!path.exists());
    let output = std::process::Command::new(std::env::current_exe().unwrap())
        .args([
            "--exact",
            "engine::method_source_guard::tests::disabled_fastpaths_child_output_contract",
            "--nocapture",
        ])
        .env("GRAPHITE_NO_FASTPATH", "1")
        .env("GRAPHITE_METHOD_GUARD_CHILD_OUTPUT", &path)
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "child failed: {}\n{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    let actual: serde_json::Value = serde_json::from_slice(&std::fs::read(&path).unwrap()).unwrap();
    std::fs::remove_file(path).unwrap();
    assert_eq!(actual, expected);
}
