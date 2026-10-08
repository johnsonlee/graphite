use super::*;

fn property(key: &str) -> Expr {
    Expr::Property {
        expr: Box::new(Expr::Variable("c".into())),
        key: key.into(),
    }
}

#[test]
fn owned_projection_preserves_preprojection_order_keys_and_distinct_metadata() {
    let ex = Executor::new(vec![], true);
    let ev = Evaluator::new(&ex, &ex.params);
    let rows: Vec<Row> = [("z", "same", 2), ("a", "same", 1), ("b", "other", 3)]
        .into_iter()
        .map(|(graph, text, rank)| {
            let mut row = Row::from([(
                "c".into(),
                Value::map(IndexMap::from([
                    ("text".into(), Value::str(text)),
                    ("rank".into(), Value::Int(rank)),
                ])),
            )]);
            add_provenance_id(&mut row, Arc::from(graph));
            row.insert(INTERNAL_WEIGHT_KEY.into(), Value::Int(7));
            row
        })
        .collect();
    let items = [ReturnItem {
        expr: property("text"),
        // Deliberately shadow the pre-projection map used by ORDER BY.
        alias: Some("c".into()),
    }];
    let order = [OrderItem {
        expr: property("rank"),
        descending: false,
    }];
    let (columns, projected) =
        project(&ev, rows.clone(), Some(&items), false, Some(&order)).unwrap();
    assert_eq!(columns, ["c"]);
    assert_eq!(
        projected[0].keys().map(String::as_str).collect::<Vec<_>>(),
        [
            "c",
            INTERNAL_PROVENANCE_KEY,
            INTERNAL_WEIGHT_KEY,
            "\0order:0"
        ]
    );
    for (row, rank) in projected.iter().zip([2, 1, 3]) {
        assert!(matches!(row[&format!("{ORDER_STASH_PREFIX}0")], Value::Int(n) if n == rank));
        assert!(matches!(row[INTERNAL_WEIGHT_KEY], Value::Int(7)));
    }
    let ordered = order_rows(&ev, projected, &order).unwrap();
    assert_eq!(
        ordered
            .iter()
            .map(QueryResult::graph_ids)
            .collect::<Vec<_>>(),
        [vec!["a"], vec!["z"], vec!["b"]]
    );
    assert_eq!(
        ordered
            .iter()
            .map(|row| row["c"].as_str())
            .collect::<Vec<_>>(),
        [Some("same"), Some("same"), Some("other")]
    );
    let (_, distinct) = project(&ev, rows, Some(&items), true, None).unwrap();
    assert_eq!(distinct.len(), 2);
    assert_eq!(distinct[0]["c"].as_str(), Some("same"));
    assert_eq!(distinct[1]["c"].as_str(), Some("other"));
    assert_eq!(QueryResult::graph_ids(&distinct[0]), ["a", "z"]);
    assert_eq!(QueryResult::graph_ids(&distinct[1]), ["b"]);
    assert!(distinct
        .iter()
        .all(|row| !row.contains_key(INTERNAL_WEIGHT_KEY)));
}

#[test]
fn owned_projection_keeps_late_expression_and_order_errors_and_drops_all_values() {
    let ex = Executor::new(vec![], false);
    let ev = Evaluator::new(&ex, &ex.params);
    let late_error = Expr::Case {
        test: None,
        whens: vec![(
            Expr::Variable("fail".into()),
            Expr::FunctionCall {
                name: "missing_function".into(),
                args: vec![],
                distinct: false,
            },
        )],
        else_expr: Some(Box::new(Expr::Literal(Literal::Int(1)))),
    };
    for error_in_order in [false, true] {
        let payload: Arc<str> = Arc::from("retained output payload");
        let rows = [false, true, false]
            .into_iter()
            .map(|fail| {
                Row::from([
                    ("payload".into(), Value::Str(payload.clone())),
                    ("fail".into(), Value::Bool(fail)),
                ])
            })
            .collect();
        let mut items = vec![ReturnItem {
            expr: Expr::Variable("payload".into()),
            alias: Some("retained".into()),
        }];
        let order = [OrderItem {
            expr: late_error.clone(),
            descending: false,
        }];
        if !error_in_order {
            items.push(ReturnItem {
                expr: late_error.clone(),
                alias: Some("checked".into()),
            });
        }
        let error = project(
            &ev,
            rows,
            Some(&items),
            false,
            error_in_order.then_some(order.as_slice()),
        )
        .unwrap_err();
        assert!(error.to_string().contains("missing_function"));
        assert_eq!(
            Arc::strong_count(&payload),
            1,
            "drop earlier outputs, current row and remaining inputs on error"
        );
    }
}
