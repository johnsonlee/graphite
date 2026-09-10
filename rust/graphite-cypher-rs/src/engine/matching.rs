//! Pattern matching (MATCH / OPTIONAL MATCH) with relationship-uniqueness semantics.

use super::pipeline::{add_provenance, Row};
use super::props::{is_method_label, label_tags};
use super::Executor;
use crate::ast::{Direction, Expr, NodePattern, Pattern, RelPattern};
use crate::eval::Evaluator;
use crate::semantics::cypher_equals;
use crate::value::{EdgeRef, MethodRef, NodeRef, PathValue, SourceIdx, Value};
use crate::CypherResult;
use graphite_storage::graph::EdgeFamily;
use graphite_storage::node::TAG_COUNT;
use graphite_storage::Edge;
use std::sync::Arc;

/// Emit callback: return Ok(false) to stop enumeration.
pub type Emit<'e> = dyn FnMut(Row) -> CypherResult<bool> + 'e;

#[derive(Default, Clone)]
pub struct MatchState {
    /// Edges already bound in this MATCH (relationship isomorphism).
    pub used: Vec<(SourceIdx, Edge)>,
    pub tracking: bool,
}

impl MatchState {
    #[inline]
    fn is_used(&self, s: SourceIdx, e: &Edge) -> bool {
        self.tracking && self.used.iter().any(|(us, ue)| *us == s && ue == e)
    }
}

/// Resolved node label constraint.
#[derive(Clone)]
pub enum NodeClass {
    /// Node tags admitted by the first label (all tags when unlabeled).
    Tags(Vec<u8>),
    /// `Method` virtual nodes.
    Method,
    /// Unknown label → no candidates.
    None,
}

pub fn resolve_node_class(labels: &[String]) -> NodeClass {
    if labels.iter().any(|l| is_method_label(l)) {
        return NodeClass::Method;
    }
    match labels.first() {
        None => NodeClass::Tags((0..TAG_COUNT as u8).collect()),
        Some(l) => match label_tags(l) {
            Some(t) => NodeClass::Tags(t),
            None => NodeClass::None,
        },
    }
}

pub fn has_unknown_label(patterns: &[Pattern]) -> bool {
    patterns.iter().any(|p| {
        p.nodes
            .iter()
            .any(|n| n.labels.iter().any(|l| !is_method_label(l) && label_tags(l).is_none()))
    })
}

/// Match a relationship pattern's type list against an edge (`matchesRelationshipType`).
pub fn rel_type_matches(types: &[String], e: &Edge) -> bool {
    if types.is_empty() {
        return true;
    }
    let fam = e.family();
    types.iter().any(|t| {
        let t = t.as_str();
        match fam {
            EdgeFamily::DataFlow => t.eq_ignore_ascii_case("DATAFLOW") || t.eq_ignore_ascii_case("DATA_FLOW"),
            EdgeFamily::Call => t.eq_ignore_ascii_case("CALL"),
            EdgeFamily::Type => t.eq_ignore_ascii_case("TYPE"),
            EdgeFamily::ControlFlow => {
                t.eq_ignore_ascii_case("CONTROL_FLOW") || t.eq_ignore_ascii_case("CONTROLFLOW")
            }
            EdgeFamily::Resource => {
                t.eq_ignore_ascii_case("RESOURCE") || t.eq_ignore_ascii_case(e.rel_type())
            }
        }
    })
}

/// True when the type list can match at least one edge family (`resolveEdgeType`).
pub fn rel_types_resolvable(types: &[String]) -> bool {
    if types.is_empty() {
        return true;
    }
    const KNOWN: [&str; 12] = [
        "DATAFLOW",
        "DATA_FLOW",
        "CALL",
        "TYPE",
        "CONTROL_FLOW",
        "CONTROLFLOW",
        "RESOURCE",
        "RESOURCE_OPEN",
        "RESOURCE_LOAD",
        "RESOURCE_BUNDLE_CANDIDATE",
        "RESOURCE_LOOKUP",
        "RESOURCE_KEYS",
    ];
    types.iter().any(|t| KNOWN.iter().any(|k| k.eq_ignore_ascii_case(t)))
}

pub struct Matcher<'a> {
    pub ex: &'a Executor,
    pub ev: &'a Evaluator<'a>,
}

impl<'a> Matcher<'a> {
    /// Enumerate matches of all `patterns` (comma-separated) extending `row`.
    pub fn match_patterns(
        &self,
        row: &Row,
        patterns: &[Pattern],
        emit: &mut Emit<'_>,
    ) -> CypherResult<bool> {
        let mut state = MatchState {
            used: Vec::new(),
            tracking: patterns.len() > 1
                || patterns
                    .iter()
                    .any(|p| p.rels.len() >= 2 || p.path_variable.is_some()),
        };
        self.match_from(row, patterns, 0, &mut state, emit)
    }

