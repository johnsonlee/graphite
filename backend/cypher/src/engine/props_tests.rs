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
