//! Cypher engine for Graphite persisted graphs.

pub mod ast;
pub mod context;
pub mod error;
pub mod value;

pub use semantics::{java_double_to_string, java_float_to_string};

// Parser (lexer + parser + AST rendering)
pub mod lexer;
pub mod parser;
pub mod render;

// Evaluation
pub mod eval;
pub mod functions;
pub mod gson;
pub mod materialize;
pub mod ordering;
pub mod semantics;
pub mod tostring;

// Execution
pub mod engine;

pub use error::{CypherError, CypherResult};
pub use value::Value;