    fn match_from(
        &self,
        row: &Row,
        patterns: &[Pattern],
        idx: usize,
        state: &mut MatchState,
        emit: &mut Emit<'_>,
    ) -> CypherResult<bool> {
        if idx == patterns.len() {
            return emit(row.clone());
        }
        let pattern = &patterns[idx];
        let used_before = state.used.len();
        let mut inner = |r: Row, st: &mut MatchState| -> CypherResult<bool> {
            self.match_from(&r, patterns, idx + 1, st, emit)
        };
        let cont = self.match_pattern(row, pattern, state, &mut inner)?;
        state.used.truncate(used_before);
        Ok(cont)
    }

    /// Match a single pattern; `next` receives each complete binding with the state.
    pub fn match_pattern(
        &self,
        row: &Row,
        pattern: &Pattern,
        state: &mut MatchState,
        next: &mut dyn FnMut(Row, &mut MatchState) -> CypherResult<bool>,
    ) -> CypherResult<bool> {
        let first = &pattern.nodes[0];
        let class = resolve_node_class(&first.labels);
        // Candidate enumeration for the first node.
        let mut on_candidate = |cand: Value, this: &Self| -> CypherResult<bool> {
            if !this.node_matches(&cand, first, row)? {
                return Ok(true);
            }
            let mut r = row.clone();
            if let Some(v) = &first.variable {
                r.insert(v.clone(), cand.clone());
                add_provenance(&mut r, this.ex, &cand);
            }
            let mut trail_nodes: Vec<Value> = Vec::new();
            let mut trail_edges: Vec<EdgeRef> = Vec::new();
            if pattern.path_variable.is_some() {
                trail_nodes.push(cand.clone());
            }
            this.extend(&r, pattern, 0, &cand, state, &mut trail_nodes, &mut trail_edges, next)
        };
        if let Some(v) = &first.variable {
            if let Some(existing) = row.get(v) {
                let ok = match (&class, existing) {
                    (NodeClass::Method, Value::Method(_)) => true,
                    (NodeClass::Tags(tags), Value::Node(n)) => self
                        .ex
                        .graph(n.source)
                        .node_tag(n.id)
                        .map(|t| tags.contains(&t))
                        .unwrap_or(false),
                    _ => false,
                };
                if !ok {
                    return Ok(true);
                }
                return on_candidate(existing.clone(), self);
            }
        }
        match class {
            NodeClass::None => Ok(true),
            NodeClass::Method => {
                for (si, src) in self.ex.sources.iter().enumerate() {
                    for i in 0..src.graph.method_count() {
                        self.ex.tick()?;
                        let v = Value::Method(MethodRef {
                            source: si as SourceIdx,
                            index: i as u32,
                        });
                        if !on_candidate(v, self)? {
                            return Ok(false);
                        }
                    }
                }
                Ok(true)
            }
            NodeClass::Tags(tags) => {
                for (si, src) in self.ex.sources.iter().enumerate() {
                    for &tag in &tags {
                        for &id in src.graph.ids_by_tag(tag) {
                            self.ex.tick()?;
                            let v = Value::Node(NodeRef {
                                source: si as SourceIdx,
                                id,
                            });
                            if !on_candidate(v, self)? {
                                return Ok(false);
                            }
                        }
                    }
                }
                Ok(true)
            }
        }
    }

    /// Check labels beyond the first and inline properties (`matchesNodeConstraints`).
    pub fn node_matches(&self, cand: &Value, np: &NodePattern, row: &Row) -> CypherResult<bool> {
        match cand {
            Value::Method(_) => {
                if np.labels.iter().any(|l| !is_method_label(l)) {
                    return Ok(false);
                }
            }
            Value::Node(n) => {
                if np.labels.len() > 1 {
                    let tag = match self.ex.graph(n.source).node_tag(n.id) {
                        Some(t) => t,
                        None => return Ok(false),
                    };
                    let labels = super::props::node_labels(tag);
                    for l in &np.labels[1..] {
                        let by_class = label_tags(l).map(|t| t.contains(&tag)).unwrap_or(false);
                        let by_name = labels.iter().any(|x| x.eq_ignore_ascii_case(l));
                        if !by_class && !by_name {
                            return Ok(false);
                        }
                    }
                }
            }
            _ => return Ok(false),
        }
        for (k, e) in &np.properties {
            let expected = self.ev.eval(e, row)?;
            let actual = self.value_property(cand, k);
            if cypher_equals(&actual, &expected) != Some(true) {
                return Ok(false);
            }
        }
        Ok(true)
    }

