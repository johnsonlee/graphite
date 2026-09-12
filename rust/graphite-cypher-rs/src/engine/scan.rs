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
    /// Indexed like `CALL_SITE_PROPS`; `None` when no predicate touches that property.
    call_site: [Option<StringBitset>; 4],
    /// True when every predicate targets a CallSite property, so only CallSite nodes
    /// (and Annotation nodes, which expose the same names) can match.
    call_site_only: bool,
    /// CallSite node ids resolved through the persisted accelerator, ascending — the
    /// same order the sweep would have produced. `None` means sweep instead.
    ///
    /// `Some(empty)` is the case that matters most across many graphs: the term reaches
    /// nothing here, so this graph contributes no CallSite work at all.
    call_site_candidates: Option<Vec<u32>>,
    /// No Annotation node can satisfy the clause, so that tag is skipped.
    skip_annotations: bool,
    /// No pre-filter could be built, so every record must reach the WHERE re-check.
    ///
    /// This is distinct from an empty plan. A `SourcePlan` whose bitsets are all `None`
    /// matches *nothing* in the sweep, so it cannot stand in for "no opinion" — doing
    /// that silently dropped every CallSite node.
    no_prefilter: bool,
}

pub struct ScanPlan {
    variable: String,
    /// Node tags to sweep when the pushdown does not apply to a source.
    tags: Vec<u8>,
    tree: PredTree,
    /// Every leaf of `tree`, for the bitset fallback and for the CallSite-only test.
    predicates: Vec<StringPredicate>,
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
        let tree = collect_tree(where_clause, &variable)?;
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
        // Plans are built a batch at a time, in parallel, and consumed in source order.
        //
        // Resolving a term against one graph's dictionary is most of the cost of a broad
        // query, and the sixty-four graphs are independent — but the rows must still be
        // produced in order, and a satisfied LIMIT must still stop the scan. Batching
        // gives both: order is preserved, and at most one batch of planning is wasted
        // when the limit lands early.
        let sources: Vec<SourceIdx> = (0..ex.sources.len() as SourceIdx).collect();
        for batch in sources.chunks(PLAN_BATCH) {
            ex.cancel.check()?;
            let plans: Vec<SourcePlan> = if batch.len() > 1 {
                batch
                    .par_iter()
                    .map(|s| build_source_plan(*s, ex.graph(*s), &self.tree, &self.predicates))
                    .collect()
            } else {
                batch
                    .iter()
                    .map(|s| build_source_plan(*s, ex.graph(*s), &self.tree, &self.predicates))
                    .collect()
            };
            for sp in &plans {
                let source = sp.source;
                let graph = ex.graph(source);
                let scan_tags: Vec<u8> = if sp.call_site_only {
                    self.tags
                        .iter()
                        .copied()
                        .filter(|t| {
                            *t == TAG_CALL_SITE_NODE
                                || (*t == TAG_ANNOTATION_NODE && !sp.skip_annotations)
                        })
                        .collect()
                } else {
                    self.tags.clone()
                };
                for tag in scan_tags {
                    // When the accelerator resolved the CallSite candidates, those *are* the
                    // records to visit; nothing else needs looking at.
                    let indexed = match (tag, &sp.call_site_candidates) {
                        (TAG_CALL_SITE_NODE, Some(nodes)) => Some(nodes.as_slice()),
                        _ => None,
                    };
                    let ids = indexed.unwrap_or_else(|| graph.ids_by_tag(tag));
                    if ids.is_empty() {
                        continue;
                    }
                    let mut hits: Vec<u32> = Vec::new();
                    for chunk in ids.chunks(SWEEP_CHUNK) {
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
                            let mut r = row.clone();
                            r.insert(self.variable.clone(), value.clone());
                            add_provenance(&mut r, ex, &value);
                            if let Some(w) = where_clause {
                                if ev.eval(w, &r)?.as_bool() != Some(true) {
                                    continue;
                                }
                            }
                            if !consume(r)? {
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

/// Graphs planned together before any of their rows are consumed.
const PLAN_BATCH: usize = 16;

/// Candidates are examined in chunks this large, so a satisfied LIMIT stops the sweep.
const SWEEP_CHUNK: usize = 65_536;

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
        for (i, set) in sp.call_site.iter().enumerate() {
            if let Some(set) = set {
                if set.get(fields[i] as usize) {
                    return true;
                }
            }
        }
        false
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
    let pruned = |candidates: Vec<u32>| SourcePlan {
        source,
        call_site: [None, None, None, None],
        call_site_only,
        call_site_candidates: Some(candidates),
        no_prefilter: false,
        skip_annotations,
    };
    if let Some(idx) = usable_index(graph) {
        let mut memo: Vec<(StringPredicate, Vec<u32>)> = Vec::new();
        // Pruning first, and separately from enumeration. Whether a graph can match at
        // all is a question about its dictionary; whether to reach the matches through
        // postings or by sweeping records is a question about cost. Answering the second
        // used to discard the first — a term dense enough to decline the postings sent
        // the whole query to an undifferentiated sweep of all sixty-four graphs, even
        // the ones whose dictionary holds no matching string at all.
        if tree_matches_nothing(graph, idx, tree, &mut memo) {
            return pruned(Vec::new());
        }
        if let Some(candidates) = indexed_candidates(graph, idx, tree, &mut memo) {
            return pruned(candidates);
        }
    }
    // The bitset sweep can only express a disjunction. A conjunction the index could not
    // answer therefore gets no pre-filter at all: every record goes to the WHERE clause.
    if !tree.is_flat_or() {
        return SourcePlan {
            source,
            call_site: [None, None, None, None],
            call_site_only: false,
            call_site_candidates: None,
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
fn indexed_candidates(
    graph: &Graph,
    idx: &graphite_storage::callsite_index::CallSiteStringIndex,
    tree: &PredTree,
    memo: &mut Vec<(StringPredicate, Vec<u32>)>,
) -> Option<Vec<u32>> {
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
    memo: &mut Vec<(StringPredicate, Vec<u32>)>,
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
fn resolve_strings(
    graph: &Graph,
    idx: &graphite_storage::callsite_index::CallSiteStringIndex,
    p: &StringPredicate,
    memo: &mut Vec<(StringPredicate, Vec<u32>)>,
) -> Option<Vec<u32>> {
    if let Some((_, ids)) = memo.iter().find(|(q, _)| q.same_test(p)) {
        return Some(ids.clone());
    }
    let ids = matching_string_ids(graph, idx, p)?;
    memo.push((p.clone(), ids.clone()));
    Some(ids)
}

/// Ascending CallSite node ids that can satisfy this subtree.
///
/// Always a *superset* of the true matches, at every level: `OR` unions its children and
/// `AND` intersects them, and a superset of each side intersects to a superset of the
/// conjunction. Survivors are re-checked against the full WHERE clause, so a loose
/// answer costs time and never correctness.
fn eval_tree(
    graph: &Graph,
    idx: &graphite_storage::callsite_index::CallSiteStringIndex,
    tree: &PredTree,
    memo: &mut Vec<(StringPredicate, Vec<u32>)>,
) -> Option<Vec<u32>> {
    match tree {
        PredTree::Leaf(p) => leaf_candidates(graph, idx, p, memo),
        PredTree::Or(children) => {
            let mut nodes = Vec::new();
            for c in children {
                nodes.extend(eval_tree(graph, idx, c, memo)?);
            }
            nodes.sort_unstable();
            nodes.dedup();
            Some(nodes)
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
            Some(nodes)
        }
    }
}

/// Ascending node ids carrying a string that satisfies one predicate, in its property.
fn leaf_candidates(
    graph: &Graph,
    idx: &graphite_storage::callsite_index::CallSiteStringIndex,
    p: &StringPredicate,
    memo: &mut Vec<(StringPredicate, Vec<u32>)>,
) -> Option<Vec<u32>> {
    let property = CALL_SITE_PROPS.iter().position(|c| *c == p.property)?;
    let strings = resolve_strings(graph, idx, p, memo)?;
    let mut postings_total = 0usize;
    for &s in &strings {
        postings_total += idx.posting_len(property, s as usize);
        // A term this broad is cheaper to sweep: postings are random access into the
        // node ids, while the sweep reads the records in order.
        if postings_total * POSTING_SWEEP_RATIO > idx.call_site_count() {
            return None;
        }
    }
    let mut nodes: Vec<u32> = Vec::with_capacity(postings_total);
    for &s in &strings {
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
/// Below this many surviving candidates, further trigram intersection is not worth it.
const TRIGRAM_INTERSECT_FLOOR: usize = 256;
/// At most this many of a literal's trigrams are sized and intersected.
const MAX_TRIGRAM_PROBES: usize = 3;

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
    // A long literal has as many trigrams as characters, and sizing every one of their
    // posting lists costs more than the narrowing is worth. A spread-out handful is
    // enough: each probe is an independent filter, so the rarest of eight already cuts
    // the dictionary down to a short candidate list.
    let step = trigrams.len().div_ceil(MAX_TRIGRAM_PROBES).max(1);
    let mut lists: Vec<_> = trigrams
        .iter()
        .step_by(step)
        .map(|t| idx.trigram_string_ids(*t))
        .collect();
    lists.sort_by_key(|l| l.len());
    let mut candidates: Vec<u32> = lists[0].iter().collect();
    for list in &lists[1..] {
        if candidates.len() <= TRIGRAM_INTERSECT_FLOOR {
            break;
        }
        candidates.retain(|id| list.contains(*id));
    }
    // The trigram set is a filter, not an answer: check the predicate for real.
    candidates.retain(|&id| predicate_matches(graph, p, id));
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
            call_site: sets,
            call_site_only,
            call_site_candidates: Some(Vec::new()),
            no_prefilter: false,
            skip_annotations,
        };
    }
    SourcePlan {
        source,
        call_site: sets,
        call_site_only,
        call_site_candidates: None,
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
            out.push(StringPredicate {
                property,
                op,
                literal,
                transform,
                trigrams,
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
}

impl PropertySets {
    /// Total postings behind these ids: what materialising this subtree would cost.
    fn posting_cost(&self, idx: &graphite_storage::callsite_index::CallSiteStringIndex) -> usize {
        let mut total = 0usize;
        for (property, ids) in self.ids.iter().enumerate() {
            for &s in ids {
                total += idx.posting_len(property, s as usize);
            }
        }
        total
    }

    /// Ascending node ids carrying any of these strings in its own property.
    fn nodes(&self, idx: &graphite_storage::callsite_index::CallSiteStringIndex) -> Vec<u32> {
        let mut nodes = Vec::with_capacity(self.posting_cost(idx));
        for (property, ids) in self.ids.iter().enumerate() {
            for &s in ids {
                if let Some(postings) = idx.postings(property, s as usize) {
                    nodes.extend(postings);
                }
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
    memo: &mut Vec<(StringPredicate, Vec<u32>)>,
) -> Option<PropertySets> {
    let mut ids: [Vec<u32>; 4] = Default::default();
    if !collect_property_sets(graph, idx, tree, memo, &mut ids) {
        return None;
    }
    for v in ids.iter_mut() {
        v.sort_unstable();
        v.dedup();
    }
    Some(PropertySets { ids })
}

fn collect_property_sets(
    graph: &Graph,
    idx: &graphite_storage::callsite_index::CallSiteStringIndex,
    tree: &PredTree,
    memo: &mut Vec<(StringPredicate, Vec<u32>)>,
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
