//! REST-shaped node/edge maps, subgraph traversal and class overview.
//!
//! These shapes differ from the Cypher materialization: `/api/.../node/{id}` returns
//! `type`/`id`/`label` plus display-oriented fields, while Cypher returns raw properties.

use graphite_storage::graph::EdgeFamily;
use graphite_storage::node::*;
use graphite_storage::{AnyValue, Edge, Graph, Node, NodeId};
use serde_json::{json, Map, Number, Value as J};

pub const MAX_SUBGRAPH_NODES: usize = 2000;
pub const MAX_SUBGRAPH_EDGES: usize = 5000;
pub const MAX_SUBGRAPH_DEPTH: i64 = 4;
pub const DEFAULT_SUBGRAPH_DEPTH: i64 = 2;
pub const DEFAULT_EDGE_LIMIT: i64 = 200;
pub const MAX_EDGE_LIMIT: i64 = 2000;
pub const DEFAULT_OVERVIEW_LIMIT: i64 = 200;
pub const MAX_OVERVIEW_LIMIT: i64 = 1000;
pub const MAX_OVERVIEW_CALL_SITES: usize = 100_000;
pub const MAX_OVERVIEW_EDGES: usize = 50_000;
pub const MAX_OVERVIEW_CLASSES: usize = 20_000;

/// Kotlin `TypeDescriptor.simpleName` — everything after the last dot.
fn simple_name(fqcn: &str) -> &str {
    match fqcn.rfind('.') {
        Some(i) => &fqcn[i + 1..],
        None => fqcn,
    }
}

fn any_json(g: &Graph, v: &AnyValue) -> J {
    match v {
        AnyValue::Int(i) => J::Number(Number::from(*i as i64)),
        AnyValue::Long(l) => J::Number(Number::from(*l)),
        AnyValue::Str(s) => J::String(g.str(*s).to_string()),
        AnyValue::Float(f) => Number::from_f64(*f as f64)
            .map(J::Number)
            .unwrap_or(J::Null),
        AnyValue::Double(d) => Number::from_f64(*d).map(J::Number).unwrap_or(J::Null),
        AnyValue::Bool(b) => J::Bool(*b),
        AnyValue::Null => J::Null,
        AnyValue::EnumRef {
            enum_class,
            enum_name,
        } => J::String(format!("{}.{}", g.str(*enum_class), g.str(*enum_name))),
        AnyValue::List(items) => J::Array(items.iter().map(|i| any_json(g, i)).collect()),
    }
}

/// Gson drops null map values, so we mirror that by omitting them.
fn put(map: &mut Map<String, J>, key: &str, value: J) {
    if !value.is_null() {
        map.insert(key.to_string(), value);
    }
}

