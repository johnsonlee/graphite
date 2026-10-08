use super::*;
use crate::context::GraphContext;
use crate::engine::{Executor, Source};

fn fixture() -> Option<Arc<Graph>> {
    let Some(dir) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
        eprintln!("GRAPHITE_INDEX_FIXTURE unset; skipping display property tests");
        return None;
    };
    Some(Arc::new(Graph::load(std::path::Path::new(&dir)).unwrap()))
}

// Preserve types, complete values and insertion order, rather than comparing sets
// of keys or JSON maps (which would lose the display ordering).
fn ordered_values(map: &IndexMap<String, Value>) -> Vec<(String, String)> {
    map.iter()
        .map(|(key, value)| (key.clone(), format!("{value:?}")))
        .collect()
}

fn display_oracle(mut full: IndexMap<String, Value>, local: bool) -> IndexMap<String, Value> {
    full.retain(|key, _| {
        !(matches!(
            key.as_str(),
            "caller_signature" | "callee_signature" | "caller_descriptor" | "callee_descriptor"
        ) || local && key == "method")
    });
    full
}

fn assert_views(g: &Graph, node: &Node) {
    let expected = display_oracle(
        node_properties(g, node),
        matches!(node.kind, NodeKind::LocalVariable { .. }),
    );
    assert_eq!(
        ordered_values(&node_display_properties(g, node)),
        ordered_values(&expected),
        "display node {node:?}"
    );
    let mut non_null = expected;
    non_null.retain(|_, value| !value.is_null());
    assert_eq!(
        ordered_values(&node_result_properties(g, node)),
        ordered_values(&non_null),
        "result node {node:?}"
    );
}

fn call_site(g: &Graph) -> Node {
    (0..g.node_capacity())
        .filter_map(|id| g.node(id as u32))
        .find(|node| matches!(node.kind, NodeKind::CallSite { .. }))
        .expect("real core fixture must contain call sites")
}

#[test]
fn call_site_display_keeps_values_order_and_direct_method_details() {
    let Some(g) = fixture() else { return };
    let NodeKind::CallSite { caller, callee, .. } = call_site(&g).kind else {
        unreachable!()
    };
    for (line, ordinal) in [(None, None), (Some(0), Some(-7)), (Some(42), Some(256))] {
        let node = Node {
            id: 137,
            kind: NodeKind::CallSite {
                caller: caller.clone(),
                callee: callee.clone(),
                line,
                receiver: None,
                arguments: vec![],
                ordinal,
            },
        };
        let expected: IndexMap<String, Value> = [
            ("id", Value::Int(137)),
            ("callee_class", Value::str(g.str(callee.declaring_class))),
            ("callee_name", Value::str(g.str(callee.name))),
            ("caller_class", Value::str(g.str(caller.declaring_class))),
            ("caller_name", Value::str(g.str(caller.name))),
            ("line", line.map_or(Value::Null, |v| Value::Int(v as i64))),
            (
                "ordinal",
                ordinal.map_or(Value::Null, |v| Value::Int(v as i64)),
            ),
        ]
        .into_iter()
        .map(|(key, value)| (key.to_owned(), value))
        .collect();
        assert_eq!(
            ordered_values(&node_display_properties(&g, &node)),
            ordered_values(&expected)
        );
        let full = node_properties(&g, &node);
        assert_eq!(
            full.keys().map(String::as_str).collect::<Vec<_>>(),
            [
                "id",
                "callee_class",
                "callee_name",
                "callee_signature",
                "callee_descriptor",
                "caller_class",
                "caller_name",
                "caller_signature",
                "caller_descriptor",
                "line",
                "ordinal",
            ]
        );
        for (key, expected) in [
            ("callee_signature", callee.signature(&g.strings)),
            ("callee_descriptor", callee.descriptor(&g.strings)),
            ("caller_signature", caller.signature(&g.strings)),
            ("caller_descriptor", caller.descriptor(&g.strings)),
        ] {
            assert_eq!(full[key].as_str(), Some(expected.as_str()));
            assert_eq!(
                node_property(&g, &node, key).as_str(),
                Some(expected.as_str())
            );
        }
        assert_views(&g, &node);
    }
}

