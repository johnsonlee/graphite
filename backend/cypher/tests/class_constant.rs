//! Class literals (`ClassConstant`, record tag 16): written by the importer, loaded from a
//! directory and a packed container, and queried by label, `value` predicate, map and dataflow.
use graphite_cypher::engine::{Executor, Source};
use graphite_cypher::Value;
use graphite_storage::columns::raw_string_field;
use graphite_storage::node::{
    class_literal_type_name, is_constant_tag, NodeKind, CONSTANT_TAGS, TAG_CLASS_CONSTANT,
    TAG_COUNT, TAG_STRING_CONSTANT,
};
use graphite_storage::Graph;
use serde_json::{json, Value as Json};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

struct Temp(PathBuf);

impl Temp {
    fn new() -> Self {
        static SEQ: AtomicU64 = AtomicU64::new(0);
        let p = std::env::temp_dir().join(format!(
            "graphite-class-constant-{}-{}",
            std::process::id(),
            SEQ.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir_all(&p).unwrap();
        Self(p)
    }
}

impl Drop for Temp {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

/// `Class<?> type = Payload.class; Registry.register(type)`, plus `Payload[].class`,
/// `int[][].class` and a string that spells Payload's descriptor.
fn save(dir: &Path) {
    let caller = json!({"declaringClass":"sample.Setup","name":"init","parameterTypes":[],"returnType":"void"});
    let callee = json!({"declaringClass":"sample.Registry","name":"register","parameterTypes":["java.lang.Class"],"returnType":"void"});
    let input = json!({"version":1,"methods":[caller, callee],"nodes":[
        {"id":0,"kind":"ClassConstant","value":"Lsample/Payload;"},
        {"id":1,"kind":"ClassConstant","value":"[Lsample/Payload;"},
        {"id":2,"kind":"ClassConstant","value":"[[I"},
        {"id":3,"kind":"StringConstant","value":"Lsample/Payload;"},
        {"id":4,"kind":"LocalVariable","name":"type","varType":"java.lang.Class","method":caller},
        {"id":5,"kind":"CallSite","caller":caller,"callee":callee,"line":7,"receiver":null,"arguments":[4]}],
        "edges":[
        {"from":0,"to":4,"kind":"ASSIGN"},
        {"from":4,"to":5,"kind":"PARAMETER_PASS"}]});
    graphite_storage::interchange::import_json(input.to_string().as_bytes(), dir).unwrap();
}

fn loaded(temp: &Temp) -> Vec<(&'static str, Arc<Graph>)> {
    let dir = temp.0.join("graph");
    save(&dir);
    let packed = temp.0.join("graph.graphite");
    graphite_storage::container::pack(&dir, &packed).unwrap();
    vec![
        ("directory", Arc::new(Graph::load(&dir).unwrap())),
        ("container", Arc::new(Graph::load(&packed).unwrap())),
    ]
}

fn json_of(value: &Value) -> Json {
    match value {
        Value::Null => Json::Null,
        Value::Str(value) => json!(value.as_ref()),
        Value::Int(value) => json!(value),
        Value::List(values) => Json::Array(values.iter().map(json_of).collect()),
        other => panic!("unexpected value {other:?}"),
    }
}

fn column(graph: &Arc<Graph>, query: &str) -> Vec<Json> {
    let executor = Executor::new(
        vec![Source {
            id: Arc::from("g"),
            graph: graph.clone(),
        }],
        false,
    );
    let result = executor.execute(query, None).unwrap();
    result
        .rows
        .iter()
        .map(|row| {
            let column = &result.columns[0];
            json_of(&row[column.as_str()])
        })
        .collect()
}

fn strings(values: &[Json]) -> Vec<&str> {
    values.iter().map(|v| v.as_str().unwrap()).collect()
}

#[test]
fn tag_16_is_a_constant_kind_of_its_own() {
    assert_eq!(TAG_CLASS_CONSTANT, 16);
    assert_eq!(TAG_COUNT, 17);
    assert!(is_constant_tag(TAG_CLASS_CONSTANT));
    assert!(CONSTANT_TAGS.contains(&TAG_CLASS_CONSTANT));
    assert!(!is_constant_tag(15));
    assert_eq!(
        raw_string_field(TAG_CLASS_CONSTANT, "value"),
        Some(graphite_storage::columns::RAW_STRING_FIELDS.len() - 1),
        "the descriptor is a raw string column of its own"
    );
    assert_eq!(
        class_literal_type_name("Lsample/Payload;"),
        "sample.Payload"
    );
    assert_eq!(
        class_literal_type_name("[Lsample/Payload;"),
        "sample.Payload[]"
    );
    assert_eq!(class_literal_type_name("[[I"), "int[][]");
}

#[test]
fn save_and_load_keep_the_descriptor_apart_from_an_equal_string() {
    let temp = Temp::new();
    for (mode, graph) in loaded(&temp) {
        let node = graph.node(0).unwrap();
        let NodeKind::ClassConstant(value) = node.kind else {
            panic!("{mode}: class literal expected, got {:?}", node.kind)
        };
        assert_eq!(graph.str(value), "Lsample/Payload;", "{mode}");
        assert!(node.is_constant(), "{mode}");
        assert_eq!(node.type_name(), "ClassConstant", "{mode}");
        let NodeKind::StringConstant(text) = graph.node(3).unwrap().kind else {
            panic!("{mode}: string expected")
        };
        assert_eq!(
            text, value,
            "{mode}: one string table entry, two constant kinds"
        );
        assert_eq!(graph.ids_by_tag(TAG_CLASS_CONSTANT), &[0, 1, 2], "{mode}");
        assert_eq!(graph.ids_by_tag(TAG_STRING_CONSTANT), &[3], "{mode}");
    }
}

#[test]
fn labels_and_properties() {
    let temp = Temp::new();
    for (mode, graph) in loaded(&temp) {
        assert_eq!(
            strings(&column(
                &graph,
                "MATCH (n:ClassConstant) RETURN n.value AS v ORDER BY v"
            )),
            ["Lsample/Payload;", "[Lsample/Payload;", "[[I"],
            "{mode}"
        );
        let labels = column(
            &graph,
            "MATCH (n:classconstant) WHERE n.id = 2 RETURN labels(n) AS l",
        );
        assert_eq!(labels, vec![json!(["ClassConstant", "Constant"])], "{mode}");
        assert_eq!(
            strings(&column(
                &graph,
                "MATCH (n) WHERE n.id = 0 RETURN n.type AS t"
            )),
            ["ClassConstant"],
            "{mode}"
        );
        assert_eq!(
            column(&graph, "MATCH (n:Constant) RETURN count(n) AS c"),
            vec![json!(4)],
            "{mode}: three class literals and the string"
        );
        assert_eq!(
            strings(&column(
                &graph,
                "MATCH (n:ClassConstant {value: '[[I'}) RETURN keys(n) AS k UNWIND k AS key RETURN key ORDER BY key"
            )),
            ["id", "value"],
            "{mode}"
        );
        assert_eq!(
            column(
                &graph,
                "MATCH (n:ClassConstant {value: '[[I'}) RETURN n.name AS name"
            ),
            vec![Json::Null],
            "{mode}"
        );
    }
}

#[test]
fn value_predicates_compare_the_descriptor_as_text() {
    let temp = Temp::new();
    for (mode, graph) in loaded(&temp) {
        let ids = |query: &str| -> Vec<i64> {
            column(&graph, query)
                .iter()
                .map(|v| {
                    v.as_i64()
                        .unwrap_or_else(|| panic!("{mode}: id expected, got {v}"))
                })
                .collect()
        };
        assert_eq!(
            ids("MATCH (n:ClassConstant) WHERE n.value = 'Lsample/Payload;' RETURN n.id AS id"),
            [0],
            "{mode}"
        );
        assert_eq!(
            ids("MATCH (n:StringConstant {value: 'Lsample/Payload;'}) RETURN n.id AS id"),
            [3],
            "{mode}: the string is not a class literal"
        );
        assert_eq!(
            ids("MATCH (n:ClassConstant) WHERE n.value STARTS WITH '[' RETURN n.id AS id ORDER BY id"),
            [1, 2],
            "{mode}"
        );
        assert_eq!(
            ids("MATCH (n:ClassConstant) WHERE n.value IN ['[[I', 'Lmissing;'] RETURN n.id AS id"),
            [2],
            "{mode}"
        );
        assert_eq!(
            ids("MATCH (n:ClassConstant) WHERE n.value CONTAINS 'Payload' RETURN n.id AS id ORDER BY id"),
            [0, 1],
            "{mode}"
        );
        assert_eq!(
            ids(
                "MATCH (n:ClassConstant) WHERE n.value ENDS WITH ';' RETURN n.id AS id ORDER BY id"
            ),
            [0, 1],
            "{mode}"
        );
        assert_eq!(
            ids("MATCH (n:Constant) WHERE n.value = 'Lsample/Payload;' RETURN n.id AS id ORDER BY id"),
            [0, 3],
            "{mode}: the Constant label covers both kinds"
        );
        assert_eq!(
            ids("MATCH (n:ClassConstant) WHERE toLower(n.value) = '[[i' RETURN n.id AS id"),
            [2],
            "{mode}"
        );
    }
}

#[test]
fn a_class_literal_flows_to_the_call_argument() {
    let temp = Temp::new();
    for (mode, graph) in loaded(&temp) {
        assert_eq!(
            strings(&column(
                &graph,
                "MATCH (c:ClassConstant)-[:DATAFLOW*1..2]->(cs:CallSiteNode {callee_name: 'register'}) RETURN c.value AS v"
            )),
            ["Lsample/Payload;"],
            "{mode}"
        );
        assert_eq!(
            column(
                &graph,
                "MATCH (c:ClassConstant)-[:DATAFLOW]->(l:LocalVariable) WHERE c.value STARTS WITH 'L' RETURN count(*) AS n"
            ),
            vec![json!(1)],
            "{mode}"
        );
    }
}

/// The JVM writer's graphs (`ClassConstantPersistenceTest`, both node builders) read natively,
/// from the directory and from a packed container.
#[test]
fn jvm_written_class_literals_read_natively() {
    let Some(root) = std::env::var_os("GRAPHITE_CLASS_CONSTANT_FIXTURE") else {
        assert!(
            std::env::var_os("GRAPHITE_REQUIRE_CLASS_CONSTANT_FIXTURE").is_none(),
            "JVM class literal fixture is required"
        );
        eprintln!("GRAPHITE_CLASS_CONSTANT_FIXTURE unset; skipping JVM interoperability");
        return;
    };
    let temp = Temp::new();
    for builder in ["default", "mmap"] {
        let dir = Path::new(&root).join(builder);
        let packed = temp.0.join(format!("{builder}.graphite"));
        graphite_storage::container::pack(&dir, &packed).unwrap();
        for path in [dir.as_path(), packed.as_path()] {
            let graph = Arc::new(Graph::load(path).unwrap());
            let mode = path.display().to_string();
            assert_eq!(
                strings(&column(
                    &graph,
                    "MATCH (n:ClassConstant) RETURN n.value AS v ORDER BY v"
                )),
                ["Lfixture/Setup$Payload;", "[Lfixture/Setup$Payload;", "[[I"],
                "{mode}"
            );
            assert_eq!(
                strings(&column(
                    &graph,
                    "MATCH (c:ClassConstant)-[:DATAFLOW*1..3]->(cs:CallSiteNode {callee_name: 'register'}) WHERE c.value STARTS WITH 'L' RETURN DISTINCT c.value AS v"
                )),
                ["Lfixture/Setup$Payload;"],
                "{mode}"
            );
            assert_eq!(
                strings(&column(
                    &graph,
                    "MATCH (n:Constant) WHERE n.value IN ['Lfixture/Setup$Payload;'] RETURN n.type AS t ORDER BY t"
                )),
                ["ClassConstant", "StringConstant"],
                "{mode}: the literal and the equal string stay two kinds"
            );
        }
    }
}
