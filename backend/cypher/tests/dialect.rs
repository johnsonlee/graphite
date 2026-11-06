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

#[test]
fn graphite_distinct_allows_legacy_hidden_order_sources() {
    let clauses = graphite_cypher::parser::parse(
        "MATCH (m) RETURN DISTINCT properties(m) AS value ORDER BY m.graphId, m.return_type",
    )
    .unwrap();
    assert!(graphite_cypher::validation::resolve_with_dialect(
        &clauses,
        None,
        CypherDialect::Graphite
    )
    .is_ok());
    let error = graphite_cypher::validation::resolve_with_dialect(
        &clauses,
        None,
        CypherDialect::OpenCypherM23,
    )
    .unwrap_err();
    assert_eq!(error.diagnostic().unwrap().detail, "UndefinedVariable");
}

#[test]
fn graphite_dynamic_indexing_extensions_remain_available() {
    let legacy = Executor::new(vec![], false);
    for (query, expected) in [
        ("WITH 'key' AS n RETURN n[n] AS value", None),
        ("RETURN [10, 20][1.9] AS value", Some(20)),
        ("RETURN {a:1}[0] AS value", None),
    ] {
        let result = legacy.execute(query, None).unwrap();
        match expected {
            Some(number) => {
                assert!(matches!(result.rows[0]["value"], Value::Int(n) if n == number))
            }
            None => assert!(matches!(result.rows[0]["value"], Value::Null)),
        }
        let error = Executor::new(vec![], false)
            .with_dialect(CypherDialect::OpenCypherM23)
            .execute(query, None)
            .unwrap_err();
        assert_eq!(error.diagnostic().unwrap().error_type, "TypeError");
    }
}

#[test]
fn graph_functions_check_runtime_types_in_standard_mode_and_preserve_legacy_fallbacks() {
    for (query, value) in [
        ("RETURN labels($value)", Value::Int(1)),
        ("RETURN type($value)", Value::map(indexmap::IndexMap::new())),
    ] {
        let standard = Executor::new(vec![], false).with_dialect(CypherDialect::OpenCypherM23);
        let standard =
            standard.with_params(indexmap::IndexMap::from([("value".into(), value.clone())]));
        let err = standard.execute(query, None).unwrap_err();
        let d = err.diagnostic().unwrap();
        assert_eq!(
            (d.error_type, d.phase, d.detail),
            ("TypeError", "runtime", "InvalidArgumentValue")
        );
        assert!(Executor::new(vec![], false)
            .with_params(indexmap::IndexMap::from([("value".into(), value)]))
            .execute(query, None)
            .is_ok());
    }
    for query in ["RETURN labels(null)", "RETURN type(null)"] {
        let result = Executor::new(vec![], false)
            .with_dialect(CypherDialect::OpenCypherM23)
            .execute(query, None)
            .unwrap();
        assert!(result.rows[0][&result.columns[0]].is_null());
    }
}

#[test]
fn correlated_exists_inherits_the_enclosing_dialect_through_projections() {
    for query in [
        "WITH 1 AS n RETURN EXISTS { UNWIND [2] AS n RETURN n } AS yes",
        "WITH 1 AS n WITH n AS alias RETURN EXISTS { UNWIND [2] AS alias RETURN alias } AS yes",
        "WITH 1 AS n RETURN [x IN [1] | EXISTS { UNWIND [2] AS n RETURN n }] AS yes",
    ] {
        let result = Executor::new(vec![], false).execute(query, None).unwrap();
        let answer = &result.rows[0]["yes"];
        if let Value::List(values) = answer {
            assert_eq!(values.len(), 1);
            assert!(matches!(values[0], Value::Bool(true)));
        } else {
            assert!(matches!(answer, Value::Bool(true)));
        }
        let error = Executor::new(vec![], false)
            .with_dialect(CypherDialect::OpenCypherM23)
            .execute(query, None)
            .unwrap_err()
            .diagnostic()
            .unwrap();
        assert_eq!(
            (error.error_type, error.phase, error.detail),
            ("SyntaxError", "compile time", "VariableAlreadyBound"),
            "{query}"
        );
    }
}
