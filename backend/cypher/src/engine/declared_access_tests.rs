use super::*;
use graphite_storage::Graph;

fn fixture() -> Option<Graph> {
    let path = std::env::var_os("GRAPHITE_TYPES_FIXTURE")
        .or_else(|| std::env::var_os("GRAPHITE_INDEX_FIXTURE"));
    let Some(path) = path else {
        assert!(std::env::var_os("GRAPHITE_REQUIRE_ALL_TYPES_FIXTURES").is_none());
        eprintln!("No graph fixture configured; declared access integration requires one");
        return None;
    };
    // Loading must not read the declaration state; the tests check that it stays unread.
    Some(Graph::load(std::path::Path::new(&path)).unwrap())
}

/// Only a query that consults declarations pays the table's first read: a CallSite aggregation,
/// a projection of a CallSite scalar through SKIP, and a constant count leave `graph.types`
/// unread (an aggregation over CallSite nodes used to initialise the whole table).
#[test]
fn queries_outside_declarations_leave_the_table_unread() {
    let Some(graph) = fixture() else { return };
    let graph = Arc::new(graph);
    assert!(!graph.declared_types_initialized());
    let ex = Executor::single("fixture", graph.clone());
    for query in [
        "MATCH (n:CallSiteNode) RETURN labels(n), count(*)",
        "MATCH (n:CallSiteNode) RETURN n.caller_class SKIP 10 LIMIT 5",
        "MATCH (n:CallSiteNode) RETURN n LIMIT 3",
        "MATCH (n:CallSiteNode) RETURN keys(n) LIMIT 1",
        "MATCH (n:CallSiteNode) WHERE n.callee_name CONTAINS \"get\" RETURN count(*)",
    ] {
        ex.execute(query, None)
            .unwrap_or_else(|e| panic!("{query}: {e}"));
        assert!(
            !graph.declared_types_initialized(),
            "{query} read the declared table"
        );
    }
    ex.execute("MATCH (n:FieldNode) RETURN n.generic_type LIMIT 1", None)
        .unwrap();
    assert!(graph.declared_types_initialized());
}

/// A table that fails to read after a successful load (an internal metadata digest mismatch)
/// is a query error for every query that consults it, never a null answer; queries that do
/// not consult it keep answering.
#[test]
fn an_unreadable_table_fails_the_queries_that_consult_it() {
    let Some(graph) = fixture() else { return };
    let graph = Arc::new(graph.with_declared_types_error("metadata digest mismatch"));
    let ex = Executor::single("fixture", graph);
    for query in [
        "MATCH (n:FieldNode) RETURN n.generic_type LIMIT 1",
        "MATCH (n:ParameterNode) RETURN n.type_info LIMIT 1",
        "MATCH (n:FieldNode) RETURN n LIMIT 1",
        "MATCH (n:ReturnNode) RETURN keys(n) LIMIT 1",
        "MATCH (n:FieldNode) WHERE n.type_info IS NULL RETURN count(*)",
        "MATCH (n:FieldNode) RETURN labels(n), count(*)",
        "MATCH (m:Method) RETURN m.generic_return_type LIMIT 1",
        "MATCH (m:Method) RETURN m LIMIT 1",
    ] {
        let error = ex.execute(query, None).err().map(|e| e.to_string());
        assert!(
            error
                .as_deref()
                .is_some_and(|e| e.contains("declared types unreadable")
                    && e.contains("metadata digest mismatch")),
            "{query}: {error:?}"
        );
    }
    for query in [
        "MATCH (n:CallSiteNode) RETURN count(*)",
        "MATCH (n:CallSiteNode) RETURN n.caller_class SKIP 10 LIMIT 5",
        "MATCH (n:CallSiteNode) RETURN n LIMIT 2",
    ] {
        ex.execute(query, None)
            .unwrap_or_else(|e| panic!("{query}: {e}"));
    }
}
