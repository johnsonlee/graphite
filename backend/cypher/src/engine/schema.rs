//! Schema-exploration fast paths: histograms and distinct sets over what a node
//! *is* rather than what it holds.
//!
//! An agent meeting a graph for the first time asks for its shape: which labels
//! exist and how many nodes carry each, which relationship types connect which
//! labels, which property keys the nodes expose, how the nodes split across graphs.
//! Every one of those answers is a function of a node's type tag, an edge's label
//! byte and the source graph -- never of a record's contents -- yet the general
//! pipeline answered them by building a row per node or per edge, evaluating the
//! projection on each, and grouping the result: a million rows to learn that
//! there are thirteen label sets, and a timeout over a fleet.
//!
//! Here the same queries are answered from the type index (one count per tag per
//! graph), a sweep of the CSR label bytes for relationship shapes, and one decoded
//! node per tag for `keys()`, since a type's key set is fixed -- except for
//! annotations, whose values add keys of their own and which are decoded one by one.
//!
//! The shape is recognised exactly and everything else falls through to the general
//! pipeline, which stays the single source of truth for semantics. The rows this
//! produces are the rows the pipeline would produce: the same values, the same
//! group order (first-seen, by the id of the node or edge that opened the group), the
//! same provenance across graphs, and the same `ORDER BY` and `LIMIT` handling.

use super::matching::{rel_type_matches, resolve_node_class, NodeClass};
use super::pipeline::{add_provenance_id, QueryResult, Row};
use super::props::node_labels;
use super::Executor;
use crate::ast::{Clause, Direction, Expr, Literal, Pattern};
use crate::context::GraphContext;
use crate::ordering::compare_order_values;
use crate::render::to_cypher_string;
use crate::semantics::{value_key, Key};
use crate::value::{NodeRef, SourceIdx, Value};
use crate::CypherResult;
use graphite_storage::node::{TAG_ANNOTATION_NODE, TAG_COUNT};
use graphite_storage::Edge;
use indexmap::IndexMap;
use rayon::prelude::*;
use std::cmp::Ordering;

/// Which end of the pattern a term reads.
#[derive(Clone, Copy, PartialEq, Eq)]
enum End {
    /// The single node, or the hop's first node.
    A,
    /// The hop's second node.
    B,
}

/// A projected expression the schema answers without visiting records.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Term {
    /// `labels(x)`.
    Labels(End),
    /// `x.graphId`, cross-graph mode only.
    GraphId(End),
    /// `type(r)`.
    RelType,
    /// The variable bound by `UNWIND keys(n) AS k`.
    Key,
    /// `count(*)`, or `count(v)` of a bound variable, which is never null.
    Count,
}

enum Shape {
    Node {
        tags: Vec<u8>,
    },
    Hop {
        tags_a: Vec<u8>,
        tags_b: Vec<u8>,
        types: Vec<String>,
        direction: Direction,
    },
}

/// A non-empty slot of a graph's edge table: (source tag, label byte, target tag,
/// count, earliest (node, position)).
type EdgeSlot = (u8, u8, u8, i64, (u32, u32));

struct Query {
    shape: Shape,
    unwind_keys: bool,
    /// One per RETURN item, with its output column.
    terms: Vec<(Term, String)>,
    /// `ORDER BY` as (column index, descending).
    order: Vec<(usize, bool)>,
    limit: Option<usize>,
}

/// A group in the making: its projected values, its count, the position of the
/// node or edge that opened it, and the graphs that contributed.
struct Group {
    values: Vec<Value>,
    count: i64,
    first: (SourceIdx, u32, u32),
    sources: Vec<SourceIdx>,
}

/// One schema-level observation: `weight` nodes (or edges) of one type in one graph,
/// first met at `first`.
struct Fact<'a> {
    source: SourceIdx,
    tag_a: u8,
    tag_b: Option<u8>,
    edge: Option<Edge>,
    key: Option<&'a str>,
    weight: i64,
    first: (u32, u32),
}

