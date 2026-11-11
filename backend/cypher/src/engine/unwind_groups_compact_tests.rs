//! Deterministic correctness and materialization assertions, never timing gates.
use super::*;
use crate::engine::CancelToken;
use crate::parser::parse;
use crate::CypherDialect;

const TAIL: &str = "UNWIND targets AS l UNWIND targets AS r \
    WITH graph, caller, l, r WHERE l < r \
    RETURN l, r, count(*) AS pairs, count(DISTINCT graph) AS graphs \
    ORDER BY pairs DESC, l, r";

fn streaming<T>(run: impl FnOnce() -> T) -> T {
    struct Reset(bool);
    impl Drop for Reset {
        fn drop(&mut self) {
            DISABLED.with(|flag| flag.set(self.0));
        }
    }
    let _reset = Reset(DISABLED.with(|flag| flag.replace(true)));
    run()
}

fn generic<T>(run: impl FnOnce() -> T) -> T {
    struct Reset(bool);
    impl Drop for Reset {
        fn drop(&mut self) {
            super::super::DISABLED.with(|flag| flag.set(self.0));
        }
    }
    let _reset = Reset(super::super::DISABLED.with(|flag| flag.replace(true)));
    run()
}

fn seed(graph: &str, targets: &[&str], provenance: Option<&str>) -> Row {
    let mut row = Row::from([
        ("graph".into(), Value::str(graph)),
        ("caller".into(), Value::str("same-caller")),
        (
            "targets".into(),
            Value::list(targets.iter().map(|value| Value::str(*value)).collect()),
        ),
    ]);
    if let Some(provenance) = provenance {
        add_provenance_id(&mut row, Arc::from(provenance));
    }
    row
}

fn same(actual: &QueryResult, expected: &QueryResult) {
    assert_eq!(actual.columns, expected.columns);
    assert_eq!(format!("{:?}", actual.rows), format!("{:?}", expected.rows));
    assert_eq!(actual.more, expected.more);
    assert!(actual.compact.is_none());
}

fn compare(ex: &Executor, query: &str, rows: Vec<Row>, admitted: bool) -> QueryResult {
    let clauses = parse(query).unwrap();
    let before = HITS.with(|hits| hits.get());
    let materialized = MATERIALIZED.with(|count| count.get());
    let actual = ex.run_segment_seeded(&clauses, rows.clone()).unwrap();
    assert_eq!(HITS.with(|hits| hits.get()) > before, admitted, "{query}");
    assert_eq!(
        MATERIALIZED.with(|count| count.get()) - materialized,
        if admitted { actual.rows.len() } else { 0 }
    );
    let streamed = streaming(|| ex.run_segment_seeded(&clauses, rows.clone())).unwrap();
    let original = generic(|| ex.run_segment_seeded(&clauses, rows)).unwrap();
    same(&actual, &streamed);
    same(&actual, &original);
    actual
}

#[test]
fn compact_pairs_materialize_only_winners_after_complete_counts() {
    for dialect in [CypherDialect::Graphite, CypherDialect::OpenCypherM23] {
        let ex = Executor::new(vec![], true).with_dialect(dialect);
        let rows = vec![
            seed("g1", &["e", "d", "c", "b", "a", "a"], Some("z")),
            seed("g2", &["b", "a"], Some("a")),
            seed("g1", &["a", "b"], Some("m")),
            seed("empty", &[], Some("unused")),
        ];
        let actual = compare(&ex, &format!("{TAIL} LIMIT 3"), rows.clone(), true);
        assert_eq!(actual.rows.len(), 3);
        let pairs: Vec<_> = actual
            .rows
            .iter()
            .map(|row| (row["l"].as_str().unwrap(), row["r"].as_str().unwrap()))
            .collect();
        assert_eq!(pairs, [("a", "b"), ("a", "c"), ("a", "d")]);
        assert!(matches!(actual.rows[0]["pairs"], Value::Int(4)));
        assert!(matches!(actual.rows[0]["graphs"], Value::Int(2)));
        assert_eq!(QueryResult::graph_ids(&actual.rows[0]), ["a", "m", "z"]);
        for row in &actual.rows[1..] {
            assert!(matches!(row["pairs"], Value::Int(2)));
            assert!(matches!(row["graphs"], Value::Int(1)));
            assert_eq!(QueryResult::graph_ids(row), ["z"]);
        }
        compare(&ex, &format!("{TAIL} LIMIT 0"), rows.clone(), true);
        compare(&ex, &format!("{TAIL} LIMIT 65536"), rows.clone(), true);
        compare(&ex, &format!("{TAIL} LIMIT 3"), vec![], true);
        compare(
            &ex,
            &format!("{TAIL} LIMIT 3"),
            vec![seed("g", &[], None)],
            true,
        );
        let by_caller = compare(
            &ex,
            &format!(
                "{} LIMIT 1",
                TAIL.replace("count(DISTINCT graph)", "count(DISTINCT caller)")
            ),
            rows,
            true,
        );
        assert!(matches!(by_caller.rows[0]["graphs"], Value::Int(1)));
    }
}

