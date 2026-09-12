//! Runtime values produced by the Cypher engine.

use graphite_storage::{Edge, NodeId};
use indexmap::IndexMap;
use std::sync::Arc;

/// Index of a graph source inside a (cross-graph) executor.
pub type SourceIdx = u32;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct NodeRef {
    pub source: SourceIdx,
    pub id: NodeId,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct EdgeRef {
    pub source: SourceIdx,
    pub edge: Edge,
}

/// A virtual `Method` node backed by metadata.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct MethodRef {
    pub source: SourceIdx,
    pub index: u32,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PathValue {
    pub source: SourceIdx,
    pub nodes: Vec<NodeId>,
    pub edges: Vec<Edge>,
}

#[derive(Debug, Clone)]
pub enum Value {
    Null,
    Bool(bool),
    /// Kotlin Int or Long. `is_int32` distinguishes `Int` for arithmetic parity.
    Int(i64),
    Float(f64),
    /// Kotlin `Float` node property (kept separate for JSON rendering parity).
    Float32(f32),
    Str(Arc<str>),
    List(Arc<Vec<Value>>),
    Map(Arc<IndexMap<String, Value>>),
    Node(NodeRef),
    Rel(EdgeRef),
    Path(Arc<PathValue>),
    Method(MethodRef),
}

impl Value {
    pub fn str<S: Into<Arc<str>>>(s: S) -> Value {
        Value::Str(s.into())
    }
    pub fn list(v: Vec<Value>) -> Value {
        Value::List(Arc::new(v))
    }
    pub fn map(m: IndexMap<String, Value>) -> Value {
        Value::Map(Arc::new(m))
    }
    #[inline]
    pub fn is_null(&self) -> bool {
        matches!(self, Value::Null)
    }
    pub fn as_str(&self) -> Option<&str> {
        match self {
            Value::Str(s) => Some(s),
            _ => None,
        }
    }
    pub fn as_bool(&self) -> Option<bool> {
        match self {
            Value::Bool(b) => Some(*b),
            _ => None,
        }
    }
    pub fn as_list(&self) -> Option<&[Value]> {
        match self {
            Value::List(l) => Some(l.as_slice()),
            _ => None,
        }
    }
    pub fn is_number(&self) -> bool {
        matches!(self, Value::Int(_) | Value::Float(_) | Value::Float32(_))
    }
    /// Kotlin `Number.toDouble()`.
    pub fn as_f64(&self) -> Option<f64> {
        match self {
            Value::Int(i) => Some(*i as f64),
            Value::Float(f) => Some(*f),
            Value::Float32(f) => Some(*f as f64),
            _ => None,
        }
    }
}

impl From<&str> for Value {
    fn from(s: &str) -> Value {
        Value::Str(Arc::from(s))
    }
}
impl From<String> for Value {
    fn from(s: String) -> Value {
        Value::Str(Arc::from(s))
    }
}
impl From<i64> for Value {
    fn from(i: i64) -> Value {
        Value::Int(i)
    }
}
impl From<i32> for Value {
    fn from(i: i32) -> Value {
        Value::Int(i as i64)
    }
}
impl From<bool> for Value {
    fn from(b: bool) -> Value {
        Value::Bool(b)
    }
}
impl From<f64> for Value {
    fn from(f: f64) -> Value {
        Value::Float(f)
    }
}
