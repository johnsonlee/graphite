use super::*;
use crate::context::GraphContext;
use crate::engine::{Executor, Source};

#[test]
fn generic_null_checks_preserve_binding_and_do_not_read_type_values() {
    let Some(dir) = std::env::var_os("GRAPHITE_TYPES_FIXTURE") else {
        assert!(
            std::env::var_os("GRAPHITE_REQUIRE_DUAL_TYPES_FIXTURES").is_none(),
            "GRAPHITE_TYPES_FIXTURE required for generic presence validation"
        );
        return;
    };
    let path = std::path::Path::new(&dir);
    for mode in ["full", "partial", "absent"] {
        let mut graph = Graph::load(path).unwrap();
        graph.update_declared_types(|table| match mode {
            "partial" => {
                let table = table.as_mut().unwrap();
                table
                    .fields
                    .retain(|(_, name, _), _| name.as_ref() != "first");
                table
                    .methods
                    .retain(|(_, name, _), _| name.as_ref() != "echo");
            }
            "absent" => *table = None,
            _ => {}
        });
        let mut node_cases = Vec::new();
        for tag in [
            TAG_FIELD_NODE,
            TAG_PARAMETER_NODE,
            TAG_RETURN_NODE,
            TAG_CALL_SITE_NODE,
        ] {
            for id in graph.ids_by_tag(tag) {
                let node = graph.node(*id).unwrap();
                for key in ["generic_type", "type_info"] {
                    let expected = node_property(&graph, &node, key).is_null();
                    assert_eq!(node_property_is_null(&graph, &node, key), expected);
                    node_cases.push((*id, key, expected));
                }
            }
        }
        let method_cases: Vec<_> = graph
            .methods()
            .iter()
            .enumerate()
            .flat_map(|(index, method)| {
                GENERIC_METHOD_KEYS.map(|key| {
                    let expected = method_property(&graph, method, key, None).is_null();
                    assert_eq!(method_property_is_null(&graph, method, key, None), expected);
                    (index as u32, key, expected)
                })
            })
            .collect();
        assert!(!node_cases.is_empty() && !method_cases.is_empty());
        if mode == "full" {
            assert!(node_cases.iter().any(|(_, _, absent)| !absent));
            assert!(method_cases.iter().any(|(_, _, absent)| !absent));
        }
        // Bindings remain, but rendering either text or a structured map would
        // access invalid backing. Presence must not touch any type expression.
        graph.update_declared_types(|table| {
            if let Some(table) = table {
                table.types.clear();
            }
        });
        let executor = Executor::new(
            vec![Source {
                id: Arc::from(mode),
                graph: Arc::new(graph),
            }],
            true,
        );
        let params = IndexMap::new();
        let evaluator = crate::eval::Evaluator::new(&executor, &params);
        for (value, key, expected) in node_cases
            .into_iter()
            .map(|(id, key, expected)| (Value::Node(NodeRef { source: 0, id }), key, expected))
            .chain(method_cases.into_iter().map(|(index, key, expected)| {
                (Value::Method(MethodRef { source: 0, index }), key, expected)
            }))
        {
            let row = IndexMap::from([("n".into(), value)]);
            let property = crate::ast::Expr::Property {
                expr: Box::new(crate::ast::Expr::Variable("n".into())),
                key: key.into(),
            };
            for (expr, expected) in [
                (
                    crate::ast::Expr::IsNull(Box::new(property.clone())),
                    expected,
                ),
                (crate::ast::Expr::IsNotNull(Box::new(property)), !expected),
            ] {
                assert_eq!(
                    evaluator.eval(&expr, &row).unwrap().as_bool(),
                    Some(expected),
                    "{mode}: {key}"
                );
            }
        }
        assert!(executor.node_property_is_null(
            NodeRef {
                source: 0,
                id: u32::MAX
            },
            "generic_type"
        ));
        for key in GENERIC_METHOD_KEYS {
            assert!(executor.method_property_is_null(
                MethodRef {
                    source: 0,
                    index: u32::MAX
                },
                key
            ));
        }
    }
}

