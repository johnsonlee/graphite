use graphite_cypher::{
    ast::{BinOp, Clause, CmpOp, Expr, Literal},
    engine::Executor,
    parser, Value,
};

fn scalar(query: &str) -> Value {
    let result = Executor::new(vec![], false).execute(query, None).unwrap();
    assert_eq!(result.rows.len(), 1, "{query}");
    assert_eq!(result.columns.len(), 1, "{query}");
    result.rows[0][&result.columns[0]].clone()
}
fn same(query: &str, expected: Value) {
    assert_eq!(
        format!("{:?}", scalar(query)),
        format!("{expected:?}"),
        "{query}"
    );
}
#[test]
fn date_arithmetic_uses_complete_elapsed_days_and_duration_scaling_discards_fractional_nanos() {
    same(
        "RETURN toString(date('2000-01-01') + duration({hours: 25}))",
        Value::str("2000-01-02"),
    );
    same(
        "RETURN toString(date('2000-01-01') - duration({hours: 25}))",
        Value::str("1999-12-31"),
    );
    same(
        "RETURN toString(date('2000-01-01') + duration({seconds: -86400, nanoseconds: 1}))",
        Value::str("2000-01-01"),
    );
    same(
        "RETURN toString(duration({nanoseconds: 1}) / 2)",
        Value::str("PT0S"),
    );
    same(
        "RETURN toString(duration({nanoseconds: 3}) / 2)",
        Value::str("PT0.000000001S"),
    );
    same(
        "RETURN toString(duration({nanoseconds: 1}) * 2)",
        Value::str("PT0.000000002S"),
    );
}
#[test]
fn chained_comparisons_compare_adjacent_operands() {
    let clauses = parser::parse("RETURN 1 < 2 <= 3 AS valid").unwrap();
    let Clause::Return {
        items: Some(items), ..
    } = &clauses[0]
    else {
        panic!("missing RETURN")
    };
    assert_eq!(
        items[0].expr,
        Expr::And(
            Box::new(Expr::Comparison {
                op: CmpOp::Lt,
                left: Box::new(Expr::Literal(Literal::Int(1))),
                right: Box::new(Expr::Literal(Literal::Int(2))),
            }),
            Box::new(Expr::Comparison {
                op: CmpOp::Le,
                left: Box::new(Expr::Literal(Literal::Int(2))),
                right: Box::new(Expr::Literal(Literal::Int(3))),
            }),
        )
    );
    same("RETURN 1 < 2 <= 3", Value::Bool(true));
    same("RETURN 1 < 3 < 2", Value::Bool(false));
    same("RETURN 'a' < 'b' <= 'c'", Value::Bool(true));
    same("RETURN 1 < 2 < null", Value::Null);
    // Explicit parentheses retain their boolean operand.
    same("RETURN (1 < 2) = true", Value::Bool(true));
}
#[test]
fn extrema_order_strings_lists_and_mixed_types() {
    same(
        "UNWIND ['a', 'b', 'B', null, 'abc', 'abc1'] AS i RETURN max(i)",
        Value::str("b"),
    );
    same(
        "UNWIND ['a', 'b', 'B', null, 'abc', 'abc1'] AS i RETURN min(i)",
        Value::str("B"),
    );
    same(
        "UNWIND [[1], [2], [2, 1]] AS x RETURN max(x)",
        Value::list(vec![Value::Int(2), Value::Int(1)]),
    );
    same(
        "UNWIND [1, 'a', null, [1, 2], 0.2, 'b'] AS x RETURN min(x)",
        Value::list(vec![Value::Int(1), Value::Int(2)]),
    );
}
#[test]
fn exponentiation_is_left_associative_in_ast_and_execution() {
    let clauses = parser::parse("RETURN 2 ^ 3 ^ 2 AS n").unwrap();
    let Clause::Return {
        items: Some(items), ..
    } = &clauses[0]
    else {
        panic!("missing RETURN")
    };
    assert_eq!(
        items[0].expr,
        Expr::Binary {
            op: BinOp::Pow,
            left: Box::new(Expr::Binary {
                op: BinOp::Pow,
                left: Box::new(Expr::Literal(Literal::Int(2))),
                right: Box::new(Expr::Literal(Literal::Int(3)))
            }),
            right: Box::new(Expr::Literal(Literal::Int(2)))
        }
    );
    same("RETURN 2 ^ 3 ^ 2", Value::Float(64.0));
}
#[test]
fn signed_minimum_integer_literals_are_valid() {
    for literal in [
        "-9223372036854775808",
        "-0x8000000000000000",
        "-0o1000000000000000000000",
    ] {
        let query = format!("RETURN {literal}");
        let clauses = parser::parse(&query).unwrap();
        let Clause::Return {
            items: Some(items), ..
        } = &clauses[0]
        else {
            panic!("missing RETURN")
        };
        assert_eq!(items[0].expr, Expr::Literal(Literal::Int(i64::MIN)));
        same(&query, Value::Int(i64::MIN));
    }
}
#[test]
fn decimal_string_to_integer_truncates_without_losing_integer_precision() {
    same("RETURN toInteger('2.9')", Value::Int(2));
    same("RETURN toInteger('-1.7')", Value::Int(-1));
    same(
        "RETURN toInteger('9223372036854775807')",
        Value::Int(i64::MAX),
    );
    same("RETURN toInteger('not a number')", Value::Null);
}
#[test]
fn list_comparison_is_lexicographic_and_three_valued() {
    same("RETURN [1, 0] >= [1]", Value::Bool(true));
    same("RETURN [1, null] >= [1]", Value::Bool(true));
    same("RETURN [1, 2] >= [1, null]", Value::Null);
    same("RETURN [1, 2] < true", Value::Null);
    same("RETURN [1, 0] < [2, null]", Value::Bool(true));
}
#[test]
fn float_nan_comparison_is_not_an_integer_divide_error() {
    same("RETURN 0.0 / 0.0 = 0.0 / 0.0", Value::Bool(false));
    same("RETURN 0.0 / 0.0 <> 1", Value::Bool(true));
    for operator in ["<", ">", "<=", ">="] {
        same(
            &format!("RETURN 0.0 / 0.0 {operator} 1"),
            Value::Bool(false),
        );
    }
    assert!(Executor::new(vec![], false)
        .execute("RETURN 1 / 0", None)
        .is_err());
}
#[test]
fn list_prepend_preserves_scalar_types() {
    same(
        "RETURN false + [4]",
        Value::list(vec![Value::Bool(false), Value::Int(4)]),
    );
    same(
        "RETURN 'x' + [4]",
        Value::list(vec![Value::str("x"), Value::Int(4)]),
    );
    same(
        "RETURN [1] + 'x'",
        Value::list(vec![Value::Int(1), Value::str("x")]),
    );
}

