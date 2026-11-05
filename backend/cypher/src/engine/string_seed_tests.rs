use super::*;
use crate::ast::{Clause, CmpOp, Literal};
use crate::engine::{CancelToken, Source};
use crate::parser::parse;
use crate::value::Value;
use crate::CypherError;
use graphite_storage::node::{NodeKind, TAG_STRING_CONSTANT};
use graphite_storage::Graph;
use std::path::PathBuf;
use std::sync::Arc;

fn parsed(query: &str) -> (Vec<Pattern>, Option<Expr>) {
    let clauses = parse(query).unwrap();
    let Clause::Match { patterns, .. } = &clauses[0] else {
        panic!("MATCH expected: {clauses:?}")
    };
    let predicate = match clauses.get(1) {
        Some(Clause::Where(expr)) => Some(expr.clone()),
        _ => None,
    };
    (patterns.clone(), predicate)
}

fn eligible(query: &str, seed: &Row) -> bool {
    let (patterns, predicate) = parsed(query);
    StringSeedPlan::build(&patterns, predicate.as_ref(), seed).is_some()
}

#[test]
fn seeds_fixed_and_variable_walks_from_string_value_predicates() {
    for query in [
        r#"MATCH (s:StringConstant)-[r]-(n) WHERE s.value = "needle" RETURN n"#,
        r#"MATCH (s:StringConstant)-[*1..2]-(n) WHERE s.value CONTAINS "needle" RETURN n"#,
        r#"MATCH p = (s:StringConstant)-[*1..3]-(n) WHERE s.value CONTAINS "a" OR toLower(s.value) CONTAINS "b" RETURN p"#,
        r#"MATCH (s:StringConstant {value: "needle"})-[r]-(n) RETURN n"#,
        r#"MATCH (s:StringConstant)-[r]-(n), (n)-[q]-(m) WHERE s.value STARTS WITH "need" RETURN m"#,
    ] {
        assert!(eligible(query, &Row::new()), "expected seed plan: {query}");
    }
}

#[test]
fn declines_shapes_without_a_safe_first_node_filter() {
    for query in [
        r#"MATCH (s:StringConstant) WHERE s.value = "needle" RETURN s"#,
        r#"MATCH (s:StringConstant)-[r]-(n) WHERE n.name = "needle" RETURN n"#,
        r#"MATCH (s:StringConstant)-[r]-(n) WHERE s.value = "needle" OR n.name = "needle" RETURN n"#,
        r#"MATCH (s:CallSiteNode)-[r]-(n) WHERE s.callee_name = "needle" RETURN n"#,
        r#"MATCH (s:Constant)-[r]-(n) WHERE s.value = "needle" RETURN n"#,
        r#"MATCH ()-[r]-(n) WHERE n.value = "needle" RETURN n"#,
    ] {
        assert!(
            !eligible(query, &Row::new()),
            "unexpected seed plan: {query}"
        );
    }

    let mut seed = Row::new();
    seed.insert("prior".into(), Value::Int(1));
    assert!(!eligible(
        r#"MATCH (s:StringConstant)-[r]-(n) WHERE s.value = "needle" RETURN n"#,
        &seed
    ));
}

fn fixture() -> Option<Arc<Graph>> {
    let Some(dir) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
        eprintln!("GRAPHITE_INDEX_FIXTURE unset; skipping real StringConstant seed tests");
        return None;
    };
    Some(Arc::new(Graph::load(&PathBuf::from(dir)).unwrap()))
}

fn connected_literal(graph: &Graph) -> Option<String> {
    graph
        .ids_by_tag(TAG_STRING_CONSTANT)
        .iter()
        .copied()
        .filter(|&id| graph.incoming(id).next().is_some() || graph.outgoing(id).next().is_some())
        .max_by_key(|&id| graph.incoming(id).count() + graph.outgoing(id).count())
        .and_then(|id| match graph.node(id)?.kind {
            NodeKind::StringConstant(value) => Some(graph.str(value).to_owned()),
            _ => None,
        })
}

fn multi(graph: Arc<Graph>) -> Executor {
    Executor::new(
        vec![
            Source {
                id: Arc::from("first"),
                graph: graph.clone(),
            },
            Source {
                id: Arc::from("second"),
                graph,
            },
        ],
        true,
    )
    .with_probe()
}

