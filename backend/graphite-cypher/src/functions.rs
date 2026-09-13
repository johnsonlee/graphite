//! Built-in scalar and aggregation functions — `CypherFunctions` parity.

use crate::context::GraphContext;
use crate::error::{CypherError, CypherResult};
use crate::semantics::{
    element_id, java_parse_double, kotlin_parse_long, kotlin_to_string, to_double, utf16_len,
    utf16_substring, value_key,
};
use crate::value::Value;
use indexmap::IndexMap;
use std::collections::HashSet;
use std::time::{SystemTime, UNIX_EPOCH};

fn arg(args: &[Value], i: usize) -> CypherResult<&Value> {
    args.get(i).ok_or_else(|| {
        CypherError::Other(format!("Index {i} out of bounds for length {}", args.len()))
    })
}

fn as_string(v: &Value) -> CypherResult<&str> {
    v.as_str().ok_or_else(|| {
        CypherError::Other("class java.lang.Object cannot be cast to class java.lang.String".into())
    })
}

fn as_number(v: &Value) -> CypherResult<f64> {
    v.as_f64().ok_or_else(|| {
        CypherError::Other("class java.lang.Object cannot be cast to class java.lang.Number".into())
    })
}

/// Call a non-aggregation function. Unknown names produce `Unknown function: <name>`.
pub fn call(name: &str, args: &[Value], ctx: &dyn GraphContext) -> CypherResult<Value> {
    let lower = name.to_ascii_lowercase();
    Ok(match lower.as_str() {
        // -- identity ------------------------------------------------------
        "id" => match arg(args, 0)? {
            Value::Node(n) => Value::Int(n.id as i64),
            _ => Value::Null,
        },
        "elementid" | "qualifiedid" => match arg(args, 0)? {
            Value::Method(m) => {
                let sig = ctx.method_signature(*m);
                if ctx.is_cross_graph() {
                    Value::str(format!("{}:Method:{}", ctx.graph_id(m.source), sig))
                } else {
                    Value::str(format!("Method:{sig}"))
                }
            }
            Value::Node(n) => Value::str(element_id(*n, ctx)),
            _ => Value::Null,
        },
        "graphid" => {
            if !ctx.is_cross_graph() {
                match arg(args, 0)? {
                    Value::Node(_) | Value::Rel(_) | Value::Method(_) | Value::Path(_) => {
                        Value::Null
                    }
                    _ => Value::Null,
                }
            } else {
                match arg(args, 0)? {
                    Value::Node(n) => Value::str(ctx.graph_id(n.source)),
                    Value::Rel(r) => Value::str(ctx.graph_id(r.source)),
                    Value::Method(m) => Value::str(ctx.graph_id(m.source)),
                    Value::Path(p) => Value::str(ctx.graph_id(p.source)),
                    _ => Value::Null,
                }
            }
        }
        "coalesce" => args
            .iter()
            .find(|v| !v.is_null())
            .cloned()
            .unwrap_or(Value::Null),
        "timestamp" => Value::Int(
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|d| d.as_millis() as i64)
                .unwrap_or(0),
        ),
        "exists" => Value::Bool(!arg(args, 0)?.is_null()),

        // -- conversion ----------------------------------------------------
        "tointeger" | "toint" => match arg(args, 0)? {
            v if v.is_number() => Value::Int(to_double(v) as i64),
            Value::Str(s) => kotlin_parse_long(s).map(Value::Int).unwrap_or(Value::Null),
            Value::Bool(b) => Value::Int(if *b { 1 } else { 0 }),
            _ => Value::Null,
        },
        "tofloat" => match arg(args, 0)? {
            v if v.is_number() => Value::Float(to_double(v)),
            Value::Str(s) => java_parse_double(s)
                .map(Value::Float)
                .unwrap_or(Value::Null),
            _ => Value::Null,
        },
        "toboolean" => match arg(args, 0)? {
            Value::Bool(b) => Value::Bool(*b),
            Value::Str(s) => {
                let l = s.to_ascii_lowercase();
                if l == "true" {
                    Value::Bool(true)
                } else if l == "false" {
                    Value::Bool(false)
                } else {
                    Value::Null
                }
            }
            _ => Value::Null,
        },
        "tostring" => match arg(args, 0)? {
            Value::Null => Value::Null,
            v => Value::str(kotlin_to_string(v, ctx)),
        },

        // -- graph shape ---------------------------------------------------
        "properties" => match arg(args, 0)? {
            Value::Node(n) => Value::map(ctx.node_properties(*n)),
            Value::Method(m) => Value::map(ctx.method_properties(*m)),
            _ => Value::Null,
        },
        "keys" => match arg(args, 0)? {
            Value::Node(n) => Value::list(
                ctx.node_properties(*n)
                    .keys()
                    .map(|k| Value::str(k.as_str()))
                    .collect(),
            ),
            Value::Method(m) => Value::list(
                ctx.method_properties(*m)
                    .keys()
                    .map(|k| Value::str(k.as_str()))
                    .collect(),
            ),
            Value::Map(m) => Value::list(m.keys().map(|k| Value::str(k.as_str())).collect()),
            _ => Value::Null,
        },
        "labels" => match arg(args, 0)? {
            Value::Node(n) => {
                Value::list(ctx.node_labels(*n).into_iter().map(Value::str).collect())
            }
            Value::Method(_) => Value::list(vec![Value::str("Method")]),
            _ => Value::list(vec![]),
        },
        "type" => match arg(args, 0)? {
            Value::Rel(r) => Value::str(ctx.rel_type(*r)),
            _ => Value::Null,
        },
        "nodes" => match arg(args, 0)? {
            Value::Path(p) => Value::list(
                p.nodes
                    .iter()
                    .map(|id| {
                        Value::Node(crate::value::NodeRef {
                            source: p.source,
                            id: *id,
                        })
                    })
                    .collect(),
            ),
            Value::List(l) => Value::list(
                l.iter()
                    .filter(|v| matches!(v, Value::Node(_)))
                    .cloned()
                    .collect(),
            ),
            _ => Value::Null,
        },
        "relationships" => match arg(args, 0)? {
            Value::Path(p) => Value::list(
                p.edges
                    .iter()
                    .map(|e| {
                        Value::Rel(crate::value::EdgeRef {
                            source: p.source,
                            edge: *e,
                        })
                    })
                    .collect(),
            ),
            Value::List(l) => Value::list(
                l.iter()
                    .filter(|v| matches!(v, Value::Rel(_)))
                    .cloned()
                    .collect(),
            ),
            _ => Value::Null,
        },

        // -- strings -------------------------------------------------------
        "tolower" | "tolowercase" => match arg(args, 0)?.as_str() {
            Some(s) => Value::str(s.to_lowercase()),
            None => Value::Null,
        },
        "toupper" | "touppercase" => match arg(args, 0)?.as_str() {
            Some(s) => Value::str(s.to_uppercase()),
            None => Value::Null,
        },
        "trim" => match arg(args, 0)?.as_str() {
            Some(s) => Value::str(s.trim_matches(crate::semantics::kotlin_is_whitespace)),
            None => Value::Null,
        },
        "ltrim" => match arg(args, 0)?.as_str() {
            Some(s) => Value::str(s.trim_start_matches(crate::semantics::kotlin_is_whitespace)),
            None => Value::Null,
        },
        "rtrim" => match arg(args, 0)?.as_str() {
            Some(s) => Value::str(s.trim_end_matches(crate::semantics::kotlin_is_whitespace)),
            None => Value::Null,
        },
        "replace" => {
            let s = match arg(args, 0)?.as_str() {
                Some(s) => s,
                None => return Ok(Value::Null),
            };
            let from = as_string(arg(args, 1)?)?;
            let to = as_string(arg(args, 2)?)?;
            Value::str(s.replace(from, to))
        }
        "substring" => {
            let s = match arg(args, 0)?.as_str() {
                Some(s) => s,
                None => return Ok(Value::Null),
            };
            let len = utf16_len(s);
            let start = as_number(arg(args, 1)?)? as i64;
            if start < 0 || start as usize > len {
                return Err(CypherError::Other(format!(
                    "begin {start}, end {len}, length {len}"
                )));
            }
            let end = if args.len() > 2 {
                let n = as_number(arg(args, 2)?)? as i64;
                ((start + n) as usize).min(len)
            } else {
                len
            };
            Value::str(utf16_substring(s, start as usize, end))
        }
        "split" => {
            let s = match arg(args, 0)?.as_str() {
                Some(s) => s,
                None => return Ok(Value::Null),
            };
            let sep = as_string(arg(args, 1)?)?;
            if sep.is_empty() {
                return Ok(Value::list(vec![Value::str(s)]));
            }
            Value::list(s.split(sep).map(Value::str).collect())
        }
        "size" | "length" => match arg(args, 0)? {
            Value::Str(s) => Value::Int(utf16_len(s) as i64),
            Value::List(l) => Value::Int(l.len() as i64),
            Value::Path(p) => Value::Int(p.edges.len() as i64),
            Value::Map(m) => Value::Int(m.len() as i64),
            _ => Value::Null,
        },
        "left" => match arg(args, 0)?.as_str() {
            Some(s) => {
                let n = (as_number(arg(args, 1)?)? as i64).max(0) as usize;
                Value::str(utf16_substring(s, 0, n.min(utf16_len(s))))
            }
            None => Value::Null,
        },
        "right" => match arg(args, 0)?.as_str() {
            Some(s) => {
                let len = utf16_len(s);
                let n = (as_number(arg(args, 1)?)? as i64).max(0) as usize;
                Value::str(utf16_substring(s, len.saturating_sub(n), len))
            }
            None => Value::Null,
        },
        "reverse" => match arg(args, 0)? {
            // Surrogate-pair aware: reverse by Unicode scalar, like Java's StringBuilder.reverse().
            Value::Str(s) => Value::str(s.chars().rev().collect::<String>()),
            Value::List(l) => {
                let mut v = l.as_ref().clone();
                v.reverse();
                Value::list(v)
            }
            _ => Value::Null,
        },

        // -- lists ---------------------------------------------------------
        "head" => match arg(args, 0)?.as_list() {
            Some(l) => l.first().cloned().unwrap_or(Value::Null),
            None => Value::Null,
        },
        "tail" => match arg(args, 0)?.as_list() {
            Some(l) => Value::list(l.iter().skip(1).cloned().collect()),
            None => Value::Null,
        },
        "last" => match arg(args, 0)?.as_list() {
            Some(l) => l.last().cloned().unwrap_or(Value::Null),
            None => Value::Null,
        },
        "range" => {
            let start = as_number(arg(args, 0)?)? as i64;
            let end = as_number(arg(args, 1)?)? as i64;
            let step = if args.len() > 2 {
                as_number(arg(args, 2)?)? as i64
            } else {
                1
            };
            if step == 0 {
                return Err(CypherError::Runtime(
                    "Step cannot be zero in range()".into(),
                ));
            }
            let mut out = Vec::new();
            let mut i = start;
            if step > 0 {
                while i <= end {
                    out.push(Value::Int(i));
                    i += step;
                }
            } else {
                while i >= end {
                    out.push(Value::Int(i));
                    i += step;
                }
            }
            Value::list(out)
        }

        // -- math ----------------------------------------------------------
        "abs" => match arg(args, 0)? {
            Value::Int(i) => Value::Int(i.abs()),
            Value::Float(f) => Value::Float(f.abs()),
            Value::Float32(f) => Value::Float32(f.abs()),
            _ => Value::Null,
        },
        "ceil" => Value::Float(to_double(arg(args, 0)?).ceil()),
        "floor" => Value::Float(to_double(arg(args, 0)?).floor()),
        // Kotlin uses floor(x + 0.5) — half-up, not banker's rounding.
        "round" => Value::Float((to_double(arg(args, 0)?) + 0.5).floor()),
        "sqrt" => Value::Float(to_double(arg(args, 0)?).sqrt()),
        "exp" => Value::Float(to_double(arg(args, 0)?).exp()),
        "log" => Value::Float(to_double(arg(args, 0)?).ln()),
        "log10" => Value::Float(to_double(arg(args, 0)?).log10()),
        "sin" => Value::Float(to_double(arg(args, 0)?).sin()),
        "cos" => Value::Float(to_double(arg(args, 0)?).cos()),
        "tan" => Value::Float(to_double(arg(args, 0)?).tan()),
        "cot" => Value::Float(1.0 / to_double(arg(args, 0)?).tan()),
        "asin" => Value::Float(to_double(arg(args, 0)?).asin()),
        "acos" => Value::Float(to_double(arg(args, 0)?).acos()),
        "atan" => Value::Float(to_double(arg(args, 0)?).atan()),
        "atan2" => Value::Float(to_double(arg(args, 0)?).atan2(to_double(arg(args, 1)?))),
        "degrees" => Value::Float(to_double(arg(args, 0)?).to_degrees()),
        "radians" => Value::Float(to_double(arg(args, 0)?).to_radians()),
        "sign" => {
            let d = to_double(arg(args, 0)?);
            Value::Int(if d > 0.0 {
                1
            } else if d < 0.0 {
                -1
            } else {
                0
            })
        }
        "rand" => Value::Float(pseudo_random()),
        "pi" => Value::Float(std::f64::consts::PI),
        "e" => Value::Float(std::f64::consts::E),

        _ => return Err(CypherError::Runtime(format!("Unknown function: {name}"))),
    })
}

