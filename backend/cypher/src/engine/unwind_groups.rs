//! Stream a proven string-pair expansion into the original aggregate states.
//!
//! Both UNWINDs normally retain their complete output before WITH filters it. This
//! narrow tail has only identity projections, a string comparison, and counts;
//! proving every input first lets it reuse one projected row without moving an
//! expression error across a clause boundary. Ordering and LIMIT remain downstream.
use super::*;
use crate::ast::CmpOp;
use std::collections::HashSet;

#[path = "unwind_groups_compact.rs"]
mod compact;

struct PairPlan<'a> {
    list: &'a str,
    left: &'a str,
    right: &'a str,
    carried: Vec<&'a str>,
    projection: &'a [ReturnItem],
    predicate: &'a Expr,
    output: &'a [ReturnItem],
    order: Option<&'a [OrderItem]>,
}

impl<'a> PairPlan<'a> {
    fn build(clauses: &'a [Clause]) -> Option<Self> {
        let [Clause::Unwind {
            expr: Expr::Variable(list),
            variable: left,
        }, Clause::Unwind {
            expr: Expr::Variable(second_list),
            variable: right,
        }, Clause::With {
            distinct: false,
            items: Some(projection),
            where_clause: Some(predicate),
        }, Clause::Return {
            distinct: false,
            items: Some(output),
        }, rest @ ..] = clauses
        else {
            return None;
        };
        if list != second_list || list == left || list == right || left == right {
            return None;
        }
        if [list, left, right].iter().any(|name| is_internal_key(name)) {
            return None;
        }
        let Expr::Comparison {
            op: CmpOp::Lt,
            left: compared_left,
            right: compared_right,
        } = predicate
        else {
            return None;
        };
        if !matches!(compared_left.as_ref(), Expr::Variable(v) if v == left)
            || !matches!(compared_right.as_ref(), Expr::Variable(v) if v == right)
        {
            return None;
        }
        // Exactly two carried scalar bindings plus the pair. Identity projections
        // have the same scope before/after WITH in both compatibility dialects.
        if projection.len() != 4 || output.len() != 4 {
            return None;
        }
        let mut names = HashSet::new();
        let mut carried = Vec::new();
        for item in projection {
            let Expr::Variable(name) = &item.expr else {
                return None;
            };
            if name == list
                || is_internal_key(name)
                || item.column_name() != *name
                || item.alias.as_ref().is_some_and(|alias| alias != name)
                || !names.insert(name.as_str())
            {
                return None;
            }
            if name != left && name != right {
                carried.push(name.as_str());
            }
        }
        if carried.len() != 2 || !names.contains(left.as_str()) || !names.contains(right.as_str()) {
            return None;
        }
        if !matches!(&output[0].expr, Expr::Variable(v) if v == left)
            || !matches!(&output[1].expr, Expr::Variable(v) if v == right)
            || !matches!(output[2].expr, Expr::CountStar)
        {
            return None;
        }
        let Expr::FunctionCall {
            name,
            args,
            distinct: true,
        } = &output[3].expr
        else {
            return None;
        };
        if !name.eq_ignore_ascii_case("count")
            || !matches!(args.as_slice(), [Expr::Variable(v)] if carried.contains(&v.as_str()))
        {
            return None;
        }
        let output_names = item_names(output);
        if output_names.iter().any(|name| is_internal_key(name))
            || output_names.iter().collect::<HashSet<_>>().len() != output.len()
        {
            return None;
        }
        let order = next_order_by(rest);
        if order.is_some_and(|items| {
            items
                .iter()
                .any(|item| !matches!(&item.expr, Expr::Variable(v) if output_names.contains(v)))
        }) {
            return None;
        }
        Some(Self {
            list,
            left,
            right,
            carried,
            projection,
            predicate,
            output,
            order,
        })
    }

    fn inputs_are_total(&self, ex: &Executor, rows: &[Row]) -> CypherResult<bool> {
        for row in rows {
            ex.cancel.check()?;
            if row.contains_key(self.left)
                || row.contains_key(self.right)
                || row.contains_key(INTERNAL_WEIGHT_KEY)
                || self
                    .carried
                    .iter()
                    .any(|name| !matches!(row.get(*name), Some(Value::Str(_))))
            {
                return Ok(false);
            }
            let Some(Value::List(values)) = row.get(self.list) else {
                return Ok(false);
            };
            for (at, value) in values.iter().enumerate() {
                if at & 1023 == 0 {
                    ex.cancel.check()?;
                }
                if !matches!(value, Value::Str(_)) {
                    return Ok(false);
                }
            }
        }
        Ok(true)
    }

