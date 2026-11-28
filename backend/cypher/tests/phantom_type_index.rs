//! A type index may name ids the graph does not hold (a hand-made index, or a flipped bit
//! the manifest's size check cannot see). Queries skip them, as the id candidate paths
//! already do, instead of indexing past the per-node arrays sized by the offset index.
use graphite_cypher::engine::Executor;
use graphite_cypher::materialize::materialize;
use graphite_storage::node::TAG_STRING_CONSTANT;
use graphite_storage::Graph;
use serde_json::json;
use std::sync::Arc;

#[test]
fn edge_and_label_aggregates_skip_ids_past_the_offset_index() {
    let dir = std::env::temp_dir().join(format!("graphite-phantom-ids-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let graph = json!({
        "version": 1,
        "methods": [],
        "nodes": [
            {"id": 0, "kind": "StringConstant", "value": "a"},
            {"id": 1, "kind": "StringConstant", "value": "b"}
        ],
        "edges": [{"from": 0, "to": 1, "kind": "ASSIGN"}]
    });
    graphite_storage::interchange::import_json(graph.to_string().as_bytes(), &dir).unwrap();
    // One tag, three ids: the two nodes and one past the offset index.
    let mut index = (graphite_storage::io::MAGIC_TYPEINDEX | 3)
        .to_be_bytes()
        .to_vec();
    index.extend(1i32.to_be_bytes());
    index.push(TAG_STRING_CONSTANT);
    index.extend(3i32.to_be_bytes());
    index.extend(21i64.to_be_bytes());
    for id in [0u32, 1, u32::MAX] {
        index.extend(id.to_be_bytes());
    }
    std::fs::write(dir.join("graph.typeindex"), index).unwrap();
    // A hand-made index is a graph written before manifests.
    std::fs::remove_file(dir.join("graph.manifest")).unwrap();
    let executor = Executor::single("g", Arc::new(Graph::load(&dir).unwrap()));
    let rows = |query: &str| -> Vec<serde_json::Value> {
        let result = executor.execute(query, None).unwrap();
        result
            .rows
            .iter()
            .map(|row| {
                serde_json::Value::Array(
                    result
                        .columns
                        .iter()
                        .map(|c| materialize(&row[c], &executor))
                        .collect(),
                )
            })
            .collect()
    };
    assert_eq!(
        rows("MATCH (a)-[r]->(b) RETURN type(r) AS t, labels(a)[0] AS l, count(*) AS n"),
        vec![json!(["DATAFLOW", "StringConstant", 1])]
    );
    // A label scan keeps the phantom as a row without properties, the contract the id
    // candidate paths already pin (`..._preserve_unsorted_duplicates_phantoms_...`).
    assert_eq!(
        rows("MATCH (n:StringConstant) RETURN n.value AS v ORDER BY v"),
        vec![json!(["a"]), json!(["b"]), json!([null])]
    );
    std::fs::remove_dir_all(&dir).unwrap();
}
