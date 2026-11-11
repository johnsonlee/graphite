//! Correctness only: modeled graphId components are not native multi-source scope
//! or performance evidence. The candidate catalog is separate from the trusted gate.
use graphite_cypher::{engine::Executor, property_graph::PropertyGraph, CypherDialect, Value};
use serde_json::{json, Value as Json};
use std::sync::Arc;

fn fixture(dialect: CypherDialect) -> Executor {
    let ex = Executor::property_graph(Arc::new(PropertyGraph::new())).with_dialect(dialect);
    ex.execute(
        "CREATE (a1:CallSite {graphId:'gA',caller_class:'A',callee_class:'X'}),
         (a2:CallSite {graphId:'gA',caller_class:'A',callee_class:'X'}),
         (a3:CallSite {graphId:'gA',caller_class:'B',callee_class:'Y'}),
         (a4:CallSite {graphId:'gA',caller_class:'B',callee_class:'X'}),
         (b1:CallSite {graphId:'gB',caller_class:'A',callee_class:'X'}),
         (b2:CallSite {graphId:'gB',caller_class:'A',callee_class:'Y'}),
         (b3:CallSite {graphId:'gB',caller_class:'B',callee_class:'X'}),
         (a1)-[:DATAFLOW]->(a2), (a1)-[:DATAFLOW]->(a2),
         (a1)-[:DATAFLOW]->(a3), (a2)-[:DATAFLOW]->(a3),
         (a3)-[:DATAFLOW]->(a4), (b1)-[:DATAFLOW]->(b2),
         (b2)-[:DATAFLOW]->(b3), (b3)-[:CALL]->(b1)",
        None,
    )
    .unwrap();
    ex
}

fn scalar(value: &Value) -> Json {
    match value {
        Value::Int(n) => json!(n),
        Value::Float(n) => json!(n),
        Value::Str(s) => json!(s.as_ref()),
        other => panic!("expected scalar catalog result, got {other:?}"),
    }
}

#[test]
fn complex_catalog_preserves_global_counts_distinct_and_optional_semantics() {
    let catalog: Vec<Json> = serde_json::from_str(include_str!(
        "../../../benchmark/complex_aggregation_queries.json"
    ))
    .unwrap();
    let expected = [
        (
            "bi-caller-dependency-rollup",
            vec!["graph", "caller", "calls", "targets"],
            json!([
                ["gA", "A", 2, 1],
                ["gA", "B", 2, 2],
                ["gB", "A", 2, 2],
                ["gB", "B", 1, 1]
            ]),
        ),
        (
            "bi-global-target-support",
            vec!["target", "graphs", "caller_graph_pairs", "calls"],
            json!([["X", 2, 4, 5], ["Y", 2, 2, 2]]),
        ),
        (
            "bi-two-hop-dataflow",
            vec!["graph", "paths", "starts", "destinations"],
            json!([["gA", 4, 2, 2], ["gB", 1, 1, 1]]),
        ),
        (
            "bi-dataflow-degree-histogram",
            vec!["graph", "degree", "sources"],
            json!([["gA", 1, 2], ["gA", 3, 1], ["gB", 1, 2]]),
        ),
        (
            "bi-dataflow-optional-sinks",
            vec!["graph", "sources", "edges", "sinks"],
            json!([["gA", 4, 5, 1], ["gB", 3, 2, 1]]),
        ),
        (
            "bi-dependency-co-use",
            vec![
                "left_target",
                "right_target",
                "caller_graph_pairs",
                "graphs",
            ],
            json!([["X", "Y", 2, 2]]),
        ),
    ];
    assert_eq!(catalog.len(), expected.len());
    for dialect in [CypherDialect::Graphite, CypherDialect::OpenCypherM23] {
        let ex = fixture(dialect);
        let empty = Executor::property_graph(Arc::new(PropertyGraph::new())).with_dialect(dialect);
        for (entry, (expected_name, columns, rows)) in catalog.iter().zip(&expected) {
            let name = entry["id"].as_str().unwrap();
            let query = entry["query"].as_str().unwrap();
            assert_eq!(name, *expected_name);
            let result = ex
                .execute(query, None)
                .unwrap_or_else(|e| panic!("{dialect:?} {name}: {e}"));
            assert_eq!(&result.columns, columns, "{dialect:?} {name}");
            let actual: Vec<Vec<Json>> = result
                .rows
                .iter()
                .map(|row| {
                    result
                        .columns
                        .iter()
                        .map(|column| scalar(&row[column]))
                        .collect()
                })
                .collect();
            // Legacy Graphite SUM returns Float, while M23 preserves an integer
            // sum over integer inputs. Assert each dialect's concrete number type.
            let expected_rows = match (dialect, name) {
                (CypherDialect::Graphite, "bi-caller-dependency-rollup") => json!([
                    ["gA", "A", 2.0, 1],
                    ["gA", "B", 2.0, 2],
                    ["gB", "A", 2.0, 2],
                    ["gB", "B", 1.0, 1]
                ]),
                (CypherDialect::Graphite, "bi-global-target-support") => {
                    json!([["X", 2, 4, 5.0], ["Y", 2, 2, 2.0]])
                }
                (CypherDialect::Graphite, "bi-dataflow-optional-sinks") => {
                    json!([["gA", 4, 5.0, 1.0], ["gB", 3, 2.0, 1.0]])
                }
                _ => rows.clone(),
            };
            assert_eq!(json!(actual), expected_rows, "{dialect:?} {name}");
            let empty_result = empty
                .execute(query, None)
                .unwrap_or_else(|e| panic!("empty {dialect:?} {name}: {e}"));
            assert_eq!(&empty_result.columns, columns, "empty {dialect:?} {name}");
            assert!(empty_result.rows.is_empty(), "empty {dialect:?} {name}");
        }
    }
}