/// xorshift-based `Math.random()` substitute (no rand dependency).
fn pseudo_random() -> f64 {
    use std::cell::Cell;
    thread_local! {
        static STATE: Cell<u64> = const { Cell::new(0) };
    }
    STATE.with(|s| {
        let mut x = s.get();
        if x == 0 {
            x = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|d| d.as_nanos() as u64)
                .unwrap_or(0x2545F4914F6CDD1D)
                | 1;
        }
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        s.set(x);
        (x >> 11) as f64 / (1u64 << 53) as f64
    })
}

/// Aggregate a column of per-row values. `values` for `count(*)` are the whole rows.
pub fn aggregate(name: &str, values: &[Value]) -> CypherResult<Value> {
    let lower = name.to_ascii_lowercase();
    let non_null: Vec<&Value> = values.iter().filter(|v| !v.is_null()).collect();
    Ok(match lower.as_str() {
        "count" => Value::Int(non_null.len() as i64),
        "sum" => Value::Float(non_null.iter().map(|v| to_double(v)).sum::<f64>()),
        "avg" => {
            if non_null.is_empty() {
                Value::Null
            } else {
                Value::Float(
                    non_null.iter().map(|v| to_double(v)).sum::<f64>() / non_null.len() as f64,
                )
            }
        }
        // min/max compare by toDouble() and return the ORIGINAL value; NaN sorts greatest.
        "min" => pick_extreme(&non_null, true),
        "max" => pick_extreme(&non_null, false),
        "collect" => Value::list(non_null.into_iter().cloned().collect()),
        "percentilecont" => percentile_cont(&non_null, 0.5),
        "percentiledisc" => percentile_disc(&non_null, 0.5),
        "stdev" => std_dev(&non_null, true),
        "stdevp" => std_dev(&non_null, false),
        _ => return Err(CypherError::Runtime(format!("Unknown aggregation: {name}"))),
    })
}

