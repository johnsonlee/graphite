//! Exact local node-ID equality, filtering the existing MATCH candidate domain.

use super::pipeline::{add_provenance, Row};
use super::Executor;
use crate::ast::{CmpOp, Expr, Literal, Pattern};
use crate::value::{NodeRef, SourceIdx, Value};
use crate::CypherResult;
use graphite_storage::node::TAG_COUNT;

pub(super) struct IdCandidatePlan<'a> {
    variable: &'a str,
    wanted: u32,
}

impl<'a> IdCandidatePlan<'a> {
    pub(super) fn build(
        patterns: &'a [Pattern],
        clause: Option<&Expr>,
        seed: &Row,
    ) -> Option<Self> {
        if super::optimizations_disabled() || !seed.is_empty() {
            return None;
        }
        let [pattern] = patterns else { return None };
        let [node] = pattern.nodes.as_slice() else {
            return None;
        };
        if pattern.path_variable.is_some()
            || !pattern.rels.is_empty()
            || !node.labels.is_empty()
            || !node.properties.is_empty()
        {
            return None;
        }
        let variable = node.variable.as_deref()?;
        let Expr::Comparison {
            op: CmpOp::Eq,
            left,
            right,
        } = clause?
        else {
            return None;
        };
        let equality = |function: &Expr, literal: &Expr| {
            let Expr::FunctionCall {
                name,
                distinct: false,
                args,
            } = function
            else {
                return None;
            };
            let [Expr::Variable(bound)] = args.as_slice() else {
                return None;
            };
            let Expr::Literal(Literal::Int(value)) = literal else {
                return None;
            };
            if !name.eq_ignore_ascii_case("id") || bound != variable {
                return None;
            }
            u32::try_from(*value).ok()
        };
        Some(Self {
            variable,
            wanted: equality(left, right).or_else(|| equality(right, left))?,
        })
    }

    /// Only matching IDs cross this callback boundary; no NodeRef or Row exists for a miss.
    /// Keep the original type-index domain, including duplicates and phantom IDs. Within a
    /// source every survivor has exactly the same NodeRef, so tag order and MergedWalk order
    /// produce identical visible rows even for unsorted lists. Sources retain their order.
    fn candidates(
        &self,
        ex: &Executor,
        emit: &mut dyn FnMut(SourceIdx, u32) -> CypherResult<bool>,
    ) -> CypherResult<bool> {
        for (source, src) in ex.sources.iter().enumerate() {
            ex.cancel.check()?;
            for tag in 0..TAG_COUNT as u8 {
                for &id in src.graph.ids_by_tag(tag) {
                    ex.tick()?;
                    if id == self.wanted && !emit(source as SourceIdx, id)? {
                        return Ok(false);
                    }
                }
            }
        }
        Ok(true)
    }

    pub(super) fn run(
        &self,
        ex: &Executor,
        emit: &mut dyn FnMut(Row) -> CypherResult<bool>,
    ) -> CypherResult<bool> {
        self.candidates(ex, &mut |source, id| {
            let value = Value::Node(NodeRef { source, id });
            let mut row = Row::new();
            row.insert(self.variable.to_owned(), value.clone());
            add_provenance(&mut row, ex, &value);
            emit(row)
        })
    }
}

#[cfg(test)]
#[path = "id_candidate_tests.rs"]
mod tests;
