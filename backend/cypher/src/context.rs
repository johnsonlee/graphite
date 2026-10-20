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
    /// Nullness with the same semantics as `node_property`. Contexts may avoid
    /// materialising values whose presence is known from declaration bindings.
    fn node_property_is_null(&self, node: NodeRef, key: &str) -> bool {
        self.node_property(node, key).is_null()
    }
    /// `getAllProperties(node)` — fixed per-type map including `id` (no `type`).
    fn node_properties(&self, node: NodeRef) -> IndexMap<String, Value>;
    /// Ordered property names, without requiring values to be materialised.
    fn node_keys(&self, node: NodeRef) -> Vec<String> {
        self.node_properties(node).into_keys().collect()
    }
    /// The map a node materialises to in a query result. This differs from
    /// `node_properties`: signatures are omitted and null-valued keys are dropped,
    /// matching `CypherExecutor.nodeToMap` and Gson's null handling.
    fn node_result_properties(&self, node: NodeRef) -> IndexMap<String, Value>;
    /// `node_result_properties` before null-valued keys are dropped, for `toString()`
    /// rendering, which shows them where JSON does not.
    fn node_display_properties(&self, node: NodeRef) -> IndexMap<String, Value>;
    /// `labels(n)` list, in Kotlin order (e.g. ["IntConstant","Constant"]).
    fn node_labels(&self, node: NodeRef) -> Vec<&'static str>;
    /// Concrete node type name (e.g. "CallSiteNode").
    fn node_type_name(&self, node: NodeRef) -> &'static str;

    /// Relationship property (`kind`, `virtual`, `dynamic`, `type`, `graphId`), else Null.
    fn rel_property(&self, rel: EdgeRef, key: &str) -> Value;
    /// The branch comparison a ControlFlowEdge carries, as (operator, comparand node id).
    /// `None` for every other family, and for a branch edge that records none.
    fn edge_comparison(&self, rel: EdgeRef) -> Option<(String, u32)>;
    /// `type(r)` string, e.g. "DATAFLOW".
    fn rel_type(&self, rel: EdgeRef) -> &'static str;

    /// Method virtual node properties: signature, class, name, parameter_types, return_type, graphId.
    fn method_property(&self, m: MethodRef, key: &str) -> Value;
    fn method_property_is_null(&self, m: MethodRef, key: &str) -> bool {
        self.method_property(m, key).is_null()
    }
    fn method_properties(&self, m: MethodRef) -> IndexMap<String, Value>;
    /// `method.signature`
    fn method_signature(&self, m: MethodRef) -> String;

    /// Cancellation / timeout polling hook; returns Err when cancelled.
    fn check_cancelled(&self) -> crate::CypherResult<()> {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ast::Expr;
    use crate::eval::Evaluator;
    use std::cell::Cell;

    struct DynamicContext {
        reads: Cell<usize>,
    }

    // An external context keeps its ordinary property semantics through the
    // default hooks, including annotation values with generic-looking names.
    impl GraphContext for DynamicContext {
        fn source_count(&self) -> usize {
            1
        }
        fn is_cross_graph(&self) -> bool {
            false
        }
        fn graph_id(&self, _: SourceIdx) -> &str {
            "test"
        }
        fn node_property(&self, _: NodeRef, key: &str) -> Value {
            self.reads.set(self.reads.get() + 1);
            match key {
                "generic_type" => Value::str("dynamic annotation value"),
                _ => Value::Null,
            }
        }
        fn node_properties(&self, _: NodeRef) -> IndexMap<String, Value> {
            unreachable!()
        }
        fn node_result_properties(&self, _: NodeRef) -> IndexMap<String, Value> {
            unreachable!()
        }
        fn node_display_properties(&self, _: NodeRef) -> IndexMap<String, Value> {
            unreachable!()
        }
        fn node_labels(&self, _: NodeRef) -> Vec<&'static str> {
            vec!["AnnotationNode"]
        }
        fn node_type_name(&self, _: NodeRef) -> &'static str {
            "AnnotationNode"
        }
        fn rel_property(&self, _: EdgeRef, _: &str) -> Value {
            unreachable!()
        }
        fn edge_comparison(&self, _: EdgeRef) -> Option<(String, u32)> {
            unreachable!()
        }
        fn rel_type(&self, _: EdgeRef) -> &'static str {
            unreachable!()
        }
        fn method_property(&self, _: MethodRef, _: &str) -> Value {
            self.reads.set(self.reads.get() + 1);
            Value::list(vec![])
        }
        fn method_properties(&self, _: MethodRef) -> IndexMap<String, Value> {
            unreachable!()
        }
        fn method_signature(&self, _: MethodRef) -> String {
            unreachable!()
        }
    }

    #[test]
    fn default_presence_hooks_preserve_dynamic_values_and_read_once() {
        let ctx = DynamicContext {
            reads: Cell::new(0),
        };
        let params = IndexMap::new();
        let evaluator = Evaluator::new(&ctx, &params);
        for (base, key, expected_null) in [
            (
                Value::Node(NodeRef { source: 0, id: 1 }),
                "generic_type",
                false,
            ),
            (Value::Node(NodeRef { source: 0, id: 1 }), "type_info", true),
            (
                Value::Method(MethodRef {
                    source: 0,
                    index: 0,
                }),
                "type_parameters",
                false,
            ),
        ] {
            let row = IndexMap::from([("value".into(), base)]);
            let property = Expr::Property {
                expr: Box::new(Expr::Variable("value".into())),
                key: key.into(),
            };
            let before = ctx.reads.get();
            assert_eq!(
                evaluator
                    .eval(&Expr::IsNull(Box::new(property.clone())), &row)
                    .unwrap()
                    .as_bool(),
                Some(expected_null)
            );
            assert_eq!(ctx.reads.get(), before + 1);
            assert_eq!(
                evaluator
                    .eval(&Expr::IsNotNull(Box::new(property)), &row)
                    .unwrap()
                    .as_bool(),
                Some(!expected_null)
            );
            assert_eq!(ctx.reads.get(), before + 2);
        }
    }
}