/// `count(*)` counts every row, nulls included.
pub fn count_star(rows: usize) -> Value {
    Value::Int(rows as i64)
}

fn pick_extreme(values: &[&Value], min: bool) -> Value {
    let mut best: Option<(&Value, f64)> = None;
    for v in values {
        let d = to_double(v);
        best = Some(match best {
            None => (v, d),
            Some((bv, bd)) => {
                // NaN is greatest, matching the Kotlin comparator.
                let replace = if min {
                    nan_aware_lt(d, bd)
                } else {
                    nan_aware_lt(bd, d)
                };
                if replace {
                    (v, d)
                } else {
                    (bv, bd)
                }
            }
        });
    }
    best.map(|(v, _)| (*v).clone()).unwrap_or(Value::Null)
}

fn nan_aware_lt(a: f64, b: f64) -> bool {
    match (a.is_nan(), b.is_nan()) {
        (true, true) => false,
        (true, false) => false,
        (false, true) => true,
        (false, false) => a < b,
    }
}

fn sorted_doubles(values: &[&Value]) -> Vec<f64> {
    let mut nums: Vec<f64> = values.iter().map(|v| to_double(v)).collect();
    nums.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    nums
}

fn percentile_cont(values: &[&Value], p: f64) -> Value {
    let nums = sorted_doubles(values);
    if nums.is_empty() {
        return Value::Null;
    }
    if nums.len() == 1 {
        return Value::Float(nums[0]);
    }
    let pos = p * (nums.len() - 1) as f64;
    let lo = pos.floor() as usize;
    let hi = pos.ceil() as usize;
    if lo == hi {
        return Value::Float(nums[lo]);
    }
    Value::Float(nums[lo] + (pos - lo as f64) * (nums[hi] - nums[lo]))
}