#[test]
fn generic_null_checks_preserve_parameter_bounds_and_annotation_fallback() {
    let Some(dir) = std::env::var_os("GRAPHITE_TYPES_FIXTURE") else {
        assert!(std::env::var_os("GRAPHITE_REQUIRE_DUAL_TYPES_FIXTURES").is_none());
        return;
    };
    let graph = Graph::load(std::path::Path::new(&dir)).unwrap();
    let method = graph
        .methods()
        .iter()
        .find(|m| graph.str(m.name) == "echo")
        .unwrap();
    for index in [-1, method.parameter_types.len() as i32] {
        let node = Node {
            id: 0,
            kind: NodeKind::Parameter {
                method: method.clone(),
                index,
                param_type: method.return_type,
            },
        };
        for key in ["generic_type", "type_info"] {
            assert!(node_property_is_null(&graph, &node, key));
            assert!(node_property(&graph, &node, key).is_null());
        }
    }
    let name = graph.strings().index_of("first").unwrap() as u32;
    let mut annotation = Node {
        id: 17,
        kind: NodeKind::Annotation {
            name,
            class_name: name,
            member_name: name,
            values: vec![(name, AnyValue::Int(7))],
        },
    };
    assert!(!node_property_is_null(&graph, &annotation, "first"));
    assert!(node_property_is_null(&graph, &annotation, "generic_type"));
    // Generic-looking keys must still traverse an annotation's dynamic values,
    // including the same invalid-SID failure as ordinary property evaluation.
    if let NodeKind::Annotation { values, .. } = &mut annotation.kind {
        values[0].0 = u32::MAX;
    }
    for key in ["generic_type", "type_info"] {
        assert!(std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            node_property_is_null(&graph, &annotation, key)
        }))
        .is_err());
    }
}

#[test]
fn null_predicates_preserve_maps_null_bases_and_subscript_semantics() {
    use crate::ast::{Expr, Literal};
    let executor = Executor::new(vec![], false);
    let params = IndexMap::new();
    let evaluator = crate::eval::Evaluator::new(&executor, &params);
    for (value, expected) in [
        (Value::Null, true),
        (Value::Int(1), true),
        (Value::map(IndexMap::new()), true),
        (
            Value::map(IndexMap::from([("generic_type".into(), Value::Null)])),
            true,
        ),
        (
            Value::map(IndexMap::from([(
                "generic_type".into(),
                Value::str("present"),
            )])),
            false,
        ),
    ] {
        let row = IndexMap::from([("n".into(), value)]);
        for expression in [
            Expr::Property {
                expr: Box::new(Expr::Variable("n".into())),
                key: "generic_type".into(),
            },
            Expr::Subscript {
                expr: Box::new(Expr::Variable("n".into())),
                index: Box::new(Expr::Literal(Literal::Str("generic_type".into()))),
            },
        ] {
            assert_eq!(
                evaluator
                    .eval(&Expr::IsNull(Box::new(expression.clone())), &row)
                    .unwrap()
                    .as_bool(),
                Some(expected)
            );
            assert_eq!(
                evaluator
                    .eval(&Expr::IsNotNull(Box::new(expression)), &row)
                    .unwrap()
                    .as_bool(),
                Some(!expected)
            );
        }
    }
    let bad_base = Expr::Property {
        expr: Box::new(Expr::CountStar),
        key: "generic_type".into(),
    };
    assert!(evaluator
        .eval(&Expr::IsNull(Box::new(bad_base)), &IndexMap::new())
        .is_err());
}

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
    assert_eq!(
        node_keys(g, node),
        node_properties(g, node).into_keys().collect::<Vec<_>>(),
        "ordered keys for {node:?}"
    );
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