#[test]
fn compact_unicode_ranks_aliases_and_provenance_use_their_original_orders() {
    for dialect in [CypherDialect::Graphite, CypherDialect::OpenCypherM23] {
        let ex = Executor::new(vec![], true).with_dialect(dialect);
        let rows = vec![
            seed("g1", &["\u{e000}", "\u{10000}", "a"], Some("\u{10000}")),
            seed("g2", &["a", "\u{10000}", "\u{e000}"], Some("\u{e000}")),
        ];
        let query = TAIL.replace("RETURN l, r, count(*) AS pairs, count(DISTINCT graph) AS graphs", "RETURN l AS left_value, r AS right_value, count(*) AS n, count(DISTINCT graph) AS support")
            .replace("ORDER BY pairs DESC, l, r", "ORDER BY n DESC, left_value, right_value");
        let result = compare(&ex, &format!("{query} LIMIT 3"), rows, true);
        assert_eq!(
            result.columns,
            ["left_value", "right_value", "n", "support"]
        );
        let pairs: Vec<_> = result
            .rows
            .iter()
            .map(|row| {
                (
                    row["left_value"].as_str().unwrap(),
                    row["right_value"].as_str().unwrap(),
                )
            })
            .collect();
        assert_eq!(
            pairs,
            [
                ("a", "\u{10000}"),
                ("a", "\u{e000}"),
                ("\u{10000}", "\u{e000}")
            ]
        );
        for row in &result.rows {
            assert!(matches!(row["n"], Value::Int(2)));
            assert!(matches!(row["support"], Value::Int(2)));
            // Provenance uses the existing Rust string ordering, unlike ORDER BY.
            assert_eq!(QueryResult::graph_ids(row), ["\u{e000}", "\u{10000}"]);
        }
    }
}

#[test]
fn wide_dictionaries_are_independent_and_preserve_word_boundaries() {
    for dialect in [CypherDialect::Graphite, CypherDialect::OpenCypherM23] {
        let ex = Executor::new(vec![], true).with_dialect(dialect);
        for size in [64, 65, 130, 257, 1025] {
            for (distinct_many, provenance_many) in [(true, true), (true, false), (false, true)] {
                let mut rows: Vec<_> = (0..size)
                    .map(|i| {
                        seed(
                            &format!("g{}", if distinct_many { i } else { 0 }),
                            &["b", "a"],
                            Some(&format!(
                                "p{:04}",
                                if provenance_many { size - 1 - i } else { 0 }
                            )),
                        )
                    })
                    .collect();
                // Repeat memberships on both sides of each multiword boundary.
                for i in [0, 63, 64, 127, 128, 256, 1023, 1024] {
                    if i < size {
                        rows.push(rows[i].clone());
                    }
                }
                // Another pair must have an independent arena slice, and a
                // missing provenance must not manufacture a provenance value.
                rows.push(seed("g0", &["a", "c"], None));
                let pairs = rows.len() - 1;
                let result = compare(&ex, &format!("{TAIL} LIMIT 2"), rows.clone(), true);
                assert_eq!(result.rows.len(), 2);
                assert!(matches!(result.rows[0]["pairs"], Value::Int(n) if n == pairs as i64));
                assert!(
                    matches!(result.rows[0]["graphs"], Value::Int(n) if n == if distinct_many { size as i64 } else { 1 })
                );
                let expected: Vec<_> = (0..if provenance_many { size } else { 1 })
                    .map(|i| format!("p{i:04}"))
                    .collect();
                assert_eq!(QueryResult::graph_ids(&result.rows[0]), expected);
                assert!(matches!(result.rows[1]["pairs"], Value::Int(1)));
                assert!(matches!(result.rows[1]["graphs"], Value::Int(1)));
                assert!(!result.rows[1].contains_key(INTERNAL_PROVENANCE_KEY));
                compare(&ex, &format!("{TAIL} LIMIT 0"), rows, true);
            }
        }
    }
}

