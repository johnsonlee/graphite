//! Certified unsorted DISTINCT: select bounded values before allocating rows.
use super::*;
use crate::ast::Literal;
use std::collections::{BinaryHeap, HashSet};

const MAX_OUTPUT_ROWS: usize = 65_536;

struct Plan {
    field: usize,
    column: String,
    limit: usize,
}

impl Plan {
    fn build(clauses: &[Clause]) -> Option<Self> {
        let [Clause::Match {
            patterns,
            optional: false,
            where_clause: None,
        }, Clause::Return {
            distinct: true,
            items: Some(items),
        }, Clause::Limit(Expr::Literal(Literal::Int(limit)))] = clauses
        else {
            return None;
        };
        let [pattern] = patterns.as_slice() else {
            return None;
        };
        let variable = single_node_variable(pattern)?;
        if variable.is_empty() || super::super::pipeline::is_internal_key(&variable) {
            return None;
        }
        let node = &pattern.nodes[0];
        if !matches!(resolve_node_class(&node.labels), NodeClass::Tags(tags) if tags.as_slice() == [TAG_CALL_SITE_NODE])
            || node.labels.iter().any(|label| {
                !super::super::props::label_tags(label)
                    .is_some_and(|tags| tags.contains(&TAG_CALL_SITE_NODE))
            })
        {
            return None;
        }
        let [item] = items.as_slice() else {
            return None;
        };
        let Expr::Property { expr, key } = &item.expr else {
            return None;
        };
        if !matches!(expr.as_ref(), Expr::Variable(v) if v == &variable) {
            return None;
        }
        let field = CALL_SITE_FIELDS.iter().position(|field| *field == key)?;
        let column = item
            .alias
            .clone()
            .unwrap_or_else(|| to_cypher_string(&item.expr));
        if column.is_empty() || super::super::pipeline::is_internal_key(&column) {
            return None;
        }
        let limit = usize::try_from(*limit).ok()?;
        if limit > MAX_OUTPUT_ROWS {
            return None;
        }
        Some(Self {
            field,
            column,
            limit,
        })
    }

    fn run(&self, ex: &Executor) -> CypherResult<Option<QueryResult>> {
        if ex.sources.len() < 2 {
            return Ok(None);
        }
        ex.cancel.check()?;
        // No selected prefix may hide a later malformed source, even at LIMIT 0.
        for source in &ex.sources {
            ex.cancel.check()?;
            if source.graph.count_by_tag(TAG_CALL_SITE_NODE) != 0
                && source
                    .graph
                    .validated_ordered_call_site(self.field, || ex.cancel.check())?
                    .is_none()
            {
                return Ok(None);
            }
        }
        let mut selected = Vec::new();
        let mut seen: HashSet<&str> = HashSet::new();
        for source in &ex.sources {
            ex.cancel.check()?;
            let remaining = self.limit - selected.len();
            if remaining == 0 {
                break;
            }
            if source.graph.count_by_tag(TAG_CALL_SITE_NODE) == 0 {
                continue;
            }
            let Some(index) = source.graph.call_site_index() else {
                return Ok(None);
            };
            let Some(groups) = index.property_values(self.field, false) else {
                return Ok(None);
            };
            let mut winners = BinaryHeap::new();
            for (sid, nodes) in groups {
                ex.tick()?;
                let value = source.graph.str(sid);
                if seen.contains(value) {
                    continue;
                }
                let Some(first) = nodes.first() else {
                    return Ok(None);
                };
                // Canonical dictionary + disjoint complete CSR groups prove unique
                // values and first IDs within this source. Only prior-source values
                // need deduplication; a bounded heap restores encounter order.
                if winners.len() < remaining {
                    winners.push((first, value));
                } else if first < winners.peek().unwrap().0 {
                    *winners.peek_mut().unwrap() = (first, value);
                }
            }
            let mut ordered = Vec::with_capacity(winners.len());
            while let Some((_, value)) = winners.pop() {
                ex.tick()?;
                ordered.push(value);
            }
            for value in ordered.into_iter().rev() {
                ex.tick()?;
                seen.insert(value);
                selected.push(value);
            }
        }
        let mut rows = Vec::with_capacity(selected.len());
        for value in selected {
            ex.tick()?;
            let mut row = Row::from([(self.column.clone(), Value::str(value))]);
            #[cfg(test)]
            MATERIALIZED.with(|count| count.set(count.get() + 1));
            if ex.cross {
                for source in &ex.sources {
                    ex.cancel.check()?;
                    if source.graph.count_by_tag(TAG_CALL_SITE_NODE) == 0 {
                        continue;
                    }
                    let Some(sid) = source.graph.strings().index_of(value) else {
                        continue;
                    };
                    let Some(index) = source.graph.call_site_index() else {
                        return Ok(None);
                    };
                    if index
                        .postings(self.field, sid)
                        .is_some_and(|nodes| !nodes.is_empty())
                    {
                        add_provenance_id(&mut row, source.id.clone());
                    }
                }
            }
            rows.push(row);
        }
        ex.cancel.check()?;
        Ok(Some(QueryResult {
            columns: vec![self.column.clone()],
            rows,
            compact: None,
            more: false,
        }))
    }
}

pub(super) fn try_execute(ex: &Executor, clauses: &[Clause]) -> CypherResult<Option<QueryResult>> {
    let Some(plan) = Plan::build(clauses) else {
        return Ok(None);
    };
    plan.run(ex)
}

#[cfg(test)]
thread_local! { static MATERIALIZED: std::cell::Cell<usize> = const { std::cell::Cell::new(0) }; }
#[cfg(test)]
#[path = "bounded_distinct_tests.rs"]
mod tests;
