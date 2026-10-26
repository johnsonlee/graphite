//! Exact local node-ID equality, filtering the existing MATCH candidate domain.
//!
//! `MATCH (n {graphId: "g", id: 616246})-[r]-(m) RETURN ... LIMIT 50` is how the
//! Explorer expands one node. Enumerated generically it visited every node of every
//! loaded graph and decoded each record to compare `id` -- seconds per request across
//! a few dozen graphs, for a node the id names outright. Here the first node of the
//! first pattern is looked up by id in the type index, in the order the exhaustive walk
//! would meet it, and the rest of the pattern is matched from there; a `graphId` the
//! pattern or clause pins skips the other graphs altogether. The node's constraints
//! and the whole WHERE are still evaluated on every row, so the plan is a pre-filter
//! that cannot change results.

use super::matching::{resolve_node_class, Matcher, NodeClass};
use super::pipeline::Row;
use super::Executor;
use crate::ast::{CmpOp, Expr, Literal, Pattern};
use crate::value::{NodeRef, SourceIdx, Value};
use crate::CypherResult;

pub(super) struct IdCandidatePlan<'a> {
    wanted: u32,
    /// The only graph the node can come from, when the pattern or clause names one.
    graph: Option<&'a str>,
    /// Node tags admitted by the first node's label.
    tags: Vec<u8>,
}

/// Top-level conjuncts of a WHERE clause.
fn conjuncts<'a>(e: &'a Expr, out: &mut Vec<&'a Expr>) {
    match e {
        Expr::And(a, b) => {
            conjuncts(a, out);
            conjuncts(b, out);
        }
        _ => out.push(e),
    }
}

/// `id(<variable>)` or `<variable>.id`.
fn names_id(e: &Expr, variable: &str) -> bool {
    match e {
        Expr::FunctionCall {
            name,
            distinct: false,
            args,
        } => {
            matches!(args.as_slice(), [Expr::Variable(bound)] if bound == variable)
                && name.eq_ignore_ascii_case("id")
        }
        Expr::Property { expr, key } => {
            key == "id" && matches!(expr.as_ref(), Expr::Variable(bound) if bound == variable)
        }
        _ => false,
    }
}

/// `<variable>.graphId`.
fn names_graph(e: &Expr, variable: &str) -> bool {
    matches!(e, Expr::Property { expr, key }
        if key == "graphId" && matches!(expr.as_ref(), Expr::Variable(bound) if bound == variable))
}

impl<'a> IdCandidatePlan<'a> {
    pub(super) fn build(
        patterns: &'a [Pattern],
        clause: Option<&'a Expr>,
        seed: &Row,
    ) -> Option<Self> {
        if super::optimizations_disabled() || !seed.is_empty() {
            return None;
        }
        let node = patterns.first()?.nodes.first()?;
        let variable = node.variable.as_deref()?;
        let NodeClass::Tags(tags) = resolve_node_class(&node.labels) else {
            return None;
        };
        let mut wanted: Option<u32> = None;
        let mut graph: Option<&str> = None;
        // A property map `{k: v}` is `n.k = v` for every entry.
        for (key, value) in &node.properties {
            match (key.as_str(), value) {
                ("id", Expr::Literal(Literal::Int(v))) => {
                    wanted = wanted.or_else(|| u32::try_from(*v).ok());
                }
                ("graphId", Expr::Literal(Literal::Str(g))) => {
                    graph = graph.or(Some(g.as_str()));
                }
                _ => {}
            }
        }
        // Every top-level conjunct holds on every row, so an equality among them
        // pins the node as surely as the map does.
        let mut parts = Vec::new();
        if let Some(clause) = clause {
            conjuncts(clause, &mut parts);
        }
        for conj in parts {
            let Expr::Comparison {
                op: CmpOp::Eq,
                left,
                right,
            } = conj
            else {
                continue;
            };
            for (lhs, rhs) in [(left, right), (right, left)] {
                match rhs.as_ref() {
                    Expr::Literal(Literal::Int(v)) if names_id(lhs, variable) => {
                        wanted = wanted.or_else(|| u32::try_from(*v).ok());
                    }
                    Expr::Literal(Literal::Str(g)) if names_graph(lhs, variable) => {
                        graph = graph.or(Some(g.as_str()));
                    }
                    _ => {}
                }
            }
        }
        Some(Self {
            wanted: wanted?,
            graph,
            tags,
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
            // `graphId` is the source's id only across graphs; alone, a graph has none
            // and the matcher rejects the constraint itself.
            if let Some(g) = self.graph {
                if ex.cross && *src.id != *g {
                    continue;
                }
            }
            for &tag in &self.tags {
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
        matcher: &Matcher,
        patterns: &[Pattern],
        emit: &mut dyn FnMut(Row) -> CypherResult<bool>,
    ) -> CypherResult<bool> {
        let seed = Row::new();
        self.candidates(ex, &mut |source, id| {
            let value = Value::Node(NodeRef { source, id });
            matcher.match_patterns_at(&seed, patterns, value, emit)
        })
    }
}

#[cfg(test)]
#[path = "id_candidate_tests.rs"]
mod tests;
