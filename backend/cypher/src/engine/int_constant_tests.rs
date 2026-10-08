use super::*;
use graphite_storage::node::{NodeKind, TAG_INT_CONSTANT};
use std::path::{Path, PathBuf};

fn fixture() -> Option<(PathBuf, Arc<Graph>)> {
    let Some(dir) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
        eprintln!("GRAPHITE_INDEX_FIXTURE unset; skipping IntConstant executor tests");
        return None;
    };
    let path = PathBuf::from(dir);
    let graph = Arc::new(Graph::load(&path).unwrap());
    assert!(
        !graph.ids_by_tag(TAG_INT_CONSTANT).is_empty(),
        "fixture needs IntConstant"
    );
    Some((path, graph))
}

fn integer(value: Value) -> i64 {
    match value {
        Value::Int(n) => n,
        other => panic!("expected typed integer, got {other:?}"),
    }
}

fn same_value(actual: Value, expected: Value) {
    // Debug preserves runtime Value variants, unlike numeric coercion/JSON.
    assert_eq!(format!("{actual:?}"), format!("{expected:?}"));
}

struct Variant {
    path: PathBuf,
    graph: Arc<Graph>,
}

impl Variant {
    fn new(dir: &Path, source: &Graph, id: u32, value: i32) -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        assert!(
            dir.is_dir(),
            "correctness fixture must be an unpacked directory"
        );
        let off = source.node_offset(id).unwrap();
        assert!(matches!(
            source.node(id).unwrap().kind,
            NodeKind::IntConstant(_)
        ));
        let path = std::env::temp_dir().join(format!(
            "graphite-int-scalar-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir(&path).unwrap();
        // Every fixture file is copied so temporary variants never touch source inode metadata.
        // Only the copied nodedata payload changes.
        // Changing an IntConstant payload does not alter CallSite strings/ordinals,
        // their content identities, or any metadata/sidecar binding.
        let setup = || {
            for entry in std::fs::read_dir(dir).unwrap() {
                let entry = entry.unwrap();
                if entry.file_name() == "graph.nodedata" || !entry.file_type().unwrap().is_file() {
                    continue;
                }
                std::fs::copy(entry.path(), path.join(entry.file_name())).unwrap();
            }
            let mut bytes = source.nodedata().to_vec();
            bytes[off + 5..off + 9].copy_from_slice(&value.to_be_bytes());
            assert_eq!(&bytes[..off + 5], &source.nodedata()[..off + 5]);
            assert_eq!(&bytes[off + 9..], &source.nodedata()[off + 9..]);
            std::fs::write(path.join("graph.nodedata"), bytes).unwrap();
            Arc::new(Graph::load(&path).unwrap())
        };
        let graph = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(setup)) {
            Ok(graph) => graph,
            Err(error) => {
                let _ = std::fs::remove_dir_all(&path);
                std::panic::resume_unwind(error);
            }
        };
        let variant = Self { path, graph };
        assert_eq!(variant.graph.int_constant_value(id), Some(value));
        assert_eq!(
            variant.graph.node(id).unwrap().kind,
            NodeKind::IntConstant(value)
        );
        variant
    }
}

impl Drop for Variant {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.path);
    }
}

#[test]
fn int_scalar_executor_matches_full_decode_properties_and_non_int_fallback() {
    let Some((_, graph)) = fixture() else { return };
    let ex = Executor::single("fixture", graph.clone());
    let mut saw_int = false;
    let mut saw_other = false;
    // The accepted scalar branch and the unchanged fallback both use the actual
    // loaded fixture. Full decode is an independent oracle, not another planner
    // that would call this same new accessor.
    for tag in 0..graphite_storage::node::TAG_COUNT as u8 {
        let Some(&id) = graph.ids_by_tag(tag).first() else {
            continue;
        };
        let decoded = graph.node(id).unwrap();
        let nr = NodeRef { source: 0, id };
        saw_int |= tag == TAG_INT_CONSTANT;
        saw_other |= tag != TAG_INT_CONSTANT;
        if let NodeKind::IntConstant(v) = decoded.kind {
            assert_eq!(graph.int_constant_value(id), Some(v));
        } else {
            assert_eq!(graph.int_constant_value(id), None);
        }
        for key in ["value", "id", "type", "missing"] {
            same_value(
                ex.node_property(nr, key),
                props::node_property(&graph, &decoded, key),
            );
        }
        same_value(
            Value::map(ex.node_properties(nr)),
            Value::map(props::node_properties(&graph, &decoded)),
        );
        same_value(
            Value::map(ex.node_display_properties(nr)),
            Value::map(props::node_display_properties(&graph, &decoded)),
        );
        same_value(
            Value::map(ex.node_result_properties(nr)),
            Value::map(props::node_result_properties(&graph, &decoded)),
        );
        assert_eq!(*ex.node(nr).unwrap(), decoded);
    }
    assert!(saw_int && saw_other);
    let missing = NodeRef {
        source: 0,
        id: u32::MAX,
    };
    assert!(ex.node_property(missing, "value").is_null());
    assert!(ex.node(missing).is_none());
}

