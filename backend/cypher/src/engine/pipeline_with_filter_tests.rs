use super::*;
use crate::engine::{CancelToken, Source};
use crate::parser::parse;
use graphite_storage::Graph;
use serde_json::json;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};

struct Fixture {
    root: PathBuf,
    graphs: Vec<Arc<Graph>>,
}

impl Fixture {
    fn new(lines: &[&[Option<u32>]]) -> Self {
        let method = json!({"declaringClass":"fixture.App", "name":"save", "parameterTypes":[], "returnType":"void"});
        let inputs = lines
            .iter()
            .map(|lines| {
                let nodes: Vec<_> = lines
                    .iter()
                    .enumerate()
                    .map(|(id, line)| {
                        json!({
                            "id":id,"kind":"CallSite","caller":method,"callee":method,
                            "line":line,"receiver":null,"arguments":[]
                        })
                    })
                    .collect();
                json!({"version":1,"methods":[method],"nodes":nodes,"edges":[]})
            })
            .collect();
        Self::from_inputs(inputs)
    }

    fn from_inputs(inputs: Vec<serde_json::Value>) -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let root = std::env::temp_dir().join(format!(
            "graphite-with-filter-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir(&root).unwrap();
        let graphs = inputs
            .into_iter()
            .enumerate()
            .map(|(index, input)| {
                let path = root.join(index.to_string());
                graphite_storage::interchange::import_json(
                    serde_json::to_vec(&input).unwrap().as_slice(),
                    &path,
                )
                .unwrap();
                Arc::new(Graph::load(&path).unwrap())
            })
            .collect();
        Self { root, graphs }
    }

    fn executor(&self) -> Executor {
        Executor::new(
            self.graphs
                .iter()
                .enumerate()
                .map(|(index, graph)| Source {
                    id: Arc::from(format!("graph-{index}")),
                    graph: graph.clone(),
                })
                .collect(),
            true,
        )
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.root);
    }
}

fn admitted(query: &str) -> Option<usize> {
    projected_filter_limit(&parse(query).unwrap(), &[Row::new()])
}

fn generic(query: &str) -> String {
    query.replacen(" WITH ", " UNWIND [1] AS oracle_one WITH ", 1)
}

fn compare(fixture: &Fixture, query: &str, probe: bool, cap: Option<usize>) -> QueryResult {
    let executor = || {
        let ex = fixture.executor();
        if probe {
            ex.with_probe()
        } else {
            ex
        }
    };
    let actual = executor().execute(query, cap).unwrap();
    let expected = executor().execute(&generic(query), cap).unwrap();
    assert_eq!(format!("{actual:?}"), format!("{expected:?}"), "{query}");
    actual
}

