//! Correctness and materialization contracts; no performance measurements.
use super::*;
use crate::engine::{CancelToken, Source};
use crate::parser::parse;
use crate::CypherError;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

fn fixture() -> Option<Arc<Graph>> {
    let Some(path) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
        assert!(std::env::var_os("GRAPHITE_REQUIRE_ALL_TYPES_FIXTURES").is_none());
        return None;
    };
    Some(Arc::new(Graph::load(Path::new(&path)).unwrap()))
}
fn sources(graph: &Arc<Graph>, ids: &[&str]) -> Vec<Source> {
    ids.iter()
        .map(|id| Source {
            id: Arc::from(*id),
            graph: graph.clone(),
        })
        .collect()
}
fn query(field: usize, limit: usize) -> String {
    format!(
        "MATCH(n:CallSite) RETURN DISTINCT n.{} AS value LIMIT {limit}",
        CALL_SITE_FIELDS[field]
    )
}
fn same(actual: &QueryResult, expected: &QueryResult) {
    assert_eq!(actual.columns, expected.columns);
    assert_eq!(format!("{:?}", actual.rows), format!("{:?}", expected.rows));
    assert_eq!(actual.more, expected.more);
}
fn raw(ex: &Executor, query: &str) -> QueryResult {
    distinct_string_property_with_ids(ex, &parse(query).unwrap(), raw_distinct_string_ids)
        .unwrap()
        .unwrap()
}
fn direct(ex: &Executor, query: &str) -> QueryResult {
    MATERIALIZED.with(|count| count.set(0));
    let result = try_execute(ex, &parse(query).unwrap())
        .unwrap()
        .expect("certified bounded DISTINCT admitted");
    assert_eq!(
        MATERIALIZED.with(std::cell::Cell::get),
        result.rows.len(),
        "only surviving rows may be materialized"
    );
    result
}

#[test]
fn all_fields_bounds_order_and_complete_provenance_match_raw_and_true_generic() {
    let Some(graph) = fixture() else { return };
    for cross in [false, true] {
        for ids in [["z-first", "a-last"], ["same", "same"]] {
            let ex = Executor::new(sources(&graph, &ids), cross);
            for field in 0..CALL_SITE_FIELDS.len() {
                for bound in [0, 1, 13, 65_536] {
                    let query = query(field, bound);
                    let result = direct(&ex, &query);
                    same(&result, &raw(&ex, &query));
                    same(
                        &result,
                        &ex.execute(&format!("UNWIND [0] AS seed {query}"), None)
                            .unwrap(),
                    );
                }
            }
        }
    }
}

#[test]
fn unsupported_shapes_and_internal_aliases_decline() {
    let ex = Executor::new(Vec::new(), true);
    for query in [
        "MATCH(n:CallSite) RETURN DISTINCT n.callee_class AS value",
        "MATCH(n:CallSite) RETURN DISTINCT n.callee_class AS value ORDER BY value LIMIT 2",
        "MATCH(n:CallSite) RETURN DISTINCT n.callee_class AS value LIMIT 65537",
        "MATCH(n:CallSite) RETURN DISTINCT n.callee_class AS value LIMIT -1",
        "MATCH(n:CallSite) RETURN DISTINCT n.callee_class AS value LIMIT $n",
        "MATCH(n:CallSite) RETURN DISTINCT n.callee_class AS value,n.callee_name AS value LIMIT 2",
        "MATCH(n:CallSite) RETURN DISTINCT toUpper(n.callee_class) AS value LIMIT 2",
        "MATCH(n:CallSite) WHERE n.callee_class='x' RETURN DISTINCT n.callee_class AS value LIMIT 2",
        "OPTIONAL MATCH(n:CallSite) RETURN DISTINCT n.callee_class AS value LIMIT 2",
        "MATCH(n:CallSite {callee_class:'x'}) RETURN DISTINCT n.callee_class AS value LIMIT 2",
        "UNWIND [0] AS seed MATCH(n:CallSite) RETURN DISTINCT n.callee_class AS value LIMIT 2",
        "MATCH(n:Field) RETURN DISTINCT n.name AS value LIMIT 2",
    ] {assert!(Plan::build(&parse(query).unwrap()).is_none(),"{query}");}
    for alias in ["", "\0hidden"] {
        let mut clauses = parse(&query(2, 2)).unwrap();
        let Clause::Return {
            items: Some(items), ..
        } = &mut clauses[1]
        else {
            unreachable!()
        };
        items[0].alias = Some(alias.into());
        assert!(try_execute(&ex, &clauses).unwrap().is_none());
    }
    let Some(graph) = fixture() else { return };
    assert!(try_execute(
        &Executor::new(sources(&graph, &["only"]), true),
        &parse(&query(2, 2)).unwrap()
    )
    .unwrap()
    .is_none());
}