/// Wrap the predicate in a semantically equivalent boolean equality that ScanPlan
/// cannot turn into a leaf.  The ordinary matcher is then the differential oracle.
fn generic_clauses(query: &str) -> Vec<Clause> {
    let mut clauses = parse(query).unwrap();
    let predicate = match &mut clauses[1] {
        Clause::Where(predicate) => {
            *predicate = Expr::Comparison {
                op: CmpOp::Eq,
                left: Box::new(predicate.clone()),
                right: Box::new(Expr::Literal(Literal::Bool(true))),
            };
            predicate.clone()
        }
        other => panic!("standalone WHERE expected: {other:?}"),
    };
    let Clause::Match { patterns, .. } = &clauses[0] else {
        unreachable!()
    };
    assert!(StringSeedPlan::build(patterns, Some(&predicate), &Row::new()).is_none());
    clauses
}

fn compare_generic(ex: &Executor, query: &str) -> crate::engine::QueryResult {
    let actual_clauses = parse(query).unwrap();
    let Clause::Match { patterns, .. } = &actual_clauses[0] else {
        unreachable!()
    };
    let Clause::Where(predicate) = &actual_clauses[1] else {
        panic!("standalone WHERE expected")
    };
    assert!(StringSeedPlan::build(patterns, Some(predicate), &Row::new()).is_some());
    let actual = ex.execute_clauses(&actual_clauses, None).unwrap();
    let expected = ex.execute_clauses(&generic_clauses(query), None).unwrap();
    assert_eq!(
        format!("{actual:?}"),
        format!("{expected:?}"),
        "typed rows, order, duplicates, provenance and LIMIT probe: {query}"
    );
    actual
}

#[test]
fn real_fixed_and_variable_walks_equal_the_generic_matcher() {
    let Some(graph) = fixture() else {
        return;
    };
    let literal = connected_literal(&graph).expect("fixture has a connected StringConstant");
    let quoted = serde_json::to_string(&literal).unwrap();
    let ex = multi(graph);

    let fixed = format!(
        "MATCH (s:StringConstant)-[r]-(n) WHERE s.value = {quoted} \
         RETURN s.value AS value, s.id AS source, type(r) AS relation, n.id AS target LIMIT 6"
    );
    let rows = compare_generic(&ex, &fixed);
    assert!(!rows.rows.is_empty());
    assert!(
        rows.more,
        "the sixth retained row must have a probe successor"
    );

    let mixed = format!(
        "MATCH (s:StringConstant)-[r]-(n) WHERE s.value = {quoted} AND n.id >= 0 \
         RETURN s.id AS source, type(r) AS relation, n.id AS target LIMIT 6"
    );
    let rows = compare_generic(&ex, &mixed);
    assert!(!rows.rows.is_empty());
    assert!(rows.more, "the complete WHERE must retain probe semantics");

    let variable = format!(
        "MATCH p = (s:StringConstant)-[*1..3]-(n) WHERE \
         s.value CONTAINS {quoted} OR s.value CONTAINS \"__graphite_absent_a__\" OR \
         toLower(s.value) CONTAINS \"__graphite_absent_b__\" \
         RETURN s.value AS value, s.id AS source, n.id AS target, p LIMIT 6"
    );
    let rows = compare_generic(&ex, &variable);
    assert!(!rows.rows.is_empty());
    assert!(
        rows.more,
        "the variable walk must propagate early termination"
    );
}

#[test]
fn cancelled_and_timed_out_seed_scans_keep_their_errors() {
    let Some(graph) = fixture() else {
        return;
    };
    let literal = connected_literal(&graph).expect("fixture has a connected StringConstant");
    let quoted = serde_json::to_string(&literal).unwrap();
    let cancel = CancelToken::new();
    cancel.cancel();
    let ex = Executor::single("fixture", graph.clone()).with_cancel(cancel);
    let err = ex
        .execute(
            &format!(
                "MATCH (s:StringConstant)-[*1..2]-(n) WHERE s.value = {quoted} RETURN n LIMIT 1"
            ),
            None,
        )
        .unwrap_err();
    assert!(matches!(err, CypherError::Cancelled));

    let timeout = CancelToken::with_timeout(0);
    let ex = Executor::single("fixture", graph).with_cancel(timeout);
    let err = ex
        .execute(
            &format!(
                "MATCH (s:StringConstant)-[*1..2]-(n) WHERE s.value = {quoted} RETURN n LIMIT 1"
            ),
            None,
        )
        .unwrap_err();
    assert!(matches!(err, CypherError::Timeout(0)));
}

