use super::Executor;
use crate::{property_graph::PropertyGraph, semantics::value_key, Value};
use std::sync::Arc;

fn session() -> (Arc<PropertyGraph>, Executor) {
    let graph = Arc::new(PropertyGraph::new());
    (graph.clone(), Executor::property_graph(graph))
}

#[test]
fn creates_arbitrary_labels_properties_and_reads_through_the_normal_pipeline() {
    let (graph, ex) = session();
    let created = ex.execute("CREATE (a:Person {name:'Alice', absent:null, id:42})-[:KNOWS {since:2020}]->(b:Person {name:'Bob'})", None).unwrap();
    assert!(created.rows.is_empty());
    assert!(created.columns.is_empty());
    let result = ex.execute("MATCH (a:Person)-[r:KNOWS]->(b:Person) RETURN a.name AS a, b.name AS b, type(r) AS t, r.since AS s, labels(a) AS labels, a.id AS p, id(a) AS identity", None).unwrap();
    assert_eq!(result.rows.len(), 1);
    let row = &result.rows[0];
    assert_eq!(row["a"].as_str(), Some("Alice"));
    assert_eq!(row["b"].as_str(), Some("Bob"));
    assert_eq!(row["t"].as_str(), Some("KNOWS"));
    assert!(matches!(row["s"], Value::Int(2020)));
    assert_eq!(row["labels"].as_list().unwrap()[0].as_str(), Some("Person"));
    assert!(matches!(row["p"], Value::Int(42)));
    assert!(matches!(row["identity"], Value::Int(0)));
    let snapshot = graph.snapshot();
    assert_eq!(snapshot.nodes.len(), 2);
    assert_eq!(snapshot.relationships.len(), 1);
    assert!(!snapshot.nodes[0].properties.contains_key("absent"));
    // A second executor over the same store sees setup queries, without a private test engine.
    let second = Executor::property_graph(graph);
    assert!(matches!(
        second
            .execute("MATCH(n) RETURN count(*) AS c", None)
            .unwrap()
            .rows[0]["c"],
        Value::Int(2)
    ));
}

#[test]
fn parallel_relationships_and_paths_keep_distinct_stable_identities() {
    let (_, ex) = session();
    ex.execute(
        "CREATE (a:A),(b:B),(a)-[:R {k:1}]->(b),(a)-[:R {k:1}]->(b)",
        None,
    )
    .unwrap();
    let result = ex
        .execute(
            "MATCH p=(a:A)-[r:R]->(b:B) RETURN r, p, relationships(p) AS rs",
            None,
        )
        .unwrap();
    assert_eq!(result.rows.len(), 2);
    assert_ne!(
        value_key(&result.rows[0]["r"]),
        value_key(&result.rows[1]["r"])
    );
    assert_ne!(
        value_key(&result.rows[0]["p"]),
        value_key(&result.rows[1]["p"])
    );
    for row in &result.rows {
        assert_eq!(
            value_key(&row["r"]),
            value_key(&row["rs"].as_list().unwrap()[0])
        );
        let materialized = crate::materialize::materialize(&row["r"], &ex);
        assert_eq!(materialized["type"], "R");
        assert_eq!(materialized["properties"]["k"], 1);
    }
    let result = ex
        .execute(
            "MATCH p=(a:A)-[r:R]->(b:B) RETURN count(DISTINCT r) AS r, count(DISTINCT p) AS p",
            None,
        )
        .unwrap();
    assert!(matches!(result.rows[0]["r"], Value::Int(2)));
    assert!(matches!(result.rows[0]["p"], Value::Int(2)));
}

#[test]
fn variable_paths_are_relationship_isomorphic_and_can_repeat_nodes() {
    let (_, ex) = session();
    ex.execute(
        "CREATE (a:A),(b:B),(c:C),(a)-[:R]->(b),(b)-[:R]->(c),(c)-[:R]->(a)",
        None,
    )
    .unwrap();
    let result = ex
        .execute(
            "MATCH p=(a:A)-[:R*0..4]->(b) RETURN length(p) AS l ORDER BY l",
            None,
        )
        .unwrap();
    assert_eq!(
        result
            .rows
            .iter()
            .map(|r| match r["l"] {
                Value::Int(n) => n,
                _ => panic!("length"),
            })
            .collect::<Vec<_>>(),
        vec![0, 1, 2, 3]
    );
}