    fn run(
        &self,
        ex: &Executor,
        ev: &Evaluator,
        seeds: &[Row],
    ) -> CypherResult<(Vec<String>, Vec<Row>)> {
        let plan = AggPlan::new(self.output, self.order, ex.dialect)?;
        let mut groups: IndexMap<Vec<Key>, GroupAcc> = IndexMap::new();
        for seed in seeds {
            ex.cancel.check()?;
            let Value::List(values) = &seed[self.list] else {
                unreachable!("proved inputs")
            };
            let mut row = Row::with_capacity(self.projection.len() + 1);
            for item in self.projection {
                let Expr::Variable(name) = &item.expr else {
                    unreachable!("identity projection")
                };
                let value = if name == self.left || name == self.right {
                    Value::Null
                } else {
                    seed[name].clone()
                };
                row.insert(name.clone(), value);
            }
            if let Some(provenance) = seed.get(INTERNAL_PROVENANCE_KEY) {
                row.insert(INTERNAL_PROVENANCE_KEY.to_string(), provenance.clone());
            }
            for left in values.iter() {
                ex.tick()?;
                *row.get_mut(self.left).unwrap() = left.clone();
                for right in values.iter() {
                    ex.tick()?;
                    *row.get_mut(self.right).unwrap() = right.clone();
                    // Do not replace this with positional i<j: collected values are
                    // in encounter order, not Cypher's UTF-16 string ordering.
                    if ev.eval(self.predicate, &row)?.as_bool() != Some(true) {
                        continue;
                    }
                    let mut key = Vec::with_capacity(plan.group_exprs.len());
                    let mut values = Vec::with_capacity(plan.group_exprs.len());
                    for expr in &plan.group_exprs {
                        let value = ev.eval(expr, &row)?;
                        key.push(value_key(&value));
                        values.push(value);
                    }
                    let acc = groups.entry(key).or_insert_with(|| GroupAcc {
                        first_row: row.clone(),
                        group_values: values,
                        agg_states: plan.states(),
                    });
                    for (at, agg) in plan.aggs.iter().enumerate() {
                        let value = match &agg.arg {
                            Some(expr) => ev.eval(expr, &row)?,
                            None => star_input(agg, &row),
                        };
                        acc.agg_states[at].push(value, 1);
                    }
                    merge_provenance(&mut acc.first_row, &row);
                }
            }
        }
        ex.cancel.check()?;
        let rows = finalize_groups(ev, &plan, self.output, groups)?;
        ex.cancel.check()?;
        Ok((item_names(self.output), rows))
    }
}

/// Bounded maintenance retains the original stage order and exact work charges,
/// including budgets inherited through a cancellation-token parent.
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

pub(super) fn try_execute(
    ex: &Executor,
    ev: &Evaluator,
    clauses: &[Clause],
    rows: &[Row],
) -> CypherResult<Option<(Vec<String>, Vec<Row>)>> {
    #[cfg(test)]
    if DISABLED.with(|disabled| disabled.get()) {
        return Ok(None);
    }
    if super::super::optimizations_disabled() || has_work_budget(ex) {
        return Ok(None);
    }
    let Some(plan) = PairPlan::build(clauses) else {
        return Ok(None);
    };
    if !plan.inputs_are_total(ex, rows)? {
        return Ok(None);
    }
    #[cfg(test)]
    HITS.with(|hits| hits.set(hits.get() + 1));
    if let Some(result) = compact::try_execute(ex, &plan, clauses, rows)? {
        return Ok(Some(result));
    }
    plan.run(ex, ev, rows).map(Some)
}

#[cfg(test)]
thread_local! {
    static DISABLED: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
    static HITS: std::cell::Cell<usize> = const { std::cell::Cell::new(0) };
}

#[cfg(test)]
#[path = "unwind_groups_tests.rs"]
mod tests;
