//! String-predicate pushdown for single-node label scans.
//!
//! The wide "find anything mentioning X" queries that dominate Explorer latency are
//! disjunctions of `CONTAINS` / `STARTS WITH` / `=` over a handful of CallSite string
//! properties. Evaluating those per node means decoding a million records.
//!
//! Instead we exploit the shared string dictionary: every string property is stored as
//! an index into `graph.strings`, and there are far fewer distinct strings than nodes
//! (85K vs 1.07M on a typical graph). Matching the predicate against the dictionary
//! yields a set of string ids; nodes are then found from those ids.
//!
//! How both halves are done depends on whether the graph directory carries the
//! persisted CallSite accelerator (`graph.callsite-string-index`):
//!
//! * **With it** — the literal's lowercase trigrams give a short candidate list of
//!   string ids, which is verified exactly against the dictionary; the per-property
//!   CSR then maps each surviving id straight to the CallSite nodes carrying it. Cost
//!   is proportional to what actually matches, so a term that matches nothing costs
//!   almost nothing and its graph is skipped outright. This matters across many
//!   graphs, where a per-graph pass over the dictionary is a floor no term can escape.
//!
//! * **Without it** — the dictionary is scanned into a bitset of matching ids and
//!   candidate nodes are swept, reading four raw `i32`s per record and testing bits.
//!   No record decoding, and the sweep parallelises cleanly.
//!
//! The accelerator is also declined when a term is so dense that walking its postings
//! would cost more than the sweep.
//!
//! The plan is only a *pre-filter*: survivors are re-checked against the full WHERE
//! clause, so an imprecise plan can never change results.

use super::pipeline::{add_provenance, Row};
use super::props::label_tags;
use super::Executor;
use crate::ast::{Expr, Pattern, StrOp};
use crate::eval::Evaluator;
use crate::value::{NodeRef, SourceIdx, Value};
use crate::CypherResult;
use graphite_storage::node::{
    read_call_site_strings, StrId, TAG_ANNOTATION_NODE, TAG_CALL_SITE_NODE,
};
use graphite_storage::Graph;
use rayon::prelude::*;

/// Properties readable straight out of a `CallSiteNode` record without decoding it.
const CALL_SITE_PROPS: [&str; 4] = ["caller_class", "caller_name", "callee_class", "callee_name"];

#[derive(Clone, Copy, PartialEq, Eq)]
enum Transform {
    None,
    Lowercase,
}

/// Pushdown operators. `Equals` has no `StrOp` counterpart: equality arrives as a
/// comparison expression, not a string predicate.
#[derive(Clone, Copy, PartialEq, Eq)]
enum PushOp {
    Equals,
    Contains,
    StartsWith,
    EndsWith,
}

#[derive(Clone)]
struct StringPredicate {
    property: &'static str,
    op: PushOp,
    literal: String,
    transform: Transform,
    /// The literal's trigrams, computed once for the query rather than once per graph.
    /// They depend only on the literal, and there are sixty-four graphs.
    trigrams: std::sync::Arc<Option<Vec<i32>>>,
    /// The literal's trigram signature, for rejecting a candidate string without
    /// decoding it.
    signature: u64,
    /// Index of the distinct dictionary test this predicate performs. Predicates that
    /// differ only in which property they read share one, so a graph resolves each
    /// literal once however many properties test it.
    test: usize,
}

impl StringPredicate {
    /// Test against a string the caller has already transformed.
    fn matches_raw(&self, candidate: &str) -> bool {
        match self.op {
            PushOp::Equals => candidate == self.literal,
            PushOp::Contains => candidate.contains(&self.literal),
            PushOp::StartsWith => candidate.starts_with(&self.literal),
            PushOp::EndsWith => candidate.ends_with(&self.literal),
        }
    }

    /// True when two predicates select the same dictionary entries. The property is
    /// deliberately not part of this: it decides which records to look in, not which
    /// strings match.
    fn same_test(&self, other: &StringPredicate) -> bool {
        self.op == other.op && self.transform == other.transform && self.literal == other.literal
    }

    /// True when the dictionary's sort order lets us find matches without scanning.
    /// Only untransformed operators qualify: lowercasing destroys the ordering.
    fn is_seekable(&self) -> bool {
        self.transform == Transform::None && matches!(self.op, PushOp::Equals | PushOp::StartsWith)
    }
}

/// A bitset over string-table indices.
struct StringBitset {
    bits: Vec<u64>,
}

impl StringBitset {
    fn new(len: usize) -> StringBitset {
        StringBitset {
            bits: vec![0u64; len.div_ceil(64)],
        }
    }
    #[inline]
    fn set(&mut self, i: usize) {
        self.bits[i >> 6] |= 1u64 << (i & 63);
    }
    #[inline]
    fn get(&self, i: usize) -> bool {
        match self.bits.get(i >> 6) {
            Some(w) => (w >> (i & 63)) & 1 == 1,
            None => false,
        }
    }
    fn is_empty(&self) -> bool {
        self.bits.iter().all(|w| *w == 0)
    }
}

/// Per-source pushdown state: which string ids satisfy the predicate for each property.
struct SourcePlan {
    source: SourceIdx,
    /// One entry per conjunct, each indexed like `CALL_SITE_PROPS` with `None` where no
    /// predicate of that conjunct touches the property. A record is a hit when, for
    /// every conjunct, some tested property carries a string in that conjunct's set. A
    /// flat disjunction is the one-conjunct case.
    call_site: Vec<[Option<StringBitset>; 4]>,
    /// True when every predicate targets a CallSite property, so only CallSite nodes
    /// (and Annotation nodes, which expose the same names) can match.
    call_site_only: bool,
    /// CallSite candidates resolved through the persisted accelerator, ascending — the
    /// same order the sweep would have produced. `None` means sweep instead.
    ///
    /// An empty candidate set is the case that matters most across many graphs: the term
    /// reaches nothing here, so this graph contributes no CallSite work at all.
    call_site_candidates: Option<Candidates>,
    /// The candidate list is not merely a superset: every node in it satisfies the
    /// clause, so the WHERE re-check over it is redundant.
    call_site_exact: bool,
    /// No Annotation node can satisfy the clause, so that tag is skipped.
    skip_annotations: bool,
    /// No pre-filter could be built, so every record must reach the WHERE re-check.
    ///
    /// This is distinct from an empty plan. A `SourcePlan` whose bitsets are all `None`
    /// matches *nothing* in the sweep, so it cannot stand in for "no opinion" — doing
    /// that silently dropped every CallSite node.
    no_prefilter: bool,
}

/// How a source's CallSite candidates are produced.
///
/// A disjunction's candidates are the union of one posting list per (property, string)
/// pair, each already ascending. Materialising that union means reading every posting —
/// bounded only by the sweep ratio, so hundreds of thousands of ids for a broad term —
/// sorting it and de-duplicating it, and then, under a `LIMIT`, using the first couple
/// of hundred. `Union` keeps the pairs instead and merges them on demand, so a satisfied
/// `LIMIT` stops after reading about as many postings as it returned rows.
enum Candidates {
    /// Ascending node ids, already materialised.
    Nodes(Vec<u32>),
    /// `(property, start, end)` posting ranges whose lists union to the candidates.
    Union(Vec<(u8, u32, u32)>),
    /// One pair list per conjunct; the candidates are the ids in every one's union.
    ///
    /// A conjunction used to be answered by materialising its cheapest side and then
    /// reading the record behind each of those ids to test the others -- a random
    /// access per candidate, which for a side of fifty thousand ids is fifty thousand
    /// cache misses before the first row. When the sides are of comparable size it is
    /// cheaper to merge each into an ascending stream and walk them together: every
    /// read is sequential, no record is decoded, and a satisfied LIMIT stops the walk.
    Intersect(Vec<Vec<(u8, u32, u32)>>),
}

/// Either lazy candidate producer, so the sweep loop pulls chunks from one type.
enum Lazy<'a> {
    Union(PostingsMerge<'a>),
    Intersect(IntersectMerge<'a>),
}

impl Lazy<'_> {
    fn next_chunk(&mut self, want: usize, out: &mut Vec<u32>) {
        match self {
            Lazy::Union(m) => m.next_chunk(want, out),
            Lazy::Intersect(m) => m.next_chunk(want, out),
        }
    }
}

