use super::*;
use crate::engine::{CancelToken, Source};
use crate::materialize::materialize;
use crate::parser::parse;
use crate::value::{EdgeRef, MethodRef, NodeRef, PathValue};
use std::sync::atomic::Ordering as AtomicOrdering;

fn item(name: &str) -> ReturnItem {
    ReturnItem {
        expr: Expr::Variable(name.into()),
        alias: None,
    }
}

// The pre-change non-aggregate projection, unconditionally rebuilding each row.
// It never calls project or the new eligibility helper.
fn generic_rows(ev: &Evaluator, rows: &[Row], items: &[ReturnItem]) -> Vec<Row> {
    let names = item_names(items);
    let mut columns = names.clone();
    rows.iter()
        .map(|row| {
            project_row(
                ev,
                row,
                Some(items),
                &names,
                None,
                false,
                false,
                &mut columns,
            )
            .unwrap()
        })
        .collect()
}

#[test]
fn identity_projection_transfers_owned_rows_and_all_typed_values_in_order() {
    let ex = Executor::new(vec![], false);
    let ev = Evaluator::new(&ex, &ex.params);
    let text: Arc<str> = Arc::from("retained string");
    let values = vec![
        Value::Null,
        Value::Bool(false),
        Value::Int(i64::MIN),
        Value::Float(-0.0),
        Value::Float32(1.25),
        Value::Str(text.clone()),
        Value::list(vec![Value::Int(7), Value::Null]),
        Value::map(IndexMap::from([("field".into(), Value::str("value"))])),
        Value::Node(NodeRef { source: 1, id: 42 }),
        Value::Method(MethodRef {
            source: 0,
            index: 3,
        }),
        Value::Path(Arc::new(PathValue {
            source: 1,
            nodes: vec![2, 4],
            edges: vec![],
        })),
        Value::Str(text.clone()),
        Value::Rel(EdgeRef {
            source: 1,
            edge: graphite_storage::Edge {
                from: 2,
                to: 4,
                label: 1,
                v2: true,
            },
        }),
    ];
    let rows: Vec<Row> = values
        .into_iter()
        .enumerate()
        .map(|(i, value)| {
            let mut row = Row::from([("c".into(), value)]);
            if i % 2 == 0 {
                add_provenance_id(&mut row, Arc::from("z"));
                add_provenance_id(&mut row, Arc::from("a"));
            }
            if i % 3 == 0 {
                row.insert(INTERNAL_WEIGHT_KEY.into(), Value::Int(7));
            }
            row
        })
        .collect();
    let items = [item("c")];
    let expected = generic_rows(&ev, &rows, &items);
    let vector = rows.as_ptr();
    let keys: Vec<_> = rows
        .iter()
        .map(|row| row.get_index(0).unwrap().0.as_ptr())
        .collect();
    let (columns, actual) = project(&ev, rows, Some(&items), false, None).unwrap();
    assert_eq!(columns, ["c"]);
    assert_eq!(format!("{actual:?}"), format!("{expected:?}"));
    assert_eq!(actual.as_ptr(), vector, "transfer the original row vector");
    assert_eq!(
        actual
            .iter()
            .map(|row| row.get_index(0).unwrap().0.as_ptr())
            .collect::<Vec<_>>(),
        keys
    );
    assert_eq!(QueryResult::graph_ids(&actual[0]), ["a", "z"]);
    assert!(matches!(actual[0][INTERNAL_WEIGHT_KEY], Value::Int(7)));
    assert!(matches!(actual[2]["c"], Value::Int(i64::MIN)));
    assert!(matches!(actual[3]["c"], Value::Float(v) if v.to_bits() == (-0.0f64).to_bits()));
    assert!(matches!(
        actual[8]["c"],
        Value::Node(NodeRef { source: 1, id: 42 })
    ));
    let Value::Str(owned) = &actual[5]["c"] else {
        panic!("string expected")
    };
    assert!(Arc::ptr_eq(owned, &text));
    drop(expected);
    drop(text);
    assert_eq!(actual[5]["c"].as_str(), Some("retained string"));
}

