//! Fuse a deliberately total OPTIONAL one-hop match into its WITH counts.
//!
//! Keep the original matcher, unmatched-null row, aggregate states and encounter
//! order. Only the intermediate matched-row Vec disappears; input seeds and the
//! grouped result retain their original cardinalities. Arbitrary expressions or
//! bounded work tokens keep the original clause boundaries.
use super::aggregate_state::DeferredCount;
use super::*;
use crate::ast::Direction;
use crate::value::NodeRef;
use std::collections::HashSet;

struct Plan<'a> {
    patterns: &'a [Pattern],
    source: &'a str,
    target: &'a str,
    items: &'a [ReturnItem],
    order: Option<&'a [OrderItem]>,
}

impl<'a> Plan<'a> {
    fn build(clauses: &'a [Clause]) -> Option<Self> {
        let [Clause::Match {
            patterns,
            optional: true,
            where_clause: None,
        }, Clause::With {
            items: Some(items),
            distinct: false,
            where_clause: None,
        }, rest @ ..] = clauses
        else {
            return None;
        };
        let [pattern] = patterns.as_slice() else {
            return None;
        };
        let [a, b] = pattern.nodes.as_slice() else {
            return None;
        };
        let [rel] = pattern.rels.as_slice() else {
            return None;
        };
        let (source, target) = (a.variable.as_deref()?, b.variable.as_deref()?);
        if pattern.path_variable.is_some()
            || source == target
            || [source, target].iter().any(|name| is_internal_key(name))
            || [a, b]
                .iter()
                .any(|node| !node.labels.is_empty() || !node.properties.is_empty())
            || rel.variable.is_some()
            || rel.variable_length
            || rel.direction != Direction::Outgoing
            || !rel.properties.is_empty()
            || !matches!(rel.types.as_slice(), [kind] if kind.eq_ignore_ascii_case("DATAFLOW"))
        {
            return None;
        }
        let mut has_count = false;
        for item in items {
            match &item.expr {
                Expr::Variable(v) if v == source => {}
                Expr::Property { expr, key }
                    if key == "graphId"
                        && matches!(expr.as_ref(), Expr::Variable(v) if v == source) => {}
                Expr::CountStar => has_count = true,
                Expr::FunctionCall {
                    name,
                    args,
                    distinct: false,
                } if name.eq_ignore_ascii_case("count")
                    && matches!(args.as_slice(), [Expr::Variable(v)] if v == target) =>
                {
                    has_count = true
                }
                _ => return None,
            }
        }
        let names = item_names(items);
        if !has_count
            || names.iter().any(|name| is_internal_key(name))
            || names.iter().collect::<HashSet<_>>().len() != names.len()
        {
            return None;
        }
        // Ordering expressions are finalized only after every match, but keep
        // admission narrow and avoid alias/hidden aggregate scope differences.
        let order = next_order_by(rest);
        if order.is_some_and(|order| {
            order
                .iter()
                .any(|item| !matches!(&item.expr, Expr::Variable(v) if names.contains(v)))
        }) {
            return None;
        }
        Some(Self {
            patterns,
            source,
            target,
            items,
            order,
        })
    }

    fn total_inputs(&self, ex: &Executor, rows: &[Row]) -> CypherResult<bool> {
        for row in rows {
            ex.cancel.check()?;
            if row.contains_key(self.target) || row.contains_key(INTERNAL_WEIGHT_KEY) {
                return Ok(false);
            }
            let Some(Value::Node(node)) = row.get(self.source) else {
                return Ok(false);
            };
            let valid = match &ex.property_graph {
                Some(graph) => node.source == 0 && graph.node(node.id).is_some(),
                None => ex
                    .sources
                    .get(node.source as usize)
                    .is_some_and(|source| source.graph.has_node(node.id)),
            };
            if !valid {
                return Ok(false);
            }
        }
        Ok(true)
    }
}

// graphId is fixed by a native NodeRef's source. Use that identity as the
// compound grouping key only for the exact graph/source/count(target) shape.
struct CompactGroups<'a> {
    source: &'a str,
    target: &'a str,
    graph_expr: &'a Expr,
    columns: Vec<String>,
    groups: IndexMap<NodeRef, CompactGroup>,
}

struct CompactGroup {
    graph: Value,
    count: DeferredCount,
    provenance: Row,
}

impl<'a> CompactGroups<'a> {
    fn build(ex: &Executor, plan: &Plan<'a>) -> Option<Self> {
        #[cfg(test)]
        if COMPACT_DISABLED.with(|flag| flag.get()) {
            return None;
        }
        if ex.property_graph.is_some() || plan.order.is_some() {
            return None;
        }
        let [graph, source, count] = plan.items else {
            return None;
        };
        if !matches!(&graph.expr, Expr::Property { expr, key }
            if key == "graphId" && matches!(expr.as_ref(), Expr::Variable(v) if v == plan.source))
            || !matches!(&source.expr, Expr::Variable(v) if v == plan.source)
            || !matches!(&count.expr, Expr::FunctionCall {name, args, distinct: false}
                if name.eq_ignore_ascii_case("count") && matches!(args.as_slice(), [Expr::Variable(v)] if v == plan.target))
        {
            return None;
        }
        Some(Self {
            source: plan.source,
            target: plan.target,
            graph_expr: &graph.expr,
            columns: item_names(plan.items),
            groups: IndexMap::new(),
        })
    }