#[test]
fn declaration_keys_use_exact_binding_without_projecting_type_values() {
    let Some(dir) = std::env::var_os("GRAPHITE_TYPES_FIXTURE") else {
        return;
    };
    let mut graph = Graph::load(std::path::Path::new(&dir)).unwrap();
    for tag in [TAG_FIELD_NODE, TAG_PARAMETER_NODE, TAG_RETURN_NODE] {
        for id in graph.ids_by_tag(tag) {
            let node = graph.node(*id).unwrap();
            assert_eq!(
                node_keys(&graph, &node),
                node_properties(&graph, &node)
                    .into_keys()
                    .collect::<Vec<_>>()
            );
        }
    }
    let field = graph
        .ids_by_tag(TAG_FIELD_NODE)
        .iter()
        .filter_map(|id| graph.node(*id))
        .find(|n| matches!(&n.kind, NodeKind::Field {name, ..} if graph.str(*name) == "first"))
        .unwrap();
    let mut unmatched_field = field.clone();
    if let NodeKind::Field {
        declaring_class,
        field_type,
        ..
    } = &mut unmatched_field.kind
    {
        *field_type = *declaring_class;
    }
    assert_eq!(
        node_keys(&graph, &unmatched_field),
        ["id", "name", "type", "class", "static"]
    );
    let method = graph
        .methods()
        .iter()
        .find(|m| graph.str(m.name) == "echo")
        .unwrap()
        .clone();
    let parameter = |method: MethodDesc, index| Node {
        id: 0,
        kind: NodeKind::Parameter {
            param_type: method.return_type,
            method,
            index,
        },
    };
    assert_eq!(
        node_keys(&graph, &parameter(method.clone(), 0)),
        ["id", "index", "type", "method", "generic_type", "type_info"]
    );
    for index in [-1, method.parameter_types.len() as i32] {
        assert_eq!(
            node_keys(&graph, &parameter(method.clone(), index)),
            ["id", "index", "type", "method"]
        );
    }
    let constructor = graph
        .methods()
        .iter()
        .find(|m| graph.str(m.name) == "<init>")
        .unwrap()
        .clone();
    assert_eq!(
        node_keys(&graph, &parameter(constructor, 0)),
        ["id", "index", "type", "method"]
    );
    let returned = |method| Node {
        id: 0,
        kind: NodeKind::Return {
            method,
            actual_type: None,
        },
    };
    assert_eq!(
        node_keys(&graph, &returned(method.clone())),
        ["id", "method", "actual_type", "generic_type", "type_info"]
    );
    let mut unmatched = method.clone();
    unmatched.return_type = method.declaring_class;
    assert_eq!(
        node_keys(&graph, &returned(unmatched)),
        ["id", "method", "actual_type"]
    );
    // A source-access assertion: bindings suffice for keys. Any expansion of the
    // type values would access this deliberately unavailable test-only backing.
    graph.update_declared_types(|table| table.as_mut().unwrap().types.clear());
    assert_eq!(
        node_keys(&graph, &field),
        [
            "id",
            "name",
            "type",
            "class",
            "static",
            "generic_type",
            "type_info"
        ]
    );
    assert_eq!(
        node_keys(&graph, &returned(method.clone())),
        ["id", "method", "actual_type", "generic_type", "type_info"]
    );
    assert_eq!(
        node_keys(&graph, &parameter(method.clone(), 0)),
        ["id", "index", "type", "method", "generic_type", "type_info"]
    );
    graph.update_declared_types(|table| *table = None);
    assert_eq!(
        node_keys(&graph, &returned(method)),
        ["id", "method", "actual_type"]
    );
}

