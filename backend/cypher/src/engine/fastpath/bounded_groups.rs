//! Complete certified counts, with rows/provenance allocated only for bounded output.
use super::*;
use std::collections::{BinaryHeap, HashMap};

const MAX_GROUPED_OUTPUT_ROWS: usize = 65_536;

#[derive(Clone, Copy)]
struct Group {
    count: i64,
    first: (usize, u32),
}

/// A direct CallSite string group and count(*) have total arguments once every
/// source's complete property/index/dictionary proof succeeds. Keep their global
/// state compact, select the requested complete-count winners, and only then
/// materialize the ordinary row format. A declined proof leaves the raw path intact.
pub(in crate::engine) fn bounded_call_site_groups(
    ex: &Executor,
    field: usize,
    columns: &[String],
    limit: usize,
    minimum_exclusive: Option<i64>,
    descending_count: bool,
) -> CypherResult<Option<QueryResult>> {
    #[cfg(test)]
    if DISABLED.with(std::cell::Cell::get) {
        return Ok(None);
    }
    if field >= CALL_SITE_FIELDS.len()
        || limit > MAX_GROUPED_OUTPUT_ROWS
        || minimum_exclusive.is_some_and(|n| n < 0)
        || columns.len() != 2
        || columns[0] == columns[1]
        || columns
            .iter()
            .any(|name| name.is_empty() || super::super::pipeline::is_internal_key(name))
    {
        return Ok(None);
    }
    ex.cancel.check()?;
    // This also proves that no global positive group count can overflow i64.
    let mut total = 0i64;
    for source in &ex.sources {
        ex.cancel.check()?;
        let Ok(count) = i64::try_from(source.graph.count_by_tag(TAG_CALL_SITE_NODE)) else {
            return Ok(None);
        };
        let Some(sum) = total.checked_add(count) else {
            return Ok(None);
        };
        total = sum;
    }
    for source in &ex.sources {
        ex.cancel.check()?;
        if source.graph.count_by_tag(TAG_CALL_SITE_NODE) != 0
            && source
                .graph
                .validated_ordered_call_site(field, || ex.cancel.check())?
                .is_none()
        {
            return Ok(None);
        }
    }
    let mut groups: HashMap<&str, Group> = HashMap::new();
    for (source_number, source) in ex.sources.iter().enumerate() {
        ex.cancel.check()?;
        if source.graph.count_by_tag(TAG_CALL_SITE_NODE) == 0 {
            continue;
        }
        let Some(index) = source.graph.call_site_index() else {
            return Ok(None);
        };
        let Some(values) = index.property_values(field, false) else {
            return Ok(None);
        };
        for (sid, nodes) in values {
            ex.tick()?;
            let Some(first_node) = nodes.first() else {
                return Ok(None);
            };
            let Ok(count) = i64::try_from(nodes.len()) else {
                return Ok(None);
            };
            let value = source.graph.str(sid);
            let first = (source_number, first_node);
            match groups.entry(value) {
                std::collections::hash_map::Entry::Occupied(mut entry) => {
                    let group = entry.get_mut();
                    let Some(count) = group.count.checked_add(count) else {
                        return Ok(None);
                    };
                    group.count = count;
                    group.first = group.first.min(first);
                }
                std::collections::hash_map::Entry::Vacant(entry) => {
                    entry.insert(Group { count, first });
                }
            }
        }
    }
    // A rank is unique: the complete CSR proof makes node IDs ascending and
    // disjoint, so two distinct values cannot have the same first source/node.
    // This restores raw first-encounter order without sorting each source's groups.
    // A max heap holds the worst selected rank; smaller ranks are better.
    let mut winners = BinaryHeap::new();
    for (value, group) in groups {
        ex.tick()?;
        if limit == 0 || minimum_exclusive.is_some_and(|min| group.count <= min) {
            continue;
        }
        let rank = (
            if descending_count { -group.count } else { 0 },
            group.first.0,
            group.first.1,
        );
        let winner = (rank, value, group.count);
        if winners.len() < limit {
            winners.push(winner);
        } else if rank < winners.peek().unwrap().0 {
            *winners.peek_mut().unwrap() = winner;
        }
    }
    let mut selected = Vec::with_capacity(winners.len());
    while let Some(winner) = winners.pop() {
        ex.tick()?;
        selected.push(winner);
    }
    let mut rows = Vec::with_capacity(selected.len());
    for (_, value, count) in selected.into_iter().rev() {
        ex.tick()?;
        let mut row = Row::from([
            (columns[0].clone(), Value::str(value)),
            (columns[1].clone(), Value::Int(count)),
        ]);
        if ex.cross {
            // Complete provenance is needed only for surviving groups. Presence
            // lookup uses already certified dictionaries and CSR group headers;
            // no posting run or omitted row is enumerated.
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
                    .postings(field, sid)
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
        columns: columns.to_vec(),
        rows,
        compact: None,
        more: false,
    }))
}

#[cfg(test)]
thread_local! { pub(super) static DISABLED: std::cell::Cell<bool> = const { std::cell::Cell::new(false) }; }

#[cfg(test)]
#[path = "bounded_groups_tests.rs"]
mod tests;