/// Ascending ids present in every one of several ascending streams.
struct IntersectMerge<'a> {
    streams: Vec<PostingsMerge<'a>>,
    heads: Vec<Option<u32>>,
}

impl<'a> IntersectMerge<'a> {
    fn new(
        idx: &'a graphite_storage::callsite_index::CallSiteStringIndex,
        sides: &[Vec<(u8, u32, u32)>],
    ) -> IntersectMerge<'a> {
        let mut streams: Vec<PostingsMerge<'a>> =
            sides.iter().map(|pairs| PostingsMerge::new(idx, pairs)).collect();
        let heads = streams.iter_mut().map(PostingsMerge::next_one).collect();
        IntersectMerge { streams, heads }
    }

    /// Refill `out` with up to `want` further ids. Empty means exhausted.
    fn next_chunk(&mut self, want: usize, out: &mut Vec<u32>) {
        out.clear();
        while out.len() < want {
            // The largest head is the only value every stream might still hold; every
            // stream below it is advanced up to it, and a stream that runs out ends
            // the intersection.
            let mut target = 0u32;
            for h in &self.heads {
                match h {
                    Some(v) => target = target.max(*v),
                    None => return,
                }
            }
            let mut aligned = true;
            for i in 0..self.streams.len() {
                while let Some(v) = self.heads[i] {
                    if v >= target {
                        break;
                    }
                    self.heads[i] = self.streams[i].next_one();
                }
                match self.heads[i] {
                    Some(v) if v == target => {}
                    Some(_) => aligned = false,
                    None => return,
                }
            }
            if aligned {
                out.push(target);
                for i in 0..self.streams.len() {
                    self.heads[i] = self.streams[i].next_one();
                }
            }
        }
    }
}

/// Ascending de-duplicated union of several ascending posting lists.
struct PostingsMerge<'a> {
    lists: Vec<graphite_storage::callsite_index::NodePostings<'a>>,
    heap: std::collections::BinaryHeap<std::cmp::Reverse<(u32, u32)>>,
    last: Option<u32>,
}

impl<'a> PostingsMerge<'a> {
    fn new(
        idx: &'a graphite_storage::callsite_index::CallSiteStringIndex,
        pairs: &[(u8, u32, u32)],
    ) -> PostingsMerge<'a> {
        let mut lists = Vec::with_capacity(pairs.len());
        let mut heap = std::collections::BinaryHeap::with_capacity(pairs.len());
        for &(property, start, end) in pairs {
            let mut postings = idx.postings_in(property as usize, start, end);
            if let Some(first) = postings.next() {
                heap.push(std::cmp::Reverse((first, lists.len() as u32)));
                lists.push(postings);
            }
        }
        PostingsMerge {
            lists,
            heap,
            last: None,
        }
    }

    /// The next id, de-duplicated across the lists. `None` means exhausted.
    fn next_one(&mut self) -> Option<u32> {
        loop {
            let std::cmp::Reverse((v, i)) = self.heap.pop()?;
            if let Some(next) = self.lists[i as usize].next() {
                self.heap.push(std::cmp::Reverse((next, i)));
            }
            if self.last != Some(v) {
                self.last = Some(v);
                return Some(v);
            }
        }
    }

    /// Refill `out` with up to `want` further ids. Empty means exhausted.
    fn next_chunk(&mut self, want: usize, out: &mut Vec<u32>) {
        out.clear();
        while out.len() < want {
            match self.next_one() {
                Some(v) => out.push(v),
                None => return,
            }
        }
    }
}

pub struct ScanPlan {
    variable: String,
    /// Node tags to sweep when the pushdown does not apply to a source.
    tags: Vec<u8>,
    tree: PredTree,
    /// Every leaf of `tree`, for the bitset fallback and for the CallSite-only test.
    predicates: Vec<StringPredicate>,
    /// How many distinct dictionary tests the leaves perform.
    tests: usize,
}

impl ScanPlan {
    /// Build a plan, or `None` when the query shape is not a single-node string scan.
    pub fn build(patterns: &[Pattern], where_clause: Option<&Expr>) -> Option<ScanPlan> {
        if super::optimizations_disabled() {
            return None;
        }
        if patterns.len() != 1 {
            return None;
        }
        let p = &patterns[0];
        if !p.rels.is_empty() || p.path_variable.is_some() || p.nodes.len() != 1 {
            return None;
        }
        let np = &p.nodes[0];
        let variable = np.variable.clone()?;
        if !np.properties.is_empty() {
            return None;
        }
        let tags = match np.labels.first() {
            None => (0..graphite_storage::node::TAG_COUNT as u8).collect::<Vec<u8>>(),
            Some(l) => label_tags(l)?,
        };
        if np.labels.len() > 1 {
            return None;
        }
        let where_clause = where_clause?;
        let mut tree = collect_tree(where_clause, &variable)?;
        let tests = assign_test_ids(&mut tree);
        let mut preds = Vec::new();
        tree.leaves(&mut preds);
        if preds.is_empty() {
            return None;
        }
        // Every predicate must target a CallSite string property for the fast sweep.
        if !preds.iter().all(|p| CALL_SITE_PROPS.contains(&p.property)) {
            return None;
        }
        Some(ScanPlan {
            variable,
            tags,
            tree,
            predicates: preds,
            tests,
        })
    }

    /// Sweep candidates and hand survivors to `consume` after the full WHERE re-check.
    ///
    /// Candidates are produced in chunks so a `LIMIT` can stop the sweep long before
    /// the whole graph has been examined, and each source is planned only when the
    /// sweep reaches it. Both matter across many graphs: a `LIMIT` that a couple of
    /// graphs already satisfy must not pay for planning the other sixty-two.
    pub fn run(
        &self,
        ex: &Executor,
        ev: &Evaluator,
        row: &Row,
        where_clause: Option<&Expr>,
        consume: &mut dyn FnMut(Row) -> CypherResult<bool>,
    ) -> CypherResult<bool> {
        self.run_inner(ex, ev, row, where_clause, &mut |value, provenance, verified| {
            let mut r = row.clone();
            // Two inserts follow; one reservation instead of two growths.
            r.reserve(2);
            r.insert(self.variable.clone(), value.clone());
            match provenance {
                Some(p) => {
                    r.insert(
                        super::pipeline::INTERNAL_PROVENANCE_KEY.to_string(),
                        p.clone(),
                    );
                }
                None => add_provenance(&mut r, ex, &value),
            }
            if let Some(w) = where_clause {
                if !verified && ev.eval(w, &r)?.as_bool() != Some(true) {
                    return Ok(true);
                }
            }
            consume(r)
        })
    }

    /// The scan variable.
    pub fn variable(&self) -> &str {
        &self.variable
    }

    /// Sweep candidates and hand each survivor's projected values to `sink`, never
    /// building a row.
    ///
    /// For the query shape that dominates the load -- one node, a pushed-down WHERE, a
    /// RETURN of that node's properties, a LIMIT -- the row is pure overhead: an
    /// `IndexMap` with a `String` key per column built to be read once by the
    /// projection and dropped, then a second one for the projection itself. Seventeen
    /// allocations per row on a path that otherwise touches four string ids. Here the
    /// properties are read straight off the node into a `Vec`, with the source graph's
    /// id alongside for provenance.
    pub fn run_nodes(
        &self,
        ex: &Executor,
        ev: &Evaluator,
        where_clause: Option<&Expr>,
        keys: &[String],
        sink: &mut dyn FnMut(Vec<Value>, std::sync::Arc<str>) -> CypherResult<bool>,
    ) -> CypherResult<bool> {
        let empty = Row::new();
        self.run_inner(ex, ev, &empty, where_clause, &mut |value, _, verified| {
            if !verified {
                if let Some(w) = where_clause {
                    // The clause only ever names the scan variable's own properties.
                    let mut r = Row::with_capacity(1);
                    r.insert(self.variable.clone(), value.clone());
                    if ev.eval(w, &r)?.as_bool() != Some(true) {
                        return Ok(true);
                    }
                }
            }
            let source = match &value {
                Value::Node(n) => n.source,
                _ => return Ok(true),
            };
            let values: Vec<Value> = keys.iter().map(|k| ev.property(&value, k)).collect();
            sink(values, ex.sources[source as usize].id.clone())
        })
    }

