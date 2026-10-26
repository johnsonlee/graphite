//! An anchored plan for a pattern with a pushable predicate on one of its ends.
//!
//! `MATCH (c)-[r:DATAFLOW]->(n) WHERE n.callee_class CONTAINS "x"` used to enumerate
//! every node as `c`, walk its edges and evaluate WHERE on each `n` reached -- the
//! whole graph, per graph, for a clause the CallSite index answers in microseconds.
//! Here the clause's pushable conjunct is resolved on the end it names, the other end
//! is reached back across the adjacency, and only those source nodes are expanded --
//! in ascending id order, with the full WHERE still evaluated on every row, so the rows
//! and their order are exactly what the exhaustive walk produces.
//!
//! When the predicate names the pattern's first node, nothing has to be reached back:
//! the scan's hits are the nodes the walk would have started from, so the pattern is
//! entered at each of them, however many relationships it has, variable-length or
//! not, with a path variable or further patterns after it.
//! `MATCH (s:StringConstant)-[*1..3]-(n) WHERE s.value = "x" ... LIMIT 100` used to
//! expand every string constant of every graph along `*1..3` and test the value on
//! the complete rows, which the LIMIT could not bound: tens of seconds across a few
//! dozen graphs for a predicate the string column answers at once.

use super::matching::{rel_type_matches, rel_types_resolvable, Matcher};
use super::pipeline::{add_provenance, Row};
use super::scan::ScanPlan;
use super::Executor;
use crate::ast::{Direction, Expr, Pattern};
use crate::eval::Evaluator;
use crate::value::{NodeRef, SourceIdx, Value};
use crate::CypherResult;

