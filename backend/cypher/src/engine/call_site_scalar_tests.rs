use super::*;
use graphite_storage::node::{NodeKind, CALL_SITE_PROPERTY_NAMES, TAG_CALL_SITE_NODE};

fn fixture() -> Option<Arc<Graph>> {
    let path = std::env::var_os("GRAPHITE_TYPES_FIXTURE")
        .or_else(|| std::env::var_os("GRAPHITE_INDEX_FIXTURE"));
    let Some(path) = path else {
        assert!(std::env::var_os("GRAPHITE_REQUIRE_ALL_TYPES_FIXTURES").is_none());
        eprintln!("No graph fixture configured; CallSite scalar integration requires one");
        return None;
    };
    let graph = Arc::new(Graph::load(std::path::Path::new(&path)).unwrap());
    assert!(!graph.ids_by_tag(TAG_CALL_SITE_NODE).is_empty());
    Some(graph)
}

fn same(actual: Value, expected: Value) {
    assert_eq!(format!("{actual:?}"), format!("{expected:?}"));
}

#[test]
fn call_site_scalar_executor_matches_full_decode_and_complete_projections() {
    let Some(graph) = fixture() else { return };
    let ex = Executor::single("fixture", graph.clone());
    let mut call_sites = 0;
    let mut other_kinds = 0;
    for tag in 0..graphite_storage::node::TAG_COUNT as u8 {
        for &id in graph.ids_by_tag(tag) {
            let decoded = graph.node(id).unwrap();
            let nr = NodeRef { source: 0, id };
            if matches!(decoded.kind, NodeKind::CallSite { .. }) {
                call_sites += 1;
            } else {
                other_kinds += 1;
            }
            for key in CALL_SITE_PROPERTY_NAMES.into_iter().chain([
                "caller_descriptor",
                "callee_signature",
                "ordinal",
                "line",
                "id",
                "missing",
            ]) {
                same(
                    ex.node_property(nr, key),
                    props::node_property(&graph, &decoded, key),
                );
            }
            same(
                Value::map(ex.node_properties(nr)),
                Value::map(props::node_properties(&graph, &decoded)),
            );
            same(
                Value::map(ex.node_display_properties(nr)),
                Value::map(props::node_display_properties(&graph, &decoded)),
            );
            same(
                Value::map(ex.node_result_properties(nr)),
                Value::map(props::node_result_properties(&graph, &decoded)),
            );
            assert_eq!(*ex.node(nr).unwrap(), decoded);
        }
    }
    assert!(call_sites > 0 && other_kinds > 0);
    for key in CALL_SITE_PROPERTY_NAMES {
        assert!(ex
            .node_property(
                NodeRef {
                    source: 0,
                    id: u32::MAX
                },
                key
            )
            .is_null());
    }
}

#[test]
fn call_site_scalar_qualified_sources_and_executor_epochs_do_not_alias() {
    let Some(graph) = fixture() else { return };
    // Separately loaded immutable backings share the same numeric string and node IDs.
    let other = fixture().unwrap();
    let id = graph.ids_by_tag(TAG_CALL_SITE_NODE)[0];
    let ex = Executor::new(
        vec![
            Source {
                id: Arc::from("a"),
                graph: graph.clone(),
            },
            Source {
                id: Arc::from("b"),
                graph: other.clone(),
            },
        ],
        true,
    );
    for source in [0, 1, 1, 0] {
        let nr = NodeRef { source, id };
        let g = if source == 0 { &graph } else { &other };
        let decoded = g.node(id).unwrap();
        ex.node(nr).unwrap(); // Seed the existing full-node cache first.
        for key in CALL_SITE_PROPERTY_NAMES {
            same(
                ex.node_property(nr, key),
                props::node_property(g, &decoded, key),
            );
        }
        assert_eq!(
            ex.node_property(nr, "graphId").as_str(),
            Some(if source == 0 { "a" } else { "b" })
        );
        assert_eq!(
            ex.node_property(nr, "elementId").as_str(),
            Some(format!("{}:{id}", if source == 0 { "a" } else { "b" }).as_str())
        );
    }
    for g in [graph, other] {
        let next = Executor::single("same-name", g.clone());
        for key in CALL_SITE_PROPERTY_NAMES {
            same(
                next.node_property(NodeRef { source: 0, id }, key),
                props::node_property(&g, &g.node(id).unwrap(), key),
            );
        }
    }
}

#[test]
fn call_site_scalar_preserves_scan_work_late_errors_probe_and_cancellation() {
    let Some(graph) = fixture() else { return };
    let id = graph.ids_by_tag(TAG_CALL_SITE_NODE)[0];
    let full = graph.node(id).unwrap();
    let cancel = CancelToken::new();
    cancel.cancel();
    let cancelled = Executor::single("fixture", graph.clone()).with_cancel(cancel);
    for key in CALL_SITE_PROPERTY_NAMES {
        same(
            cancelled.node_property(NodeRef { source: 0, id }, key),
            props::node_property(&graph, &full, key),
        );
    }
    // Property reads own neither scan work nor polling; their caller still does.
    assert_eq!(cancelled.poll.load(Ordering::Relaxed), 0);
    assert!(matches!(cancelled.tick(), Err(CypherError::Cancelled)));
    assert!(matches!(
        cancelled.execute("MATCH (n:CallSite) RETURN n.callee_class", None),
        Err(CypherError::Cancelled)
    ));

    let ex = Executor::single("fixture", graph.clone()).with_probe();
    let query = "MATCH (n:CallSite) WITH n.callee_class AS c WHERE c IS NOT NULL RETURN c";
    let all = ex.execute(query, None).unwrap();
    assert_eq!(all.rows.len(), graph.ids_by_tag(TAG_CALL_SITE_NODE).len());
    assert!(ex.poll.load(Ordering::Relaxed) >= all.rows.len() as u64);
    let probe = Executor::single("fixture", graph.clone())
        .with_probe()
        .execute(&format!("{query} LIMIT 1"), None)
        .unwrap();
    assert_eq!(format!("{:?}", probe.rows), format!("{:?}", &all.rows[..1]));
    assert_eq!(probe.more, all.rows.len() > 1);
    let last = *graph.ids_by_tag(TAG_CALL_SITE_NODE).last().unwrap();
    let late = format!("MATCH (n:CallSite) WITH CASE WHEN n.id = {last} THEN 1 / 0 ELSE n.callee_class END AS c WHERE c IS NOT NULL RETURN c LIMIT 1");
    assert!(
        matches!(Executor::single("fixture", graph).execute(&late, None), Err(CypherError::Runtime(message)) if message == "Division by zero")
    );
}