    /// The scan proper. `emit` receives each surviving node, the source's provenance
    /// value when one row-independent value serves every row, and whether the plan
    /// that produced it was exact -- in which case WHERE need not be re-checked.
    fn run_inner(
        &self,
        ex: &Executor,
        ev: &Evaluator,
        row: &Row,
        where_clause: Option<&Expr>,
        emit: &mut dyn FnMut(Value, Option<&Value>, bool) -> CypherResult<bool>,
    ) -> CypherResult<bool> {
        // The clause itself is `emit`'s business; the scan only needs to know it exists
        // to decide whether an inexact plan's survivors are still worth producing.
        let _ = (ev, where_clause);
        // Plans are built a batch at a time, in parallel, and consumed in source order.
        //
        // Resolving a term against one graph's dictionary is most of the cost of a broad
        // query, and the sixty-four graphs are independent — but the rows must still be
        // produced in order, and a satisfied LIMIT must still stop the scan. Batching
        // gives both: order is preserved, and at most one batch of planning is wasted
        // when the limit lands early.
        // A cheap serial pass first: the trigram bitmap answers "can this graph hold the
        // term at all" in a handful of bit tests, with no dictionary work and no thread
        // hand-off. Only the survivors are planned, and only they pay for the fan-out —
        // which for a term most graphs do not contain is nearly all of the saving, and
        // for a term they all contain costs sixty-four bitmap probes.
        let sources: Vec<SourceIdx> = (0..ex.sources.len() as SourceIdx)
            .filter(|s| may_match(ex.graph(*s), &self.tree))
            .collect();
        // Batches start at one graph per thread and double from there. A satisfied
        // LIMIT usually lands in the first batch, and a dense term costs real planning
        // per graph, so a first batch of sixteen on four cores meant four graphs
        // planned serially per core before a single row could be produced -- half a
        // millisecond on a query that then took one graph's rows and stopped.
        // Three batches at most: one graph, then one per thread, then everything left.
        //
        // The first is a single graph planned on this thread: a term dense enough to
        // fill its LIMIT from one graph gets its rows without a thread fan-out. The
        // second is a parallel batch for a term that needed a few more. Past that the
        // term is rare, and a rare term is missing from most graphs -- so what remains
        // is mostly establishing emptiness, at a few microseconds per graph, and the
        // cost of doing that is dominated by how many times the work is fanned out and
        // joined, not by the work. Doubling from four to sixteen meant seven batches
        // for sixty-four graphs, and seven joins to learn that none of them matched.
        let threads = rayon::current_num_threads().max(1);
        let mut batches: Vec<&[SourceIdx]> = Vec::new();
        let mut rest = sources.as_slice();
        for size in [1, threads] {
            if rest.is_empty() {
                break;
            }
            let (head, tail) = rest.split_at(size.min(rest.len()));
            batches.push(head);
            rest = tail;
        }
        if !rest.is_empty() {
            batches.push(rest);
        }
        for batch in batches {
            ex.cancel.check()?;
            let plans: Vec<SourcePlan> = if batch.len() > 1 {
                batch
                    .par_iter()
                    .map(|s| {
                        build_source_plan(
                            *s,
                            ex.graph(*s),
                            &self.tree,
                            &self.predicates,
                            self.tests,
                        )
                    })
                    .collect()
            } else {
                batch
                    .iter()
                    .map(|s| {
                        build_source_plan(
                            *s,
                            ex.graph(*s),
                            &self.tree,
                            &self.predicates,
                            self.tests,
                        )
                    })
                    .collect()
            };
            for sp in &plans {
                let source = sp.source;
                let graph = ex.graph(source);
                // Every row from this source carries the same provenance, so it is
                // built once here and cloned in -- an `Arc` bump per row -- rather than
                // assembled per row from a fresh list, a sort and two allocations. Only
                // when the base row has none of its own; a row that already names
                // graphs is merged the general way.
                let provenance: Option<Value> = (ex.cross
                    && !row.contains_key(super::pipeline::INTERNAL_PROVENANCE_KEY))
                .then(|| Value::list(vec![Value::Str(ex.sources[source as usize].id.clone())]));
                // Which tags this source contributes, decided inline: collecting them
                // meant a vector per graph, and there are sixty-four of them per query.
                let wanted = |t: u8| {
                    !sp.call_site_only
                        || t == TAG_CALL_SITE_NODE
                        || (t == TAG_ANNOTATION_NODE && !sp.skip_annotations)
                };
                for &tag in self.tags.iter().filter(|t| wanted(**t)) {
                    // When the accelerator resolved the CallSite candidates, those *are* the
                    // records to visit; nothing else needs looking at.
                    let indexed = match (tag, &sp.call_site_candidates) {
                        (TAG_CALL_SITE_NODE, Some(c)) => Some(c),
                        _ => None,
                    };
                    // A materialised candidate list (or the whole tag) is walked in
                    // place; a disjunction's union is merged a chunk at a time instead,
                    // so a satisfied LIMIT never pays for the postings it does not read.
                    let slice: Option<&[u32]> = match indexed {
                        Some(Candidates::Nodes(nodes)) => Some(nodes.as_slice()),
                        Some(Candidates::Union(_) | Candidates::Intersect(_)) => None,
                        None => Some(graph.ids_by_tag(tag)),
                    };
                    if slice.is_some_and(<[u32]>::is_empty) {
                        continue;
                    }
                    let mut merge = match (indexed, usable_index(graph)) {
                        (Some(Candidates::Union(pairs)), Some(idx)) => {
                            Some(Lazy::Union(PostingsMerge::new(idx, pairs)))
                        }
                        (Some(Candidates::Intersect(sides)), Some(idx)) => {
                            Some(Lazy::Intersect(IntersectMerge::new(idx, sides)))
                        }
                        _ => None,
                    };
                    // When the accelerator answered exactly, the candidates are the
                    // matches: re-checking WHERE would decode four strings per record to
                    // re-derive what the dictionary already decided.
                    // Only CallSite records reached through an exact plan -- indexed
                    // candidates or the bitset sweep -- skip it. An unfiltered source
                    // and the Annotation tag always go through WHERE.
                    let verified =
                        tag == TAG_CALL_SITE_NODE && sp.call_site_exact && !sp.no_prefilter;
                    let mut hits: Vec<u32> = Vec::new();
                    let mut merged: Vec<u32> = Vec::new();
                    let mut offset = 0usize;
                    // The merge is pulled a chunk at a time; the first chunk is small and
                    // each one doubles. Pulling a full sweep chunk first meant merging
                    // sixty-five thousand ids to satisfy a LIMIT of two hundred.
                    let mut want = MERGE_FIRST_CHUNK;
                    loop {
                        let chunk: &[u32] = match slice {
                            Some(ids) => {
                                if offset >= ids.len() {
                                    break;
                                }
                                let end = (offset + SWEEP_CHUNK).min(ids.len());
                                let c = &ids[offset..end];
                                offset = end;
                                c
                            }
                            None => {
                                match merge.as_mut() {
                                    Some(m) => m.next_chunk(want, &mut merged),
                                    None => break,
                                }
                                want = (want * 2).min(SWEEP_CHUNK);
                                if merged.is_empty() {
                                    break;
                                }
                                &merged[..]
                            }
                        };
                        ex.cancel.check()?;
                        hits.clear();
                        if indexed.is_some() || sp.no_prefilter {
                            // Already narrowed, or never narrowed: either way the WHERE
                            // re-check below decides.
                            hits.extend_from_slice(chunk);
                        } else if tag == TAG_CALL_SITE_NODE {
                            sweep_call_sites(graph, chunk, sp, &mut hits);
                        } else {
                            // No raw fast path for this tag: let WHERE decide.
                            hits.extend_from_slice(chunk);
                        }
                        for &id in &hits {
                            ex.tick()?;
                            let value = Value::Node(NodeRef {
                                source: sp.source,
                                id,
                            });
                            if !emit(value, provenance.as_ref(), verified)? {
                                return Ok(false);
                            }
                        }
                    }
                }
            }
        }
        Ok(true)
    }
}

