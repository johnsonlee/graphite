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
