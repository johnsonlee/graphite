use graphite_cypher::{
    engine::{Executor, QueryResult},
    CypherDialect, Value,
};

fn run(query: &str, dialect: CypherDialect) -> QueryResult {
    Executor::new(vec![], false)
        .with_dialect(dialect)
        .execute(query, None)
        .unwrap()
}

#[test]
fn ordering_alias_expressions_preserves_each_profiles_binding_scope() {
    let query = "UNWIND [{num:1},{num:3},{num:-5}] AS n RETURN n.num AS n ORDER BY n+2";
    for (dialect, expected) in [
        (CypherDialect::Graphite, vec![1, 3, -5]),
        (CypherDialect::OpenCypherM23, vec![-5, 1, 3]),
    ] {
        let result = run(query, dialect);
        assert_eq!(result.columns, vec!["n"]);
        assert_eq!(
            result
                .rows
                .iter()
                .map(|row| match row["n"] {
                    Value::Int(n) => n,
                    _ => panic!("integer result"),
                })
                .collect::<Vec<_>>(),
            expected
        );
    }
}

#[test]
fn with_predicate_scope_and_distinct_boundary_are_explicit_dialect_contracts() {
    for (query, legacy, standard) in [
        ("UNWIND ['A','B','C'] AS a WITH a AS name WHERE name='B' OR a='C' RETURN name ORDER BY name",vec!["B"],vec!["B","C"]),
        ("UNWIND ['A','B','C'] AS a WITH DISTINCT a AS name WHERE a='B' RETURN name",vec![],vec!["B"]),
    ] {
        for (dialect, expected) in [(CypherDialect::Graphite,legacy),(CypherDialect::OpenCypherM23,standard)] {
            let result=run(query,dialect);
            assert_eq!(result.columns,vec!["name"]);
            assert_eq!(result.rows.iter().map(|row| row["name"].as_str().unwrap()).collect::<Vec<_>>(),expected);
        }
    }
}

#[test]
fn union_column_validation_is_standard_while_legacy_retains_dynamic_columns() {
    let query = "RETURN 1 AS a UNION RETURN 2 AS b";
    let legacy = run(query, CypherDialect::Graphite);
    assert_eq!(legacy.columns, vec!["a"]);
    assert_eq!(legacy.rows.len(), 2);
    assert!(matches!(legacy.rows[0]["a"], Value::Int(1)));
    assert!(matches!(legacy.rows[1]["b"], Value::Int(2)));
    let error = Executor::new(vec![], false)
        .with_dialect(CypherDialect::OpenCypherM23)
        .execute(query, None)
        .unwrap_err();
    let diagnostic = error.diagnostic().unwrap();
    assert_eq!(
        (diagnostic.error_type, diagnostic.phase, diagnostic.detail),
        ("SyntaxError", "compile time", "DifferentColumnsInUnion")
    );
}
