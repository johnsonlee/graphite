//! Compact counts for the proven pair tail with a fully ordered bounded output.
//!
//! This is a narrower child of the streaming plan. Unsupported dictionaries or
//! provenance return to that plan before any pair is consumed. Every group count
//! finishes, including losing groups and LIMIT 0, before any result row is built.
use super::super::aggregate_state::DeferredCount;
use super::*;
use crate::semantics::compare_utf16;
use std::cmp::Reverse;
use std::collections::{BinaryHeap, HashMap};

const MAX_OUTPUT_ROWS: usize = 65_536;

type Pair = (u32, u32);
// Smallest rank is best; the max heap exposes the worst retained complete count.
// Reverse avoids negating i64::MIN after the established release wrapping count.
type Winner = (Reverse<i64>, u32, u32, u32, u32, u64, u64);

#[derive(Default)]
struct CompactGroup {
    count: DeferredCount,
    distinct: u64,
    provenance: u64,
}

struct Prepared<'a> {
    strings: Vec<&'a str>,
    ranks: Vec<u32>,
    lists: Vec<Vec<u32>>,
    distinct: Vec<u64>,
    provenance: Vec<u64>,
    provenance_names: Vec<&'a str>,
}

fn capacity(plan: &PairPlan<'_>, clauses: &[Clause]) -> Option<usize> {
    let [_, _, _, _, Clause::OrderBy(order), Clause::Limit(Expr::Literal(Literal::Int(limit)))] =
        clauses
    else {
        return None;
    };
    let names = item_names(plan.output);
    if order.len() != 3 {
        return None;
    }
    for (item, (name, descending)) in
        order
            .iter()
            .zip([(&names[2], true), (&names[0], false), (&names[1], false)])
    {
        if item.descending != descending || !matches!(&item.expr, Expr::Variable(v) if v == name) {
            return None;
        }
    }
    let limit = usize::try_from(*limit).ok()?;
    (limit <= MAX_OUTPUT_ROWS).then_some(limit)
}

fn mask<'a>(ids: &mut HashMap<&'a str, u8>, value: &'a str) -> Option<u64> {
    let next = ids.len();
    let id = match ids.get(value) {
        Some(id) => *id,
        None if next < 64 => {
            ids.insert(value, next as u8);
            next as u8
        }
        None => return None,
    };
    Some(1u64 << id)
}

fn prepare<'a>(
    ex: &Executor,
    plan: &PairPlan<'_>,
    rows: &'a [Row],
) -> CypherResult<Option<Prepared<'a>>> {
    let Expr::FunctionCall { args, .. } = &plan.output[3].expr else {
        unreachable!("pair admission")
    };
    let Expr::Variable(distinct_name) = &args[0] else {
        unreachable!("pair admission")
    };
    let mut string_ids: HashMap<&str, u32> = HashMap::new();
    let mut distinct_ids = HashMap::new();
    let mut provenance_ids = HashMap::new();
    let mut prepared = Prepared {
        strings: Vec::new(),
        ranks: Vec::new(),
        lists: Vec::with_capacity(rows.len()),
        distinct: Vec::with_capacity(rows.len()),
        provenance: Vec::with_capacity(rows.len()),
        provenance_names: Vec::new(),
    };
    for row in rows {
        ex.cancel.check()?;
        let Some(Value::Str(value)) = row.get(distinct_name) else {
            return Ok(None);
        };
        let Some(bit) = mask(&mut distinct_ids, value.as_ref()) else {
            return Ok(None);
        };
        prepared.distinct.push(bit);
        let mut provenance = 0u64;
        match row.get(INTERNAL_PROVENANCE_KEY) {
            None => {}
            Some(Value::List(values)) if !values.is_empty() => {
                let mut previous: Option<&str> = None;
                for value in values.iter() {
                    let Value::Str(value) = value else {
                        return Ok(None);
                    };
                    let value = value.as_ref();
                    // The generic first row preserves an existing provenance list.
                    // Decline noncanonical lists rather than normalizing its content.
                    if previous.is_some_and(|previous| previous >= value) {
                        return Ok(None);
                    }
                    previous = Some(value);
                    let old_len = provenance_ids.len();
                    let Some(bit) = mask(&mut provenance_ids, value) else {
                        return Ok(None);
                    };
                    if provenance_ids.len() > old_len {
                        prepared.provenance_names.push(value);
                    }
                    provenance |= bit;
                }
            }
            // Missing and present-but-empty are observably different in the old
            // projector. The streaming parent preserves empty/malformed lists.
            Some(_) => return Ok(None),
        }
        prepared.provenance.push(provenance);
        let Some(Value::List(values)) = row.get(plan.list) else {
            return Ok(None);
        };
        let mut ids = Vec::with_capacity(values.len());
        for (at, value) in values.iter().enumerate() {
            if at & 1023 == 0 {
                ex.cancel.check()?;
            }
            let Value::Str(value) = value else {
                return Ok(None);
            };
            let value = value.as_ref();
            let id = match string_ids.get(value) {
                Some(id) => *id,
                None => {
                    let Ok(id) = u32::try_from(prepared.strings.len()) else {
                        return Ok(None);
                    };
                    prepared.strings.push(value);
                    string_ids.insert(value, id);
                    id
                }
            };
            ids.push(id);
        }
        prepared.lists.push(ids);
    }
    let mut ordered: Vec<usize> = (0..prepared.strings.len()).collect();
    ordered.sort_by(|&a, &b| compare_utf16(prepared.strings[a], prepared.strings[b]));
    ex.cancel.check()?;
    prepared.ranks.resize(prepared.strings.len(), 0);
    for (rank, id) in ordered.into_iter().enumerate() {
        prepared.ranks[id] = rank as u32;
    }
    Ok(Some(prepared))
}

