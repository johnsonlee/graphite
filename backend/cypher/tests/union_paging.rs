//! A deduplicating UNION under a row window (`execute(query, Some(window))`) answers the first
//! `window` rows of its complete distinct result, or fails; it never answers a prefix that lacks
//! rows. A branch cut at the window's row count may hold fewer distinct rows than the window
//! needs (it repeats rows), so the engine reads more of that branch, up to a row cap, and refuses
//! once the cap cannot establish them.
use graphite_cypher::engine::Executor;
use graphite_cypher::value::Value;

fn values(executor: &Executor, query: &str, window: usize) -> Result<Vec<i64>, String> {
    executor
        .execute(query, Some(window))
        .map(|result| {
            result
                .rows
                .iter()
                .map(|row| match row["v"] {
                    Value::Int(n) => n,
                    ref other => panic!("{other:?}"),
                })
                .collect()
        })
        .map_err(|error| error.to_string())
}

#[test]
fn a_repeating_branch_yields_the_complete_distinct_prefix_with_or_without_the_probe() {
    let repeated =
        "UNWIND [1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,2] AS x RETURN x AS v UNION RETURN 3 AS v";
    for executor in [
        Executor::new(vec![], false).with_probe(),
        Executor::new(vec![], false),
    ] {
        assert_eq!(values(&executor, repeated, 1).unwrap(), [1]);
        assert_eq!(values(&executor, repeated, 2).unwrap(), [1, 2]);
        assert_eq!(values(&executor, repeated, 3).unwrap(), [1, 2, 3]);
        assert_eq!(values(&executor, repeated, 50).unwrap(), [1, 2, 3]);
    }
    // The window cut the result only where a row past it exists.
    let probed = Executor::new(vec![], false).with_probe();
    values(&probed, repeated, 2).unwrap();
    assert!(probed.window_cut());
    let whole = Executor::new(vec![], false).with_probe();
    values(&whole, repeated, 3).unwrap();
    assert!(!whole.window_cut());
}

#[test]
fn a_duplicate_past_the_page_is_no_next_page() {
    // Review of #229: the branch's probe row was another `1`, so the page claimed a next page
    // and a lower bound of two rows where the whole result is one row.
    let tail = "UNWIND [1,1,1] AS x RETURN x AS v UNION RETURN 1 AS v";
    let probed = Executor::new(vec![], false).with_probe();
    let result = probed.execute(tail, Some(1)).unwrap();
    assert_eq!(result.rows.len(), 1);
    assert!(!result.more, "no row exists past the page");
    assert!(!probed.window_cut(), "so no next page");
    // A distinct row past the page, in either branch, is one.
    for query in [
        "UNWIND [1,1,1,2] AS x RETURN x AS v UNION RETURN 1 AS v",
        "UNWIND [1,1,1] AS x RETURN x AS v UNION RETURN 2 AS v",
    ] {
        let probed = Executor::new(vec![], false).with_probe();
        let result = probed.execute(query, Some(1)).unwrap();
        assert_eq!(result.rows.len(), 1, "{query}");
        assert!(result.more && probed.window_cut(), "{query}");
    }
    // A UNION ALL keeps its duplicates, so a duplicate past the page is a row of the next.
    let all = Executor::new(vec![], false).with_probe();
    let result = all
        .execute(
            "UNWIND [1,1,1] AS x RETURN x AS v UNION ALL RETURN 1 AS v",
            Some(1),
        )
        .unwrap();
    assert!(result.more && all.window_cut());
}

#[test]
fn a_plain_branch_pages_however_often_it_repeats_a_row() {
    // 100,000 copies of one row exhaust the bounded re-read (98,304 rows hold one distinct row
    // of the two the window needs). The union removes duplicates anyway, so a branch that ends
    // in a plain RETURN is then read as distinct rows, and the page is established. Before,
    // this query failed.
    let flooded = "UNWIND range(1, 100000) AS i RETURN 1 AS v UNION RETURN 2 AS v";
    let probed = Executor::new(vec![], false).with_probe();
    assert_eq!(values(&probed, flooded, 2).unwrap(), [1, 2]);
    assert!(!probed.window_cut(), "two rows are the whole result");
    assert_eq!(
        values(&Executor::new(vec![], false), flooded, 2).unwrap(),
        [1, 2]
    );
    // A window the first distinct row fills never needed the re-read.
    let first = Executor::new(vec![], false).with_probe();
    assert_eq!(values(&first, flooded, 1).unwrap(), [1]);
    assert!(first.window_cut());
    // The distinct read is the last resort, not the first, because DISTINCT evaluates every
    // row of a branch where a plain read over a MATCH stops at its window. The route test
    // `cypher_pages_concatenate_to_the_whole_result_and_say_where_the_next_starts` holds that:
    // a page established within the bounded re-read never evaluates a row past its lookahead.
}