#[test]
fn only_total_scalar_projection_filter_and_plain_limit_are_admitted() {
    let target =
        "MATCH (n:CallSite) WITH n.callee_class AS c WHERE c IS NOT NULL RETURN c LIMIT 200";
    assert_eq!(admitted(target), Some(200));
    assert_eq!(admitted("MATCH (site:CallSiteNode) WITH site.line AS position, site.caller_name AS owner WHERE position IS NOT NULL RETURN owner, position LIMIT 0"), Some(0));
    for query in [
        "MATCH (n:CallSite) WITH n.line AS c WHERE c IS NOT NULL RETURN c ORDER BY c LIMIT 1",
        "MATCH (n:CallSite) WITH n.line AS c WHERE c IS NOT NULL RETURN c SKIP 1 LIMIT 1",
        "MATCH (n:CallSite) WITH DISTINCT n.line AS c WHERE c IS NOT NULL RETURN c LIMIT 1",
        "MATCH (n:CallSite) WITH n.line AS c WHERE c IS NOT NULL RETURN DISTINCT c LIMIT 1",
        "MATCH (n:CallSite) WITH n.line AS c WHERE c IS NOT NULL RETURN count(c) LIMIT 1",
        "MATCH (n:CallSite) WITH count(n) AS c WHERE c IS NOT NULL RETURN c LIMIT 1",
        "MATCH (n:CallSite) WITH n.line AS c WHERE c IS NOT NULL WITH c WHERE c > 10 RETURN c LIMIT 1",
        "MATCH (n:CallSite) WITH n.line AS c WHERE c IS NOT NULL RETURN c LIMIT $limit",
        "MATCH (n:CallSite) WITH n.line AS c WHERE c IS NOT NULL RETURN c LIMIT -1",
        "MATCH (n:CallSite) WITH n.line AS c WHERE c IS NOT NULL RETURN rand() LIMIT 1",
        "MATCH (n:CallSite) WITH timestamp() AS c WHERE c IS NOT NULL RETURN c LIMIT 1",
        "MATCH (n:CallSite) WITH n.line AS c WHERE rand() > 0 RETURN c LIMIT 1",
        "MATCH (n:CallSite) WITH n.line AS c, n.id AS c WHERE c IS NOT NULL RETURN c LIMIT 1",
        "MATCH (n:CallSite) WITH n.line AS c WHERE n IS NOT NULL RETURN c LIMIT 1",
        "MATCH (n:CallSite) WITH n.generic_type AS c WHERE c IS NOT NULL RETURN c LIMIT 1",
        "MATCH (n:Field) WITH n.type_info AS c WHERE c IS NOT NULL RETURN c LIMIT 1",
        "MATCH (n:Parameter) WITH n.generic_type AS c WHERE c IS NOT NULL RETURN c LIMIT 1",
        "MATCH (n:Annotation) WITH n.dynamic AS c WHERE c IS NOT NULL RETURN c LIMIT 1",
        "MATCH (n:ResourceValue) WITH n.value AS c WHERE c IS NOT NULL RETURN c LIMIT 1",
        "MATCH (n:CallSite) WITH n.callee_name AS c WHERE c =~ '[' RETURN c LIMIT 1",
        "MATCH (n:Constant) WITH n.value AS c WHERE c IS NOT NULL RETURN c LIMIT 1",
        "MATCH (n) WITH n.callee_class AS c WHERE c IS NOT NULL RETURN c LIMIT 1",
        "MATCH (n:CallSite {line: 1}) WITH n.line AS c WHERE c IS NOT NULL RETURN c LIMIT 1",
        "MATCH (n:CallSite) WHERE n.line > 1 WITH n.line AS c WHERE c IS NOT NULL RETURN c LIMIT 1",
    ] {
        assert_eq!(admitted(query), None, "{query}");
    }
    assert!(projected_filter_limit(&parse(target).unwrap(), &[]).is_none());
    assert!(projected_filter_limit(
        &parse(target).unwrap(),
        &[Row::from([("bound".into(), Value::Int(1))])]
    )
    .is_none());
}

#[test]
fn accepted_rows_keep_order_duplicates_and_cross_graph_provenance() {
    let fixture = Fixture::new(&[
        &[None, Some(10), None, Some(10)],
        &[None, Some(20), Some(30), None],
    ]);
    let query =
        "MATCH (n:CallSite) WITH n.line AS c, n.id AS i WHERE c IS NOT NULL RETURN c, i LIMIT 3";
    let rows = compare(&fixture, query, false, None).rows;
    assert_eq!(rows.len(), 3);
    for (row, (line, id, graph)) in
        rows.iter()
            .zip([(10, 1, "graph-0"), (10, 3, "graph-0"), (20, 1, "graph-1")])
    {
        assert!(matches!(row["c"], Value::Int(n) if n == line));
        assert!(matches!(row["i"], Value::Int(n) if n == id));
        assert_eq!(QueryResult::graph_ids(row), [graph]);
    }
    // Scalar aliases are ordinary projected bindings, including shadowing n.
    compare(
        &fixture,
        "MATCH (n:CallSite) WITH n.line AS n WHERE n IS NOT NULL RETURN n LIMIT 3",
        false,
        None,
    );
}

