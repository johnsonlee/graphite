//! Correctness and source-access contracts, never timing/performance evidence.
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

fn columns() -> Vec<String> {
    vec!["value".into(), "c".into()]
}

fn old<T>(run: impl FnOnce() -> T) -> T {
    struct Reset(bool);
    impl Drop for Reset {
        fn drop(&mut self) {
            DISABLED.with(|flag| flag.set(self.0));
        }
    }
    let _reset = Reset(DISABLED.with(|flag| flag.replace(true)));
    run()
}

fn same(actual: &QueryResult, expected: &QueryResult) {
    assert_eq!(actual.columns, expected.columns);
    assert_eq!(format!("{:?}", actual.rows), format!("{:?}", expected.rows));
    assert_eq!(actual.more, expected.more);
}

fn raw(ex: &Executor, field: usize, bound: usize, descending: bool) -> QueryResult {
    let order = if descending { " ORDER BY c DESC" } else { "" };
    let query = format!(
        "MATCH(n:CallSite) RETURN n.{} AS value, count(*) AS c{order} LIMIT {bound}",
        CALL_SITE_FIELDS[field]
    );
    grouped_call_site_property_with_counts(
        ex,
        &parse(&query).unwrap(),
        raw_call_site_property_counts,
    )
    .unwrap()
    .unwrap()
}

#[test]
fn bounded_rows_match_raw_global_counts_order_ties_and_complete_provenance() {
    let Some(graph) = fixture() else { return };
    for cross in [false, true] {
        for ids in [["z-first", "a-later"], ["same", "same"]] {
            let ex = Executor::new(sources(&graph, &ids), cross);
            for field in 0..CALL_SITE_FIELDS.len() {
                for descending in [false, true] {
                    for bound in [0, 1, 7, 65_536] {
                        let actual = bounded_call_site_groups(
                            &ex,
                            field,
                            &columns(),
                            bound,
                            None,
                            descending,
                        )
                        .unwrap()
                        .expect("bounded certified path");
                        same(&actual, &raw(&ex, field, bound, descending));
                    }
                }
            }
        }
    }
}

#[test]
fn with_filter_uses_complete_global_counts_before_selection_and_provenance() {
    let Some(graph) = fixture() else { return };
    let ids = ["z", "x", "v", "t", "b", "a"];
    let ex = Executor::new(sources(&graph, &ids), true);
    for (field, property) in CALL_SITE_FIELDS.iter().enumerate() {
        let total = raw(&ex, field, 65_536, false);
        for minimum in [0, 5, i64::MAX] {
            for bound in [0, 1, 50] {
                let actual =
                    bounded_call_site_groups(&ex, field, &columns(), bound, Some(minimum), false)
                        .unwrap()
                        .expect("bounded filtered path");
                let mut expected = total.clone();
                expected
                    .rows
                    .retain(|r| matches!(r["c"], Value::Int(n) if n>minimum));
                expected.rows.truncate(bound);
                same(&actual, &expected);
                let query = format!("MATCH(n:CallSite) WITH n.{property} AS value,count(*) AS c WHERE c > {minimum} RETURN value,c LIMIT {bound}");
                let pipeline = ex.execute(&query, None).unwrap();
                let previous = old(|| ex.execute(&query, None)).unwrap();
                same(&pipeline, &previous);
                same(&pipeline, &expected);
            }
        }
    }
}

#[test]
fn unsupported_bounds_aliases_and_predicates_decline_without_rows() {
    let ex = Executor::new(Vec::new(), true);
    for names in [
        vec!["one".into()],
        vec!["same".into(), "same".into()],
        vec!["".into(), "count".into()],
        vec!["value".into(), "\0hidden".into()],
    ] {
        assert!(bounded_call_site_groups(&ex, 0, &names, 1, None, false)
            .unwrap()
            .is_none());
    }
    for (field, limit, minimum) in [(4, 1, None), (0, 65_537, None), (0, 1, Some(-1))] {
        assert!(
            bounded_call_site_groups(&ex, field, &columns(), limit, minimum, false)
                .unwrap()
                .is_none()
        );
    }
}

