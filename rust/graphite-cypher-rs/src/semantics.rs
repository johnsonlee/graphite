//! Value semantics ported from `CypherValueSemantics.kt` plus the Kotlin/JVM
//! coercion helpers (`toDouble`, `toString`, `toLongOrNull`, ...) that the
//! evaluator, functions, ordering and materialization share.

use crate::context::GraphContext;
use crate::value::{EdgeRef, MethodRef, NodeRef, PathValue, Value};
use graphite_storage::{Edge, NodeId};
use indexmap::IndexMap;
use std::cmp::Ordering;
use std::sync::Arc;

/// One result / binding row: variable name -> value, insertion ordered.
pub type Row = IndexMap<String, Value>;

// ============================================================================
// Number helpers
// ============================================================================

/// `CypherFunctions.toDouble` / `ExpressionEvaluator.toDouble`:
/// `Number -> toDouble()`, `String -> toDoubleOrNull() ?: 0.0`, else `0.0`.
pub fn to_double(value: &Value) -> f64 {
    match value {
        Value::Int(i) => *i as f64,
        Value::Float(f) => *f,
        Value::Float32(f) => *f as f64,
        Value::Str(s) => java_parse_double(s).unwrap_or(0.0),
        _ => 0.0,
    }
}

/// Kotlin `Double.toLong()`: truncation, saturating, NaN -> 0.
#[inline]
pub fn f64_to_i64(f: f64) -> i64 {
    f as i64
}

/// Kotlin `Double.toInt()`: truncation, saturating to the `Int` range, NaN -> 0.
#[inline]
pub fn f64_to_i32(f: f64) -> i32 {
    f as i32
}

/// Kotlin `Number.toInt()` for a Cypher value; `None` when the value is not a number.
pub fn number_to_i32(value: &Value) -> Option<i32> {
    match value {
        Value::Int(i) => Some(*i as i32),
        Value::Float(f) => Some(f64_to_i32(*f)),
        Value::Float32(f) => Some(f64_to_i32(*f as f64)),
        _ => None,
    }
}

/// Kotlin `Number.toLong()` for a Cypher value; `None` when the value is not a number.
pub fn number_to_i64(value: &Value) -> Option<i64> {
    match value {
        Value::Int(i) => Some(*i),
        Value::Float(f) => Some(f64_to_i64(*f)),
        Value::Float32(f) => Some(f64_to_i64(*f as f64)),
        _ => None,
    }
}

/// `java.lang.Double.parseDouble` subset used by Kotlin `String.toDoubleOrNull()`.
///
/// Accepts surrounding whitespace, an optional sign, decimal/exponent forms,
/// an optional trailing `d`/`D`/`f`/`F` suffix and the exact spellings
/// `NaN`, `Infinity`. Hexadecimal floating literals are not supported.
pub fn java_parse_double(text: &str) -> Option<f64> {
    let t = text.trim_matches(|c: char| c <= ' ');
    if t.is_empty() {
        return None;
    }
    let (neg, body) = match t.as_bytes()[0] {
        b'-' => (true, &t[1..]),
        b'+' => (false, &t[1..]),
        _ => (false, t),
    };
    let sign = if neg { -1.0 } else { 1.0 };
    match body {
        "NaN" => return Some(f64::NAN),
        "Infinity" => return Some(sign * f64::INFINITY),
        _ => {}
    }
    let mut digits = body;
    if let Some(stripped) = digits.strip_suffix(['d', 'D', 'f', 'F']) {
        digits = stripped;
    }
    if digits.is_empty() {
        return None;
    }
    // Java grammar: digits [. digits] [e [sign] digits] | . digits [exp]
    let mut seen_digit = false;
    let mut seen_dot = false;
    let mut seen_exp = false;
    let mut exp_digit = false;
    let mut prev_exp = false;
    for c in digits.chars() {
        match c {
            '0'..='9' => {
                if seen_exp {
                    exp_digit = true;
                } else {
                    seen_digit = true;
                }
                prev_exp = false;
            }
            '.' if !seen_dot && !seen_exp => {
                seen_dot = true;
                prev_exp = false;
            }
            'e' | 'E' if !seen_exp && seen_digit => {
                seen_exp = true;
                prev_exp = true;
            }
            '+' | '-' if prev_exp => {
                prev_exp = false;
            }
            _ => return None,
        }
    }
    if !seen_digit || (seen_exp && !exp_digit) {
        return None;
    }
    digits.parse::<f64>().ok().map(|v| sign * v)
}

/// Kotlin `String.toLongOrNull()` (radix 10, optional sign, no whitespace).
pub fn kotlin_parse_long(text: &str) -> Option<i64> {
    let bytes = text.as_bytes();
    if bytes.is_empty() {
        return None;
    }
    let body = match bytes[0] {
        b'+' | b'-' => {
            if bytes.len() == 1 {
                return None;
            }
            &text[1..]
        }
        _ => text,
    };
    if !body.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    text.parse::<i64>().ok()
}

// ============================================================================
// Exact decimal representation (BigDecimal parity without a dependency)
// ============================================================================