/// Answer a schema-exploration query from the type index and edge labels, or `None`
/// when the clauses are not exactly such a query.
pub fn schema_histogram(ex: &Executor, clauses: &[Clause]) -> CypherResult<Option<QueryResult>> {
    let Some(q) = recognise(ex, clauses) else {
        return Ok(None);
    };
    let mut groups: IndexMap<Vec<Key>, Group> = IndexMap::new();
    if let Shape::Hop {
        tags_a,
        tags_b,
        types,
        direction,
    } = &q.shape
    {
        // Every source is swept independently, on the pool, and its facts are then
        // absorbed in source order, so the group order is the sequential one.
        let swept: Vec<CypherResult<Vec<EdgeSlot>>> = ex
            .sources
            .par_iter()
            .map(|s| sweep_edges(ex, &s.graph, tags_a, tags_b, types, *direction))
            .collect();
        for (si, entries) in swept.into_iter().enumerate() {
            let v2 = ex.sources[si].graph.node_version < 3;
            for (tag_a, label, tag_b, weight, first) in entries? {
                let f = Fact {
                    source: si as SourceIdx,
                    tag_a,
                    tag_b: Some(tag_b),
                    edge: Some(Edge {
                        from: 0,
                        to: 0,
                        label,
                        v2,
                    }),
                    key: None,
                    weight,
                    first,
                };
                absorb(ex, &q, &mut groups, f);
            }
        }
    }
    for (si, s) in ex.sources.iter().enumerate() {
        let si = si as SourceIdx;
        let graph = &s.graph;
        match &q.shape {
            Shape::Node { tags } => {
                for &tag in tags {
                    let ids = graph.ids_by_tag(tag);
                    let Some(&first) = ids.first() else {
                        continue;
                    };
                    if !q.unwind_keys {
                        let f = node_fact(si, tag, None, ids.len() as i64, (first, 0));
                        absorb(ex, &q, &mut groups, f);
                    } else if tag != TAG_ANNOTATION_NODE {
                        // A type's key set is fixed: read it off the first node and
                        // weight it by the type's population.
                        let node = NodeRef {
                            source: si,
                            id: first,
                        };
                        let keys = ex.node_properties(node);
                        for (pos, key) in keys.keys().enumerate() {
                            let f = node_fact(
                                si,
                                tag,
                                Some(key),
                                ids.len() as i64,
                                (first, pos as u32),
                            );
                            absorb(ex, &q, &mut groups, f);
                        }
                    } else {
                        // An annotation's values add keys of their own: one node at a
                        // time, each key weighing one.
                        for &id in ids {
                            ex.tick()?;
                            let keys = ex.node_properties(NodeRef { source: si, id });
                            for (pos, key) in keys.keys().enumerate() {
                                let f = node_fact(si, tag, Some(key), 1, (id, pos as u32));
                                absorb(ex, &q, &mut groups, f);
                            }
                        }
                    }
                }
            }
            Shape::Hop { .. } => {}
        }
    }

    // First-seen order across the whole scan, as the pipeline's group-by keeps it.
    let mut groups: Vec<Group> = groups.into_values().collect();
    groups.sort_by_key(|g| g.first);
    if !q.order.is_empty() {
        groups.sort_by(|a, b| {
            for &(col, descending) in &q.order {
                let av = column_value(a, &q, col);
                let bv = column_value(b, &q, col);
                let c = compare_order_values(&av, &bv);
                let c = if descending { c.reverse() } else { c };
                if c != Ordering::Equal {
                    return c;
                }
            }
            Ordering::Equal
        });
    }
    if let Some(l) = q.limit {
        groups.truncate(l);
    }

    let columns: Vec<String> = q.terms.iter().map(|(_, c)| c.clone()).collect();
    let rows: Vec<Row> = groups
        .into_iter()
        .map(|g| {
            let mut row = Row::with_capacity(columns.len() + 1);
            for (i, (term, column)) in q.terms.iter().enumerate() {
                let v = match term {
                    Term::Count => Value::Int(g.count),
                    _ => column_value(&g, &q, i),
                };
                row.insert(column.clone(), v);
            }
            if ex.cross {
                for s in &g.sources {
                    add_provenance_id(&mut row, ex.sources[*s as usize].id.clone());
                }
            }
            row
        })
        .collect();
    Ok(Some(QueryResult {
        columns,
        rows,
        compact: None,
        more: false,
    }))
}

