//! Frozen Graphite scalar contracts and opt-in openCypher M23 contracts.
use graphite_cypher::{
    ast::{BinOp, Clause, CmpOp, Expr, Literal},
    engine::{CancelToken, Executor},
    materialize::materialize,
    parser::{parse, parse_cached, parse_cached_with_dialect, parse_with_dialect},
    CypherDialect::{self, Graphite, OpenCypherM23},
    CypherError, Value,
};
use serde_json::{json, Value as Json};

fn value(dialect: CypherDialect, expression: &str) -> Json {
    let executor = Executor::new(vec![], false).with_dialect(dialect);
    let result = executor
        .execute(&format!("RETURN {expression} AS v"), None)
        .unwrap();
    assert_eq!(result.columns, ["v"]);
    assert_eq!(result.rows.len(), 1);
    materialize(&result.rows[0]["v"], &executor)
}

fn diagnostic(expression: &str) -> (&'static str, &'static str, &'static str) {
    let error = Executor::new(vec![], false)
        .with_dialect(OpenCypherM23)
        .execute(&format!("RETURN {expression} AS v"), None)
        .unwrap_err()
        .diagnostic()
        .unwrap();
    (error.error_type, error.phase, error.detail)
}

#[test]
fn powers_and_comparison_chains_keep_dialect_specific_ast_and_cache_entries() {
    let expr = |dialect, text: &str| {
        let clauses = parse_with_dialect(text, dialect).unwrap();
        let Clause::Return {
            items: Some(items), ..
        } = &clauses[0]
        else {
            panic!()
        };
        items[0].expr.clone()
    };
    let lit = |i| Expr::Literal(Literal::Int(i));
    let pow = |a, b| Expr::Binary {
        op: BinOp::Pow,
        left: Box::new(a),
        right: Box::new(b),
    };
    assert_eq!(
        expr(Graphite, "RETURN 2^3^2"),
        pow(lit(2), pow(lit(3), lit(2)))
    );
    assert_eq!(
        expr(OpenCypherM23, "RETURN 2^3^2"),
        pow(pow(lit(2), lit(3)), lit(2))
    );
    let lt = |a, b| Expr::Comparison {
        op: CmpOp::Lt,
        left: Box::new(a),
        right: Box::new(b),
    };
    assert_eq!(
        expr(Graphite, "RETURN 1<2<3"),
        lt(lt(lit(1), lit(2)), lit(3))
    );
    assert_eq!(
        expr(OpenCypherM23, "RETURN 1<2<3"),
        Expr::And(Box::new(lt(lit(1), lit(2))), Box::new(lt(lit(2), lit(3))))
    );
    for _ in 0..3 {
        assert_eq!(value(Graphite, "2^3^2"), json!(512.0));
        assert_eq!(value(OpenCypherM23, "2^3^2"), json!(64.0));
        assert_eq!(value(Graphite, "1<2<3"), json!(false));
        assert_eq!(value(OpenCypherM23, "1<2<3"), json!(true));
    }
    let standard = parse_cached_with_dialect("RETURN 2^3^2", OpenCypherM23).unwrap();
    let legacy = parse_cached_with_dialect("RETURN 2^3^2", Graphite).unwrap();
    assert_ne!(standard, legacy);
    for (query, expected) in [
        ("RETURN 2^3^2", pow(lit(2), pow(lit(3), lit(2)))),
        ("RETURN 1<2<3", lt(lt(lit(1), lit(2)), lit(3))),
    ] {
        // Existing Rust parser entry points keep Graphite's historical AST even
        // when a standard-mode cache entry for the same text already exists.
        let standard = parse_cached_with_dialect(query, OpenCypherM23).unwrap();
        let default = parse(query).unwrap();
        let cached_default = parse_cached(query).unwrap();
        for clauses in [&default, cached_default.as_ref()] {
            let Clause::Return {
                items: Some(items), ..
            } = &clauses[0]
            else {
                panic!("missing RETURN: {query}")
            };
            assert_eq!(items[0].expr, expected, "{query}");
        }
        assert_ne!(*standard, default, "{query}");
        assert!(std::sync::Arc::ptr_eq(
            &cached_default,
            &parse_cached_with_dialect(query, Graphite).unwrap()
        ));
    }
}

#[test]
fn graphite_uses_rendered_list_comparison_while_m23_uses_partial_lexical_order() {
    assert_eq!(value(Graphite, "[2] < [10]"), json!(false));
    assert_eq!(value(OpenCypherM23, "[2] < [10]"), json!(true));
    assert_eq!(value(Graphite, "[1] < 2"), json!(false));
    assert_eq!(value(OpenCypherM23, "[1] < 2"), Json::Null);
}

#[test]
fn list_addition_preserves_legacy_string_precedence_and_left_list_only_append() {
    for (expression, legacy, standard) in [
        ("3 + [1,2]", Json::Null, json!([3, 1, 2])),
        ("[1,2] + 'x'", json!("[1, 2]x"), json!([1, 2, "x"])),
        ("'x' + [1,2]", json!("x[1, 2]"), json!(["x", 1, 2])),
        ("[1,2] + null", Json::Null, json!([1, 2, null])),
        ("null + [1,2]", Json::Null, json!([null, 1, 2])),
    ] {
        assert_eq!(value(Graphite, expression), legacy, "{expression}");
        assert_eq!(value(OpenCypherM23, expression), standard, "{expression}");
    }
    for dialect in [Graphite, OpenCypherM23] {
        assert_eq!(value(dialect, "[1,2] + 3"), json!([1, 2, 3]));
    }
}