/// A finite decimal number in canonical form: `(-1)^neg * 0.d1d2..dn * 10^exp`
/// with no leading or trailing zero digits (zero is the empty digit string).
///
/// Mirrors `BigDecimal(number.toString()).stripTrailingZeros()`: for doubles and
/// floats the digits come from the shortest round-trip representation (which is
/// what `Double.toString()` / `Float.toString()` produce on modern JDKs).
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Decimal {
    pub negative: bool,
    pub digits: Vec<u8>,
    /// Decimal exponent such that value = 0.digits * 10^exp.
    pub exp: i32,
}

impl Decimal {
    pub fn zero() -> Decimal {
        Decimal { negative: false, digits: Vec::new(), exp: 0 }
    }

    pub fn is_zero(&self) -> bool {
        self.digits.is_empty()
    }

    pub fn from_i64(v: i64) -> Decimal {
        if v == 0 {
            return Decimal::zero();
        }
        let negative = v < 0;
        let s = v.unsigned_abs().to_string();
        Decimal::from_parts(negative, s.as_bytes().iter().map(|b| b - b'0').collect(), s.len() as i32)
    }

    /// Builds from the output of `format!("{:e}", x)` (shortest round-trip digits).
    fn from_sci(negative: bool, sci: &str) -> Decimal {
        // sci looks like "1.2345e-7" or "5e3"
        let (mantissa, exp) = sci.split_once('e').unwrap_or((sci, "0"));
        let exp: i32 = exp.parse().unwrap_or(0);
        let mut digits: Vec<u8> = Vec::new();
        for b in mantissa.bytes() {
            if b.is_ascii_digit() {
                digits.push(b - b'0');
            }
        }
        // mantissa is d.ddd => value = d.ddd * 10^exp = 0.dddd * 10^(exp+1)
        Decimal::from_parts(negative, digits, exp + 1)
    }

    fn from_parts(negative: bool, mut digits: Vec<u8>, mut exp: i32) -> Decimal {
        // strip leading zeros
        let lead = digits.iter().take_while(|d| **d == 0).count();
        if lead > 0 {
            digits.drain(..lead);
            exp -= lead as i32;
        }
        while digits.last() == Some(&0) {
            digits.pop();
        }
        if digits.is_empty() {
            return Decimal::zero();
        }
        Decimal { negative, digits, exp }
    }

    /// `BigDecimal(double.toString())` — finite values only.
    pub fn from_f64(v: f64) -> Option<Decimal> {
        if !v.is_finite() {
            return None;
        }
        if v == 0.0 {
            return Some(Decimal::zero());
        }
        let s = format!("{:e}", v.abs());
        Some(Decimal::from_sci(v < 0.0, &s))
    }

    /// `BigDecimal(float.toString())` — finite values only.
    pub fn from_f32(v: f32) -> Option<Decimal> {
        if !v.is_finite() {
            return None;
        }
        if v == 0.0 {
            return Some(Decimal::zero());
        }
        let s = format!("{:e}", v.abs());
        Some(Decimal::from_sci(v < 0.0, &s))
    }

    /// Exact `BigDecimal.compareTo`.
    pub fn cmp_exact(&self, other: &Decimal) -> Ordering {
        let sa = if self.is_zero() { 0 } else if self.negative { -1 } else { 1 };
        let sb = if other.is_zero() { 0 } else if other.negative { -1 } else { 1 };
        if sa != sb {
            return sa.cmp(&sb);
        }
        if sa == 0 {
            return Ordering::Equal;
        }
        let mag = match self.exp.cmp(&other.exp) {
            Ordering::Equal => self.digits.cmp(&other.digits),
            o => o,
        };
        if sa < 0 {
            mag.reverse()
        } else {
            mag
        }
    }

    /// `BigDecimal.longValueExact()` — `None` when fractional or out of range.
    pub fn to_i64_exact(&self) -> Option<i64> {
        if self.is_zero() {
            return Some(0);
        }
        if self.exp < self.digits.len() as i32 {
            return None; // fractional
        }
        if self.exp > 19 {
            return None;
        }
        let mut acc: i128 = 0;
        for d in &self.digits {
            acc = acc * 10 + *d as i128;
        }
        for _ in 0..(self.exp - self.digits.len() as i32) {
            acc *= 10;
        }
        if self.negative {
            acc = -acc;
        }
        i64::try_from(acc).ok()
    }
}

/// `Number.toCypherBigDecimal()` for finite values.
fn to_decimal(value: &Value) -> Option<Decimal> {
    match value {
        Value::Int(i) => Some(Decimal::from_i64(*i)),
        Value::Float(f) => Decimal::from_f64(*f),
        Value::Float32(f) => Decimal::from_f32(*f),
        _ => None,
    }
}

/// `Number.toExactLongOrNull()`.
pub fn to_exact_i64(value: &Value) -> Option<i64> {
    match value {
        Value::Int(i) => Some(*i),
        Value::Float(_) | Value::Float32(_) => to_decimal(value).and_then(|d| d.to_i64_exact()),
        _ => None,
    }
}

/// `java.lang.Double.compareTo`: NaN is greatest and equal to itself, `-0.0 < 0.0`.
pub fn java_double_compare(a: f64, b: f64) -> Ordering {
    match (a.is_nan(), b.is_nan()) {
        (true, true) => Ordering::Equal,
        (true, false) => Ordering::Greater,
        (false, true) => Ordering::Less,
        (false, false) => {
            if a < b {
                Ordering::Less
            } else if a > b {
                Ordering::Greater
            } else {
                // equal or signed zeros
                let ab = a.to_bits() as i64;
                let bb = b.to_bits() as i64;
                ab.cmp(&bb)
            }
        }
    }
}