/// `nodeToMap(node)`.
pub fn node_to_map(g: &Graph, node: &Node) -> J {
    let mut m = Map::new();
    let id = J::Number(Number::from(node.id as i64));
    let s = |i: StrId| J::String(g.str(i).to_string());
    match &node.kind {
        NodeKind::CallSite { caller, callee, .. } => {
            m.insert("type".into(), json!("CallSiteNode"));
            m.insert("id".into(), id);
            m.insert("caller".into(), json!(caller.signature(&g.strings)));
            m.insert("callee".into(), json!(callee.signature(&g.strings)));
            m.insert(
                "label".into(),
                json!(format!(
                    "{}.{}",
                    simple_name(g.str(callee.declaring_class)),
                    g.str(callee.name)
                )),
            );
        }
        NodeKind::IntConstant(v) => {
            m.insert("type".into(), json!("IntConstant"));
            m.insert("id".into(), id);
            m.insert("value".into(), json!(v));
            m.insert("label".into(), json!(v.to_string()));
        }
        NodeKind::StringConstant(v) => {
            m.insert("type".into(), json!("StringConstant"));
            m.insert("id".into(), id);
            m.insert("value".into(), s(*v));
            m.insert("label".into(), json!(format!("\"{}\"", g.str(*v))));
        }
        NodeKind::EnumConstant {
            enum_type,
            enum_name,
            ..
        } => {
            m.insert("type".into(), json!("EnumConstant"));
            m.insert("id".into(), id);
            m.insert("enumType".into(), s(*enum_type));
            m.insert("enumName".into(), s(*enum_name));
            m.insert(
                "label".into(),
                json!(format!(
                    "{}.{}",
                    simple_name(g.str(*enum_type)),
                    g.str(*enum_name)
                )),
            );
        }
        NodeKind::LongConstant(v) => {
            m.insert("type".into(), json!("LongConstant"));
            m.insert("id".into(), id);
            m.insert("value".into(), json!(v));
            m.insert("label".into(), json!(format!("{v}L")));
        }
        NodeKind::FloatConstant(v) => {
            m.insert("type".into(), json!("FloatConstant"));
            m.insert("id".into(), id);
            m.insert(
                "value".into(),
                Number::from_f64(*v as f64)
                    .map(J::Number)
                    .unwrap_or(J::Null),
            );
            m.insert(
                "label".into(),
                json!(format!(
                    "{}f",
                    graphite_cypher::semantics::java_float_to_string(*v)
                )),
            );
        }
        NodeKind::DoubleConstant(v) => {
            m.insert("type".into(), json!("DoubleConstant"));
            m.insert("id".into(), id);
            m.insert(
                "value".into(),
                Number::from_f64(*v).map(J::Number).unwrap_or(J::Null),
            );
            m.insert(
                "label".into(),
                json!(format!(
                    "{}d",
                    graphite_cypher::semantics::java_double_to_string(*v)
                )),
            );
        }
        NodeKind::BooleanConstant(v) => {
            m.insert("type".into(), json!("BooleanConstant"));
            m.insert("id".into(), id);
            m.insert("value".into(), json!(v));
            m.insert("label".into(), json!(v.to_string()));
        }
        NodeKind::NullConstant => {
            m.insert("type".into(), json!("NullConstant"));
            m.insert("id".into(), id);
            m.insert("label".into(), json!("null"));
        }
        NodeKind::ResourceFile {
            path,
            source,
            format,
            profile,
        } => {
            m.insert("type".into(), json!("ResourceFileNode"));
            m.insert("id".into(), id);
            m.insert("path".into(), s(*path));
            m.insert("source".into(), s(*source));
            m.insert("format".into(), s(*format));
            put(&mut m, "profile", profile.map(s).unwrap_or(J::Null));
            m.insert("label".into(), s(*path));
        }
        NodeKind::ResourceValue {
            path,
            key,
            value,
            format,
            profile,
        } => {
            m.insert("type".into(), json!("ResourceValueNode"));
            m.insert("id".into(), id);
            m.insert("path".into(), s(*path));
            m.insert("key".into(), s(*key));
            put(&mut m, "value", any_json(g, value));
            m.insert("format".into(), s(*format));
            put(&mut m, "profile", profile.map(s).unwrap_or(J::Null));
            m.insert(
                "label".into(),
                json!(format!(
                    "{}={}",
                    g.str(*key),
                    graphite_cypher::engine::props::any_to_kotlin_string(g, value)
                )),
            );
        }
        NodeKind::Field {
            declaring_class,
            name,
            field_type,
            ..
        } => {
            m.insert("type".into(), json!("FieldNode"));
            m.insert("id".into(), id);
            m.insert("class".into(), s(*declaring_class));
            m.insert("name".into(), s(*name));
            m.insert("fieldType".into(), s(*field_type));
            m.insert(
                "label".into(),
                json!(format!(
                    "{}.{}",
                    simple_name(g.str(*declaring_class)),
                    g.str(*name)
                )),
            );
        }
        NodeKind::Parameter {
            index,
            param_type,
            method,
        } => {
            m.insert("type".into(), json!("ParameterNode"));
            m.insert("id".into(), id);
            m.insert("index".into(), json!(index));
            m.insert("paramType".into(), s(*param_type));
            m.insert("method".into(), json!(method.signature(&g.strings)));
            m.insert("label".into(), json!(format!("param#{index}")));
        }
        NodeKind::Return { method, .. } => {
            m.insert("type".into(), json!("ReturnNode"));
            m.insert("id".into(), id);
            m.insert("method".into(), json!(method.signature(&g.strings)));
            m.insert("label".into(), json!("return"));
        }
        NodeKind::LocalVariable {
            name,
            var_type,
            method,
        } => {
            m.insert("type".into(), json!("LocalVariable"));
            m.insert("id".into(), id);
            m.insert("name".into(), s(*name));
            m.insert("varType".into(), s(*var_type));
            m.insert("method".into(), json!(method.signature(&g.strings)));
            m.insert("label".into(), s(*name));
        }
        NodeKind::Annotation {
            name,
            class_name,
            member_name,
            values,
        } => {
            m.insert("type".into(), json!("AnnotationNode"));
            m.insert("id".into(), id);
            m.insert("name".into(), s(*name));
            m.insert("class".into(), s(*class_name));
            m.insert("member".into(), s(*member_name));
            m.insert(
                "label".into(),
                json!(format!("@{}", simple_name(g.str(*name)))),
            );
            // Annotation attributes are merged in and may overwrite the keys above.
            for (k, v) in values {
                put(&mut m, g.str(*k), any_json(g, v));
            }
        }
    }
    J::Object(m)
}

