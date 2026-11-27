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
fn a_branch_whose_distinct_prefix_exceeds_the_row_cap_fails_instead_of_answering_short() {
    // 60,000 copies of one row: the branch is read to its end within the cap (the window
    // doubles to 65,536 rows), and the answer is complete.
    let read_through = "UNWIND range(1, 60000) AS i RETURN 1 AS v UNION RETURN 2 AS v";
    assert_eq!(
        values(&Executor::new(vec![], false).with_probe(), read_through, 2).unwrap(),
        [1, 2]
    );
    // 100,000 copies: at the cap the branch is still cut and yields one distinct row of the
    // two the window needs; whether a second follows is unknown, so the query fails.
    let flooded = "UNWIND range(1, 100000) AS i RETURN 1 AS v UNION RETURN 2 AS v";
    let err = values(&Executor::new(vec![], false).with_probe(), flooded, 2).unwrap_err();
    assert!(
        err.contains("A UNION branch repeats its rows too often to page"),
        "{err}"
    );
    assert!(
        err.contains("65536 of its rows hold only 1 new distinct rows"),
        "{err}"
    );
    // UNION ALL keeps every row, so a window never needs more than the window.
    let all = "UNWIND range(1, 60000) AS i RETURN 1 AS v UNION ALL RETURN 2 AS v";
    assert_eq!(
        values(&Executor::new(vec![], false).with_probe(), all, 2).unwrap(),
        [1, 1]
    );
}