#[test]
fn discarded_method_details_preserve_invalid_string_id_panics_and_first_failure_order() {
    fn panic_message(action: impl FnOnce()) -> String {
        let payload = std::panic::catch_unwind(std::panic::AssertUnwindSafe(action))
            .expect_err("Invalid method string ID must still panic");
        if let Some(message) = payload.downcast_ref::<String>() {
            message.clone()
        } else if let Some(message) = payload.downcast_ref::<&str>() {
            (*message).to_owned()
        } else {
            panic!("Expected the original string-table bounds panic payload");
        }
    }

    let Some(g) = fixture() else { return };
    let original = call_site(&g);
    let bad = u32::try_from(g.strings.len())
        .unwrap()
        .checked_add(10)
        .unwrap();
    // Each bad slot alone, then simultaneous faults: callee parameters precede
    // its return, and both precede caller parameters and caller return.
    for (mask, first_bad_slot) in [(1, 0), (2, 1), (4, 2), (8, 3), (15, 0), (14, 1), (12, 2)] {
        let mut node = original.clone();
        let NodeKind::CallSite { callee, caller, .. } = &mut node.kind else {
            unreachable!()
        };
        let valid = callee.name;
        callee.parameter_types = vec![valid];
        caller.parameter_types = vec![valid];
        callee.return_type = valid;
        caller.return_type = valid;
        if mask & 1 != 0 {
            // A valid prefix and second invalid parameter also check vector order.
            callee.parameter_types = vec![valid, bad, bad + 4];
        }
        if mask & 2 != 0 {
            callee.return_type = bad + 1;
        }
        if mask & 4 != 0 {
            caller.parameter_types = vec![valid, bad + 2, bad + 5];
        }
        if mask & 8 != 0 {
            caller.return_type = bad + 3;
        }
        let expected = panic_message(|| {
            let _ = display_oracle(node_properties(&g, &node), false);
        });
        assert!(expected.contains("index out of bounds"), "{expected}");
        assert!(
            expected.contains(&format!("index is {}", bad + first_bad_slot)),
            "Wrong first fault for mask {mask}: {expected}"
        );
        assert_eq!(
            panic_message(|| {
                let _ = node_display_properties(&g, &node);
            }),
            expected,
            "Display must preserve the full-property failure, mask {mask}"
        );
        assert_eq!(
            panic_message(|| {
                let _ = node_result_properties(&g, &node);
            }),
            expected,
            "Result must preserve the full-property failure, mask {mask}"
        );
    }
}

#[test]
fn display_and_result_filter_oracle_covers_every_node_kind() {
    let Some(g) = fixture() else { return };
    let call = call_site(&g);
    let NodeKind::CallSite { caller, .. } = &call.kind else {
        unreachable!()
    };
    let name = caller.name;
    let ty = caller.declaring_class;
    // Synthetic node combinations are correctness cases backed by real interned
    // strings. They cover kinds (notably resources) absent from some core jars.
    let kinds = vec![
        call.kind.clone(),
        NodeKind::IntConstant(-17),
        NodeKind::StringConstant(name),
        NodeKind::LongConstant(i64::MAX),
        NodeKind::FloatConstant(1.25),
        NodeKind::DoubleConstant(-2.5),
        NodeKind::BooleanConstant(false),
        NodeKind::NullConstant,
        NodeKind::EnumConstant {
            enum_type: ty,
            enum_name: name,
            args: vec![AnyValue::Int(9)],
        },
        NodeKind::LocalVariable {
            name,
            var_type: ty,
            method: caller.clone(),
        },
        NodeKind::Field {
            declaring_class: ty,
            name,
            field_type: ty,
            is_static: true,
        },
        NodeKind::Parameter {
            index: 2,
            param_type: ty,
            method: caller.clone(),
        },
        NodeKind::Return {
            method: caller.clone(),
            actual_type: None,
        },
        NodeKind::ResourceFile {
            path: name,
            source: ty,
            format: name,
            profile: None,
        },
        NodeKind::ResourceValue {
            path: name,
            key: ty,
            value: AnyValue::List(vec![AnyValue::Null, AnyValue::Bool(true)]),
            format: name,
            profile: Some(ty),
        },
        NodeKind::Annotation {
            name,
            class_name: ty,
            member_name: name,
            values: vec![(ty, AnyValue::Int(7)), (name, AnyValue::Null)],
        },
    ];
    let mut tags = std::collections::BTreeSet::new();
    for (id, kind) in kinds.into_iter().enumerate() {
        let node = Node {
            id: id as u32,
            kind,
        };
        tags.insert(node.tag());
        assert_views(&g, &node);
        if matches!(node.kind, NodeKind::LocalVariable { .. }) {
            assert_eq!(
                node_property(&g, &node, "method").as_str(),
                Some(caller.signature(&g.strings).as_str())
            );
            assert!(node_properties(&g, &node).contains_key("method"));
            assert_eq!(
                node_display_properties(&g, &node)
                    .keys()
                    .map(String::as_str)
                    .collect::<Vec<_>>(),
                ["id", "name", "type"]
            );
        }
    }
    assert_eq!(tags, (0..16).collect());
}