fn is_integral(value: &Value) -> bool {
    matches!(value, Value::Int(_))
}

/// `cypherNumbersEqual`.
pub fn cypher_numbers_equal(left: &Value, right: &Value) -> bool {
    let both_integral = is_integral(left) && is_integral(right);
    let same_floating = matches!((left, right), (Value::Float(_), Value::Float(_)) | (Value::Float32(_), Value::Float32(_)));
    let ld = to_double(left);
    let rd = to_double(right);
    let either_non_finite = !ld.is_finite() || !rd.is_finite();
    if both_integral {
        left_i64(left) == left_i64(right)
    } else if same_floating || either_non_finite {
        ld == rd
    } else {
        match (to_decimal(left), to_decimal(right)) {
            (Some(a), Some(b)) => a.cmp_exact(&b) == Ordering::Equal,
            _ => ld == rd,
        }
    }
}

fn left_i64(v: &Value) -> i64 {
    match v {
        Value::Int(i) => *i,
        _ => 0,
    }
}

/// `compareCypherNumbers`: integral fast path, non-finite via `Double.compareTo`,
/// otherwise exact `BigDecimal` comparison.
pub fn compare_cypher_numbers(left: &Value, right: &Value) -> Ordering {
    if let (Value::Int(a), Value::Int(b)) = (left, right) {
        return a.cmp(b);
    }
    let ld = to_double(left);
    let rd = to_double(right);
    if !ld.is_finite() || !rd.is_finite() {
        return java_double_compare(ld, rd);
    }
    match (to_decimal(left), to_decimal(right)) {
        (Some(a), Some(b)) => a.cmp_exact(&b),
        _ => java_double_compare(ld, rd),
    }
}

// ============================================================================
// Equality
// ============================================================================

/// `cypherEquals` — three-valued structural equality.
pub fn cypher_equals(left: &Value, right: &Value) -> Option<bool> {
    match (left, right) {
        (Value::Null, _) | (_, Value::Null) => None,
        (a, b) if a.is_number() && b.is_number() => Some(cypher_numbers_equal(a, b)),
        (Value::List(a), Value::List(b)) => cypher_lists_equal(a, b),
        (Value::Map(a), Value::Map(b)) => cypher_maps_equal(a, b),
        (Value::Bool(a), Value::Bool(b)) => Some(a == b),
        (Value::Str(a), Value::Str(b)) => Some(a == b),
        (Value::Node(a), Value::Node(b)) => Some(a == b),
        (Value::Rel(a), Value::Rel(b)) => Some(a == b),
        (Value::Method(a), Value::Method(b)) => Some(a == b),
        (Value::Path(a), Value::Path(b)) => Some(a == b),
        _ => Some(false),
    }
}

fn cypher_lists_equal(left: &[Value], right: &[Value]) -> Option<bool> {
    if left.len() != right.len() {
        return Some(false);
    }
    let mut contains_null = false;
    for (a, b) in left.iter().zip(right.iter()) {
        match cypher_equals(a, b) {
            Some(false) => return Some(false),
            None => contains_null = true,
            Some(true) => {}
        }
    }
    if contains_null {
        None
    } else {
        Some(true)
    }
}

fn cypher_maps_equal(left: &IndexMap<String, Value>, right: &IndexMap<String, Value>) -> Option<bool> {
    if left.len() != right.len() || !left.keys().all(|k| right.contains_key(k)) {
        return Some(false);
    }
    let mut contains_null = false;
    for (k, a) in left {
        match cypher_equals(a, &right[k]) {
            Some(false) => return Some(false),
            None => contains_null = true,
            Some(true) => {}
        }
    }
    if contains_null {
        None
    } else {
        Some(true)
    }
}

// ============================================================================
// Canonical keys (cypherValueKey)
// ============================================================================

/// Hashable canonical key implementing `cypherValueKey` semantics:
/// numbers collapse across representations (`1 == 1.0 == 1.00`), non-finite
/// numbers compare by identity (`NaN == NaN`, `+Inf != -Inf`), collections are
/// normalized recursively, graph values by identity.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum Key {
    Null,
    Bool(bool),
    Number(Decimal),
    /// -1 = -Infinity, 1 = +Infinity, 2 = NaN
    NonFinite(i8),
    Str(Arc<str>),
    List(Vec<Key>),
    /// Entries sorted by key (Kotlin map equality is order independent).
    Map(Vec<(String, Key)>),
    Node(NodeRef),
    Rel(EdgeRef),
    Path(u32, Vec<NodeId>, Vec<Edge>),
    Method(MethodRef),
}