#[test]
fn failed_queries_rollback_and_program_graph_executors_stay_immutable() {
    let (graph, ex) = session();
    ex.execute("CREATE (:Before {name:'kept'})", None).unwrap();
    assert!(ex
        .execute("CREATE (:Temporary) RETURN missingFunction()", None)
        .is_err());
    let snapshot = graph.snapshot();
    assert_eq!(snapshot.nodes.len(), 1);
    assert_eq!(snapshot.nodes[0].labels, vec!["Before"]);
    assert_eq!(snapshot.nodes[0].properties["name"].as_str(), Some("kept"));
    let immutable = Executor::new(vec![], false);
    assert!(immutable
        .execute("CREATE (:A)", None)
        .unwrap_err()
        .to_string()
        .contains("graph is immutable"));
}

#[test]
fn property_relationship_identity_does_not_enlarge_program_runtime_values() {
    // The existing program-graph Value is 24 bytes on supported 64-bit targets.
    // Keep generic relationship IDs in the existing enum payload budget.
    assert_eq!(std::mem::size_of::<Value>(), 24);
    assert_eq!(std::mem::size_of::<Option<std::num::NonZeroU32>>(), 4);
}

#[test]
fn self_loop_path_orientation_is_recorded_even_when_endpoints_are_equal() {
    let (_, ex) = session();
    ex.execute("CREATE (a:A)-[:R]->(a)", None).unwrap();
    for (query, direction) in [
        ("MATCH p=(a:A)-[:R]->(a) RETURN p", 1),
        ("MATCH p=(a:A)<-[:R]-(a) RETURN p", -1),
    ] {
        let result = ex.execute(query, None).unwrap();
        assert_eq!(result.rows.len(), 1);
        let Value::Path(path) = &result.rows[0]["p"] else {
            panic!("path")
        };
        assert_eq!(path.nodes, vec![0, 0]);
        assert_eq!(
            path.property_path.as_ref().unwrap().directions,
            vec![direction]
        );
        assert_eq!(
            path.property_path.as_ref().unwrap().relationship_ids.len(),
            1
        );
    }
}

#[test]
fn invalid_recursive_or_mixed_properties_are_rejected_transactionally() {
    let (graph, ex) = session();
    for query in [
        "CREATE (a:A) CREATE (:B {recursive:a})",
        "CREATE (:A {map:{x:1}})",
        "CREATE (:A {nested:[[1]]})",
        "CREATE (:A {mixed:[true, 1]})",
        "CREATE (:A {nulls:[1, null]})",
    ] {
        assert!(
            ex.execute(query, None)
                .unwrap_err()
                .to_string()
                .contains("Property values"),
            "{query}"
        );
        assert!(graph.snapshot().nodes.is_empty(), "{query}");
        assert!(graph.snapshot().relationships.is_empty(), "{query}");
    }
    ex.execute(
        "CREATE (:A {names:['a','b'], numbers:[1,2], present:true, absent:null})",
        None,
    )
    .unwrap();
    let snapshot = graph.snapshot();
    assert_eq!(snapshot.nodes.len(), 1);
    assert_eq!(
        snapshot.nodes[0].properties["names"]
            .as_list()
            .unwrap()
            .len(),
        2
    );
    assert!(!snapshot.nodes[0].properties.contains_key("absent"));
}

#[test]
fn native_set_remove_and_map_replacement_preserve_snapshot_values() {
    let (graph, ex) = session();
    ex.execute("CREATE (a:A {x:1, keep:'old'})-[r:R {x:1}]->(b:B)", None)
        .unwrap();
    let result=ex.execute("MATCH (a:A)-[r:R]->(b:B) SET a.x=2, a += {added:true, keep:null}, a:C:D, r.x=3 RETURN a.x AS x, a.added AS added, a.keep AS removed, r.x AS rx",None).unwrap();
    assert!(matches!(result.rows[0]["x"], Value::Int(2)));
    assert!(matches!(result.rows[0]["rx"], Value::Int(3)));
    assert!(matches!(result.rows[0]["added"], Value::Bool(true)));
    assert!(result.rows[0]["removed"].is_null());
    assert_eq!(graph.snapshot().nodes[0].labels, vec!["A", "C", "D"]);
    ex.execute(
        "MATCH (a:A) REMOVE a.x, a:C:D SET a = {name:'new', dropped:null}",
        None,
    )
    .unwrap();
    let snapshot = graph.snapshot();
    assert_eq!(snapshot.nodes[0].labels, vec!["A"]);
    assert_eq!(snapshot.nodes[0].properties.len(), 1);
    assert_eq!(snapshot.nodes[0].properties["name"].as_str(), Some("new"));
    assert!(ex
        .execute("MATCH (a:A) SET a.name='broken', a.recursive=a", None)
        .is_err());
    assert_eq!(
        graph.snapshot().nodes[0].properties["name"].as_str(),
        Some("new")
    );
    ex.execute(
        "OPTIONAL MATCH (n:Missing) SET n.x=1, n += {y:2}, n:A REMOVE n.x, n:A",
        None,
    )
    .unwrap();
    assert_eq!(graph.snapshot().nodes.len(), 2);
}

