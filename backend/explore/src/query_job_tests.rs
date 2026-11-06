//! Lifecycle and availability checks; channels define order, never query-speed assumptions.
use crate::guard::{CypherGuard, Outcome, QueryPermit};
use crate::query_job::{self, QueryJobError};
use crate::registry::{GraphRegistry, LoadMode};
use crate::routes::{router, AppState};
use axum::body::{to_bytes, Body};
use axum::http::{header, Request, StatusCode};
use axum::response::Response;
use axum::Router;
use serde_json::{json, Value};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc};
use std::time::Duration;
use tokio::sync::oneshot;
use tower::ServiceExt;

// These bounds only stop a broken handshake from hanging the test process. No latency
// or throughput observation is collected or used as an acceptance criterion.
const DEADLOCK_BOUND: Duration = Duration::from_secs(10);

fn acquire(guard: &CypherGuard) -> QueryPermit {
    guard
        .try_acquire(None)
        .ok()
        .expect("available query permit")
}

fn active(guard: &CypherGuard) -> u64 {
    guard.metrics.active.load(Ordering::SeqCst)
}

fn completed(guard: &CypherGuard, outcome: Outcome) -> u64 {
    guard
        .metrics
        .snapshot()
        .into_iter()
        .find(|(found, _, _)| *found == outcome)
        .unwrap()
        .1
}

async fn reacquire(guard: &CypherGuard) -> QueryPermit {
    tokio::time::timeout(DEADLOCK_BOUND, async {
        loop {
            if let Ok(permit) = guard.try_acquire(None) {
                return permit;
            }
            tokio::task::yield_now().await;
        }
    })
    .await
    .expect("the exited worker must return its permit")
}

#[test]
fn permit_unwinding_records_failure_and_releases_capacity_once() {
    let guard = CypherGuard::new(1, 60_000);
    let mut permit = acquire(&guard);
    assert_eq!(active(&guard), 1);
    let failure = catch_unwind(AssertUnwindSafe(move || {
        permit.complete(Outcome::Success);
        panic!("failure after selecting a successful outcome");
    }));
    assert!(failure.is_err());
    assert_eq!(active(&guard), 0);
    assert_eq!(completed(&guard, Outcome::Failed), 1);
    assert_eq!(completed(&guard, Outcome::Success), 0);
    drop(acquire(&guard));
    assert_eq!(active(&guard), 0);
    assert_eq!(completed(&guard, Outcome::Failed), 1);
    assert_eq!(completed(&guard, Outcome::Cancelled), 1);
}

#[tokio::test(flavor = "current_thread")]
async fn a_panicking_job_returns_failure_and_the_next_job_can_complete() {
    let guard = CypherGuard::new(1, 60_000);
    let result = query_job::run::<()>(acquire(&guard), |_| panic!("worker failed")).await;
    assert!(matches!(result, Err(QueryJobError::Failed)));
    assert_eq!(active(&guard), 0);
    assert_eq!(completed(&guard, Outcome::Failed), 1);
    let next = query_job::run(acquire(&guard), |_| Ok(42)).await;
    assert_eq!(next.ok(), Some(42));
    assert_eq!(active(&guard), 0);
    assert_eq!(completed(&guard, Outcome::Success), 1);
}

#[tokio::test(flavor = "current_thread")]
async fn dropping_the_waiter_cancels_work_but_retains_capacity_until_the_worker_exits() {
    let guard = Arc::new(CypherGuard::new(1, 60_000));
    let permit = acquire(&guard);
    let cancel = permit.cancel.clone();
    let (entered, entry) = oneshot::channel();
    let (release, released) = mpsc::channel();
    let waiter = tokio::spawn(query_job::run(permit, move |permit| {
        entered.send(()).unwrap();
        released.recv_timeout(DEADLOCK_BOUND).unwrap();
        permit.cancel.check()?;
        Ok(())
    }));
    tokio::time::timeout(DEADLOCK_BOUND, entry)
        .await
        .expect("worker must enter")
        .unwrap();
    waiter.abort();
    assert!(matches!(waiter.await, Err(error) if error.is_cancelled()));
    assert!(
        cancel.is_cancelled(),
        "dropping the waiter cancels its token"
    );
    assert_eq!(active(&guard), 1, "the worker still owns the permit");
    assert!(guard.try_acquire(None).is_err());
    assert_eq!(completed(&guard, Outcome::Cancelled), 0);
    release.send(()).unwrap();
    let mut recovered = reacquire(&guard).await;
    assert_eq!(completed(&guard, Outcome::Cancelled), 1);
    assert_eq!(active(&guard), 1, "only the replacement request is active");
    recovered.complete(Outcome::Success);
    drop(recovered);
    assert_eq!(active(&guard), 0);
}