#[test]
fn aggregates_work_inside_complete_projected_expressions() {
    same("MATCH (a) RETURN count(a) > 0", Value::Bool(false));
    same("UNWIND [1,2] AS n RETURN 1 + count(*)", Value::Int(3));
    same(
        "UNWIND [1,2] AS n RETURN {total: count(*)}",
        Value::map(indexmap::IndexMap::from([("total".into(), Value::Int(2))])),
    );
    same(
        "UNWIND [1,2] AS n RETURN [count(*)]",
        Value::list(vec![Value::Int(2)]),
    );
    same(
        "UNWIND [1,2] AS n RETURN CASE WHEN count(*) > 1 THEN 'yes' ELSE 'no' END",
        Value::str("yes"),
    );
}
#[test]
fn aggregate_order_expressions_use_finalized_values_and_aliases() {
    let ex = Executor::new(vec![], false).with_params(indexmap::IndexMap::from([(
        "offset".into(),
        Value::Int(10),
    )]));
    let result = ex
        .execute(
            "MATCH (p) RETURN avg(p.age) AS a ORDER BY $offset + avg(p.age)",
            None,
        )
        .unwrap();
    assert_eq!(result.columns, vec!["a"]);
    assert_eq!(format!("{:?}", result.rows[0]["a"]), "Null");
    let result = ex
        .execute(
            "UNWIND [1,1,2] AS n RETURN n, count(*) AS c ORDER BY c + 1 DESC",
            None,
        )
        .unwrap();
    assert_eq!(result.rows.len(), 2);
    assert_eq!(format!("{:?}", result.rows[0]["n"]), "Int(1)");
    assert_eq!(format!("{:?}", result.rows[0]["c"]), "Int(2)");
}

#[test]
fn union_column_contract_is_checked_before_graph_mutation() {
    use graphite_cypher::property_graph::PropertyGraph;
    use std::sync::Arc;
    for union in ["UNION", "UNION ALL"] {
        let graph = Arc::new(PropertyGraph::new());
        let ex = Executor::property_graph(graph.clone());
        let error = ex
            .execute(
                &format!("CREATE (:A) RETURN 1 AS a {union} RETURN 2 AS b"),
                None,
            )
            .unwrap_err();
        let diagnostic = error.diagnostic().unwrap();
        assert_eq!(diagnostic.error_type, "SyntaxError");
        assert_eq!(diagnostic.phase, "compile time");
        assert_eq!(diagnostic.detail, "DifferentColumnsInUnion");
        assert!(graph.snapshot().nodes.is_empty());
    }
    let result = Executor::new(vec![], false)
        .execute("RETURN 1 AS a UNION ALL RETURN 2 AS a", None)
        .unwrap();
    assert_eq!(result.rows.len(), 2);
    assert_eq!(format!("{:?}", result.rows[0]["a"]), "Int(1)");
    assert_eq!(format!("{:?}", result.rows[1]["a"]), "Int(2)");
}

