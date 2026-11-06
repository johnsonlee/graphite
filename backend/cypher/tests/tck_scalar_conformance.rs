use graphite_cypher::{
    ast::{BinOp, Clause, Expr, Literal},
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
