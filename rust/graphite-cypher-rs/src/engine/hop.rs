//! An anchored plan for a single-hop pattern with a pushable predicate on one end.
//!
//! `MATCH (c)-[r:DATAFLOW]->(n) WHERE n.callee_class CONTAINS "x"` used to enumerate
//! every node as `c`, walk its edges and evaluate WHERE on each `n` reached -- the
//! whole graph, per graph, for a clause the CallSite index answers in microseconds.
//! Here the clause's pushable conjunct is resolved on the end it names, the other end
//! is reached back across the adjacency, and only those source nodes are expanded --
//! in ascending id order, with the full WHERE still evaluated on every row, so the rows
//! and their order are exactly what the exhaustive walk produces.

use super::matching::{rel_type_matches, rel_types_resolvable, Matcher};
use super::pipeline::{add_provenance, Row};
use super::scan::ScanPlan;
use super::Executor;
use crate::ast::{Direction, Expr, Pattern};
use crate::eval::Evaluator;
use crate::value::{NodeRef, SourceIdx, Value};
use crate::CypherResult;

pub struct HopPlan {
    /// Which end the anchored conjunct names: 0 the source node, 1 the target.
    anchor: usize,
    scan: ScanPlan,
    /// The conjunct the scan answers; the whole WHERE is still applied per row.
    conjunct: Expr,
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

impl HopPlan {
    pub fn build(patterns: &[Pattern], where_clause: Option<&Expr>) -> Option<HopPlan> {
        if super::optimizations_disabled() || patterns.len() != 1 {
            return None;
        }
        let p = &patterns[0];
        if p.path_variable.is_some() || p.nodes.len() != 2 || p.rels.len() != 1 {
            return None;
        }
        let rel = &p.rels[0];
        if rel.variable_length || !rel.properties.is_empty() || !rel_types_resolvable(&rel.types) {
            return None;
        }
        // The source must be nameable so it can be bound ahead of the expansion.
        p.nodes[0].variable.as_ref()?;
        if p.nodes.iter().any(|n| !n.properties.is_empty()) {
            return None;
        }
        let where_clause = where_clause?;
        let mut parts = Vec::new();
        conjuncts(where_clause, &mut parts);
        // The target end first: the predicate usually names what the query is
        // looking for, and reaching the sources back across the adjacency is cheap.
        for anchor in [1usize, 0] {
            let node = &p.nodes[anchor];
            if node.variable.is_none() {
                continue;
            }
            let single = Pattern {
                path_variable: None,
                nodes: vec![node.clone()],
                rels: Vec::new(),
            };
            for conj in &parts {
                if let Some(scan) = ScanPlan::build(std::slice::from_ref(&single), Some(conj)) {
                    return Some(HopPlan {
                        anchor,
                        scan,
                        conjunct: (*conj).clone(),
                    });
                }
            }
        }
        None
    }

