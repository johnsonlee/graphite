//! `Any?.toString()` rendering, for the CLI's text-table and CSV output.
//!
//! `graphite query` prints values two different ways. `--format json` goes through Gson,
//! which omits nulls and HTML-escapes; `--format text` and `--format csv` call
//! `toString()` on whatever object the executor produced. The two disagree on real
//! output — a node prints `line=null` in the table and has no `line` key in the JSON —
//! so this is a separate renderer rather than a reformat of the JSON.
//!
//! What is being reproduced is Java's own `toString()` for the types the executor yields:
//! `LinkedHashMap` renders as `{k=v, k2=v2}`, `List` as `[a, b]`, a null *value* inside
//! either as the literal `null`, and numbers through `Long.toString` / `Double.toString`.

use crate::context::GraphContext;
use crate::semantics::{java_double_to_string, java_float_to_string};
use crate::value::Value;

/// Render one value the way Kotlin's `toString()` would.
pub fn java_to_string(v: &Value, ctx: &dyn GraphContext) -> String {
    match v {
        Value::Null => "null".to_string(),
        Value::Bool(b) => b.to_string(),
        Value::Int(i) => i.to_string(),
        Value::Float(f) => java_double_to_string(*f),
        Value::Float32(f) => java_float_to_string(*f),
        // A bare string renders as itself: no quoting, no escaping. CSV quoting is the
        // caller's job, and it applies only to strings, which is why it must stay here.
        Value::Str(s) => s.to_string(),
        Value::List(items) => {
            let parts: Vec<String> = items.iter().map(|i| java_to_string(i, ctx)).collect();
            format!("[{}]", parts.join(", "))
        }
        Value::Map(m) => {
            let entries: Vec<String> = m
                .iter()
                .map(|(k, val)| format!("{k}={}", java_to_string(val, ctx)))
                .collect();
            format!("{{{}}}", entries.join(", "))
        }
        Value::Node(n) => {
            let mut entries = vec![
                format!("id={}", n.id),
                format!("type={}", ctx.node_type_name(*n)),
            ];
            for (k, val) in ctx.node_display_properties(*n) {
                if k == "id" {
                    continue;
                }
                entries.push(format!("{k}={}", java_to_string(&val, ctx)));
            }
            format!("{{{}}}", entries.join(", "))
        }
        Value::Rel(r) => {
            let e = edge_fields(*r, ctx);
            let body: Vec<String> = e
                .fields
                .iter()
                .map(|(k, v)| format!("{k}={}", json_to_string(v)))
                .collect();
            // `NodeId.toString()` is `node#N`, so the endpoints do not print as bare
            // integers the way they serialise.
            let mut parts = vec![format!("from=node#{}", e.from), format!("to=node#{}", e.to)];
            parts.extend(body);
            format!("{}({})", e.class, parts.join(", "))
        }
        // A path is a Kotlin `List` of alternating node maps and edge objects, so it
        // renders as one — not as the `{nodes, edges}` object the HTTP API returns.
        Value::Path(p) => {
            let parts: Vec<String> = path_elements(p, ctx)
                .iter()
                .map(|v| java_to_string(v, ctx))
                .collect();
            format!("[{}]", parts.join(", "))
        }
        other => json_to_string(&crate::materialize::materialize(other, ctx)),
    }
}