#[test]
fn filtered_limits_and_http_probe_count_only_accepted_rows() {
    let fixture = Fixture::new(&[&[None, Some(10), None], &[Some(20), Some(30), None]]);
    for limit in 0..=4 {
        let query = format!(
            "MATCH (n:CallSite) WITH n.line AS c WHERE c IS NOT NULL RETURN c LIMIT {limit}"
        );
        for probe in [false, true] {
            let result = compare(&fixture, &query, probe, None);
            assert_eq!(result.rows.len(), limit.min(3));
            assert_eq!(result.more, probe && limit < 3);
        }
    }
    let capped = compare(
        &fixture,
        "MATCH (n:CallSite) WITH n.line AS c WHERE c IS NOT NULL RETURN c LIMIT 200",
        true,
        Some(2),
    );
    assert_eq!(capped.rows.len(), 2);
    assert!(capped.more);
    let empty = Fixture::new(&[&[], &[None, None]]);
    let result = compare(
        &empty,
        "MATCH (n:CallSite) WITH n.line AS c WHERE c IS NOT NULL RETURN c LIMIT 1",
        true,
        None,
    );
    assert_eq!(result.columns, ["c"]);
    assert!(result.rows.is_empty() && !result.more);
}

#[test]
fn ineligible_shapes_preserve_complete_aggregation_order_and_late_errors() {
    let fixture = Fixture::new(&[&[Some(10), Some(10), None], &[Some(10), Some(20), Some(30)]]);
    for query in [
        "MATCH (n:CallSite) WITH n.line AS c WHERE c IS NOT NULL RETURN c ORDER BY c DESC LIMIT 1",
        "MATCH (n:CallSite) WITH n.line AS c WHERE c IS NOT NULL RETURN DISTINCT c LIMIT 1",
        "MATCH (n:CallSite) WITH n.line AS c WHERE c IS NOT NULL RETURN count(c) AS total LIMIT 1",
        "MATCH (n:CallSite) WITH n.line AS c WHERE c IS NOT NULL RETURN c SKIP 1 LIMIT 2",
    ] {
        compare(&fixture, query, true, None);
    }
    for prefix in [
        "MATCH (n:CallSite) WITH n.line AS c, 1 / (n.line - 30) AS d WHERE c IS NOT NULL RETURN c",
        "MATCH (n:CallSite) WITH n.line AS c WHERE 1 / (c - 30) > 0 RETURN c",
        "MATCH (n:CallSite) WITH n.line AS c WHERE c IS NOT NULL RETURN 1 / (c - 30) AS d",
    ] {
        for limit in [0, 1] {
            let query = format!("{prefix} LIMIT {limit}");
            assert_eq!(admitted(&query), None);
            let actual = fixture.executor().execute(&query, None).unwrap_err();
            let expected = fixture
                .executor()
                .execute(&generic(&query), None)
                .unwrap_err();
            assert!(
                matches!(&actual, CypherError::Runtime(message) if message == "Division by zero")
            );
            assert_eq!(actual, expected, "{query}");
        }
    }
}

#[test]
fn cancellation_and_polling_remain_active_for_rejected_candidates() {
    let lines = vec![None; 2050];
    let fixture = Fixture::new(&[&lines]);
    let query = "MATCH (n:CallSite) WITH n.line AS c WHERE c IS NOT NULL RETURN c LIMIT 1";
    for limit in [0, 1] {
        let checked_query = format!(
            "MATCH (n:CallSite) WITH n.line AS c WHERE c IS NOT NULL RETURN c LIMIT {limit}"
        );
        let token = CancelToken::new();
        token.cancel();
        assert!(
            matches!(
                fixture
                    .executor()
                    .with_cancel(token)
                    .execute(&checked_query, None),
                Err(CypherError::Cancelled)
            ),
            "LIMIT {limit} must honor prior cancellation"
        );
        assert!(
            matches!(
                fixture
                    .executor()
                    .with_cancel(CancelToken::with_timeout(0))
                    .execute(&checked_query, None),
                Err(CypherError::Timeout(0))
            ),
            "LIMIT {limit} must honor an expired deadline"
        );
    }
    let ex = fixture.executor();
    assert!(ex.execute(query, None).unwrap().rows.is_empty());
    assert!(
        ex.poll.load(Ordering::Relaxed) >= 4100,
        "matcher and filter poll every rejected row"
    );
}