#[test]
fn real_nodes_and_cross_graph_results_match_complete_filtered_properties() {
    let Some(g) = fixture() else { return };
    let ex = Executor::new(
        ["left", "right"]
            .into_iter()
            .map(|id| Source {
                id: Arc::from(id),
                graph: g.clone(),
            })
            .collect(),
        true,
    );
    let mut seen = std::collections::BTreeSet::new();
    let mut count = 0;
    for id in 0..g.node_capacity() {
        let Some(node) = g.node(id as u32) else {
            continue;
        };
        assert_views(&g, &node);
        // Exercise each actual node kind through both source identities. The full
        // local graph above still checks every real node, not only representatives.
        if seen.insert(node.tag()) {
            for source in 0..2 {
                let nr = NodeRef {
                    source,
                    id: id as u32,
                };
                let local = matches!(node.kind, NodeKind::LocalVariable { .. });
                let expected = display_oracle(ex.node_properties(nr), local);
                let actual = ex.node_display_properties(nr);
                assert_eq!(ordered_values(&actual), ordered_values(&expected));
                let graph_id = if source == 0 { "left" } else { "right" };
                assert_eq!(actual["graphId"].as_str(), Some(graph_id));
                assert_eq!(
                    actual["elementId"].as_str(),
                    Some(format!("{graph_id}:{id}").as_str())
                );
                assert_eq!(actual["qualifiedId"].as_str(), actual["elementId"].as_str());
                let keys = actual.keys().map(String::as_str).collect::<Vec<_>>();
                assert_eq!(
                    &keys[keys.len() - 3..],
                    ["graphId", "elementId", "qualifiedId"]
                );
                let mut non_null = expected;
                non_null.retain(|_, value| !value.is_null());
                assert_eq!(
                    ordered_values(&ex.node_result_properties(nr)),
                    ordered_values(&non_null)
                );
                let mut json = serde_json::Map::new();
                json.insert("id".into(), serde_json::json!(id));
                json.insert("type".into(), serde_json::json!(node.type_name()));
                for (key, value) in &non_null {
                    if key != "id" {
                        json.insert(key.clone(), crate::materialize::materialize(value, &ex));
                    }
                }
                assert_eq!(
                    crate::materialize::materialize(&Value::Node(nr), &ex),
                    serde_json::Value::Object(json)
                );
            }
        }
        count += 1;
    }
    assert_eq!(count, g.node_count());
    for tag in [
        TAG_CALL_SITE_NODE,
        TAG_LOCAL_VARIABLE,
        TAG_PARAMETER_NODE,
        TAG_RETURN_NODE,
        TAG_ANNOTATION_NODE,
    ] {
        assert!(
            seen.contains(&tag),
            "real core fixture lacks required tag {tag}"
        );
    }
}

