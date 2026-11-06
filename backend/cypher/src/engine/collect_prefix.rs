//! A bounded collect prefix over a completely certified scalar CallSite domain.
//!
//! The existing cap proof establishes that the remaining query cannot inspect the
//! discarded suffix. All sources must additionally prove every matching record
//! and scalar SID valid before any row is omitted. Other aggregate arguments and
//! domains retain the original eager evaluation and its first-error order.
use super::*;
use crate::value::{NodeRef, SourceIdx};
use graphite_storage::node::CALL_SITE_PROPERTY_NAMES;

struct CollectPrefixPlan<'a> {
    variable: &'a str,
    items: &'a [ReturnItem],
    cap: usize,
}

impl<'a> CollectPrefixPlan<'a> {
    fn build(
        rows: &[Row],
        patterns: &'a [Pattern],
        shape: &'a FusedShape,
        prefix_caps: &[Option<usize>],
    ) -> Option<Self> {
        if super::super::optimizations_disabled()
            || !matches!(rows, [seed] if seed.is_empty())
            || shape.distinct
            || shape.order.is_some()
            || shape.where_clause.is_some()
        {
            return None;
        }
        let [Some(cap)] = prefix_caps else {
            return None;
        };
        if *cap > MAX_BOUNDED_ORDER_ROWS {
            return None;
        }
        let [pattern] = patterns else { return None };
        let [node] = pattern.nodes.as_slice() else {
            return None;
        };
        if !pattern.rels.is_empty()
            || pattern.path_variable.is_some()
            || !node.properties.is_empty()
            || !matches!(resolve_node_class(&node.labels), NodeClass::Tags(tags) if tags.as_slice() == [TAG_CALL_SITE_NODE])
            || node.labels.iter().any(|label| {
                !super::super::props::label_tags(label)
                    .is_some_and(|tags| tags.contains(&TAG_CALL_SITE_NODE))
            })
        {
            return None;
        }
        let variable = node.variable.as_deref()?;
        if variable.is_empty() || is_internal_key(variable) {
            return None;
        }
        let items = shape.items.as_deref()?;
        let [item] = items else { return None };
        if item_names(items).iter().any(|name| is_internal_key(name)) {
            return None;
        }
        let Expr::FunctionCall {
            name,
            args,
            distinct: false,
        } = &item.expr
        else {
            return None;
        };
        if !name.eq_ignore_ascii_case("collect") {
            return None;
        }
        let [Expr::Property { expr, key }] = args.as_slice() else {
            return None;
        };
        if !matches!(expr.as_ref(), Expr::Variable(v) if v == variable)
            || !CALL_SITE_PROPERTY_NAMES.contains(&key.as_str())
        {
            return None;
        }
        Some(Self {
            variable,
            items,
            cap: *cap,
        })
    }

    fn run(&self, ex: &Executor, ev: &Evaluator) -> CypherResult<Option<(Vec<String>, Vec<Row>)>> {
        // Certify later graphs even for an empty prefix. A later invalid SID or
        // truncated CallSite must restore the original complete scan, not vanish
        // behind an already-full prefix or a zero-length slice.
        for source in &ex.sources {
            if !source
                .graph
                .call_site_scalar_records_valid(|| ex.cancel.check())?
            {
                return Ok(None);
            }
        }
        let mut plan = AggPlan::new(self.items)?;
        plan.prefix_caps = vec![Some(self.cap)];
        let mut acc = GroupAcc {
            first_row: Row::new(),
            group_values: Vec::new(),
            agg_states: plan.states(),
        };
        let mut any_match = false;
        let mut remaining = self.cap;
        for (si, source) in ex.sources.iter().enumerate() {
            ex.cancel.check()?;
            let ids = source.graph.ids_by_tag(TAG_CALL_SITE_NODE);
            if ids.is_empty() {
                continue;
            }
            any_match = true;
            // Matching a named node contributes provenance even when its value
            // falls beyond the retained prefix. Source order controls values;
            // add_provenance_id independently preserves sorted unique graph IDs.
            if ex.cross {
                add_provenance_id(&mut acc.first_row, source.id.clone());
            }
            for &id in ids {
                if remaining == 0 {
                    break;
                }
                ex.tick()?;
                let row = Row::from([(
                    self.variable.to_owned(),
                    Value::Node(NodeRef {
                        source: si as SourceIdx,
                        id,
                    }),
                )]);
                let value = ev.eval(plan.aggs[0].arg.as_ref().unwrap(), &row)?;
                if !value.is_null() {
                    remaining -= 1;
                }
                acc.agg_states[0].push(value, 1);
            }
        }
        ex.cancel.check()?;
        let mut groups = IndexMap::new();
        if any_match {
            groups.insert(Vec::new(), acc);
        }
        let rows = finalize_groups(ev, &plan, self.items, groups, None)?;
        Ok(Some((item_names(self.items), rows)))
    }
}

#[cfg(test)]
thread_local! { static DISABLED: std::cell::Cell<bool> = const { std::cell::Cell::new(false) }; }

pub(super) fn try_execute(
    ex: &Executor,
    ev: &Evaluator,
    rows: &[Row],
    patterns: &[Pattern],
    shape: &FusedShape,
    prefix_caps: &[Option<usize>],
) -> CypherResult<Option<(Vec<String>, Vec<Row>)>> {
    #[cfg(test)]
    if DISABLED.with(std::cell::Cell::get) {
        return Ok(None);
    }
    let Some(plan) = CollectPrefixPlan::build(rows, patterns, shape, prefix_caps) else {
        return Ok(None);
    };
    plan.run(ex, ev)
}

#[cfg(test)]
#[path = "collect_prefix_tests.rs"]
mod tests;
