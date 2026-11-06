use thiserror::Error;

#[derive(Debug, Error, Clone, PartialEq)]
pub enum CypherError {
    /// `CypherParseException`: "Syntax error at position N: msg"
    #[error("Syntax error at position {position}: {message}")]
    Parse { position: usize, message: String },
    /// Grammar rejection with a diagnostic emitted at its lexer/parser origin.
    #[error("Syntax error at position {position}: {message}")]
    Syntax {
        position: usize,
        message: String,
        detail: &'static str,
    },
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

/// Structured evidence emitted by the engine's actual failure path.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CypherDiagnostic {
    pub error_type: &'static str,
    pub phase: &'static str,
    pub detail: &'static str,
}

impl CypherError {
    pub fn diagnostic(&self) -> Option<CypherDiagnostic> {
        match self {
            Self::Syntax { detail, .. } => Some(CypherDiagnostic {
                error_type: "SyntaxError",
                phase: "compile time",
                detail,
            }),
            _ => None,
        }
    }

    pub fn runtime<S: Into<String>>(s: S) -> CypherError {
        CypherError::Runtime(s.into())
    }
    pub fn other<S: Into<String>>(s: S) -> CypherError {
        CypherError::Other(s.into())
    }
}

pub type CypherResult<T> = Result<T, CypherError>;

#[cfg(test)]
mod diagnostic_tests {
    use super::*;
    #[test]
    fn grammar_rejection_has_compile_diagnostic_but_message_text_does_not_classify_runtime() {
        let error = CypherError::Syntax {
            position: 2,
            message: "unexpected token".into(),
            detail: "UnexpectedSyntax",
        };
        assert_eq!(
            error.diagnostic(),
            Some(CypherDiagnostic {
                error_type: "SyntaxError",
                phase: "compile time",
                detail: "UnexpectedSyntax"
            })
        );
        assert_eq!(
            CypherError::Parse {
                position: 0,
                message: "legacy conversion".into()
            }
            .diagnostic(),
            None
        );
        assert_eq!(
            CypherError::runtime("UndefinedVariable SyntaxError").diagnostic(),
            None
        );
    }
}