#[test]
fn declaration_key_partitions_preserve_mixed_source_rows_order_and_provenance() {
    let Some(dir) = std::env::var_os("GRAPHITE_TYPES_FIXTURE") else {
        return;
    };
    let path = std::path::Path::new(&dir);
    let original = Graph::load(path).unwrap();
    let mut partial = Graph::load(path).unwrap();
    partial.update_declared_types(|table| {
        let table = table.as_mut().unwrap();
        table
            .fields
            .retain(|(_, name, _), _| name.as_ref() != "first");
        table
            .methods
            .retain(|(_, name, _), _| name.as_ref() != "echo");
    });
    let mut legacy = Graph::load(path).unwrap();
    legacy.update_declared_types(|table| *table = None);
    let sources: Vec<Source> = [
        ("original", original),
        ("partial", partial),
        ("legacy", legacy),
    ]
    .into_iter()
    .map(|(id, graph)| Source {
        id: Arc::from(id),
        graph: Arc::new(graph),
    })
    .collect();
    let fast = Executor::new(sources.clone(), true);
    let plain = Executor::new(sources.clone(), true).without_partitioning();
    for query in [
        "MATCH (n) UNWIND keys(n) AS k RETURN k, count(*) AS c ORDER BY c DESC LIMIT 50",
        "MATCH (n) RETURN keys(n) AS k, count(*) AS c",
        "MATCH (n:FieldNode) RETURN n.class AS owner, keys(n) AS k, count(*) AS c",
        "MATCH (n:ParameterNode) RETURN n.graphId AS graph, keys(n) AS k, count(*) AS c",
        "MATCH (n:ReturnNode) WHERE 'generic_type' IN keys(n) RETURN keys(n) AS k, count(*) AS c",
        "MATCH (n:FieldNode) WHERE NOT ('generic_type' IN keys(n)) RETURN n.graphId AS graph, count(*) AS c",
        "MATCH (n) RETURN keys(n) AS k LIMIT 50",
        "MATCH (n) RETURN n.graphId AS graph, labels(n) AS labels, n.generic_type IS NULL AS missing, n.type_info IS NOT NULL AS present, count(*) AS c",
        "MATCH (n:FieldNode) WHERE n.generic_type IS NOT NULL RETURN n.class AS owner, count(*) AS c",
        "MATCH (n:ParameterNode) WHERE n.type_info IS NULL RETURN n.graphId AS graph, count(*) AS c",
        "MATCH (n:ReturnNode) RETURN n.generic_type IS NULL AS missing, count(*) AS c",
        "MATCH (n:FieldNode) WITH n.generic_type IS NOT NULL AS present RETURN present, count(*) AS c",
        "MATCH (n:FieldNode) WITH n AS aliased RETURN aliased.generic_type IS NULL AS missing, count(*) AS c",
        "MATCH (n:FieldNode) UNWIND [null, {generic_type: 'shadowed'}] AS n RETURN n.generic_type IS NULL AS missing, count(*) AS c",
    ] {
        let expected = plain.execute(query, None).unwrap();
        let actual = fast.execute(query, None).unwrap();
        assert_eq!(actual.columns, expected.columns, "{query}");
        assert_eq!(actual.rows.iter().map(ordered_values).collect::<Vec<_>>(),
            expected.rows.iter().map(ordered_values).collect::<Vec<_>>(), "{query}");
    }
    let token = crate::engine::CancelToken::new();
    token.cancel();
    let cancelled = Executor::new(sources, true).with_cancel(token);
    assert!(cancelled
        .execute(
            "MATCH (n) UNWIND keys(n) AS k RETURN k, count(*) AS c",
            None
        )
        .is_err());
    assert!(cancelled
        .execute(
            "MATCH (n) RETURN n.generic_type IS NOT NULL AS present, count(*) AS c",
            None
        )
        .is_err());
}