/// Candidates are examined in chunks this large, so a satisfied LIMIT stops the sweep.
const SWEEP_CHUNK: usize = 65_536;
/// The lazy merge's first chunk; each following chunk doubles, up to `SWEEP_CHUNK`.
const MERGE_FIRST_CHUNK: usize = 256;

/// Raw sweep of CallSite records against the string-id bitsets, appending hits to `out`.
fn sweep_call_sites(graph: &Graph, ids: &[u32], sp: &SourcePlan, out: &mut Vec<u32>) {
    let data = graph.nodedata();
    let test = |id: &u32| -> bool {
        let offset = match graph.node_offset(*id) {
            Some(o) => o,
            None => return false,
        };
        let s = read_call_site_strings(data, offset);
        let fields = [s.caller_class, s.caller_name, s.callee_class, s.callee_name];
        sp.call_site.iter().all(|conjunct| {
            conjunct
                .iter()
                .enumerate()
                .any(|(i, set)| set.as_ref().is_some_and(|set| set.get(fields[i] as usize)))
        })
    };
    // Parallelise only when the chunk is large enough to pay for the fan-out. Results
    // stay in id order, which is nodedata order for a type-index range.
    const PARALLEL_THRESHOLD: usize = 16_384;
    if ids.len() >= PARALLEL_THRESHOLD {
        let hits: Vec<u32> = ids.par_iter().filter(|id| test(id)).copied().collect();
        out.extend_from_slice(&hits);
    } else {
        out.extend(ids.iter().filter(|id| test(id)).copied());
    }
}

/// Indices of dictionary entries matching a seekable predicate, via binary search.
fn seek_range(graph: &Graph, p: &StringPredicate) -> std::ops::Range<usize> {
    let n = graph.strings.len();
    let lower = lower_bound(graph, &p.literal);
    match p.op {
        PushOp::Equals => {
            if lower < n && graph.strings.get(lower) == p.literal {
                lower..lower + 1
            } else {
                lower..lower
            }
        }
        // Every string with the prefix sorts together, starting at the lower bound.
        PushOp::StartsWith => {
            let mut end = lower;
            while end < n && graph.strings.get(end).starts_with(&p.literal) {
                end += 1;
            }
            lower..end
        }
        _ => 0..0,
    }
}

/// First index whose string is not ordered before `needle` (Java string ordering).
fn lower_bound(graph: &Graph, needle: &str) -> usize {
    let (mut lo, mut hi) = (0usize, graph.strings.len());
    while lo < hi {
        let mid = (lo + hi) / 2;
        if graphite_storage::strings::java_cmp(graph.strings.get(mid), needle).is_lt() {
            lo = mid + 1;
        } else {
            hi = mid;
        }
    }
    lo
}

/// Cheap test that avoids allocating a lowercase copy of an already-lowercase string.
#[inline]
fn is_lowercase_ascii(s: &str) -> bool {
    s.bytes().all(|b| !b.is_ascii_uppercase() && b.is_ascii())
}

fn build_source_plan(
    source: SourceIdx,
    graph: &Graph,
    tree: &PredTree,
    preds: &[StringPredicate],
    tests: usize,
) -> SourcePlan {
    let call_site_only = preds.iter().all(|p| CALL_SITE_PROPS.contains(&p.property));
    // An Annotation node exposes `name`, `class`, `member` and `values`, and then any
    // key its own value pairs carry. A CallSite property name therefore reaches one only
    // if that exact name exists in the graph's dictionary as a value-pair key — which for
    // names like `caller_class` it essentially never does. Four binary searches settle
    // whether the whole tag can be skipped, instead of decoding every annotation record
    // to find out that none of them match.
    let skip_annotations = call_site_only
        && !preds
            .iter()
            .any(|p| graph.strings.index_of(p.property).is_some());
    let pruned = |candidates: Candidates, exact: bool| SourcePlan {
        source,
        call_site: Vec::new(),
        call_site_only,
        call_site_candidates: Some(candidates),
        call_site_exact: exact,
        no_prefilter: false,
        skip_annotations,
    };
    if let Some(idx) = usable_index(graph) {
        let mut memo = Memo::new(tests);
        // Pruning first, and separately from enumeration. Whether a graph can match at
        // all is a question about its dictionary; whether to reach the matches through
        // postings or by sweeping records is a question about cost. Answering the second
        // used to discard the first — a term dense enough to decline the postings sent
        // the whole query to an undifferentiated sweep of all sixty-four graphs, even
        // the ones whose dictionary holds no matching string at all.
        // A conjunction decides its own emptiness -- from run sizes and one side's
        // records -- rather than resolving every literal first.
        if let PredTree::And(children) = tree {
            match plan_conjunction(graph, idx, children, &mut memo) {
                Conjunction::Empty => return pruned(Candidates::Nodes(Vec::new()), true),
                Conjunction::Nodes(nodes) => return pruned(Candidates::Nodes(nodes), true),
                Conjunction::Intersect(sides) => {
                    return pruned(Candidates::Intersect(sides), true)
                }
                Conjunction::Sweep(conjuncts) => {
                    return SourcePlan {
                        source,
                        call_site: conjuncts,
                        call_site_only,
                        call_site_candidates: None,
                        call_site_exact: true,
                        no_prefilter: false,
                        skip_annotations,
                    }
                }
                Conjunction::Probe => {}
            }
        } else if tree_matches_nothing(graph, idx, tree, &mut memo) {
            return pruned(Candidates::Nodes(Vec::new()), true);
        }
        // A disjunction's candidates are a union of posting lists, and a union of sorted
        // lists does not have to be built to be read in order. Handing the pairs to the
        // merge keeps the answer identical and makes its cost proportional to how much of
        // it the query actually consumes.
        if tree.is_flat_or() {
            if let Some(sets) = property_sets(graph, idx, tree, &mut memo) {
                let ceiling = idx.call_site_count() / POSTING_SWEEP_RATIO;
                let cost = sets.posting_cost_up_to(idx, ceiling);
                if cost > ceiling {
                    // Too dense for postings, but the dictionary has already been
                    // resolved: the sweep's bitsets come straight from those ids. This
                    // used to fall through to a second pass over every string id and
                    // then a full scan of the dictionary to rediscover the same sets --
                    // over a millisecond per graph on a term like "get".
                    return SourcePlan {
                        source,
                        call_site: vec![sets.bitsets(graph.strings.len())],
                        call_site_only,
                        call_site_candidates: None,
                        call_site_exact: true,
                        no_prefilter: false,
                        skip_annotations,
                    };
                }
                if cost > MERGE_FLOOR {
                    return pruned(Candidates::Union(sets.pairs()), true);
                }
            }
        }
        if let Some((candidates, exact)) = indexed_candidates(graph, idx, tree, &mut memo) {
            return pruned(Candidates::Nodes(candidates), exact);
        }
    }
    // The bitset sweep can only express a disjunction. A conjunction the index could not
    // answer therefore gets no pre-filter at all: every record goes to the WHERE clause.
    if !tree.is_flat_or() {
        return SourcePlan {
            source,
            call_site: Vec::new(),
            call_site_only: false,
            call_site_candidates: None,
            call_site_exact: false,
            no_prefilter: true,
            skip_annotations,
        };
    }
    build_sweep_plan(source, graph, preds, call_site_only, skip_annotations)
}

/// Resolve CallSite candidates through `graph.callsite-string-index`.
///
/// `None` means the accelerator cannot (or should not) answer this predicate set, and
/// the caller must fall back to scanning the dictionary and sweeping records.
///
/// The flag says whether the list is the exact answer rather than merely a superset.
fn indexed_candidates(
    graph: &Graph,
    idx: &graphite_storage::callsite_index::CallSiteStringIndex,
    tree: &PredTree,
    memo: &mut Memo,
) -> Option<(Vec<u32>, bool)> {
    eval_tree(graph, idx, tree, memo)
}

/// The accelerator, when it describes this graph's CallSite records.
fn usable_index(graph: &Graph) -> Option<&graphite_storage::callsite_index::CallSiteStringIndex> {
    let idx = graph.call_site_index()?;
    (idx.call_site_count() == graph.count_by_tag(TAG_CALL_SITE_NODE)).then_some(idx)
}

