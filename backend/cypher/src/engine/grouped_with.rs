//! A proven CSR aggregate followed by the original WITH filter and projection.
//!
//! Admission is deliberately limited to a direct CallSite string group and
//! count(*), so replacing the grouped input cannot suppress argument errors. An
//! unproved source declines the whole plan before any grouped rows are built.
use super::*;
use crate::ast::CmpOp;
use graphite_storage::node::CALL_SITE_PROPERTY_NAMES;

struct GroupedWithPlan<'a> {
    matched: &'a Clause,
    with_items: &'a [ReturnItem],
    predicate: Option<&'a Expr>,
    return_items: &'a [ReturnItem],
    property: usize,
    limit: usize,
}

impl<'a> GroupedWithPlan<'a> {
    fn build(clauses: &'a [Clause]) -> Option<Self> {
        let [matched @ Clause::Match {
            patterns,
            optional: false,
            where_clause: None,
        }, Clause::With {
            distinct: false,
            items: Some(with_items),
            where_clause: predicate,
        }, Clause::Return {
            distinct: false,
            items: Some(return_items),
        }, Clause::Limit(Expr::Literal(Literal::Int(limit)))] = clauses
        else {
            return None;
        };
        let limit = usize::try_from(*limit).ok()?;
        let [pattern] = patterns.as_slice() else {
            return None;
        };
        let [node] = pattern.nodes.as_slice() else {
            return None;
        };
        if pattern.path_variable.is_some()
            || !pattern.rels.is_empty()
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
        if is_internal_key(variable) {
            return None;
        }
        let [group, count] = with_items.as_slice() else {
            return None;
        };
        let Expr::Property { expr, key } = &group.expr else {
            return None;
        };
        if !matches!(expr.as_ref(), Expr::Variable(v) if v == variable)
            || !matches!(count.expr, Expr::CountStar)
        {
            return None;
        }
        let property = CALL_SITE_PROPERTY_NAMES
            .iter()
            .position(|field| *field == key)?;
        let group_alias = group.alias.as_deref()?;
        let count_alias = count.alias.as_deref()?;
        if group_alias.is_empty()
            || count_alias.is_empty()
            || group_alias == count_alias
            || group_alias == variable
            || count_alias == variable
            || is_internal_key(group_alias)
            || is_internal_key(count_alias)
        {
            return None;
        }
        if let Some(predicate) = predicate {
            if !matches!(predicate, Expr::Comparison { op: CmpOp::Gt, left, right }
                if matches!(left.as_ref(), Expr::Variable(v) if v == count_alias)
                && matches!(right.as_ref(), Expr::Literal(Literal::Int(0..))))
            {
                return None;
            }
        }
        let [group_return, count_return] = return_items.as_slice() else {
            return None;
        };
        if group_return.alias.is_some()
            || count_return.alias.is_some()
            || !matches!(&group_return.expr, Expr::Variable(v) if v == group_alias)
            || !matches!(&count_return.expr, Expr::Variable(v) if v == count_alias)
        {
            return None;
        }
        Some(Self {
            matched,
            with_items,
            predicate: predicate.as_ref(),
            return_items,
            property,
            limit,
        })
    }

    fn run(&self, ex: &Executor) -> CypherResult<Option<QueryResult>> {
        // Single-source queries retain their existing query strategy.
        if ex.sources.len() < 2 {
            return Ok(None);
        }
        // A positive count cannot overflow when the complete input cardinality
        // fits i64. Otherwise keep generic aggregation's original error boundary.
        let total = ex.sources.iter().try_fold(0i64, |total, source| {
            total.checked_add(i64::try_from(source.graph.count_by_tag(TAG_CALL_SITE_NODE)).ok()?)
        });
        if total.is_none() {
            return Ok(None);
        }
        for source in &ex.sources {
            ex.cancel.check()?;
            if source.graph.count_by_tag(TAG_CALL_SITE_NODE) == 0 {
                continue;
            }
            if source
                .graph
                .validated_ordered_call_site(self.property, || ex.cancel.check())?
                .is_none()
            {
                return Ok(None);
            }
        }
        // All sources are proven first; the shared group fast path therefore
        // cannot take its raw fallback or alter generic malformed-input behavior.
        let grouped_clauses = [
            self.matched.clone(),
            Clause::Return {
                distinct: false,
                items: Some(self.with_items.to_vec()),
            },
        ];
        let Some(grouped) =
            super::super::fastpath::grouped_call_site_property(ex, &grouped_clauses)?
        else {
            return Ok(None);
        };
        let ev = Evaluator::new(ex, &ex.params);
        let rows = if let Some(predicate) = self.predicate {
            let mut filtered = Vec::new();
            for row in grouped.rows {
                ex.tick()?;
                if ev.eval(predicate, &row)?.as_bool() == Some(true) {
                    filtered.push(row);
                }
            }
            filtered
        } else {
            grouped.rows
        };
        // Filtering occurs after global counts and complete provenance merge.
        // The original projector owns column rendering, aliases and hidden keys.
        let (columns, mut rows) = project(&ev, rows, Some(self.return_items), false, None)?;
        rows.truncate(self.limit);
        ex.cancel.check()?;
        Ok(Some(QueryResult {
            columns,
            rows,
            compact: None,
            more: false,
        }))
    }
}

#[cfg(test)]
thread_local! { static DISABLED: std::cell::Cell<bool> = const { std::cell::Cell::new(false) }; }

pub(super) fn try_execute(ex: &Executor, clauses: &[Clause]) -> CypherResult<Option<QueryResult>> {
    #[cfg(test)]
    if DISABLED.with(std::cell::Cell::get) {
        return Ok(None);
    }
    let Some(plan) = GroupedWithPlan::build(clauses) else {
        return Ok(None);
    };
    plan.run(ex)
}

#[cfg(test)]
#[path = "grouped_with_tests.rs"]
mod tests;
