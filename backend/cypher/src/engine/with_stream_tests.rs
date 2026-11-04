use super::*;
use crate::engine::pipeline::{add_provenance_id, INTERNAL_PROVENANCE_KEY};
use crate::engine::{CancelToken, Source};
use crate::parser::parse;
use crate::render::to_cypher_string;
use crate::value::Value;
use crate::CypherError;
use graphite_storage::Graph;
use std::sync::atomic::Ordering;
use std::sync::Arc;

fn admitted(query: &str) -> bool {
    WithStreamPlan::build(&parse(query).unwrap(), &[Row::new()]).is_some()
}

#[test]
fn admits_total_projection_filter_chains_and_literal_paging() {
    for query in [
        "MATCH (n:CallSite) WITH n.callee_class AS c WHERE c IS NOT NULL RETURN c LIMIT 200",
        "MATCH (n:CallSite:Node) WITH n AS x WITH x.callee_class AS c WHERE c IS NOT NULL RETURN c LIMIT 200",
        "MATCH (n:CallSite) WHERE n.id <> 0 WITH n.id AS id WHERE id IN [1, 2] WITH id AS c WHERE c = 2 RETURN c AS answer SKIP 1 LIMIT 3",
        "MATCH (n:CallSite {caller_name: 'test'}) WITH n AS x WITH * WHERE x.caller_name STARTS WITH 't' RETURN x.caller_name LIMIT 0",
        "MATCH (n:Method) WITH 1 AS c WHERE c IS NULL OR c = 1 RETURN c LIMIT 9223372036854775807",
    ] {
        assert!(admitted(query), "expected streaming admission: {query}");
    }
}

#[test]
fn declines_error_sensitive_and_nonstreaming_shapes_before_source_access() {
    for query in [
        "OPTIONAL MATCH (n) WITH n AS c WHERE c IS NOT NULL RETURN c LIMIT 1",
        "MATCH (n)-[]-(m) WITH n.id AS c WHERE c IS NOT NULL RETURN c LIMIT 1",
        "MATCH (n), (m) WITH n.id AS c WHERE c IS NOT NULL RETURN c LIMIT 1",
        "MATCH (n) WHERE missing_function(n) WITH n AS c RETURN c LIMIT 1",
        "MATCH (n {name: missing_function(1)}) WITH n AS c RETURN c LIMIT 1",
        "MATCH (n {generic_type: 'T'}) WITH n AS c RETURN c LIMIT 1",
        "MATCH (n) WITH n.generic_type AS c WHERE c IS NOT NULL RETURN c LIMIT 1",
        "MATCH (n:Method) WITH n.generic_return_type AS c RETURN c LIMIT 1",
        "MATCH (n) WITH n.type_info AS c RETURN c LIMIT 1",
        "MATCH (n) WITH 1 / n.id AS c RETURN c LIMIT 1",
        "MATCH (n) WITH CASE WHEN n.id = 0 THEN missing_function(1) ELSE 2 END AS c RETURN c LIMIT 1",
        "MATCH (n) WITH n AS c WHERE c.name =~ '[bad' RETURN c LIMIT 1",
        "MATCH (n) WITH n AS c WHERE NOT c.name RETURN c LIMIT 1",
        "MATCH (n) WITH n AS c WHERE c > 1 RETURN c LIMIT 1",
        "MATCH (n) WITH DISTINCT n.name AS c RETURN c LIMIT 1",
        "MATCH (n) WITH n.name AS c ORDER BY c RETURN c LIMIT 1",
        "MATCH (n) WITH n.name AS c LIMIT 2 RETURN c LIMIT 1",
        "MATCH (n) WITH count(*) AS c RETURN c LIMIT 1",
        "MATCH (n) WITH n AS c RETURN DISTINCT c LIMIT 1",
        "MATCH (n) WITH n AS c RETURN c ORDER BY c LIMIT 1",
        "MATCH (n) WITH n AS c RETURN * LIMIT 1",
        "MATCH (n) WITH n AS c RETURN c LIMIT $limit",
        "MATCH (n) WITH n AS c RETURN c SKIP $skip LIMIT 1",
        "MATCH (n) WITH n AS c RETURN c",
        "MATCH (n) WITH n.name AS c RETURN c LIMIT 1",
        "MATCH (n:StringConstant) WITH n.value AS c RETURN c LIMIT 1",
        "MATCH (n:CallSite) WITH n.caller_signature AS c RETURN c LIMIT 1",
        "MATCH (n:CallSite) WITH $dynamic AS x WITH x.callee_class AS c RETURN c LIMIT 1",
        "MATCH (n:CallSite) WITH n.caller_class AS n RETURN n.callee_class LIMIT 1",
        "MATCH (n:CallSite:Method) WITH n.id AS c RETURN c LIMIT 1",
        "MATCH (n:Node:CallSite) WITH n.id AS c RETURN c LIMIT 1",
    ] {
        assert!(!admitted(query), "unexpected streaming admission: {query}");
    }
    let clauses = parse("MATCH (n:CallSite) WITH n.id AS c RETURN c LIMIT 1").unwrap();
    assert!(
        WithStreamPlan::build(&clauses, &[Row::from([("seed".into(), Value::Int(1))])]).is_none()
    );
    let mut expr = Expr::Literal(Literal::Bool(true));
    for _ in 0..MAX_EXPRESSION_DEPTH {
        expr = Expr::IsNotNull(Box::new(expr));
    }
    assert!(total(&expr, &Scope::new(), 0, &mut MAX_EXPRESSION_NODES.clone()).is_none());
}