pub fn value_key(value: &Value) -> Key {
    match value {
        Value::Null => Key::Null,
        Value::Bool(b) => Key::Bool(*b),
        Value::Int(i) => Key::Number(Decimal::from_i64(*i)),
        Value::Float(_) | Value::Float32(_) => {
            let d = to_double(value);
            if d.is_finite() {
                Key::Number(to_decimal(value).unwrap_or_else(Decimal::zero))
            } else if d.is_nan() {
                Key::NonFinite(2)
            } else if d > 0.0 {
                Key::NonFinite(1)
            } else {
                Key::NonFinite(-1)
            }
        }
        Value::Str(s) => Key::Str(s.clone()),
        Value::List(l) => Key::List(l.iter().map(value_key).collect()),
        Value::Map(m) => {
            let mut entries: Vec<(String, Key)> = m.iter().map(|(k, v)| (k.clone(), value_key(v))).collect();
            entries.sort_by(|a, b| a.0.cmp(&b.0));
            Key::Map(entries)
        }
        Value::Node(n) => Key::Node(*n),
        Value::Rel(r) => Key::Rel(*r),
        Value::Path(p) => Key::Path(p.source, p.nodes.clone(), p.edges.clone()),
        Value::Method(m) => Key::Method(*m),
    }
}

/// `requiresCypherNormalization`: true when the value (recursively) contains a number.
pub fn requires_normalization(value: &Value) -> bool {
    match value {
        Value::Int(_) | Value::Float(_) | Value::Float32(_) => true,
        Value::List(l) => l.iter().any(requires_normalization),
        Value::Map(m) => m.values().any(requires_normalization),
        _ => false,
    }
}

// ============================================================================
// Kotlin toString
// ============================================================================

/// `java.lang.Double.toString` (shortest round-trip digits, JDK 19+ semantics).
pub fn java_double_to_string(v: f64) -> String {
    if v.is_nan() {
        return "NaN".to_string();
    }
    if v.is_infinite() {
        return if v > 0.0 { "Infinity".to_string() } else { "-Infinity".to_string() };
    }
    if v == 0.0 {
        return if v.is_sign_negative() { "-0.0".to_string() } else { "0.0".to_string() };
    }
    let sci = format!("{:e}", v.abs());
    java_float_layout(v.is_sign_negative(), &sci)
}

/// `java.lang.Float.toString`.
pub fn java_float_to_string(v: f32) -> String {
    if v.is_nan() {
        return "NaN".to_string();
    }
    if v.is_infinite() {
        return if v > 0.0 { "Infinity".to_string() } else { "-Infinity".to_string() };
    }
    if v == 0.0 {
        return if v.is_sign_negative() { "-0.0".to_string() } else { "0.0".to_string() };
    }
    let sci = format!("{:e}", v.abs());
    java_float_layout(v.is_sign_negative(), &sci)
}

/// Lays out shortest digits per the Java `Double.toString` contract:
/// plain decimal for `1e-3 <= |v| < 1e7`, computerized scientific otherwise.
fn java_float_layout(negative: bool, sci: &str) -> String {
    let (mantissa, exp) = sci.split_once('e').unwrap_or((sci, "0"));
    let exp: i32 = exp.parse().unwrap_or(0);
    let digits: String = mantissa.chars().filter(|c| c.is_ascii_digit()).collect();
    let digits = digits.trim_end_matches('0');
    let digits = if digits.is_empty() { "0" } else { digits };
    let mut out = String::new();
    if negative {
        out.push('-');
    }
    if (-3..7).contains(&exp) {
        if exp >= 0 {
            let int_len = (exp + 1) as usize;
            if digits.len() <= int_len {
                out.push_str(digits);
                for _ in digits.len()..int_len {
                    out.push('0');
                }
                out.push_str(".0");
            } else {
                out.push_str(&digits[..int_len]);
                out.push('.');
                out.push_str(&digits[int_len..]);
            }
        } else {
            out.push_str("0.");
            for _ in 0..(-exp - 1) {
                out.push('0');
            }
            out.push_str(digits);
        }
    } else {
        out.push_str(&digits[..1]);
        out.push('.');
        if digits.len() > 1 {
            out.push_str(&digits[1..]);
        } else {
            out.push('0');
        }
        out.push('E');
        out.push_str(&exp.to_string());
    }
    out
}

/// Kotlin `Any?.toString()` for a Cypher value. Graph values (nodes,
/// relationships, paths, methods) are rendered in a data-class-like form that
/// approximates the JVM output; scalars and collections are exact.
pub fn kotlin_to_string(value: &Value, ctx: &dyn GraphContext) -> String {
    let mut out = String::new();
    write_kotlin_string(&mut out, value, Some(ctx));
    out
}

/// `kotlin_to_string` without graph access (graph values use a stable but
/// approximate rendering).
pub fn kotlin_to_string_plain(value: &Value) -> String {
    let mut out = String::new();
    write_kotlin_string(&mut out, value, None);
    out
}

fn write_kotlin_string(out: &mut String, value: &Value, ctx: Option<&dyn GraphContext>) {
    match value {
        Value::Null => out.push_str("null"),
        Value::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
        Value::Int(i) => out.push_str(&i.to_string()),
        Value::Float(f) => out.push_str(&java_double_to_string(*f)),
        Value::Float32(f) => out.push_str(&java_float_to_string(*f)),
        Value::Str(s) => out.push_str(s),
        Value::List(l) => {
            out.push('[');
            for (i, v) in l.iter().enumerate() {
                if i > 0 {
                    out.push_str(", ");
                }
                write_kotlin_string(out, v, ctx);
            }
            out.push(']');
        }
        Value::Map(m) => {
            out.push('{');
            for (i, (k, v)) in m.iter().enumerate() {
                if i > 0 {
                    out.push_str(", ");
                }
                out.push_str(k);
                out.push('=');
                write_kotlin_string(out, v, ctx);
            }
            out.push('}');
        }
        Value::Node(n) => write_node_string(out, *n, ctx),
        Value::Rel(r) => write_rel_string(out, *r, ctx),
        Value::Path(p) => write_path_string(out, p, ctx),
        Value::Method(m) => match ctx {
            Some(ctx) => {
                let graph_id = ctx.method_property(*m, "graphId");
                out.push_str("MethodValue(graphId=");
                write_kotlin_string(out, &graph_id, Some(ctx));
                out.push_str(", method=");
                out.push_str(&ctx.method_signature(*m));
                out.push(')');
            }
            None => out.push_str(&format!("MethodValue(source={}, index={})", m.source, m.index)),
        },
    }
}