fn node_fact(
    source: SourceIdx,
    tag: u8,
    key: Option<&str>,
    weight: i64,
    first: (u32, u32),
) -> Fact<'_> {
    Fact {
        source,
        tag_a: tag,
        tag_b: None,
        edge: None,
        key,
        weight,
        first,
    }
}

/// Count one graph's edges by (source tag, label byte, target tag) in a flat table
/// of 16 x 256 x 16 slots, keeping each slot's earliest (node, position) so groups
/// can be ordered as the pipeline meets them. Whether a label byte matches the
/// pattern's types is decided once per byte; a target's tag is read without
/// decoding its record. Returns the non-empty slots in table order.
fn sweep_edges(
    ex: &Executor,
    graph: &graphite_storage::Graph,
    tags_a: &[u8],
    tags_b: &[u8],
    types: &[String],
    direction: Direction,
) -> CypherResult<Vec<EdgeSlot>> {
    let csr = match direction {
        Direction::Outgoing => &graph.forward,
        _ => &graph.backward,
    };
    let v2 = graph.node_version < 3;
    let mut type_ok: [u8; 256] = [0; 256];
    let mut counts: Vec<(i64, (u32, u32))> =
        vec![(0, (u32::MAX, u32::MAX)); TAG_COUNT * 256 * TAG_COUNT];
    // Cancellation is polled on a local counter: the executor's shared one is an
    // atomic that every thread would bounce between cores once per node.
    let mut polled = 0u32;
    for &tag_a in tags_a {
        for &id in graph.ids_by_tag(tag_a) {
            polled = polled.wrapping_add(1);
            if polled & 1023 == 0 {
                ex.cancel.check()?;
            }
            let (targets, labels) = csr.neighbors(id as usize);
            for (pos, (&other, &label)) in targets.iter().zip(labels).enumerate() {
                let ok = &mut type_ok[label as usize];
                if *ok == 0 {
                    let probe = Edge {
                        from: id,
                        to: other,
                        label,
                        v2,
                    };
                    *ok = if rel_type_matches(types, &probe) {
                        1
                    } else {
                        2
                    };
                }
                if *ok == 2 {
                    continue;
                }
                let Some(tag_b) = graph.node_tag(other) else {
                    continue;
                };
                if !tags_b.contains(&tag_b) {
                    continue;
                }
                let slot = &mut counts
                    [(tag_a as usize * 256 + label as usize) * TAG_COUNT + tag_b as usize];
                slot.0 += 1;
                let first = (id, pos as u32);
                if first < slot.1 {
                    slot.1 = first;
                }
            }
        }
    }
    Ok(counts
        .iter()
        .enumerate()
        .filter(|(_, (weight, _))| *weight > 0)
        .map(|(i, &(weight, first))| {
            let tag_b = (i % TAG_COUNT) as u8;
            let label = ((i / TAG_COUNT) % 256) as u8;
            let tag_a = (i / TAG_COUNT / 256) as u8;
            (tag_a, label, tag_b, weight, first)
        })
        .collect())
}

/// The value of output column `col` for a group: its projected value, or its count.
fn column_value(g: &Group, q: &Query, col: usize) -> Value {
    match q.terms[col].0 {
        Term::Count => Value::Int(g.count),
        _ => {
            // Projected values are stored in term order, skipping the counts.
            let at = q.terms[..col]
                .iter()
                .filter(|(t, _)| *t != Term::Count)
                .count();
            g.values[at].clone()
        }
    }
}

/// Fold one fact into its group.
fn absorb(ex: &Executor, q: &Query, groups: &mut IndexMap<Vec<Key>, Group>, f: Fact<'_>) {
    let mut values = Vec::with_capacity(q.terms.len());
    for (term, _) in &q.terms {
        let v = match term {
            Term::Count => continue,
            Term::Labels(End::A) => labels_value(f.tag_a),
            Term::Labels(End::B) => labels_value(f.tag_b.unwrap_or(f.tag_a)),
            Term::GraphId(_) => Value::str(ex.sources[f.source as usize].id.clone()),
            Term::RelType => Value::str(f.edge.map(|e| e.rel_type()).unwrap_or("")),
            Term::Key => Value::str(f.key.unwrap_or("")),
        };
        values.push(v);
    }
    let key: Vec<Key> = values.iter().map(value_key).collect();
    let first = (f.source, f.first.0, f.first.1);
    match groups.get_mut(&key) {
        Some(g) => {
            g.count += f.weight;
            if first < g.first {
                g.first = first;
            }
            if g.sources.last() != Some(&f.source) {
                g.sources.push(f.source);
            }
        }
        None => {
            groups.insert(
                key,
                Group {
                    values,
                    count: f.weight,
                    first,
                    sources: vec![f.source],
                },
            );
        }
    }
}