/// `edgeToMap(edge)`.
pub fn edge_to_map(e: &Edge) -> J {
    let mut m = Map::new();
    m.insert("from".into(), J::Number(Number::from(e.from as i64)));
    m.insert("to".into(), J::Number(Number::from(e.to as i64)));
    m.insert("type".into(), J::String(e.rest_type().to_string()));
    match e.family() {
        EdgeFamily::Call => {
            m.insert("virtual".into(), J::Bool(e.is_virtual()));
            m.insert("dynamic".into(), J::Bool(e.is_dynamic()));
        }
        _ => {
            if let Some(k) = e.kind_name() {
                m.insert("kind".into(), J::String(k.to_string()));
            }
        }
    }
    J::Object(m)
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Direction {
    Both,
    Outgoing,
    Incoming,
}

impl Direction {
    pub fn parse(raw: Option<&str>) -> Option<Direction> {
        match raw.map(|s| s.to_ascii_lowercase()).as_deref() {
            None | Some("both") => Some(Direction::Both),
            Some("outgoing") => Some(Direction::Outgoing),
            Some("incoming") => Some(Direction::Incoming),
            _ => None,
        }
    }
    fn outgoing(self) -> bool {
        self != Direction::Incoming
    }
    fn incoming(self) -> bool {
        self != Direction::Outgoing
    }
}

/// Depth-first subgraph expansion. Edges are recorded even when the target is already
/// visited or the node cap has been reached, matching the Kotlin traversal.
pub fn build_subgraph(g: &Graph, center: NodeId, depth: i64, direction: Direction) -> J {
    let mut nodes: Vec<J> = Vec::new();
    let mut edges: Vec<J> = Vec::new();
    let mut visited: std::collections::HashSet<NodeId> = std::collections::HashSet::new();
    visit(
        g,
        center,
        depth,
        direction,
        &mut nodes,
        &mut edges,
        &mut visited,
    );
    json!({ "nodes": nodes, "edges": edges })
}

fn visit(
    g: &Graph,
    id: NodeId,
    remaining: i64,
    direction: Direction,
    nodes: &mut Vec<J>,
    edges: &mut Vec<J>,
    visited: &mut std::collections::HashSet<NodeId>,
) {
    if nodes.len() >= MAX_SUBGRAPH_NODES || !visited.insert(id) || remaining < 0 {
        return;
    }
    let node = match g.node(id) {
        Some(n) => n,
        None => return,
    };
    nodes.push(node_to_map(g, &node));
    if remaining <= 0 {
        return;
    }
    if direction.outgoing() {
        for e in g.outgoing(id) {
            if edges.len() >= MAX_SUBGRAPH_EDGES {
                break;
            }
            edges.push(edge_to_map(&e));
            visit(g, e.to, remaining - 1, direction, nodes, edges, visited);
        }
    }
    if direction.incoming() {
        for e in g.incoming(id) {
            if edges.len() >= MAX_SUBGRAPH_EDGES {
                break;
            }
            edges.push(edge_to_map(&e));
            visit(g, e.from, remaining - 1, direction, nodes, edges, visited);
        }
    }
}

/// `/api/overview` — class nodes sized by call-site count, edges by call weight.
pub fn build_class_overview(g: &Graph, limit: i64) -> J {
    let limit = limit.max(0) as usize;
    match g.class_overview.as_ref() {
        Some(o) => {
            let classes: Vec<(String, i32)> = o
                .classes
                .iter()
                .take(limit)
                .map(|(s, c)| (g.str(*s).to_string(), *c))
                .collect();
            let top: std::collections::HashSet<&str> =
                classes.iter().map(|(c, _)| c.as_str()).collect();
            let edges: Vec<J> = o
                .edges
                .iter()
                .filter_map(|(from, to, count)| {
                    let f = g.str(*from);
                    let t = g.str(*to);
                    if top.contains(f) && top.contains(t) {
                        Some(json!({"from": f, "to": t, "type": "Call", "weight": count}))
                    } else {
                        None
                    }
                })
                .collect();
            overview_json(classes, edges)
        }
        None => build_class_overview_from_call_sites(g, limit),
    }
}

fn overview_json(classes: Vec<(String, i32)>, edges: Vec<J>) -> J {
    let nodes: Vec<J> = classes
        .iter()
        .map(|(c, count)| {
            json!({
                "id": c,
                "type": "Class",
                "label": simple_name(c),
                "fullName": c,
                "callSites": count,
            })
        })
        .collect();
    json!({ "nodes": nodes, "edges": edges })
}

fn build_class_overview_from_call_sites(g: &Graph, limit: usize) -> J {
    use std::collections::HashMap;
    let mut class_counts: HashMap<String, i32> = HashMap::new();
    let mut edge_counts: HashMap<(String, String), i32> = HashMap::new();
    for &id in g
        .ids_by_tag(TAG_CALL_SITE_NODE)
        .iter()
        .take(MAX_OVERVIEW_CALL_SITES)
    {
        let s = match g.call_site_strings(id) {
            Some(s) => s,
            None => continue,
        };
        let caller = g.str(s.caller_class).to_string();
        let callee = g.str(s.callee_class).to_string();
        increment_bounded(&mut class_counts, caller.clone(), MAX_OVERVIEW_CLASSES);
        increment_bounded(&mut class_counts, callee.clone(), MAX_OVERVIEW_CLASSES);
        if caller != callee {
            increment_bounded(&mut edge_counts, (caller, callee), MAX_OVERVIEW_EDGES);
        }
    }
    let mut classes: Vec<(String, i32)> = class_counts.into_iter().collect();
    classes.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    classes.truncate(limit);
    let top: std::collections::HashSet<String> = classes.iter().map(|(c, _)| c.clone()).collect();
    let mut edges: Vec<((String, String), i32)> = edge_counts
        .into_iter()
        .filter(|((f, t), _)| top.contains(f) && top.contains(t))
        .collect();
    edges.sort_by(|a, b| a.0 .0.cmp(&b.0 .0).then(a.0 .1.cmp(&b.0 .1)));
    let edges: Vec<J> = edges
        .into_iter()
        .map(|((f, t), c)| json!({"from": f, "to": t, "type": "Call", "weight": c}))
        .collect();
    overview_json(classes, edges)
}

/// Only add a new key while under the cap; existing keys always increment.
fn increment_bounded<K: std::hash::Hash + Eq>(
    counts: &mut std::collections::HashMap<K, i32>,
    key: K,
    max_keys: usize,
) {
    match counts.get_mut(&key) {
        Some(v) => *v += 1,
        None => {
            if counts.len() < max_keys {
                counts.insert(key, 1);
            }
        }
    }
}

/// Split a total limit across graphs: the first `total % n` graphs get one extra.
pub fn distributed_limits(graph_count: usize, total: i64) -> Vec<i64> {
    if graph_count == 0 {
        return Vec::new();
    }
    let base = total / graph_count as i64;
    let extra = total % graph_count as i64;
    (0..graph_count)
        .map(|i| base + if (i as i64) < extra { 1 } else { 0 })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn simple_name_falls_back_to_whole_string() {
        assert_eq!(simple_name("com.example.Foo"), "Foo");
        assert_eq!(simple_name("Foo"), "Foo");
    }

    #[test]
    fn limits_are_distributed_with_remainder_first() {
        assert_eq!(distributed_limits(3, 10), vec![4, 3, 3]);
        assert_eq!(distributed_limits(2, 10), vec![5, 5]);
        assert_eq!(distributed_limits(0, 10), Vec::<i64>::new());
    }

    #[test]
    fn direction_parsing_is_case_insensitive() {
        assert!(Direction::parse(None).is_some());
        assert!(Direction::parse(Some("OUTGOING")).is_some());
        assert!(Direction::parse(Some("sideways")).is_none());
    }
}
