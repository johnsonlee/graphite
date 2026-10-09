//! Node/relationship/method property access — `NodePropertyAccessor` parity.

use crate::value::{EdgeRef, MethodRef, NodeRef, Value};
use graphite_storage::graph::EdgeFamily;
use graphite_storage::node::*;
use graphite_storage::{AnyValue, Graph, MethodDesc};
use indexmap::IndexMap;
use std::sync::Arc;

pub fn any_to_value(g: &Graph, v: &AnyValue) -> Value {
    match v {
        AnyValue::Int(i) => Value::Int(*i as i64),
        AnyValue::Long(l) => Value::Int(*l),
        AnyValue::Str(s) => Value::str(g.str(*s)),
        AnyValue::Float(f) => Value::Float32(*f),
        AnyValue::Double(d) => Value::Float(*d),
        AnyValue::Bool(b) => Value::Bool(*b),
        AnyValue::Null => Value::Null,
        AnyValue::EnumRef {
            enum_class,
            enum_name,
        } => Value::str(format!("{}.{}", g.str(*enum_class), g.str(*enum_name))),
        AnyValue::List(items) => Value::list(items.iter().map(|i| any_to_value(g, i)).collect()),
    }
}

/// Kotlin `EnumValueReference.toString()` — a data class: `EnumValueReference(enumClass=..., enumName=...)`.
pub fn any_to_kotlin_string(g: &Graph, v: &AnyValue) -> String {
    match v {
        AnyValue::Int(i) => i.to_string(),
        AnyValue::Long(l) => l.to_string(),
        AnyValue::Str(s) => g.str(*s).to_string(),
        AnyValue::Float(f) => crate::semantics::java_double_to_string(*f as f64),
        AnyValue::Double(d) => crate::semantics::java_double_to_string(*d),
        AnyValue::Bool(b) => b.to_string(),
        AnyValue::Null => "null".to_string(),
        AnyValue::EnumRef {
            enum_class,
            enum_name,
        } => format!(
            "EnumValueReference(enumClass={}, enumName={})",
            g.str(*enum_class),
            g.str(*enum_name)
        ),
        AnyValue::List(items) => {
            let parts: Vec<String> = items.iter().map(|i| any_to_kotlin_string(g, i)).collect();
            format!("[{}]", parts.join(", "))
        }
    }
}

fn sig(g: &Graph, m: &MethodDesc) -> Value {
    Value::str(m.signature(g.strings()))
}

fn s(g: &Graph, id: StrId) -> Value {
    Value::str(g.str(id))
}

fn type_info(table: &graphite_storage::types::DeclaredTypes, id: usize) -> Value {
    let t = table.type_expr(id);
    let mut map = IndexMap::new();
    map.insert("kind".into(), Value::str(t.kind));
    for (key, value) in [("name", &t.name), ("scope", &t.scope)] {
        if !value.is_empty() {
            map.insert(key.into(), Value::str(*value));
        }
    }
    if let Some(owner) = t.owner {
        map.insert("owner".into(), type_info(table, owner));
    }
    if let Some(component) = t.component {
        map.insert("component".into(), type_info(table, component));
    }
    if !t.variance.is_empty() {
        map.insert("variance".into(), Value::str(t.variance));
    }
    map.insert(
        "arguments".into(),
        Value::list(t.arguments.iter().map(|id| type_info(table, *id)).collect()),
    );
    Value::map(map)
}

fn declared_method<'a>(
    g: &'a Graph,
    m: &MethodDesc,
) -> Option<(
    &'a graphite_storage::types::DeclaredTypes,
    graphite_storage::types::MethodView<'a>,
)> {
    let table = g.declared_types()?;
    Some((table, table.method(m, g.strings())?))
}

pub fn has_declared_types_for_tag(g: &Graph, tag: u8) -> bool {
    g.declared_types().is_some_and(|table| match tag {
        TAG_FIELD_NODE => table.field_count() != 0,
        TAG_PARAMETER_NODE | TAG_RETURN_NODE => table.method_count() != 0,
        _ => false,
    })
}

fn declared_node_type(g: &Graph, node: &Node) -> Option<usize> {
    g.declared_types()?.node_type_id(node, g.strings())
}

