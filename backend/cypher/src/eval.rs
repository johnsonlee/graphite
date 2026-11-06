//! Expression evaluation with Cypher three-valued logic.

use crate::ast::*;
use crate::context::GraphContext;
use crate::error::{CypherError, CypherResult};
use crate::functions;
use crate::semantics::{
    cypher_equals, kotlin_to_string, predicate_order, to_double, utf16_len, value_key,
    PredicateOrder, Row,
};
use crate::value::Value;
use indexmap::IndexMap;
use parking_lot::Mutex;
use regex::Regex;
use std::collections::HashMap;
use std::sync::Arc;

const REGEX_CACHE_CAPACITY: usize = 256;

/// Aggregation function names (lowercased) — `CypherFunctions.isAggregation`.
pub fn is_aggregation_name(name: &str) -> bool {
    matches!(
        name.to_ascii_lowercase().as_str(),
        "count"
            | "sum"
            | "avg"
            | "min"
            | "max"
            | "collect"
            | "percentilecont"
            | "percentiledisc"
            | "stdev"
            | "stdevp"
    )
}

/// Whether an expression contains an aggregation, using Kotlin's exact recursion set.
///
/// Note the deliberate gaps: `CASE`, list/map literals, `AND`/`OR`/`NOT`, subscripts
/// and slices are **not** descended into, so `CASE WHEN .. THEN count(x) END` does
/// not register as an aggregation.
pub fn contains_aggregation(e: &Expr) -> bool {
    match e {
        Expr::CountStar => true,
        Expr::FunctionCall { name, args, .. } => {
            is_aggregation_name(name) || args.iter().any(contains_aggregation)
        }
        Expr::LabelPredicate { expr, .. }
        | Expr::Property { expr, .. }
        | Expr::Unary { expr, .. }
        | Expr::Distinct(expr)
        | Expr::IsNull(expr)
        | Expr::IsNotNull(expr)
        | Expr::Not(expr) => contains_aggregation(expr),
        Expr::Binary { left, right, .. }
        | Expr::Comparison { left, right, .. }
        | Expr::StringOp { left, right, .. }
        | Expr::In { left, right }
        | Expr::And(left, right)
        | Expr::Or(left, right)
        | Expr::Xor(left, right) => contains_aggregation(left) || contains_aggregation(right),
        Expr::ListLiteral(items) => items.iter().any(contains_aggregation),
        Expr::MapLiteral(items) => items.iter().any(|(_, e)| contains_aggregation(e)),
        Expr::Case {
            test,
            whens,
            else_expr,
        } => {
            test.as_deref().is_some_and(contains_aggregation)
                || whens
                    .iter()
                    .any(|(a, b)| contains_aggregation(a) || contains_aggregation(b))
                || else_expr.as_deref().is_some_and(contains_aggregation)
        }
        Expr::ListComprehension {
            list, filter, map, ..
        } => {
            contains_aggregation(list)
                || filter.as_deref().is_some_and(contains_aggregation)
                || map.as_deref().is_some_and(contains_aggregation)
        }
        Expr::PredicateFunction {
            list, predicate, ..
        } => contains_aggregation(list) || predicate.as_deref().is_some_and(contains_aggregation),
        Expr::Subscript { expr, index } => {
            contains_aggregation(expr) || contains_aggregation(index)
        }
        Expr::Slice { expr, from, to } => {
            contains_aggregation(expr)
                || from.as_deref().is_some_and(contains_aggregation)
                || to.as_deref().is_some_and(contains_aggregation)
        }
        Expr::ExistsSubquery(_) | Expr::PatternComprehension { .. } => false,
        Expr::PatternPredicate(pattern) => pattern
            .nodes
            .iter()
            .flat_map(|n| &n.properties)
            .chain(pattern.rels.iter().flat_map(|r| &r.properties))
            .any(|(_, e)| contains_aggregation(e)),
        Expr::Literal(_) | Expr::Variable(_) | Expr::Parameter(_) => false,
    }
}