#[test]
fn declaration_key_summary_and_unsorted_fallback_preserve_filtered_rows() {
    let Some(dir) = std::env::var_os("GRAPHITE_TYPES_FIXTURE") else {
        return;
    };
    let dir = std::path::Path::new(&dir);
    // Alter only type-index traversal order in a private copy of the real fixture.
    // The declaration table stays digest-bound to its unchanged metadata/properties.
    struct FixtureCopy(std::path::PathBuf);
    impl Drop for FixtureCopy {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
    let copy = FixtureCopy(
        std::env::temp_dir().join(format!("graphite-declaration-order-{}", std::process::id())),
    );
    std::fs::create_dir_all(&copy.0).unwrap();
    for entry in std::fs::read_dir(dir).unwrap() {
        let entry = entry.unwrap();
        if entry.file_type().unwrap().is_file() {
            std::fs::copy(entry.path(), copy.0.join(entry.file_name())).unwrap();
        }
    }
    let index = copy.0.join("graph.typeindex");
    let mut bytes = std::fs::read(&index).unwrap();
    let entries = i32::from_be_bytes(bytes[4..8].try_into().unwrap()) as usize;
    for entry in 0..entries {
        let pos = 8 + entry * 13;
        if [TAG_FIELD_NODE, TAG_PARAMETER_NODE, TAG_RETURN_NODE].contains(&bytes[pos]) {
            let count = i32::from_be_bytes(bytes[pos + 1..pos + 5].try_into().unwrap()) as usize;
            let offset = i64::from_be_bytes(bytes[pos + 5..pos + 13].try_into().unwrap()) as usize;
            bytes[offset..offset + count * 4]
                .as_chunks_mut::<4>()
                .0
                .reverse();
        }
    }
    std::fs::write(index, bytes).unwrap();
    for path in [dir, copy.0.as_path()] {
        let mut graph = Graph::load(path).unwrap();
        graph.update_declared_types(|table| {
            table
                .as_mut()
                .unwrap()
                .fields
                .retain(|(_, name, _), _| name.as_ref() != "first");
        });
        assert_eq!(
            graph.declared_key_partitions(TAG_FIELD_NODE).is_some(),
            path == dir
        );
        let field_id = graph.ids_by_tag(TAG_FIELD_NODE)[0];
        let source = Source {
            id: Arc::from("fixture"),
            graph: Arc::new(graph),
        };
        let fast = Executor::new(vec![source.clone()], true);
        let plain = Executor::new(vec![source], true).without_partitioning();
        for query in [
            "MATCH (n) UNWIND keys(n) AS k RETURN k, count(*) AS c ORDER BY c DESC LIMIT 50".to_string(),
            "MATCH (n) RETURN keys(n) AS k, count(*) AS c LIMIT 1".to_string(),
            "MATCH (n:FieldNode) RETURN n.class AS owner, keys(n) AS k, count(*) AS c".to_string(),
            "MATCH (n:FieldNode) WHERE n.name = 'first' RETURN keys(n) AS k, count(*) AS c".to_string(),
            "MATCH (n:FieldNode) WHERE NOT ('generic_type' IN keys(n)) RETURN keys(n) AS k, count(*) AS c".to_string(),
            "MATCH (n:FieldNode) RETURN n.generic_type IS NULL AS missing, count(*) AS c LIMIT 1".to_string(),
            "MATCH (n:ParameterNode) WHERE n.type_info IS NOT NULL RETURN n.graphId AS graph, count(*) AS c".to_string(),
            format!("MATCH (n) WHERE id(n) = {field_id} UNWIND keys(n) AS k RETURN k, count(*) AS c"),
        ] {
            let expected = plain.execute(&query, None).unwrap();
            let actual = fast.execute(&query, None).unwrap();
            assert_eq!(actual.columns, expected.columns, "{path:?}: {query}");
            assert_eq!(actual.rows.iter().map(ordered_values).collect::<Vec<_>>(), expected.rows.iter().map(ordered_values).collect::<Vec<_>>(), "{path:?}: {query}");
        }
    }
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
            ("callee_signature", callee.signature(g.strings())),
            ("callee_descriptor", callee.descriptor(g.strings())),
            ("caller_signature", caller.signature(g.strings())),
            ("caller_descriptor", caller.descriptor(g.strings())),
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
    let bad = u32::try_from(g.strings().len())
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
                Some(caller.signature(g.strings()).as_str())
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
    for variable in ["GRAPHITE_TYPES_V1_FIXTURE", "GRAPHITE_TYPES_FIXTURE"] {
        let Some(dir) = std::env::var_os(variable) else {
            eprintln!("{variable} unset; skipping Java interoperability test");
            continue;
        };
        assert_declared_types_java_fixture(std::path::Path::new(&dir));
    }
}

fn assert_declared_types_java_fixture(path: &std::path::Path) {
    let graph = Graph::load(path).unwrap();
    assert!(graph.declared_types().is_some());
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
    other.update_declared_types(|table| {
        for ty in &mut table.as_mut().unwrap().types {
            if ty.name.as_ref() == "java.lang.String" {
                ty.name = "example.Other".into();
            }
        }
    });
    let mut legacy = Graph::load(path).unwrap();
    legacy.update_declared_types(|table| *table = None);
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
    partial.update_declared_types(|table| {
        let table = table.as_mut().unwrap();
        table
            .fields
            .retain(|(_, name, _), _| name.as_ref() != "first");
        table
            .methods
            .retain(|(_, name, _), _| name.as_ref() != "echo");
    });
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

fn assert_complete_declared_graph_equivalence(expected: &Graph, actual: &Graph) {
    assert_eq!(expected.declared_types(), actual.declared_types());
    assert_eq!(expected.node_capacity(), actual.node_capacity());
    assert_eq!(expected.node_count(), actual.node_count());
    for id in 0..expected.node_capacity() as u32 {
        match (expected.node(id), actual.node(id)) {
            (Some(left), Some(right)) => {
                assert_eq!(
                    ordered_values(&node_properties(expected, &left)),
                    ordered_values(&node_properties(actual, &right)),
                    "node {id}"
                );
                assert_eq!(
                    node_keys(expected, &left),
                    node_keys(actual, &right),
                    "keys {id}"
                );
            }
            (None, None) => {}
            _ => panic!("node presence differs at {id}"),
        }
    }
    assert_eq!(expected.methods().len(), actual.methods().len());
    for (left, right) in expected.methods().iter().zip(actual.methods()) {
        assert_eq!(
            ordered_values(&method_properties(expected, left, None)),
            ordered_values(&method_properties(actual, right, None))
        );
    }
}

#[test]
fn java_v1_v2_and_packed_graphs_preserve_all_properties_and_mixed_graph_queries() {
    let (Some(v1_dir), Some(v2_dir)) = (
        std::env::var_os("GRAPHITE_TYPES_V1_FIXTURE"),
        std::env::var_os("GRAPHITE_TYPES_V2_FIXTURE")
            .or_else(|| std::env::var_os("GRAPHITE_TYPES_FIXTURE")),
    ) else {
        eprintln!("both GRAPHITE_TYPES_V1_FIXTURE and GRAPHITE_TYPES_FIXTURE required for wire interoperability");
        return;
    };
    let v1_path = std::path::Path::new(&v1_dir);
    let v2_path = std::path::Path::new(&v2_dir);
    let v1 = Arc::new(Graph::load(v1_path).unwrap());
    let v2 = Arc::new(Graph::load(v2_path).unwrap());
    assert_complete_declared_graph_equivalence(&v1, &v2);
    let temp =
        std::env::temp_dir().join(format!("graphite-types-wire-props-{}", std::process::id()));
    std::fs::create_dir_all(&temp).unwrap();
    let mut sources = vec![
        Source {
            id: Arc::from("v1"),
            graph: v1.clone(),
        },
        Source {
            id: Arc::from("v2"),
            graph: v2.clone(),
        },
    ];
    for (id, path) in [("packed-v1", v1_path), ("packed-v2", v2_path)] {
        let packed = temp.join(format!("{id}.graphite"));
        graphite_storage::container::pack(path, &packed).unwrap();
        let graph = Arc::new(Graph::load(&packed).unwrap());
        assert_complete_declared_graph_equivalence(&v1, &graph);
        sources.push(Source {
            id: Arc::from(id),
            graph,
        });
    }
    let executor = Executor::new(sources, true);
    let result = executor.execute("MATCH (f:FieldNode) WHERE f.name = 'first' RETURN f.graphId AS source, f.generic_type AS declared, f.type_info AS info ORDER BY source", None).unwrap();
    assert_eq!(result.rows.len(), 4);
    let Value::Map(expected_info) = &result.rows[0]["info"] else {
        panic!("type map");
    };
    for (row, source) in result
        .rows
        .iter()
        .zip(["packed-v1", "packed-v2", "v1", "v2"])
    {
        assert_eq!(row["source"].as_str(), Some(source));
        assert_eq!(
            row["declared"].as_str(),
            Some("java.util.List<java.lang.String>")
        );
        let Value::Map(info) = &row["info"] else {
            panic!("type map");
        };
        assert_eq!(ordered_values(info), ordered_values(expected_info));
    }
    drop(executor);
    std::fs::remove_dir_all(temp).unwrap();
}

#[test]
fn declared_type_info_is_sparse_internally_and_in_nested_json() {
    use graphite_storage::types::{MutableDeclaredTypes as DeclaredTypes, TypeExpr};
    let table = DeclaredTypes {
        types: vec![
            TypeExpr {
                kind: "variable".into(),
                name: "T".into(),
                scope: "class:fixture.Holder".into(),
                owner: None,
                component: None,
                variance: Arc::from(""),
                arguments: vec![],
            },
            TypeExpr {
                kind: "wildcard".into(),
                name: Arc::from(""),
                scope: Arc::from(""),
                owner: None,
                component: Some(0),
                variance: "super".into(),
                arguments: vec![],
            },
            TypeExpr {
                kind: "class".into(),
                name: "java.util.List".into(),
                scope: Arc::from(""),
                owner: None,
                component: None,
                variance: Arc::from(""),
                arguments: vec![1],
            },
            TypeExpr {
                kind: "array".into(),
                name: Arc::from(""),
                scope: Arc::from(""),
                owner: None,
                component: Some(2),
                variance: Arc::from(""),
                arguments: vec![],
            },
        ],
        ..DeclaredTypes::default()
    };
    let table = graphite_storage::types::DeclaredTypes::from(table);
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

#[test]
fn scalar_properties_preserve_declared_and_non_declared_node_contracts() {
    for variable in ["GRAPHITE_TYPES_V1_FIXTURE", "GRAPHITE_TYPES_FIXTURE"] {
        let Some(dir) = std::env::var_os(variable) else {
            assert!(
                std::env::var_os("GRAPHITE_REQUIRE_DUAL_TYPES_FIXTURES").is_none(),
                "{variable} is required for strict scalar property validation"
            );
            eprintln!("{variable} unset; skipping scalar property interoperability test");
            continue;
        };
        let mut graph = Graph::load(std::path::Path::new(&dir)).unwrap();
        let name = graph.strings().index_of("first").unwrap() as u32;
        let null_name = graph.strings().index_of("second").unwrap() as u32;
        let annotation = Node {
            id: 17,
            kind: NodeKind::Annotation {
                name,
                class_name: name,
                member_name: name,
                values: vec![(name, AnyValue::Int(7)), (null_name, AnyValue::Null)],
            },
        };
        assert!(matches!(
            node_property(&graph, &annotation, "first"),
            Value::Int(7)
        ));
        for key in [
            "second",
            "generic_type",
            "type_info",
            "__missing_property__",
        ] {
            assert!(node_property(&graph, &annotation, key).is_null());
        }
        let mut declarations = Vec::new();
        for tag in [
            TAG_CALL_SITE_NODE,
            TAG_FIELD_NODE,
            TAG_PARAMETER_NODE,
            TAG_RETURN_NODE,
        ] {
            assert!(
                !graph.ids_by_tag(tag).is_empty(),
                "missing fixture tag {tag}"
            );
            for id in graph.ids_by_tag(tag) {
                let node = graph.node(*id).unwrap();
                let full = node_properties(&graph, &node);
                for (key, expected) in &full {
                    assert_eq!(
                        format!("{:?}", node_property(&graph, &node, key)),
                        format!("{expected:?}"),
                        "{variable}, {tag}, {id}, {key}"
                    );
                }
                assert!(node_property(&graph, &node, "__missing_property__").is_null());
                for key in ["generic_type", "type_info"] {
                    if !full.contains_key(key) {
                        assert!(node_property(&graph, &node, key).is_null());
                    }
                }
                if let NodeKind::CallSite { caller, callee, .. } = &node.kind {
                    for (key, sid) in [
                        ("caller_class", caller.declaring_class),
                        ("callee_class", callee.declaring_class),
                        ("callee_name", callee.name),
                    ] {
                        assert_eq!(
                            node_property(&graph, &node, key).as_str(),
                            Some(graph.str(sid))
                        );
                    }
                } else if full.contains_key("generic_type") {
                    declarations.push(node);
                }
            }
        }
        for tag in [TAG_FIELD_NODE, TAG_PARAMETER_NODE, TAG_RETURN_NODE] {
            assert!(declarations.iter().any(|node| node.tag() == tag));
        }
        // A present table without member bindings and a legacy graph both
        // expose null scalars, while ordinary node identity remains available.
        for absent_table in [false, true] {
            graph.update_declared_types(|table| {
                if absent_table {
                    *table = None;
                } else {
                    let table = table.as_mut().unwrap();
                    table.fields.clear();
                    table.methods.clear();
                }
            });
            for node in &declarations {
                for key in ["generic_type", "type_info", "__missing_property__"] {
                    assert!(node_property(&graph, node, key).is_null());
                }
                assert!(matches!(
                    node_property(&graph, node, "id"),
                    Value::Int(id) if id == i64::from(node.id)
                ));
            }
        }
    }
}

#[test]
fn java_all_five_wire_formats_preserve_full_properties_and_shared_string_queries() {
    let variables = [
        "GRAPHITE_TYPES_V1_FIXTURE",
        "GRAPHITE_TYPES_V2_FIXTURE",
        "GRAPHITE_TYPES_V3_FIXTURE",
        "GRAPHITE_TYPES_V4_FIXTURE",
        "GRAPHITE_TYPES_FIXTURE",
    ];
    let paths = variables
        .iter()
        .map(std::env::var_os)
        .collect::<Option<Vec<_>>>();
    let Some(paths) = paths else {
        assert!(
            std::env::var_os("GRAPHITE_REQUIRE_ALL_TYPES_FIXTURES").is_none(),
            "all five wire fixtures required"
        );
        eprintln!(
            "all five GTY01/02/03/04/05 fixtures unset; skipping shared string interoperability"
        );
        return;
    };
    let temporary = std::env::temp_dir().join(format!(
        "graphite-types-shared-props-{}",
        std::process::id()
    ));
    std::fs::create_dir_all(&temporary).unwrap();
    let expected = Arc::new(Graph::load(std::path::Path::new(&paths[0])).unwrap());
    let mut sources = Vec::new();
    for (i, path) in paths.iter().enumerate() {
        let path = std::path::Path::new(path);
        let source = graphite_storage::GraphSource::open(path).unwrap();
        let raw = source.require("graph.types").unwrap();
        assert_eq!(
            i32::from_be_bytes(raw[..4].try_into().unwrap()),
            0x47545901 + i as i32
        );
        for packed in [false, true] {
            let packed_path = temporary.join(format!("v{}.graphite", i + 1));
            let actual_path = if packed {
                graphite_storage::container::pack(path, &packed_path).unwrap();
                packed_path.as_path()
            } else {
                path
            };
            let graph = Arc::new(Graph::load(actual_path).unwrap());
            assert_complete_declared_graph_equivalence(&expected, &graph);
            assert_eq!(graph.strings().serialized_digest().is_some(), i >= 2);
            sources.push(Source {
                id: Arc::from(format!(
                    "v{}-{}",
                    i + 1,
                    if packed { "packed" } else { "directory" }
                )),
                graph,
            });
        }
    }
    let executor = Executor::new(sources, true);
    let fields=executor.execute("MATCH (f:FieldNode) WHERE f.name = 'first' RETURN f.graphId AS source, f.generic_type AS declared, f.type_info AS info ORDER BY source",None).unwrap();
    let source_ids = [
        "v1-directory",
        "v1-packed",
        "v2-directory",
        "v2-packed",
        "v3-directory",
        "v3-packed",
        "v4-directory",
        "v4-packed",
        "v5-directory",
        "v5-packed",
    ];
    assert_eq!(fields.rows.len(), 10);
    for (row, expected_source) in fields.rows.iter().zip(source_ids) {
        assert_eq!(row["source"].as_str(), Some(expected_source));
        let Value::List(ids) = &row[crate::engine::INTERNAL_PROVENANCE_KEY] else {
            panic!("source provenance required");
        };
        assert_eq!(ids.len(), 1);
        assert_eq!(ids[0].as_str(), Some(expected_source));
        assert_eq!(
            row["declared"].as_str(),
            Some("java.util.List<java.lang.String>")
        );
        let Value::Map(info) = &row["info"] else {
            panic!("full type map required")
        };
        let Value::Map(expected_info) = &fields.rows[0]["info"] else {
            panic!("full type map required")
        };
        assert_eq!(ordered_values(info), ordered_values(expected_info));
    }
    for query in [
        "MATCH (n:ParameterNode) RETURN n.graphId AS source, n.generic_type AS declared, n.type_info AS info",
        "MATCH (n:ReturnNode) RETURN n.graphId AS source, n.generic_type AS declared, n.type_info AS info",
        "MATCH (m:Method) RETURN m.graphId AS source, m.generic_return_type AS result, m.generic_parameter_types AS parameters, m.type_parameters AS formals, m.return_type_info AS resultInfo, m.parameter_type_info AS parameterInfo",
    ] {
        let result=executor.execute(query,None).unwrap();
        let mut groups=std::collections::BTreeMap::<String,Vec<String>>::new();
        for mut row in result.rows {
            let source=row.shift_remove("source").unwrap();
            let Value::List(ids)=row.shift_remove(crate::engine::INTERNAL_PROVENANCE_KEY).unwrap() else { panic!("source provenance required"); };
            assert_eq!(ids.len(),1);
            assert_eq!(ids[0].as_str(),source.as_str());
            groups.entry(source.as_str().unwrap().to_owned()).or_default().push(format!("{:?}",ordered_values(&row)));
        }
        assert_eq!(groups.keys().map(String::as_str).collect::<Vec<_>>(),source_ids, "{query}");
        for rows in groups.values_mut() {rows.sort();}
        let first=groups.values().next().unwrap();
        assert!(!first.is_empty());
        for rows in groups.values() {assert_eq!(rows,first,"{query}");}
    }
    drop(executor);
    std::fs::remove_dir_all(temporary).unwrap();
}
