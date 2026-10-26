//! A pushed-down first-node seed for relationship patterns over string constants.
//!
//! Code search commonly starts from a literal and then walks one to three hops:
//! `MATCH (s:StringConstant)-[*1..3]-(n) WHERE s.value CONTAINS "needle"`.
//! The general matcher expands every string constant before WHERE can reject the
//! starting node.  This plan uses the ordinary string scan to select `s` first, then
//! hands each survivor to the ordinary matcher.  It changes only the candidate source;
//! node constraints, relationship order and uniqueness, the complete WHERE clause,
//! provenance and early termination remain on their existing paths.

use super::matching::{resolve_node_class, Matcher, NodeClass};
use super::pipeline::Row;
use super::scan::ScanPlan;
use super::Executor;
use crate::ast::{Expr, Pattern};
use crate::eval::Evaluator;
use crate::value::Value;
use crate::CypherResult;
use graphite_storage::node::TAG_STRING_CONSTANT;

pub(super) struct StringSeedPlan {
    scan: ScanPlan,
    /// The self-contained conjunct used to select the first node.  The complete WHERE
    /// is deliberately not evaluated until all pattern variables have been bound.
    conjunct: Option<Expr>,
}

/// Top-level conjuncts are independently necessary.  A candidate selected by any one
/// of them is therefore a safe superset of the complete WHERE result.
fn conjuncts<'a>(expr: &'a Expr, out: &mut Vec<&'a Expr>) {
    match expr {
        Expr::And(left, right) => {
            conjuncts(left, out);
            conjuncts(right, out);
        }
        _ => out.push(expr),
    }
}

impl StringSeedPlan {
    pub(super) fn build(
        patterns: &[Pattern],
        where_clause: Option<&Expr>,
        seed: &Row,
    ) -> Option<Self> {
        if super::optimizations_disabled() || !seed.is_empty() {
            return None;
        }
        let pattern = patterns.first()?;
        // A node-only query already has ScanPlan's faster direct path.  This plan is
        // specifically the bridge from that scan into relationship matching.
        if pattern.rels.is_empty() || pattern.nodes.len() < 2 {
            return None;
        }
        let first = pattern.nodes.first()?;
        first.variable.as_ref()?;
        let NodeClass::Tags(tags) = resolve_node_class(&first.labels) else {
            return None;
        };
        if tags.as_slice() != [TAG_STRING_CONSTANT] {
            return None;
        }

        let single = Pattern {
            path_variable: None,
            nodes: vec![first.clone()],
            rels: Vec::new(),
        };

        // Prefer a WHERE conjunct because it is what makes the screenshots expensive.
        // Passing only that conjunct is essential: another conjunct may name `n`, which
        // does not exist until after the walk.  ScanPlan still incorporates literal
        // properties from the first node's inline map.
        let mut parts = Vec::new();
        if let Some(clause) = where_clause {
            conjuncts(clause, &mut parts);
        }
        for conjunct in parts {
            if let Some(scan) = ScanPlan::build(std::slice::from_ref(&single), Some(conjunct)) {
                return Some(Self {
                    scan,
                    conjunct: Some(conjunct.clone()),
                });
            }
        }

        ScanPlan::build(std::slice::from_ref(&single), None).map(|scan| Self {
            scan,
            conjunct: None,
        })
    }

    pub(super) fn run(
        &self,
        ex: &Executor,
        ev: &Evaluator,
        matcher: &Matcher,
        patterns: &[Pattern],
        emit: &mut dyn FnMut(Row) -> CypherResult<bool>,
    ) -> CypherResult<bool> {
        let seed = Row::new();
        self.scan
            .matching_refs(ex, ev, self.conjunct.as_ref(), &mut |node| {
                matcher.match_patterns_at(&seed, patterns, Value::Node(node), emit)
            })
    }
}

#[cfg(test)]
#[path = "string_seed_tests.rs"]
mod tests;
