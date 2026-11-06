use graphite_cypher::{engine::Executor, property_graph::PropertyGraph, CypherDialect, Value};
use std::sync::Arc;

#[test]
fn dialect_names_are_explicit_and_round_trip() {
    assert_eq!(CypherDialect::default(), CypherDialect::Graphite);
    for dialect in [CypherDialect::Graphite, CypherDialect::OpenCypherM23] {
        assert_eq!(
            dialect.to_string().parse::<CypherDialect>().unwrap(),
            dialect
        );
    }
    assert!("opencypher".parse::<CypherDialect>().is_err());
}

#[test]
fn default_preserves_container_formatting_and_m23_rejects_at_runtime() {
    let legacy = Executor::new(vec![], false);
    let result = legacy
        .execute("RETURN toString([1, 2]) AS value", None)
        .unwrap();
    assert_eq!(result.rows[0]["value"].as_str(), Some("[1, 2]"));
    let standard = Executor::new(vec![], false).with_dialect(CypherDialect::OpenCypherM23);
    for value in ["[1, 2]", "{answer: 42}"] {
        let error = standard
            .execute(&format!("RETURN toString({value})"), None)
            .unwrap_err();
        let diagnostic = error.diagnostic().unwrap();
        assert_eq!(
            (diagnostic.error_type, diagnostic.phase, diagnostic.detail),
            ("TypeError", "runtime", "InvalidArgumentValue")
        );
    }
    let result = standard.execute("RETURN toString(42) AS number, toString(true) AS boolean, toString(null) AS nil, toString(date('2000-01-01')) AS date", None).unwrap();
    assert_eq!(result.rows[0]["number"].as_str(), Some("42"));
    assert_eq!(result.rows[0]["boolean"].as_str(), Some("true"));
    assert!(matches!(result.rows[0]["nil"], Value::Null));
    assert_eq!(result.rows[0]["date"].as_str(), Some("2000-01-01"));
}

#[test]
fn m23_rejects_graph_entities_independently_of_storage_source() {
    let graph = Arc::new(PropertyGraph::default());
    let standard = Executor::property_graph(graph).with_dialect(CypherDialect::OpenCypherM23);
    standard.execute("CREATE (a)-[:T]->(b)", None).unwrap();
    for query in [
        "MATCH (n) RETURN toString(n)",
        "MATCH ()-[r]->() RETURN toString(r)",
        "MATCH p=()-->() RETURN toString(p)",
    ] {
        let error = standard.execute(query, None).unwrap_err();
        assert_eq!(error.diagnostic().unwrap().detail, "InvalidArgumentValue");
    }
}

#[test]
fn graphite_unwind_rebinding_is_preserved_while_m23_rejects_it() {
    let query = "WITH 1 AS n UNWIND [2] AS n RETURN n";
    let result = Executor::new(vec![], false).execute(query, None).unwrap();
    assert!(matches!(result.rows[0]["n"], Value::Int(2)));
    let error = Executor::new(vec![], false)
        .with_dialect(CypherDialect::OpenCypherM23)
        .execute(query, None)
        .unwrap_err();
    assert_eq!(error.diagnostic().unwrap().detail, "VariableAlreadyBound");
}