/// Uses the compiled Java fixture emitted by DeclaredTypesIntegrationTest, so the
/// JVM writer and native reader must agree on descriptors, references and maps.
#[test]
fn declared_types_java_fixture_query_roundtrip_and_graph_local_identity() {
    let Some(dir) = std::env::var_os("GRAPHITE_TYPES_FIXTURE") else {
        eprintln!("GRAPHITE_TYPES_FIXTURE unset; skipping Java interoperability test");
        return;
    };
    let path = std::path::Path::new(&dir);
    let graph = Graph::load(path).unwrap();
    assert!(graph.declared_types.is_some());
    let method = graph
        .methods()
        .iter()
        .find(|m| graph.str(m.name) == "echo")
        .expect("echo method")
        .clone();
    assert_eq!(
        method_property(&graph, &method, "generic_return_type", None).as_str(),
        Some("java.util.List<java.lang.String>")
    );
    assert_eq!(
        method_property(&graph, &method, "return_type", None).as_str(),
        Some("java.util.List")
    );
    let field = graph
        .ids_by_tag(TAG_FIELD_NODE)
        .iter()
        .filter_map(|id| graph.node(*id))
        .find(|n| matches!(&n.kind, NodeKind::Field {name, ..} if graph.str(*name)=="first"))
        .expect("first field");
    assert_eq!(
        node_property(&graph, &field, "generic_type").as_str(),
        Some("java.util.List<java.lang.String>")
    );
    let Value::Map(info) = node_property(&graph, &field, "type_info") else {
        panic!("type map")
    };
    assert_eq!(info["name"].as_str(), Some("java.util.List"));
    let Value::List(arguments) = &info["arguments"] else {
        panic!("arguments")
    };
    let Value::Map(argument) = &arguments[0] else {
        panic!("argument map")
    };
    assert_eq!(argument["name"].as_str(), Some("java.lang.String"));
    assert!(!info.contains_key("scope"));

    for tag in [TAG_PARAMETER_NODE, TAG_RETURN_NODE] {
        let declarations: Vec<_> = graph
            .ids_by_tag(tag)
            .iter()
            .filter_map(|id| graph.node(*id))
            .filter(|node| match &node.kind {
                NodeKind::Parameter { method, index, .. } => {
                    graph.str(method.name) == "echo" && *index >= 0
                }
                NodeKind::Return { method, .. } => graph.str(method.name) == "echo",
                _ => false,
            })
            .collect();
        assert!(!declarations.is_empty(), "echo declarations for tag {tag}");
        for node in declarations {
            assert_eq!(
                node_property(&graph, &node, "generic_type").as_str(),
                Some("java.util.List<java.lang.String>")
            );
        }
    }
    for (field_name, expected) in [("value", "T"), ("matrix", "T[][]")] {
        let declaration = graph.ids_by_tag(TAG_FIELD_NODE).iter().filter_map(|id| graph.node(*id))
            .find(|node| matches!(&node.kind, NodeKind::Field { name, .. } if graph.str(*name) == field_name))
            .unwrap_or_else(|| panic!("missing {field_name} field"));
        assert_eq!(
            node_property(&graph, &declaration, "generic_type").as_str(),
            Some(expected)
        );
    }
    let matrix = graph
        .methods()
        .iter()
        .find(|m| graph.str(m.name) == "echoMatrix")
        .expect("echoMatrix method");
    assert_eq!(
        method_property(&graph, matrix, "generic_return_type", None).as_str(),
        Some("T[][]")
    );
    let Value::List(parameters) = method_property(&graph, matrix, "generic_parameter_types", None)
    else {
        panic!("matrix parameters")
    };
    assert_eq!(parameters.len(), 1);
    assert_eq!(parameters[0].as_str(), Some("T[][]"));
    let Value::Map(outer) = method_property(&graph, matrix, "return_type_info", None) else {
        panic!("outer array")
    };
    assert_eq!(outer["kind"].as_str(), Some("array"));
    let Value::Map(inner) = &outer["component"] else {
        panic!("inner array")
    };
    assert_eq!(inner["kind"].as_str(), Some("array"));
    let Value::Map(element) = &inner["component"] else {
        panic!("type variable")
    };
    assert_eq!(element["kind"].as_str(), Some("variable"));
    assert_eq!(element["name"].as_str(), Some("T"));
    assert!(element["scope"].as_str().unwrap().starts_with("class:"));
    let identity = graph
        .methods()
        .iter()
        .find(|m| graph.str(m.name) == "identity")
        .expect("identity method");
    assert_eq!(
        method_property(&graph, identity, "generic_return_type", None).as_str(),
        Some("T")
    );
    let Value::Map(info) = method_property(&graph, identity, "return_type_info", None) else {
        panic!("variable info")
    };
    assert_eq!(info["kind"].as_str(), Some("variable"));
    assert!(info["scope"].as_str().unwrap().starts_with("class:"));

    // A separate graph has the same local IDs and member identities, but a
    // different type table. Query source identity must select the right table.
    let mut other = Graph::load(path).unwrap();
    let table = other.declared_types.as_mut().unwrap();
    for ty in &mut table.types {
        if ty.name == "java.lang.String" {
            ty.name = "example.Other".into();
        }
    }
    let mut legacy = Graph::load(path).unwrap();
    legacy.declared_types = None;
    for key in ["generic_type", "type_info"] {
        assert!(node_property(&legacy, &field, key).is_null());
        assert!(!node_properties(&legacy, &field).contains_key(key));
    }
    for key in GENERIC_METHOD_KEYS {
        assert!(method_property(&legacy, &method, key, None).is_null());
    }
    for key in GENERIC_METHOD_KEYS {
        assert!(!method_properties(&legacy, &method, None).contains_key(key));
    }
    let mut partial = Graph::load(path).unwrap();
    let table = partial.declared_types.as_mut().unwrap();
    table.fields.retain(|(_, name, _), _| name != "first");
    table.methods.retain(|(_, name, _), _| name != "echo");
    assert!(!node_properties(&partial, &field).contains_key("generic_type"));
    assert!(!method_properties(&partial, &method, None).contains_key("generic_return_type"));
    let partial = Executor::new(
        vec![Source {
            id: Arc::from("partial"),
            graph: Arc::new(partial),
        }],
        false,
    );
    let groups = partial
        .execute(
            "MATCH (f:FieldNode) RETURN 'generic_type' IN keys(f) AS declared, count(*) AS n",
            None,
        )
        .unwrap()
        .rows;
    assert_eq!(groups.len(), 2, "declaration keys vary within one node tag");
    assert!(groups
        .iter()
        .any(|row| row["declared"].as_bool() == Some(false) && matches!(row["n"], Value::Int(1))));
    assert!(groups
        .iter()
        .any(|row| row["declared"].as_bool() == Some(true)));
    for query in [
        "MATCH (f:FieldNode) WHERE f.name = 'first' AND NOT any(k IN keys(f) WHERE toString(f[k]) CONTAINS 'absent-marker') RETURN f.name AS name",
        "MATCH (m:Method) WHERE m.name = 'echo' AND NOT any(k IN keys(m) WHERE toString(m[k]) CONTAINS 'absent-marker') RETURN m.name AS name",
    ] {
        assert_eq!(partial.execute(query, None).unwrap().rows.len(), 1, "{query}");
    }
    let executor = Executor::new(
        vec![
            Source {
                id: Arc::from("original"),
                graph: Arc::new(graph),
            },
            Source {
                id: Arc::from("other"),
                graph: Arc::new(other),
            },
            Source {
                id: Arc::from("legacy"),
                graph: Arc::new(legacy),
            },
        ],
        true,
    );
    let result = executor.execute("MATCH (m:Method) WHERE m.name = 'echo' RETURN m.graphId AS source, m.generic_return_type AS declared ORDER BY source",None).unwrap();
    assert_eq!(result.rows.len(), 3);
    assert_eq!(result.rows[0]["source"].as_str(), Some("legacy"));
    assert!(result.rows[0]["declared"].is_null());
    assert_eq!(result.rows[1]["source"].as_str(), Some("original"));
    assert_eq!(
        result.rows[1]["declared"].as_str(),
        Some("java.util.List<java.lang.String>")
    );
    assert_eq!(result.rows[2]["source"].as_str(), Some("other"));
    assert_eq!(
        result.rows[2]["declared"].as_str(),
        Some("java.util.List<example.Other>")
    );
    let result = executor.execute("MATCH (f:FieldNode) WHERE f.name = 'first' RETURN f.graphId AS source, f.generic_type AS declared ORDER BY source",None).unwrap();
    assert_eq!(result.rows.len(), 3);
    assert!(result.rows[0]["declared"].is_null());
    assert_eq!(
        result.rows[1]["declared"].as_str(),
        Some("java.util.List<java.lang.String>")
    );
    assert_eq!(
        result.rows[2]["declared"].as_str(),
        Some("java.util.List<example.Other>")
    );
    for query in [
        "MATCH (f:FieldNode) WHERE any(k IN keys(f) WHERE toString(f[k]) CONTAINS 'example.Other') RETURN f.graphId AS source",
        "MATCH (f:FieldNode) WHERE f.generic_type CONTAINS 'example.Other' RETURN f.graphId AS source",
        "MATCH (f:FieldNode) WHERE toString(f.type_info) CONTAINS 'example.Other' RETURN f.graphId AS source",
    ] {
        let rows = executor.execute(query, None).unwrap().rows;
        assert!(!rows.is_empty(), "{query}");
        assert!(rows.iter().all(|row| row["source"].as_str() == Some("other")), "{query}: {rows:?}");
    }
    for query in [
        "MATCH (f:FieldNode) WHERE f.name = 'first' AND NOT any(k IN keys(f) WHERE toString(f[k]) CONTAINS 'absent-marker') RETURN f.graphId AS source",
        "MATCH (m:Method) WHERE m.name = 'echo' AND NOT any(k IN keys(m) WHERE toString(m[k]) CONTAINS 'absent-marker') RETURN m.graphId AS source",
    ] {
        assert_eq!(executor.execute(query, None).unwrap().rows.len(), 3, "legacy negative ANY: {query}");
    }
}

