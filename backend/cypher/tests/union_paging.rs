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
    // The union keeps the first occurrence of each row, so a branch that ends in a plain
    // RETURN is deduplicated as it is read: a window of n rows is n distinct rows, however
    // often the branch repeats one. Read by row count, 100,000 copies of one row could not
    // be paged at all: the query failed.
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
    // The read stops at the row that completes the window. Over a MATCH that is a row the
    // page needs and none after it, which the route test
    // `cypher_pages_concatenate_to_the_whole_result_and_say_where_the_next_starts` holds.
}

#[test]
fn a_union_all_branch_after_a_union_keeps_every_row() {
    // Review of #229: the union deduplicates the branches up to its last UNION, and a UNION ALL
    // after it appends its branch as it is. Treating the whole query as deduplicating read the
    // last branch as distinct rows: five rows were three, and a page of three claimed no next.
    let mixed =
        "RETURN 0 AS v UNION RETURN 1 AS v UNION ALL UNWIND range(1, 100000) AS i RETURN 2 AS v";
    let whole = Executor::new(vec![], false).execute(mixed, None).unwrap();
    assert_eq!(whole.rows.len(), 100_002);
    for (window, expected) in [
        (1, vec![0]),
        (2, vec![0, 1]),
        (3, vec![0, 1, 2]),
        (5, vec![0, 1, 2, 2, 2]),
    ] {
        let probed = Executor::new(vec![], false).with_probe();
        assert_eq!(values(&probed, mixed, window).unwrap(), expected);
        assert!(probed.window_cut(), "rows remain past a window of {window}");
        assert_eq!(
            values(&Executor::new(vec![], false), mixed, window).unwrap(),
            expected
        );
    }
    // A UNION after a UNION ALL deduplicates everything before it, the rows an earlier UNION
    // kept included: the merge used to seed its keys on top of that UNION's and drop them all.
    let again = "RETURN 1 AS v UNION RETURN 2 AS v UNION ALL RETURN 1 AS v UNION RETURN 3 AS v";
    let whole = Executor::new(vec![], false).execute(again, None).unwrap();
    assert_eq!(whole.rows.len(), 3);
    assert_eq!(
        values(&Executor::new(vec![], false), again, 5).unwrap(),
        [1, 2, 3]
    );
    // The other way round, the later UNION removes the duplicates of everything before it.
    let later = "UNWIND [2, 2, 2] AS x RETURN x AS v UNION ALL RETURN 2 AS v UNION RETURN 3 AS v";
    let probed = Executor::new(vec![], false).with_probe();
    assert_eq!(values(&probed, later, 5).unwrap(), [2, 3]);
    assert!(!probed.window_cut());
}

#[test]
fn a_branch_with_its_own_limit_past_the_row_cap_fails_instead_of_answering_short() {
    // A branch with its own LIMIT cannot be deduplicated as it is read (that would apply
    // before the LIMIT and choose other rows), so it is read by row count and re-read with a
    // larger window when its rows repeat, up to a cap. 60,000 copies are read to the
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
    for _ in 0..200 {
        let branches = 2 + next(3);
        let domain = 1 + next(6);
        let mut query = String::new();
        for branch in 0..branches {
            if branch > 0 {
                // Each operator is chosen on its own, so UNION and UNION ALL mix in one
                // query: a UNION ALL after a UNION keeps its branch's duplicates, and a UNION
                // after a UNION ALL removes the ones before it.
                query.push_str(if next(3) == 0 {
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

/// Two graphs whose string constants repeat within a graph and across both.
fn overlapping_graphs() -> (Vec<graphite_cypher::engine::Source>, std::path::PathBuf) {
    let root = std::env::temp_dir().join(format!("graphite-union-paging-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    let sources = [
        ("first", ["b", "a", "b", "c", "a", "b", "d", "c", "b", "e"]),
        ("second", ["c", "c", "a", "f", "b", "f", "g", "a", "c", "h"]),
    ]
    .into_iter()
    .map(|(id, values)| {
        let nodes: Vec<serde_json::Value> = values
            .iter()
            .enumerate()
            .map(|(i, v)| serde_json::json!({"id": i, "kind": "StringConstant", "value": v}))
            .collect();
        let graph = serde_json::json!({"version": 1, "methods": [], "edges": [], "nodes": nodes});
        let dir = root.join(id);
        std::fs::create_dir_all(&root).unwrap();
        graphite_storage::interchange::import_json(graph.to_string().as_bytes(), &dir).unwrap();
        graphite_cypher::engine::Source {
            id: id.into(),
            graph: std::sync::Arc::new(graphite_storage::Graph::load(&dir).unwrap()),
        }
    })
    .collect();
    (sources, root)
}

#[test]
fn across_graphs_every_window_is_a_prefix_of_the_whole_result_rows_and_provenance() {
    // The service runs every query over all loaded graphs, and each row says which graphs it
    // came from. A window must answer the same rows as the unwindowed query, with the same
    // provenance: a branch the union deduplicates as it reads keeps the first occurrence of
    // each row as it is, exactly as the union does, and does not go on to collect the graphs
    // of later duplicates the way a DISTINCT the query wrote does.
    let (sources, root) = overlapping_graphs();
    let executor = |probe: bool| {
        let executor = Executor::new(sources.clone(), true);
        if probe {
            executor.with_probe()
        } else {
            executor
        }
    };
    let strings = "MATCH (n:StringConstant) RETURN n.value AS v";
    let queries = [
        format!("{strings} UNION {strings}"),
        format!("{strings} UNION RETURN 'zzz' AS v"),
        format!("RETURN 'c' AS v UNION {strings} UNION RETURN 'a' AS v"),
        format!("{strings} UNION ALL {strings}"),
        // A DISTINCT the query wrote keeps its own reading: each row carries every graph.
        format!("MATCH (n:StringConstant) RETURN DISTINCT n.value AS v UNION {strings}"),
        format!("{strings} UNION MATCH (m:StringConstant) WITH DISTINCT m.value AS v RETURN v"),
        // A branch with its own LIMIT is read by row count.
        format!("{strings} LIMIT 7 UNION {strings}"),
        // Mixed operators: the branch after the last UNION keeps its duplicates.
        format!("RETURN 'c' AS v UNION {strings} UNION ALL {strings}"),
        format!("{strings} UNION ALL {strings} UNION RETURN 'zzz' AS v"),
    ];
    for query in &queries {
        let whole = executor(false).execute(query, None).unwrap();
        let rows: Vec<String> = whole.rows.iter().map(|row| format!("{row:?}")).collect();
        assert!(rows.len() >= 3, "{query}");
        for window in 1..=rows.len() + 1 {
            for probe in [true, false] {
                let executor = executor(probe);
                let page = executor.execute(query, Some(window)).unwrap();
                let page: Vec<String> = page.rows.iter().map(|row| format!("{row:?}")).collect();
                assert_eq!(
                    page,
                    rows[..window.min(rows.len())],
                    "window {window}, probe {probe}: {query}"
                );
                if probe {
                    assert_eq!(
                        executor.window_cut(),
                        rows.len() > window,
                        "{window}: {query}"
                    );
                }
            }
        }
    }
    // The provenance is part of what was compared: the first query's rows name one graph each.
    let first = executor(false).execute(&queries[0], None).unwrap();
    let rendered = format!("{:?}", first.rows[0]);
    assert!(
        rendered.contains("first") && !rendered.contains("second"),
        "{rendered}"
    );
    drop(sources);
    std::fs::remove_dir_all(&root).unwrap();
}
