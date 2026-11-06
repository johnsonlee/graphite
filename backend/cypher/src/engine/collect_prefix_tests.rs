//! Correctness tests only; real-source timings belong to the multi-graph runner.
use super::*;
use crate::engine::{CancelToken, Source};
use crate::parser::parse;
use graphite_storage::{node::NODE_HEADER_BYTES, Graph};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

fn admitted(query: &str) -> bool {
    let clauses = parse(query).unwrap();
    // Match the enclosing dispatch, which reaches run_fused_with only for
    // non-optional matches and supplies caps only without WITH's WHERE.
    let Clause::Match {
        patterns,
        optional: false,
        ..
    } = &clauses[0]
    else {
        return false;
    };
    let Some((shape, predicate, consumed)) = FusedShape::detect_with(&clauses) else {
        return false;
    };
    if predicate.is_some() {
        return false;
    }
    let caps = collect_prefix_caps(&shape, &clauses[consumed..]);
    CollectPrefixPlan::build(&[Row::new()], patterns, &shape, &caps).is_some()
}

#[test]
fn admits_only_exclusively_bounded_direct_scalar_collects() {
    for property in CALL_SITE_PROPERTY_NAMES {
        for tail in [
            "RETURN names[0..0] AS values",
            "RETURN names[..10] AS values",
            "RETURN names[2..5] AS values",
            "RETURN names[10..3] AS values",
            "RETURN names[0..65536] AS values LIMIT 0",
            "RETURN names[0..1] AS first, names[2..3] AS next",
        ] {
            let query = format!(
                "MATCH (n:cAlLsItE:CallSiteNode) WITH COLLECT(n.{property}) AS names {tail}"
            );
            assert!(admitted(&query), "{query}");
        }
    }
    for query in [
        "MATCH (n:CallSite) WITH collect(n.callee_name) AS names RETURN names",
        "MATCH (n:CallSite) WITH collect(n.callee_name) AS names RETURN names[0]",
        "MATCH (n:CallSite) WITH collect(n.callee_name) AS names RETURN names[0..]",
        "MATCH (n:CallSite) WITH collect(n.callee_name) AS names RETURN names[-1..3]",
        "MATCH (n:CallSite) WITH collect(n.callee_name) AS names RETURN names[0..65537]",
        "MATCH (n:CallSite) WITH collect(n.callee_name) AS names RETURN names[0..$cap]",
        "MATCH (n:CallSite) WITH collect(n.callee_name) AS names RETURN names[0..10] ORDER BY names",
        "MATCH (n:CallSite) WITH collect(n.callee_name) AS names RETURN DISTINCT names[0..10]",
        "MATCH (n:CallSite) WITH collect(n.callee_name) AS names WHERE true RETURN names[0..10]",
        "MATCH (n:CallSite) WHERE true WITH collect(n.callee_name) AS names RETURN names[0..10]",
        "MATCH (n:CallSite {callee_name: 'x'}) WITH collect(n.callee_name) AS names RETURN names[0..10]",
        "MATCH p=(n:CallSite) WITH collect(n.callee_name) AS names RETURN names[0..10]",
        "MATCH (n:CallSite)-[]->(m) WITH collect(n.callee_name) AS names RETURN names[0..10]",
        "MATCH (n:CallSite), (m:CallSite) WITH collect(n.callee_name) AS names RETURN names[0..10]",
        "OPTIONAL MATCH (n:CallSite) WITH collect(n.callee_name) AS names RETURN names[0..10]",
        "MATCH (n:CallSite:Field) WITH collect(n.callee_name) AS names RETURN names[0..10]",
        "MATCH (n:Field) WITH collect(n.name) AS names RETURN names[0..10]",
        "MATCH (n:CallSite) WITH collect(n.line) AS names RETURN names[0..10]",
        "MATCH (n:CallSite) WITH collect(n.missing) AS names RETURN names[0..10]",
        "MATCH (n:CallSite) WITH collect(n.callee_signature) AS names RETURN names[0..10]",
        "MATCH (n:CallSite) WITH collect(n.graphId) AS names RETURN names[0..10]",
        "MATCH (n:CallSite) WITH collect(DISTINCT n.callee_name) AS names RETURN names[0..10]",
        "MATCH (n:CallSite) WITH collect(missing_function(n.callee_name)) AS names RETURN names[0..10]",
        "MATCH (n:CallSite) WITH collect(CASE WHEN true THEN n.callee_name ELSE null END) AS names RETURN names[0..10]",
        "MATCH (n:CallSite) WITH n.callee_class AS c, collect(n.callee_name) AS names RETURN c, names[0..10]",
        "MATCH (n:CallSite) WITH count(*) AS count, collect(n.callee_name) AS names RETURN count, names[0..10]",
    ] {
        assert!(!admitted(query), "unexpected admission: {query}");
    }
    let clauses = parse(
        "MATCH(n:CallSite) WITH collect(n.callee_name) AS names RETURN names[0..1] AS values",
    )
    .unwrap();
    let Clause::Match { patterns, .. } = &clauses[0] else {
        unreachable!()
    };
    let (shape, _, consumed) = FusedShape::detect_with(&clauses).unwrap();
    let caps = collect_prefix_caps(&shape, &clauses[consumed..]);
    assert!(CollectPrefixPlan::build(
        &[Row::from([("seed".into(), Value::Bool(true))])],
        patterns,
        &shape,
        &caps,
    )
    .is_none());
}

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