#[test]
fn the_row_limit_stops_candidate_access_after_enough_filtered_rows() {
    let fixture = Fixture::new(&[&vec![Some(10); 64], &vec![Some(20); 64]]);
    let query = "MATCH (n:CallSite) WITH n.callee_class AS c WHERE c IS NOT NULL RETURN c LIMIT 2";
    let optimized = fixture.executor();
    let actual = optimized.execute(query, None).unwrap();
    let plain = fixture.executor();
    let expected = plain.execute(&generic(query), None).unwrap();
    assert_eq!(format!("{actual:?}"), format!("{expected:?}"));
    assert!(
        optimized.poll.load(Ordering::Relaxed) < 16,
        "the accepted prefix stops the existing matcher"
    );
    assert!(
        plain.poll.load(Ordering::Relaxed) > 128,
        "the correctness oracle consumes the complete input"
    );
}

#[test]
fn schema_scalar_filters_keep_values_order_and_provenance_across_tags() {
    let method = json!({"declaringClass":"fixture.App","name":"f","parameterTypes":["string"],"returnType":"string"});
    let fixture = Fixture::from_inputs(["alpha","beta"].map(|name| json!({
        "version":1, "methods":[method], "edges":[], "nodes":[
            {"id":0,"kind":"Field","declaringClass":"C","name":"x","fieldType":"string","isStatic":false},
            {"id":1,"kind":"Field","declaringClass":"C","name":name,"fieldType":"string","isStatic":true},
            {"id":2,"kind":"Parameter","method":method,"index":0,"paramType":name},
            {"id":3,"kind":"Return","method":method,"actualType":null},
            {"id":4,"kind":"Return","method":method,"actualType":name}
        ]
    })).to_vec());
    for query in [
        "MATCH (n:Field) WITH n.name AS c, n.static AS s WHERE c <> 'x' AND s = true RETURN c LIMIT 2",
        "MATCH (n:Parameter) WITH n.type AS c, n.index AS i WHERE c IS NOT NULL AND i = 0 RETURN c LIMIT 2",
        "MATCH (n:ReturnNode) WITH n.actual_type AS c WHERE c IS NOT NULL AND c ENDS WITH 'a' RETURN c LIMIT 2",
    ] {
        assert_eq!(admitted(query), Some(2));
        let result = compare(&fixture, query, true, None);
        assert_eq!(result.columns, ["c"]);
        assert!(!result.more);
        assert_eq!(result.rows.len(), 2);
        for (row, (value, graph)) in result.rows.iter().zip([("alpha","graph-0"),("beta","graph-1")]) {
            assert_eq!(row["c"].as_str(), Some(value));
            assert_eq!(QueryResult::graph_ids(row), [graph]);
        }
    }
    let query = "MATCH (n:ReturnNode) WITH n.actual_type AS c, 'missing' AS label WHERE c IS NULL RETURN label LIMIT 1";
    assert_eq!(admitted(query), Some(1));
    let result = compare(&fixture, query, true, None);
    assert!(result.more);
    assert_eq!(result.rows[0]["label"].as_str(), Some("missing"));
    assert_eq!(QueryResult::graph_ids(&result.rows[0]), ["graph-0"]);
}

#[test]
fn both_sides_of_a_conjunction_keep_late_errors_visible() {
    let fixture = Fixture::new(&[&[Some(10)], &[Some(30)]]);
    let query = "MATCH (n:CallSite) WITH n.line AS c WHERE c IS NOT NULL AND CASE WHEN c = 30 THEN 'x' =~ '[' ELSE true END RETURN c LIMIT 1";
    assert_eq!(admitted(query), None);
    let actual = fixture.executor().execute(query, None).unwrap_err();
    assert!(
        matches!(&actual, CypherError::Other(message) if message.contains("Unsupported regex"))
    );
    assert_eq!(
        actual,
        fixture
            .executor()
            .execute(&generic(query), None)
            .unwrap_err()
    );
}

