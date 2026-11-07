//! Accepted queries own capacity and metrics through execution and response construction.
use crate::guard::{CancelOnDrop, Outcome, QueryPermit};
use graphite_cypher::{CypherError, CypherResult};
use std::panic::{catch_unwind, AssertUnwindSafe};

pub(crate) enum QueryJobError {
    Query(CypherError),
    Failed,
}

pub(crate) async fn run<T: Send + 'static>(
    permit: QueryPermit,
    work: impl FnOnce(&QueryPermit) -> CypherResult<T> + Send + 'static,
) -> Result<T, QueryJobError> {
    let _cancel = CancelOnDrop(permit.cancel.clone());
    tokio::task::spawn_blocking(move || {
        let mut permit = permit;
        let result = catch_unwind(AssertUnwindSafe(|| work(&permit)));
        let (outcome, result) = match result {
            Ok(Ok(value)) => (Outcome::Success, Ok(value)),
            Ok(Err(error)) => (Outcome::of(Some(&error)), Err(QueryJobError::Query(error))),
            Err(_) => (Outcome::Failed, Err(QueryJobError::Failed)),
        };
        permit.complete(outcome);
        result
    })
    .await
    .map_err(|_| QueryJobError::Failed)?
}
