//! Convert engine values into the JSON shapes the Explorer API returns.

use crate::context::GraphContext;
use crate::semantics::{java_double_to_string, java_float_to_string};
use crate::value::Value;
use serde_json::{Map, Number, Value as J};

/// `CypherExecutor.materializeValue`.
pub fn materialize(v: &Value, ctx: &dyn GraphContext) -> J {
    match v {
        Value::Null => J::Null,
        Value::Bool(b) => J::Bool(*b),
        Value::Int(i) => J::Number(Number::from(*i)),
        Value::Float(f) => number(*f),
        Value::Float32(f) => {
            // Kotlin Float serialises through its own toString, so 1.1f is 1.1, not 1.10000002.
            java_float_to_string(*f)
                .parse::<f64>()
                .ok()
                .map(number)
                .unwrap_or(J::Null)
        }
        Value::Str(s) => J::String(s.to_string()),
        Value::List(items) => J::Array(items.iter().map(|i| materialize(i, ctx)).collect()),
        Value::Map(m) => {
            let mut out = Map::new();
            for (k, val) in m.iter() {
                out.insert(k.clone(), materialize(val, ctx));
            }
            J::Object(out)
        }
        Value::Node(n) => {
            let mut out = Map::new();
            out.insert("id".into(), J::Number(Number::from(n.id as i64)));
            out.insert("type".into(), J::String(ctx.node_type_name(*n).to_string()));
            for (k, val) in ctx.node_result_properties(*n) {
                if k == "id" {
                    continue;
                }
                // `type` is deliberately overwritten for LocalVariable/FieldNode/ParameterNode.
                out.insert(k, materialize(&val, ctx));
            }
            J::Object(out)
        }
        Value::Rel(r) => {
            let mut out = Map::new();
            if ctx.is_cross_graph() {
                let gid = ctx.graph_id(r.source).to_string();
                out.insert("graphId".into(), J::String(gid.clone()));
                out.insert("from".into(), J::Number(Number::from(r.edge.from as i64)));
                out.insert("to".into(), J::Number(Number::from(r.edge.to as i64)));
                out.insert(
                    "fromElementId".into(),
                    J::String(format!("{gid}:{}", r.edge.from)),
                );
                out.insert(
                    "toElementId".into(),
                    J::String(format!("{gid}:{}", r.edge.to)),
                );
            } else {
                out.insert("from".into(), J::Number(Number::from(r.edge.from as i64)));
                out.insert("to".into(), J::Number(Number::from(r.edge.to as i64)));
            }
            out.insert("type".into(), J::String(r.edge.rel_type().to_string()));
            match r.edge.kind_name() {
                Some(k) => {
                    out.insert("kind".into(), J::String(k.to_string()));
                }
                None => {
                    out.insert("virtual".into(), J::Bool(r.edge.is_virtual()));
                    out.insert("dynamic".into(), J::Bool(r.edge.is_dynamic()));
                }
            }
            J::Object(out)
        }
        Value::Path(p) => {
            let nodes: Vec<J> = p
                .nodes
                .iter()
                .map(|id| {
                    materialize(
                        &Value::Node(crate::value::NodeRef {
                            source: p.source,
                            id: *id,
                        }),
                        ctx,
                    )
                })
                .collect();
            let rels: Vec<J> = p
                .edges
                .iter()
                .map(|e| {
                    materialize(
                        &Value::Rel(crate::value::EdgeRef {
                            source: p.source,
                            edge: *e,
                        }),
                        ctx,
                    )
                })
                .collect();
            if ctx.is_cross_graph() {
                let mut out = Map::new();
                out.insert(
                    "graphId".into(),
                    J::String(ctx.graph_id(p.source).to_string()),
                );
                out.insert("length".into(), J::Number(Number::from(p.edges.len() as i64)));
                out.insert("nodes".into(), J::Array(nodes));
                out.insert("relationships".into(), J::Array(rels));
                J::Object(out)
            } else {
                // Single-graph paths materialise as a flat interleaved list.
                let mut flat = Vec::with_capacity(nodes.len() + rels.len());
                for (i, n) in nodes.into_iter().enumerate() {
                    if i > 0 {
                        flat.push(rels[i - 1].clone());
                    }
                    flat.push(n);
                }
                J::Array(flat)
            }
        }
        Value::Method(m) => {
            let mut out = Map::new();
            for (k, val) in ctx.method_properties(*m) {
                out.insert(k, materialize(&val, ctx));
            }
            J::Object(out)
        }
    }
}

/// Render a double the way Gson does: integral values keep a `.0`.
fn number(f: f64) -> J {
    if !f.is_finite() {
        return J::String(java_double_to_string(f));
    }
    match Number::from_f64(f) {
        Some(n) => J::Number(n),
        None => J::Null,
    }
}
