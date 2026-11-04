//! Bounded, globally ordered reads of the persisted CallSite property CSRs.
//!
//! Unlike bounded top-K, this plan avoids projecting losing nodes. Every source's
//! complete index/domain proof precedes output, so an unsupported or malformed
//! source can fall back without losing rows. First-use proof work is cancellable
//! and remains inside the query boundary.

use super::matching::{resolve_node_class, NodeClass};
use super::pipeline::{add_provenance_id, is_internal_key, QueryResult, Row};
use super::props::label_tags;
use super::Executor;
use crate::ast::{Clause, Expr, Literal, ReturnItem};
use crate::context::GraphContext;
use crate::render::to_cypher_string;
use crate::semantics::compare_utf16;
use crate::value::NodeRef;
use crate::CypherResult;
use graphite_storage::callsite_index::{NodePostings, PropertyValues};
use graphite_storage::node::{CALL_SITE_PROPERTY_NAMES, TAG_CALL_SITE_NODE};
use std::cmp::Ordering;
use std::collections::{BinaryHeap, HashSet};

const MAX_ORDERED_ROWS: usize = 65_536;

pub(super) struct OrderedPropertyPlan {
    property: usize,
    descending: bool,
    projections: Vec<String>,
    columns: Vec<String>,
    skip: usize,
    limit: usize,
}

fn property<'a>(expr: &'a Expr, variable: &str) -> Option<&'a str> {
    let Expr::Property { expr, key } = expr else {
        return None;
    };
    matches!(expr.as_ref(), Expr::Variable(v) if v == variable).then_some(key)
}

fn column(item: &ReturnItem) -> String {
    item.alias
        .clone()
        .unwrap_or_else(|| to_cypher_string(&item.expr))
}

impl OrderedPropertyPlan {
    pub(super) fn build(clauses: &[Clause]) -> Option<Self> {
        let [Clause::Match {
            patterns,
            optional: false,
            where_clause: None,
        }, Clause::Return {
            distinct: false,
            items: Some(items),
        }, Clause::OrderBy(order), tail @ ..] = clauses
        else {
            return None;
        };
        let [pattern] = patterns.as_slice() else {
            return None;
        };
        let [node] = pattern.nodes.as_slice() else {
            return None;
        };
        if !pattern.rels.is_empty()
            || pattern.path_variable.is_some()
            || !node.properties.is_empty()
            || !matches!(resolve_node_class(&node.labels), NodeClass::Tags(tags) if tags.as_slice() == [TAG_CALL_SITE_NODE])
            || node.labels.iter().any(|label| {
                !label_tags(label).is_some_and(|tags| tags.contains(&TAG_CALL_SITE_NODE))
            })
        {
            return None;
        }
        let variable = node.variable.as_deref()?;
        let [order] = order.as_slice() else {
            return None;
        };
        if items.is_empty() {
            return None;
        }
        let columns: Vec<_> = items.iter().map(column).collect();
        if columns.iter().any(|name| is_internal_key(name))
            || columns.iter().collect::<HashSet<_>>().len() != columns.len()
        {
            return None;
        }
        let expr = match &order.expr {
            Expr::Variable(alias) => {
                &items
                    .get(columns.iter().position(|name| name == alias)?)?
                    .expr
            }
            expr => expr,
        };
        let property_index = CALL_SITE_PROPERTY_NAMES
            .iter()
            .position(|name| Some(*name) == property(expr, variable))?;
        let projections = items
            .iter()
            .map(|item| {
                let key = property(&item.expr, variable)?;
                (CALL_SITE_PROPERTY_NAMES.contains(&key)
                    || matches!(
                        key,
                        "id" | "line" | "ordinal" | "graphId" | "elementId" | "qualifiedId"
                    ))
                .then(|| key.to_owned())
            })
            .collect::<Option<Vec<_>>>()?;
        let literal = |expr: &Expr| match expr {
            Expr::Literal(Literal::Int(n)) => usize::try_from(*n).ok(),
            _ => None,
        };
        let (skip, limit) = match tail {
            [Clause::Limit(limit)] => (0, literal(limit)?),
            [Clause::Skip(skip), Clause::Limit(limit)] => (literal(skip)?, literal(limit)?),
            _ => return None,
        };
        if skip.checked_add(limit)? > MAX_ORDERED_ROWS {
            return None;
        }
        Some(Self {
            property: property_index,
            descending: order.descending,
            projections,
            columns,
            skip,
            limit,
        })
    }

