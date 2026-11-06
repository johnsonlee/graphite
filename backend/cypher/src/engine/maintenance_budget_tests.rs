use super::*;

#[test]
fn ordinary_queries_have_no_new_default_work_limit() {
    let executor = Executor::new(Vec::new(), false);
    for _ in 0..100_000 {
        executor.tick().unwrap();
    }
    let result = executor.execute("RETURN 42 AS answer", Some(1)).unwrap();
    assert_eq!(result.columns, vec!["answer"]);
    assert_eq!(result.rows[0]["answer"].as_f64(), Some(42.0));
}

#[test]
fn child_maintenance_checks_share_work_and_preserve_parent_error_precedence() {
    let parent = CancelToken::new();
    let child = CancelToken::child_with_limits(parent.clone(), 60_000, 2);
    child.check().unwrap();
    child.check().unwrap();
    assert_eq!(child.check(), Err(CypherError::BudgetExceeded(2)));
    parent.cancel();
    assert_eq!(child.check(), Err(CypherError::Cancelled));

    let expired = CancelToken::child_with_limits(CancelToken::with_timeout(0), 60_000, 100);
    assert_eq!(expired.check(), Err(CypherError::Timeout(0)));
    let deadline = CancelToken::child_with_limits(CancelToken::new(), 0, 100);
    assert_eq!(deadline.check(), Err(CypherError::Timeout(0)));
}
