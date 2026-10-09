//! Correctness-only JavaProjectLoader → GTY05 → native directory/container parity.
use graphite_cypher::engine::props::node_properties;
use graphite_cypher::engine::{Executor, Source, INTERNAL_PROVENANCE_KEY};
use graphite_cypher::Value;
use graphite_storage::node::{NodeKind, TAG_FIELD_NODE};
use graphite_storage::{Graph, GraphSource, Node};
use serde_json::{json, Value as Json};
use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};
use std::sync::Arc;

const CHILD: &str = "fixture.InheritedFixture$Child";
const PARENT: &str = "fixture.InheritedFixture$Parent";
const ITEMS: &str = "fixture.InheritedFixture$Items";
const SHADOW: &str = "fixture.InheritedFixture$Shadow";
const NAMES: [&str; 4] = ["names", "value", "shared", "ITEMS"];

#[test]
fn java_inherited_aliases_survive_both_builders_resave_and_native_containers() {
    let Some(root) = std::env::var_os("GRAPHITE_INHERITED_TYPES_FIXTURE") else {
        assert!(
            std::env::var_os("GRAPHITE_REQUIRE_INHERITED_TYPES_FIXTURE").is_none(),
            "Java inherited-field fixture is required"
        );
        eprintln!(
            "GRAPHITE_INHERITED_TYPES_FIXTURE unset; skipping explicit Java interoperability"
        );
        return;
    };
    let root = Path::new(&root);
    let temporary = Temporary::new();
    let mut sources = Vec::new();
    for builder in ["default", "mmap"] {
        let mut expected_fields = None;
        let mut expected_types = None;
        for generation in ["original", "resaved"] {
            let directory = root.join(builder).join(generation);
            assert!(
                directory.is_dir(),
                "missing fixture {}",
                directory.display()
            );
            for packed in [false, true] {
                let id = format!(
                    "{builder}-{generation}-{}",
                    if packed { "packed" } else { "directory" }
                );
                let container = temporary.0.join(format!("{id}.graphite"));
                let path = if packed {
                    graphite_storage::container::pack(&directory, &container).unwrap();
                    container.as_path()
                } else {
                    directory.as_path()
                };
                let graph = Arc::new(load_checked(path));
                assert_aliases(&graph);
                let fields = field_snapshot(&graph);
                if let Some(expected) = &expected_fields {
                    assert_eq!(expected, &fields, "all field IDs and full properties: {id}");
                } else {
                    expected_fields = Some(fields);
                }
                let types = format!("{:?}", graph.declared_types().unwrap().to_mutable());
                if let Some(expected) = &expected_types {
                    assert_eq!(expected, &types, "full declaration table: {id}");
                } else {
                    expected_types = Some(types);
                }
                sources.push(Source {
                    id: Arc::from(id),
                    graph,
                });
            }
        }
    }
    assert_query(&sources);
}

fn load_checked(path: &Path) -> Graph {
    let source = GraphSource::open(path).unwrap();
    let raw = source.require("graph.types").unwrap();
    assert_eq!(i32::from_be_bytes(raw[..4].try_into().unwrap()), 0x47545905);
    let graph = Graph::load(path).unwrap();
    assert!(graph.strings().serialized_digest().is_some());
    graph
}

fn find_field(graph: &Graph, owner: &str, expected_name: &str) -> Node {
    let matching: Vec<_> = graph
        .ids_by_tag(TAG_FIELD_NODE)
        .iter()
        .filter_map(|id| graph.node(*id))
        .filter(|node| {
            matches!(&node.kind, NodeKind::Field {declaring_class, name, ..}
            if graph.str(*declaring_class) == owner && graph.str(*name) == expected_name)
        })
        .collect();
    assert_eq!(matching.len(), 1, "{owner}.{expected_name}");
    matching.into_iter().next().unwrap()
}

fn assert_aliases(graph: &Graph) {
    let table = graph.declared_types().unwrap();
    for name in NAMES {
        let owner = if name == "ITEMS" { ITEMS } else { PARENT };
        let descriptor = if name == "value" {
            "Ljava/lang/Object;"
        } else {
            "Ljava/util/List;"
        };
        let alias_id = table.field_type(CHILD, name, descriptor).unwrap();
        let original_id = table.field_type(owner, name, descriptor).unwrap();
        assert_eq!(alias_id, original_id, "exact declaration binding {name}");
        let alias = find_field(graph, CHILD, name);
        let original = find_field(graph, owner, name);
        assert_ne!(
            alias.id, original.id,
            "erased symbolic Child identity remains distinct"
        );
        let alias_props = node_properties(graph, &alias);
        let original_props = node_properties(graph, &original);
        assert_eq!(
            value_json(&alias_props["type"]),
            value_json(&original_props["type"])
        );
        assert_eq!(alias_props["generic_type"].as_str(), Some(rendered(name)));
        assert_eq!(table.render(alias_id), rendered(name));
        assert_eq!(value_json(&alias_props["type_info"]), expected_info(name));
        assert_eq!(
            value_json(&original_props["type_info"]),
            expected_info(name)
        );
        assert_eq!(
            alias_props["static"].as_bool(),
            Some(matches!(name, "shared" | "ITEMS"))
        );
    }
    let shadow = find_field(graph, SHADOW, "names");
    let properties = node_properties(graph, &shadow);
    assert_eq!(
        properties["generic_type"].as_str(),
        Some("java.util.List<java.lang.Integer>")
    );
    assert_eq!(value_json(&properties["type_info"]), expected_info("ITEMS"));
    assert_ne!(
        table.field_type(SHADOW, "names", "Ljava/util/List;"),
        table.field_type(CHILD, "names", "Ljava/util/List;")
    );
}

