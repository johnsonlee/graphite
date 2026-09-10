//! Abstraction over graph sources used by the expression evaluator.
//!
//! The executor implements this for one or many `graphite_storage::Graph`s.

use crate::value::{EdgeRef, MethodRef, NodeRef, SourceIdx, Value};
use indexmap::IndexMap;

pub trait GraphContext {
    /// Number of graph sources (1 for a single-graph executor).
    fn source_count(&self) -> usize;
    /// True when the executor runs in cross-graph mode (values become "qualified":
    /// `graphId`, `elementId`, `qualifiedId` are exposed and `$metadata.graphIds` provenance is tracked).
    fn is_cross_graph(&self) -> bool;
    /// Graph id string of a source (e.g. "orders").
    fn graph_id(&self, source: SourceIdx) -> &str;

    /// `NodePropertyAccessor.getProperty(node, key)` semantics, including the `id` and `type` fallbacks.
    /// Returns `Value::Null` when absent.
    fn node_property(&self, node: NodeRef, key: &str) -> Value;
    /// `getAllProperties(node)` — fixed per-type map including `id` (no `type`).
    fn node_properties(&self, node: NodeRef) -> IndexMap<String, Value>;
    /// The map a node materialises to in a query result. This differs from
    /// `node_properties`: signatures are omitted and null-valued keys are dropped,
    /// matching `CypherExecutor.nodeToMap` and Gson's null handling.
    fn node_result_properties(&self, node: NodeRef) -> IndexMap<String, Value>;
    /// `labels(n)` list, in Kotlin order (e.g. ["IntConstant","Constant"]).
    fn node_labels(&self, node: NodeRef) -> Vec<&'static str>;
    /// Concrete node type name (e.g. "CallSiteNode").
    fn node_type_name(&self, node: NodeRef) -> &'static str;

    /// Relationship property (`kind`, `virtual`, `dynamic`, `type`, `graphId`), else Null.
    fn rel_property(&self, rel: EdgeRef, key: &str) -> Value;
    /// `type(r)` string, e.g. "DATAFLOW".
    fn rel_type(&self, rel: EdgeRef) -> &'static str;

    /// Method virtual node properties: signature, class, name, parameter_types, return_type, graphId.
    fn method_property(&self, m: MethodRef, key: &str) -> Value;
    fn method_properties(&self, m: MethodRef) -> IndexMap<String, Value>;
    /// `method.signature`
    fn method_signature(&self, m: MethodRef) -> String;

    /// Cancellation / timeout polling hook; returns Err when cancelled.
    fn check_cancelled(&self) -> crate::CypherResult<()> {
        Ok(())
    }
}