fn select(
    ex: &Executor,
    groups: IndexMap<Pair, CompactGroup>,
    ranks: &[u32],
    limit: usize,
) -> CypherResult<BinaryHeap<Winner>> {
    let mut winners = BinaryHeap::new();
    for ((left, right), group) in groups {
        ex.tick()?;
        // This must precede either LIMIT 0 or heap rejection. Preserve the same
        // first-group overflow boundary as finalize_groups, not a winning subset.
        let count = group.count.finish();
        if limit == 0 {
            continue;
        }
        let winner = (
            Reverse(count),
            ranks[left as usize],
            ranks[right as usize],
            left,
            right,
            group.distinct,
            group.provenance,
        );
        if winners.len() < limit {
            winners.push(winner);
        } else if winner < *winners.peek().unwrap() {
            *winners.peek_mut().unwrap() = winner;
        }
    }
    Ok(winners)
}

pub(super) fn try_execute(
    ex: &Executor,
    plan: &PairPlan<'_>,
    clauses: &[Clause],
    rows: &[Row],
) -> CypherResult<Option<(Vec<String>, Vec<Row>)>> {
    #[cfg(test)]
    if DISABLED.with(|disabled| disabled.get()) {
        return Ok(None);
    }
    let Some(limit) = capacity(plan, clauses) else {
        return Ok(None);
    };
    let Some(prepared) = prepare(ex, plan, rows)? else {
        return Ok(None);
    };
    #[cfg(test)]
    HITS.with(|hits| hits.set(hits.get() + 1));
    let mut groups: IndexMap<Pair, CompactGroup> = IndexMap::new();
    for (source, ids) in prepared.lists.iter().enumerate() {
        ex.cancel.check()?;
        for &left in ids {
            ex.tick()?;
            for &right in ids {
                ex.tick()?;
                if prepared.ranks[left as usize] >= prepared.ranks[right as usize] {
                    continue;
                }
                let group = groups.entry((left, right)).or_default();
                group.count.add(1);
                group.distinct |= prepared.distinct[source];
                group.provenance |= prepared.provenance[source];
            }
        }
    }
    ex.cancel.check()?;
    let winners = select(ex, groups, &prepared.ranks, limit)?;
    let columns = item_names(plan.output);
    let mut output = Vec::with_capacity(winners.len());
    for (Reverse(count), _, _, left, right, distinct, provenance) in winners.into_sorted_vec() {
        ex.tick()?;
        let mut row = Row::from([
            (
                columns[0].clone(),
                Value::str(prepared.strings[left as usize]),
            ),
            (
                columns[1].clone(),
                Value::str(prepared.strings[right as usize]),
            ),
            (columns[2].clone(), Value::Int(count)),
            (
                columns[3].clone(),
                Value::Int(i64::from(distinct.count_ones())),
            ),
        ]);
        for (id, name) in prepared.provenance_names.iter().enumerate() {
            if provenance & (1u64 << id) != 0 {
                add_provenance_id(&mut row, Arc::from(*name));
            }
        }
        #[cfg(test)]
        MATERIALIZED.with(|count| count.set(count.get() + 1));
        output.push(row);
    }
    ex.cancel.check()?;
    Ok(Some((columns, output)))
}

#[cfg(test)]
thread_local! {
    static DISABLED: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
    static HITS: std::cell::Cell<usize> = const { std::cell::Cell::new(0) };
    static MATERIALIZED: std::cell::Cell<usize> = const { std::cell::Cell::new(0) };
}

#[cfg(test)]
#[path = "unwind_groups_compact_tests.rs"]
mod tests;
