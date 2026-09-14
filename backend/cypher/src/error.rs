use thiserror::Error;

#[derive(Debug, Error, Clone, PartialEq)]
pub enum CypherError {
    /// `CypherParseException`: "Syntax error at position N: msg"
    #[error("Syntax error at position {position}: {message}")]
    Parse { position: usize, message: String },
    /// `CypherException` with a verbatim message (e.g. "Unknown function: foo").
    #[error("{0}")]
    Runtime(String),
    /// `CypherAggregationException`
    #[error("Aggregation function '{0}' must be used in RETURN or WITH clause")]
    Aggregation(String),
    /// `NotImplementedError` from Kotlin `TODO(...)`.
    #[error("An operation is not implemented: {0}")]
    NotImplemented(String),
    #[error("Cypher query cancelled")]
    Cancelled,
    #[error("Cypher query timed out after {0} ms")]
    Timeout(u64),
    #[error("Cypher work budget exceeded after {0} graph work units; add a selective label/filter or use a metadata-backed query")]
    BudgetExceeded(u64),
    /// Any other JVM exception type (ClassCastException, IndexOutOfBounds, ...), message as-is.
    #[error("{0}")]
    Other(String),
}

impl CypherError {
    pub fn runtime<S: Into<String>>(s: S) -> CypherError {
        CypherError::Runtime(s.into())
    }
    pub fn other<S: Into<String>>(s: S) -> CypherError {
        CypherError::Other(s.into())
    }
}

pub type CypherResult<T> = Result<T, CypherError>;