/// True when no CallSite record in this graph can satisfy the clause.
///
/// Decided entirely from the dictionary, so it holds however dense the term is: a term
/// present in no string cannot be present in any record. `false` means "not proven
/// absent", never "present" — a leaf the index cannot resolve prunes nothing.
///
/// Annotation nodes are deliberately not covered. They expose the same property names,
/// but the trigram index spans only the strings CallSite properties use, so absence
/// there says nothing about them and they are still swept.
fn tree_matches_nothing(
    graph: &Graph,
    idx: &graphite_storage::callsite_index::CallSiteStringIndex,
    tree: &PredTree,
    memo: &mut Memo,
) -> bool {
    match tree {
        PredTree::Leaf(p) => match resolve_strings(graph, idx, p, memo) {
            Some(ids) => ids.is_empty(),
            None => false,
        },
        // A disjunction is empty only when every branch is.
        PredTree::Or(children) => children
            .iter()
            .all(|c| tree_matches_nothing(graph, idx, c, memo)),
        // A conjunction is empty as soon as one conjunct is.
        PredTree::And(children) => children
            .iter()
            .any(|c| tree_matches_nothing(graph, idx, c, memo)),
    }
}

/// Matching string ids for one predicate, resolved once per query and reused.
///
/// The same literal is usually tested against all four properties, and the matching ids
/// depend only on the literal, the operator and the transform — never on which property
/// is being tested.
fn resolve_strings<'m>(
    graph: &Graph,
    idx: &graphite_storage::callsite_index::CallSiteStringIndex,
    p: &StringPredicate,
    memo: &'m mut Memo,
) -> Option<&'m [u32]> {
    if memo.slots[p.test].is_none() {
        // `Declined` and "resolved to nothing" are different answers and both are
        // remembered, so a term the index cannot handle is not retried per property.
        memo.slots[p.test] = Some(matching_string_ids(graph, idx, p));
    }
    memo.slots[p.test].as_ref()?.as_deref()
}

/// Per-graph cache of dictionary resolutions, one slot per distinct test.
///
/// Indexed rather than searched, and holding the ids rather than copies of them: a
/// broad query resolves the same literal for four properties across sixty-four graphs,
/// and the copies alone were hundreds of allocations.
struct Memo {
    slots: Vec<Option<Option<Vec<u32>>>>,
}

impl Memo {
    fn new(tests: usize) -> Memo {
        Memo {
            slots: vec![None; tests],
        }
    }
}

/// Number the distinct dictionary tests in the tree, so each is resolved once per graph.
fn assign_test_ids(tree: &mut PredTree) -> usize {
    let mut seen: Vec<StringPredicate> = Vec::new();
    number(tree, &mut seen);
    seen.len()
}

fn number(tree: &mut PredTree, seen: &mut Vec<StringPredicate>) {
    match tree {
        PredTree::Leaf(p) => {
            p.test = match seen.iter().position(|q| q.same_test(p)) {
                Some(i) => i,
                None => {
                    seen.push(p.clone());
                    seen.len() - 1
                }
            };
        }
        PredTree::Or(children) | PredTree::And(children) => {
            children.iter_mut().for_each(|c| number(c, seen))
        }
    }
}

/// Ascending CallSite node ids that can satisfy this subtree, and whether that list is
/// exact.
///
/// Always a *superset* of the true matches, at every level: `OR` unions its children and
/// `AND` intersects them, and a superset of each side intersects to a superset of the
/// conjunction. Survivors are re-checked against the full WHERE clause, so a loose
/// answer costs time and never correctness.
///
/// The second element says the list is more than a superset — every node in it satisfies
/// the subtree. A leaf resolved through the index is exact, because the dictionary ids
/// behind it are exactly the strings the predicate matches and the postings are exactly
/// the records carrying them. A disjunction is exact when all its branches are; a
/// conjunction when every conjunct contributed a filter and none was skipped.
fn eval_tree(
    graph: &Graph,
    idx: &graphite_storage::callsite_index::CallSiteStringIndex,
    tree: &PredTree,
    memo: &mut Memo,
) -> Option<(Vec<u32>, bool)> {
    match tree {
        PredTree::Leaf(p) => leaf_candidates(graph, idx, p, memo).map(|nodes| (nodes, true)),
        PredTree::Or(children) => {
            let mut nodes = Vec::new();
            let mut exact = true;
            for c in children {
                let (child, child_exact) = eval_tree(graph, idx, c, memo)?;
                nodes.extend(child);
                exact &= child_exact;
            }
            nodes.sort_unstable();
            nodes.dedup();
            Some((nodes, exact))
        }
        PredTree::And(children) => {
            // Only the cheapest conjunct is turned into node ids. The rest stay on the
            // dictionary side as sets of string ids, and filter those nodes by reading
            // the four raw ids out of each record — no postings walked, nothing unioned.
            //
            // It matters because the common shape is one broad term AND a handful of
            // alternatives: materialising every branch costs the sum of all of them,
            // while materialising the smallest costs the minimum and the rest become a
            // membership test over a set that is usually tiny.
            let mut sets: Vec<Option<PropertySets>> = Vec::with_capacity(children.len());
            for c in children {
                sets.push(property_sets(graph, idx, c, memo));
            }
            // A conjunct the index declines — a term so broad that walking its postings
            // costs more than sweeping — says nothing, and is skipped. Intersecting only
            // the conjuncts that are selective still yields a superset.
            let mut best: Option<(usize, usize)> = None;
            for (i, s) in sets.iter().enumerate() {
                if let Some(s) = s {
                    let cost = s.posting_cost(idx);
                    if best.is_none_or(|(_, c)| cost < c) {
                        best = Some((i, cost));
                    }
                }
            }
            let (chosen, cost) = best?;
            // Past this the sweep is cheaper than the postings, as for a single leaf.
            if cost * POSTING_SWEEP_RATIO > idx.call_site_count() {
                return None;
            }
            // Every conjunct that produced a set is applied exactly; a skipped one is the
            // only thing that leaves survivors unverified.
            let exact = sets.iter().all(Option::is_some);
            let mut nodes = sets[chosen].as_ref()?.nodes(idx);
            for (i, s) in sets.iter().enumerate() {
                if i == chosen {
                    continue;
                }
                let Some(s) = s else { continue };
                nodes.retain(|id| s.matches_node(graph, *id));
                if nodes.is_empty() {
                    break;
                }
            }
            Some((nodes, exact))
        }
    }
}

/// Ascending node ids carrying a string that satisfies one predicate, in its property.
fn leaf_candidates(
    graph: &Graph,
    idx: &graphite_storage::callsite_index::CallSiteStringIndex,
    p: &StringPredicate,
    memo: &mut Memo,
) -> Option<Vec<u32>> {
    let property = CALL_SITE_PROPS.iter().position(|c| *c == p.property)?;
    let strings = resolve_strings(graph, idx, p, memo)?;
    let mut postings_total = 0usize;
    for &s in strings {
        postings_total += idx.posting_len(property, s as usize);
        // A term this broad is cheaper to sweep: postings are random access into the
        // node ids, while the sweep reads the records in order.
        if postings_total * POSTING_SWEEP_RATIO > idx.call_site_count() {
            return None;
        }
    }
    let mut nodes: Vec<u32> = Vec::with_capacity(postings_total);
    for &s in strings {
        if let Some(postings) = idx.postings(property, s as usize) {
            nodes.extend(postings);
        }
    }
    // Postings for one string are ascending, but different strings interleave.
    nodes.sort_unstable();
    nodes.dedup();
    Some(nodes)
}

/// Walking postings stops paying off once they cover this fraction of the records.
const POSTING_SWEEP_RATIO: usize = 8;
/// A conjunction is walked as intersecting streams when its sides together hold at most
/// this many times the postings of its smallest side. Past that, materialising the
/// small side and probing records for the rest reads less, even at a cache miss each.
const INTERSECT_RATIO: usize = 3;