#[test]
fn sparse_masks_store_only_present_words_without_an_admission_cap() {
    let ex = Executor::new(vec![], true);
    let clauses = parse(&format!("{TAIL} LIMIT 2")).unwrap();
    let plan = PairPlan::build(&clauses).unwrap();
    let rows: Vec<_> = (0..1025)
        .map(|i| seed(&format!("g{i}"), &["a", "b"], Some(&format!("p{i}"))))
        .collect();
    let prepared = prepare(&ex, &plan, &rows).unwrap().unwrap();
    let mut masks = ExtraMasks::new(prepared.distinct_count, prepared.provenance_names.len());
    assert!(matches!(masks.storage, MaskStorage::Sparse(_)));
    let groups = accumulate::<true>(&ex, &prepared, &mut masks).unwrap();
    assert_eq!(groups.len(), 1);
    let MaskStorage::Sparse(words) = &masks.storage else {
        unreachable!()
    };
    assert_eq!(words.len(), 32); // 16 occupied high words in each independent dictionary.
    assert_eq!(
        masks.distinct_count(&ex, groups[0].distinct, 0).unwrap(),
        1025
    );
    assert_eq!(masks.word(1, 0), 0); // Absent group/word never creates a stored entry.
    assert_eq!(words.len(), 32);
}

#[test]
fn one_source_merges_multiple_high_provenance_words_and_keeps_pairs_separate() {
    for dialect in [CypherDialect::Graphite, CypherDialect::OpenCypherM23] {
        let ex = Executor::new(vec![], true).with_dialect(dialect);
        let mut all = seed("g", &["a", "b"], None);
        // More than 1024 high-word memberships exercises the periodic inner
        // cancellation-check boundary as well as the first contribution.
        let names: Vec<_> = (0..1154).map(|i| format!("p{i:04}")).collect();
        for name in &names {
            add_provenance_id(&mut all, Arc::from(name.as_str()));
        }
        let rows = vec![all.clone(), seed("g", &["a", "c"], Some("p0064")), all];
        let result = compare(&ex, &format!("{TAIL} LIMIT 2"), rows, true);
        assert!(matches!(result.rows[0]["pairs"], Value::Int(2)));
        assert!(matches!(result.rows[0]["graphs"], Value::Int(1)));
        assert_eq!(QueryResult::graph_ids(&result.rows[0]), names);
        assert_eq!(QueryResult::graph_ids(&result.rows[1]), ["p0064"]);
    }
}

#[test]
fn missing_provenance_is_supported_and_empty_or_noncanonical_provenance_is_preserved_by_fallback() {
    let ex = Executor::new(vec![], true);
    let query = format!("{TAIL} LIMIT 1");
    let rows = vec![
        seed("g1", &["a", "b"], None),
        seed("g2", &["a", "b"], Some("p")),
    ];
    let result = compare(&ex, &query, rows, true);
    assert_eq!(QueryResult::graph_ids(&result.rows[0]), ["p"]);
    let result = compare(&ex, &query, vec![seed("g", &["a", "b"], None)], true);
    assert!(!result.rows[0].contains_key(INTERNAL_PROVENANCE_KEY));
    for provenance in [
        Value::list(vec![]),
        Value::list(vec![Value::Int(1)]),
        Value::list(vec![Value::str("z"), Value::str("a")]),
        Value::list(vec![Value::str("a"), Value::str("a")]),
        Value::Null,
    ] {
        let mut row = seed("g", &["a", "b"], None);
        row.insert(INTERNAL_PROVENANCE_KEY.into(), provenance);
        compare(&ex, &query, vec![row], false);
    }
}

#[test]
fn exact_order_and_literal_bound_admission_preserve_the_streaming_parent() {
    let ex = Executor::new(vec![], true);
    for query in [
        format!("{TAIL} LIMIT 65537"),
        format!("{TAIL} SKIP 1 LIMIT 1"),
        format!("{} LIMIT 1", TAIL.replace("pairs DESC", "pairs ASC")),
        format!(
            "{} LIMIT 1",
            TAIL.replace("pairs DESC, l, r", "pairs DESC, r, l")
        ),
        format!("{} LIMIT 1", TAIL.replace("pairs DESC, l, r", "pairs DESC")),
        TAIL.to_string(),
    ] {
        compare(&ex, &query, vec![seed("g", &["c", "a", "b"], None)], false);
    }
    let query = format!("{TAIL} LIMIT $limit");
    let clauses = parse(&query).unwrap();
    let plan = PairPlan::build(&clauses).unwrap();
    assert!(capacity(&plan, &clauses).is_none());
}

