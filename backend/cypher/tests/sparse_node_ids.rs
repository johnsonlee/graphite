//! The node-id contract of the JSON importer: ids are dense and ordered from zero, and a
//! sparse id is refused rather than paid for. The per-node offset index is sized by the
//! largest id (8 bytes per id), so a dense numbering is what keeps it proportional to the
//! node count; the JVM writer, which does accept sparse ids, pins the same sizing in
//! `SparseNodeIdTest`.
use serde_json::json;

#[test]
fn the_importer_refuses_sparse_ids_and_sizes_the_offset_index_by_the_largest_id() {
    let dir = std::env::temp_dir().join(format!("graphite-sparse-ids-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let node = |id: u32| json!({"id": id, "kind": "StringConstant", "value": format!("v{id}")});
    let sparse = json!({"version": 1, "methods": [], "edges": [], "nodes": [node(0), node(7)]});
    let err = graphite_storage::interchange::import_json(sparse.to_string().as_bytes(), &dir)
        .unwrap_err()
        .to_string();
    assert!(
        err.contains("node IDs must be dense and ordered from zero"),
        "{err}"
    );
    let dense = json!({"version": 1, "methods": [], "edges": [], "nodes": (0..5).map(node).collect::<Vec<_>>()});
    graphite_storage::interchange::import_json(dense.to_string().as_bytes(), &dir).unwrap();
    let offsets = std::fs::metadata(dir.join("graph.nodeoffsets"))
        .unwrap()
        .len();
    assert_eq!(offsets, 8 + 8 * 5, "a header and one offset per id");
    std::fs::remove_dir_all(&dir).unwrap();
}