/// How a conjunction of flat sides is answered.
enum Conjunction {
    /// A side has no postings at all, so nothing satisfies the conjunction.
    Empty,
    /// Walk every side's posting stream together.
    Intersect(Vec<Vec<(u8, u32, u32)>>),
    /// Every side is too dense for postings: sweep the records against each side's
    /// bitsets. Exact, and where this used to hand every record to the generic WHERE
    /// evaluator -- half a second on `Stub AND (... OR Stub ...)` over the Android
    /// graphs, decoding four strings per record to re-decide what the dictionary had
    /// already resolved.
    Sweep(Vec<[Option<StringBitset>; 4]>),
    /// Materialise the smallest side and probe records for the rest: the side is either
    /// not flat, or so much smaller than the others that reading its records costs less
    /// than walking theirs.
    Probe,
    /// Already answered: the smallest side was small enough to read outright, and every
    /// other side was decided per record by evaluating its predicates on the strings
    /// those records actually carry.
    Nodes(Vec<u32>),
}

/// Postings a conjunction's smallest side may have and still be read outright, with
/// the other sides evaluated on its records rather than resolved against the dictionary.
const PROBE_BY_PREDICATE_LIMIT: usize = 4096;

/// How many dictionary entries a flat side could match, from run sizes alone -- no
/// intersection, no verification. `Some(0)` proves the side empty; `None` means the
/// side is not flat or a term has no trigram to size by.
fn side_proxy(
    graph: &Graph,
    idx: &graphite_storage::callsite_index::CallSiteStringIndex,
    tree: &PredTree,
) -> Option<usize> {
    match tree {
        PredTree::Leaf(p) => {
            if p.is_seekable() {
                return Some(seek_range(graph, p).len());
            }
            let trigrams = p.trigrams.as_ref().as_ref()?;
            trigrams
                .iter()
                .map(|t| idx.trigram_string_ids(*t).len())
                .min()
        }
        PredTree::Or(children) => children
            .iter()
            .map(|c| side_proxy(graph, idx, c))
            .sum(),
        PredTree::And(_) => None,
    }
}

/// Whether one record's four strings satisfy a subtree, evaluating each predicate on
/// the string itself. Memoised per (test, string id): a side's records repeat a few
/// dozen distinct strings, and each is decided once.
fn tree_matches_record(
    graph: &Graph,
    tree: &PredTree,
    fields: &[u32; 4],
    cache: &mut std::collections::HashMap<(usize, u32), bool>,
) -> bool {
    match tree {
        PredTree::Leaf(p) => {
            let Some(property) = CALL_SITE_PROPS.iter().position(|c| *c == p.property) else {
                return false;
            };
            let sid = fields[property];
            *cache
                .entry((p.test, sid))
                .or_insert_with(|| predicate_matches(graph, p, sid))
        }
        PredTree::Or(children) => children
            .iter()
            .any(|c| tree_matches_record(graph, c, fields, cache)),
        PredTree::And(children) => children
            .iter()
            .all(|c| tree_matches_record(graph, c, fields, cache)),
    }
}

fn plan_conjunction(
    graph: &Graph,
    idx: &graphite_storage::callsite_index::CallSiteStringIndex,
    children: &[PredTree],
    memo: &mut Memo,
) -> Conjunction {
    // The literals of a conjunction usually match; the conjunction usually does not.
    // Resolving every side against the dictionary to learn that cost forty-odd
    // microseconds per graph -- six literals, each a trigram walk and a verification --
    // and the answer was decided by the few hundred records of the smallest side all
    // along. So: size each side from its rarest trigram run, resolve only the smallest,
    // read its records, and decide the other sides on the strings those records carry.
    let proxies: Vec<Option<usize>> = children
        .iter()
        .map(|c| side_proxy(graph, idx, c))
        .collect();
    if proxies.contains(&Some(0)) {
        return Conjunction::Empty;
    }
    let smallest = proxies
        .iter()
        .enumerate()
        .filter_map(|(i, p)| p.map(|v| (i, v)))
        .min_by_key(|(_, v)| *v)
        .map(|(i, _)| i);
    if let Some(i) = smallest {
        if let Some(sets) = property_sets(graph, idx, &children[i], memo) {
            let cost = sets.posting_cost_up_to(idx, PROBE_BY_PREDICATE_LIMIT);
            if cost == 0 {
                return Conjunction::Empty;
            }
            if cost <= PROBE_BY_PREDICATE_LIMIT {
                let mut nodes = sets.nodes(idx);
                let mut cache = std::collections::HashMap::new();
                let data = graph.nodedata();
                nodes.retain(|&id| {
                    let Some(offset) = graph.node_offset(id) else {
                        return false;
                    };
                    let s = read_call_site_strings(data, offset);
                    let fields = [s.caller_class, s.caller_name, s.callee_class, s.callee_name];
                    children
                        .iter()
                        .enumerate()
                        .all(|(j, c)| j == i || tree_matches_record(graph, c, &fields, &mut cache))
                });
                return Conjunction::Nodes(nodes);
            }
        }
    }
    let ceiling = idx.call_site_count() / POSTING_SWEEP_RATIO;
    let mut sets: Vec<PropertySets> = Vec::with_capacity(children.len());
    for c in children {
        match property_sets(graph, idx, c, memo) {
            Some(s) => sets.push(s),
            None => return Conjunction::Probe,
        }
    }
    let costs: Vec<usize> = sets
        .iter()
        .map(|s| s.posting_cost_up_to(idx, ceiling))
        .collect();
    let Some(&min) = costs.iter().min() else {
        return Conjunction::Probe;
    };
    if min == 0 {
        return Conjunction::Empty;
    }
    if min > ceiling {
        let len = graph.strings.len();
        return Conjunction::Sweep(sets.iter().map(|s| s.bitsets(len)).collect());
    }
    if costs.iter().any(|&c| c > ceiling)
        || costs.iter().sum::<usize>() > min.saturating_mul(INTERSECT_RATIO)
    {
        return Conjunction::Probe;
    }
    Conjunction::Intersect(sets.iter().map(PropertySets::pairs).collect())
}
/// Below this many postings, building the union outright beats merging it lazily: the
/// heap costs a comparison and a mmap read per id, where a short list is one `extend`
/// and a sort that fits in cache.
const MERGE_FLOOR: usize = 4096;
/// Below this many surviving candidates, further trigram intersection is not worth it.
const TRIGRAM_INTERSECT_FLOOR: usize = 32;

/// Exactly the string ids satisfying one predicate, via the accelerator.
fn matching_string_ids(
    graph: &Graph,
    idx: &graphite_storage::callsite_index::CallSiteStringIndex,
    p: &StringPredicate,
) -> Option<Vec<u32>> {
    // Equality and prefixes are contiguous in the sorted dictionary, so the binary
    // search beats any index: no candidate verification, no postings intersection.
    if p.is_seekable() {
        return Some(seek_range(graph, p).map(|s| s as u32).collect());
    }
    // The index is built over trigrams of each string's Kotlin-lowercased form. An
    // ASCII literal is the case where "lowercase" cannot mean two different things:
    // every Unicode lowercase mapping leaves ASCII in ASCII and maps each character
    // independently, so `s.contains(literal)` still implies
    // `lower(s).contains(lower(literal))`, and the literal's trigrams are a sound
    // superset filter. A non-ASCII literal is not worth the risk of a mapping
    // disagreement silently dropping a match, so it takes the dictionary scan.
    if !p.literal.is_ascii() {
        return None;
    }
    let trigrams = p.trigrams.as_ref().as_ref()?;
    if trigrams.is_empty() {
        return None;
    }
    // Cheapest question first: does this graph contain the term's trigrams at all? A
    // string holding the term holds every one of them, so a single missing trigram
    // settles the whole graph without touching a posting list.
    if !idx.may_contain_all(trigrams) {
        return Some(Vec::new());
    }
    // Every trigram's posting list is sized -- two binary searches each -- and the
    // rarest few are the ones intersected. Probing a spread-out handful instead picked
    // whatever fell at those positions, and for a word like "observable" that was
    // "obs", "erv" and "abl": three of the commonest fragments in any codebase, so
    // hundreds of candidates survived to be decoded in a graph that held no match at
    // all. Its rarest fragments, "rva" and "vab", are absent from most graphs
    // outright, and an absent one settles the graph before a single string is read.
    // That matters across sixty-four graphs, where the term is missing from most of
    // them and the per-graph bitmap cannot tell, since each fragment alone is present.
    let mut lists: Vec<_> = trigrams
        .iter()
        .map(|t| idx.trigram_string_ids(*t))
        .collect();
    lists.sort_by_key(|l| l.len());
    if lists[0].is_empty() {
        return Some(Vec::new());
    }
    // Every list is already sized, so every list is available to intersect; each one
    // is walked once alongside the shrinking candidate set, and the walk stops as soon
    // as few enough candidates remain that verifying them is cheaper than narrowing.
    let mut candidates: Vec<u32> = lists[0].iter().collect();
    for list in &lists[1..] {
        if candidates.len() <= TRIGRAM_INTERSECT_FLOOR {
            break;
        }
        list.intersect_into(&mut candidates);
    }
    // The trigram set is a filter, not an answer: check the predicate for real. The
    // signature goes first: it is one word read, where checking for real means decoding
    // the string out of the front-coded dictionary. A string containing the literal
    // contains every one of the literal's trigrams, so every bit the literal's signature
    // sets is also set in that string's — the test can reject, never wrongly admit.
    candidates.retain(|&id| {
        idx.signature(id as usize) & p.signature == p.signature && predicate_matches(graph, p, id)
    });
    Some(candidates)
}