#[test]
fn a_branch_with_its_own_limit_past_the_row_cap_fails_instead_of_answering_short() {
    // DISTINCT cannot go inside a branch with its own LIMIT (it would apply first and choose
    // other rows), so such a branch keeps the bounded re-read. 60,000 copies are read to the
    // branch's end within the cap (its window doubles from 3 to 98,304 rows).
    let read_through = "UNWIND range(1, 60000) AS i RETURN 1 AS v LIMIT 60000 UNION RETURN 2 AS v";
    assert_eq!(
        values(&Executor::new(vec![], false).with_probe(), read_through, 2).unwrap(),
        [1, 2]
    );
    // 100,000 copies: at the cap the branch is still cut and yields one distinct row of the
    // two the window needs; whether a second follows is unknown, so the query fails.
    let flooded = "UNWIND range(1, 100000) AS i RETURN 1 AS v LIMIT 100000 UNION RETURN 2 AS v";
    let err = values(&Executor::new(vec![], false).with_probe(), flooded, 2).unwrap_err();
    assert!(
        err.contains("A UNION branch repeats its rows too often to page"),
        "{err}"
    );
    assert!(
        err.contains("98304 of its rows hold only 1 new distinct rows"),
        "{err}"
    );
    // UNION ALL keeps every row, so a window never needs more than the window.
    let all = "UNWIND range(1, 60000) AS i RETURN 1 AS v UNION ALL RETURN 2 AS v";
    assert_eq!(
        values(&Executor::new(vec![], false).with_probe(), all, 2).unwrap(),
        [1, 1]
    );
}

#[test]
fn every_window_of_a_union_is_a_prefix_of_its_whole_result() {
    // The paging contract, checked exhaustively on small inputs: for every window size, the
    // rows are the first rows of the unwindowed result, and the window cut the result exactly
    // when rows remain. Branches repeat rows heavily and overlap each other; a deterministic
    // generator stands in for a list of hand-picked cases, which is how this code was reviewed
    // before and how each of its earlier defects was missed.
    let mut state = 0x2545_F491_4F6C_DD1Du64;
    let mut next = |bound: u64| {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        state % bound
    };
    let list = |length: u64, domain: u64, next: &mut dyn FnMut(u64) -> u64| {
        let items: Vec<String> = (0..length).map(|_| next(domain).to_string()).collect();
        format!("[{}]", items.join(","))
    };
    for case in 0..120 {
        let branches = 2 + next(2);
        let domain = 1 + next(6);
        let mut query = String::new();
        for branch in 0..branches {
            if branch > 0 {
                query.push_str(if case % 3 == 2 {
                    " UNION ALL "
                } else {
                    " UNION "
                });
            }
            let length = next(24);
            // Some branches carry their own LIMIT, which takes the bounded re-read path.
            let limit = if next(4) == 0 {
                format!(" LIMIT {}", 1 + next(8))
            } else {
                String::new()
            };
            query.push_str(&format!(
                "UNWIND {} AS x{branch} RETURN x{branch} AS v{limit}",
                list(length, domain, &mut next)
            ));
        }
        let whole = Executor::new(vec![], false)
            .execute(&query, None)
            .map(|result| {
                result
                    .rows
                    .iter()
                    .map(|row| match row["v"] {
                        Value::Int(n) => n,
                        ref other => panic!("{other:?}"),
                    })
                    .collect::<Vec<i64>>()
            })
            .unwrap();
        for window in 1..=whole.len() + 2 {
            for probed in [true, false] {
                let executor = if probed {
                    Executor::new(vec![], false).with_probe()
                } else {
                    Executor::new(vec![], false)
                };
                let page = values(&executor, &query, window).unwrap();
                let expected = &whole[..window.min(whole.len())];
                assert_eq!(page, expected, "window {window} of: {query}");
                if probed {
                    assert_eq!(
                        executor.window_cut(),
                        whole.len() > window,
                        "window {window} of {} rows: {query}",
                        whole.len()
                    );
                }
            }
        }
    }
}