#[test]
fn projects_before_filtering_and_keeps_shadowing_nulls_duplicates_and_provenance() {
    let clauses = parse("MATCH (n:CallSite) WITH n.callee_class AS c, n.id AS id WHERE c IS NOT NULL WITH c AS id, id AS c WHERE c <> 2 RETURN id, c, missing AS absent LIMIT 2").unwrap();
    let plan = WithStreamPlan::build(&clauses, &[Row::new()]).unwrap();
    let ex = Executor::new(vec![], true);
    let ev = Evaluator::new(&ex, &ex.params);
    let mut projected = Vec::new();
    for (source, id, name) in [
        ("a", 1, Some("same")),
        ("a", 2, Some("drop")),
        ("z", 3, None),
        ("z", 4, Some("same")),
    ] {
        let mut row = Row::from([(
            "n".into(),
            Value::map(Row::from([
                (
                    "callee_class".into(),
                    name.map(Value::str).unwrap_or(Value::Null),
                ),
                ("id".into(), Value::Int(id)),
            ])),
        )]);
        add_provenance_id(&mut row, Arc::from(source));
        if let Some(row) = plan.project(&ex, &ev, row).unwrap() {
            projected.push(row);
        }
    }
    assert_eq!(projected.len(), 2);
    assert_eq!(
        projected
            .iter()
            .map(|row| row["id"].as_str())
            .collect::<Vec<_>>(),
        [Some("same"), Some("same")]
    );
    assert!(matches!(projected[0]["c"], Value::Int(1)));
    assert!(matches!(projected[1]["c"], Value::Int(4)));
    assert!(projected.iter().all(|row| row["absent"].is_null()));
    assert_eq!(QueryResult::graph_ids(&projected[0]), ["a"]);
    assert_eq!(QueryResult::graph_ids(&projected[1]), ["z"]);
    assert_eq!(
        projected[0].keys().map(String::as_str).collect::<Vec<_>>(),
        ["id", "c", "absent", INTERNAL_PROVENANCE_KEY]
    );
}

fn sources() -> Option<Vec<Source>> {
    let path = std::env::var_os("GRAPHITE_TYPES_FIXTURE")
        .or_else(|| std::env::var_os("GRAPHITE_INDEX_FIXTURE"));
    let Some(path) = path else {
        assert!(std::env::var_os("GRAPHITE_REQUIRE_ALL_TYPES_FIXTURES").is_none());
        return None;
    };
    let graph = Arc::new(Graph::load(std::path::Path::new(&path)).unwrap());
    Some(
        ["a", "z"]
            .into_iter()
            .map(|id| Source {
                id: Arc::from(id),
                graph: graph.clone(),
            })
            .collect(),
    )
}

// Every result expression is replaced by an equivalent CASE. CASE deliberately
// declines this plan, preserving the eager pipeline as an independent oracle.
fn reference(clauses: &[Clause]) -> Vec<Clause> {
    let mut clauses = clauses.to_vec();
    let Clause::Return {
        items: Some(items), ..
    } = clauses
        .iter_mut()
        .find(|clause| matches!(clause, Clause::Return { .. }))
        .unwrap()
    else {
        panic!("explicit RETURN required")
    };
    for item in items {
        if item.alias.is_none() {
            item.alias = Some(to_cypher_string(&item.expr));
        }
        item.expr = Expr::Case {
            test: None,
            whens: vec![(Expr::Literal(Literal::Bool(true)), item.expr.clone())],
            else_expr: None,
        };
    }
    assert!(WithStreamPlan::build(&clauses, &[Row::new()]).is_none());
    clauses
}