fn original<T>(run: impl FnOnce() -> T) -> T {
    struct Reset(bool);
    impl Drop for Reset {
        fn drop(&mut self) {
            DISABLED.with(|flag| flag.set(self.0));
        }
    }
    let _reset = Reset(DISABLED.with(|flag| flag.replace(true)));
    run()
}

fn direct(ex: &Executor, query: &str) -> CypherResult<Option<(Vec<String>, Vec<Row>)>> {
    let clauses = parse(query).unwrap();
    let Clause::Match { patterns, .. } = &clauses[0] else {
        panic!("MATCH")
    };
    let (shape, predicate, consumed) = FusedShape::detect_with(&clauses).unwrap();
    assert!(predicate.is_none());
    let caps = collect_prefix_caps(&shape, &clauses[consumed..]);
    let ev = Evaluator::new(ex, &ex.params);
    try_execute(ex, &ev, &[Row::new()], patterns, &shape, &caps)
}

fn outcome(ex: &Executor, query: &str, disable: bool) -> String {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if disable {
            original(|| ex.execute(query, None))
        } else {
            ex.execute(query, None)
        }
    }));
    match result {
        Ok(result) => format!("{result:?}"),
        Err(panic) => format!(
            "panic:{}",
            panic
                .downcast_ref::<String>()
                .cloned()
                .unwrap_or_else(|| panic.downcast_ref::<&str>().unwrap().to_string())
        ),
    }
}

#[test]
fn prefix_keeps_encounter_order_duplicates_aliases_and_later_graph_provenance() {
    let Some(graph) = fixture() else { return };
    let ids = graph.ids_by_tag(TAG_CALL_SITE_NODE);
    assert!(ids.len() >= 32);
    let (_path, graph) = variant(&graph, &ids[..32], Damage::None);
    for cross in [false, true] {
        for names in [["z-first", "a-last"], ["same-id", "same-id"]] {
            let ex = Executor::new(sources(&graph, &names), cross);
            for property in CALL_SITE_PROPERTY_NAMES {
                for range in ["0..0", "0..1", "2..5", "10..3", "0..65536"] {
                    let query = format!("MATCH(n:CallSite) WITH collect(n.{property}) AS names RETURN names[{range}] AS values");
                    let reference = format!("MATCH(n:CallSite) WITH collect(n.{property}) AS names RETURN (CASE WHEN true THEN names ELSE [] END)[{range}] AS values");
                    let actual = ex.execute(&query, None).unwrap();
                    let expected = ex.execute(&reference, None).unwrap();
                    assert_eq!(format!("{actual:?}"), format!("{expected:?}"), "{query}");
                    assert_eq!(outcome(&ex, &query, false), outcome(&ex, &query, true));
                    let expected_ids = if cross {
                        let mut ids = names.to_vec();
                        ids.sort();
                        ids.dedup();
                        ids
                    } else {
                        Vec::new()
                    };
                    assert_eq!(QueryResult::graph_ids(&actual.rows[0]), expected_ids);
                }
            }
        }
    }
}