pub(super) fn has_declared_node_type(g: &Graph, node: &Node) -> bool {
    declared_node_type(g, node).is_some()
}

/// Declaration-bearing nodes have two possible key sets. Reading those sets
/// requires member binding, never rendering or expanding the bound type tree.
pub fn node_keys(g: &Graph, node: &Node) -> Vec<String> {
    let fixed: &[&str] = match &node.kind {
        NodeKind::Field {
            declaring_class,
            name,
            field_type,
            ..
        } => {
            for id in [name, field_type, declaring_class] {
                let _ = g.str(*id);
            }
            &["id", "name", "type", "class", "static"]
        }
        NodeKind::Parameter {
            method, param_type, ..
        } => {
            let _ = g.str(*param_type);
            check_method_signature_strings(g, method);
            &["id", "index", "type", "method"]
        }
        NodeKind::Return {
            method,
            actual_type,
        } => {
            check_method_signature_strings(g, method);
            if let Some(id) = actual_type {
                let _ = g.str(*id);
            }
            &["id", "method", "actual_type"]
        }
        _ => return node_properties(g, node).into_keys().collect(),
    };
    let mut keys: Vec<String> = fixed.iter().map(|key| (*key).to_owned()).collect();
    if has_declared_node_type(g, node) {
        keys.extend(["generic_type", "type_info"].map(str::to_owned));
    }
    keys
}

fn check_method_signature_strings(g: &Graph, method: &MethodDesc) {
    let _ = g.str(method.declaring_class);
    let _ = g.str(method.name);
    for parameter in &method.parameter_types {
        let _ = g.str(*parameter);
    }
}

fn declared_node_property(g: &Graph, node: &Node, key: &str) -> Value {
    declared_node_type(g, node)
        .map(|id| {
            let table = g.declared_types().unwrap();
            if key == "generic_type" {
                Value::str(table.render(id))
            } else {
                type_info(table, id)
            }
        })
        .unwrap_or(Value::Null)
}

fn generic_method_property(g: &Graph, m: &MethodDesc, key: &str) -> Value {
    let Some((table, types)) = declared_method(g, m) else {
        return Value::Null;
    };
    match key {
        "generic_return_type" => Value::str(table.render(types.returns)),
        "generic_parameter_types" => Value::list(
            types
                .parameters
                .iter()
                .map(|id| Value::str(table.render(*id)))
                .collect(),
        ),
        "return_type_info" => type_info(table, types.returns),
        "parameter_type_info" => Value::list(
            types
                .parameters
                .iter()
                .map(|id| type_info(table, *id))
                .collect(),
        ),
        "type_parameters" => Value::list(
            types
                .type_parameters
                .iter()
                .map(|p| {
                    Value::map(IndexMap::from([
                        ("name".into(), Value::str(p.name)),
                        ("scope".into(), Value::str(p.scope)),
                        (
                            "bounds".into(),
                            Value::list(
                                p.bounds
                                    .iter()
                                    .map(|id| Value::str(table.render(*id)))
                                    .collect(),
                            ),
                        ),
                        (
                            "bound_info".into(),
                            Value::list(p.bounds.iter().map(|id| type_info(table, *id)).collect()),
                        ),
                    ]))
                })
                .collect(),
        ),
        _ => Value::Null,
    }
}

pub const GENERIC_METHOD_KEYS: [&str; 5] = [
    "generic_return_type",
    "generic_parameter_types",
    "return_type_info",
    "parameter_type_info",
    "type_parameters",
];