#[test]
fn real_multisource_streaming_matches_eager_rows_columns_provenance_and_probe() {
    let Some(sources) = sources() else { return };
    for query in [
        "MATCH (n:CallSite) WITH n.callee_class AS c WHERE c IS NOT NULL RETURN c LIMIT 1",
        "MATCH (n:CallSite:Node) WITH n AS x WITH x.callee_class AS c WHERE c IS NOT NULL RETURN c LIMIT 1",
        "MATCH (n:CallSite) WITH n.graphId AS g, n.id AS id WHERE g = 'z' WITH id AS c, g WHERE c IS NOT NULL RETURN g, c SKIP 1 LIMIT 2",
        "MATCH (n:CallSite) WITH n.callee_class AS c WHERE c = null RETURN c LIMIT 2",
        "MATCH (n:CallSite) WITH n.id AS c, n.callee_name AS text WITH text AS c, c AS text RETURN c, text SKIP 2 LIMIT 3",
        "MATCH (n:UnknownLabel) WITH n AS c RETURN c LIMIT 3",
        "MATCH (n:CallSite) WITH n.id AS c RETURN c LIMIT 0",
        "MATCH (n:CallSite) WITH n.id AS c RETURN c SKIP 2147483647 LIMIT 2",
    ] {
        let clauses = parse(query).unwrap();
        assert!(WithStreamPlan::build(&clauses, &[Row::new()]).is_some());
        for probe in [false, true] {
            let executor = || {
                let ex = Executor::new(sources.clone(), true).with_compact();
                if probe { ex.with_probe() } else { ex }
            };
            let actual = executor().execute_clauses(&clauses, None).unwrap();
            let expected = executor().execute_clauses(&reference(&clauses), None).unwrap();
            assert_eq!(format!("{actual:?}"), format!("{expected:?}"), "{query}, probe={probe}");
        }
    }
    let ex = Executor::new(sources, true).with_probe();
    let result = ex
        .execute(
            "MATCH (n:CallSite) WITH n.callee_class AS c WHERE c IS NOT NULL RETURN c LIMIT 1",
            None,
        )
        .unwrap();
    assert_eq!(result.columns, ["c"]);
    assert_eq!(result.rows.len(), 1);
    assert!(result.rows[0]["c"].as_str().is_some());
    assert_eq!(QueryResult::graph_ids(&result.rows[0]), ["a"]);
    assert!(result.more);
    assert!(
        ex.poll.load(Ordering::Relaxed)
            < ex.sources
                .iter()
                .map(|source| source.graph.node_count() as u64)
                .sum::<u64>()
    );
}

#[test]
fn real_late_projection_and_match_errors_are_not_hidden_by_limits() {
    let Some(sources) = sources() else { return };
    for limit in [0, 1] {
        for query in [
            format!("MATCH (n:CallSite) WITH CASE WHEN n.graphId = 'z' THEN missing_function(n.id) ELSE n.id END AS c WHERE c IS NOT NULL RETURN c LIMIT {limit}"),
            format!("MATCH (n:CallSite) WHERE CASE WHEN n.graphId = 'z' THEN missing_function(n.id) ELSE true END WITH n.id AS c WHERE c IS NOT NULL RETURN c LIMIT {limit}"),
        ] {
            assert!(!admitted(&query));
            let error = Executor::new(sources.clone(), true).execute(&query, None).unwrap_err();
            assert!(error.to_string().contains("missing_function"), "{query}: {error}");
        }
    }
}

#[test]
fn real_declared_access_and_aliased_getters_keep_later_source_failures() {
    let Some(mut sources) = sources() else { return };
    let path = std::env::var_os("GRAPHITE_TYPES_FIXTURE")
        .or_else(|| std::env::var_os("GRAPHITE_INDEX_FIXTURE"))
        .unwrap();
    let bad = Graph::load(std::path::Path::new(&path))
        .unwrap()
        .with_declared_types_error("late source metadata digest mismatch");
    sources[1].graph = Arc::new(bad);
    for limit in [0, 1] {
        for prefix in [
            "MATCH (n:FieldNode) WITH n.generic_type AS c WHERE c IS NOT NULL RETURN c",
            "MATCH (n:FieldNode) WITH n AS x WITH x.type_info AS c WHERE c IS NOT NULL RETURN c",
            "MATCH (n:FieldNode) WITH n AS x WHERE x.generic_type IS NULL RETURN x",
            "MATCH (n:Method) WITH n AS x WITH x.generic_return_type AS c WHERE c IS NOT NULL RETURN c",
        ] {
            let query = format!("{prefix} LIMIT {limit}");
            assert!(!admitted(&query));
            let error = Executor::new(sources.clone(), true)
                .execute(&query, None)
                .expect_err(&query);
            assert!(
                error
                    .to_string()
                    .contains("late source metadata digest mismatch"),
                "{query}: {error}"
            );
        }
    }
}

