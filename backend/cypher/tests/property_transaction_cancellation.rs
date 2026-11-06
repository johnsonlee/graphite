use graphite_cypher::{
    engine::{CancelToken, Executor},
    procedures::{ProcedureRegistry, ProcedureSignature},
    property_graph::PropertyGraph,
    CypherError,
};
use std::{
    sync::{mpsc, Arc, Mutex},
    time::Duration,
};

fn queued_request(cancel: Arc<CancelToken>, cancel_after_start: bool) -> CypherError {
    let graph = Arc::new(PropertyGraph::new());
    let (entered_tx, entered_rx) = mpsc::channel();
    let (release_tx, release_rx) = mpsc::channel();
    let release_rx = Mutex::new(release_rx);
    let mut registry = ProcedureRegistry::new();
    registry
        .register(
            ProcedureSignature {
                name: "hold".into(),
                inputs: vec![],
                outputs: vec![],
            },
            move |_| {
                entered_tx.send(()).unwrap();
                release_rx.lock().unwrap().recv().unwrap();
                Ok(vec![])
            },
        )
        .unwrap();
    let first = Executor::property_graph(graph.clone()).with_procedures(Arc::new(registry));
    let holder = std::thread::spawn(move || {
        first.execute("CREATE (:Committed) WITH 1 AS x CALL hold() RETURN x", None)
    });
    entered_rx.recv_timeout(Duration::from_secs(5)).unwrap();
    let (started_tx, started_rx) = mpsc::channel();
    let (done_tx, done_rx) = mpsc::channel();
    let second = Executor::property_graph(graph.clone()).with_cancel(cancel.clone());
    let waiter = std::thread::spawn(move || {
        started_tx.send(()).unwrap();
        done_tx
            .send(second.execute("CREATE (:Queued)", None))
            .unwrap();
    });
    started_rx.recv_timeout(Duration::from_secs(5)).unwrap();
    if cancel_after_start {
        cancel.cancel();
    }
    // Correctness deadline: the cancelled request must finish while the holder
    // remains blocked. Always release and join both workers before asserting.
    let immediate = done_rx.recv_timeout(Duration::from_millis(500));
    release_tx.send(()).unwrap();
    let held_result = holder.join().unwrap();
    waiter.join().unwrap();
    held_result.unwrap();
    let result = immediate.expect("cancelled/deadline request waited for an unrelated transaction");
    let snapshot = graph.snapshot();
    assert_eq!(snapshot.nodes.len(), 1);
    assert_eq!(snapshot.nodes[0].labels, ["Committed"]);
    result.unwrap_err()
}

#[test]
fn pre_cancelled_request_does_not_wait_for_another_transaction() {
    let cancel = CancelToken::new();
    cancel.cancel();
    assert!(matches!(
        queued_request(cancel, false),
        CypherError::Cancelled
    ));
}

#[test]
fn cancellation_while_queued_does_not_wait_for_another_transaction() {
    assert!(matches!(
        queued_request(CancelToken::new(), true),
        CypherError::Cancelled
    ));
}

#[test]
fn deadline_while_queued_does_not_wait_for_another_transaction() {
    assert!(matches!(
        queued_request(CancelToken::with_timeout(20), false),
        CypherError::Timeout(20)
    ));
}