fn labels_value(tag: u8) -> Value {
    Value::list(node_labels(tag).into_iter().map(Value::str).collect())
}

/// Parse the clauses into a schema query, or `None` when any part of them needs the
/// general pipeline.
fn recognise(ex: &Executor, clauses: &[Clause]) -> Option<Query> {
    let (patterns, i) = match clauses.first() {
        Some(Clause::Match {
            patterns,
            optional: false,
            where_clause: None,
        }) => (patterns, 1),
        _ => return None,
    };
    if patterns.len() != 1 {
        return None;
    }
    let p: &Pattern = &patterns[0];
    if p.path_variable.is_some() || p.nodes.iter().any(|n| !n.properties.is_empty()) {
        return None;
    }
    let tags = |labels: &[String]| match resolve_node_class(labels) {
        NodeClass::Tags(t) => Some(t),
        _ => None,
    };
    let (shape, var_a, var_b, var_r) = match (p.nodes.len(), p.rels.len()) {
        (1, 0) => (
            Shape::Node {
                tags: tags(&p.nodes[0].labels)?,
            },
            p.nodes[0].variable.clone(),
            None,
            None,
        ),
        (2, 1) => {
            let rel = &p.rels[0];
            if rel.variable_length || !rel.properties.is_empty() || rel.direction == Direction::Both
            {
                return None;
            }
            if p.nodes[0].variable.is_some() && p.nodes[0].variable == p.nodes[1].variable {
                return None;
            }
            (
                Shape::Hop {
                    tags_a: tags(&p.nodes[0].labels)?,
                    tags_b: tags(&p.nodes[1].labels)?,
                    types: rel.types.clone(),
                    direction: rel.direction,
                },
                p.nodes[0].variable.clone(),
                p.nodes[1].variable.clone(),
                rel.variable.clone(),
            )
        }
        _ => return None,
    };

    let mut i = i;
    let mut key_var: Option<String> = None;
    if let Some(Clause::Unwind { expr, variable }) = clauses.get(i) {
        let var = var_a.as_deref()?;
        if !matches!(&shape, Shape::Node { .. }) || !is_call(expr, "keys", var) {
            return None;
        }
        key_var = Some(variable.clone());
        i += 1;
    }
    let (distinct, items) = match clauses.get(i) {
        Some(Clause::Return {
            distinct,
            items: Some(items),
        }) => (*distinct, items),
        _ => return None,
    };
    i += 1;

    let end_of = |v: &str| -> Option<End> {
        if var_a.as_deref() == Some(v) {
            Some(End::A)
        } else if var_b.as_deref() == Some(v) {
            Some(End::B)
        } else {
            None
        }
    };
    let bound = |v: &str| {
        end_of(v).is_some() || var_r.as_deref() == Some(v) || key_var.as_deref() == Some(v)
    };
    let mut terms = Vec::with_capacity(items.len());
    for it in items {
        let term = term_of(
            &it.expr,
            ex.cross,
            &end_of,
            &bound,
            var_r.as_deref(),
            key_var.as_deref(),
        )?;
        let column = it
            .alias
            .clone()
            .unwrap_or_else(|| to_cypher_string(&it.expr));
        terms.push((term, column));
    }
    let counted = terms.iter().any(|(t, _)| *t == Term::Count);
    if !counted && !distinct {
        // One row per node or edge: not a schema question.
        return None;
    }

    let mut order = Vec::new();
    if let Some(Clause::OrderBy(items)) = clauses.get(i) {
        for o in items {
            let Expr::Variable(name) = &o.expr else {
                return None;
            };
            let col = terms.iter().position(|(_, c)| c == name)?;
            order.push((col, o.descending));
        }
        i += 1;
    }
    let mut limit = None;
    if let Some(Clause::Limit(Expr::Literal(Literal::Int(n)))) = clauses.get(i) {
        if *n < 0 {
            return None;
        }
        limit = Some(*n as usize);
        i += 1;
    }
    if i != clauses.len() {
        return None;
    }
    Some(Query {
        shape,
        unwind_keys: key_var.is_some(),
        terms,
        order,
        limit,
    })
}