#[test]
fn identity_projection_checks_actual_columns_for_quoted_unicode_and_empty_rows() {
    let ex = Executor::new(vec![], false);
    let ev = Evaluator::new(&ex, &ex.params);
    for (spelling, name) in [
        ("c", "c"),
        ("`a b`", "a b"),
        ("`变量`", "变量"),
        ("`RETURN`", "RETURN"),
    ] {
        let clauses = parse(&format!("RETURN {spelling}")).unwrap();
        let [Clause::Return {
            distinct: false,
            items: Some(items),
        }] = clauses.as_slice()
        else {
            panic!("single explicit RETURN expected: {clauses:?}")
        };
        assert_eq!(items, &[item(name)]);
        assert_eq!(to_cypher_string(&items[0].expr), name);
        let rows = vec![Row::from([(name.into(), Value::Int(23))])];
        assert!(is_identity_projection(items, &[name.into()], &rows));
        assert!(!is_identity_projection(
            items,
            &["wrong rendered column".into()],
            &rows
        ));
        let (columns, actual) = project(&ev, rows, Some(items), false, None).unwrap();
        assert_eq!(columns, [name]);
        assert!(matches!(actual[0][name], Value::Int(23)));
        let aliased = [ReturnItem {
            expr: Expr::Variable(name.into()),
            alias: Some(name.into()),
        }];
        assert!(is_identity_projection(
            &aliased,
            &[name.into()],
            &[Row::from([(name.into(), Value::Null)])]
        ));
        let (columns, empty) = project(&ev, vec![], Some(items), false, None).unwrap();
        assert_eq!(columns, [name]);
        assert!(empty.is_empty());
    }
}

#[test]
fn identity_projection_falls_back_for_missing_extra_reordered_and_renamed_keys() {
    let ex = Executor::new(vec![], false);
    let ev = Evaluator::new(&ex, &ex.params);
    let items = [item("c")];
    let mut reversed = Row::new();
    add_provenance_id(&mut reversed, Arc::from("a"));
    reversed.insert("c".into(), Value::Int(3));
    for row in [
        Row::new(),
        Row::from([("c".into(), Value::Int(3)), ("extra".into(), Value::Int(9))]),
        Row::from([
            ("c".into(), Value::Int(3)),
            (format!("{ORDER_STASH_PREFIX}0"), Value::Int(1)),
        ]),
        reversed,
    ] {
        let rows = vec![row];
        assert!(!is_identity_projection(&items, &["c".into()], &rows));
        let expected = generic_rows(&ev, &rows, &items);
        let (_, actual) = project(&ev, rows, Some(&items), false, None).unwrap();
        assert_eq!(format!("{actual:?}"), format!("{expected:?}"));
        assert_eq!(actual[0].get_index(0).unwrap().0, "c");
    }
    // Admission must inspect the whole batch: a late missing binding still projects Null.
    let mixed = vec![Row::from([("c".into(), Value::Int(3))]), Row::new()];
    assert!(!is_identity_projection(&items, &["c".into()], &mixed));
    let expected = generic_rows(&ev, &mixed, &items);
    let (_, actual) = project(&ev, mixed, Some(&items), false, None).unwrap();
    assert_eq!(format!("{actual:?}"), format!("{expected:?}"));
    assert!(matches!(actual[1]["c"], Value::Null));
    let renamed = [ReturnItem {
        expr: Expr::Variable("c".into()),
        alias: Some("renamed".into()),
    }];
    let rows = vec![Row::from([("c".into(), Value::Int(9))])];
    assert!(!is_identity_projection(
        &renamed,
        &["renamed".into()],
        &rows
    ));
    let (columns, actual) = project(&ev, rows, Some(&renamed), false, None).unwrap();
    assert_eq!(columns, ["renamed"]);
    assert!(matches!(actual[0]["renamed"], Value::Int(9)));
    assert!(!actual[0].contains_key("c"));
    let mut unknown = Row::from([("c".into(), Value::Int(9))]);
    unknown.insert("\0unknown".into(), Value::Int(5));
    let (_, star) = project(&ev, vec![unknown], None, false, None).unwrap();
    assert_eq!(
        star[0].keys().map(String::as_str).collect::<Vec<_>>(),
        ["c"]
    );
}

