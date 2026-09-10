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
        self.transform == Transform::None
            && matches!(self.op, PushOp::Equals | PushOp::StartsWith)
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
    #[allow(dead_code)] // kept alongside `set`/`get` as part of the bitset surface
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
}

pub struct ScanPlan {
    variable: String,
    /// Node tags to sweep when the pushdown does not apply to a source.
    tags: Vec<u8>,
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
        let mut preds = Vec::new();
        if !collect_disjuncts(where_clause, &variable, &mut preds) || preds.is_empty() {
            return None;
        }
        // Every predicate must target a CallSite string property for the fast sweep.
        if !preds.iter().all(|p| CALL_SITE_PROPS.contains(&p.property)) {
            return None;
        }
        Some(ScanPlan {
            variable,
            tags,
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
        for source in 0..ex.sources.len() {
            let source = source as SourceIdx;
            let graph = ex.graph(source);
            ex.cancel.check()?;
            let sp = &build_source_plan(source, graph, &self.predicates);
            let scan_tags: Vec<u8> = if sp.call_site_only {
                self.tags
                    .iter()
                    .copied()
                    .filter(|t| *t == TAG_CALL_SITE_NODE || *t == TAG_ANNOTATION_NODE)
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
                    if indexed.is_some() {
                        // Already narrowed; the WHERE re-check below decides the rest.
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
        Ok(true)
    }
}

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

fn build_source_plan(source: SourceIdx, graph: &Graph, preds: &[StringPredicate]) -> SourcePlan {
    let call_site_only = preds.iter().all(|p| CALL_SITE_PROPS.contains(&p.property));
    if let Some(candidates) = indexed_candidates(graph, preds) {
        return SourcePlan {
            source,
            call_site: [None, None, None, None],
            call_site_only,
            call_site_candidates: Some(candidates),
        };
    }
    build_sweep_plan(source, graph, preds, call_site_only)
}

/// Resolve CallSite candidates through `graph.callsite-string-index`.
///
/// `None` means the accelerator cannot (or should not) answer this predicate set, and
/// the caller must fall back to scanning the dictionary and sweeping records.
fn indexed_candidates(graph: &Graph, preds: &[StringPredicate]) -> Option<Vec<u32>> {
    let idx = graph.call_site_index()?;
    // The CSRs describe the graph's CallSite records; anything else is not ours.
    if idx.call_site_count() != graph.count_by_tag(TAG_CALL_SITE_NODE) {
        return None;
    }
    // Matching string ids per property, unioned across predicates on that property.
    //
    // The wide queries repeat one literal across all four properties, and the matching
    // string ids depend only on the literal, the operator and the transform — never on
    // which property is being tested. Resolving each distinct predicate once turns four
    // dictionary resolutions into one.
    let mut resolved: Vec<(&StringPredicate, Vec<u32>)> = Vec::new();
    let mut per_property: [Vec<u32>; 4] = Default::default();
    for p in preds {
        let slot = CALL_SITE_PROPS.iter().position(|c| *c == p.property)?;
        match resolved.iter().find(|(q, _)| q.same_test(p)) {
            Some((_, ids)) => per_property[slot].extend_from_slice(ids),
            None => {
                let ids = matching_string_ids(graph, idx, p)?;
                per_property[slot].extend_from_slice(&ids);
                resolved.push((p, ids));
            }
        }
    }
    let mut postings_total = 0usize;
    for ids in per_property.iter_mut().enumerate() {
        let (property, ids) = ids;
        ids.sort_unstable();
        ids.dedup();
        for &s in ids.iter() {
            postings_total += idx.posting_len(property, s as usize);
        }
        // A term this broad is cheaper to sweep: postings are random access into the
        // node ids, while the sweep reads the records in order.
        if postings_total * POSTING_SWEEP_RATIO > idx.call_site_count() {
            return None;
        }
    }
    let mut nodes: Vec<u32> = Vec::with_capacity(postings_total);
    for (property, ids) in per_property.iter().enumerate() {
        for &s in ids {
            if let Some(postings) = idx.postings(property, s as usize) {
                nodes.extend(postings);
            }
        }
    }
    // A node can match on more than one property, and the sweep visits each node once
    // in ascending id order, which is the order the type index stores them in.
    nodes.sort_unstable();
    nodes.dedup();
    Some(nodes)
}

/// Walking postings stops paying off once they cover this fraction of the records.
const POSTING_SWEEP_RATIO: usize = 8;
/// Below this many surviving candidates, further trigram intersection is not worth it.
const TRIGRAM_INTERSECT_FLOOR: usize = 256;
/// At most this many of a literal's trigrams are sized and intersected.
const MAX_TRIGRAM_PROBES: usize = 8;

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
    let trigrams = graphite_storage::callsite_index::literal_trigrams(&p.literal)?;
    if trigrams.is_empty() {
        return None;
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
    SourcePlan {
        source,
        call_site: sets,
        call_site_only,
        call_site_candidates: None,
    }
}

/// Flatten an OR tree into string predicates. Returns false if any leaf is unsupported.
fn collect_disjuncts(e: &Expr, variable: &str, out: &mut Vec<StringPredicate>) -> bool {
    match e {
        Expr::Or(a, b) => {
            collect_disjuncts(a, variable, out) && collect_disjuncts(b, variable, out)
        }
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
            out.push(StringPredicate {
                property,
                op,
                literal,
                transform,
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