#[test]
fn probe_keeps_one_extra_winner_and_reports_more_without_materializing_other_groups() {
    for dialect in [CypherDialect::Graphite, CypherDialect::OpenCypherM23] {
        let ex = Executor::new(vec![], true)
            .with_dialect(dialect)
            .with_probe();
        let query = format!(
            "WITH 'g' AS graph, 'c' AS caller, ['d','c','b','a'] AS targets {TAIL} LIMIT 2"
        );
        let before = MATERIALIZED.with(|count| count.get());
        let actual = ex.execute(&query, None).unwrap();
        assert_eq!(MATERIALIZED.with(|count| count.get()) - before, 3);
        assert_eq!(actual.rows.len(), 2);
        assert!(actual.more);
        let streamed = streaming(|| ex.execute(&query, None)).unwrap();
        let original = generic(|| ex.execute(&query, None)).unwrap();
        same(&actual, &streamed);
        same(&actual, &original);
    }
}

#[test]
fn bounded_parent_tokens_decline_before_compact_preparation() {
    let parent = CancelToken::child_with_limits(CancelToken::new(), 60_000, 1000);
    let child = CancelToken {
        parent: Some(parent),
        ..CancelToken::default()
    };
    let ex = Executor::new(vec![], true).with_cancel(Arc::new(child));
    let clauses = parse(&format!("{TAIL} LIMIT 1")).unwrap();
    let rows = vec![seed("g", &["b", "a"], None)];
    let before = HITS.with(|hits| hits.get());
    let streamed_before = super::super::HITS.with(|hits| hits.get());
    let result = ex.run_segment_seeded(&clauses, rows).unwrap();
    assert_eq!(result.rows.len(), 1);
    assert_eq!(HITS.with(|hits| hits.get()), before);
    assert_eq!(super::super::HITS.with(|hits| hits.get()), streamed_before);
}

#[test]
fn wide_dictionary_cancellation_and_parent_budget_keep_generic_behavior() {
    let clauses = parse(&format!("{TAIL} LIMIT 2")).unwrap();
    let rows: Vec<_> = (0..130)
        .map(|i| seed(&format!("g{i}"), &["a", "b"], Some(&format!("p{i}"))))
        .collect();
    for budget in [1, 100, 100_000] {
        let executor = || {
            let parent = CancelToken::child_with_limits(CancelToken::new(), 60_000, budget);
            Executor::new(vec![], true).with_cancel(Arc::new(CancelToken {
                parent: Some(parent),
                ..CancelToken::default()
            }))
        };
        let before = HITS.with(|hits| hits.get());
        let actual = executor().run_segment_seeded(&clauses, rows.clone());
        let expected = generic(|| executor().run_segment_seeded(&clauses, rows.clone()));
        match (actual, expected) {
            (Ok(a), Ok(b)) => same(&a, &b),
            (Err(a), Err(b)) => assert_eq!(format!("{a:?}"), format!("{b:?}")),
            other => panic!("different budget outcome: {other:?}"),
        }
        assert_eq!(HITS.with(|hits| hits.get()), before);
    }
    let token = CancelToken::new();
    token.cancel();
    let ex = Executor::new(vec![], true).with_cancel(token);
    let plan = PairPlan::build(&clauses).unwrap();
    assert!(matches!(
        try_execute(&ex, &plan, &clauses, &rows),
        Err(CypherError::Cancelled)
    ));
}

#[test]
fn losing_and_zero_limit_groups_still_finish_shared_overflow_state() {
    let ex = Executor::new(vec![], true);
    for limit in [0, 1] {
        let mut first = CompactGroup::default();
        first.count.add(5);
        let mut overflow = CompactGroup::default();
        overflow.count.add(i64::MAX);
        overflow.count.add(1);
        let groups = IndexMap::from([((0, 1), first), ((1, 2), overflow)]);
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            select(&ex, groups, &[0, 1, 2], limit)
        }));
        if cfg!(debug_assertions) {
            assert!(result.is_err(), "overflow was hidden by LIMIT {limit}");
        } else {
            let winners = result.unwrap().unwrap();
            assert_eq!(winners.len(), limit);
            if limit == 1 {
                assert_eq!(winners.peek().unwrap().0, Reverse(5));
            }
        }
    }
}
