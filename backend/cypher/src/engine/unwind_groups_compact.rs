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
type Winner = (Reverse<i64>, u32, u32, u32, u32, u64, u64, usize);

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
    distinct: Vec<usize>,
    provenance: Vec<u64>,
    provenance_names: Vec<&'a str>,
    provenance_high: Vec<Vec<usize>>,
    distinct_count: usize,
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

// This threshold chooses a representation, never query admission. Wide sparse
// dictionaries must not reserve dictionary-size words for every pair group.
const DENSE_HIGH_WORDS: usize = 8;

enum MaskStorage {
    Dense(Vec<u64>),
    Sparse(HashMap<(usize, usize), u64>),
}

struct ExtraMasks {
    storage: MaskStorage,
    distinct_words: usize,
    provenance_words: usize,
}

impl ExtraMasks {
    fn new(distinct: usize, provenance: usize) -> Self {
        let distinct_words = distinct.div_ceil(64).saturating_sub(1);
        let provenance_words = provenance.div_ceil(64).saturating_sub(1);
        let storage = if distinct_words + provenance_words <= DENSE_HIGH_WORDS {
            MaskStorage::Dense(Vec::new())
        } else {
            MaskStorage::Sparse(HashMap::new())
        };
        Self {
            storage,
            distinct_words,
            provenance_words,
        }
    }

    fn allocate(&mut self, group: usize) {
        if let MaskStorage::Dense(words) = &mut self.storage {
            words.resize(
                (group + 1) * (self.distinct_words + self.provenance_words),
                0,
            );
        }
    }

    fn insert(&mut self, group: usize, word: usize, bit: u64) {
        match &mut self.storage {
            MaskStorage::Dense(words) => {
                words[group * (self.distinct_words + self.provenance_words) + word] |= bit;
            }
            MaskStorage::Sparse(words) => {
                *words.entry((group, word)).or_default() |= bit;
            }
        }
    }

    fn word(&self, group: usize, word: usize) -> u64 {
        match &self.storage {
            MaskStorage::Dense(words) => {
                words[group * (self.distinct_words + self.provenance_words) + word]
            }
            MaskStorage::Sparse(words) => words.get(&(group, word)).copied().unwrap_or(0),
        }
    }

    fn distinct_count(&self, ex: &Executor, inline: u64, group: usize) -> CypherResult<u64> {
        let mut count = u64::from(inline.count_ones());
        for word in 0..self.distinct_words {
            if word & 1023 == 0 {
                ex.cancel.check()?;
            }
            count += u64::from(self.word(group, word).count_ones());
        }
        Ok(count)
    }
}

fn intern<'a>(ids: &mut HashMap<&'a str, usize>, value: &'a str) -> usize {
    let next = ids.len();
    *ids.entry(value).or_insert(next)
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
        provenance_high: Vec::with_capacity(rows.len()),
        distinct_count: 0,
    };
    for row in rows {
        ex.cancel.check()?;
        let Some(Value::Str(value)) = row.get(distinct_name) else {
            return Ok(None);
        };
        prepared
            .distinct
            .push(intern(&mut distinct_ids, value.as_ref()));
        let mut provenance = 0u64;
        let mut provenance_high = Vec::new();
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
                    ex.cancel.check()?;
                    let id = intern(&mut provenance_ids, value);
                    if provenance_ids.len() > old_len {
                        prepared.provenance_names.push(value);
                    }
                    if id < 64 {
                        provenance |= 1u64 << id;
                    } else {
                        provenance_high.push(id);
                    }
                }
            }
            // Missing and present-but-empty are observably different in the old
            // projector. The streaming parent preserves empty/malformed lists.
            Some(_) => return Ok(None),
        }
        prepared.provenance.push(provenance);
        prepared.provenance_high.push(provenance_high);
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
    prepared.distinct_count = distinct_ids.len();
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
    for (index, ((left, right), group)) in groups.into_iter().enumerate() {
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
            index,
        );
        if winners.len() < limit {
            winners.push(winner);
        } else if winner < *winners.peek().unwrap() {
            *winners.peek_mut().unwrap() = winner;
        }
    }
    Ok(winners)
}

fn accumulate<const WIDE: bool>(
    ex: &Executor,
    prepared: &Prepared<'_>,
    extra: &mut ExtraMasks,
) -> CypherResult<IndexMap<Pair, CompactGroup>> {
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
                if !WIDE {
                    let group = groups.entry((left, right)).or_default();
                    group.count.add(1);
                    group.distinct |= 1u64 << prepared.distinct[source];
                    group.provenance |= prepared.provenance[source];
                    continue;
                }
                let entry = groups.entry((left, right));
                let group_index = entry.index();
                let group = entry.or_insert_with(|| {
                    extra.allocate(group_index);
                    CompactGroup::default()
                });
                group.count.add(1);
                let distinct = prepared.distinct[source];
                if distinct < 64 {
                    group.distinct |= 1u64 << distinct;
                } else {
                    extra.insert(group_index, distinct / 64 - 1, 1u64 << (distinct % 64));
                }
                group.provenance |= prepared.provenance[source];
                for (at, &id) in prepared.provenance_high[source].iter().enumerate() {
                    if at & 1023 == 0 {
                        ex.cancel.check()?;
                    }
                    extra.insert(
                        group_index,
                        extra.distinct_words + id / 64 - 1,
                        1u64 << (id % 64),
                    );
                }
            }
        }
    }
    Ok(groups)
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
    let mut extra = ExtraMasks::new(prepared.distinct_count, prepared.provenance_names.len());
    let groups = if extra.distinct_words == 0 && extra.provenance_words == 0 {
        accumulate::<false>(ex, &prepared, &mut extra)?
    } else {
        accumulate::<true>(ex, &prepared, &mut extra)?
    };
    ex.cancel.check()?;
    let winners = select(ex, groups, &prepared.ranks, limit)?;
    let columns = item_names(plan.output);
    let mut output = Vec::with_capacity(winners.len());
    for (Reverse(count), _, _, left, right, distinct, provenance, offset) in
        winners.into_sorted_vec()
    {
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
                Value::Int(extra.distinct_count(ex, distinct, offset)? as i64),
            ),
        ]);
        for (id, name) in prepared.provenance_names.iter().enumerate() {
            if id & 1023 == 0 {
                ex.cancel.check()?;
            }
            let word = if id < 64 {
                provenance
            } else {
                extra.word(offset, extra.distinct_words + id / 64 - 1)
            };
            if word & (1u64 << (id % 64)) != 0 {
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