#[test]
fn late_invalid_scalar_sids_keep_eager_panic_payloads_even_before_zero_limit() {
    let Some(sources) = sources() else { return };
    let source = std::env::var_os("GRAPHITE_INDEX_FIXTURE")
        .or_else(|| std::env::var_os("GRAPHITE_TYPES_FIXTURE"))
        .unwrap();
    struct Variant {
        path: std::path::PathBuf,
        graph: Arc<Graph>,
    }
    impl Drop for Variant {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.path);
        }
    }
    fn variant(source: &std::path::Path, sid: u32) -> Variant {
        let original = Graph::load(source).unwrap();
        let id = original.ids_by_tag(TAG_CALL_SITE_NODE)[0];
        let offset = original.node_offset(id).unwrap();
        let path = std::env::temp_dir().join(format!(
            "graphite-with-stream-invalid-sid-{}-{sid}",
            std::process::id()
        ));
        std::fs::create_dir(&path).unwrap();
        for entry in std::fs::read_dir(source).unwrap() {
            let entry = entry.unwrap();
            if entry.file_type().unwrap().is_file() && entry.file_name() != "graph.nodedata" {
                std::fs::copy(entry.path(), path.join(entry.file_name())).unwrap();
            }
        }
        let mut bytes = original.nodedata().to_vec();
        bytes[offset + 5..offset + 9].copy_from_slice(&sid.to_be_bytes());
        std::fs::write(path.join("graph.nodedata"), bytes).unwrap();
        let graph = Arc::new(Graph::load(&path).unwrap());
        assert_eq!(
            graph.call_site_scalar_strings(id).unwrap().caller_class,
            sid
        );
        Variant { path, graph }
    }
    fn panic_payload(result: std::thread::Result<crate::CypherResult<QueryResult>>) -> String {
        let payload = result.expect_err("the eager scalar access must still panic");
        if let Some(message) = payload.downcast_ref::<String>() {
            message.clone()
        } else if let Some(message) = payload.downcast_ref::<&str>() {
            (*message).into()
        } else {
            panic!("expected a string bounds-check panic")
        }
    }
    let bad_late = variant(std::path::Path::new(&source), u32::MAX);
    let bad_first = variant(std::path::Path::new(&source), u32::MAX - 1);
    for first_bad in [false, true] {
        let inputs = vec![
            Source {
                id: Arc::from("a"),
                graph: if first_bad {
                    bad_first.graph.clone()
                } else {
                    sources[0].graph.clone()
                },
            },
            Source {
                id: Arc::from("z"),
                graph: bad_late.graph.clone(),
            },
        ];
        for limit in [0, 1] {
            let query = format!("MATCH (n:CallSite) WITH n.caller_class AS c WHERE c IS NOT NULL RETURN c LIMIT {limit}");
            let clauses = parse(&query).unwrap();
            assert!(WithStreamPlan::build(&clauses, &[Row::new()]).is_some());
            let run = |clauses: &[Clause]| {
                std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    Executor::new(inputs.clone(), true).execute_clauses(clauses, None)
                }))
            };
            assert_eq!(
                panic_payload(run(&clauses)),
                panic_payload(run(&reference(&clauses)))
            );
        }
    }
}

#[test]
fn cancellation_and_timeouts_precede_zero_limit_and_source_access() {
    for timeout in [false, true] {
        let cancel = CancelToken::new();
        if timeout {
            cancel.timeout(60_000);
        } else {
            cancel.cancel();
        }
        let ex = Executor::new(vec![], true).with_cancel(cancel);
        let error = ex
            .execute(
                "MATCH (n) WITH n.id AS c WHERE c IS NOT NULL RETURN c LIMIT 0",
                None,
            )
            .unwrap_err();
        if timeout {
            assert!(matches!(error, CypherError::Timeout(60_000)));
        } else {
            assert!(matches!(error, CypherError::Cancelled));
        }
        assert_eq!(ex.poll.load(Ordering::Relaxed), 0);
    }
}
