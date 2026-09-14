//! `CypherExpr.toCypherString()` — the source of RETURN column names.
//!
//! This is a *re-rendering*, not the original source text: `(1+2)*3` and `1+2*3`
//! both render as `1 + 2 * 3`, so they collide as column names exactly as in Kotlin.

use crate::ast::*;
use crate::semantics::{java_double_to_string, java_float_to_string};

pub fn to_cypher_string(expr: &Expr) -> String {
    let mut s = String::new();
    write(&mut s, expr);
    s
}

fn write(out: &mut String, e: &Expr) {
    match e {
        Expr::Literal(l) => write_literal(out, l),
        Expr::Variable(v) => out.push_str(v),
        Expr::Parameter(p) => {
            out.push('$');
            out.push_str(p);
        }
        Expr::Property { expr, key } => {
            write(out, expr);
            out.push('.');
            out.push_str(key);
        }
        Expr::FunctionCall {
            name,
            distinct,
            args,
        } => {
            out.push_str(name);
            out.push('(');
            if *distinct {
                out.push_str("DISTINCT ");
            }
            for (i, a) in args.iter().enumerate() {
                if i > 0 {
                    out.push_str(", ");
                }
                write(out, a);
            }
            out.push(')');
        }
        Expr::CountStar => out.push_str("count(*)"),
        Expr::Distinct(inner) => {
            out.push_str("DISTINCT ");
            write(out, inner);
        }
        Expr::Binary { op, left, right } => infix(out, left, bin_op_str(*op), right),
        Expr::Unary { negate, expr } => {
            if *negate {
                out.push('-');
            }
            write(out, expr);
        }
        Expr::Comparison { op, left, right } => infix(out, left, cmp_op_str(*op), right),
        Expr::StringOp { op, left, right } => infix(out, left, str_op_str(*op), right),
        Expr::In { left, right } => infix(out, left, "IN", right),
        Expr::IsNull(e) => {
            write(out, e);
            out.push_str(" IS NULL");
        }
        Expr::IsNotNull(e) => {
            write(out, e);
            out.push_str(" IS NOT NULL");
        }
        Expr::Not(e) => {
            out.push_str("NOT ");
            write(out, e);
        }
        Expr::And(a, b) => infix(out, a, "AND", b),
        Expr::Or(a, b) => infix(out, a, "OR", b),
        Expr::Xor(a, b) => infix(out, a, "XOR", b),
        Expr::Case {
            test,
            whens,
            else_expr,
        } => {
            out.push_str("CASE");
            if let Some(t) = test {
                out.push(' ');
                write(out, t);
            }
            for (c, r) in whens {
                out.push_str(" WHEN ");
                write(out, c);
                out.push_str(" THEN ");
                write(out, r);
            }
            if let Some(e) = else_expr {
                out.push_str(" ELSE ");
                write(out, e);
            }
            out.push_str(" END");
        }
        Expr::ListLiteral(items) => {
            out.push('[');
            for (i, it) in items.iter().enumerate() {
                if i > 0 {
                    out.push_str(", ");
                }
                write(out, it);
            }
            out.push(']');
        }
        Expr::MapLiteral(entries) => {
            out.push('{');
            for (i, (k, v)) in entries.iter().enumerate() {
                if i > 0 {
                    out.push_str(", ");
                }
                out.push_str(k);
                out.push_str(": ");
                write(out, v);
            }
            out.push('}');
        }
        Expr::ListComprehension {
            variable,
            list,
            filter,
            map,
        } => {
            out.push('[');
            out.push_str(variable);
            out.push_str(" IN ");
            write(out, list);
            if let Some(f) = filter {
                out.push_str(" WHERE ");
                write(out, f);
            }
            if let Some(m) = map {
                out.push_str(" | ");
                write(out, m);
            }
            out.push(']');
        }
        Expr::PredicateFunction {
            name,
            variable,
            list,
            predicate,
        } => {
            out.push_str(&name.to_ascii_lowercase());
            out.push('(');
            out.push_str(variable);
            out.push_str(" IN ");
            write(out, list);
            if let Some(p) = predicate {
                out.push_str(" WHERE ");
                write(out, p);
            }
            out.push(')');
        }
        Expr::Subscript { expr, index } => {
            write(out, expr);
            out.push('[');
            write(out, index);
            out.push(']');
        }
        Expr::Slice { expr, from, to } => {
            write(out, expr);
            out.push('[');
            if let Some(f) = from {
                write(out, f);
            }
            out.push_str("..");
            if let Some(t) = to {
                write(out, t);
            }
            out.push(']');
        }
    }
}

fn infix(out: &mut String, left: &Expr, op: &str, right: &Expr) {
    write(out, left);
    out.push(' ');
    out.push_str(op);
    out.push(' ');
    write(out, right);
}