/// Apply a predicate to a dictionary entry, transforming it the way the sweep does.
fn predicate_matches(graph: &Graph, p: &StringPredicate, id: u32) -> bool {
    let text = graph.strings.get(id as usize);
    match p.transform {
        Transform::None => p.matches_raw(text),
        Transform::Lowercase => {
            if is_lowercase_ascii(text) {
                p.matches_raw(text)
            } else if text.is_ascii() {
                let mut lowered = text.to_owned();
                lowered.make_ascii_lowercase();
                p.matches_raw(&lowered)
            } else {
                let lowered: String = text.chars().flat_map(|c| c.to_lowercase()).collect();
                p.matches_raw(&lowered)
            }
        }
    }
}

/// The original plan: scan the dictionary into bitsets, then sweep records against them.
fn build_sweep_plan(
    source: SourceIdx,
    graph: &Graph,
    preds: &[StringPredicate],
    call_site_only: bool,
    skip_annotations: bool,
) -> SourcePlan {
    let n = graph.strings.len();
    // Group predicates by property once, then make a single pass over the dictionary.
    // A separate pass per property would re-read (and re-lowercase) every string.
    let mut by_property: [Vec<&StringPredicate>; 4] = [vec![], vec![], vec![], vec![]];
    for p in preds {
        if let Some(i) = CALL_SITE_PROPS.iter().position(|c| *c == p.property) {
            by_property[i].push(p);
        }
    }
    let mut sets: [Option<StringBitset>; 4] = std::array::from_fn(|i| {
        if by_property[i].is_empty() {
            None
        } else {
            Some(StringBitset::new(n))
        }
    });
    // The dictionary is sorted, so equality and prefix matches are contiguous ranges.
    // Marking those by binary search often removes the need to look at it at all.
    let mut needs_linear_pass = false;
    for i in 0..4 {
        let set = match &mut sets[i] {
            Some(set) => set,
            None => continue,
        };
        for p in &by_property[i] {
            if p.is_seekable() {
                for s in seek_range(graph, p) {
                    set.set(s);
                }
            } else {
                needs_linear_pass = true;
            }
        }
    }
    if needs_linear_pass {
        let needs_lowercase = preds
            .iter()
            .any(|p| p.transform == Transform::Lowercase && !p.is_seekable());
        // Split the dictionary across threads: each range produces the ids it matched,
        // which are then folded into the shared bitsets.
        const DICT_CHUNK: usize = 8192;
        let chunk_hits: Vec<[Vec<u32>; 4]> = (0..n)
            .step_by(DICT_CHUNK)
            .collect::<Vec<usize>>()
            .into_par_iter()
            .map(|start| {
                let end = (start + DICT_CHUNK).min(n);
                let mut hits: [Vec<u32>; 4] = Default::default();
                let mut lowered = String::new();
                for s in start..end {
                    let text = graph.strings.get(s);
                    let cased: &str = if needs_lowercase && !is_lowercase_ascii(text) {
                        lowered.clear();
                        if text.is_ascii() {
                            // Byte-wise is far cheaper than the Unicode mapping, and
                            // these are JVM class and member names, so ASCII dominates.
                            lowered.push_str(text);
                            // SAFETY: ASCII lowercasing keeps the string valid UTF-8.
                            unsafe { lowered.as_bytes_mut().make_ascii_lowercase() };
                        } else {
                            lowered.extend(text.chars().flat_map(|c| c.to_lowercase()));
                        }
                        &lowered
                    } else {
                        text
                    };
                    for i in 0..4 {
                        if by_property[i].is_empty() {
                            continue;
                        }
                        let hit = by_property[i].iter().any(|p| {
                            !p.is_seekable()
                                && match p.transform {
                                    Transform::None => p.matches_raw(text),
                                    Transform::Lowercase => p.matches_raw(cased),
                                }
                        });
                        if hit {
                            hits[i].push(s as u32);
                        }
                    }
                }
                hits
            })
            .collect();
        for hits in chunk_hits {
            for (i, ids) in hits.into_iter().enumerate() {
                if let Some(set) = &mut sets[i] {
                    for id in ids {
                        set.set(id as usize);
                    }
                }
            }
        }
    }
    // Nothing in the dictionary matched, so no record can: skip the sweep rather than
    // reading every one of them to discover that.
    if sets
        .iter()
        .all(|s| s.as_ref().is_none_or(StringBitset::is_empty))
    {
        return SourcePlan {
            source,
            call_site: vec![sets],
            call_site_only,
            call_site_candidates: Some(Candidates::Nodes(Vec::new())),
            call_site_exact: true,
            no_prefilter: false,
            skip_annotations,
        };
    }
    // The bitset sweep tests exactly the disjunction: a record hits when any tested
    // property carries a string one of that property's predicates matched. Nothing
    // looser, so its survivors need no WHERE re-check either.
    SourcePlan {
        source,
        call_site: vec![sets],
        call_site_only,
        call_site_candidates: None,
        call_site_exact: true,
        no_prefilter: false,
        skip_annotations,
    }
}

/// A WHERE clause reduced to the parts this pushdown understands.
///
/// The production queries are not flat disjunctions. They look like
/// `(a CONTAINS X OR b CONTAINS X) AND (a CONTAINS Y OR b CONTAINS Y)` — two broad
/// searches narrowed against each other. Treating only `OR` meant every one of those
/// declined the pushdown entirely and fell to the generic evaluator, which is orders of
/// magnitude slower. Keeping the tree lets `AND` do what it is there for: intersect.
enum PredTree {
    Leaf(StringPredicate),
    Or(Vec<PredTree>),
    And(Vec<PredTree>),
}

impl PredTree {
    fn leaves(&self, out: &mut Vec<StringPredicate>) {
        match self {
            PredTree::Leaf(p) => out.push(p.clone()),
            PredTree::Or(cs) | PredTree::And(cs) => cs.iter().for_each(|c| c.leaves(out)),
        }
    }
    /// True when the tree is a plain disjunction, the only shape the bitset sweep
    /// fallback can express.
    fn is_flat_or(&self) -> bool {
        match self {
            PredTree::Leaf(_) => true,
            PredTree::Or(cs) => cs.iter().all(|c| c.is_flat_or()),
            PredTree::And(_) => false,
        }
    }
}

/// Parse a WHERE clause into the tree. `None` when any leaf is unsupported — the whole
/// clause is then left to the generic evaluator, since a partial reading of it could
/// exclude rows that match.
fn collect_tree(e: &Expr, variable: &str) -> Option<PredTree> {
    match e {
        Expr::Or(a, b) => Some(PredTree::Or(vec![
            collect_tree(a, variable)?,
            collect_tree(b, variable)?,
        ])),
        Expr::And(a, b) => Some(PredTree::And(vec![
            collect_tree(a, variable)?,
            collect_tree(b, variable)?,
        ])),
        _ => {
            let mut out = Vec::new();
            if collect_leaf(e, variable, &mut out) && out.len() == 1 {
                Some(PredTree::Leaf(out.pop()?))
            } else {
                None
            }
        }
    }
}

