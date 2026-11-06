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

#[test]
fn concurrent_maintenance_checks_accept_exactly_the_shared_work_limit() {
    let token = CancelToken::child_with_limits(CancelToken::new(), 60_000, 64);
    let accepted = std::sync::atomic::AtomicUsize::new(0);
    std::thread::scope(|scope| {
        for _ in 0..8 {
            let token = &token;
            let accepted = &accepted;
            scope.spawn(move || {
                for _ in 0..16 {
                    match token.check() {
                        Ok(()) => {
                            accepted.fetch_add(1, Ordering::Relaxed);
                        }
                        Err(error) => assert_eq!(error, CypherError::BudgetExceeded(64)),
                    }
                }
            });
        }
    });
    assert_eq!(accepted.load(Ordering::Relaxed), 64);
    assert_eq!(token.check(), Err(CypherError::BudgetExceeded(64)));
}

#[test]
fn maintenance_work_counter_never_wraps_and_zero_budget_accepts_no_work() {
    let zero = CancelToken::child_with_limits(CancelToken::new(), 60_000, 0);
    for _ in 0..2 {
        assert_eq!(zero.check(), Err(CypherError::BudgetExceeded(0)));
    }

    let last = CancelToken::child_with_limits(CancelToken::new(), 60_000, u64::MAX);
    last.work
        .as_ref()
        .unwrap()
        .consumed
        .store(u64::MAX - 1, Ordering::Relaxed);
    last.check().unwrap();
    for _ in 0..2 {
        assert_eq!(last.check(), Err(CypherError::BudgetExceeded(u64::MAX)));
    }
    assert_eq!(
        last.work.as_ref().unwrap().consumed.load(Ordering::Relaxed),
        u64::MAX
    );
}