    /// Enumerate the pattern's matches from an empty row, in the exhaustive walk's
    /// order, handing each row to `consume` after the full WHERE.
    pub fn run(
        &self,
        ex: &Executor,
        ev: &Evaluator,
        matcher: &Matcher,
        patterns: &[Pattern],
        where_clause: Option<&Expr>,
        consume: &mut dyn FnMut(Row) -> CypherResult<bool>,
    ) -> CypherResult<bool> {
        let pattern = &patterns[0];
        let source_var = pattern.nodes[0].variable.as_deref().expect("named source");
        let direction = pattern.rels[0].direction;
        let types = &pattern.rels[0].types;
        let mut emit = |r: Row| -> CypherResult<bool> {
            if let Some(w) = where_clause {
                if ev.eval(w, &r)?.as_bool() != Some(true) {
                    return Ok(true);
                }
            }
            consume(r)
        };
        // Matched anchor nodes arrive grouped by source, in id order; each source's
        // group is expanded once complete, so a LIMIT stops as early as it can.
        let mut current: Option<SourceIdx> = None;
        let mut matched: Vec<u32> = Vec::new();
        let mut flush = |source: SourceIdx, matched: &mut Vec<u32>| -> CypherResult<bool> {
            let graph = ex.graph(source);
            let mut sources: Vec<u32> = if self.anchor == 0 {
                std::mem::take(matched)
            } else {
                let mut out = Vec::new();
                for &t in matched.iter() {
                    // The edges the walk would have taken from `c` to reach `t`, seen
                    // from `t`'s side.
                    if direction != Direction::Incoming {
                        out.extend(
                            graph
                                .incoming(t)
                                .filter(|e| rel_type_matches(types, e))
                                .map(|e| e.from),
                        );
                    }
                    if direction != Direction::Outgoing {
                        out.extend(
                            graph
                                .outgoing(t)
                                .filter(|e| rel_type_matches(types, e))
                                .map(|e| e.to),
                        );
                    }
                }
                matched.clear();
                out
            };
            sources.sort_unstable();
            sources.dedup();
            for c in sources {
                ex.tick()?;
                let node = Value::Node(NodeRef { source, id: c });
                let mut row = Row::with_capacity(2);
                row.insert(source_var.to_string(), node.clone());
                add_provenance(&mut row, ex, &node);
                if !matcher.match_patterns(&row, patterns, &mut emit)? {
                    return Ok(false);
                }
            }
            Ok(true)
        };
        let cont = self
            .scan
            .matching_refs(ex, ev, Some(&self.conjunct), &mut |n| {
                if current != Some(n.source) {
                    if let Some(s) = current {
                        if !flush(s, &mut matched)? {
                            return Ok(false);
                        }
                    }
                    current = Some(n.source);
                }
                matched.push(n.id);
                Ok(true)
            })?;
        if !cont {
            return Ok(false);
        }
        if let Some(s) = current {
            return flush(s, &mut matched);
        }
        Ok(true)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ast::Clause;

    fn plan(query: &str) -> Option<HopPlan> {
        let clauses = crate::parser::parse(query).unwrap();
        let Clause::Match { patterns, .. } = &clauses[0] else {
            panic!("expected MATCH")
        };
        // A plain MATCH's WHERE is its own clause.
        let where_clause = match clauses.get(1) {
            Some(Clause::Where(e)) => Some(e),
            _ => None,
        };
        HopPlan::build(patterns, where_clause)
    }

    #[test]
    fn anchors_on_the_end_the_predicate_names() {
        let p = plan(r#"MATCH (c)-[r:DATAFLOW]->(n) WHERE n.callee_class CONTAINS "x" RETURN c"#)
            .expect("target anchor");
        assert_eq!(p.anchor, 1);
        let p = plan(r#"MATCH (c)-[r:DATAFLOW]->(n) WHERE c.value CONTAINS "x" RETURN c"#)
            .expect("source anchor");
        assert_eq!(p.anchor, 0);
        // A conjunct on either end anchors; the rest of the clause stays per row.
        let p = plan(
            r#"MATCH (c)-[r]->(n) WHERE n.callee_class CONTAINS "x" AND c.value = "y" RETURN c"#,
        )
        .expect("anchor from a conjunction");
        assert_eq!(p.anchor, 1);
    }

    #[test]
    fn declines_what_it_cannot_anchor() {
        assert!(plan(r#"MATCH (c)-[r:DATAFLOW]->(n) WHERE n.line = 5 RETURN c"#).is_none());
        assert!(plan(r#"MATCH (c)-[r:DATAFLOW]->(n) RETURN c"#).is_none());
        assert!(plan(
            r#"MATCH (c)-[r:DATAFLOW*1..2]->(n) WHERE n.callee_class CONTAINS "x" RETURN c"#
        )
        .is_none());
        assert!(plan(
            r#"MATCH p = (c)-[r:DATAFLOW]->(n) WHERE n.callee_class CONTAINS "x" RETURN p"#
        )
        .is_none());
        assert!(
            plan(r#"MATCH ()-[r:DATAFLOW]->(n) WHERE n.callee_class CONTAINS "x" RETURN n"#)
                .is_none()
        );
        assert!(
            plan(r#"MATCH (c)-[r]->(n)-[s]->(m) WHERE n.callee_class CONTAINS "x" RETURN c"#)
                .is_none()
        );
    }
}