#[test]
fn decimal_string_integer_conversion_is_opt_in_and_invalid_legacy_inputs_remain_null() {
    for (expression, number) in [("toInteger('1.7')", 1), ("toInteger('1e3')", 1000)] {
        assert_eq!(value(Graphite, expression), Json::Null);
        assert_eq!(value(OpenCypherM23, expression), json!(number));
    }
    for expression in [
        "toBoolean(1)",
        "toBoolean([])",
        "toFloat(true)",
        "toFloat([])",
        "toInteger([])",
        "toInteger({x:1})",
    ] {
        assert_eq!(value(Graphite, expression), Json::Null);
        assert_eq!(
            diagnostic(expression),
            ("TypeError", "runtime", "InvalidArgumentValue"),
            "{expression}"
        );
    }
}

#[test]
fn map_properties_and_null_labels_keep_legacy_shape() {
    assert_eq!(value(Graphite, "properties({x:1})"), Json::Null);
    assert_eq!(value(OpenCypherM23, "properties({x:1})"), json!({"x":1}));
    assert_eq!(value(Graphite, "labels(null)"), json!([]));
    assert_eq!(value(OpenCypherM23, "labels(null)"), Json::Null);
}

#[test]
fn explicit_null_slice_bounds_use_legacy_defaults_only_in_graphite() {
    for (expression, expected) in [
        ("[1,2,3][null..2]", json!([1, 2])),
        ("[1,2,3][0..null]", json!([1, 2, 3])),
        ("[1,2,3][null..null]", json!([1, 2, 3])),
        ("'abc'[null..2]", json!("ab")),
    ] {
        assert_eq!(value(Graphite, expression), expected);
        assert_eq!(value(OpenCypherM23, expression), Json::Null);
    }
}

#[test]
fn float_range_arguments_keep_legacy_truncation_and_standard_integer_contract() {
    assert_eq!(value(Graphite, "range(0.5,3.5,1.5)"), json!([0, 1, 2, 3]));
    assert_eq!(
        diagnostic("range(0.5,3.5,1.5)"),
        ("ArgumentError", "runtime", "InvalidArgumentType")
    );
    assert_eq!(
        diagnostic("range(0,1,0)"),
        ("ArgumentError", "runtime", "NumberOutOfRange")
    );
    assert_eq!(
        Executor::new(vec![], false)
            .execute("RETURN range(0,1,0)", None)
            .unwrap_err(),
        CypherError::Runtime("Step cannot be zero in range()".into())
    );
}

#[test]
fn pagination_coercion_is_legacy_only() {
    for (suffix, expected) in [
        ("LIMIT 1.9", vec![1]),
        ("LIMIT '2'", vec![1, 2]),
        ("SKIP 1.9", vec![2, 3]),
        ("SKIP '1'", vec![2, 3]),
    ] {
        let query = format!("UNWIND [1,2,3] AS n RETURN n {suffix}");
        let result = Executor::new(vec![], false).execute(&query, None).unwrap();
        let numbers: Vec<_> = result
            .rows
            .iter()
            .map(|row| match row["n"] {
                Value::Int(n) => n,
                ref other => panic!("{other:?}"),
            })
            .collect();
        assert_eq!(numbers, expected);
        let error = Executor::new(vec![], false)
            .with_dialect(OpenCypherM23)
            .execute(&query, None)
            .unwrap_err()
            .diagnostic()
            .unwrap();
        assert_eq!(
            (error.error_type, error.phase, error.detail),
            ("SyntaxError", "compile time", "InvalidArgumentType")
        );
    }
}

#[test]
fn floating_division_by_zero_preserves_graphite_error_and_standard_ieee_result() {
    for (expression, standard) in [
        ("1.0/0.0", "Infinity"),
        ("-1.0/0.0", "-Infinity"),
        ("0.0/0.0", "NaN"),
    ] {
        let error = Executor::new(vec![], false)
            .execute(&format!("RETURN {expression}"), None)
            .unwrap_err();
        assert!(matches!(error,CypherError::Runtime(ref message) if message=="Division by zero"));
        assert_eq!(value(OpenCypherM23, expression), json!(standard));
    }
}

#[test]
fn checked_range_progress_and_cancellation_remain_available_in_both_modes() {
    for dialect in [Graphite, OpenCypherM23] {
        assert_eq!(
            value(dialect, "range(9223372036854775807,9223372036854775807,1)"),
            json!([i64::MAX])
        );
        let token = CancelToken::new();
        token.cancel();
        assert!(matches!(
            Executor::new(vec![], false)
                .with_dialect(dialect)
                .with_cancel(token)
                .execute("RETURN range(0,100,1)", None),
            Err(CypherError::Cancelled)
        ));
    }
}