fn write_literal(out: &mut String, l: &Literal) {
    match l {
        Literal::Null => out.push_str("null"),
        Literal::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
        Literal::Int(i) => out.push_str(&i.to_string()),
        Literal::Float(f) => out.push_str(&java_double_to_string(*f)),
        Literal::Str(s) => {
            out.push('\'');
            out.push_str(&s.replace('\'', "\\'"));
            out.push('\'');
        }
    }
}

fn bin_op_str(op: BinOp) -> &'static str {
    match op {
        BinOp::Add => "+",
        BinOp::Sub => "-",
        BinOp::Mul => "*",
        BinOp::Div => "/",
        BinOp::Mod => "%",
        BinOp::Pow => "^",
    }
}

fn cmp_op_str(op: CmpOp) -> &'static str {
    match op {
        CmpOp::Eq => "=",
        CmpOp::Ne => "<>",
        CmpOp::Lt => "<",
        CmpOp::Gt => ">",
        CmpOp::Le => "<=",
        CmpOp::Ge => ">=",
    }
}

fn str_op_str(op: StrOp) -> &'static str {
    match op {
        StrOp::StartsWith => "STARTS WITH",
        StrOp::EndsWith => "ENDS WITH",
        StrOp::Contains => "CONTAINS",
        StrOp::Regex => "=~",
    }
}

#[allow(dead_code)]
fn _f32_marker(v: f32) -> String {
    java_float_to_string(v)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::parser::parse;

    fn col(query: &str) -> String {
        let clauses = parse(query).expect("parse");
        for c in &clauses {
            if let Clause::Return {
                items: Some(items), ..
            } = c
            {
                return to_cypher_string(&items[0].expr);
            }
        }
        panic!("no RETURN");
    }

    #[test]
    fn property_and_variable_columns() {
        assert_eq!(col("MATCH (n) RETURN n"), "n");
        assert_eq!(col("MATCH (n) RETURN n.value"), "n.value");
    }

    #[test]
    fn function_columns() {
        assert_eq!(col("RETURN count(*)"), "count(*)");
        assert_eq!(col("MATCH (n) RETURN count(n)"), "count(n)");
        assert_eq!(
            col("MATCH (n) RETURN count(DISTINCT n)"),
            "count(DISTINCT n)"
        );
        assert_eq!(col("RETURN toLower('A')"), "toLower('A')");
    }

    #[test]
    fn arithmetic_columns_lose_parentheses() {
        assert_eq!(col("RETURN 1 + 2 * 3"), "1 + 2 * 3");
        assert_eq!(col("RETURN (1 + 2) * 3"), "1 + 2 * 3");
    }

    #[test]
    fn string_predicates_and_null_checks() {
        assert_eq!(
            col("MATCH (n) RETURN n.name STARTS WITH 'a'"),
            "n.name STARTS WITH 'a'"
        );
        assert_eq!(col("MATCH (n) RETURN n.x IS NULL"), "n.x IS NULL");
        assert_eq!(col("MATCH (n) RETURN n.x IS NOT NULL"), "n.x IS NOT NULL");
        assert_eq!(col("MATCH (n) RETURN NOT n.x"), "NOT n.x");
    }

    #[test]
    fn literals_render_like_kotlin() {
        assert_eq!(col("RETURN null"), "null");
        assert_eq!(col("RETURN true"), "true");
        assert_eq!(col("RETURN 1.0"), "1.0");
        assert_eq!(col("RETURN 'it''s'"), "'it\\'s'");
    }

    #[test]
    fn collections_and_case() {
        assert_eq!(col("RETURN [1, 2]"), "[1, 2]");
        assert_eq!(col("RETURN {a: 1}"), "{a: 1}");
        assert_eq!(
            col("RETURN CASE WHEN true THEN 1 ELSE 2 END"),
            "CASE WHEN true THEN 1 ELSE 2 END"
        );
        assert_eq!(
            col("RETURN [x IN [1] WHERE x > 0 | x]"),
            "[x IN [1] WHERE x > 0 | x]"
        );
        assert_eq!(
            col("RETURN any(x IN [1] WHERE x > 0)"),
            "any(x IN [1] WHERE x > 0)"
        );
    }

    #[test]
    fn subscript_and_slice() {
        assert_eq!(col("RETURN [1,2][0]"), "[1, 2][0]");
        assert_eq!(col("RETURN [1,2][0..1]"), "[1, 2][0..1]");
        assert_eq!(col("RETURN [1,2][..1]"), "[1, 2][..1]");
        assert_eq!(col("RETURN [1,2][0..]"), "[1, 2][0..]");
    }
}