// Historical recursion policy retained for Graphite compatibility.
pub(crate) fn legacy_contains_aggregation(e: &Expr) -> bool {
    match e {
        Expr::CountStar => true,
        Expr::FunctionCall { name, args, .. } => {
            is_aggregation_name(name) || args.iter().any(legacy_contains_aggregation)
        }
        Expr::Property { expr, .. } => legacy_contains_aggregation(expr),
        Expr::PredicateFunction {
            list, predicate, ..
        } => {
            legacy_contains_aggregation(list)
                || predicate
                    .as_deref()
                    .is_some_and(legacy_contains_aggregation)
        }
        Expr::Binary { left, right, .. } => {
            legacy_contains_aggregation(left) || legacy_contains_aggregation(right)
        }
        Expr::Comparison { left, right, .. } => {
            legacy_contains_aggregation(left) || legacy_contains_aggregation(right)
        }
        Expr::Distinct(inner) => legacy_contains_aggregation(inner),
        _ => false,
    }
}

struct RegexCache {
    map: HashMap<String, Arc<CompiledRegex>>,
    order: Vec<String>,
}

/// A compiled `=~` pattern. Literal and literal-prefix patterns bypass the regex engine.
pub(crate) enum CompiledRegex {
    /// Whole-string equality.
    Literal(String),
    /// `X.*` — prefix match, and the remainder must contain no Java line terminator.
    Prefix(String),
    Regex(Regex),
    /// The pattern uses syntax the `regex` crate cannot express.
    Unsupported(String),
}

impl CompiledRegex {
    pub(crate) fn matches(&self, input: &str) -> CypherResult<bool> {
        match self {
            CompiledRegex::Literal(l) => Ok(input == l),
            CompiledRegex::Prefix(p) => {
                if !input.starts_with(p.as_str()) {
                    return Ok(false);
                }
                // `.` does not match a line terminator by default in Java.
                Ok(!input[p.len()..].chars().any(is_java_line_terminator))
            }
            CompiledRegex::Regex(r) => Ok(r
                .find(input)
                .is_some_and(|m| m.start() == 0 && m.end() == input.len())),
            CompiledRegex::Unsupported(p) => Err(CypherError::Other(format!(
                "Unsupported regex construct in pattern: {p}"
            ))),
        }
    }
}

fn is_java_line_terminator(c: char) -> bool {
    matches!(c, '\n' | '\r' | '\u{85}' | '\u{2028}' | '\u{2029}')
}

const REGEX_META: &[char] = &[
    '\\', '.', '^', '$', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}',
];

/// Recognise a pattern that is a plain literal, optionally with a trailing `.*`.
fn literal_fast_path(pattern: &str) -> Option<CompiledRegex> {
    let (body, prefix) = match pattern.strip_suffix(".*") {
        Some(b) => (b, true),
        None => (pattern, false),
    };
    let mut out = String::with_capacity(body.len());
    let mut chars = body.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '\\' {
            match chars.next() {
                Some(n) if REGEX_META.contains(&n) => out.push(n),
                _ => return None,
            }
        } else if REGEX_META.contains(&c) {
            return None;
        } else {
            out.push(c);
        }
    }
    Some(if prefix {
        CompiledRegex::Prefix(out)
    } else {
        CompiledRegex::Literal(out)
    })
}

pub(crate) fn compile_regex(pattern: &str) -> Arc<CompiledRegex> {
    if let Some(fast) = literal_fast_path(pattern) {
        return Arc::new(fast);
    }
    match Regex::new(pattern) {
        Ok(r) => Arc::new(CompiledRegex::Regex(r)),
        Err(_) => Arc::new(CompiledRegex::Unsupported(pattern.to_string())),
    }
}

pub struct Evaluator<'a> {
    ctx: &'a dyn GraphContext,
    params: &'a IndexMap<String, Value>,
    regex_cache: Mutex<RegexCache>,
}