/// `getProperty(node, key)`.
pub fn node_property(g: &Graph, node: &Node, key: &str) -> Value {
    if key == "id" {
        return Value::Int(node.id as i64);
    }
    let v = match &node.kind {
        NodeKind::CallSite {
            caller,
            callee,
            line,
            ordinal,
            ..
        } => match key {
            "callee_class" => s(g, callee.declaring_class),
            "callee_name" => s(g, callee.name),
            "callee_signature" => sig(g, callee),
            "callee_descriptor" => Value::str(callee.descriptor(g.strings())),
            "caller_class" => s(g, caller.declaring_class),
            "caller_name" => s(g, caller.name),
            "caller_signature" => sig(g, caller),
            "caller_descriptor" => Value::str(caller.descriptor(g.strings())),
            "line" => line.map(|l| Value::Int(l as i64)).unwrap_or(Value::Null),
            "ordinal" => ordinal.map(|o| Value::Int(o as i64)).unwrap_or(Value::Null),
            _ => Value::Null,
        },
        NodeKind::IntConstant(v) => match key {
            "value" => Value::Int(*v as i64),
            _ => Value::Null,
        },
        NodeKind::StringConstant(v) => match key {
            "value" => s(g, *v),
            _ => Value::Null,
        },
        NodeKind::LongConstant(v) => match key {
            "value" => Value::Int(*v),
            _ => Value::Null,
        },
        NodeKind::FloatConstant(v) => match key {
            "value" => Value::Float32(*v),
            _ => Value::Null,
        },
        NodeKind::DoubleConstant(v) => match key {
            "value" => Value::Float(*v),
            _ => Value::Null,
        },
        NodeKind::BooleanConstant(v) => match key {
            "value" => Value::Bool(*v),
            _ => Value::Null,
        },
        NodeKind::NullConstant => Value::Null,
        NodeKind::EnumConstant {
            enum_type,
            enum_name,
            args,
        } => match key {
            "value" => args
                .first()
                .map(|a| any_to_value(g, a))
                .unwrap_or(Value::Null),
            "name" => s(g, *enum_name),
            "enum_type" => s(g, *enum_type),
            _ => Value::Null,
        },
        NodeKind::LocalVariable {
            name,
            var_type,
            method,
        } => match key {
            "name" => s(g, *name),
            "type" => s(g, *var_type),
            "method" => sig(g, method),
            _ => Value::Null,
        },
        NodeKind::Field {
            declaring_class,
            name,
            field_type,
            is_static,
        } => match key {
            "name" => s(g, *name),
            "type" => s(g, *field_type),
            "class" => s(g, *declaring_class),
            "static" => Value::Bool(*is_static),
            "generic_type" | "type_info" => declared_node_property(g, node, key),
            _ => Value::Null,
        },
        NodeKind::Parameter {
            index,
            param_type,
            method,
        } => match key {
            "index" => Value::Int(*index as i64),
            "type" => s(g, *param_type),
            "method" => sig(g, method),
            "generic_type" | "type_info" => declared_node_property(g, node, key),
            _ => Value::Null,
        },
        NodeKind::Return {
            method,
            actual_type,
        } => match key {
            "method" => sig(g, method),
            "actual_type" => actual_type.map(|t| s(g, t)).unwrap_or(Value::Null),
            "generic_type" | "type_info" => declared_node_property(g, node, key),
            _ => Value::Null,
        },
        NodeKind::ResourceFile {
            path,
            source,
            format,
            profile,
        } => match key {
            "path" => s(g, *path),
            "source" => s(g, *source),
            "format" => s(g, *format),
            "profile" => profile.map(|p| s(g, p)).unwrap_or(Value::Null),
            _ => Value::Null,
        },
        NodeKind::ResourceValue {
            path,
            key: k,
            value,
            format,
            profile,
        } => match key {
            "path" => s(g, *path),
            "key" => s(g, *k),
            "value" => any_to_value(g, value),
            "format" => s(g, *format),
            "profile" => profile.map(|p| s(g, p)).unwrap_or(Value::Null),
            _ => Value::Null,
        },
        NodeKind::Annotation {
            name,
            class_name,
            member_name,
            values,
        } => match key {
            "name" => s(g, *name),
            "class" => s(g, *class_name),
            "member" => s(g, *member_name),
            "values" => Value::str(annotation_values_to_string(g, values)),
            _ => values
                .iter()
                .find(|(k, _)| g.str(*k) == key)
                .map(|(_, v)| any_to_value(g, v))
                .unwrap_or(Value::Null),
        },
    };
    if !v.is_null() {
        return v;
    }
    if key == "type" {
        return Value::str(node.type_name());
    }
    Value::Null
}

