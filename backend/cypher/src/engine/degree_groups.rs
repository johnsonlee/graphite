//! Compact first-stage degree counts; traversal and the histogram tail stay shared.
//!
//! For a physical source node, graphId is a deterministic source property, so the
//! two grouping keys (graphId, node) have exactly the same partition as NodeRef.
//! Keep encounter order and shared deferred overflow, without per-edge key/value
//! vectors or a generic aggregate-state allocation per source node.
use super::aggregate_state::DeferredCount;
use super::*;
use crate::ast::Direction;
use crate::value::NodeRef;

pub(super) struct DegreeGroups<'a> {
    variable: &'a str,
    graph_expr: &'a Expr,
    columns: Vec<String>,
    groups: IndexMap<NodeRef, DegreeGroup>,
}

struct DegreeGroup {
    graph: Value,
    count: DeferredCount,
    provenance: Row,
}

impl<'a> DegreeGroups<'a> {
    pub(super) fn build(
        ex: &Executor,
        items: &'a [ReturnItem],
        shape: &FusedShape,
        patterns: &'a [Pattern],
        seeds: &[Row],
    ) -> Option<Self> {
        #[cfg(test)]
        if DISABLED.with(|flag| flag.get()) {
            return None;
        }
        if super::super::optimizations_disabled()
            || ex.property_graph.is_some()
            || shape.distinct
            || shape.order.is_some()
            || shape.where_clause.is_some()
            || seeds.len() != 1
            || !seeds[0].is_empty()
        {
            return None;
        }
        // Budgeted requests retain precisely the existing admission and tick path.
        let mut token = Some(ex.cancel.as_ref());
        while let Some(current) = token {
            if current.work.is_some() {
                return None;
            }
            token = current.parent.as_deref();
        }
        let [pattern] = patterns else { return None };
        let [source, target] = pattern.nodes.as_slice() else {
            return None;
        };
        let [rel] = pattern.rels.as_slice() else {
            return None;
        };
        let variable = source.variable.as_deref()?;
        if pattern.path_variable.is_some()
            || source.labels.as_slice() != ["CallSite"]
            || !source.properties.is_empty()
            || !target.labels.is_empty()
            || !target.properties.is_empty()
            || target.variable.as_deref() == Some(variable)
            || rel.variable.is_some()
            || rel.variable_length
            || rel.direction != Direction::Outgoing
            || rel.types.as_slice() != ["DATAFLOW"]
            || !rel.properties.is_empty()
            || is_internal_key(variable)
            || target.variable.as_deref().is_some_and(is_internal_key)
        {
            return None;
        }
        let [graph, node, count] = items else {
            return None;
        };
        if !matches!(&graph.expr, Expr::Property { expr, key }
            if key == "graphId" && matches!(expr.as_ref(), Expr::Variable(v) if v == variable))
            || !matches!(&node.expr, Expr::Variable(v) if v == variable)
            || !matches!(count.expr, Expr::CountStar)
        {
            return None;
        }
        let columns = item_names(items);
        if columns.iter().any(|name| is_internal_key(name))
            || columns
                .iter()
                .collect::<std::collections::HashSet<_>>()
                .len()
                != 3
        {
            return None;
        }
        #[cfg(test)]
        HITS.with(|hits| hits.set(hits.get() + 1));
        Some(Self {
            variable,
            graph_expr: &graph.expr,
            columns,
            groups: IndexMap::new(),
        })
    }

    pub(super) fn consume(&mut self, ev: &Evaluator, mut row: Row) -> CypherResult<bool> {
        let Some(Value::Node(node)) = row.get(self.variable) else {
            unreachable!("admitted single physical DATAFLOW match binds a source node")
        };
        let node = *node;
        match self.groups.entry(node) {
            indexmap::map::Entry::Occupied(mut entry) => {
                let group = entry.get_mut();
                group.count.add(1);
                merge_provenance(&mut group.provenance, &row);
            }
            indexmap::map::Entry::Vacant(entry) => {
                // graphId cannot vary for repeated instances of one physical NodeRef.
                let graph = ev.eval(self.graph_expr, &row)?;
                row.retain(|key, _| key == INTERNAL_PROVENANCE_KEY);
                let mut count = DeferredCount::default();
                count.add(1);
                entry.insert(DegreeGroup {
                    graph,
                    count,
                    provenance: row,
                });
            }
        }
        Ok(true)
    }

    pub(super) fn finish(self, ex: &Executor) -> CypherResult<(Vec<String>, Vec<Row>)> {
        let mut rows = Vec::with_capacity(self.groups.len());
        for (index, (node, group)) in self.groups.into_iter().enumerate() {
            if index & 1023 == 0 {
                ex.cancel.check()?;
            }
            // Finish every group before downstream ORDER/LIMIT, including LIMIT 0.
            let count = group.count.finish();
            let mut row = Row::from([
                (self.columns[0].clone(), group.graph),
                (self.columns[1].clone(), Value::Node(node)),
                (self.columns[2].clone(), Value::Int(count)),
            ]);
            if let Some(provenance) = group.provenance.get(INTERNAL_PROVENANCE_KEY) {
                row.insert(INTERNAL_PROVENANCE_KEY.into(), provenance.clone());
            }
            rows.push(row);
        }
        Ok((self.columns, rows))
    }
}

#[cfg(test)]
thread_local! {
    static DISABLED: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
    static HITS: std::cell::Cell<usize> = const { std::cell::Cell::new(0) };
}

#[cfg(test)]
#[path = "degree_groups_tests.rs"]
mod tests;