#[test]
fn native_merge_matches_whole_patterns_and_chooses_actions_per_row() {
    let (graph, ex) = session();
    let result=ex.execute("UNWIND [1,1,2] AS id MERGE (n:Person {id:id}) ON CREATE SET n.created=true, n.seen=1 ON MATCH SET n.seen=n.seen+1 RETURN n.id AS id, n.seen AS seen ORDER BY id",None).unwrap();
    assert_eq!(result.rows.len(), 3);
    let snapshot = graph.snapshot();
    assert_eq!(snapshot.nodes.len(), 2);
    assert!(matches!(
        snapshot.nodes[0].properties["seen"],
        Value::Int(2)
    ));
    assert!(matches!(
        snapshot.nodes[1].properties["seen"],
        Value::Int(1)
    ));
    assert!(matches!(
        snapshot.nodes[0].properties["created"],
        Value::Bool(true)
    ));
    ex.execute("MATCH (a:Person {id:1}) MERGE (a)-[r:R {name:'known'}]->(b:Target {id:4}) ON CREATE SET r.created=true",None).unwrap();
    ex.execute("MATCH (a:Person {id:1}) MERGE (a)-[r:R {name:'known'}]->(b:Target {id:4}) ON MATCH SET r.matched=true",None).unwrap();
    let snapshot = graph.snapshot();
    assert_eq!(snapshot.nodes.len(), 3);
    assert_eq!(snapshot.relationships.len(), 1);
    assert!(matches!(
        snapshot.relationships[0].properties["created"],
        Value::Bool(true)
    ));
    assert!(matches!(
        snapshot.relationships[0].properties["matched"],
        Value::Bool(true)
    ));
    assert!(ex.execute("MERGE (:Person {id:null})", None).is_err());
    assert_eq!(graph.snapshot().nodes.len(), 3);
}

#[test]
fn native_delete_validates_whole_clause_and_detach_deletes_incident_edges() {
    let (graph, ex) = session();
    ex.execute("CREATE (a:A {x:1})-[r:R {x:2}]->(b:B)", None)
        .unwrap();
    assert!(ex
        .execute("MATCH(a:A) SET a.x=9 DELETE a", None)
        .unwrap_err()
        .to_string()
        .contains("relationships"));
    assert_eq!(graph.snapshot().nodes.len(), 2);
    assert!(matches!(
        graph.snapshot().nodes[0].properties["x"],
        Value::Int(1)
    ));
    // Validate all targets before deletion, so node-before-relationship syntax is safe.
    ex.execute("MATCH(a:A)-[r:R]->(b:B) DELETE a,r", None)
        .unwrap();
    assert_eq!(graph.snapshot().nodes.len(), 1);
    assert!(graph.snapshot().relationships.is_empty());
    ex.execute("CREATE (a:A)-[:R]->(b:C)", None).unwrap();
    ex.execute("MATCH(a:A) DETACH DELETE a", None).unwrap();
    assert_eq!(graph.snapshot().nodes.len(), 2);
    assert!(graph.snapshot().relationships.is_empty());
    ex.execute("OPTIONAL MATCH(n:Missing) DELETE n RETURN n", None)
        .unwrap();
    let before = graph.snapshot();
    assert!(ex.execute("DELETE 42", None).is_err());
    assert_eq!(graph.snapshot().nodes.len(), before.nodes.len());
    ex.execute("MATCH(n) DELETE n", None).unwrap();
    assert!(graph.snapshot().nodes.is_empty());
}

#[test]
fn all_mutations_remain_rejected_by_default_program_graph_execution() {
    let ex = Executor::new(vec![], false);
    for query in [
        "MERGE (:A)",
        "SET n.x=1",
        "REMOVE n.x",
        "DELETE null",
        "DETACH DELETE null",
    ] {
        assert!(
            ex.execute(query, None)
                .unwrap_err()
                .to_string()
                .contains("graph is immutable"),
            "{query}"
        );
    }
}