pub struct HopPlan {
    /// Which end the anchored conjunct names: 0 the first node of the first pattern,
    /// 1 the target of a single hop.
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
        if super::optimizations_disabled() {
            return None;
        }
        let p = patterns.first()?;
        if p.rels.is_empty() || p.rels.iter().any(|r| !rel_types_resolvable(&r.types)) {
            return None;
        }
        // The source must be nameable so it can be bound ahead of the expansion.
        p.nodes[0].variable.as_ref()?;
        // The target of a single plain hop can be reached back from; the first node
        // of any pattern can be entered at.
        let single_hop = patterns.len() == 1
            && p.path_variable.is_none()
            && p.rels.len() == 1
            && !p.rels[0].variable_length
            && p.rels[0].properties.is_empty()
            && p.nodes.iter().all(|n| n.properties.is_empty());
        let where_clause = where_clause?;
        let mut parts = Vec::new();
        conjuncts(where_clause, &mut parts);
        // The target end first: the predicate usually names what the query is
        // looking for, and reaching the sources back across the adjacency is cheap.
        for anchor in [1usize, 0] {
            if anchor == 1 && !single_hop {
                continue;
            }
            let node = &p.nodes[anchor];
            if node.variable.is_none() || !node.properties.is_empty() {
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
        let seed = Row::new();
        let mut flush = |source: SourceIdx, matched: &mut Vec<u32>| -> CypherResult<bool> {
            if self.anchor == 0 {
                // The hits are the walk's own starting points: enter the patterns at
                // each, in the walk's order, which checks the node's remaining
                // constraints and expands.
                matched.sort_unstable();
                matched.dedup();
                for &c in matched.iter() {
                    ex.tick()?;
                    let node = Value::Node(NodeRef { source, id: c });
                    if !matcher.match_patterns_at(&seed, patterns, node, &mut emit)? {
                        return Ok(false);
                    }
                }
                matched.clear();
                return Ok(true);
            }
            let source_var = pattern.nodes[0].variable.as_deref().expect("named source");
            let graph = ex.graph(source);
            let mut sources: Vec<u32> = {
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
        assert!(plan(r#"MATCH (c)-[r:DATAFLOW]->(n) WHERE n.line > 5 RETURN c"#).is_none());
        assert!(plan(r#"MATCH (c)-[r:DATAFLOW]->(n) RETURN c"#).is_none());
        // The target of anything but a single plain hop cannot be reached back from.
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
        assert!(plan(
            r#"MATCH (c)-[r:DATAFLOW]->(n {line: 1}) WHERE n.callee_class CONTAINS "x" RETURN c"#
        )
        .is_none());
        assert!(plan(
            r#"MATCH (c)-[r:DATAFLOW]->(n), (m) WHERE n.callee_class CONTAINS "x" RETURN c"#
        )
        .is_none());
        // Nor can an anchor carrying a property map, a predicate the scan cannot
        // answer, a pattern without a relationship, or an unknown relationship type.
        assert!(plan(
            r#"MATCH (s:StringConstant {line: 1})-[*1..3]-(n) WHERE s.value = "x" RETURN n"#
        )
        .is_none());
        assert!(
            plan(r#"MATCH (s:StringConstant)-[*1..3]-(n) WHERE s.line > 5 RETURN n"#).is_none()
        );
        assert!(plan(r#"MATCH (s:StringConstant) WHERE s.value = "x" RETURN s"#).is_none());
        assert!(plan(
            r#"MATCH (s:StringConstant)-[:NOSUCH*1..3]-(n) WHERE s.value = "x" RETURN n"#
        )
        .is_none());
    }

    /// Against the real fixture (`GRAPHITE_INDEX_FIXTURE`): every anchored shape yields
    /// the rows, order, provenance and probe of the exhaustive walk. The oracle wraps
    /// the predicate as `(predicate) = true`, which no scan can answer, so neither this
    /// plan nor any other takes it.
    #[test]
    fn anchored_expansion_matches_the_exhaustive_walk() {
        use crate::engine::{Executor, Source};
        use crate::value::Value;
        use std::sync::Arc;
        let Some(dir) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
            eprintln!("GRAPHITE_INDEX_FIXTURE unset; skipping");
            return;
        };
        let graph = Arc::new(graphite_storage::Graph::load(std::path::Path::new(&dir)).unwrap());
        let ex = Executor::new(
            vec![
                Source {
                    id: Arc::from("a"),
                    graph: graph.clone(),
                },
                Source {
                    id: Arc::from("z"),
                    graph,
                },
            ],
            true,
        )
        .with_probe();
        // A string constant with neighbours, and one that reaches a call site.
        let seeds = ex
            .execute(
                "MATCH (s:StringConstant)-[*1..3]-(n) RETURN DISTINCT s.value AS v ORDER BY v LIMIT 3",
                None,
            )
            .unwrap();
        assert!(!seeds.rows.is_empty());
        let values: Vec<String> = seeds
            .rows
            .iter()
            .map(|r| r["v"].as_str().unwrap().to_string())
            .collect();
        let v = &values[0];
        let lower: String = v.to_lowercase().chars().take(3).collect();
        let mut oracle_hits = 0usize;
        // Each shape with the LIMITs worth an exhaustive oracle run: the walk expands
        // every string constant of both graphs per run, so the list is kept short.
        let all: &[Option<usize>] = &[None, Some(1)];
        let limited: &[Option<usize>] = &[Some(1), Some(7)];
        let shapes: Vec<(String, bool, &[Option<usize>])> = vec![
            (format!("MATCH (s:StringConstant)-[*1..3]-(n) WHERE s.value = '{v}' RETURN s.value AS value, labels(n) AS labels, n.class AS class, n.method AS method, n.name AS name, n.caller_class AS callerClass, n.line AS line LIMIT 100"), true, all),
            (format!("MATCH (s:StringConstant)-[*1..2]-(n) WHERE s.value CONTAINS '{v}' OR toLower(s.value) CONTAINS '{lower}' RETURN s.value AS value, labels(n) AS labels, n.name AS name LIMIT 150"), true, all),
            (format!("MATCH (s:StringConstant)-[*1..2]-(n) WHERE s.value CONTAINS '{}' OR s.value CONTAINS '{}' RETURN s.value AS value, n.id AS id LIMIT 100", values[1], values[2]), true, &[None]),
            (format!("MATCH p = (s:StringConstant)-[*1..2]-(n) WHERE s.value = '{v}' RETURN p"), true, &[None]),
            (format!("MATCH (s:StringConstant)-[r]->(n)-[t]-(m) WHERE s.value = '{v}' RETURN type(r), type(t), m.id ORDER BY m.id"), true, &[None]),
            (format!("MATCH (s:StringConstant)-[*1..2]-(n), (c:CallSite) WHERE s.value = '{v}' RETURN n.id, c.id LIMIT 7"), true, limited),
            (format!("MATCH (s:StringConstant)-[*1..2]-(n) WHERE s.value = '{v}' AND n.graphId = 'z' RETURN n.graphId, count(*)"), true, &[None]),
            (format!("MATCH (s:StringConstant)-[r:DATAFLOW*1..2]->(n:CallSite) WHERE s.value = '{v}' RETURN n.callee_name"), true, &[None]),
            ("MATCH (s:StringConstant)-[*1..2]-(n) WHERE s.value = 'no such string constant' RETURN n".to_string(), true, &[None]),
            // The value is read off the record: a map on the anchor stays generic.
            (format!("MATCH (s:StringConstant {{value: '{v}'}})-[*1..2]-(n) RETURN n.id"), false, &[Some(3)]),
        ];
        for (query, planned, limits) in shapes {
            let clauses = crate::parser::parse(&query).unwrap();
            let crate::ast::Clause::Match { patterns, .. } = &clauses[0] else {
                panic!("MATCH expected")
            };
            let where_clause = match clauses.get(1) {
                Some(crate::ast::Clause::Where(e)) => Some(e),
                _ => None,
            };
            assert_eq!(
                HopPlan::build(patterns, where_clause).map(|p| p.anchor),
                planned.then_some(0),
                "{query}"
            );
            let mut generic = clauses.clone();
            if let Some(crate::ast::Clause::Where(w)) = generic.get_mut(1) {
                *w = Expr::Comparison {
                    op: crate::ast::CmpOp::Eq,
                    left: Box::new(w.clone()),
                    right: Box::new(Expr::Literal(crate::ast::Literal::Bool(true))),
                };
                let crate::ast::Clause::Match { patterns, .. } = &generic[0] else {
                    panic!("MATCH expected")
                };
                let crate::ast::Clause::Where(w) = &generic[1] else {
                    panic!("WHERE expected")
                };
                assert!(
                    HopPlan::build(patterns, Some(w)).is_none(),
                    "oracle: {query}"
                );
            }
            for &limit in limits {
                let actual = ex.execute_clauses(&clauses, limit).unwrap();
                let expected = ex.execute_clauses(&generic, limit).unwrap();
                assert_eq!(
                    format!("{actual:?}"),
                    format!("{expected:?}"),
                    "rows, order, provenance and probe: {query} LIMIT {limit:?}"
                );
                if limit.is_none() {
                    oracle_hits += expected.rows.len();
                }
            }
        }
        assert!(oracle_hits > 10, "the fixture must exercise the expansion");
        // Cancellation reaches the anchored expansion.
        let cancel = crate::engine::CancelToken::new();
        cancel.cancel();
        let cancelled = Executor::new(ex.sources.clone(), true).with_cancel(cancel);
        assert!(matches!(
            cancelled.execute(
                &format!("MATCH (s:StringConstant)-[*1..3]-(n) WHERE s.value = '{v}' RETURN n"),
                None
            ),
            Err(crate::CypherError::Cancelled)
        ));
        let _ = Value::Null;
    }

    #[test]
    fn enters_any_pattern_at_a_scanned_first_node() {
        for query in [
            r#"MATCH (s:StringConstant)-[*1..3]-(n) WHERE s.value = "x" RETURN n"#,
            r#"MATCH (s:StringConstant)-[*1..2]-(n) WHERE s.value CONTAINS "a" OR toLower(s.value) CONTAINS "b" RETURN n LIMIT 150"#,
            r#"MATCH (s:StringConstant)-[r:DATAFLOW*]->(n) WHERE s.value = "x" AND n.line > 5 RETURN n"#,
            r#"MATCH p = (s:StringConstant)-[*1..3]-(n) WHERE s.value = "x" RETURN p"#,
            r#"MATCH (s:StringConstant)-[r]->(n)-[t]->(m) WHERE s.value = "x" RETURN m"#,
            r#"MATCH (s:StringConstant)-[r {kind: "k"}]->(n {line: 1}) WHERE s.value = "x" RETURN n"#,
            r#"MATCH (s:StringConstant)-[*1..3]-(n), (m:CallSite) WHERE s.value = "x" RETURN n, m"#,
            r#"MATCH (c)-[r:DATAFLOW*1..2]->(n) WHERE c.value = "x" RETURN n"#,
        ] {
            let p = plan(query).unwrap_or_else(|| panic!("{query}"));
            assert_eq!(p.anchor, 0, "{query}");
        }
    }
}