fn write_node_string(out: &mut String, n: NodeRef, ctx: Option<&dyn GraphContext>) {
    match ctx {
        Some(ctx) => {
            let cross = ctx.is_cross_graph();
            if cross {
                out.push_str("QualifiedNode(graphId=");
                out.push_str(ctx.graph_id(n.source));
                out.push_str(", node=");
            }
            out.push_str(ctx.node_type_name(n));
            out.push_str("(id=node#");
            out.push_str(&n.id.to_string());
            for (k, v) in ctx.node_properties(n) {
                if k == "id" {
                    continue;
                }
                out.push_str(", ");
                out.push_str(&k);
                out.push('=');
                write_kotlin_string(out, &v, Some(ctx));
            }
            out.push(')');
            if cross {
                out.push(')');
            }
        }
        None => out.push_str(&format!("Node(source={}, id=node#{})", n.source, n.id)),
    }
}

/// Kotlin data-class `toString()` of an edge, e.g.
/// `DataFlowEdge(from=node#1, to=node#2, kind=ASSIGN)`.
pub fn edge_to_string(r: EdgeRef, ctx: &dyn GraphContext) -> String {
    let mut out = String::new();
    let class = edge_class_simple_name(ctx.rel_type(r));
    out.push_str(class);
    out.push_str("(from=node#");
    out.push_str(&r.edge.from.to_string());
    out.push_str(", to=node#");
    out.push_str(&r.edge.to.to_string());
    if class == "CallEdge" {
        out.push_str(", isVirtual=");
        out.push_str(&kotlin_to_string(&ctx.rel_property(r, "virtual"), ctx));
        out.push_str(", isDynamic=");
        out.push_str(&kotlin_to_string(&ctx.rel_property(r, "dynamic"), ctx));
    } else {
        out.push_str(", kind=");
        out.push_str(&kotlin_to_string(&ctx.rel_property(r, "kind"), ctx));
        if class == "ControlFlowEdge" {
            out.push_str(", comparison=null");
        }
    }
    out.push(')');
    out
}

fn write_rel_string(out: &mut String, r: EdgeRef, ctx: Option<&dyn GraphContext>) {
    match ctx {
        Some(ctx) => {
            if ctx.is_cross_graph() {
                out.push_str("QualifiedEdge(graphId=");
                out.push_str(ctx.graph_id(r.source));
                out.push_str(", edge=");
                out.push_str(&edge_to_string(r, ctx));
                out.push(')');
            } else {
                out.push_str(&edge_to_string(r, ctx));
            }
        }
        None => out.push_str(&format!(
            "Edge(source={}, from=node#{}, to=node#{}, label={})",
            r.source, r.edge.from, r.edge.to, r.edge.label
        )),
    }
}

fn write_path_string(out: &mut String, p: &PathValue, ctx: Option<&dyn GraphContext>) {
    let cross = ctx.map(|c| c.is_cross_graph()).unwrap_or(false);
    if cross {
        out.push_str("QualifiedPath(graphId=");
        if let Some(ctx) = ctx {
            out.push_str(ctx.graph_id(p.source));
        }
        out.push_str(", nodes=");
    } else {
        out.push_str("Path(nodes=");
    }
    let nodes = Value::list(p.nodes.iter().map(|id| Value::Node(NodeRef { source: p.source, id: *id })).collect());
    write_kotlin_string(out, &nodes, ctx);
    out.push_str(", edges=");
    let edges = Value::list(p.edges.iter().map(|e| Value::Rel(EdgeRef { source: p.source, edge: *e })).collect());
    write_kotlin_string(out, &edges, ctx);
    out.push(')');
}

/// Simple Kotlin class name of the edge implementing a relationship type.
pub fn edge_class_simple_name(rel_type: &str) -> &'static str {
    match rel_type {
        "DATAFLOW" => "DataFlowEdge",
        "CALL" => "CallEdge",
        "TYPE" => "TypeEdge",
        "CONTROL_FLOW" => "ControlFlowEdge",
        _ => "ResourceEdge",
    }
}

/// Fully qualified JVM class name of the edge implementing a relationship type.
pub fn edge_class_name(rel_type: &str) -> String {
    format!("io.johnsonlee.graphite.core.{}", edge_class_simple_name(rel_type))
}

// ============================================================================
// Strings (UTF-16 semantics as on the JVM)
// ============================================================================

/// Kotlin `String.compareTo`: lexicographic over UTF-16 code units.
pub fn compare_utf16(a: &str, b: &str) -> Ordering {
    a.encode_utf16().cmp(b.encode_utf16())
}