/// `<name>(<var>)`, case-insensitively.
fn is_call(e: &Expr, name: &str, var: &str) -> bool {
    matches!(e, Expr::FunctionCall { name: n, distinct: false, args }
        if n.eq_ignore_ascii_case(name)
            && matches!(args.as_slice(), [Expr::Variable(v)] if v == var))
}

fn term_of(
    e: &Expr,
    cross: bool,
    end_of: &dyn Fn(&str) -> Option<End>,
    bound: &dyn Fn(&str) -> bool,
    var_r: Option<&str>,
    key_var: Option<&str>,
) -> Option<Term> {
    match e {
        Expr::CountStar => Some(Term::Count),
        Expr::Variable(v) if key_var == Some(v.as_str()) => Some(Term::Key),
        Expr::Property { expr, key } if key == "graphId" && cross => match expr.as_ref() {
            Expr::Variable(v) => end_of(v).map(Term::GraphId),
            _ => None,
        },
        Expr::FunctionCall {
            name,
            distinct: false,
            args,
        } => {
            let [Expr::Variable(v)] = args.as_slice() else {
                return None;
            };
            if name.eq_ignore_ascii_case("labels") {
                end_of(v).map(Term::Labels)
            } else if name.eq_ignore_ascii_case("type") {
                (var_r == Some(v.as_str())).then_some(Term::RelType)
            } else if name.eq_ignore_ascii_case("count") {
                bound(v).then_some(Term::Count)
            } else {
                None
            }
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::super::{Executor, QueryResult, Source};
    use super::*;
    use crate::parser::parse;
    use std::sync::Arc;

    fn answered(ex: &Executor, q: &str) -> Option<QueryResult> {
        schema_histogram(ex, &parse(q).unwrap()).unwrap()
    }

    #[test]
    fn schema_shapes_are_recognised_and_answered_without_a_graph() {
        let ex = Executor::new(vec![], true);
        for q in [
            "MATCH (n) RETURN labels(n) AS labels, count(*) AS c ORDER BY c DESC LIMIT 40",
            "MATCH (n) RETURN labels(n), count(*)",
            "MATCH (n) RETURN DISTINCT labels(n) AS labels",
            "MATCH (n:Constant) RETURN labels(n) AS l, count(n) AS c ORDER BY c ASC, l DESC",
            "MATCH ()-[r]->() RETURN type(r) AS t, count(*) AS c",
            "MATCH ()-[r]->() RETURN DISTINCT type(r)",
            "MATCH ()-[r:DATAFLOW]->() RETURN count(r) AS c",
            "MATCH (a)-[r]->(b) RETURN labels(a) AS a, type(r) AS t, labels(b) AS b, count(*) AS c LIMIT 5",
            "MATCH (a)<-[r]-(b) RETURN labels(a), labels(b), count(r)",
            "MATCH (n) UNWIND keys(n) AS k RETURN k, count(*) AS c ORDER BY c DESC LIMIT 50",
            "MATCH (n) UNWIND keys(n) AS k RETURN DISTINCT k",
            "MATCH (n) UNWIND keys(n) AS k RETURN labels(n) AS l, k, count(k) AS c",
            "MATCH (n) RETURN n.graphId AS g, count(*) AS c",
            "MATCH (n) RETURN DISTINCT n.graphId AS g",
            "MATCH (a)-[r]->(b) RETURN a.graphId AS g, type(r) AS t, count(*) AS c",
        ] {
            let r = answered(&ex, q).unwrap_or_else(|| panic!("{q}: not recognised"));
            assert!(r.rows.is_empty(), "{q}: no sources, no rows");
        }
        let r = answered(&ex, "MATCH (n) RETURN labels(n), count(*) AS c").unwrap();
        assert_eq!(r.columns, ["labels(n)", "c"]);
    }

    #[test]
    fn anything_the_schema_cannot_answer_falls_through() {
        let cross = Executor::new(vec![], true);
        let single = Executor::new(vec![], false);
        for q in [
            // No aggregate and no DISTINCT: one row per node.
            "MATCH (n) RETURN labels(n) AS l",
            // A WHERE, a property map, an OPTIONAL MATCH, a path variable.
            "MATCH (n) WHERE n.line > 1 RETURN labels(n), count(*)",
            "MATCH (n {line: 1}) RETURN labels(n), count(*)",
            "OPTIONAL MATCH (n) RETURN labels(n), count(*)",
            "MATCH p = (a)-[r]->(b) RETURN type(r), count(*)",
            // Undirected, variable-length, typed-by-property relationships.
            "MATCH (a)-[r]-(b) RETURN type(r), count(*)",
            "MATCH (a)-[r*1..2]->(b) RETURN labels(b), count(*)",
            "MATCH (a)-[r {kind: 'ASSIGN'}]->(b) RETURN type(r), count(*)",
            // Anything read off a record, an unknown label, a Method, keys of a hop.
            "MATCH (n) RETURN n.callee_class, count(*)",
            "MATCH (n:NoSuchLabel) RETURN labels(n), count(*)",
            "MATCH (m:Method) RETURN labels(m), count(*)",
            "MATCH (a)-[r]->(b) UNWIND keys(a) AS k RETURN k, count(*)",
            "MATCH (n) UNWIND keys(n) AS k RETURN keys(n), count(*)",
            // ORDER BY anything but an output column, RETURN *, DISTINCT count.
            "MATCH (n) RETURN labels(n) AS l, count(*) AS c ORDER BY count(*) DESC",
            "MATCH (n) RETURN labels(n) AS l, count(*) AS c ORDER BY n.id",
            "MATCH (n) RETURN *",
            "MATCH (n) RETURN labels(n), count(DISTINCT n)",
            "MATCH (n) RETURN labels(n), count(*) LIMIT -1",
            "MATCH (n) RETURN labels(n), count(*) SKIP 1",
            "MATCH (n) WITH n RETURN labels(n), count(*)",
        ] {
            assert!(answered(&cross, q).is_none(), "{q}");
        }
        // graphId is a property only across graphs.
        assert!(answered(&single, "MATCH (n) RETURN n.graphId AS g, count(*) AS c").is_none());
        assert!(answered(&cross, "MATCH (n) RETURN n.graphId AS g, count(*) AS c").is_some());
    }

    /// Against a real graph (`GRAPHITE_INDEX_FIXTURE`, the core jar in CI), as two
    /// cross-graph sources and as one plain source: every schema shape produces the
    /// rows, columns and provenance the general pipeline produces, which an
    /// `UNWIND [1] AS one` after the MATCH forces.
    #[test]
    fn a_schema_histogram_answers_as_the_general_pipeline_does() {
        let Some(dir) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
            eprintln!("GRAPHITE_INDEX_FIXTURE unset; skipping");
            return;
        };
        let graph =
            Arc::new(graphite_storage::graph::Graph::load(std::path::Path::new(&dir)).unwrap());
        let source = |id: &str| Source {
            id: Arc::from(id),
            graph: graph.clone(),
        };
        let render = |r: &QueryResult| -> Vec<String> {
            let mut out = vec![format!("columns={:?}", r.columns)];
            for row in &r.rows {
                let cells: Vec<String> = r
                    .columns
                    .iter()
                    .map(|c| format!("{:?}", row.get(c)))
                    .collect();
                out.push(format!(
                    "{} | {:?}",
                    cells.join(" | "),
                    QueryResult::graph_ids(row)
                ));
            }
            out
        };
        let cases: &[(&str, &str)] = &[
            ("MATCH (n)", "RETURN labels(n) AS labels, count(*) AS c ORDER BY c DESC LIMIT 40"),
            ("MATCH (n)", "RETURN labels(n), count(*)"),
            ("MATCH (n)", "RETURN DISTINCT labels(n) AS labels"),
            ("MATCH (n)", "RETURN DISTINCT labels(n) AS labels LIMIT 2"),
            ("MATCH (n:Constant)", "RETURN labels(n) AS l, count(n) AS c ORDER BY c ASC"),
            ("MATCH (n:Constant)", "RETURN labels(n) AS l, count(n) AS c ORDER BY c DESC, l ASC"),
            ("MATCH ()-[r]->()", "RETURN type(r) AS t, count(*) AS c ORDER BY c DESC"),
            ("MATCH ()-[r]->()", "RETURN DISTINCT type(r) AS t"),
            ("MATCH ()-[r]->()", "RETURN count(r) AS c"),
            ("MATCH ()-[r:DATAFLOW]->()", "RETURN count(*) AS c"),
            ("MATCH ()-[r:CALL]->()", "RETURN type(r) AS t, count(*) AS c"),
            (
                "MATCH (a)-[r]->(b)",
                "RETURN labels(a) AS a, type(r) AS t, labels(b) AS b, count(*) AS c ORDER BY c DESC LIMIT 40",
            ),
            (
                "MATCH (a)<-[r]-(b)",
                "RETURN labels(a) AS a, type(r) AS t, labels(b) AS b, count(*) AS c ORDER BY c DESC LIMIT 10",
            ),
            ("MATCH (a:CallSiteNode)-[r:DATAFLOW]->(b:LocalVariable)", "RETURN type(r) AS t, count(*) AS c"),
            ("MATCH (a:CallSiteNode)-[r]->(b)", "RETURN DISTINCT labels(b) AS b"),
            ("MATCH (n)", "UNWIND keys(n) AS k RETURN k, count(*) AS c ORDER BY c DESC LIMIT 50"),
            ("MATCH (n)", "UNWIND keys(n) AS k RETURN k, count(k) AS c"),
            ("MATCH (n:AnnotationNode)", "UNWIND keys(n) AS k RETURN DISTINCT k"),
            ("MATCH (n)", "UNWIND keys(n) AS k RETURN labels(n) AS l, k, count(*) AS c ORDER BY c DESC LIMIT 30"),
            ("MATCH (n)", "RETURN n.graphId AS g, count(*) AS c"),
            ("MATCH (n)", "RETURN DISTINCT n.graphId AS g"),
            ("MATCH (n:CallSiteNode)", "RETURN n.graphId AS g, labels(n) AS l, count(n) AS c ORDER BY g DESC LIMIT 5"),
            ("MATCH (a)-[r]->(b)", "RETURN a.graphId AS g, type(r) AS t, count(*) AS c ORDER BY c DESC LIMIT 6"),
            ("MATCH (n)", "RETURN labels(n) AS l, count(*) AS c ORDER BY c DESC LIMIT 0"),
        ];
        for (sources, cross) in [
            (vec![source("a"), source("b")], true),
            (vec![source("g")], false),
        ] {
            let ex = Executor::new(sources, cross);
            let mut answered_here = 0;
            for (head, rest) in cases {
                let fast = format!("{head} {rest}");
                let generic = format!("{head} UNWIND [1] AS one {rest}");
                if answered(&ex, &fast).is_some() {
                    answered_here += 1;
                }
                let a = ex
                    .execute(&fast, Some(1000))
                    .unwrap_or_else(|e| panic!("{fast}: {e}"));
                let b = ex
                    .execute(&generic, Some(1000))
                    .unwrap_or_else(|e| panic!("{generic}: {e}"));
                assert_eq!(render(&a), render(&b), "cross={cross}: {fast}");
            }
            // Every case but the graphId ones in single-graph mode takes the fast path.
            let expected = if cross { cases.len() } else { cases.len() - 4 };
            assert_eq!(
                answered_here, expected,
                "cross={cross}: shapes answered here"
            );
        }
        let ex = Executor::new(vec![source("a"), source("b")], true);
        let r = ex
            .execute(
                "MATCH (n) RETURN labels(n) AS l, count(*) AS c ORDER BY c DESC LIMIT 1",
                None,
            )
            .unwrap();
        assert_eq!(r.rows.len(), 1);
        assert_eq!(QueryResult::graph_ids(&r.rows[0]), ["a", "b"]);
    }
}