#[test]
fn result_headers_preserve_original_expression_spelling_and_unicode_offsets() {
    let query = "WITH '中文' AS text\nRETURN (12 / 4) * (3 - 2 * 4), text";
    let clauses = parser::parse(query).unwrap();
    let Clause::Return {
        items: Some(items), ..
    } = clauses.last().unwrap()
    else {
        panic!("RETURN")
    };
    assert_eq!(
        items[0].source_text.as_deref(),
        Some("(12 / 4) * (3 - 2 * 4)")
    );
    assert_eq!(items[1].source_text, None);
    let result = Executor::new(vec![], false).execute(query, None).unwrap();
    assert_eq!(result.columns, vec!["(12 / 4) * (3 - 2 * 4)", "text"]);
    assert_eq!(
        format!("{:?}", result.rows[0]["(12 / 4) * (3 - 2 * 4)"]),
        "Int(-15)"
    );
    assert_eq!(format!("{:?}", result.rows[0]["text"]), "Str(\"中文\")");
    let result = Executor::new(vec![], false)
        .execute("WITH 1 AS `x` RETURN `x`", None)
        .unwrap();
    assert_eq!(result.columns, vec!["x"]);
}

#[test]
fn null_slices_and_graph_functions_preserve_null_and_map_values() {
    for slice in ["null..null", "1..null", "null..3", "..null", "null.."] {
        same(&format!("RETURN [1,2,3][{slice}]"), Value::Null);
    }
    same(
        "RETURN [1,2,3][1..]",
        Value::list(vec![Value::Int(2), Value::Int(3)]),
    );
    same("RETURN labels(null)", Value::Null);
    same(
        "RETURN properties({name:'Popeye',level:9001})",
        Value::map(indexmap::IndexMap::from([
            ("name".into(), Value::str("Popeye")),
            ("level".into(), Value::Int(9001)),
        ])),
    );
}
#[test]
fn integer_sum_retains_exact_integer_type_and_promotes_only_floating_inputs() {
    same(
        "UNWIND [1000000000,1000000001,1000000002] AS n RETURN sum(n)",
        Value::Int(3000000003),
    );
    same("MATCH (n) RETURN sum(n.value)", Value::Int(0));
    same("UNWIND [1,2.5] AS n RETURN sum(n)", Value::Float(3.5));
    let error = Executor::new(vec![], false)
        .execute("UNWIND [9223372036854775807,1] AS n RETURN sum(n)", None)
        .unwrap_err();
    let diagnostic = error.diagnostic().unwrap();
    assert_eq!(diagnostic.error_type, "ArithmeticError");
    assert_eq!(diagnostic.detail, "IntegerOverflow");
}

#[test]
fn percentile_aggregations_use_the_requested_parameter_and_reject_out_of_range_values() {
    for (p, expected) in [(0.0, 10.0), (0.5, 20.0), (1.0, 30.0)] {
        for function in ["percentileDisc", "percentileCont"] {
            same(
                &format!("UNWIND [10.0,20.0,30.0] AS x RETURN {function}(x,{p})"),
                Value::Float(expected),
            );
        }
    }
    same(
        "UNWIND [10.0,20.0,30.0] AS x RETURN percentileCont(x,0.25)",
        Value::Float(15.0),
    );
    for p in [-1.0, 1.1, 1000.0] {
        for function in ["percentileDisc", "percentileCont"] {
            let err = Executor::new(vec![], false)
                .execute(
                    &format!("UNWIND [10.0] AS x RETURN {function}(x,{p})"),
                    None,
                )
                .unwrap_err();
            let diagnostic = err.diagnostic().unwrap();
            assert_eq!(diagnostic.error_type, "ArgumentError");
            assert_eq!(diagnostic.phase, "runtime");
            assert_eq!(diagnostic.detail, "NumberOutOfRange");
        }
    }
}

