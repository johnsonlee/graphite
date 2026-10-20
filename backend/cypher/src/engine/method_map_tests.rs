use super::*;
use crate::engine::{CancelToken, Executor, QueryResult, Source};

fn fixtures() -> Vec<Graph> {
    let names = [
        "GRAPHITE_TYPES_V1_FIXTURE",
        "GRAPHITE_TYPES_V2_FIXTURE",
        "GRAPHITE_TYPES_V3_FIXTURE",
        "GRAPHITE_TYPES_V4_FIXTURE",
        "GRAPHITE_TYPES_FIXTURE",
    ];
    names
        .into_iter()
        .filter_map(|name| {
            let Some(path) = std::env::var_os(name) else {
                assert!(
                    std::env::var_os("GRAPHITE_REQUIRE_ALL_TYPES_FIXTURES").is_none(),
                    "{name} required"
                );
                return None;
            };
            Some(Graph::load(std::path::Path::new(&path)).unwrap())
        })
        .collect()
}

// The old scalar-property path independently rebinds each key. This remains
// callable and checks the full-map optimization without using its new binding.
fn expected(g: &Graph, method: &MethodDesc, graph_id: Option<&str>) -> IndexMap<String, Value> {
    let mut map = IndexMap::new();
    for key in [
        "signature",
        "class",
        "name",
        "parameter_types",
        "return_type",
    ] {
        map.insert(key.to_owned(), method_property(g, method, key, graph_id));
    }
    for key in GENERIC_METHOD_KEYS {
        let value = method_property(g, method, key, graph_id);
        if !value.is_null() {
            map.insert(key.to_owned(), value);
        }
    }
    if let Some(id) = graph_id {
        map.insert("graphId".to_owned(), Value::str(id));
    }
    map
}

#[test]
fn full_method_maps_preserve_all_properties_order_absence_and_empty_lists() {
    for mut graph in fixtures() {
        let mut saw_bound = false;
        let mut saw_empty = false;
        for method in graph.methods() {
            for id in [None, Some("fixture")] {
                let actual = method_properties(&graph, method, id);
                assert_eq!(
                    format!("{actual:?}"),
                    format!("{:?}", expected(&graph, method, id))
                );
                if let Some((_, types)) = declared_method(&graph, method) {
                    saw_bound = true;
                    assert!(GENERIC_METHOD_KEYS
                        .iter()
                        .all(|key| actual.contains_key(*key)));
                    if types.parameters.is_empty() && types.type_parameters.iter().next().is_none()
                    {
                        saw_empty = true;
                        for key in [
                            "generic_parameter_types",
                            "parameter_type_info",
                            "type_parameters",
                        ] {
                            assert!(
                                matches!(actual.get(key), Some(Value::List(items)) if items.is_empty()),
                                "{key}"
                            );
                        }
                    }
                }
            }
        }
        assert!(
            saw_bound && saw_empty,
            "required fixture must exercise present empty projections"
        );
        graph.update_declared_types(|table| {
            table
                .as_mut()
                .unwrap()
                .methods
                .retain(|(_, name, _), _| name.as_ref() != "echo");
        });
        let echo = graph
            .methods()
            .iter()
            .find(|m| graph.str(m.name) == "echo")
            .unwrap();
        let partial = method_properties(&graph, echo, Some("partial"));
        assert!(GENERIC_METHOD_KEYS
            .iter()
            .all(|key| !partial.contains_key(*key)));
        assert_eq!(
            format!("{partial:?}"),
            format!("{:?}", expected(&graph, echo, Some("partial")))
        );
        graph.update_declared_types(|table| *table = None);
        for method in graph.methods() {
            let actual = method_properties(&graph, method, None);
            assert_eq!(
                actual.keys().map(String::as_str).collect::<Vec<_>>(),
                [
                    "signature",
                    "class",
                    "name",
                    "parameter_types",
                    "return_type"
                ]
            );
            assert_eq!(
                format!("{actual:?}"),
                format!("{:?}", expected(&graph, method, None))
            );
            assert!(GENERIC_METHOD_KEYS
                .iter()
                .all(|key| method_property(&graph, method, key, None).is_null()));
        }
    }
}

#[test]
fn full_method_return_and_properties_match_scalar_oracle_across_graphs() {
    let sources: Vec<_> = fixtures()
        .into_iter()
        .enumerate()
        .map(|(index, mut graph)| {
            if index == 1 {
                graph.update_declared_types(|table| *table = None);
            } else if index == 2 {
                graph.update_declared_types(|table| {
                    table
                        .as_mut()
                        .unwrap()
                        .methods
                        .retain(|(_, name, _), _| name.as_ref() != "echo");
                });
            }
            Source {
                id: Arc::from(format!("version-{index}")),
                graph: Arc::new(graph),
            }
        })
        .collect();
    if sources.is_empty() {
        return;
    }
    let ex = Executor::new(sources.clone(), true);
    let result = ex
        .execute(
            "MATCH (m:Method) RETURN m AS method, properties(m) AS props",
            None,
        )
        .unwrap();
    assert_eq!(
        result.rows.len(),
        sources
            .iter()
            .map(|s| s.graph.method_count())
            .sum::<usize>()
    );
    for row in &result.rows {
        let Value::Method(method) = &row["method"] else {
            panic!("full Method reference")
        };
        let source = &sources[method.source as usize];
        let descriptor = &source.graph.methods()[method.index as usize];
        let oracle = expected(&source.graph, descriptor, Some(&source.id));
        let Value::Map(actual) = &row["props"] else {
            panic!("full property map")
        };
        assert_eq!(format!("{actual:?}"), format!("{oracle:?}"));
        let oracle_json = crate::materialize::materialize(&Value::map(oracle), &ex);
        assert_eq!(
            crate::materialize::materialize(&row["method"], &ex),
            oracle_json
        );
        assert_eq!(
            crate::materialize::materialize(&row["props"], &ex),
            oracle_json
        );
        assert_eq!(QueryResult::graph_ids(row), vec![source.id.to_string()]);
    }
    let cancel = CancelToken::new();
    cancel.cancel();
    assert!(Executor::new(sources, true)
        .with_cancel(cancel)
        .execute("MATCH (m:Method) RETURN m, properties(m)", None)
        .is_err());
}
