use super::*;
use crate::guard::CypherGuard;
use axum::body::Body;
use axum::http::{Request, StatusCode};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Condvar, Mutex};
use std::time::Duration;
use tower::ServiceExt;

struct Fixture(PathBuf);
impl Fixture {
    fn new() -> Self {
        let path =
            std::env::temp_dir().join(format!("graphite-update-test-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&path).unwrap();
        Self(path)
    }
    fn graph(&self, name: &str, value: &str) -> PathBuf {
        let path = self.0.join(name);
        let json = serde_json::json!({"version":1,"methods":[],"nodes":[{"id":0,"kind":"StringConstant","value":value}],"edges":[]});
        graphite_storage::interchange::import_json(json.to_string().as_bytes(), &path).unwrap();
        path
    }
    fn state(&self) -> AppState {
        AppState::new(
            Arc::new(crate::registry::GraphRegistry::new(
                self.0.clone(),
                LoadMode::Mapped,
            )),
            Arc::new(CypherGuard::new(1, 1000)),
            "test".into(),
            true,
        )
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn value(state: &AppState, id: &str) -> String {
    let lease = state.registry.acquire(id).unwrap().unwrap();
    Executor::single(id, lease.graph)
        .execute("MATCH (n:StringConstant) RETURN n.value AS value", Some(1))
        .unwrap()
        .rows[0]["value"]
        .as_str()
        .unwrap()
        .to_owned()
}

fn rule(cypher: &str) -> crate::topology::TopologyQuery {
    crate::topology::TopologyQuery {
        name: "fixture".into(),
        cypher: cypher.into(),
    }
}

#[tokio::test]
async fn failed_http_replacement_keeps_the_old_graph_and_exact_topology() {
    let fixture = Fixture::new();
    let old = fixture.graph("old", "g");
    let candidate = fixture.graph("candidate", "absent");
    let mut state = fixture.state();
    let original = state.registry.load("g", &old, None).unwrap();
    state.topology_queries = vec![rule(
        "MATCH (n:StringConstant) RETURN n.value AS source, n.value AS target",
    )];
    state.rebuild_topology().unwrap();
    let before = state.topology_api_map();
    let state = Arc::new(state);
    let response = crate::routes::router(state.clone())
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/graphs/g")
                .body(Body::from(
                    serde_json::json!({"path":candidate}).to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    let body = axum::body::to_bytes(response.into_body(), usize::MAX)
        .await
        .unwrap();
    assert!(String::from_utf8(body.to_vec()).unwrap().contains("absent"));
    assert_eq!(
        state.registry.describe("g").unwrap().unwrap().generation,
        original.generation
    );
    assert_eq!(value(&state, "g"), "g");
    assert_eq!(state.topology_api_map(), before);
}

#[tokio::test]
async fn failed_unload_preserves_graph_leases_and_topology() {
    let fixture = Fixture::new();
    let path = fixture.graph("graph", "value");
    let mut state = fixture.state();
    state.registry.load("g", &path, None).unwrap();
    let original = state.registry.load("h", &path, None).unwrap();
    state.topology_queries = vec![rule("RETURN 'g' AS source, 'h' AS target")];
    state.rebuild_topology().unwrap();
    let before = state.topology_api_map();
    let state = Arc::new(state);
    assert!(state
        .clone()
        .update_graph(GraphUpdate::Unload { id: "h".into() })
        .await
        .err()
        .unwrap()
        .contains("h"));
    assert_eq!(
        state.registry.describe("h").unwrap().unwrap().generation,
        original.generation
    );
    assert_eq!(value(&state, "h"), "value");
    assert_eq!(state.topology_api_map(), before);
}

#[derive(Default)]
struct Gate {
    ready: Mutex<bool>,
    wake: Condvar,
}
impl Gate {
    fn wait(&self) {
        let (ready, result) = self
            .wake
            .wait_timeout_while(
                self.ready.lock().unwrap(),
                Duration::from_secs(5),
                |ready| !*ready,
            )
            .unwrap();
        assert!(*ready && !result.timed_out(), "worker was not released");
    }
    fn release(&self) {
        *self.ready.lock().unwrap() = true;
        self.wake.notify_all();
    }
}
struct Release(Arc<Gate>);
impl Drop for Release {
    fn drop(&mut self) {
        self.0.release();
    }
}

fn pause_first_update(
    state: &mut AppState,
    phase: UpdatePhase,
) -> (tokio::sync::oneshot::Receiver<()>, Release) {
    let (entered, receiver) = tokio::sync::oneshot::channel();
    let entered = Mutex::new(Some(entered));
    let gate = Arc::new(Gate::default());
    let release = Release(gate.clone());
    let arrivals = AtomicUsize::new(0);
    state.graph_update_hook = Some(Arc::new(move |_, current| {
        if current == phase && arrivals.fetch_add(1, Ordering::SeqCst) == 0 {
            entered.lock().unwrap().take().unwrap().send(()).unwrap();
            gate.wait();
        }
    }));
    (receiver, release)
}

#[tokio::test(flavor = "current_thread")]
async fn stale_replace_and_unload_cannot_overwrite_a_newer_commit() {
    for unload in [false, true] {
        let fixture = Fixture::new();
        let old = fixture.graph("old", "old");
        let slow = fixture.graph("slow", "slow");
        let fresh = fixture.graph("fresh", "fresh");
        let mut state = fixture.state();
        state.graph_updates = Arc::new(tokio::sync::Semaphore::new(2));
        state.registry.load("g", &old, None).unwrap();
        state.rebuild_topology().unwrap();
        let (entered, release) = pause_first_update(&mut state, UpdatePhase::BeforeCommit);
        let state = Arc::new(state);
        let update = if unload {
            GraphUpdate::Unload { id: "g".into() }
        } else {
            GraphUpdate::Load {
                id: "g".into(),
                path: slow,
                mode: None,
            }
        };
        let first = tokio::spawn(state.clone().update_graph(update));
        tokio::time::timeout(Duration::from_secs(2), entered)
            .await
            .unwrap()
            .unwrap();
        let committed = state
            .clone()
            .update_graph(GraphUpdate::Load {
                id: "g".into(),
                path: fresh,
                mode: None,
            })
            .await
            .unwrap()
            .unwrap();
        release.0.release();
        let error = match first.await.unwrap() {
            Err(error) => error,
            Ok(_) => panic!("stale update committed"),
        };
        assert!(error.contains("catalog changed"));
        assert_eq!(
            state.registry.describe("g").unwrap().unwrap().generation,
            committed.generation
        );
        assert_eq!(value(&state, "g"), "fresh");
        assert_eq!(state.topology_api_map()["stale"], false);
    }
}

#[tokio::test(flavor = "current_thread")]
async fn dropped_load_cancels_publication_but_capacity_lives_until_worker_stops() {
    let fixture = Fixture::new();
    let old = fixture.graph("old", "old");
    let candidate = fixture.graph("candidate", "new");
    let mut state = fixture.state();
    state.graph_updates = Arc::new(tokio::sync::Semaphore::new(1));
    let original = state.registry.load("g", &old, None).unwrap();
    state.rebuild_topology().unwrap();
    let before = state.topology_api_map();
    let (entered, release) = pause_first_update(&mut state, UpdatePhase::Prepared);
    let state = Arc::new(state);
    let request = tokio::spawn(state.clone().update_graph(GraphUpdate::Load {
        id: "g".into(),
        path: candidate,
        mode: None,
    }));
    tokio::time::timeout(Duration::from_secs(2), entered)
        .await
        .unwrap()
        .unwrap();
    // A single runtime worker still answers another task while the load worker is parked.
    assert_eq!(
        tokio::spawn(async {
            tokio::task::yield_now().await;
            42
        })
        .await
        .unwrap(),
        42
    );
    request.abort();
    assert!(request.await.err().unwrap().is_cancelled());
    assert_eq!(state.graph_updates.available_permits(), 0);
    assert_eq!(
        state.registry.describe("g").unwrap().unwrap().generation,
        original.generation
    );
    release.0.release();
    tokio::time::timeout(Duration::from_secs(2), async {
        while state.graph_updates.available_permits() == 0 {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    assert_eq!(
        state.registry.describe("g").unwrap().unwrap().generation,
        original.generation
    );
    assert_eq!(value(&state, "g"), "old");
    assert_eq!(state.topology_api_map(), before);
}

#[tokio::test]
async fn topology_work_budget_rejects_an_update_without_publishing_any_part() {
    let fixture = Fixture::new();
    let old = fixture.graph("old", "old");
    let candidate = fixture.graph("candidate", "new");
    let mut state = fixture.state();
    let original = state.registry.load("g", &old, None).unwrap();
    state.rebuild_topology().unwrap();
    let before = state.topology_api_map();
    state.topology_queries = vec![rule(
        "MATCH (n:StringConstant) RETURN 'g' AS source, 'g' AS target",
    )];
    state.topology_work_limit = 1;
    let state = Arc::new(state);
    let error = match state
        .clone()
        .update_graph(GraphUpdate::Load {
            id: "g".into(),
            path: candidate,
            mode: None,
        })
        .await
    {
        Err(error) => error,
        Ok(_) => panic!("budget-exhausted update committed"),
    };
    assert!(error.contains("work budget exceeded"), "{error}");
    assert_eq!(
        state.registry.describe("g").unwrap().unwrap().generation,
        original.generation
    );
    assert_eq!(value(&state, "g"), "old");
    assert_eq!(state.topology_api_map(), before);
}

#[test]
fn parent_cancellation_and_timeout_after_topology_build_cannot_publish() {
    for timeout in [false, true] {
        let fixture = Fixture::new();
        let old = fixture.graph("old", "old");
        let candidate = fixture.graph("candidate", "new");
        let mut state = fixture.state();
        let original = state.registry.load("g", &old, None).unwrap();
        state.rebuild_topology().unwrap();
        let before = state.topology_api_map();
        let cancel = CancelToken::new();
        let completion = cancel.clone();
        state.graph_update_hook = Some(Arc::new(move |_, phase| {
            if phase == UpdatePhase::BeforeCommit {
                if timeout {
                    completion.timeout(1);
                } else {
                    completion.cancel();
                }
            }
        }));
        let error = match state.update_graph_blocking(
            GraphUpdate::Load {
                id: "g".into(),
                path: candidate,
                mode: None,
            },
            &cancel,
        ) {
            Err(error) => error,
            Ok(_) => panic!("cancelled update committed"),
        };
        assert!(
            error.contains(if timeout { "timed out" } else { "cancelled" }),
            "{error}"
        );
        assert_eq!(
            state.registry.describe("g").unwrap().unwrap().generation,
            original.generation
        );
        assert_eq!(value(&state, "g"), "old");
        assert_eq!(state.topology_api_map(), before);
    }
}

#[tokio::test]
async fn topology_rules_share_one_work_budget_for_the_whole_build() {
    let fixture = Fixture::new();
    let old = fixture.graph("old", "old");
    let candidate = fixture.graph("candidate", "new");
    let mut state = fixture.state();
    state.registry.load("g", &old, None).unwrap();
    state.topology_work_limit = 64;
    let query = rule("RETURN 'g' AS source, 'g' AS target");
    state.topology_queries = vec![query.clone()];
    state
        .rebuild_topology()
        .expect("one rule fits the maintenance budget");
    let before = state.topology_api_map();
    let original = state.registry.catalog_version();
    state.topology_queries = vec![query; 64];
    let state = Arc::new(state);
    let error = match state
        .clone()
        .update_graph(GraphUpdate::Load {
            id: "g".into(),
            path: candidate,
            mode: None,
        })
        .await
    {
        Err(error) => error,
        Ok(_) => panic!("per-rule resets bypassed the shared budget"),
    };
    assert!(error.contains("work budget exceeded"), "{error}");
    assert_eq!(state.registry.catalog_version(), original);
    assert_eq!(value(&state, "g"), "old");
    assert_eq!(state.topology_api_map(), before);
}