fn percentile_disc(values: &[&Value], p: f64) -> Value {
    let nums = sorted_doubles(values);
    if nums.is_empty() {
        return Value::Null;
    }
    let idx = ((p * nums.len() as f64).ceil() as usize).saturating_sub(1);
    Value::Float(nums[idx.min(nums.len() - 1)])
}

fn std_dev(values: &[&Value], sample: bool) -> Value {
    if values.len() < 2 {
        return Value::Null;
    }
    let nums: Vec<f64> = values.iter().map(|v| to_double(v)).collect();
    let mean = nums.iter().sum::<f64>() / nums.len() as f64;
    let sum_sq: f64 = nums.iter().map(|x| (x - mean) * (x - mean)).sum();
    let divisor = if sample {
        (nums.len() - 1) as f64
    } else {
        nums.len() as f64
    };
    Value::Float((sum_sq / divisor).sqrt())
}

/// Deduplicate by canonical value key, preserving first-seen order (`DISTINCT` in aggregations).
pub fn distinct_values(values: &[Value]) -> Vec<Value> {
    let mut seen = HashSet::new();
    values
        .iter()
        .filter(|v| seen.insert(value_key(v)))
        .cloned()
        .collect()
}

#[allow(dead_code)]
fn _map_marker() -> IndexMap<String, Value> {
    IndexMap::new()
}