#[test]
fn int_scalar_distinct_payloads_do_not_alias_sources_or_executor_epochs() {
    let Some((dir, source)) = fixture() else {
        return;
    };
    let id = source.ids_by_tag(TAG_INT_CONSTANT)[0];
    let original = source.int_constant_value(id).unwrap();
    let a = Variant::new(&dir, &source, id, -7);
    let b = Variant::new(&dir, &source, id, i32::MAX);
    let ex = Executor::new(
        vec![
            Source {
                id: Arc::from("a"),
                graph: a.graph.clone(),
            },
            Source {
                id: Arc::from("b"),
                graph: b.graph.clone(),
            },
        ],
        true,
    );
    for source in [0, 1, 0, 1] {
        let nr = NodeRef { source, id };
        let expected = if source == 0 { -7 } else { i64::from(i32::MAX) };
        // Seed the old full-node cache, then alternate scalar and full views.
        assert_eq!(ex.node(nr).unwrap().id, id);
        assert_eq!(integer(ex.node_property(nr, "value")), expected);
        assert_eq!(integer(ex.node_property(nr, "value")), expected);
        assert_eq!(integer(ex.node_properties(nr)["value"].clone()), expected);
        assert_eq!(
            ex.node_property(nr, "graphId").as_str(),
            Some(if source == 0 { "a" } else { "b" })
        );
    }
    let nr = NodeRef { source: 0, id };
    for graph in [&a.graph, &b.graph, &a.graph] {
        let next = Executor::single("same-source-name", (*graph).clone());
        let expected = graph.int_constant_value(id).unwrap();
        assert_eq!(
            integer(next.node_property(nr, "value")),
            i64::from(expected)
        );
        assert_eq!(next.node(nr).unwrap().kind, NodeKind::IntConstant(expected));
    }
    assert_eq!(source.int_constant_value(id), Some(original));
}

#[test]
fn int_scalar_range_rows_probe_errors_and_cancellation_keep_existing_boundaries() {
    let Some((_, graph)) = fixture() else { return };
    let mut expected: Vec<(u32, i32)> = graph
        .ids_by_tag(TAG_INT_CONSTANT)
        .iter()
        .map(|&id| {
            let node = graph.node(id).unwrap();
            match node.kind {
                NodeKind::IntConstant(v) => (node.id, v),
                _ => panic!("invalid fixture IntConstant tag"),
            }
        })
        .collect();
    expected.sort_unstable_by_key(|(id, _)| *id);
    let ex = Executor::single("fixture", graph.clone()).with_probe();
    let result = ex.execute(
        "MATCH (n:IntConstant) WHERE n.value >= -2147483648 AND n.value <= 2147483647 RETURN n.id AS id, n.value AS value, n.value AS again ORDER BY id LIMIT 2",
        None,
    ).unwrap();
    assert_eq!(result.more, expected.len() > 2);
    assert_eq!(result.rows.len(), expected.len().min(2));
    for (row, &(id, value)) in result.rows.iter().zip(&expected) {
        assert_eq!(integer(row["id"].clone()), i64::from(id));
        assert_eq!(integer(row["value"].clone()), i64::from(value));
        assert_eq!(integer(row["again"].clone()), i64::from(value));
    }
    assert!(matches!(
        ex.execute("MATCH (n:IntConstant) WHERE n.value >= -2147483648 RETURN n.value / 0 AS bad", None),
        Err(CypherError::Runtime(message)) if message == "Division by zero"
    ));
    let cancel = CancelToken::new();
    cancel.cancel();
    let cancelled = Executor::single("fixture", graph).with_cancel(cancel);
    let nr = NodeRef {
        source: 0,
        id: expected[0].0,
    };
    assert_eq!(cancelled.poll.load(Ordering::Relaxed), 0);
    // Property reads did not own polling before this optimization, and still do not.
    assert_eq!(
        integer(cancelled.node_property(nr, "value")),
        i64::from(expected[0].1)
    );
    assert_eq!(cancelled.poll.load(Ordering::Relaxed), 0);
    assert!(matches!(cancelled.tick(), Err(CypherError::Cancelled)));
    assert!(matches!(
        cancelled.execute("MATCH (n:IntConstant) RETURN n.value", None),
        Err(CypherError::Cancelled)
    ));
}