/// Kotlin `Map.toString()` of the annotation values: `{k=v, k2=v2}`.
pub fn annotation_values_to_string(g: &Graph, values: &[(StrId, AnyValue)]) -> String {
    let mut out = String::from("{");
    for (i, (k, v)) in values.iter().enumerate() {
        if i > 0 {
            out.push_str(", ");
        }
        out.push_str(g.str(*k));
        out.push('=');
        out.push_str(&any_to_kotlin_string(g, v));
    }
    out.push('}');
    out
}

/// `getAllProperties(node)` — fixed map including `id`, excluding `type`.
pub fn node_properties(g: &Graph, node: &Node) -> IndexMap<String, Value> {
    node_properties_impl(g, node, true)
}

fn check_method_detail_strings(g: &Graph, method: &MethodDesc) {
    // Formatting the discarded signature/descriptor used to read these IDs.
    // Preserve their bounds failures, in the same order, without building strings.
    for &parameter in &method.parameter_types {
        let _ = g.str(parameter);
    }
    let _ = g.str(method.return_type);
}

fn node_properties_impl(
    g: &Graph,
    node: &Node,
    include_call_site_method_details: bool,
) -> IndexMap<String, Value> {
    let mut m = IndexMap::new();
    m.insert("id".to_string(), Value::Int(node.id as i64));
    let mut put = |k: &str, v: Value| {
        m.insert(k.to_string(), v);
    };
    match &node.kind {
        NodeKind::CallSite {
            caller,
            callee,
            line,
            ordinal,
            ..
        } => {
            put("callee_class", s(g, callee.declaring_class));
            put("callee_name", s(g, callee.name));
            if include_call_site_method_details {
                put("callee_signature", sig(g, callee));
                put(
                    "callee_descriptor",
                    Value::str(callee.descriptor(g.strings())),
                );
            } else {
                check_method_detail_strings(g, callee);
            }
            put("caller_class", s(g, caller.declaring_class));
            put("caller_name", s(g, caller.name));
            if include_call_site_method_details {
                put("caller_signature", sig(g, caller));
                put(
                    "caller_descriptor",
                    Value::str(caller.descriptor(g.strings())),
                );
            } else {
                check_method_detail_strings(g, caller);
            }
            put(
                "line",
                line.map(|l| Value::Int(l as i64)).unwrap_or(Value::Null),
            );
            put(
                "ordinal",
                ordinal.map(|o| Value::Int(o as i64)).unwrap_or(Value::Null),
            );
        }
        NodeKind::IntConstant(v) => put("value", Value::Int(*v as i64)),
        NodeKind::StringConstant(v) => put("value", s(g, *v)),
        NodeKind::LongConstant(v) => put("value", Value::Int(*v)),
        NodeKind::FloatConstant(v) => put("value", Value::Float32(*v)),
        NodeKind::DoubleConstant(v) => put("value", Value::Float(*v)),
        NodeKind::BooleanConstant(v) => put("value", Value::Bool(*v)),
        NodeKind::NullConstant => put("value", Value::Null),
        NodeKind::EnumConstant {
            enum_type,
            enum_name,
            args,
        } => {
            put(
                "value",
                args.first()
                    .map(|a| any_to_value(g, a))
                    .unwrap_or(Value::Null),
            );
            put("name", s(g, *enum_name));
            put("enum_type", s(g, *enum_type));
        }
        NodeKind::LocalVariable {
            name,
            var_type,
            method,
        } => {
            // The baseline's `keys(n)` and `properties(n)` carry the enclosing method
            // (NodePropertyAccessor.getAllProperties); only its `RETURN n` map leaves
            // it out, which `node_display_properties` mirrors.
            put("name", s(g, *name));
            put("type", s(g, *var_type));
            put("method", sig(g, method));
        }
        NodeKind::Field {
            declaring_class,
            name,
            field_type,
            is_static,
        } => {
            put("name", s(g, *name));
            put("type", s(g, *field_type));
            put("class", s(g, *declaring_class));
            put("static", Value::Bool(*is_static));
        }
        NodeKind::Parameter {
            index,
            param_type,
            method,
        } => {
            put("index", Value::Int(*index as i64));
            put("type", s(g, *param_type));
            put("method", sig(g, method));
        }
        NodeKind::Return {
            method,
            actual_type,
        } => {
            put("method", sig(g, method));
            put(
                "actual_type",
                actual_type.map(|t| s(g, t)).unwrap_or(Value::Null),
            );
        }
        NodeKind::ResourceFile {
            path,
            source,
            format,
            profile,
        } => {
            put("path", s(g, *path));
            put("source", s(g, *source));
            put("format", s(g, *format));
            put("profile", profile.map(|p| s(g, p)).unwrap_or(Value::Null));
        }
        NodeKind::ResourceValue {
            path,
            key,
            value,
            format,
            profile,
        } => {
            put("path", s(g, *path));
            put("key", s(g, *key));
            put("value", any_to_value(g, value));
            put("format", s(g, *format));
            put("profile", profile.map(|p| s(g, p)).unwrap_or(Value::Null));
        }
        NodeKind::Annotation {
            name,
            class_name,
            member_name,
            values,
        } => {
            put("name", s(g, *name));
            put("class", s(g, *class_name));
            put("member", s(g, *member_name));
            for (k, v) in values {
                m.insert(g.str(*k).to_string(), any_to_value(g, v));
            }
        }
    }
    if let Some(id) = declared_node_type(g, node) {
        let table = g.declared_types().unwrap();
        m.insert("generic_type".into(), Value::str(table.render(id)));
        m.insert("type_info".into(), type_info(table, id));
    }
    m
}