#[test]
fn different_sources_keep_prefix_boundary_and_all_contributors() {
    let Some(graph) = fixture() else { return };
    let ids = graph.ids_by_tag(TAG_CALL_SITE_NODE);
    let first = ids[0];
    let first_sid = graph.call_site_scalar_strings(first).unwrap().callee_name;
    let second = *ids
        .iter()
        .find(|&&id| graph.call_site_scalar_strings(id).unwrap().callee_name != first_sid)
        .expect("real fixture needs two distinct callee names");
    let second_sid = graph.call_site_scalar_strings(second).unwrap().callee_name;
    let (_left, left) = variant(&graph, &[first, first, second], Damage::None);
    let (_right, right) = variant(&graph, &[second, first], Damage::None);
    let (_empty, empty) = variant(&graph, &[], Damage::None);
    let ex = Executor::new(
        vec![
            Source {
                id: Arc::from("z-first"),
                graph: left,
            },
            Source {
                id: Arc::from("empty"),
                graph: empty,
            },
            Source {
                id: Arc::from("a-last"),
                graph: right,
            },
        ],
        true,
    );
    for cap in [0, 1, 4, 10] {
        let query = format!("MATCH(n:CallSite) WITH collect(n.callee_name) AS names RETURN names[0..{cap}] AS values");
        let actual = ex.execute(&query, None).unwrap();
        assert_eq!(outcome(&ex, &query, false), outcome(&ex, &query, true));
        let Value::List(values) = &actual.rows[0]["values"] else {
            panic!("list")
        };
        let expected = [first_sid, first_sid, second_sid, second_sid, first_sid]
            .into_iter()
            .take(cap)
            .map(|sid| graph.str(sid))
            .collect::<Vec<_>>();
        assert_eq!(
            values
                .iter()
                .map(|v| v.as_str().unwrap())
                .collect::<Vec<_>>(),
            expected
        );
        assert_eq!(
            QueryResult::graph_ids(&actual.rows[0]),
            ["a-last", "z-first"]
        );
    }
    // Nullable/nonexistent properties stay on the original null-dropping path.
    let query = "MATCH(n:CallSite) WITH collect(n.missing) AS names RETURN names[0..10] AS values";
    let actual = ex.execute(query, None).unwrap();
    assert_eq!(outcome(&ex, query, false), outcome(&ex, query, true));
    assert!(matches!(&actual.rows[0]["values"], Value::List(values) if values.is_empty()));
    assert_eq!(
        QueryResult::graph_ids(&actual.rows[0]),
        ["a-last", "z-first"]
    );
    let query = "MATCH(n:CallSite) WITH collect(CASE WHEN n.graphId = 'a-last' THEN missing_function(n.id) ELSE n.callee_name END) AS names RETURN names[0..1] AS values";
    let error = ex.execute(query, None).unwrap_err();
    assert!(error.to_string().contains("missing_function"));
    assert_eq!(outcome(&ex, query, false), outcome(&ex, query, true));
}

#[test]
fn warm_certified_prefix_does_not_visit_the_discarded_suffix() {
    let Some(graph) = fixture() else { return };
    let ids = graph.ids_by_tag(TAG_CALL_SITE_NODE);
    let (_path, graph) = variant(&graph, &ids[..32], Damage::None);
    assert!(graph
        .call_site_scalar_records_valid(|| Ok::<_, ()>(()))
        .unwrap());
    let ex = Executor::new(sources(&graph, &["z", "a"]), true);
    let eager = Executor::new(sources(&graph, &["z", "a"]), true);
    let query =
        "MATCH(n:CallSite) WITH collect(n.callee_name) AS names RETURN names[0..1] AS names";
    let (columns, rows) = direct(&ex, query).unwrap().expect("certified prefix path");
    let actual = ex.execute(query, None).unwrap();
    let expected = original(|| eager.execute(query, None)).unwrap();
    assert_eq!(columns, expected.columns);
    assert_eq!(format!("{rows:?}"), format!("{:?}", expected.rows));
    assert_eq!(format!("{actual:?}"), format!("{expected:?}"));
    // These counters verify source-access behavior, not a timing or performance gate.
    let matched = graph.count_by_tag(TAG_CALL_SITE_NODE) * 2;
    assert!(ex.poll.load(Ordering::Relaxed) < matched as u64);
    assert!(eager.poll.load(Ordering::Relaxed) >= matched as u64);
}

