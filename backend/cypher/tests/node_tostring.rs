//! A node prints as the Kotlin map it is: `id`, `type` (the node's kind), then its display
//! properties, where a property named like an earlier key replaces that key's value in
//! place, as `LinkedHashMap.put` does. A local variable's `type` property is its declared
//! type, so it prints `{id=4, type=string, name=message}`, never a second `type` key.
use graphite_cypher::engine::Executor;
use graphite_cypher::tostring::java_to_string;
use graphite_storage::Graph;
use serde_json::json;
use std::sync::Arc;

#[test]
fn a_property_named_like_an_earlier_key_replaces_it_in_place() {
    let dir = std::env::temp_dir().join(format!("graphite-node-tostring-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let method = json!({"declaringClass":"app.Main", "name":"run", "parameterTypes":[], "returnType":"void"});
    let graph = json!({
        "version": 1,
        "methods": [method],
        "nodes": [
            {"id": 0, "kind": "LocalVariable", "name": "message", "varType": "java.lang.String", "method": method},
            {"id": 1, "kind": "StringConstant", "value": "hi"}
        ],
        "edges": [{"from": 1, "to": 0, "kind": "ASSIGN"}]
    });
    graphite_storage::interchange::import_json(graph.to_string().as_bytes(), &dir).unwrap();
    let executor = Executor::single("g", Arc::new(Graph::load(&dir).unwrap()));
    let render = |query: &str| {
        let result = executor.execute(query, None).unwrap();
        let column = &result.columns[0];
        java_to_string(&result.rows[0][column], &executor)
    };
    assert_eq!(
        render("MATCH (n:LocalVariable) RETURN n"),
        "{id=0, type=java.lang.String, name=message}"
    );
    assert_eq!(
        render("MATCH p = (c)-[]->(n:LocalVariable) RETURN p"),
        "[{id=1, type=StringConstant, value=hi}, DataFlowEdge(from=node#1, to=node#0, kind=ASSIGN), \
         {id=0, type=java.lang.String, name=message}]"
    );
    std::fs::remove_dir_all(&dir).unwrap();
}