#[test]
fn identity_projection_keeps_distinct_order_stashes_and_expression_errors() {
    let ex = Executor::new(vec![], true);
    let ev = Evaluator::new(&ex, &ex.params);
    let items = [item("c")];
    let rows: Vec<Row> = ["z", "a"]
        .into_iter()
        .map(|id| {
            let mut row = Row::from([("c".into(), Value::Int(7))]);
            add_provenance_id(&mut row, Arc::from(id));
            row.insert(INTERNAL_WEIGHT_KEY.into(), Value::Int(9));
            row
        })
        .collect();
    let (_, dedup) = project(&ev, rows.clone(), Some(&items), true, None).unwrap();
    assert_eq!(dedup.len(), 1);
    assert!(matches!(dedup[0]["c"], Value::Int(7)));
    assert_eq!(QueryResult::graph_ids(&dedup[0]), ["a", "z"]);
    assert!(!dedup[0].contains_key(INTERNAL_WEIGHT_KEY));
    let order = [OrderItem {
        expr: Expr::Literal(Literal::Int(7)),
        descending: true,
    }];
    let (_, ordered) = project(&ev, rows, Some(&items), false, Some(&order)).unwrap();
    assert!(matches!(
        ordered[0][&format!("{ORDER_STASH_PREFIX}0")],
        Value::Int(7)
    ));
    let clauses = parse("RETURN c, missing_function(c) AS failure").unwrap();
    let [Clause::Return {
        items: Some(items), ..
    }] = clauses.as_slice()
    else {
        panic!("RETURN expected")
    };
    let err = project(
        &ev,
        vec![Row::from([("c".into(), Value::Int(7))])],
        Some(items),
        false,
        None,
    )
    .unwrap_err();
    assert!(err.to_string().contains("missing_function"));
}

fn real_sources() -> Option<Vec<Source>> {
    let Some(dir) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
        eprintln!("GRAPHITE_INDEX_FIXTURE unset; skipping real identity projection tests");
        return None;
    };
    let graph = Arc::new(graphite_storage::Graph::load(std::path::Path::new(&dir)).unwrap());
    Some(
        ["a", "z"]
            .into_iter()
            .map(|id| Source {
                id: Arc::from(id),
                graph: graph.clone(),
            })
            .collect(),
    )
}

// CASE true is semantically the same scalar but cannot take the identity path.
// Only the final RETURN changes: the entire MATCH/WITH/filter/LIMIT stays identical.
fn generic_clauses(query: &str) -> (Vec<Clause>, Vec<Clause>) {
    let actual = parse(query).unwrap();
    let mut generic = actual.clone();
    let at = actual
        .iter()
        .rposition(|c| matches!(c, Clause::Return { .. }))
        .unwrap();
    let Clause::Return {
        distinct: false,
        items: Some(items),
    } = &mut generic[at]
    else {
        panic!("single non-distinct RETURN expected")
    };
    let [item] = items.as_mut_slice() else {
        panic!("one item expected")
    };
    let Expr::Variable(name) = &item.expr else {
        panic!("variable expected")
    };
    assert_eq!(name, "c");
    item.alias = Some(name.clone());
    item.expr = Expr::Case {
        test: None,
        whens: vec![(Expr::Literal(Literal::Bool(true)), item.expr.clone())],
        else_expr: Some(Box::new(Expr::Literal(Literal::Null))),
    };
    assert!(!is_identity_projection(items, &["c".into()], &[]));
    assert_eq!(&generic[..at], &actual[..at]);
    assert_eq!(&generic[at + 1..], &actual[at + 1..]);
    (actual, generic)
}

