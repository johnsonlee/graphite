//! `ORDER BY` comparator — `compareOrderValues` / `OrderValueType` parity.
//!
//! `null` sorts greatest, `NaN` sorts greatest, and cross-type ordering follows a
//! fixed type rank. `DESC` is applied by the caller reversing the result.

use crate::semantics::{compare_cypher_numbers, compare_utf16, java_double_compare};
use crate::value::Value;
use std::cmp::Ordering;

/// Cross-type ordering rank. Anything unrecognised is treated as `STRING`.
fn type_rank(v: &Value) -> u8 {
    match v {
        Value::Map(_) => 0,
        Value::Node(_) | Value::Method(_) => 1,
        Value::Rel(_) => 2,
        Value::List(_) => 3,
        Value::Path(_) => 4,
        Value::Str(_) => 5,
        Value::Bool(_) => 6,
        Value::Int(_) | Value::Float(_) | Value::Float32(_) => 7,
        Value::Null => u8::MAX,
    }
}

pub fn compare_order_values(a: &Value, b: &Value) -> Ordering {
    match (a.is_null(), b.is_null()) {
        (true, true) => return Ordering::Equal,
        (true, false) => return Ordering::Greater,
        (false, true) => return Ordering::Less,
        (false, false) => {}
    }
    match (a, b) {
        (Value::Int(x), Value::Int(y)) => return x.cmp(y),
        (Value::Str(x), Value::Str(y)) => return compare_utf16(x, y),
        (Value::Bool(x), Value::Bool(y)) => return x.cmp(y),
        (Value::Float(x), Value::Float(y)) => return java_double_compare(*x, *y),
        (Value::Float32(x), Value::Float32(y)) => {
            return java_double_compare(*x as f64, *y as f64)
        }
        _ => {}
    }
    if a.is_number() && b.is_number() {
        return compare_cypher_numbers(a, b);
    }
    let (ra, rb) = (type_rank(a), type_rank(b));
    if ra != rb {
        return ra.cmp(&rb);
    }
    match (a, b) {
        (Value::Map(x), Value::Map(y)) => {
            let c = x.len().cmp(&y.len());
            if c != Ordering::Equal {
                return c;
            }
            let mut xk: Vec<&String> = x.keys().collect();
            let mut yk: Vec<&String> = y.keys().collect();
            xk.sort();
            yk.sort();
            for (kx, ky) in xk.iter().zip(yk.iter()) {
                let c = compare_utf16(kx, ky);
                if c != Ordering::Equal {
                    return c;
                }
            }
            for k in xk {
                let c = compare_order_values(&x[k], &y[k]);
                if c != Ordering::Equal {
                    return c;
                }
            }
            Ordering::Equal
        }
        (Value::List(x), Value::List(y)) => {
            for (ex, ey) in x.iter().zip(y.iter()) {
                let c = compare_order_values(ex, ey);
                if c != Ordering::Equal {
                    return c;
                }
            }
            x.len().cmp(&y.len())
        }
        (Value::Node(x), Value::Node(y)) => x.source.cmp(&y.source).then(x.id.cmp(&y.id)),
        (Value::Method(x), Value::Method(y)) => x.source.cmp(&y.source).then(x.index.cmp(&y.index)),
        (Value::Rel(x), Value::Rel(y)) => x
            .source
            .cmp(&y.source)
            .then(x.edge.from.cmp(&y.edge.from))
            .then(x.edge.to.cmp(&y.edge.to))
            .then(x.edge.rest_type().cmp(y.edge.rest_type())),
        (Value::Path(x), Value::Path(y)) => {
            for (nx, ny) in x.nodes.iter().zip(y.nodes.iter()) {
                let c = nx.cmp(ny);
                if c != Ordering::Equal {
                    return c;
                }
            }
            x.nodes.len().cmp(&y.nodes.len())
        }
        _ => Ordering::Equal,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn null_sorts_greatest() {
        assert_eq!(compare_order_values(&Value::Null, &Value::Int(1)), Ordering::Greater);
        assert_eq!(compare_order_values(&Value::Int(1), &Value::Null), Ordering::Less);
        assert_eq!(compare_order_values(&Value::Null, &Value::Null), Ordering::Equal);
    }

    #[test]
    fn nan_sorts_greatest() {
        assert_eq!(
            compare_order_values(&Value::Float(f64::NAN), &Value::Float(1.0)),
            Ordering::Greater
        );
    }

    #[test]
    fn cross_type_rank_order() {
        // MAP < NODE < RELATIONSHIP < LIST < PATH < STRING < BOOLEAN < NUMBER
        assert_eq!(
            compare_order_values(&Value::str("a"), &Value::Bool(true)),
            Ordering::Less
        );
        assert_eq!(
            compare_order_values(&Value::Bool(true), &Value::Int(1)),
            Ordering::Less
        );
        assert_eq!(
            compare_order_values(&Value::list(vec![]), &Value::str("a")),
            Ordering::Less
        );
    }

    #[test]
    fn numbers_compare_across_types() {
        assert_eq!(compare_order_values(&Value::Int(1), &Value::Float(1.0)), Ordering::Equal);
        assert_eq!(compare_order_values(&Value::Int(1), &Value::Float(1.5)), Ordering::Less);
    }

    #[test]
    fn strings_compare_by_utf16() {
        assert_eq!(compare_order_values(&Value::str("a"), &Value::str("b")), Ordering::Less);
        assert_eq!(compare_order_values(&Value::str("B"), &Value::str("a")), Ordering::Less);
    }

    #[test]
    fn lists_compare_elementwise_then_by_size() {
        let a = Value::list(vec![Value::Int(1), Value::Int(2)]);
        let b = Value::list(vec![Value::Int(1), Value::Int(3)]);
        let c = Value::list(vec![Value::Int(1)]);
        assert_eq!(compare_order_values(&a, &b), Ordering::Less);
        assert_eq!(compare_order_values(&a, &c), Ordering::Greater);
    }
}