#[test]
fn declared_type_info_is_sparse_internally_and_in_nested_json() {
    use graphite_storage::types::{DeclaredTypes, TypeExpr};
    let table = DeclaredTypes {
        types: vec![
            TypeExpr {
                kind: "variable".into(),
                name: "T".into(),
                scope: "class:fixture.Holder".into(),
                owner: None,
                component: None,
                variance: String::new(),
                arguments: vec![],
            },
            TypeExpr {
                kind: "wildcard".into(),
                name: String::new(),
                scope: String::new(),
                owner: None,
                component: Some(0),
                variance: "super".into(),
                arguments: vec![],
            },
            TypeExpr {
                kind: "class".into(),
                name: "java.util.List".into(),
                scope: String::new(),
                owner: None,
                component: None,
                variance: String::new(),
                arguments: vec![1],
            },
            TypeExpr {
                kind: "array".into(),
                name: String::new(),
                scope: String::new(),
                owner: None,
                component: Some(2),
                variance: String::new(),
                arguments: vec![],
            },
        ],
        ..DeclaredTypes::default()
    };
    let info = type_info(&table, 3);
    let Value::Map(map) = &info else {
        panic!("array map")
    };
    assert_eq!(
        map.keys().map(String::as_str).collect::<Vec<_>>(),
        ["kind", "component", "arguments"]
    );
    let context = Executor::new(vec![], false);
    assert_eq!(
        crate::materialize::materialize(&info, &context),
        serde_json::json!({
            "kind": "array",
            "arguments": [],
            "component": {
                "kind": "class", "name": "java.util.List",
                "arguments": [{
                    "kind": "wildcard", "variance": "super", "arguments": [],
                    "component": {"kind": "variable", "name": "T", "scope": "class:fixture.Holder", "arguments": []}
                }]
            }
        })
    );
    let user_map = Value::map(IndexMap::from([("scope".into(), Value::Null)]));
    assert_eq!(
        crate::materialize::materialize(&user_map, &context),
        serde_json::json!({"scope": null})
    );
}