fn assert_query(sources: &[Source]) {
    let expected_sources: BTreeSet<_> =
        sources.iter().map(|source| source.id.to_string()).collect();
    assert_eq!(expected_sources.len(), 8);
    let executor = Executor::new(sources.to_vec(), true);
    let query = format!("MATCH (f:FieldNode) WHERE f.class = '{CHILD}' RETURN f.graphId AS source, f.id AS id, f.name AS name, f.class AS owner, f.type AS erased, f.static AS static, f.generic_type AS declared, f.type_info AS info ORDER BY source, name");
    let result = executor.execute(&query, None).unwrap();
    assert_eq!(result.rows.len(), 8 * NAMES.len());
    let mut actual = BTreeMap::<String, BTreeSet<String>>::new();
    for row in &result.rows {
        let source = row["source"].as_str().unwrap();
        let name = row["name"].as_str().unwrap();
        assert!(NAMES.contains(&name));
        assert_eq!(row["owner"].as_str(), Some(CHILD));
        assert_eq!(row["declared"].as_str(), Some(rendered(name)));
        assert_eq!(value_json(&row["info"]), expected_info(name));
        assert_eq!(
            row["erased"].as_str(),
            Some(if name == "value" {
                "java.lang.Object"
            } else {
                "java.util.List"
            })
        );
        assert_eq!(
            row["static"].as_bool(),
            Some(matches!(name, "shared" | "ITEMS"))
        );
        assert_eq!(value_json(&row[INTERNAL_PROVENANCE_KEY]), json!([source]));
        let graph = &sources
            .iter()
            .find(|item| item.id.as_ref() == source)
            .unwrap()
            .graph;
        assert_eq!(
            value_json(&row["id"]),
            json!(find_field(graph, CHILD, name).id)
        );
        assert!(actual.entry(source.into()).or_default().insert(name.into()));
    }
    assert_eq!(
        actual.keys().cloned().collect::<BTreeSet<_>>(),
        expected_sources
    );
    for names in actual.values() {
        assert_eq!(
            names,
            &NAMES
                .iter()
                .map(|name| name.to_string())
                .collect::<BTreeSet<_>>()
        );
    }
}

fn rendered(name: &str) -> &'static str {
    match name {
        "names" => "java.util.List<java.lang.String>",
        "value" => "T",
        "shared" => "java.util.List<java.lang.Long>",
        "ITEMS" => "java.util.List<java.lang.Integer>",
        _ => panic!("unexpected field {name}"),
    }
}

fn expected_info(name: &str) -> Json {
    if name == "value" {
        json!({"kind": "variable", "name": "T", "scope": format!("class:{PARENT}"), "arguments": []})
    } else {
        let argument = match name {
            "names" => "java.lang.String",
            "shared" => "java.lang.Long",
            "ITEMS" => "java.lang.Integer",
            _ => panic!("unexpected field {name}"),
        };
        json!({"kind": "class", "name": "java.util.List", "arguments": [
            {"kind": "class", "name": argument, "arguments": []}
        ]})
    }
}

fn field_snapshot(graph: &Graph) -> BTreeMap<u32, String> {
    graph
        .ids_by_tag(TAG_FIELD_NODE)
        .iter()
        .map(|id| {
            let node = graph.node(*id).unwrap();
            (*id, format!("{:?}", node_properties(graph, &node)))
        })
        .collect()
}

fn value_json(value: &Value) -> Json {
    match value {
        Value::Str(value) => json!(value.as_ref()),
        Value::Bool(value) => json!(value),
        Value::Int(value) => json!(value),
        Value::List(values) => Json::Array(values.iter().map(value_json).collect()),
        Value::Map(values) => Json::Object(
            values
                .iter()
                .map(|(key, value)| (key.clone(), value_json(value)))
                .collect(),
        ),
        _ => panic!("unexpected alias property type: {value:?}"),
    }
}

struct Temporary(PathBuf);
impl Temporary {
    fn new() -> Self {
        let nonce = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path = std::env::temp_dir().join(format!(
            "graphite-inherited-interop-{}-{nonce}",
            std::process::id()
        ));
        std::fs::create_dir(&path).unwrap();
        Self(path)
    }
}
impl Drop for Temporary {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}