#[test]
fn persisted_jvm_scalar_filters_match_generic_rows_and_probe() {
    let Some(dir) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
        return;
    };
    let graph = Arc::new(Graph::load(std::path::Path::new(&dir)).unwrap());
    let executor = || {
        Executor::new(
            ["first", "second"]
                .map(|id| Source {
                    id: Arc::from(id),
                    graph: graph.clone(),
                })
                .to_vec(),
            true,
        )
        .with_probe()
    };
    for query in [
        "MATCH (n:Field) WITH n.name AS c WHERE c <> 'x' RETURN c LIMIT 3",
        "MATCH (n:Parameter) WITH n.type AS c, n.index AS i WHERE c IS NOT NULL AND i >= 0 RETURN c, i LIMIT 3",
        "MATCH (n:ReturnNode) WITH n.id AS i WHERE i IS NOT NULL RETURN i LIMIT 3",
    ] {
        assert_eq!(admitted(query), Some(3));
        let actual = executor().execute(query, None).unwrap();
        let expected = executor().execute(&generic(query), None).unwrap();
        assert_eq!(format!("{actual:?}"), format!("{expected:?}"), "{query}");
        assert_eq!(actual.rows.len(), 3, "{query}");
        assert!(actual.more, "{query}");
        assert_eq!(QueryResult::graph_ids(&actual.rows[0]), ["first"]);
    }
}

#[test]
fn late_bad_callsite_sids_decline_filtered_early_stop_and_keep_eager_first_error() {
    use graphite_storage::node::{NODE_HEADER_BYTES, TAG_CALL_SITE_NODE};
    let Some(path) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
        assert!(std::env::var_os("GRAPHITE_REQUIRE_ALL_TYPES_FIXTURES").is_none());
        return;
    };
    let graph = Arc::new(Graph::load(std::path::Path::new(&path)).unwrap());
    assert!(graph.dir.join("graph.callsite-string-index").is_file());
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let root = std::env::temp_dir().join(format!(
        "graphite-filter-bad-sid-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    std::fs::create_dir(&root).unwrap();
    let fixture = Fixture {
        root,
        graphs: vec![graph.clone(), graph],
    };
    let damaged = |source: usize, sid: u32| {
        let original = &fixture.graphs[source];
        let path = fixture.root.join(format!("invalid-sid-{sid}"));
        std::fs::create_dir(&path).unwrap();
        for entry in std::fs::read_dir(&original.dir).unwrap() {
            let entry = entry.unwrap();
            if entry.file_type().unwrap().is_file() && entry.file_name() != "graph.nodedata" {
                std::fs::hard_link(entry.path(), path.join(entry.file_name())).unwrap();
            }
        }
        let id = *original.ids_by_tag(TAG_CALL_SITE_NODE).last().unwrap();
        let offset = original.node_offset(id).unwrap();
        let mut bytes = original.nodedata().to_vec();
        bytes[offset + NODE_HEADER_BYTES..offset + NODE_HEADER_BYTES + 4]
            .copy_from_slice(&sid.to_be_bytes());
        std::fs::write(path.join("graph.nodedata"), bytes).unwrap();
        Arc::new(Graph::load(&path).unwrap())
    };
    let bad_first = damaged(0, u32::MAX - 1);
    let bad_late = damaged(1, u32::MAX);
    let payload = |result: std::thread::Result<crate::CypherResult<QueryResult>>| {
        let panic = result.expect_err("the original complete projection must expose the bad SID");
        panic
            .downcast_ref::<String>()
            .cloned()
            .unwrap_or_else(|| panic.downcast_ref::<&str>().unwrap().to_string())
    };
    for first_bad in [false, true] {
        let input = vec![
            Source {
                id: Arc::from("first"),
                graph: if first_bad {
                    bad_first.clone()
                } else {
                    fixture.graphs[0].clone()
                },
            },
            Source {
                id: Arc::from("later"),
                graph: bad_late.clone(),
            },
        ];
        let ex = Executor::new(input, true);
        for limit in [0, 1] {
            // The ordered comparison cannot use WithStreamPlan. It exercises
            // main's projected-filter path and its own source certificate.
            // IS NOT NULL additionally covers the shared admission/fallback path.
            for predicate in ["c > ''", "c IS NOT NULL"] {
                let query = format!("MATCH (n:CallSite) WITH n.caller_class AS c WHERE {predicate} RETURN c LIMIT {limit}");
                let clauses = parse(&query).unwrap();
                assert_eq!(projected_filter_limit(&clauses, &[Row::new()]), Some(limit));
                assert_eq!(
                    validated_projected_filter_limit(&ex, &clauses, &[Row::new()]).unwrap(),
                    None
                );
                let run = |query: &str| {
                    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                        ex.execute(query, None)
                    }))
                };
                let actual = payload(run(&query));
                let expected = payload(run(&generic(&query)));
                assert_eq!(actual, expected, "first_bad={first_bad}: {query}");
                assert!(
                    actual.contains(if first_bad {
                        "4294967294"
                    } else {
                        "4294967295"
                    }),
                    "{actual}"
                );
            }
        }
    }
}