impl<'a> Evaluator<'a> {
    pub fn new(ctx: &'a dyn GraphContext, params: &'a IndexMap<String, Value>) -> Evaluator<'a> {
        Evaluator {
            ctx,
            params,
            regex_cache: Mutex::new(RegexCache {
                map: HashMap::new(),
                order: Vec::new(),
            }),
        }
    }

    pub fn context(&self) -> &'a dyn GraphContext {
        self.ctx
    }

    fn regex(&self, pattern: &str) -> Arc<CompiledRegex> {
        let mut cache = self.regex_cache.lock();
        if let Some(hit) = cache.map.get(pattern).cloned() {
            if let Some(pos) = cache.order.iter().position(|k| k == pattern) {
                let k = cache.order.remove(pos);
                cache.order.push(k);
            }
            return hit;
        }
        let compiled = compile_regex(pattern);
        cache.map.insert(pattern.to_string(), compiled.clone());
        cache.order.push(pattern.to_string());
        while cache.order.len() > REGEX_CACHE_CAPACITY {
            let evict = cache.order.remove(0);
            cache.map.remove(&evict);
        }
        compiled
    }

    pub fn eval(&self, expr: &Expr, row: &Row) -> CypherResult<Value> {
        match expr {
            Expr::LabelPredicate { expr, labels } => {
                let value = self.eval(expr, row)?;
                match value {
                    Value::Null => Ok(Value::Null),
                    Value::Node(node) => {
                        let matches = if let Some(graph) = self.ctx.property_graph() {
                            graph.ensure_live_entity(&Value::Node(node))?;
                            graph.node(node.id).is_some_and(|node| {
                                labels.iter().all(|label| node.labels.contains(label))
                            })
                        } else {
                            let actual = self.ctx.node_labels(node);
                            labels.iter().all(|label| actual.contains(&label.as_str()))
                        };
                        Ok(Value::Bool(matches))
                    }
                    Value::Method(_) => {
                        Ok(Value::Bool(labels.iter().all(|label| label == "Method")))
                    }
                    Value::Rel(rel) => {
                        let type_name = if let Some(id) = rel.property_id {
                            self.ctx
                                .property_graph()
                                .and_then(|graph| graph.relationship(u64::from(id.get())))
                                .map(|relationship| relationship.type_name)
                                .ok_or_else(|| {
                                    CypherError::execution(
                                        "EntityNotFound",
                                        "DeletedEntityAccess",
                                        "The relationship has been deleted",
                                    )
                                })?
                        } else {
                            self.ctx.rel_type(rel).to_owned()
                        };
                        Ok(Value::Bool(labels.iter().all(|label| *label == type_name)))
                    }
                    _ => Err(CypherError::execution(
                        "TypeError",
                        "InvalidArgumentType",
                        "A label predicate requires a node or relationship",
                    )),
                }
            }
            Expr::PatternComprehension {
                pattern,
                filter,
                map,
            } => {
                for variable in pattern.nodes.iter().filter_map(|n| n.variable.as_ref()) {
                    if row.get(variable).is_some_and(Value::is_null) {
                        return Ok(Value::Null);
                    }
                }
                let mut result = Vec::new();
                for binding in self.ctx.pattern_rows(pattern, row)? {
                    self.ctx.check_cancelled()?;
                    if let Some(filter) = filter {
                        if self.eval(filter, &binding)?.as_bool() != Some(true) {
                            continue;
                        }
                    }
                    result.push(self.eval(map, &binding)?);
                }
                Ok(Value::list(result))
            }
            Expr::ExistsSubquery(clauses) => Ok(Value::Bool(
                !self.ctx.subquery_rows(clauses, row)?.is_empty(),
            )),
            Expr::PatternPredicate(pattern) => {
                // A null bound endpoint makes the predicate unknown, rather than
                // pretending that the graph simply contains no matching path.
                for variable in pattern
                    .nodes
                    .iter()
                    .filter_map(|n| n.variable.as_ref())
                    .chain(pattern.rels.iter().filter_map(|r| r.variable.as_ref()))
                {
                    if row.get(variable).is_some_and(Value::is_null) {
                        return Ok(Value::Null);
                    }
                }
                Ok(Value::Bool(self.ctx.pattern_exists(pattern, row)?))
            }
            Expr::Literal(l) => Ok(literal_value(l)),
            Expr::Variable(name) => Ok(row.get(name).cloned().unwrap_or(Value::Null)),
            Expr::Parameter(name) => Ok(self.params.get(name).cloned().unwrap_or(Value::Null)),
            Expr::Property { expr, key } => {
                let base = self.eval(expr, row)?;
                if let Some(graph) = self.ctx.property_graph() {
                    graph.ensure_live_entity(&base)?;
                }
                Ok(self.property(&base, key))
            }
            Expr::CountStar => Err(CypherError::Aggregation("count".into())),
            Expr::Distinct(inner) => self.eval(inner, row),
            Expr::FunctionCall { name, args, .. } => {
                if name.eq_ignore_ascii_case("exists")
                    && args.len() == 1
                    && matches!(args[0], Expr::PatternPredicate(_))
                {
                    return self.eval(&args[0], row);
                }
                if is_aggregation_name(name) {
                    return Err(CypherError::Aggregation(name.to_ascii_lowercase()));
                }
                let mut vals = Vec::with_capacity(args.len());
                for a in args {
                    vals.push(self.eval(a, row)?);
                }
                functions::call(name, &vals, self.ctx)
            }
            Expr::Binary { op, left, right } => {
                let l = self.eval(left, row)?;
                let r = self.eval(right, row)?;
                self.binary(*op, &l, &r)
            }
            Expr::Unary { negate, expr } => {
                let v = self.eval(expr, row)?;
                if !*negate {
                    return Ok(v);
                }
                Ok(match v {
                    Value::Int(i) => Value::Int(-i),
                    Value::Float(f) => Value::Float(-f),
                    Value::Float32(f) => Value::Float32(-f),
                    Value::Null => Value::Null,
                    _ => Value::Null,
                })
            }
            Expr::Comparison { op, left, right } => {
                let l = self.eval(left, row)?;
                let r = self.eval(right, row)?;
                Ok(self.compare(*op, &l, &r))
            }
            Expr::StringOp { op, left, right } => {
                let l = self.eval(left, row)?;
                let r = self.eval(right, row)?;
                self.string_op(*op, &l, &r)
            }
            Expr::In { left, right } => {
                let l = self.eval(left, row)?;
                let r = self.eval(right, row)?;
                Ok(self.in_list(&l, &r))
            }
            Expr::IsNull(e) => Ok(Value::Bool(self.eval_is_null(e, row)?)),
            Expr::IsNotNull(e) => Ok(Value::Bool(!self.eval_is_null(e, row)?)),
            Expr::Not(e) => {
                let v = self.eval(e, row)?;
                match v {
                    Value::Null => Ok(Value::Null),
                    Value::Bool(b) => Ok(Value::Bool(!b)),
                    other => Err(class_cast(&other, "java.lang.Boolean")),
                }
            }
            // AND/OR never short-circuit: Kotlin evaluates both sides.
            Expr::And(a, b) => {
                let l = self.eval(a, row)?.as_bool();
                let r = self.eval(b, row)?.as_bool();
                Ok(match (l, r) {
                    (Some(false), _) | (_, Some(false)) => Value::Bool(false),
                    (Some(x), Some(y)) => Value::Bool(x && y),
                    _ => Value::Null,
                })
            }
            Expr::Or(a, b) => {
                let l = self.eval(a, row)?.as_bool();
                let r = self.eval(b, row)?.as_bool();
                Ok(match (l, r) {
                    (Some(true), _) | (_, Some(true)) => Value::Bool(true),
                    (Some(x), Some(y)) => Value::Bool(x || y),
                    _ => Value::Null,
                })
            }
            Expr::Xor(a, b) => {
                let l = self.eval(a, row)?.as_bool();
                let r = self.eval(b, row)?.as_bool();
                Ok(match (l, r) {
                    (Some(x), Some(y)) => Value::Bool(x ^ y),
                    _ => Value::Null,
                })
            }
            Expr::Case {
                test,
                whens,
                else_expr,
            } => {
                match test {
                    Some(t) => {
                        let tv = self.eval(t, row)?;
                        for (cond, result) in whens {
                            let cv = self.eval(cond, row)?;
                            if cypher_equals(&tv, &cv) == Some(true) {
                                return self.eval(result, row);
                            }
                        }
                    }
                    None => {
                        for (cond, result) in whens {
                            if self.eval(cond, row)?.as_bool() == Some(true) {
                                return self.eval(result, row);
                            }
                        }
                    }
                }
                match else_expr {
                    Some(e) => self.eval(e, row),
                    None => Ok(Value::Null),
                }
            }
            Expr::ListLiteral(items) => {
                let mut out = Vec::with_capacity(items.len());
                for i in items {
                    out.push(self.eval(i, row)?);
                }
                Ok(Value::list(out))
            }
            Expr::MapLiteral(entries) => {
                let mut m = IndexMap::with_capacity(entries.len());
                for (k, v) in entries {
                    m.insert(k.clone(), self.eval(v, row)?);
                }
                Ok(Value::map(m))
            }
            Expr::ListComprehension {
                variable,
                list,
                filter,
                map,
            } => {
                let lv = self.eval(list, row)?;
                let items = match lv.as_list() {
                    Some(l) => l.to_vec(),
                    None => return Ok(Value::Null),
                };
                let mut out = Vec::new();
                let mut scratch = row.clone();
                for item in items {
                    scratch.insert(variable.clone(), item.clone());
                    if let Some(f) = filter {
                        if self.eval(f, &scratch)?.as_bool() != Some(true) {
                            continue;
                        }
                    }
                    out.push(match map {
                        Some(m) => self.eval(m, &scratch)?,
                        None => item,
                    });
                }
                Ok(Value::list(out))
            }
            Expr::PredicateFunction {
                name,
                variable,
                list,
                predicate,
            } => self.predicate_function(name, variable, list, predicate.as_deref(), row),
            Expr::Subscript { expr, index } => {
                let base = self.eval(expr, row)?;
                if let Some(graph) = self.ctx.property_graph() {
                    graph.ensure_live_entity(&base)?;
                }
                let idx = self.eval(index, row)?;
                self.subscript(&base, &idx)
            }
            Expr::Slice { expr, from, to } => {
                let base = self.eval(expr, row)?;
                let f = match from {
                    Some(e) => Some(self.eval(e, row)?),
                    None => None,
                };
                let t = match to {
                    Some(e) => Some(self.eval(e, row)?),
                    None => None,
                };
                Ok(self.slice(&base, f.as_ref(), t.as_ref()))
            }
        }
    }

    /// `resolveProperty(obj, name)`.
    pub fn property(&self, base: &Value, key: &str) -> Value {
        match base {
            Value::Node(n) => self.ctx.node_property(*n, key),
            Value::Rel(r) => self.ctx.rel_property(*r, key),
            Value::Method(m) => self.ctx.method_property(*m, key),
            Value::Map(m) => m.get(key).cloned().unwrap_or(Value::Null),
            Value::Temporal(t) => t.property(key),
            Value::Path(p) => match key {
                "length" => Value::Int(p.edges.len() as i64),
                "graphId" if self.ctx.is_cross_graph() => Value::str(self.ctx.graph_id(p.source)),
                _ => Value::Null,
            },
            _ => Value::Null,
        }
    }

    fn eval_is_null(&self, expr: &Expr, row: &Row) -> CypherResult<bool> {
        if let Expr::Property { expr, key } = expr {
            let base = self.eval(expr, row)?;
            if let Some(graph) = self.ctx.property_graph() {
                graph.ensure_live_entity(&base)?;
            }
            return Ok(match base {
                Value::Node(node) => self.ctx.node_property_is_null(node, key),
                Value::Method(method) => self.ctx.method_property_is_null(method, key),
                _ => self.property(&base, key).is_null(),
            });
        }
        Ok(self.eval(expr, row)?.is_null())
    }

    fn binary(&self, op: BinOp, l: &Value, r: &Value) -> CypherResult<Value> {
        if op == BinOp::Add {
            match (l, r) {
                (Value::List(a), Value::List(b)) => {
                    let mut out = a.as_ref().clone();
                    out.extend(b.iter().cloned());
                    return Ok(Value::list(out));
                }
                (Value::List(a), other) => {
                    let mut out = a.as_ref().clone();
                    out.push(other.clone());
                    return Ok(Value::list(out));
                }
                (other, Value::List(b)) => {
                    let mut out = vec![other.clone()];
                    out.extend(b.iter().cloned());
                    return Ok(Value::list(out));
                }
                _ => {}
            }
        }
        if l.is_null() || r.is_null() {
            return Ok(Value::Null);
        }
        if let Some(result) = crate::temporal::binary(op, l, r) {
            return result;
        }
        // String concatenation wins for '+' when either side is a String.
        if op == BinOp::Add {
            if let (Value::Str(a), _) = (l, r) {
                return Ok(Value::str(format!(
                    "{}{}",
                    a,
                    kotlin_to_string(r, self.ctx)
                )));
            }
            if let (_, Value::Str(b)) = (l, r) {
                return Ok(Value::str(format!(
                    "{}{}",
                    kotlin_to_string(l, self.ctx),
                    b
                )));
            }
        }
        if op == BinOp::Pow {
            return Ok(Value::Float(to_double(l).powf(to_double(r))));
        }
        let both_int = matches!(l, Value::Int(_)) && matches!(r, Value::Int(_));
        if both_int {
            let (a, b) = match (l, r) {
                (Value::Int(a), Value::Int(b)) => (*a, *b),
                _ => unreachable!(),
            };
            return Ok(match op {
                BinOp::Add => Value::Int(a.wrapping_add(b)),
                BinOp::Sub => Value::Int(a.wrapping_sub(b)),
                BinOp::Mul => Value::Int(a.wrapping_mul(b)),
                BinOp::Div => {
                    if b == 0 {
                        return Err(CypherError::Runtime("Division by zero".into()));
                    }
                    Value::Int(a.wrapping_div(b))
                }
                BinOp::Mod => {
                    if b == 0 {
                        return Err(CypherError::Runtime("Division by zero".into()));
                    }
                    Value::Int(a.wrapping_rem(b))
                }
                BinOp::Pow => unreachable!(),
            });
        }
        if !l.is_number() || !r.is_number() {
            return Ok(Value::Null);
        }
        let a = to_double(l);
        let b = to_double(r);
        Ok(match op {
            BinOp::Add => Value::Float(a + b),
            BinOp::Sub => Value::Float(a - b),
            BinOp::Mul => Value::Float(a * b),
            BinOp::Div => Value::Float(a / b),
            BinOp::Mod => {
                if b == 0.0 {
                    return Err(CypherError::Runtime("Division by zero".into()));
                }
                Value::Float(a % b)
            }
            BinOp::Pow => unreachable!(),
        })
    }

    fn compare(&self, op: CmpOp, l: &Value, r: &Value) -> Value {
        match op {
            CmpOp::Eq => match cypher_equals(l, r) {
                Some(b) => Value::Bool(b),
                None => Value::Null,
            },
            CmpOp::Ne => match cypher_equals(l, r) {
                Some(b) => Value::Bool(!b),
                None => Value::Null,
            },
            _ => {
                if l.is_null() || r.is_null() {
                    return Value::Null;
                }
                let ord = match predicate_order(l, r) {
                    PredicateOrder::Ordered(order) => order,
                    PredicateOrder::Unknown => return Value::Null,
                    PredicateOrder::Unordered => return Value::Bool(false),
                };
                Value::Bool(match op {
                    CmpOp::Lt => ord.is_lt(),
                    CmpOp::Gt => ord.is_gt(),
                    CmpOp::Le => ord.is_le(),
                    CmpOp::Ge => ord.is_ge(),
                    _ => unreachable!(),
                })
            }
        }
    }

    fn string_op(&self, op: StrOp, l: &Value, r: &Value) -> CypherResult<Value> {
        let (a, b) = match (l.as_str(), r.as_str()) {
            (Some(a), Some(b)) => (a, b),
            _ => return Ok(Value::Null),
        };
        Ok(Value::Bool(match op {
            StrOp::StartsWith => a.starts_with(b),
            StrOp::EndsWith => a.ends_with(b),
            StrOp::Contains => a.contains(b),
            StrOp::Regex => self.regex(b).matches(a)?,
        }))
    }

    fn in_list(&self, needle: &Value, haystack: &Value) -> Value {
        let items = match haystack.as_list() {
            Some(l) => l,
            None => return Value::Null,
        };
        if items.is_empty() {
            // Empty list is false even for a null needle.
            return Value::Bool(false);
        }
        let mut saw_null = false;
        for item in items {
            match cypher_equals(needle, item) {
                Some(true) => return Value::Bool(true),
                Some(false) => {}
                None => saw_null = true,
            }
        }
        if saw_null {
            Value::Null
        } else {
            Value::Bool(false)
        }
    }

    fn predicate_function(
        &self,
        name: &str,
        variable: &str,
        list: &Expr,
        predicate: Option<&Expr>,
        row: &Row,
    ) -> CypherResult<Value> {
        let lv = self.eval(list, row)?;
        let items = match lv.as_list() {
            Some(l) => l.to_vec(),
            None => return Ok(Value::Null),
        };
        let mut scratch = row.clone();
        let mut results: Vec<Option<bool>> = Vec::with_capacity(items.len());
        for item in items {
            scratch.insert(variable.to_string(), item.clone());
            let v = match predicate {
                Some(p) => self.eval(p, &scratch)?,
                None => item,
            };
            results.push(v.as_bool());
        }
        let any_null = results.iter().any(|r| r.is_none());
        let true_count = results.iter().filter(|r| **r == Some(true)).count();
        let any_false = results.contains(&Some(false));
        Ok(match name.to_ascii_lowercase().as_str() {
            "any" => {
                if true_count > 0 {
                    Value::Bool(true)
                } else if any_null {
                    Value::Null
                } else {
                    Value::Bool(false)
                }
            }
            "all" => {
                if any_false {
                    Value::Bool(false)
                } else if any_null {
                    Value::Null
                } else {
                    Value::Bool(true)
                }
            }
            "none" => {
                if true_count > 0 {
                    Value::Bool(false)
                } else if any_null {
                    Value::Null
                } else {
                    Value::Bool(true)
                }
            }
            "single" => {
                if true_count > 1 {
                    Value::Bool(false)
                } else if any_null {
                    Value::Null
                } else {
                    Value::Bool(true_count == 1)
                }
            }
            other => {
                return Err(CypherError::Runtime(format!(
                    "Unknown predicate function: {other}"
                )))
            }
        })
    }

    fn subscript(&self, base: &Value, index: &Value) -> CypherResult<Value> {
        if base.is_null() || index.is_null() {
            return Ok(Value::Null);
        }
        let standard = self.ctx.dialect() == crate::CypherDialect::OpenCypherM23;
        let invalid = || {
            CypherError::execution(
                "TypeError",
                "InvalidArgumentType",
                "Indexing requires a list and integer index or a map and string key",
            )
        };
        Ok(match base {
            Value::List(items) => {
                let i = match index {
                    Value::Int(i) => *i,
                    Value::Float(f) if !standard => *f as i64,
                    _ if !standard => return Ok(Value::Null),
                    _ => return Err(invalid()),
                };
                let len = items.len() as i64;
                let idx = if i < 0 { len + i } else { i };
                if idx < 0 || idx >= len {
                    Value::Null
                } else {
                    items[idx as usize].clone()
                }
            }
            Value::Map(m) => match index.as_str() {
                Some(k) => m.get(k).cloned().unwrap_or(Value::Null),
                None if !standard => Value::Null,
                None => {
                    return Err(CypherError::execution(
                        "TypeError",
                        "MapElementAccessByNonString",
                        "Map indexes must be strings",
                    ))
                }
            },
            Value::Node(_) | Value::Rel(_) | Value::Method(_) => match index.as_str() {
                Some(k) => self.property(base, k),
                None if !standard => Value::Null,
                None => {
                    return Err(CypherError::execution(
                        "TypeError",
                        "MapElementAccessByNonString",
                        "Property indexes must be strings",
                    ))
                }
            },
            _ if !standard => Value::Null,
            _ => return Err(invalid()),
        })
    }

    fn slice(&self, base: &Value, from: Option<&Value>, to: Option<&Value>) -> Value {
        if from.is_some_and(Value::is_null) || to.is_some_and(Value::is_null) {
            return Value::Null;
        }
        let items = match base {
            Value::List(l) => l,
            Value::Str(s) => {
                let len = utf16_len(s) as i64;
                let (start, end) = slice_bounds(from, to, len);
                if start >= end {
                    return Value::str("");
                }
                return Value::str(crate::semantics::utf16_substring(
                    s,
                    start as usize,
                    end as usize,
                ));
            }
            _ => return Value::Null,
        };
        let len = items.len() as i64;
        let (start, end) = slice_bounds(from, to, len);
        if start >= end {
            return Value::list(vec![]);
        }
        Value::list(items[start as usize..end as usize].to_vec())
    }
}