#[tokio::test(flavor = "current_thread")]
async fn a_parked_blocking_query_allows_a_single_runtime_worker_to_serve_a_heartbeat() {
    let guard = Arc::new(CypherGuard::new(1, 60_000));
    let (entered, entry) = oneshot::channel();
    let (release, released) = mpsc::channel();
    let job = tokio::spawn(query_job::run(acquire(&guard), move |_| {
        entered.send(()).unwrap();
        released.recv_timeout(DEADLOCK_BOUND).unwrap();
        Ok("fully constructed response")
    }));
    tokio::time::timeout(DEADLOCK_BOUND, entry)
        .await
        .expect("query must enter its blocking worker")
        .unwrap();
    let observed = guard.clone();
    let heartbeat = tokio::spawn(async move {
        assert_eq!(active(&observed), 1);
        "heartbeat acknowledged"
    });
    assert_eq!(
        tokio::time::timeout(DEADLOCK_BOUND, heartbeat)
            .await
            .expect("the runtime must be available while query execution is parked")
            .unwrap(),
        "heartbeat acknowledged"
    );
    assert!(
        !job.is_finished(),
        "heartbeat runs before the blocked query finishes"
    );
    assert!(
        guard.try_acquire(None).is_err(),
        "response construction still owns capacity"
    );
    release.send(()).unwrap();
    let response = job
        .await
        .unwrap()
        .ok()
        .expect("query completes after release");
    assert_eq!(response, "fully constructed response");
    assert_eq!(active(&guard), 0);
    assert_eq!(completed(&guard, Outcome::Success), 1);
}

struct RegistryDirectory(PathBuf);
impl RegistryDirectory {
    fn new() -> Self {
        let path =
            std::env::temp_dir().join(format!("graphite-query-panic-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&path).unwrap();
        Self(path)
    }
}
impl Drop for RegistryDirectory {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

async fn post(app: Router) -> Response {
    app.oneshot(
        Request::builder()
            .method("POST")
            .uri("/api/cypher")
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(json!({"query":"RETURN 7 AS value"}).to_string()))
            .unwrap(),
    )
    .await
    .expect("Router must return a response after a worker panic")
}

async fn body(response: Response) -> Value {
    serde_json::from_slice(&to_bytes(response.into_body(), usize::MAX).await.unwrap()).unwrap()
}

#[tokio::test(flavor = "current_thread")]
async fn http_query_panic_returns_500_and_following_query_still_serves_real_results() {
    let directory = RegistryDirectory::new();
    let registry = Arc::new(GraphRegistry::new(directory.0.clone(), LoadMode::Mapped));
    let guard = Arc::new(CypherGuard::new(1, 60_000));
    let mut state = AppState::new(registry, guard.clone(), "test".into(), true);
    let panic_once = AtomicBool::new(true);
    // Inject a controlled panic at the execution boundary while keeping permit ownership,
    // Router error mapping and recovery on their production paths.
    state.cypher_before_execute = Some(Arc::new(move |_, _| {
        if panic_once.swap(false, Ordering::SeqCst) {
            panic!("private query failure detail");
        }
    }));
    let app = router(Arc::new(state));
    let failed_request = tokio::spawn(post(app.clone()));
    let failed = failed_request
        .await
        .expect("a query panic must not unwind the Router task");
    assert_eq!(failed.status(), StatusCode::INTERNAL_SERVER_ERROR);
    assert_eq!(
        body(failed).await,
        json!({"error":"Query execution failed"})
    );
    assert_eq!(active(&guard), 0);
    assert_eq!(completed(&guard, Outcome::Failed), 1);
    let recovered = post(app).await;
    assert_eq!(recovered.status(), StatusCode::OK);
    let recovered = body(recovered).await;
    assert_eq!(recovered["columns"], json!(["value"]));
    assert_eq!(
        recovered["rows"],
        json!([{"value":7,"$metadata":{"graphIds":[]}}])
    );
    assert_eq!(recovered["rowCount"], 1);
    assert_eq!(active(&guard), 0);
    assert_eq!(completed(&guard, Outcome::Success), 1);
}