/// Recognise one `<property> <op> <literal>` leaf.
fn collect_leaf(e: &Expr, variable: &str, out: &mut Vec<StringPredicate>) -> bool {
    match e {
        Expr::StringOp { op, left, right } => {
            let op = match op {
                StrOp::Contains => PushOp::Contains,
                StrOp::StartsWith => PushOp::StartsWith,
                StrOp::EndsWith => PushOp::EndsWith,
                // Regex is never pushed down.
                StrOp::Regex => return false,
            };
            push_predicate(op, left, right, variable, out)
        }
        Expr::Comparison {
            op: crate::ast::CmpOp::Eq,
            left,
            right,
        } => push_predicate(PushOp::Equals, left, right, variable, out),
        _ => false,
    }
}

/// Record one `<property> <op> <literal>` predicate, if both sides are recognised.
fn push_predicate(
    op: PushOp,
    left: &Expr,
    right: &Expr,
    variable: &str,
    out: &mut Vec<StringPredicate>,
) -> bool {
    let literal = match right {
        Expr::Literal(crate::ast::Literal::Str(s)) => s.clone(),
        _ => return false,
    };
    match property_operand(left, variable) {
        Some((property, transform)) => {
            let trigrams =
                std::sync::Arc::new(graphite_storage::callsite_index::literal_trigrams(&literal));
            let signature = graphite_storage::callsite_index::literal_signature(&literal);
            out.push(StringPredicate {
                property,
                op,
                literal,
                transform,
                trigrams,
                signature,
                // Numbered once the whole tree is known.
                test: 0,
            });
            true
        }
        None => false,
    }
}

/// Recognise `n.prop`, `toLower(n.prop)` and `toLower(coalesce(n.prop, ''))`.
fn property_operand(e: &Expr, variable: &str) -> Option<(&'static str, Transform)> {
    match e {
        Expr::Property { expr, key } => match expr.as_ref() {
            Expr::Variable(v) if v == variable => {
                let prop = CALL_SITE_PROPS.iter().find(|p| *p == key)?;
                Some((prop, Transform::None))
            }
            _ => None,
        },
        Expr::FunctionCall { name, args, .. } => {
            let lower = name.to_ascii_lowercase();
            match lower.as_str() {
                "tolower" | "tolowercase" => {
                    let (p, _) = property_operand(args.first()?, variable)?;
                    Some((p, Transform::Lowercase))
                }
                "coalesce" => {
                    let (p, t) = property_operand(args.first()?, variable)?;
                    Some((p, t))
                }
                _ => None,
            }
        }
        _ => None,
    }
}

#[allow(dead_code)]
fn _strid_marker(_: StrId) {}

/// Matching string ids per CallSite property, for a subtree that is a leaf or a
/// disjunction of them. A node satisfies it when any one of its four string ids is in
/// the corresponding set — the same "any property hits" test the sweep applies.
struct PropertySets {
    ids: [Vec<u32>; 4],
    /// Each id's posting range in its property, resolved once: sizing, materialising and
    /// merging all read from here instead of searching the CSR again.
    ranges: [Vec<(u32, u32)>; 4],
}

impl PropertySets {
    /// Total postings behind these ids: what materialising this subtree would cost.
    fn posting_cost(&self, idx: &graphite_storage::callsite_index::CallSiteStringIndex) -> usize {
        self.posting_cost_up_to(idx, usize::MAX)
    }

    /// Total postings, but stop counting once past `ceiling`: every string id costs a
    /// binary search into the CSR, and a dense term has sixteen thousand of them, all
    /// summed to learn something the first few hundred already settled.
    fn posting_cost_up_to(
        &self,
        idx: &graphite_storage::callsite_index::CallSiteStringIndex,
        ceiling: usize,
    ) -> usize {
        let _ = idx;
        let mut total = 0usize;
        for ranges in &self.ranges {
            for &(start, end) in ranges {
                total += (end - start) as usize;
                if total > ceiling {
                    return total;
                }
            }
        }
        total
    }

    /// The per-property bitsets the record sweep tests, built from the resolved ids.
    fn bitsets(&self, dictionary_len: usize) -> [Option<StringBitset>; 4] {
        std::array::from_fn(|p| {
            if self.ids[p].is_empty() {
                return None;
            }
            let mut set = StringBitset::new(dictionary_len);
            for &s in &self.ids[p] {
                set.set(s as usize);
            }
            Some(set)
        })
    }

    /// The `(property, string id)` pairs behind these sets, for the lazy merge.
    fn pairs(&self) -> Vec<(u8, u32, u32)> {
        let mut out = Vec::new();
        for (property, ranges) in self.ranges.iter().enumerate() {
            out.extend(
                ranges
                    .iter()
                    .filter(|(s, e)| e > s)
                    .map(|&(s, e)| (property as u8, s, e)),
            );
        }
        out
    }

    /// Ascending node ids carrying any of these strings in its own property.
    fn nodes(&self, idx: &graphite_storage::callsite_index::CallSiteStringIndex) -> Vec<u32> {
        let mut nodes = Vec::with_capacity(self.posting_cost(idx));
        for (property, ranges) in self.ranges.iter().enumerate() {
            for &(start, end) in ranges {
                nodes.extend(idx.postings_in(property, start, end));
            }
        }
        nodes.sort_unstable();
        nodes.dedup();
        nodes
    }

    /// Test one record directly, without touching the postings at all.
    fn matches_node(&self, graph: &Graph, node: u32) -> bool {
        let Some(offset) = graph.node_offset(node) else {
            return false;
        };
        let s = read_call_site_strings(graph.nodedata(), offset);
        let fields = [s.caller_class, s.caller_name, s.callee_class, s.callee_name];
        for (property, ids) in self.ids.iter().enumerate() {
            if ids.binary_search(&(fields[property] as u32)).is_ok() {
                return true;
            }
        }
        false
    }
}

/// The per-property string id sets of a leaf or a disjunction of leaves.
///
/// `None` when the subtree is not that shape, or when any leaf's term is one the index
/// declines: a partial set would wrongly exclude rows, since these sets are used to
/// *filter*, not merely to narrow.
fn property_sets(
    graph: &Graph,
    idx: &graphite_storage::callsite_index::CallSiteStringIndex,
    tree: &PredTree,
    memo: &mut Memo,
) -> Option<PropertySets> {
    let mut ids: [Vec<u32>; 4] = Default::default();
    if !collect_property_sets(graph, idx, tree, memo, &mut ids) {
        return None;
    }
    for v in ids.iter_mut() {
        v.sort_unstable();
        v.dedup();
    }
    let ranges = std::array::from_fn(|p| idx.posting_ranges(p, &ids[p]));
    Some(PropertySets { ids, ranges })
}

fn collect_property_sets(
    graph: &Graph,
    idx: &graphite_storage::callsite_index::CallSiteStringIndex,
    tree: &PredTree,
    memo: &mut Memo,
    out: &mut [Vec<u32>; 4],
) -> bool {
    match tree {
        PredTree::Leaf(p) => {
            let Some(property) = CALL_SITE_PROPS.iter().position(|c| *c == p.property) else {
                return false;
            };
            let Some(strings) = resolve_strings(graph, idx, p, memo) else {
                return false;
            };
            out[property].extend(strings);
            true
        }
        PredTree::Or(children) => children
            .iter()
            .all(|c| collect_property_sets(graph, idx, c, memo, out)),
        PredTree::And(_) => false,
    }
}

/// Whether a graph can hold the clause's terms at all, from the trigram bitmap alone.
///
/// Deliberately weaker than `tree_matches_nothing`: it reads no dictionary and decodes
/// no string, so it can be run over every source before any planning begins. `true`
/// means "not ruled out" — a graph with no usable index, or a term too short or too
/// non-ASCII for trigrams, is always planned.
fn may_match(graph: &Graph, tree: &PredTree) -> bool {
    let Some(idx) = usable_index(graph) else {
        return true;
    };
    match tree {
        PredTree::Leaf(p) => match p.trigrams.as_ref() {
            Some(trigrams) if !trigrams.is_empty() => idx.may_contain_all(trigrams),
            _ => true,
        },
        PredTree::Or(children) => children.iter().any(|c| may_match(graph, c)),
        PredTree::And(children) => children.iter().all(|c| may_match(graph, c)),
    }
}
