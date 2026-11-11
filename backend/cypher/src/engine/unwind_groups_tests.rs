//! Correctness only; no synthetic or single-source performance measurements.
use super::*;
use crate::engine::{CancelToken, Source};
use crate::parser::parse;
use crate::CypherDialect;
use std::sync::atomic::Ordering as AtomicOrdering;

const TAIL: &str = "UNWIND targets AS l UNWIND targets AS r \
    WITH graph, caller, l, r WHERE l < r \
    RETURN l, r, count(*) AS pairs, count(DISTINCT graph) AS graphs";

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

fn seed(graph: &str, values: Vec<Value>, provenance: &str) -> Row {
    let mut row = Row::from([
        ("graph".into(), Value::str(graph)),
        ("caller".into(), Value::str("same-caller")),
        ("targets".into(), Value::list(values)),
    ]);
    add_provenance_id(&mut row, Arc::from(provenance));
    row
}

fn strings(values: &[&str]) -> Vec<Value> {
    values.iter().map(|s| Value::str(*s)).collect()
}

fn same(actual: &QueryResult, expected: &QueryResult) {
    assert_eq!(actual.columns, expected.columns);
    assert_eq!(format!("{:?}", actual.rows), format!("{:?}", expected.rows));
    assert_eq!(actual.more, expected.more);
    assert!(actual.compact.is_none());
}

fn compared(ex: &Executor, query: &str, seeds: Vec<Row>, admitted: bool) -> QueryResult {
    let clauses = parse(query).unwrap();
    let before = HITS.with(|hits| hits.get());
    let actual = ex.run_segment_seeded(&clauses, seeds.clone()).unwrap();
    assert_eq!(HITS.with(|hits| hits.get()) > before, admitted, "{query}");
    let expected = original(|| ex.run_segment_seeded(&clauses, seeds)).unwrap();
    same(&actual, &expected);
    actual
}

#[test]
fn pair_stream_keeps_multiplicity_distinct_graphs_provenance_and_final_order() {
    for dialect in [CypherDialect::Graphite, CypherDialect::OpenCypherM23] {
        let ex = Executor::new(vec![], true).with_dialect(dialect);
        let rows = vec![
            seed("gA", strings(&["Y", "X", "X"]), "z"),
            seed("gB", strings(&["X", "Y"]), "a"),
            seed("gA", strings(&["Y", "X"]), "b"),
            seed("empty", vec![], "not-contributing"),
        ];
        let result = compared(&ex, TAIL, rows.clone(), true);
        assert_eq!(result.columns, ["l", "r", "pairs", "graphs"]);
        assert_eq!(result.rows.len(), 1);
        let row = &result.rows[0];
        assert_eq!(row["l"].as_str(), Some("X"));
        assert_eq!(row["r"].as_str(), Some("Y"));
        assert!(matches!(row["pairs"], Value::Int(4)));
        assert!(matches!(row["graphs"], Value::Int(2)));
        assert_eq!(QueryResult::graph_ids(row), ["a", "b", "z"]);
        for suffix in [
            " ORDER BY pairs DESC, l, r LIMIT 1",
            " ORDER BY l SKIP 1 LIMIT 1",
            " LIMIT 0",
        ] {
            compared(&ex, &format!("{TAIL}{suffix}"), rows.clone(), true);
        }
        let by_caller = compared(
            &ex,
            &TAIL.replace("count(DISTINCT graph)", "count(DISTINCT caller)"),
            rows.clone(),
            true,
        );
        assert!(matches!(by_caller.rows[0]["graphs"], Value::Int(1)));
        let empty = compared(&ex, TAIL, vec![], true);
        assert!(empty.rows.is_empty());
        let empty_lists = compared(&ex, TAIL, vec![seed("g", vec![], "g")], true);
        assert!(empty_lists.rows.is_empty());
    }
}

#[test]
fn pair_comparison_uses_utf16_order_not_list_position_or_utf8() {
    for dialect in [CypherDialect::Graphite, CypherDialect::OpenCypherM23] {
        let ex = Executor::new(vec![], true).with_dialect(dialect);
        let result = compared(
            &ex,
            TAIL,
            vec![seed("g", strings(&["\u{e000}", "\u{10000}", "a"]), "g")],
            true,
        );
        let pairs: Vec<_> = result
            .rows
            .iter()
            .map(|row| (row["l"].as_str().unwrap(), row["r"].as_str().unwrap()))
            .collect();
        assert_eq!(
            pairs,
            [
                ("\u{10000}", "\u{e000}"),
                ("a", "\u{e000}"),
                ("a", "\u{10000}")
            ]
        );
        assert!(result
            .rows
            .iter()
            .all(|row| matches!(row["pairs"], Value::Int(1))
                && matches!(row["graphs"], Value::Int(1))));
    }
}