    fn consume(&mut self, ev: &Evaluator, mut row: Row) -> CypherResult<bool> {
        let Some(Value::Node(node)) = row.get(self.source) else {
            unreachable!("proved source")
        };
        let node = *node;
        let increment = match row.get(self.target) {
            Some(Value::Node(_)) => 1,
            Some(Value::Null) => 0,
            _ => unreachable!("original matcher or unmatched optional row"),
        };
        match self.groups.entry(node) {
            indexmap::map::Entry::Occupied(mut entry) => {
                let group = entry.get_mut();
                group.count.add(increment);
                merge_provenance(&mut group.provenance, &row);
            }
            indexmap::map::Entry::Vacant(entry) => {
                let graph = ev.eval(self.graph_expr, &row)?;
                row.retain(|key, _| key == INTERNAL_PROVENANCE_KEY);
                let mut count = DeferredCount::default();
                count.add(increment);
                entry.insert(CompactGroup {
                    graph,
                    count,
                    provenance: row,
                });
            }
        }
        Ok(true)
    }

    fn finish(self, ex: &Executor) -> CypherResult<(Vec<String>, Vec<Row>)> {
        let mut rows = Vec::with_capacity(self.groups.len());
        for (index, (node, group)) in self.groups.into_iter().enumerate() {
            if index & 1023 == 0 {
                ex.cancel.check()?;
            }
            // All groups, including zero-degree sinks, finish in encounter order
            // before any subsequent LIMIT can discard a row or hide overflow.
            let count = group.count.finish();
            let mut row = Row::from([
                (self.columns[0].clone(), group.graph),
                (self.columns[1].clone(), Value::Node(node)),
                (self.columns[2].clone(), Value::Int(count)),
            ]);
            if let Some(value) = group.provenance.get(INTERNAL_PROVENANCE_KEY) {
                row.insert(INTERNAL_PROVENANCE_KEY.into(), value.clone());
            }
            rows.push(row);
        }
        ex.cancel.check()?;
        Ok((self.columns, rows))
    }
}

pub(super) fn try_execute(
    ex: &Executor,
    matcher: &Matcher,
    ev: &Evaluator,
    clauses: &[Clause],
    rows: &mut Vec<Row>,
) -> CypherResult<Option<(Vec<String>, Vec<Row>)>> {
    #[cfg(test)]
    if DISABLED.with(|flag| flag.get()) {
        return Ok(None);
    }
    if super::super::optimizations_disabled() || has_work_budget(ex) {
        return Ok(None);
    }
    let Some(plan) = Plan::build(clauses) else {
        return Ok(None);
    };
    if !plan.total_inputs(ex, rows)? {
        return Ok(None);
    }
    #[cfg(test)]
    HITS.with(|hits| hits.set(hits.get() + 1));
    if let Some(mut groups) = CompactGroups::build(ex, &plan) {
        #[cfg(test)]
        COMPACT_HITS.with(|hits| hits.set(hits.get() + 1));
        for mut seed in std::mem::take(rows) {
            ex.cancel.check()?;
            let mut produced = false;
            matcher.match_patterns(&seed, plan.patterns, &mut |row| {
                produced = true;
                groups.consume(ev, row)
            })?;
            if !produced {
                seed.insert(plan.target.to_owned(), Value::Null);
                groups.consume(ev, seed)?;
            }
        }
        ex.cancel.check()?;
        return groups.finish(ex).map(Some);
    }
    let agg = AggPlan::new(plan.items, plan.order, ex.dialect)?;
    let mut groups: IndexMap<Vec<Key>, GroupAcc> = IndexMap::new();
    let mut consume = |row: Row| -> CypherResult<bool> {
        let mut keys = Vec::with_capacity(agg.group_exprs.len());
        let mut values = Vec::with_capacity(agg.group_exprs.len());
        for expr in &agg.group_exprs {
            let value = ev.eval(expr, &row)?;
            keys.push(value_key(&value));
            values.push(value);
        }
        let group = groups.entry(keys).or_insert_with(|| GroupAcc {
            first_row: row.clone(),
            group_values: values,
            agg_states: agg.states(),
        });
        for (at, aggregate) in agg.aggs.iter().enumerate() {
            let value = match &aggregate.arg {
                Some(expr) => ev.eval(expr, &row)?,
                None => star_input(aggregate, &row),
            };
            group.agg_states[at].push(value, 1);
        }
        merge_provenance(&mut group.first_row, &row);
        Ok(true)
    };
    // Ownership transfers only after admission. Release seeds as their matches
    // finish rather than retaining the entire initial MATCH during aggregation.
    for mut seed in std::mem::take(rows) {
        ex.cancel.check()?;
        let mut produced = false;
        matcher.match_patterns(&seed, plan.patterns, &mut |row| {
            produced = true;
            consume(row)
        })?;
        if !produced {
            seed.insert(plan.target.to_owned(), Value::Null);
            consume(seed)?;
        }
    }
    ex.cancel.check()?;
    let output = finalize_groups(ev, &agg, plan.items, groups)?;
    ex.cancel.check()?;
    Ok(Some((item_names(plan.items), output)))
}

fn has_work_budget(ex: &Executor) -> bool {
    let mut token = Some(ex.cancel.as_ref());
    while let Some(current) = token {
        if current.work.is_some() {
            return true;
        }
        token = current.parent.as_deref();
    }
    false
}

#[cfg(test)]
thread_local! {
    static DISABLED: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
    static HITS: std::cell::Cell<usize> = const { std::cell::Cell::new(0) };
    static COMPACT_DISABLED: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
    static COMPACT_HITS: std::cell::Cell<usize> = const { std::cell::Cell::new(0) };
}

#[cfg(test)]
#[path = "optional_groups_tests.rs"]
mod tests;