#[test]
fn direct_empty_and_cached_zero_prefix_keep_rows_and_check_cancellation() {
    let Some(graph) = fixture() else { return };
    assert!(graph
        .call_site_scalar_records_valid(|| Ok::<_, ()>(()))
        .unwrap());
    let (_empty_path, empty) = variant(&graph, &[], Damage::None);
    for input in [
        Vec::new(),
        sources(&empty, &["z", "a"]),
        sources(&graph, &["z", "a"]),
    ] {
        for timeout in [false, true] {
            let cancel = CancelToken::new();
            let ex = Executor::new(input.clone(), true).with_cancel(cancel.clone());
            let query = "MATCH(n:CallSite) WITH collect(n.callee_name) AS names RETURN names[0..0] AS names";
            let (columns, rows) = direct(&ex, query).unwrap().expect("zero prefix path");
            let expected = original(|| ex.execute(query, None)).unwrap();
            assert_eq!(columns, expected.columns);
            assert_eq!(format!("{rows:?}"), format!("{:?}", expected.rows));
            if timeout {
                cancel.timeout(60_000);
            } else {
                cancel.cancel();
            }
            let error = direct(&ex, query).unwrap_err();
            if timeout {
                assert!(matches!(error, CypherError::Timeout(60_000)));
            } else {
                assert!(matches!(error, CypherError::Cancelled));
            }
        }
    }
}