/// `String.length` on the JVM (UTF-16 code units).
pub fn utf16_len(s: &str) -> usize {
    s.encode_utf16().count()
}

/// `String.substring(begin, end)` over UTF-16 indexes (unpaired surrogates are
/// replaced with U+FFFD).
pub fn utf16_substring(s: &str, begin: usize, end: usize) -> String {
    let units: Vec<u16> = s.encode_utf16().collect();
    String::from_utf16_lossy(&units[begin..end])
}

/// Kotlin `Char.isWhitespace()` (`Character.isWhitespace || Character.isSpaceChar`).
pub fn kotlin_is_whitespace(c: char) -> bool {
    if c == '\u{85}' {
        return false;
    }
    c.is_whitespace() || ('\u{1c}'..='\u{1f}').contains(&c)
}

// ============================================================================
// Comparison (`<`, `>`, `<=`, `>=`)
// ============================================================================

/// `ExpressionEvaluator.compareValues` (both operands non-null).
pub fn compare_values_with(a: &Value, b: &Value, ctx: &dyn GraphContext) -> Ordering {
    match (a, b) {
        (x, y) if x.is_number() && y.is_number() => compare_cypher_numbers(x, y),
        (Value::Str(x), Value::Str(y)) => compare_utf16(x, y),
        (Value::Bool(x), Value::Bool(y)) => x.cmp(y),
        (Value::Node(x), Value::Node(y)) if ctx.is_cross_graph() => {
            if x == y {
                Ordering::Equal
            } else {
                compare_utf16(&element_id(*x, ctx), &element_id(*y, ctx))
            }
        }
        (Value::Rel(x), Value::Rel(y)) if ctx.is_cross_graph() => {
            if x == y {
                Ordering::Equal
            } else {
                let xs = format!("{}:{}", ctx.graph_id(x.source), edge_to_string(*x, ctx));
                let ys = format!("{}:{}", ctx.graph_id(y.source), edge_to_string(*y, ctx));
                compare_utf16(&xs, &ys)
            }
        }
        _ => compare_utf16(&kotlin_to_string(a, ctx), &kotlin_to_string(b, ctx)),
    }
}

/// `compare_values_with` without graph access (string fallback uses the plain rendering).
pub fn compare_values(a: &Value, b: &Value) -> Ordering {
    match (a, b) {
        (x, y) if x.is_number() && y.is_number() => compare_cypher_numbers(x, y),
        (Value::Str(x), Value::Str(y)) => compare_utf16(x, y),
        (Value::Bool(x), Value::Bool(y)) => x.cmp(y),
        _ => compare_utf16(&kotlin_to_string_plain(a), &kotlin_to_string_plain(b)),
    }
}

/// `"$graphId:${node.id.value}"`.
pub fn element_id(n: NodeRef, ctx: &dyn GraphContext) -> String {
    format!("{}:{}", ctx.graph_id(n.source), n.id)
}

// ============================================================================
// Misc
// ============================================================================

/// `QueryPipeline.evaluateToInt`: `Number -> toLong().coerceIn(Int)`, `String ->
/// toLongOrNull() coerced else 0`, anything else (including null) -> 0.
pub fn to_int_for_skip_limit(value: &Value) -> i64 {
    let coerce = |l: i64| l.clamp(i32::MIN as i64, i32::MAX as i64);
    match value {
        Value::Int(i) => coerce(*i),
        Value::Float(f) => coerce(f64_to_i64(*f)),
        Value::Float32(f) => coerce(f64_to_i64(*f as f64)),
        Value::Str(s) => kotlin_parse_long(s).map(coerce).unwrap_or(0),
        _ => 0,
    }
}