/// The Kotlin object a relationship *is*, before anything maps it.
///
/// `graphite query` hands the executor's own value to Gson and to `toString()`, so a
/// relationship shows up as the data class — `DataFlowEdge(from=node#1, to=node#2,
/// kind=ASSIGN)`, serialising to its declared fields. The Explorer's HTTP API instead
/// maps relationships to a `{from, to, type, kind}` shape, which is why this is a
/// separate rendering rather than a reuse of `materialize`.
pub struct EdgeFields {
    pub class: &'static str,
    pub from: u32,
    pub to: u32,
    /// Declared fields after `from` and `to`, in declaration order.
    pub fields: Vec<(&'static str, serde_json::Value)>,
}

pub fn edge_fields(r: crate::value::EdgeRef, ctx: &dyn GraphContext) -> EdgeFields {
    use graphite_storage::EdgeFamily;
    use serde_json::Value as J;
    let kind = || match r.edge.kind_name() {
        Some(k) => J::String(k.to_string()),
        None => J::Null,
    };

    let (class, fields): (&'static str, Vec<(&'static str, J)>) = match r.edge.family() {
        EdgeFamily::DataFlow => ("DataFlowEdge", vec![("kind", kind())]),
        EdgeFamily::Resource => ("ResourceEdge", vec![("kind", kind())]),
        EdgeFamily::Type => ("TypeEdge", vec![("kind", kind())]),
        EdgeFamily::Call => (
            "CallEdge",
            vec![
                ("isVirtual", J::Bool(r.edge.is_virtual())),
                ("isDynamic", J::Bool(r.edge.is_dynamic())),
            ],
        ),
        EdgeFamily::ControlFlow => {
            let comparison = ctx.edge_comparison(r).map(|c| {
                let mut m = serde_json::Map::new();
                m.insert("operator".into(), J::String(c.0));
                m.insert("comparandNodeId".into(), J::Number(c.1.into()));
                J::Object(m)
            });
            (
                "ControlFlowEdge",
                vec![
                    ("kind", kind()),
                    ("comparison", comparison.unwrap_or(J::Null)),
                ],
            )
        }
    };
    EdgeFields {
        class,
        from: r.edge.from,
        to: r.edge.to,
        fields,
    }
}

/// The CLI's JSON view of a value: Gson reflecting over the executor's own objects.
pub fn raw_json(v: &Value, ctx: &dyn GraphContext) -> serde_json::Value {
    use serde_json::Value as J;
    match v {
        Value::Rel(r) => {
            let e = edge_fields(*r, ctx);
            let mut m = serde_json::Map::new();
            m.insert("from".into(), J::Number(e.from.into()));
            m.insert("to".into(), J::Number(e.to.into()));
            for (k, val) in e.fields {
                m.insert(k.to_string(), val);
            }
            J::Object(m)
        }
        Value::List(items) => J::Array(items.iter().map(|i| raw_json(i, ctx)).collect()),
        Value::Path(p) => J::Array(
            path_elements(p, ctx)
                .iter()
                .map(|e| raw_json(e, ctx))
                .collect(),
        ),
        other => crate::materialize::materialize(other, ctx),
    }
}

/// `toString()` of a value that only exists here as JSON — a map or list of primitives.
fn json_to_string(j: &serde_json::Value) -> String {
    match j {
        serde_json::Value::Null => "null".to_string(),
        serde_json::Value::Bool(b) => b.to_string(),
        serde_json::Value::Number(n) => n.to_string(),
        serde_json::Value::String(s) => s.clone(),
        serde_json::Value::Array(items) => {
            let parts: Vec<String> = items.iter().map(json_to_string).collect();
            format!("[{}]", parts.join(", "))
        }
        serde_json::Value::Object(m) => {
            let entries: Vec<String> = m
                .iter()
                .map(|(k, v)| format!("{k}={}", json_to_string(v)))
                .collect();
            format!("{{{}}}", entries.join(", "))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::json_to_string;
    use serde_json::json;

    #[test]
    fn renders_java_collection_syntax() {
        assert_eq!(json_to_string(&json!({"a": 1, "b": null})), "{a=1, b=null}");
        assert_eq!(json_to_string(&json!([1, "x", true])), "[1, x, true]");
        // Nesting composes, and a string never gains quotes.
        assert_eq!(json_to_string(&json!({"k": ["a"]})), "{k=[a]}");
        assert_eq!(json_to_string(&json!("plain")), "plain");
    }
}

/// A path flattened to the alternating node/edge sequence the baseline holds it as.
fn path_elements(p: &crate::value::PathValue, _ctx: &dyn GraphContext) -> Vec<Value> {
    let mut out = Vec::with_capacity(p.nodes.len() + p.edges.len());
    for (i, id) in p.nodes.iter().enumerate() {
        out.push(Value::Node(crate::value::NodeRef {
            source: p.source,
            id: *id,
        }));
        if let Some(e) = p.edges.get(i) {
            out.push(Value::Rel(crate::value::EdgeRef {
                source: p.source,
                edge: *e,
            }));
        }
    }
    out
}