    fn value_property(&self, v: &Value, key: &str) -> Value {
        use crate::context::GraphContext;
        match v {
            Value::Node(n) => self.ex.node_property(*n, key),
            Value::Method(m) => self.ex.method_property(*m, key),
            _ => Value::Null,
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn extend(
        &self,
        row: &Row,
        pattern: &Pattern,
        hop: usize,
        current: &Value,
        state: &mut MatchState,
        trail_nodes: &mut Vec<Value>,
        trail_edges: &mut Vec<EdgeRef>,
        next: &mut dyn FnMut(Row, &mut MatchState) -> CypherResult<bool>,
    ) -> CypherResult<bool> {
        if hop == pattern.rels.len() {
            let mut r = row.clone();
            if let Some(pv) = &pattern.path_variable {
                r.insert(pv.clone(), self.build_path(trail_nodes, trail_edges));
            }
            return next(r, state);
        }
        let rel = &pattern.rels[hop];
        let target = &pattern.nodes[hop + 1];
        let cur = match current {
            Value::Node(n) => *n,
            _ => return Ok(true), // Method nodes have no edges.
        };
        if !rel_types_resolvable(&rel.types) {
            return Ok(true);
        }
        let target_class = resolve_node_class(&target.labels);
        if matches!(target_class, NodeClass::None | NodeClass::Method) {
            return Ok(true);
        }
        let target_tags = match &target_class {
            NodeClass::Tags(t) => t.clone(),
            _ => unreachable!(),
        };
        if rel.variable_length {
            let min = rel.min_hops.unwrap_or(1) as usize;
            let max = rel.max_hops.map(|m| m as usize);
            let mut path: Vec<EdgeRef> = Vec::new();
            return self.extend_var(
                row, pattern, hop, cur, rel, target, &target_tags, min, max, &mut path, state, trail_nodes,
                trail_edges, next,
            );
        }
        let edges = self.edges_of(cur, rel.direction);
        for e in edges {
            self.ex.tick()?;
            if !self.edge_ok(cur.source, &e, rel, row, state)? {
                continue;
            }
            let tgt_id = if e.from == cur.id && rel.direction != Direction::Incoming { e.to } else { e.from };
            let tgt = NodeRef { source: cur.source, id: tgt_id };
            if !self.target_ok(tgt, &target_tags, target, row)? {
                continue;
            }
            let er = EdgeRef { source: cur.source, edge: e };
            let mut r = row.clone();
            if let Some(v) = &rel.variable {
                r.insert(v.clone(), Value::Rel(er));
                add_provenance(&mut r, self.ex, &Value::Rel(er));
            }
            let tv = Value::Node(tgt);
            if let Some(v) = &target.variable {
                r.insert(v.clone(), tv.clone());
                add_provenance(&mut r, self.ex, &tv);
            }
            let pushed = state.tracking;
            if pushed {
                state.used.push((cur.source, e));
            }
            if pattern.path_variable.is_some() {
                trail_edges.push(er);
                trail_nodes.push(tv.clone());
            }
            let cont = self.extend(&r, pattern, hop + 1, &tv, state, trail_nodes, trail_edges, next)?;
            if pattern.path_variable.is_some() {
                trail_edges.pop();
                trail_nodes.pop();
            }
            if pushed {
                state.used.pop();
            }
            if !cont {
                return Ok(false);
            }
        }
        Ok(true)
    }

    #[allow(clippy::too_many_arguments)]
    fn extend_var(
        &self,
        row: &Row,
        pattern: &Pattern,
        hop: usize,
        cur: NodeRef,
        rel: &RelPattern,
        target: &NodePattern,
        target_tags: &[u8],
        min: usize,
        max: Option<usize>,
        path: &mut Vec<EdgeRef>,
        state: &mut MatchState,
        trail_nodes: &mut Vec<Value>,
        trail_edges: &mut Vec<EdgeRef>,
        next: &mut dyn FnMut(Row, &mut MatchState) -> CypherResult<bool>,
    ) -> CypherResult<bool> {
        // Zero-length match.
        if path.len() >= min && self.target_ok(cur, target_tags, target, row)? {
            let mut r = row.clone();
            if let Some(v) = &rel.variable {
                r.insert(
                    v.clone(),
                    Value::list(path.iter().map(|e| Value::Rel(*e)).collect()),
                );
            }
            let tv = Value::Node(cur);
            if let Some(v) = &target.variable {
                r.insert(v.clone(), tv.clone());
                add_provenance(&mut r, self.ex, &tv);
            }
            let pushed = state.tracking;
            let before = state.used.len();
            if pushed {
                for e in path.iter() {
                    state.used.push((e.source, e.edge));
                }
            }
            let cont = self.extend(&r, pattern, hop + 1, &tv, state, trail_nodes, trail_edges, next)?;
            state.used.truncate(before);
            if !cont {
                return Ok(false);
            }
        }
        if let Some(m) = max {
            if path.len() >= m {
                return Ok(true);
            }
        }
        let edges = self.edges_of(cur, rel.direction);
        for e in edges {
            self.ex.tick()?;
            if path.iter().any(|p| p.edge == e) {
                continue;
            }
            if !self.edge_ok(cur.source, &e, rel, row, state)? {
                continue;
            }
            let tgt_id = if e.from == cur.id && rel.direction != Direction::Incoming { e.to } else { e.from };
            let tgt = NodeRef { source: cur.source, id: tgt_id };
            let er = EdgeRef { source: cur.source, edge: e };
            path.push(er);
            if pattern.path_variable.is_some() {
                trail_edges.push(er);
                trail_nodes.push(Value::Node(tgt));
            }
            let cont = self.extend_var(
                row, pattern, hop, tgt, rel, target, target_tags, min, max, path, state, trail_nodes,
                trail_edges, next,
            )?;
            if pattern.path_variable.is_some() {
                trail_edges.pop();
                trail_nodes.pop();
            }
            path.pop();
            if !cont {
                return Ok(false);
            }
        }
        Ok(true)
    }

    /// Edges of `n` in a direction; BOTH = outgoing then incoming (skipping self-loops on the incoming side).
    pub fn edges_of(&self, n: NodeRef, dir: Direction) -> Vec<Edge> {
        let g = self.ex.graph(n.source);
        match dir {
            Direction::Outgoing => g.outgoing(n.id).collect(),
            Direction::Incoming => g.incoming(n.id).collect(),
            Direction::Both => {
                let mut v: Vec<Edge> = g.outgoing(n.id).collect();
                v.extend(g.incoming(n.id).filter(|e| e.from != e.to));
                v
            }
        }
    }

    fn edge_ok(
        &self,
        source: SourceIdx,
        e: &Edge,
        rel: &RelPattern,
        row: &Row,
        state: &MatchState,
    ) -> CypherResult<bool> {
        if !rel_type_matches(&rel.types, e) {
            return Ok(false);
        }
        if state.is_used(source, e) {
            return Ok(false);
        }
        if let Some(v) = &rel.variable {
            if let Some(existing) = row.get(v) {
                let same = match existing {
                    Value::Rel(er) => er.source == source && er.edge == *e,
                    Value::List(l) => l.iter().any(|x| matches!(x, Value::Rel(er) if er.source == source && er.edge == *e)),
                    _ => false,
                };
                if !same {
                    return Ok(false);
                }
            }
        }
        for (k, expr) in &rel.properties {
            let expected = self.ev.eval(expr, row)?;
            let er = EdgeRef { source, edge: *e };
            let actual = super::props::rel_property(self.ex.graph(source), er, k, None);
            if !matches!(k.as_str(), "kind" | "virtual" | "dynamic") {
                return Ok(false);
            }
            if cypher_equals(&actual, &expected) != Some(true) {
                return Ok(false);
            }
        }
        Ok(true)
    }

    fn target_ok(&self, tgt: NodeRef, tags: &[u8], np: &NodePattern, row: &Row) -> CypherResult<bool> {
        let tag = match self.ex.graph(tgt.source).node_tag(tgt.id) {
            Some(t) => t,
            None => return Ok(false),
        };
        if !tags.contains(&tag) {
            return Ok(false);
        }
        let tv = Value::Node(tgt);
        if let Some(v) = &np.variable {
            if let Some(existing) = row.get(v) {
                if !matches!(existing, Value::Node(n) if *n == tgt) {
                    return Ok(false);
                }
            }
        }
        self.node_matches(&tv, np, row)
    }

    fn build_path(&self, nodes: &[Value], edges: &[EdgeRef]) -> Value {
        let source = edges
            .first()
            .map(|e| e.source)
            .or_else(|| match nodes.first() {
                Some(Value::Node(n)) => Some(n.source),
                _ => None,
            })
            .unwrap_or(0);
        let node_ids = nodes
            .iter()
            .filter_map(|v| match v {
                Value::Node(n) => Some(n.id),
                _ => None,
            })
            .collect();
        Value::Path(Arc::new(PathValue {
            source,
            nodes: node_ids,
            edges: edges.iter().map(|e| e.edge).collect(),
        }))
    }
}

#[allow(dead_code)]
fn _expr_marker(_: &Expr) {}