/// `CypherExecutor.nodeToMap` — the shape a node takes in a query result.
/// Signature and descriptor fields are excluded and null values are dropped, as Gson does.
pub fn node_result_properties(g: &Graph, node: &Node) -> IndexMap<String, Value> {
    let mut m = node_display_properties(g, node);
    m.retain(|_, v| !v.is_null());
    m
}

/// The same map before null-valued keys are dropped.
///
/// JSON output never shows those keys, because Gson omits nulls — but `toString()` does,
/// so `graphite query` prints `line=null` in its text and CSV output. The two views come
/// apart only here.
pub fn node_display_properties(g: &Graph, node: &Node) -> IndexMap<String, Value> {
    let mut m = node_properties_impl(g, node, false);
    // Annotation values can also use these names; retain the existing filtering
    // for dynamic keys even though CallSite fields are no longer constructed.
    m.shift_remove("caller_signature");
    m.shift_remove("callee_signature");
    m.shift_remove("caller_descriptor");
    m.shift_remove("callee_descriptor");
    if matches!(node.kind, NodeKind::LocalVariable { .. }) {
        // The baseline's `RETURN n` map for a local variable is its name and type.
        m.shift_remove("method");
    }
    m
}

pub fn node_labels(tag: u8) -> Vec<&'static str> {
    match tag {
        TAG_CALL_SITE_NODE => vec!["CallSiteNode"],
        TAG_INT_CONSTANT => vec!["IntConstant", "Constant"],
        TAG_STRING_CONSTANT => vec!["StringConstant", "Constant"],
        TAG_LONG_CONSTANT => vec!["LongConstant", "Constant"],
        TAG_FLOAT_CONSTANT => vec!["FloatConstant", "Constant"],
        TAG_DOUBLE_CONSTANT => vec!["DoubleConstant", "Constant"],
        TAG_BOOLEAN_CONSTANT => vec!["BooleanConstant", "Constant"],
        TAG_NULL_CONSTANT => vec!["NullConstant", "Constant"],
        TAG_ENUM_CONSTANT => vec!["EnumConstant", "Constant"],
        TAG_LOCAL_VARIABLE => vec!["LocalVariable"],
        TAG_FIELD_NODE => vec!["FieldNode"],
        TAG_PARAMETER_NODE => vec!["ParameterNode"],
        TAG_RETURN_NODE => vec!["ReturnNode"],
        TAG_RESOURCE_FILE_NODE => vec!["ResourceFileNode", "ResourceFile"],
        TAG_RESOURCE_VALUE_NODE => vec!["ResourceValueNode", "ResourceValue", "Resource"],
        TAG_ANNOTATION_NODE => vec!["AnnotationNode", "Annotation"],
        _ => vec![],
    }
}

