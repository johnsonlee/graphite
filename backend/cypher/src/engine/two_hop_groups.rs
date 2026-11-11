//! Fixed two-hop summary state, retaining the original matcher and final ordering.
//!
//! Cache the graph-value group only after a source produces a real path. Sources
//! with equal external IDs share a group, while endpoint DISTINCT uses NodeRef,
//! including source identity. No path enumeration or relationship-trail rule changes.
use super::aggregate_state::DeferredCount;
use super::*;
use crate::ast::Direction;
use crate::value::NodeRef;
use std::collections::HashSet;

pub(super) struct TwoHopGroups<'a> {
    start: &'a str,
    destination: &'a str,
    graph_expr: &'a Expr,
    columns: Vec<String>,
    by_source: Vec<Option<usize>>,
    groups: IndexMap<Key, Group>,
}

struct Group {
    graph: Value,
    paths: DeferredCount,
    starts: HashSet<NodeRef>,
    destinations: HashSet<NodeRef>,
    start_count: DeferredCount,
    destination_count: DeferredCount,
    provenance: Row,
}

impl<'a> TwoHopGroups<'a> {
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
            || shape.where_clause.is_some()
            || seeds.len() != 1
            || !seeds[0].is_empty()
        {
            return None;
        }
        let mut token = Some(ex.cancel.as_ref());
        while let Some(current) = token {
            if current.work.is_some() {
                return None;
            }
            token = current.parent.as_deref();
        }
        let [pattern] = patterns else { return None };
        let [a, b, c] = pattern.nodes.as_slice() else {
            return None;
        };
        let [first, second] = pattern.rels.as_slice() else {
            return None;
        };
        let names = [
            a.variable.as_deref()?,
            b.variable.as_deref()?,
            c.variable.as_deref()?,
        ];
        if names.iter().collect::<HashSet<_>>().len() != 3
            || names.iter().any(|name| is_internal_key(name))
            || pattern.path_variable.is_some()
            || a.labels.as_slice() != ["CallSite"]
            || !b.labels.is_empty()
            || !c.labels.is_empty()
            || [a, b, c].iter().any(|node| !node.properties.is_empty())
            || [first, second].iter().any(|rel| {
                rel.variable.is_some()
                    || rel.variable_length
                    || rel.direction != Direction::Outgoing
                    || rel.types.as_slice() != ["DATAFLOW"]
                    || !rel.properties.is_empty()
            })
        {
            return None;
        }
        let [graph, paths, starts, destinations] = items else {
            return None;
        };
        if !matches!(&graph.expr, Expr::Property {expr, key}
            if key == "graphId" && matches!(expr.as_ref(), Expr::Variable(v) if v == names[0]))
            || !matches!(paths.expr, Expr::CountStar)
            || !distinct_node_count(&starts.expr, names[0])
            || !distinct_node_count(&destinations.expr, names[2])
        {
            return None;
        }
        let columns = item_names(items);
        if columns.iter().any(|name| is_internal_key(name))
            || columns.iter().collect::<HashSet<_>>().len() != 4
            || shape.order.as_ref().is_some_and(|order| {
                order.iter().any(
                    |item| !matches!(&item.expr, Expr::Variable(name) if columns.contains(name)),
                )
            })
        {
            return None;
        }
        #[cfg(test)]
        HITS.with(|hits| hits.set(hits.get() + 1));
        Some(Self {
            start: names[0],
            destination: names[2],
            graph_expr: &graph.expr,
            columns,
            by_source: vec![None; ex.sources.len()],
            groups: IndexMap::new(),
        })
    }

    pub(super) fn consume(&mut self, ev: &Evaluator, mut row: Row) -> CypherResult<bool> {
        let (Some(Value::Node(start)), Some(Value::Node(destination))) =
            (row.get(self.start), row.get(self.destination))
        else {
            unreachable!("admitted native two-hop match binds physical endpoints")
        };
        let (start, destination) = (*start, *destination);
        let index = match self.by_source[start.source as usize] {
            Some(index) => index,
            None => {
                let graph = ev.eval(self.graph_expr, &row)?;
                self.register_source(start.source, graph)
            }
        };
        let group = self.groups.get_index_mut(index).unwrap().1;
        group.paths.add(1);
        if group.starts.insert(start) {
            group.start_count.add(1);
        }
        if group.destinations.insert(destination) {
            group.destination_count.add(1);
        }
        // The first contributing row preserves absence as well as presence of
        // provenance. Subsequent rows use the same merge operation as aggregation.
        if group.provenance.is_empty() {
            row.retain(|key, _| key == INTERNAL_PROVENANCE_KEY);
            group.provenance = row;
        } else {
            merge_provenance(&mut group.provenance, &row);
        }
        Ok(true)
    }

    fn register_source(&mut self, source: u32, graph: Value) -> usize {
        let entry = self.groups.entry(value_key(&graph));
        let index = entry.index();
        entry.or_insert_with(|| Group {
            graph,
            paths: DeferredCount::default(),
            starts: HashSet::new(),
            destinations: HashSet::new(),
            start_count: DeferredCount::default(),
            destination_count: DeferredCount::default(),
            provenance: Row::new(),
        });
        self.by_source[source as usize] = Some(index);
        index
    }

    pub(super) fn finish(self, ex: &Executor) -> CypherResult<(Vec<String>, Vec<Row>)> {
        let mut output = Vec::with_capacity(self.groups.len());
        for (index, (_, group)) in self.groups.into_iter().enumerate() {
            if index & 1023 == 0 {
                ex.cancel.check()?;
            }
            // Match the aggregate expression order and finish every group before LIMIT.
            let paths = group.paths.finish();
            let starts = group.start_count.finish();
            let destinations = group.destination_count.finish();
            let mut row = Row::from([
                (self.columns[0].clone(), group.graph),
                (self.columns[1].clone(), Value::Int(paths)),
                (self.columns[2].clone(), Value::Int(starts)),
                (self.columns[3].clone(), Value::Int(destinations)),
            ]);
            if let Some(provenance) = group.provenance.get(INTERNAL_PROVENANCE_KEY) {
                row.insert(INTERNAL_PROVENANCE_KEY.into(), provenance.clone());
            }
            output.push(row);
        }
        Ok((self.columns, output))
    }
}

fn distinct_node_count(expr: &Expr, variable: &str) -> bool {
    matches!(expr, Expr::FunctionCall { name, args, distinct: true }
        if name.eq_ignore_ascii_case("count")
        && matches!(args.as_slice(), [Expr::Variable(v)] if v == variable))
}

#[cfg(test)]
thread_local! {
    static DISABLED: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
    static HITS: std::cell::Cell<usize> = const { std::cell::Cell::new(0) };
}

#[cfg(test)]
#[path = "two_hop_groups_tests.rs"]
mod tests;