fn slice_bounds(from: Option<&Value>, to: Option<&Value>, len: i64) -> (i64, i64) {
    let raw_start = from.and_then(|v| v.as_f64()).map(|f| f as i64).unwrap_or(0);
    let raw_end = to.and_then(|v| v.as_f64()).map(|f| f as i64).unwrap_or(len);
    let start = if raw_start < 0 {
        len + raw_start
    } else {
        raw_start
    }
    .clamp(0, len);
    let end = if raw_end < 0 { len + raw_end } else { raw_end }.clamp(0, len);
    (start, end)
}

fn literal_value(l: &Literal) -> Value {
    match l {
        Literal::Null => Value::Null,
        Literal::Bool(b) => Value::Bool(*b),
        Literal::Int(i) => Value::Int(*i),
        Literal::Float(f) => Value::Float(*f),
        Literal::Str(s) => Value::str(s.as_str()),
    }
}

fn class_cast(v: &Value, target: &str) -> CypherError {
    let actual = match v {
        Value::Bool(_) => "java.lang.Boolean",
        Value::Int(_) => "java.lang.Long",
        Value::Float(_) | Value::Float32(_) => "java.lang.Double",
        Value::Str(_) => "java.lang.String",
        Value::List(_) => "java.util.List",
        Value::Map(_) => "java.util.Map",
        Value::Temporal(_) => "Temporal",
        _ => "java.lang.Object",
    };
    CypherError::Other(format!("class {actual} cannot be cast to class {target}"))
}

#[allow(dead_code)]
fn _value_key_marker(v: &Value) -> crate::semantics::Key {
    value_key(v)
}