#[test]
fn inline_literal_seed_defers_predicates_that_need_later_bindings() {
    for predicate in [
        "r.kind = 'PARAMETER_PASS'",
        "n.callee_name = 'success'",
        "r.kind = $kind",
        "(s.value = 'needle' AND r.kind = 'PARAMETER_PASS') OR (s.value = 'other' AND n.line = 1)",
    ] {
        let query = format!(
            "MATCH (s:StringConstant {{value: 'needle'}})-[r]->(n) WHERE {predicate} RETURN n"
        );
        let (patterns, predicate) = parsed(&query);
        let plan = StringSeedPlan::build(&patterns, predicate.as_ref(), &Row::new())
            .expect("inline literal must retain its seed optimization");
        assert!(
            plan.conjunct.is_none(),
            "must defer later bindings: {query}"
        );
    }
    let (patterns, predicate) = parsed(
        "MATCH (s:StringConstant {value: 'needle'})-[r]->(n) WHERE s.value STARTS WITH 'need' RETURN n",
    );
    let plan = StringSeedPlan::build(&patterns, predicate.as_ref(), &Row::new()).unwrap();
    assert!(
        plan.conjunct.is_some(),
        "self-contained WHERE still seeds the walk"
    );
}

#[test]
fn persisted_inline_literal_relationship_filters_match_generic_across_graphs() {
    use serde_json::json;
    struct Temp(PathBuf);
    impl Drop for Temp {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
    let path = Temp(std::env::temp_dir().join(format!(
        "graphite-string-seed-bindings-{}",
        std::process::id()
    )));
    let caller = json!({"declaringClass":"client.js", "name":"save", "parameterTypes":[], "returnType":"void"});
    let success = json!({"declaringClass":"alerts", "name":"success", "parameterTypes":["string"], "returnType":"void"});
    let other = json!({"declaringClass":"alerts", "name":"other", "parameterTypes":["string"], "returnType":"void"});
    let graph_json = json!({"version":1,"methods":[caller,success,other],"nodes":[
        {"id":0,"kind":"StringConstant","value":"User added"},
        {"id":1,"kind":"CallSite","caller":caller,"callee":success,"line":10,"receiver":null,"arguments":[0]},
        {"id":2,"kind":"CallSite","caller":caller,"callee":other,"line":11,"receiver":null,"arguments":[0]},
        {"id":3,"kind":"StringConstant","value":"Unrelated"}
    ],"edges":[
        {"from":0,"to":1,"kind":"PARAMETER_PASS"},
        {"from":0,"to":2,"kind":"ASSIGN"},
        {"from":3,"to":1,"kind":"PARAMETER_PASS"}
    ]});
    graphite_storage::interchange::import_json(
        serde_json::to_vec(&graph_json).unwrap().as_slice(),
        &path.0,
    )
    .unwrap();
    let ex = multi(Arc::new(Graph::load(&path.0).unwrap())).with_params(
        [
            ("kind".into(), Value::str("PARAMETER_PASS")),
            ("name".into(), Value::str("success")),
        ]
        .into_iter()
        .collect(),
    );
    for predicate in [
        "r.kind = 'PARAMETER_PASS'",
        "n.callee_name = 'success'",
        "r.kind = $kind AND n.callee_name = $name",
        "s.value STARTS WITH 'User' AND r.kind = 'PARAMETER_PASS'",
        "(s.value = 'User added' AND r.kind = 'PARAMETER_PASS') OR (s.value = 'absent' AND n.callee_name = 'other')",
    ] {
        let suffix = format!("-[r]->(n:CallSiteNode) WHERE {predicate} RETURN graphId(s) AS graph, s.value AS value, r.kind AS kind, n.callee_name AS name ORDER BY graph");
        let query = format!("MATCH (s:StringConstant {{value: 'User added'}}){suffix}");
        // A nonliteral inline value leaves candidate enumeration to the general matcher.
        let generic = format!("MATCH (s:StringConstant {{value: 'User added' + ''}}){suffix}");
        let (patterns, pred) = parsed(&generic);
        assert!(StringSeedPlan::build(&patterns, pred.as_ref(), &Row::new()).is_none());
        let actual = ex.execute(&query, None).unwrap();
        let expected = ex.execute(&generic, None).unwrap();
        assert_eq!(format!("{actual:?}"), format!("{expected:?}"), "{query}");
        assert_eq!(actual.rows.len(), 2, "one match from each actual graph: {query}");
        for (row, graph) in actual.rows.iter().zip(["first", "second"]) {
            assert_eq!(row["graph"].as_str(), Some(graph));
            assert_eq!(row["value"].as_str(), Some("User added"));
            assert_eq!(row["kind"].as_str(), Some("PARAMETER_PASS"));
            assert_eq!(row["name"].as_str(), Some("success"));
        }
    }
    let rejected = ex.execute(
        "MATCH (s:StringConstant {value: 'User added'})-[r]->(n) WHERE r.kind = 'PARAMETER_PASS' AND n.callee_name = 'other' RETURN n", None,
    ).unwrap();
    assert!(
        rejected.rows.is_empty(),
        "deferred predicates must still filter complete rows"
    );
}