#[test]
fn real_identity_projection_matches_generic_rows_provenance_probe_and_scan_work() {
    let Some(sources) = real_sources() else {
        return;
    };
    let prefix = "MATCH (n:CallSite) WITH n.callee_class AS c WHERE c IS NOT NULL RETURN c";
    let parsed = parse(prefix).unwrap();
    let [Clause::Match {
        optional: false,
        where_clause: None,
        patterns,
    }, Clause::With {
        distinct: false,
        items: Some(items),
        where_clause: Some(predicate),
    }, Clause::Return {
        distinct: false,
        items: Some(return_items),
    }] = parsed.as_slice()
    else {
        panic!("MATCH, WITH carrying its WHERE, RETURN expected: {parsed:?}")
    };
    assert_eq!(patterns.len(), 1);
    assert!(patterns[0].path_variable.is_none() && patterns[0].rels.is_empty());
    assert_eq!(patterns[0].nodes.len(), 1);
    assert_eq!(patterns[0].nodes[0].variable.as_deref(), Some("n"));
    assert_eq!(patterns[0].nodes[0].labels, ["CallSite"]);
    assert!(patterns[0].nodes[0].properties.is_empty());
    assert_eq!(
        items,
        &[ReturnItem {
            expr: Expr::Property {
                expr: Box::new(Expr::Variable("n".into())),
                key: "callee_class".into()
            },
            alias: Some("c".into()),
        }]
    );
    assert_eq!(
        predicate,
        &Expr::IsNotNull(Box::new(Expr::Variable("c".into())))
    );
    assert_eq!(return_items, &[item("c")]);
    let all = Executor::new(sources.clone(), true)
        .execute(prefix, None)
        .unwrap();
    assert!(
        all.rows.len() > 2,
        "real core fixture must contain calls in both sources"
    );
    assert!(all
        .rows
        .iter()
        .all(|r| matches!(&r["c"], Value::Str(s) if !s.is_empty())));
    assert_eq!(QueryResult::graph_ids(&all.rows[0]), ["a"]);
    assert_eq!(QueryResult::graph_ids(all.rows.last().unwrap()), ["z"]);
    for limit in [0, 1, 200, all.rows.len(), all.rows.len() + 1] {
        let (actual_clauses, reference_clauses) =
            generic_clauses(&format!("{prefix} LIMIT {limit}"));
        for probe in [false, true] {
            let mut actual_ex = Executor::new(sources.clone(), true);
            let mut reference_ex = Executor::new(sources.clone(), true);
            if probe {
                actual_ex = actual_ex.with_compact().with_probe();
                reference_ex = reference_ex.with_compact().with_probe();
            }
            let actual = actual_ex.execute_clauses(&actual_clauses, None).unwrap();
            let reference = reference_ex
                .execute_clauses(&reference_clauses, None)
                .unwrap();
            assert_eq!(format!("{actual:?}"), format!("{reference:?}"));
            assert_eq!(actual.columns, ["c"]);
            assert_eq!(actual.rows.len(), limit.min(all.rows.len()));
            assert_eq!(actual.more, probe && all.rows.len() > limit);
            assert_eq!(
                format!("{:?}", actual.rows),
                format!("{:?}", &all.rows[..actual.rows.len()])
            );
            assert_eq!(
                actual_ex.poll.load(AtomicOrdering::Relaxed),
                reference_ex.poll.load(AtomicOrdering::Relaxed)
            );
            assert!(actual_ex.poll.load(AtomicOrdering::Relaxed) >= all.rows.len() as u64);
            let body = |result: &QueryResult, ex: &Executor| {
                result
                    .rows
                    .iter()
                    .map(|row| {
                        row.iter()
                            .map(|(k, v)| (k.clone(), materialize(v, ex)))
                            .collect::<Vec<_>>()
                    })
                    .collect::<Vec<_>>()
            };
            assert_eq!(body(&actual, &actual_ex), body(&reference, &reference_ex));
        }
    }
}

#[test]
fn real_identity_projection_preserves_late_with_errors_before_small_limits() {
    let Some(sources) = real_sources() else {
        return;
    };
    let ex = Executor::new(sources.clone(), true);
    let ids = ex
        .execute("MATCH (n:CallSite) RETURN n.id AS id", None)
        .unwrap();
    let Value::Int(last) = ids.rows.last().unwrap()["id"] else {
        panic!("integer id expected")
    };
    assert!(ids
        .rows
        .iter()
        .any(|row| matches!(row["id"], Value::Int(id) if id != last)));
    for limit in [0, 1] {
        let query = format!("MATCH (n:CallSite) WITH CASE WHEN n.graphId = 'z' AND n.id = {last} THEN missing_function(n.id) ELSE n.callee_class END AS c WHERE c IS NOT NULL RETURN c LIMIT {limit}");
        let (actual, reference) = generic_clauses(&query);
        let run = |clauses: &[Clause]| {
            Executor::new(sources.clone(), true)
                .execute_clauses(clauses, None)
                .unwrap_err()
        };
        let actual_error = run(&actual);
        assert!(actual_error.to_string().contains("missing_function"));
        assert_eq!(
            format!("{actual_error:?}"),
            format!("{:?}", run(&reference))
        );
    }
}

#[test]
fn real_identity_projection_preserves_cancellation_and_timeout_errors() {
    let Some(sources) = real_sources() else {
        return;
    };
    for limit in [0, 1, 200] {
        let (actual, reference) = generic_clauses(&format!("MATCH (n:CallSite) WITH n.callee_class AS c WHERE c IS NOT NULL RETURN c LIMIT {limit}"));
        for timeout in [false, true] {
            let token = CancelToken::new();
            if timeout {
                token.timeout(60_000);
            } else {
                token.cancel();
            }
            let ex = Executor::new(sources.clone(), true)
                .with_cancel(token)
                .with_probe();
            let actual_error = ex.execute_clauses(&actual, None).unwrap_err();
            let expected_error = ex.execute_clauses(&reference, None).unwrap_err();
            assert_eq!(format!("{actual_error:?}"), format!("{expected_error:?}"));
            if timeout {
                assert!(matches!(actual_error, CypherError::Timeout(60_000)));
            } else {
                assert!(matches!(actual_error, CypherError::Cancelled));
            }
            assert_eq!(ex.poll.load(AtomicOrdering::Relaxed), 0);
        }
    }
}