#[test]
fn additional_labels_match_the_real_generic_pipeline_not_an_index_only_counter() {
    let Some(graph) = fixture() else { return };
    let ex = Executor::new(sources(&graph, &["z", "a"]), true);
    for labels in ["CallSite:Field", "CallSite:Unknown"] {
        let query=format!("MATCH(n:{labels}) RETURN n.callee_class AS value,count(*) AS c ORDER BY c DESC LIMIT 20");
        assert!(grouped_call_site_property(&ex, &parse(&query).unwrap())
            .unwrap()
            .is_none());
        let actual = ex.execute(&query, None).unwrap();
        let expected = ex
            .execute(&format!("UNWIND [0] AS seed {query}"), None)
            .unwrap();
        same(&actual, &expected);
        assert!(actual.rows.is_empty());
    }
    let query="MATCH(n:cAlLsItE:CallSiteNode) RETURN n.callee_class AS value,count(*) AS c ORDER BY c DESC LIMIT 7";
    let actual = grouped_call_site_property(&ex, &parse(query).unwrap())
        .unwrap()
        .expect("valid aliases");
    let expected = ex
        .execute(&format!("UNWIND [0] AS seed {query}"), None)
        .unwrap();
    same(&actual, &expected);
    for labels in [
        "CallSite:Field",
        "CallSite:Unknown",
        "CallSite:CallSiteNode",
    ] {
        let count_query = format!("MATCH(n:{labels}) RETURN count(*) AS c");
        let clauses = parse(&count_query).unwrap();
        let admitted = count_star(&ex, &clauses).unwrap();
        assert_eq!(admitted.is_some(), labels == "CallSite:CallSiteNode");
        assert_eq!(
            crate::engine::partition::PartitionPlan::build(&ex, &clauses).is_some(),
            labels == "CallSite:CallSiteNode"
        );
        same(
            &ex.execute(&count_query, None).unwrap(),
            &ex.execute(&format!("UNWIND [0] AS seed {count_query}"), None)
                .unwrap(),
        );
        let with_query = format!("MATCH(n:{labels}) WITH n.callee_class AS value,count(*) AS c WHERE c > 5 RETURN value,c LIMIT 50");
        same(
            &ex.execute(&with_query, None).unwrap(),
            &ex.execute(&format!("UNWIND [0] AS seed {with_query}"), None)
                .unwrap(),
        );
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
        "graphite-bounded-groups-{}-{}",
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
        std::fs::write(path.0.join("graph.callsite-string-index"), bytes).unwrap();
    }
    if let Some(field) = bad_field {
        let id = *ids.last().unwrap();
        let start = graph.node_offset(id).unwrap() + graphite_storage::node::NODE_HEADER_BYTES;
        let mut bytes = graph.nodedata().to_vec();
        let at = start + field * 4;
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
fn later_sources_change_winners_threshold_and_complete_tie_provenance() {
    let Some(graph) = fixture() else { return };
    let index = graph
        .validated_ordered_call_site(2, || Ok::<_, ()>(()))
        .unwrap()
        .unwrap();
    let mut groups = index
        .property_values(2, false)
        .unwrap()
        .filter(|(_, nodes)| nodes.len() >= 8)
        .map(|(sid, nodes)| {
            (
                nodes.first().unwrap(),
                sid,
                nodes.take(8).collect::<Vec<_>>(),
            )
        })
        .collect::<Vec<_>>();
    groups.sort_unstable_by_key(|(first, _, _)| *first);
    let a = &groups[0];
    let b = &groups[1];
    let mut left_ids = a.2[..3]
        .iter()
        .chain(b.2[..1].iter())
        .copied()
        .collect::<Vec<_>>();
    let (_left, left) = valid_subset(&graph, &mut left_ids);
    let mut right_ids = a.2[..1]
        .iter()
        .chain(b.2[..8].iter())
        .copied()
        .collect::<Vec<_>>();
    let (_right, right) = valid_subset(&graph, &mut right_ids);
    let ex = Executor::new(
        vec![
            Source {
                id: Arc::from("z-first"),
                graph: left.clone(),
            },
            Source {
                id: Arc::from("a-later"),
                graph: right,
            },
        ],
        true,
    );
    for minimum in [None, Some(5)] {
        let actual = bounded_call_site_groups(&ex, 2, &columns(), 1, minimum, minimum.is_none())
            .unwrap()
            .expect("bounded winner path");
        assert_eq!(actual.rows.len(), 1);
        assert_eq!(actual.rows[0]["value"].as_str(), Some(graph.str(b.1)));
        assert!(matches!(actual.rows[0]["c"], Value::Int(9)));
        assert_eq!(
            QueryResult::graph_ids(&actual.rows[0]),
            ["a-later", "z-first"]
        );
        if minimum.is_none() {
            same(&actual, &raw(&ex, 2, 1, true));
        }
    }
    let equal_threshold = bounded_call_site_groups(&ex, 2, &columns(), 1, Some(9), false)
        .unwrap()
        .expect("strict threshold path");
    assert!(equal_threshold.rows.is_empty());
    let mut tie_ids = a.2[..1]
        .iter()
        .chain(b.2[..3].iter())
        .copied()
        .collect::<Vec<_>>();
    let (_tie, tie) = valid_subset(&graph, &mut tie_ids);
    let ex = Executor::new(
        vec![
            Source {
                id: Arc::from("z-first"),
                graph: left,
            },
            Source {
                id: Arc::from("a-later"),
                graph: tie,
            },
        ],
        true,
    );
    let actual = bounded_call_site_groups(&ex, 2, &columns(), 2, None, true)
        .unwrap()
        .expect("stable count ties");
    same(&actual, &raw(&ex, 2, 2, true));
    assert_eq!(
        actual
            .rows
            .iter()
            .map(|row| row["value"].as_str().unwrap())
            .collect::<Vec<_>>(),
        [graph.str(a.1), graph.str(b.1)]
    );
    assert!(actual
        .rows
        .iter()
        .all(|row| matches!(row["c"], Value::Int(4))));
    assert!(actual
        .rows
        .iter()
        .all(|row| QueryResult::graph_ids(row) == ["a-later", "z-first"]));
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
fn empty_domains_have_no_fictional_groups_and_unproved_sources_fall_back_even_at_zero() {
    let Some(graph) = fixture() else { return };
    let ids = graph.ids_by_tag(TAG_CALL_SITE_NODE);
    let (_empty, empty) = variant(&graph, &[], true, None);
    for input in [Vec::new(), sources(&empty, &["z", "a"])] {
        let result =
            bounded_call_site_groups(&Executor::new(input, true), 0, &columns(), 1, None, true)
                .unwrap()
                .expect("empty domain");
        assert!(result.rows.is_empty());
    }
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
    ] {
        let (_path, changed) = variant(&graph, &chosen, broken, bad_field);
        for earlier in [false, true] {
            let mut input = sources(&graph, &["first", "last"]);
            input[usize::from(!earlier)].graph = changed.clone();
            let ex = Executor::new(input, true);
            for limit in [0, 1] {
                assert!(
                    bounded_call_site_groups(&ex, 0, &columns(), limit, None, true)
                        .unwrap()
                        .is_none()
                );
                let clauses=parse(&format!("MATCH(n:CallSite) RETURN n.caller_class AS value,count(*) AS c ORDER BY c DESC LIMIT {limit}")).unwrap();
                assert_eq!(
                    outcome(|| grouped_call_site_property(&ex, &clauses)),
                    outcome(|| grouped_call_site_property_with_counts(
                        &ex,
                        &clauses,
                        raw_call_site_property_counts
                    ))
                );
            }
        }
    }
}

#[test]
fn cached_certificate_cancellation_and_timeout_precede_zero_limit() {
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
        let ex = Executor::new(sources(&graph, &["z", "a"]), true).with_cancel(cancel);
        for limit in [0, 1] {
            let error =
                bounded_call_site_groups(&ex, 0, &columns(), limit, None, true).unwrap_err();
            assert!(matches!(
                error,
                CypherError::Cancelled | CypherError::Timeout(60_000)
            ));
        }
    }
}