#[test]
fn order_by_expressions_resolve_projected_aliases_before_shadowed_input_bindings() {
    let ex = Executor::new(vec![], false);
    let result = ex
        .execute(
            "UNWIND [{num: 1}, {num: 3}, {num: -5}] AS n RETURN n.num AS n ORDER BY n + 2",
            None,
        )
        .unwrap();
    assert_eq!(result.columns, vec!["n"]);
    assert_eq!(
        result
            .rows
            .iter()
            .map(|r| format!("{:?}", r["n"]))
            .collect::<Vec<_>>(),
        vec!["Int(-5)", "Int(1)", "Int(3)"]
    );
    let result = ex
        .execute(
            "UNWIND [4, 2, 0, 3, 1] AS x WITH x % 3 AS x ORDER BY x * -1 LIMIT 3 RETURN x",
            None,
        )
        .unwrap();
    assert_eq!(
        result
            .rows
            .iter()
            .map(|r| format!("{:?}", r["x"]))
            .collect::<Vec<_>>(),
        vec!["Int(2)", "Int(1)", "Int(1)"]
    );
}

#[test]
fn range_is_integer_only_checked_at_boundaries_and_cancellable() {
    same(
        "RETURN range(9223372036854775806,9223372036854775807)",
        Value::list(vec![Value::Int(i64::MAX - 1), Value::Int(i64::MAX)]),
    );
    same(
        "RETURN range(-9223372036854775807,-9223372036854775808,-1)",
        Value::list(vec![Value::Int(i64::MIN + 1), Value::Int(i64::MIN)]),
    );
    for value in ["true", "0.0", "'1'", "[1]", "{}"] {
        let err = Executor::new(vec![], false)
            .execute(&format!("RETURN range({value},2)"), None)
            .unwrap_err();
        let d = err.diagnostic().unwrap();
        assert_eq!(
            (d.error_type, d.phase, d.detail),
            ("ArgumentError", "runtime", "InvalidArgumentType")
        );
    }
    let err = Executor::new(vec![], false)
        .execute("RETURN range(0,1,0)", None)
        .unwrap_err();
    assert_eq!(err.diagnostic().unwrap().detail, "NumberOutOfRange");
    let cancel = graphite_cypher::engine::CancelToken::new();
    cancel.cancel();
    let ex = Executor::new(vec![], false).with_cancel(cancel);
    assert!(matches!(
        graphite_cypher::functions::call("range", &[Value::Int(0), Value::Int(1000)], &ex),
        Err(graphite_cypher::CypherError::Cancelled)
    ));
}

#[test]
fn skip_limit_parameters_require_nonnegative_integer_values() {
    for (value, detail) in [
        (Value::Int(-1), "NegativeIntegerArgument"),
        (Value::Float(0.0), "InvalidArgumentType"),
        (Value::str("1"), "InvalidArgumentType"),
    ] {
        for clause in ["SKIP", "LIMIT"] {
            let ex = Executor::new(vec![], false)
                .with_params(indexmap::IndexMap::from([("n".into(), value.clone())]));
            let err = ex
                .execute(&format!("RETURN 1 {clause} $n"), None)
                .unwrap_err();
            let d = err.diagnostic().unwrap();
            assert_eq!(
                (d.error_type, d.phase, d.detail),
                ("SyntaxError", "runtime", detail)
            );
        }
    }
}

#[test]
fn with_where_sees_input_and_projected_bindings_without_leaking_dropped_variables() {
    let ex = Executor::new(vec![], false);
    let query="UNWIND [{name:'A'},{name:'B'},{name:'C'}] AS a WITH a.name AS name WHERE name = 'B' OR a.name = 'C' RETURN * ORDER BY name";
    let result = ex.execute(query, None).unwrap();
    assert_eq!(result.columns, vec!["name"]);
    assert_eq!(
        result
            .rows
            .iter()
            .map(|r| r["name"].as_str().unwrap())
            .collect::<Vec<_>>(),
        vec!["B", "C"]
    );
    assert!(result.rows.iter().all(|r| !r.contains_key("a")));
    let graph = std::sync::Arc::new(graphite_cypher::property_graph::PropertyGraph::new());
    let ex = Executor::property_graph(graph);
    ex.execute("CREATE ({name:'A'}),({name:'B'}),({name:'C'})", None)
        .unwrap();
    let result=ex.execute("MATCH (a) WITH a.name AS name WHERE name = 'B' OR a.name = 'C' RETURN name ORDER BY name",None).unwrap();
    assert_eq!(
        result
            .rows
            .iter()
            .map(|r| r["name"].as_str().unwrap())
            .collect::<Vec<_>>(),
        vec!["B", "C"]
    );
    let result = ex
        .execute(
            "MATCH (a) WITH DISTINCT a.name AS name WHERE a.name = 'B' RETURN name",
            None,
        )
        .unwrap();
    assert_eq!(result.rows.len(), 1);
    assert_eq!(result.rows[0]["name"].as_str(), Some("B"));
}