#[test]
fn all_label_constraints_match_true_generic_and_valid_aliases_admit() {
    let Some(graph) = fixture() else { return };
    let ex = Executor::new(sources(&graph, &["z", "a"]), true);
    for labels in [
        "CallSite:Field",
        "CallSite:Unknown",
        "cAlLsItE:CallSiteNode",
    ] {
        let query = format!("MATCH(n:{labels}) RETURN DISTINCT n.callee_class AS value LIMIT 7");
        let valid = labels == "cAlLsItE:CallSiteNode";
        assert_eq!(
            try_execute(&ex, &parse(&query).unwrap()).unwrap().is_some(),
            valid
        );
        assert_eq!(
            distinct_string_property(&ex, &parse(&query).unwrap())
                .unwrap()
                .is_some(),
            valid
        );
        let result = ex.execute(&query, None).unwrap();
        same(
            &result,
            &ex.execute(&format!("UNWIND [0] AS seed {query}"), None)
                .unwrap(),
        );
        if !valid {
            assert!(result.rows.is_empty());
        }
    }
}

struct Variant(PathBuf);
impl Drop for Variant {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn variant(
    graph: &Graph,
    ids: &[u32],
    broken_index: bool,
    bad_field: Option<usize>,
) -> (Variant, Arc<Graph>) {
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let path = Variant(std::env::temp_dir().join(format!(
        "graphite-bounded-distinct-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    )));
    std::fs::create_dir(&path.0).unwrap();
    for entry in std::fs::read_dir(&graph.dir).unwrap() {
        let entry = entry.unwrap();
        if !entry.file_type().unwrap().is_file() {
            continue;
        }
        let name = entry.file_name();
        if name == "graph.typeindex"
            || (broken_index && name == "graph.callsite-string-index")
            || (bad_field.is_some() && name == "graph.nodedata")
        {
            continue;
        }
        std::fs::hard_link(entry.path(), path.0.join(name)).unwrap();
    }
    let original = std::fs::read(graph.dir.join("graph.typeindex")).unwrap();
    let mut bytes = original[..4].to_vec();
    bytes.extend(1i32.to_be_bytes());
    bytes.push(TAG_CALL_SITE_NODE);
    bytes.extend((ids.len() as i32).to_be_bytes());
    bytes.extend(21i64.to_be_bytes());
    for id in ids {
        bytes.extend(id.to_be_bytes());
    }
    std::fs::write(path.0.join("graph.typeindex"), bytes).unwrap();
    if broken_index {
        let mut bytes = std::fs::read(graph.dir.join("graph.callsite-string-index")).unwrap();
        bytes[76..80].copy_from_slice(&u32::MAX.to_be_bytes());
        let call_sites = u32::from_be_bytes(bytes[12..16].try_into().unwrap()) as usize;
        let mut callee_groups = 76;
        for property in 0..2 {
            let at = 48 + property * 4;
            let unique = u32::from_be_bytes(bytes[at..at + 4].try_into().unwrap()) as usize;
            callee_groups += (unique * 2 + call_sites) * 4;
        }
        bytes[callee_groups..callee_groups + 4].copy_from_slice(&u32::MAX.to_be_bytes());
        std::fs::write(path.0.join("graph.callsite-string-index"), bytes).unwrap();
    }
    if let Some(field) = bad_field {
        let id = *ids.last().unwrap();
        let start = graph.node_offset(id).unwrap() + graphite_storage::node::NODE_HEADER_BYTES;
        let mut bytes = graph.nodedata().to_vec();
        let at = if field < 2 {
            start + field * 4
        } else {
            let caller_params =
                i32::from_be_bytes(bytes[start + 8..start + 12].try_into().unwrap()) as usize;
            start + 16 + caller_params * 4 + (field - 2) * 4
        };
        bytes[at..at + 4].copy_from_slice(&u32::MAX.to_be_bytes());
        std::fs::write(path.0.join("graph.nodedata"), bytes).unwrap();
    }
    let loaded = Arc::new(Graph::load(&path.0).unwrap());
    (path, loaded)
}

fn valid_subset(graph: &Graph, ids: &mut [u32]) -> (Variant, Arc<Graph>) {
    ids.sort_unstable();
    let (path, stale) = variant(graph, ids, false, None);
    drop(stale);
    // An absent accelerator is rebuilt from exactly this fixture's selected
    // real node domain; the original directory is never changed.
    std::fs::remove_file(path.0.join("graph.callsite-string-index")).unwrap();
    let loaded = Arc::new(Graph::load(&path.0).unwrap());
    assert!(loaded
        .validated_ordered_call_site(2, || Ok::<_, ()>(()))
        .unwrap()
        .is_some());
    (path, loaded)
}

#[test]
fn later_graph_values_fill_remaining_slots_and_update_full_winner_provenance() {
    let Some(graph) = fixture() else { return };
    let index = graph
        .validated_ordered_call_site(2, || Ok::<_, ()>(()))
        .unwrap()
        .unwrap();
    let groups = index
        .property_values(2, false)
        .unwrap()
        .filter(|(_, nodes)| nodes.len() >= 2)
        .take(2)
        .map(|(sid, nodes)| (sid, nodes.take(2).collect::<Vec<_>>()))
        .collect::<Vec<_>>();
    let mut left_ids = groups[0].1.clone();
    let (_left, left) = valid_subset(&graph, &mut left_ids);
    let mut right_ids = groups[0]
        .1
        .iter()
        .chain(&groups[1].1)
        .copied()
        .collect::<Vec<_>>();
    let (_right, right) = valid_subset(&graph, &mut right_ids);
    let (_empty, empty) = variant(&graph, &[], true, None);
    let ex = Executor::new(
        vec![
            Source {
                id: Arc::from("empty"),
                graph: empty,
            },
            Source {
                id: Arc::from("z-first"),
                graph: left,
            },
            Source {
                id: Arc::from("a-later"),
                graph: right,
            },
        ],
        true,
    );
    for bound in [0, 1, 2, 3] {
        let text = query(2, bound);
        let result = direct(&ex, &text);
        same(&result, &raw(&ex, &text));
        same(
            &result,
            &ex.execute(&format!("UNWIND [0] AS seed {text}"), None)
                .unwrap(),
        );
        if bound > 0 {
            assert_eq!(
                result.rows[0]["value"].as_str(),
                Some(graph.str(groups[0].0))
            );
            assert_eq!(
                QueryResult::graph_ids(&result.rows[0]),
                ["a-later", "z-first"]
            );
        }
        if bound > 1 {
            assert_eq!(
                result.rows[1]["value"].as_str(),
                Some(graph.str(groups[1].0))
            );
            assert_eq!(QueryResult::graph_ids(&result.rows[1]), ["a-later"]);
        }
    }
    let (_empty, empty) = variant(&graph, &[], true, None);
    let ex = Executor::new(sources(&empty, &["first", "last"]), true);
    for bound in [0, 1] {
        assert!(direct(&ex, &query(2, bound)).rows.is_empty());
    }
}

fn outcome(run: impl FnOnce() -> CypherResult<Option<QueryResult>>) -> String {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(run)) {
        Ok(result) => format!("{result:?}"),
        Err(payload) => format!(
            "panic:{}",
            payload
                .downcast_ref::<String>()
                .cloned()
                .unwrap_or_else(|| payload.downcast_ref::<&str>().unwrap().to_string())
        ),
    }
}

#[test]
fn malformed_later_records_and_indexes_decline_before_zero_or_full_prefix() {
    let Some(graph) = fixture() else { return };
    let ids = graph.ids_by_tag(TAG_CALL_SITE_NODE);
    let mut duplicate = ids.to_vec();
    duplicate.insert(0, ids[0]);
    let mut reversed = ids.to_vec();
    reversed.reverse();
    let mut phantom = ids.to_vec();
    phantom.push(u32::MAX);
    for (chosen, broken, bad_field) in [
        (duplicate, false, None),
        (reversed, false, None),
        (phantom, false, None),
        (ids[..ids.len() - 1].to_vec(), false, None),
        (ids.to_vec(), true, None),
        (ids.to_vec(), false, Some(0)),
        (ids.to_vec(), false, Some(1)),
        (ids.to_vec(), false, Some(2)),
        (ids.to_vec(), false, Some(3)),
    ] {
        let (_path, changed) = variant(&graph, &chosen, broken, bad_field);
        for earlier in [false, true] {
            let mut input = sources(&graph, &["first", "last"]);
            input[usize::from(!earlier)].graph = changed.clone();
            let ex = Executor::new(input, true);
            for bound in [0, 1] {
                let clauses = parse(&query(2, bound)).unwrap();
                assert!(try_execute(&ex, &clauses).unwrap().is_none());
                assert_eq!(
                    outcome(|| distinct_string_property(&ex, &clauses)),
                    outcome(|| distinct_string_property_with_ids(
                        &ex,
                        &clauses,
                        raw_distinct_string_ids
                    ))
                );
            }
        }
    }
    let (path, changed) = variant(&graph, ids, false, None);
    drop(changed);
    let last = *ids.last().unwrap();
    let bytes = graph.nodedata()
        [..graph.node_offset(last).unwrap() + graphite_storage::node::NODE_HEADER_BYTES + 2]
        .to_vec();
    std::fs::remove_file(path.0.join("graph.nodedata")).unwrap();
    std::fs::write(path.0.join("graph.nodedata"), bytes).unwrap();
    let changed = Arc::new(Graph::load(&path.0).unwrap());
    let ex = Executor::new(
        vec![
            Source {
                id: Arc::from("first"),
                graph: graph.clone(),
            },
            Source {
                id: Arc::from("last"),
                graph: changed,
            },
        ],
        true,
    );
    for bound in [0, 1] {
        let clauses = parse(&query(2, bound)).unwrap();
        assert!(try_execute(&ex, &clauses).unwrap().is_none());
        assert_eq!(
            outcome(|| distinct_string_property(&ex, &clauses)),
            outcome(|| distinct_string_property_with_ids(&ex, &clauses, raw_distinct_string_ids))
        );
    }
}

#[test]
fn cached_proof_checks_cancellation_and_timeout_before_zero_limit_or_materialization() {
    let Some(graph) = fixture() else { return };
    for field in 0..CALL_SITE_FIELDS.len() {
        assert!(graph
            .validated_ordered_call_site(field, || Ok::<_, ()>(()))
            .unwrap()
            .is_some());
    }
    for timeout in [false, true] {
        let cancel = CancelToken::new();
        if timeout {
            cancel.timeout(60_000);
        } else {
            cancel.cancel();
        }
        let ex = Executor::new(sources(&graph, &["first", "last"]), true).with_cancel(cancel);
        for bound in [0, 1] {
            MATERIALIZED.with(|count| count.set(0));
            let error = try_execute(&ex, &parse(&query(2, bound)).unwrap()).unwrap_err();
            assert!(matches!(
                error,
                CypherError::Cancelled | CypherError::Timeout(60_000)
            ));
            assert_eq!(MATERIALIZED.with(std::cell::Cell::get), 0);
        }
    }
}