/// Label → set of tags (`nodeTypesByLabel`, case-insensitive). None = unknown label.
/// The `Method` label is reported separately via `is_method_label`.
pub fn label_tags(label: &str) -> Option<Vec<u8>> {
    let l = label.to_ascii_lowercase();
    Some(match l.as_str() {
        "callsitenode" | "callsite" => vec![TAG_CALL_SITE_NODE],
        "intconstant" => vec![TAG_INT_CONSTANT],
        "stringconstant" => vec![TAG_STRING_CONSTANT],
        "longconstant" => vec![TAG_LONG_CONSTANT],
        "floatconstant" => vec![TAG_FLOAT_CONSTANT],
        "doubleconstant" => vec![TAG_DOUBLE_CONSTANT],
        "booleanconstant" => vec![TAG_BOOLEAN_CONSTANT],
        "nullconstant" => vec![TAG_NULL_CONSTANT],
        "enumconstant" => vec![TAG_ENUM_CONSTANT],
        "constant" | "constantnode" => (0..=TAG_ENUM_CONSTANT).collect(),
        "fieldnode" | "field" => vec![TAG_FIELD_NODE],
        "parameternode" | "parameter" => vec![TAG_PARAMETER_NODE],
        "returnnode" | "return" => vec![TAG_RETURN_NODE],
        "resourcefilenode" | "resourcefile" => vec![TAG_RESOURCE_FILE_NODE],
        "resourcevaluenode" | "resourcevalue" | "resource" => vec![TAG_RESOURCE_VALUE_NODE],
        "localvariable" | "local" => vec![TAG_LOCAL_VARIABLE],
        "annotationnode" | "annotation" => vec![TAG_ANNOTATION_NODE],
        "node" => (0..TAG_COUNT as u8).collect(),
        _ => return None,
    })
}

pub fn is_method_label(label: &str) -> bool {
    label.eq_ignore_ascii_case("method")
}

pub fn rel_property(g: &Graph, rel: EdgeRef, key: &str, graph_id: Option<&str>) -> Value {
    let e = &rel.edge;
    match key {
        "type" => Value::str(e.rel_type()),
        "kind" => match e.family() {
            EdgeFamily::Call => Value::Null,
            _ => e.kind_name().map(Value::str).unwrap_or(Value::Null),
        },
        "virtual" if e.family() == EdgeFamily::Call => Value::Bool(e.is_virtual()),
        "dynamic" if e.family() == EdgeFamily::Call => Value::Bool(e.is_dynamic()),
        "graphId" => graph_id.map(Value::str).unwrap_or(Value::Null),
        _ => {
            let _ = g;
            Value::Null
        }
    }
}

pub fn method_properties(
    g: &Graph,
    m: &MethodDesc,
    graph_id: Option<&str>,
) -> IndexMap<String, Value> {
    let mut map = IndexMap::new();
    map.insert("signature".to_string(), sig(g, m));
    map.insert("class".to_string(), s(g, m.declaring_class));
    map.insert("name".to_string(), s(g, m.name));
    map.insert(
        "parameter_types".to_string(),
        Value::list(m.parameter_types.iter().map(|p| s(g, *p)).collect()),
    );
    map.insert("return_type".to_string(), s(g, m.return_type));
    if declared_method(g, m).is_some() {
        for key in GENERIC_METHOD_KEYS {
            map.insert(key.into(), generic_method_property(g, m, key));
        }
    }
    if let Some(gid) = graph_id {
        map.insert("graphId".to_string(), Value::str(gid));
    }
    map
}

pub fn method_property(g: &Graph, m: &MethodDesc, key: &str, graph_id: Option<&str>) -> Value {
    match key {
        "signature" => sig(g, m),
        "class" => s(g, m.declaring_class),
        "name" => s(g, m.name),
        "parameter_types" => Value::list(m.parameter_types.iter().map(|p| s(g, *p)).collect()),
        "return_type" => s(g, m.return_type),
        "generic_return_type"
        | "generic_parameter_types"
        | "return_type_info"
        | "parameter_type_info"
        | "type_parameters" => generic_method_property(g, m, key),
        "graphId" => graph_id.map(Value::str).unwrap_or(Value::Null),
        _ => Value::Null,
    }
}

#[allow(dead_code)]
fn _unused(_: NodeRef, _: MethodRef, _: Arc<str>) {}

#[cfg(test)]
#[path = "props_tests.rs"]
mod tests;