#[test]
fn admission_declines_non_total_shapes_before_consuming_rows() {
    for query in [
        TAIL.replace("UNWIND targets AS r", "UNWIND other AS r"),
        TAIL.replace("UNWIND targets AS l", "UNWIND targets AS targets"),
        TAIL.replace("WHERE l < r", "WHERE l <= r"),
        TAIL.replace("WHERE l < r", "WHERE size(l) < size(r)"),
        TAIL.replace(
            "WITH graph, caller, l, r",
            "WITH DISTINCT graph, caller, l, r",
        ),
        TAIL.replace(
            "WITH graph, caller, l, r",
            "WITH graph, caller, l AS other, r",
        ),
        TAIL.replace(
            "WITH graph, caller, l, r",
            "WITH graph, caller, l, toUpper(r) AS r",
        ),
        TAIL.replace("count(*) AS pairs", "sum(1) AS pairs"),
        TAIL.replace("AS graphs", "AS pairs"),
        TAIL.replace("count(DISTINCT graph)", "count(DISTINCT l)"),
        TAIL.replace("count(DISTINCT graph)", "count(graph)"),
        TAIL.replace(
            "count(DISTINCT graph)",
            "count(DISTINCT missing_function(graph))",
        ),
        format!("{TAIL} ORDER BY missing_function(l)"),
    ] {
        assert!(
            PairPlan::build(&parse(&query).unwrap()).is_none(),
            "{query}"
        );
    }
    let mut internal_alias = parse(TAIL).unwrap();
    let Clause::Return {
        items: Some(items), ..
    } = &mut internal_alias[3]
    else {
        unreachable!()
    };
    items[2].alias = Some("\0private".into());
    assert!(PairPlan::build(&internal_alias).is_none());
    let ex = Executor::new(vec![], true);
    let clauses = parse(TAIL).unwrap();
    let ev = Evaluator::new(&ex, &ex.params);
    let mut shadowed = seed("g", strings(&["a", "b"]), "g");
    shadowed.insert("l".into(), Value::str("existing"));
    let mut weighted = seed("g", strings(&["a", "b"]), "g");
    weighted.insert(INTERNAL_WEIGHT_KEY.into(), Value::Int(1));
    let mut missing = seed("g", strings(&["a", "b"]), "g");
    missing.shift_remove("caller");
    let mut not_list = seed("g", vec![], "g");
    not_list.insert("targets".into(), Value::str("ab"));
    let mut null_list = seed("g", vec![], "g");
    null_list.insert("targets".into(), Value::Null);
    for row in [
        not_list,
        null_list,
        seed("g", vec![Value::Int(1), Value::Int(2)], "g"),
        seed("g", vec![Value::str("x"), Value::Null], "g"),
        seed("g", vec![Value::Float(1.0), Value::Int(2)], "g"),
        shadowed,
        weighted,
        missing,
    ] {
        assert!(try_execute(&ex, &ev, &clauses, &[row]).unwrap().is_none());
    }
    for dialect in [CypherDialect::Graphite, CypherDialect::OpenCypherM23] {
        let ex = Executor::new(vec![], true).with_dialect(dialect);
        for values in [
            vec![Value::Int(2), Value::Int(1)],
            vec![Value::str("x"), Value::Null],
        ] {
            compared(&ex, TAIL, vec![seed("g", values, "g")], false);
        }
    }
}

#[test]
fn late_expression_errors_are_not_hidden_by_filtered_pairs_or_limit_zero() {
    for dialect in [CypherDialect::Graphite, CypherDialect::OpenCypherM23] {
        let ex = Executor::new(vec![], true).with_dialect(dialect);
        for query in [
            format!(
                "{} LIMIT 0",
                TAIL.replace("count(*) AS pairs", "sum(missing_function(l)) AS pairs")
            ),
            format!(
                "{} LIMIT 0",
                TAIL.replace("WHERE l < r", "WHERE l < r AND missing_function(l)")
            ),
            format!("{TAIL} ORDER BY missing_function(l) LIMIT 0"),
        ] {
            let clauses = parse(&query).unwrap();
            let rows = vec![seed("g", strings(&["b", "a"]), "g")];
            let before = HITS.with(|hits| hits.get());
            let actual = ex.run_segment_seeded(&clauses, rows.clone()).unwrap_err();
            let expected = original(|| ex.run_segment_seeded(&clauses, rows)).unwrap_err();
            assert_eq!(
                format!("{actual:?}"),
                format!("{expected:?}"),
                "{dialect:?}: {query}"
            );
            assert_eq!(HITS.with(|hits| hits.get()), before);
        }
    }
}