struct Variant(PathBuf);
impl Drop for Variant {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

#[derive(Clone, Copy)]
enum Damage {
    None,
    BadSid(usize, u32),
    Truncated,
}

fn variant(graph: &Graph, ids: &[u32], damage: Damage) -> (Variant, Arc<Graph>) {
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let path = Variant(std::env::temp_dir().join(format!(
        "graphite-collect-prefix-{}-{}",
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
            || (matches!(damage, Damage::BadSid(..) | Damage::Truncated)
                && name == "graph.nodedata")
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
    match damage {
        Damage::None => {}
        Damage::BadSid(property, sid) => {
            let offset = graph.node_offset(*ids.last().unwrap()).unwrap();
            let mut bytes = graph.nodedata().to_vec();
            let start = offset + NODE_HEADER_BYTES;
            let count = i32::from_be_bytes(bytes[start + 8..start + 12].try_into().unwrap()).max(0)
                as usize;
            let at = if property < 2 {
                start + property * 4
            } else {
                start + 16 + count * 4 + (property - 2) * 4
            };
            bytes[at..at + 4].copy_from_slice(&sid.to_be_bytes());
            std::fs::write(path.0.join("graph.nodedata"), bytes).unwrap();
        }
        Damage::Truncated => {
            let offset = graph.node_offset(*ids.last().unwrap()).unwrap();
            let bytes = graph.nodedata();
            let start = offset + NODE_HEADER_BYTES;
            let caller_params = i32::from_be_bytes(bytes[start + 8..start + 12].try_into().unwrap())
                .max(0) as usize;
            let callee = start + 16 + caller_params * 4;
            let callee_params =
                i32::from_be_bytes(bytes[callee + 8..callee + 12].try_into().unwrap()).max(0)
                    as usize;
            // All four scalar strings are present, but the complete record is not.
            let end = callee + 16 + callee_params * 4;
            std::fs::write(path.0.join("graph.nodedata"), &bytes[..end]).unwrap();
        }
    }
    let graph = Arc::new(Graph::load(&path.0).unwrap());
    (path, graph)
}

#[test]
fn empty_domains_and_unsorted_or_duplicate_ids_keep_generic_results() {
    let Some(graph) = fixture() else { return };
    let ids = graph.ids_by_tag(TAG_CALL_SITE_NODE);
    assert!(ids.len() > 3);
    let mut reverse = ids[..3].to_vec();
    reverse.reverse();
    for chosen in [Vec::new(), reverse, vec![ids[0], ids[0], ids[1]]] {
        let (_path, graph) = variant(&graph, &chosen, Damage::None);
        for sources in [Vec::new(), sources(&graph, &["z", "a"])] {
            let ex = Executor::new(sources, true);
            for cap in [0, 1, 10] {
                let query = format!("MATCH(n:CallSite) WITH collect(n.callee_name) AS names RETURN names[0..{cap}] AS values");
                let actual = ex.execute(&query, None).unwrap();
                assert_eq!(outcome(&ex, &query, false), outcome(&ex, &query, true));
                assert_eq!(actual.rows.len(), 1);
                if chosen.is_empty() || ex.sources.is_empty() {
                    assert_eq!(format!("{:?}", actual.rows[0]["values"]), "List([])");
                    assert!(QueryResult::graph_ids(&actual.rows[0]).is_empty());
                }
            }
        }
    }
}

#[test]
fn any_late_bad_scalar_or_frame_falls_back_before_omitting_rows_or_errors() {
    let Some(graph) = fixture() else { return };
    let ids = graph.ids_by_tag(TAG_CALL_SITE_NODE);
    assert!(ids.len() > 3);
    for (property, &name) in CALL_SITE_PROPERTY_NAMES.iter().enumerate() {
        for damage in [Damage::BadSid(property, u32::MAX), Damage::Truncated] {
            let (_late_path, bad_late) = variant(&graph, &ids[..3], damage);
            let (_first_path, bad_first) =
                variant(&graph, &ids[..3], Damage::BadSid(property, u32::MAX - 1));
            for earlier_bad in [false, true] {
                let ex = Executor::new(
                    vec![
                        Source {
                            id: Arc::from("first"),
                            graph: if earlier_bad {
                                bad_first.clone()
                            } else {
                                graph.clone()
                            },
                        },
                        Source {
                            id: Arc::from("later"),
                            graph: bad_late.clone(),
                        },
                    ],
                    true,
                );
                for cap in [0, 1, 4] {
                    for selected in [name, "caller_name"] {
                        let query = format!("MATCH(n:CallSite) WITH collect(n.{selected}) AS names RETURN names[0..{cap}] AS values");
                        assert_eq!(
                            outcome(&ex, &query, false),
                            outcome(&ex, &query, true),
                            "{query}"
                        );
                    }
                }
            }
        }
    }
}

#[test]
fn cancellation_and_timeout_precede_even_zero_prefix_and_empty_graphs() {
    for timed_out in [false, true] {
        let cancel = CancelToken::new();
        if timed_out {
            cancel.timeout(60_000);
        } else {
            cancel.cancel();
        }
        let ex = Executor::new(Vec::new(), true).with_cancel(cancel);
        for cap in [0, 1] {
            let query = format!("MATCH(n:CallSite) WITH collect(n.callee_name) AS names RETURN names[0..{cap}] AS values");
            let error = ex.execute(&query, None).unwrap_err();
            assert!(
                matches!(error, CypherError::Cancelled | CypherError::Timeout(_)),
                "{error:?}"
            );
        }
    }
}

#[test]
fn interrupted_cold_scalar_proof_is_not_cached_and_can_be_retried() {
    let Some(graph) = fixture() else { return };
    let mut checks = 0;
    let failed = graph.call_site_scalar_records_valid(|| {
        checks += 1;
        if checks == 2 {
            Err(CypherError::Cancelled)
        } else {
            Ok(())
        }
    });
    assert!(matches!(failed, Err(CypherError::Cancelled)));
    let mut retries = 0;
    assert!(graph
        .call_site_scalar_records_valid(|| {
            retries += 1;
            Ok::<_, CypherError>(())
        })
        .unwrap());
    assert_eq!(retries, graph.count_by_tag(TAG_CALL_SITE_NODE) + 2);
    let ex = Executor::new(sources(&graph, &["z", "a"]), true);
    let query =
        "MATCH(n:CallSite) WITH collect(n.callee_name) AS names RETURN names[0..0] AS values";
    assert_eq!(outcome(&ex, query, false), outcome(&ex, query, true));
}