#[test]
fn uncertified_field_string_getters_keep_late_bad_sids_on_eager_path() {
    use graphite_storage::node::{NODE_HEADER_BYTES, TAG_FIELD_NODE};
    let method = json!({"declaringClass":"C","name":"f","parameterTypes":[],"returnType":"void"});
    let fixture = Fixture::from_inputs(["first", "later"].map(|name| json!({
        "version":1,"methods":[method],"edges":[],"nodes":[
            {"id":0,"kind":"Field","declaringClass":"C","name":name,"fieldType":"string","isStatic":false},
            {"id":1,"kind":"Field","declaringClass":"C","name":"bad-tail","fieldType":"string","isStatic":false}
        ]
    })).to_vec());
    let original = &fixture.graphs[1];
    let path = fixture.root.join("bad-field-sid");
    std::fs::create_dir(&path).unwrap();
    for entry in std::fs::read_dir(&original.dir).unwrap() {
        let entry = entry.unwrap();
        if entry.file_type().unwrap().is_file() && entry.file_name() != "graph.nodedata" {
            std::fs::hard_link(entry.path(), path.join(entry.file_name())).unwrap();
        }
    }
    let offset = original
        .node_offset(*original.ids_by_tag(TAG_FIELD_NODE).last().unwrap())
        .unwrap();
    let mut bytes = original.nodedata().to_vec();
    bytes[offset + NODE_HEADER_BYTES + 4..offset + NODE_HEADER_BYTES + 8]
        .copy_from_slice(&u32::MAX.to_be_bytes());
    std::fs::write(path.join("graph.nodedata"), bytes).unwrap();
    let bad = Arc::new(Graph::load(&path).unwrap());
    let ex = Executor::new(
        vec![
            Source {
                id: Arc::from("first"),
                graph: fixture.graphs[0].clone(),
            },
            Source {
                id: Arc::from("later"),
                graph: bad,
            },
        ],
        true,
    );
    for limit in [0, 1] {
        let query =
            format!("MATCH (n:Field) WITH n.name AS c WHERE c IS NOT NULL RETURN c LIMIT {limit}");
        let clauses = parse(&query).unwrap();
        assert_eq!(projected_filter_limit(&clauses, &[Row::new()]), Some(limit));
        assert_eq!(
            validated_projected_filter_limit(&ex, &clauses, &[Row::new()]).unwrap(),
            None
        );
        let payload = |query: &str| {
            let panic =
                std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| ex.execute(query, None)))
                    .expect_err("a later Field.name SID is not total");
            panic
                .downcast_ref::<String>()
                .cloned()
                .unwrap_or_else(|| panic.downcast_ref::<&str>().unwrap().to_string())
        };
        assert_eq!(payload(&query), payload(&generic(&query)));
        let literal =
            format!("MATCH(n:Field) WITH 'fixed' AS c WHERE c IS NOT NULL RETURN c LIMIT {limit}");
        assert_eq!(
            validated_projected_filter_limit(&ex, &parse(&literal).unwrap(), &[Row::new()])
                .unwrap(),
            Some(limit)
        );
        let id = format!("MATCH(n:Field) WITH n.id AS c WHERE c >= 0 RETURN c LIMIT {limit}");
        assert_eq!(
            validated_projected_filter_limit(&ex, &parse(&id).unwrap(), &[Row::new()]).unwrap(),
            Some(limit)
        );
        assert_eq!(
            format!("{:?}", ex.execute(&id, None).unwrap()),
            format!("{:?}", ex.execute(&generic(&id), None).unwrap())
        );
    }
}
