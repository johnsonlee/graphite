//! Skip row construction for Method sources excluded by a total, restricted WHERE.

use super::pipeline::{add_provenance, Row};
use super::Executor;
use crate::ast::{CmpOp, Expr, Literal, Pattern, StrOp};
use crate::value::{MethodRef, SourceIdx, Value};
use crate::CypherResult;

// Planning must also be bounded when there are no Methods to evaluate the WHERE on.
const MAX_DEPTH: usize = 32;
const MAX_NODES: usize = 256;

pub(super) struct MethodSourceGuard<'a> {
    variable: &'a str,
    predicate: &'a Expr,
    reads_class: bool,
}

impl<'a> MethodSourceGuard<'a> {
    pub(super) fn build(
        ex: &Executor,
        patterns: &'a [Pattern],
        clause: Option<&'a Expr>,
        seed: &Row,
    ) -> Option<Self> {
        if !ex.cross || super::optimizations_disabled() || !seed.is_empty() {
            return None;
        }
        let [pattern] = patterns else { return None };
        let [node] = pattern.nodes.as_slice() else {
            return None;
        };
        if pattern.path_variable.is_some()
            || !pattern.rels.is_empty()
            || node.labels.as_slice() != ["Method"]
            || !node.properties.is_empty()
        {
            return None;
        }
        let variable = node.variable.as_deref()?;
        let predicate = clause?;
        let mut remaining = MAX_NODES;
        let (reads_class, reads_graph_id) = validate(predicate, variable, 0, &mut remaining)?;
        if !reads_graph_id {
            return None;
        }
        Some(Self {
            variable,
            predicate,
            reads_class,
        })
    }

    pub(super) fn run(
        &self,
        ex: &Executor,
        emit: &mut dyn FnMut(Row) -> CypherResult<bool>,
    ) -> CypherResult<bool> {
        for (source, src) in ex.sources.iter().enumerate() {
            let admitted = possible(self.predicate, &src.id);
            for (index, method) in src.graph.methods().iter().enumerate() {
                // Match the generic Method enumerator's polling, including rejected sources.
                ex.tick()?;
                // AND/OR evaluate both sides. A malformed class SID must still reach the
                // original evaluator, even when the graphId comparison already says false.
                if !admitted
                    && (!self.reads_class
                        || (method.declaring_class as usize) < src.graph.strings().len())
                {
                    continue;
                }
                let value = Value::Method(MethodRef {
                    source: source as SourceIdx,
                    index: index as u32,
                });
                let mut row = Row::new();
                row.insert(self.variable.to_owned(), value.clone());
                add_provenance(&mut row, ex, &value);
                // Survivors retain the entire original WHERE, not a reduced predicate.
                if !emit(row)? {
                    return Ok(false);
                }
            }
        }
        Ok(true)
    }
}

fn property(expr: &Expr, variable: &str, key: &str) -> bool {
    matches!(expr, Expr::Property { expr, key: actual }
        if actual == key && matches!(expr.as_ref(), Expr::Variable(name) if name == variable))
}

// Return whether permitted leaves read class and graphId. Reject the entire predicate on any
// unknown expression: graphId filtering must not hide errors in a non-short-circuit WHERE.
fn validate(
    expr: &Expr,
    variable: &str,
    depth: usize,
    remaining: &mut usize,
) -> Option<(bool, bool)> {
    if depth >= MAX_DEPTH || *remaining == 0 {
        return None;
    }
    *remaining -= 1;
    match expr {
        Expr::And(a, b) | Expr::Or(a, b) => {
            let left = validate(a, variable, depth + 1, remaining)?;
            let right = validate(b, variable, depth + 1, remaining)?;
            Some((left.0 || right.0, left.1 || right.1))
        }
        Expr::Comparison {
            op: CmpOp::Eq,
            left,
            right,
        } if property(left, variable, "graphId")
            && matches!(right.as_ref(), Expr::Literal(Literal::Str(_))) =>
        {
            Some((false, true))
        }
        Expr::StringOp {
            op: StrOp::StartsWith,
            left,
            right,
        } if property(left, variable, "class")
            && matches!(right.as_ref(), Expr::Literal(Literal::Str(_))) =>
        {
            Some((true, false))
        }
        _ => None,
    }
}

// Only called after validating the entire bounded AST. Class leaves may be true;
// graphId leaves are exact for a cross-graph Method. False therefore proves exclusion.
fn possible(expr: &Expr, graph_id: &str) -> bool {
    match expr {
        Expr::And(a, b) => possible(a, graph_id) && possible(b, graph_id),
        Expr::Or(a, b) => possible(a, graph_id) || possible(b, graph_id),
        Expr::Comparison { right, .. } => {
            matches!(right.as_ref(), Expr::Literal(Literal::Str(id)) if id == graph_id)
        }
        Expr::StringOp { .. } => true,
        _ => unreachable!("validated Method source predicate"),
    }
}

#[cfg(test)]
#[path = "method_source_guard_tests.rs"]
mod tests;