    pub(super) fn run(&self, ex: &Executor) -> CypherResult<Option<QueryResult>> {
        ex.cancel.check()?;
        // Prove every source before returning even the first row. Later invalid
        // sources must fall back to the matcher as one complete query.
        let mut cursors = Vec::new();
        for (source, entry) in ex.sources.iter().enumerate() {
            if entry.graph.count_by_tag(TAG_CALL_SITE_NODE) == 0 {
                continue;
            }
            let Some(index) = entry
                .graph
                .validated_ordered_call_site(self.property, || ex.cancel.check())?
            else {
                return Ok(None);
            };
            let Some(values) = index.property_values(self.property, self.descending) else {
                return Ok(None);
            };
            cursors.push(SourceCursor {
                source,
                values,
                nodes: None,
                sid: 0,
            });
        }
        let mut heap = BinaryHeap::new();
        for (cursor, stream) in cursors.iter_mut().enumerate() {
            if let Some((node, sid)) = stream.next() {
                heap.push(Head {
                    cursor,
                    source: stream.source,
                    node,
                    value: ex.graph(stream.source as u32).str(sid),
                    descending: self.descending,
                });
            }
        }
        let mut skipped = 0;
        let mut rows = Vec::with_capacity(self.limit.min(1024));
        while rows.len() < self.limit {
            let Some(head) = heap.pop() else { break };
            ex.tick()?;
            if skipped < self.skip {
                skipped += 1;
            } else {
                let node = NodeRef {
                    source: head.source as u32,
                    id: head.node,
                };
                let mut row = Row::with_capacity(self.columns.len() + usize::from(ex.cross));
                for (name, key) in self.columns.iter().zip(&self.projections) {
                    row.insert(name.clone(), ex.node_property(node, key));
                }
                if ex.cross {
                    add_provenance_id(&mut row, ex.sources[head.source].id.clone());
                }
                rows.push(row);
            }
            if let Some((node, sid)) = cursors[head.cursor].next() {
                heap.push(Head {
                    cursor: head.cursor,
                    source: head.source,
                    node,
                    value: ex.graph(head.source as u32).str(sid),
                    descending: self.descending,
                });
            }
        }
        ex.cancel.check()?;
        Ok(Some(QueryResult {
            columns: self.columns.clone(),
            rows,
            compact: None,
            more: false,
        }))
    }
}

struct SourceCursor<'a> {
    source: usize,
    values: PropertyValues<'a>,
    nodes: Option<NodePostings<'a>>,
    sid: u32,
}

impl SourceCursor<'_> {
    fn next(&mut self) -> Option<(u32, u32)> {
        loop {
            if let Some(node) = self.nodes.as_mut().and_then(Iterator::next) {
                return Some((node, self.sid));
            }
            let (sid, nodes) = self.values.next()?;
            self.sid = sid;
            self.nodes = Some(nodes);
        }
    }
}

struct Head<'a> {
    cursor: usize,
    source: usize,
    node: u32,
    value: &'a str,
    descending: bool,
}

impl Ord for Head<'_> {
    fn cmp(&self, other: &Self) -> Ordering {
        let value = compare_utf16(self.value, other.value);
        let value = if self.descending {
            value.reverse()
        } else {
            value
        };
        // BinaryHeap is a max-heap. Reverse the entire desired stable order;
        // DESC affects the value only, never source/node encounter ties.
        value
            .then(self.source.cmp(&other.source))
            .then(self.node.cmp(&other.node))
            .reverse()
    }
}
impl PartialOrd for Head<'_> {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}
impl PartialEq for Head<'_> {
    fn eq(&self, other: &Self) -> bool {
        self.cmp(other) == Ordering::Equal
    }
}
impl Eq for Head<'_> {}

#[cfg(test)]
#[path = "ordered_property_tests.rs"]
mod tests;