/// WHERE / filter truthiness: only the boolean `true` keeps a row.
#[inline]
pub fn is_truthy(value: &Value) -> bool {
    matches!(value, Value::Bool(true))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn f(v: f64) -> Value {
        Value::Float(v)
    }
    fn i(v: i64) -> Value {
        Value::Int(v)
    }
    fn l(v: Vec<Value>) -> Value {
        Value::list(v)
    }
    fn m(entries: Vec<(&str, Value)>) -> Value {
        Value::map(entries.into_iter().map(|(k, v)| (k.to_string(), v)).collect())
    }

    #[test]
    fn structural_equality_is_recursive_exact_and_three_valued() {
        assert_eq!(Some(true), cypher_equals(&l(vec![i(1)]), &l(vec![f(1.0)])));
        assert_eq!(Some(false), cypher_equals(&l(vec![i(1)]), &l(vec![i(2)])));
        assert_eq!(None, cypher_equals(&l(vec![Value::Null]), &l(vec![Value::Null])));
        assert_eq!(Some(true), cypher_equals(&m(vec![("value", i(1))]), &m(vec![("value", f(1.0))])));
        assert_eq!(Some(false), cypher_equals(&m(vec![("value", i(1))]), &m(vec![("other", i(1))])));
        assert_eq!(None, cypher_equals(&m(vec![("value", Value::Null)]), &m(vec![("value", Value::Null)])));
        assert_eq!(Some(true), cypher_equals(&f(f64::INFINITY), &Value::Float32(f32::INFINITY)));
        assert_eq!(Some(false), cypher_equals(&f(f64::INFINITY), &f(f64::NEG_INFINITY)));
    }

    #[test]
    fn equality_across_types_and_nulls() {
        assert_eq!(None, cypher_equals(&Value::Null, &Value::Null));
        assert_eq!(None, cypher_equals(&i(1), &Value::Null));
        assert_eq!(Some(false), cypher_equals(&i(1), &Value::str("1")));
        assert_eq!(Some(true), cypher_equals(&i(2), &Value::Int(2)));
        assert_eq!(Some(false), cypher_equals(&f(f64::NAN), &f(f64::NAN)));
        assert_eq!(Some(true), cypher_equals(&Value::Float32(1.1), &f(1.1)));
        assert_eq!(Some(false), cypher_equals(&i(9007199254740993), &f(9007199254740992.0)));
        assert_eq!(Some(true), cypher_equals(&i(9007199254740992), &f(9007199254740992.0)));
        assert_eq!(Some(true), cypher_equals(&Value::Bool(true), &Value::Bool(true)));
        assert_eq!(Some(false), cypher_equals(&Value::Bool(true), &i(1)));
    }

    #[test]
    fn value_keys_normalize_numbers_recursively_without_losing_precision() {
        assert_eq!(value_key(&i(1)), value_key(&f(1.0)));
        assert_eq!(
            value_key(&l(vec![m(vec![("value", i(1))])])),
            value_key(&l(vec![m(vec![("value", f(1.0))])]))
        );
        assert_ne!(value_key(&i(9007199254740993)), value_key(&f(9007199254740992.0)));
        assert_eq!(value_key(&f(f64::INFINITY)), value_key(&Value::Float32(f32::INFINITY)));
        assert_ne!(value_key(&f(f64::INFINITY)), value_key(&f(f64::NEG_INFINITY)));
        assert_eq!(value_key(&f(f64::NAN)), value_key(&f(f64::NAN)));
        assert_eq!(value_key(&f(0.0)), value_key(&f(-0.0)));
        assert_eq!(value_key(&f(1.10)), value_key(&Value::Float32(1.1)));
        assert_ne!(value_key(&Value::Null), value_key(&Value::Bool(false)));
    }

    #[test]
    fn value_keys_for_maps_are_order_independent() {
        let a = m(vec![("a", i(1)), ("b", i(2))]);
        let b = m(vec![("b", f(2.0)), ("a", f(1.0))]);
        assert_eq!(value_key(&a), value_key(&b));
    }

    #[test]
    fn requires_normalization_detects_numbers() {
        assert!(!requires_normalization(&l(vec![Value::str("value"), Value::Null])));
        assert!(!requires_normalization(&m(vec![("key", l(vec![Value::str("value")]))])));
        assert!(requires_normalization(&l(vec![Value::str("value"), i(1)])));
        assert!(requires_normalization(&m(vec![("key", l(vec![i(1)]))])));
    }

    #[test]
    fn numeric_comparison_is_exact_and_orders_nonfinite_values_explicitly() {
        assert_eq!(Ordering::Greater, compare_cypher_numbers(&i(9007199254740993), &i(9007199254740992)));
        assert_eq!(Ordering::Equal, compare_cypher_numbers(&i(1), &f(1.0)));
        assert_eq!(Ordering::Less, compare_cypher_numbers(&f(1.5), &i(2)));
        assert_eq!(Ordering::Greater, compare_cypher_numbers(&f(f64::INFINITY), &i(i64::MAX)));
        assert_eq!(Ordering::Less, compare_cypher_numbers(&f(f64::NEG_INFINITY), &i(i64::MIN)));
        assert_eq!(Ordering::Greater, compare_cypher_numbers(&f(f64::NAN), &i(5)));
        assert_eq!(Ordering::Greater, compare_cypher_numbers(&i(9007199254740993), &f(9007199254740992.0)));
        // BigDecimal has no signed zero, so -0.0 and 0.0 compare equal here
        // (unlike `java_double_compare`, which orders -0.0 first).
        assert_eq!(Ordering::Equal, compare_cypher_numbers(&f(-0.0), &f(0.0)));
        assert_eq!(Ordering::Less, java_double_compare(-0.0, 0.0));
    }

    #[test]
    fn exact_long_conversion_rejects_fractional_nonfinite_and_out_of_range_values() {
        assert_eq!(Some(2), to_exact_i64(&i(2)));
        assert_eq!(Some(2), to_exact_i64(&f(2.0)));
        assert_eq!(None, to_exact_i64(&f(2.5)));
        assert_eq!(None, to_exact_i64(&f(f64::INFINITY)));
        assert_eq!(None, to_exact_i64(&f(9223372036854775808.0)));
        // Double.toString rounds to -9.223372036854776E18 = -9223372036854776000,
        // which overflows a long, so longValueExact throws and Kotlin yields null.
        assert_eq!(None, to_exact_i64(&f(-9223372036854775808.0)));
        assert_eq!(Some(i64::MIN), to_exact_i64(&i(i64::MIN)));
        assert_eq!(Some(1000000), to_exact_i64(&f(1e6)));
    }

    #[test]
    fn java_double_to_string_matches_jvm_layout() {
        assert_eq!("1.0", java_double_to_string(1.0));
        assert_eq!("1.5", java_double_to_string(1.5));
        assert_eq!("100.0", java_double_to_string(100.0));
        assert_eq!("0.001", java_double_to_string(0.001));
        assert_eq!("1.0E-4", java_double_to_string(0.0001));
        assert_eq!("1234567.0", java_double_to_string(1234567.0));
        assert_eq!("1.0E7", java_double_to_string(10000000.0));
        assert_eq!("1.0E20", java_double_to_string(1e20));
        assert_eq!("1.234E-5", java_double_to_string(0.00001234));
        assert_eq!("-3.14", java_double_to_string(-3.14));
        assert_eq!("NaN", java_double_to_string(f64::NAN));
        assert_eq!("Infinity", java_double_to_string(f64::INFINITY));
        assert_eq!("-Infinity", java_double_to_string(f64::NEG_INFINITY));
        assert_eq!("0.0", java_double_to_string(0.0));
        assert_eq!("-0.0", java_double_to_string(-0.0));
        assert_eq!("9.007199254740992E15", java_double_to_string(9007199254740992.0));
        assert_eq!("1.1", java_float_to_string(1.1));
        assert_eq!("2.5", java_float_to_string(2.5));
    }

    #[test]
    fn kotlin_to_string_renders_collections_like_the_jvm() {
        let v = l(vec![i(1), Value::str("a"), Value::Null, m(vec![("k", f(2.0))]), Value::Bool(true)]);
        assert_eq!("[1, a, null, {k=2.0}, true]", kotlin_to_string_plain(&v));
    }

    #[test]
    fn java_parse_double_follows_jvm_grammar() {
        assert_eq!(Some(3.14), java_parse_double("3.14"));
        assert_eq!(Some(3.14), java_parse_double(" 3.14 "));
        assert_eq!(Some(1.0), java_parse_double("1d"));
        assert_eq!(Some(100000.0), java_parse_double("1e5"));
        assert_eq!(Some(0.5), java_parse_double(".5"));
        assert_eq!(Some(5.0), java_parse_double("5."));
        assert!(java_parse_double("NaN").map(f64::is_nan).unwrap_or(false));
        assert_eq!(Some(f64::NEG_INFINITY), java_parse_double("-Infinity"));
        assert_eq!(None, java_parse_double("inf"));
        assert_eq!(None, java_parse_double("nan"));
        assert_eq!(None, java_parse_double("abc"));
        assert_eq!(None, java_parse_double(""));
        assert_eq!(None, java_parse_double("1e"));
        assert_eq!(None, java_parse_double("1.2.3"));
    }

    #[test]
    fn kotlin_parse_long_follows_kotlin_grammar() {
        assert_eq!(Some(42), kotlin_parse_long("42"));
        assert_eq!(Some(-42), kotlin_parse_long("-42"));
        assert_eq!(Some(42), kotlin_parse_long("+42"));
        assert_eq!(None, kotlin_parse_long("42.0"));
        assert_eq!(None, kotlin_parse_long(" 42"));
        assert_eq!(None, kotlin_parse_long("-"));
        assert_eq!(None, kotlin_parse_long("99999999999999999999"));
    }

    #[test]
    fn to_double_coercion() {
        assert_eq!(3.14, to_double(&Value::str("3.14")));
        assert_eq!(0.0, to_double(&Value::str("abc")));
        assert_eq!(0.0, to_double(&Value::Bool(true)));
        assert_eq!(0.0, to_double(&Value::Null));
        assert_eq!(2.0, to_double(&i(2)));
    }

    #[test]
    fn skip_limit_int_coercion() {
        assert_eq!(3, to_int_for_skip_limit(&i(3)));
        assert_eq!(3, to_int_for_skip_limit(&f(3.9)));
        assert_eq!(7, to_int_for_skip_limit(&Value::str("7")));
        assert_eq!(0, to_int_for_skip_limit(&Value::str("abc")));
        assert_eq!(0, to_int_for_skip_limit(&Value::Null));
        assert_eq!(0, to_int_for_skip_limit(&Value::Bool(true)));
        assert_eq!(i32::MAX as i64, to_int_for_skip_limit(&i(i64::MAX)));
        assert_eq!(i32::MIN as i64, to_int_for_skip_limit(&Value::str("-99999999999")));
    }

    #[test]
    fn compare_values_fallbacks() {
        assert_eq!(Ordering::Less, compare_values(&Value::str("abc"), &Value::str("def")));
        assert_eq!(Ordering::Less, compare_values(&Value::Bool(false), &Value::Bool(true)));
        // "true" vs "3" — toString fallback
        assert_eq!(Ordering::Greater, compare_values(&Value::Bool(true), &i(3)));
        assert_eq!(Ordering::Less, compare_values(&l(vec![i(1)]), &l(vec![i(2)])));
        // UTF-16 ordering: U+FFFF sorts before U+10000 on the JVM
        assert_eq!(Ordering::Greater, compare_values(&Value::str("\u{FFFF}"), &Value::str("\u{10000}")));
    }

    #[test]
    fn utf16_helpers() {
        assert_eq!(2, utf16_len("\u{1F600}"));
        assert_eq!("b", utf16_substring("abc", 1, 2));
        assert!(kotlin_is_whitespace(' '));
        assert!(kotlin_is_whitespace('\u{1c}'));
        assert!(!kotlin_is_whitespace('\u{85}'));
        assert!(kotlin_is_whitespace('\u{a0}'));
    }
}