#[test]
fn bounded_tokens_keep_original_charges_and_cancelled_tokens_never_succeed() {
    let clauses = parse(TAIL).unwrap();
    let rows = vec![seed("g", strings(&["b", "a", "c"]), "g")];
    for limit in [0, 1, 3, 10, 1000] {
        let token = || CancelToken::child_with_limits(CancelToken::new(), 60_000, limit);
        let fast = Executor::new(vec![], true).with_cancel(token());
        let slow = Executor::new(vec![], true).with_cancel(token());
        let before = HITS.with(|hits| hits.get());
        let actual = fast.run_segment_seeded(&clauses, rows.clone());
        let expected = original(|| slow.run_segment_seeded(&clauses, rows.clone()));
        match (actual, expected) {
            (Ok(a), Ok(b)) => same(&a, &b),
            (Err(a), Err(b)) => assert_eq!(format!("{a:?}"), format!("{b:?}")),
            other => panic!("different budget outcome: {other:?}"),
        }
        assert_eq!(HITS.with(|hits| hits.get()), before);
        assert_eq!(
            fast.cancel
                .work
                .as_ref()
                .unwrap()
                .consumed
                .load(AtomicOrdering::Relaxed),
            slow.cancel
                .work
                .as_ref()
                .unwrap()
                .consumed
                .load(AtomicOrdering::Relaxed)
        );
    }
    let parent = CancelToken::child_with_limits(CancelToken::new(), 60_000, 1000);
    let child = CancelToken {
        parent: Some(parent),
        ..CancelToken::default()
    };
    let ex = Executor::new(vec![], true).with_cancel(Arc::new(child));
    assert!(has_work_budget(&ex));
    assert!(
        try_execute(&ex, &Evaluator::new(&ex, &ex.params), &clauses, &rows)
            .unwrap()
            .is_none()
    );
    for token in [CancelToken::new(), CancelToken::with_timeout(0)] {
        if token.deadline.lock().is_none() {
            token.cancel();
        }
        let ex = Executor::new(vec![], true).with_cancel(token);
        let error =
            try_execute(&ex, &Evaluator::new(&ex, &ex.params), &clauses, &rows).unwrap_err();
        assert!(matches!(
            error,
            CypherError::Cancelled | CypherError::Timeout(0)
        ));
    }
}

#[test]
fn native_multi_source_pairs_match_retained_pipeline_and_http_probe() {
    let Some(path) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
        assert!(std::env::var_os("GRAPHITE_REQUIRE_ALL_TYPES_FIXTURES").is_none());
        return;
    };
    let graph = Arc::new(graphite_storage::Graph::load(std::path::Path::new(&path)).unwrap());
    let sources = || {
        ["z-first", "a-last"]
            .into_iter()
            .map(|id| Source {
                id: Arc::from(id),
                graph: graph.clone(),
            })
            .collect()
    };
    for dialect in [CypherDialect::Graphite, CypherDialect::OpenCypherM23] {
        let ex = Executor::new(sources(), true)
            .with_dialect(dialect)
            .with_probe();
        // Select two real stored classes to keep this a small correctness check,
        // never a performance sample. The measured catalog itself is not limited.
        let values = ex
            .execute(
                "MATCH(n:CallSite) RETURN DISTINCT n.callee_class AS target LIMIT 2",
                None,
            )
            .unwrap();
        assert_eq!(
            values.rows.len(),
            2,
            "native fixture needs two callee classes"
        );
        let selected: Vec<_> = values
            .rows
            .iter()
            .map(|row| row["target"].as_str().unwrap())
            .collect();
        let literal = serde_json::to_string(&selected).unwrap();
        let query = format!("MATCH(n:CallSite) WHERE n.callee_class IN {literal} WITH n.graphId AS graph, n.caller_class AS caller, collect(DISTINCT n.callee_class) AS targets {TAIL} ORDER BY pairs DESC, l, r LIMIT 1");
        let before = HITS.with(|hits| hits.get());
        let actual = ex.execute(&query, None).unwrap();
        assert!(HITS.with(|hits| hits.get()) > before);
        let expected = original(|| ex.execute(&query, None)).unwrap();
        same(&actual, &expected);
        // Also prove the tail on real derived bindings when both classes happen
        // not to co-occur in one caller of this fixture.
        let query = format!("MATCH(n:CallSite) WHERE n.callee_class IN {literal} WITH n.graphId AS graph, 'all' AS caller, collect(DISTINCT n.callee_class) AS targets {TAIL} ORDER BY pairs DESC, l, r LIMIT 1");
        let actual = ex.execute(&query, None).unwrap();
        let expected = original(|| ex.execute(&query, None)).unwrap();
        same(&actual, &expected);
        assert_eq!(actual.rows.len(), 1);
        assert!(matches!(actual.rows[0]["pairs"], Value::Int(2)));
        assert!(matches!(actual.rows[0]["graphs"], Value::Int(2)));
        assert_eq!(
            QueryResult::graph_ids(&actual.rows[0]),
            ["a-last", "z-first"]
        );
    }
}
