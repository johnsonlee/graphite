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

/// What a scan hands each surviving node to: the node, the source's shared provenance
/// value when one serves every row, and whether the plan was exact.
pub type Emit<'a> = dyn FnMut(Value, Option<&Value>, bool) -> CypherResult<bool> + 'a;

/// Properties readable straight out of a `CallSiteNode` record without decoding it.
const CALL_SITE_PROPS: [&str; 4] = ["caller_class", "caller_name", "callee_class", "callee_name"];

/// Every property name the scan can read raw off some record: CallSite's four through
/// the CallSite index, the rest through a per-type string column.
const PUSHABLE_PROPS: [&str; 25] = [
    "caller_class",
    "caller_name",
    "callee_class",
    "callee_name",
    "value",
    "name",
    "type",
    "class",
    "enum_type",
    "path",
    "source",
    "format",
    "key",
    "member",
    // Answered by decoding the record, or never true for the type; the planner
    // decides per type, so a predicate on them still skips every other type.
    "callee_signature",
    "caller_signature",
    "line",
    "static",
    "index",
    "method",
    "actual_type",
    "profile",
    // Synthesised from the graph id and the node id in cross-graph mode; folded per
    // graph without decoding anything.
    "graphId",
    "elementId",
    "qualifiedId",
];

/// Every property key a node map can carry, in cross-graph mode: what
/// `any(k IN keys(n) WHERE ...)` ranges over. `id` is on every node; an annotation's
/// value pairs add keys of their own, which is why the Annotation type is always
/// decoded for that shape.
const ALL_KEYS: [&str; 26] = {
    let mut keys = ["id"; 26];
    let mut i = 0;
    while i < PUSHABLE_PROPS.len() {
        keys[i + 1] = PUSHABLE_PROPS[i];
        i += 1;
    }
    keys
};

fn is_synthetic_key(property: &str) -> bool {
    matches!(property, "graphId" | "elementId" | "qualifiedId")
}

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
    /// `=~` over a pattern whose required literal text is known: the literal drives
    /// the trigram candidates, the compiled pattern decides each candidate.
    Regex,
}

/// What a leaf compares. Every leaf is planned per node type by what the type exposes
/// for its property; only a `Text` leaf is ever answered from a dictionary.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum LeafShape {
    /// `<property> <string op> "literal"`.
    Text,
    /// `<property> = <number>`: true only where the property holds a number.
    Numeric,
    /// `<anything> IN <property>`: true only where the property holds a list.
    Member,
}

#[derive(Clone)]
struct StringPredicate {
    /// The property name. Borrowed for the names the scan reads raw; owned for any
    /// other name, which no type but Annotation can carry, so the leaf skips the rest.
    property: std::borrow::Cow<'static, str>,
    shape: LeafShape,
    op: PushOp,
    /// The text every match must contain. For `Regex` it is the pattern's longest
    /// literal run, a superset filter; the pattern itself is what is checked.
    literal: String,
    transform: Transform,
    /// The `=~` pattern and its compiled form; `None` for every other operator.
    regex: Option<(String, std::sync::Arc<crate::eval::CompiledRegex>)>,
    /// The operand was `toString(<property>)`: identity on a string, and the only
    /// way a numeric or boolean property can satisfy a string predicate.
    via_to_string: bool,
    /// Produced by expanding `any(k IN keys(n) WHERE ...)`: the leaf stands for one
    /// possible key, and a type whose keys are open-ended (Annotation) must decode.
    from_keys: bool,
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
    fn prop(&self) -> &str {
        &self.property
    }

    /// Test against a string the caller has already transformed.
    fn matches_raw(&self, candidate: &str) -> bool {
        match self.op {
            PushOp::Equals => candidate == self.literal,
            PushOp::Contains => candidate.contains(&self.literal),
            PushOp::StartsWith => candidate.starts_with(&self.literal),
            PushOp::EndsWith => candidate.ends_with(&self.literal),
            // Only a pattern the evaluator supports is ever pushed, so a failure here
            // cannot happen; reading it as "no match" keeps the filter a subset.
            PushOp::Regex => self
                .regex
                .as_ref()
                .is_some_and(|(_, r)| r.matches(candidate).unwrap_or(false)),
        }
    }

    /// What decides a match: the literal, or for `=~` the pattern.
    fn test_text(&self) -> &str {
        match &self.regex {
            Some((pattern, _)) => pattern,
            None => &self.literal,
        }
    }

    /// True when two predicates select the same dictionary entries. The property is
    /// deliberately not part of this: it decides which records to look in, not which
    /// strings match.
    fn same_test(&self, other: &StringPredicate) -> bool {
        self.shape == other.shape
            && self.op == other.op
            && self.transform == other.transform
            && self.test_text() == other.test_text()
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
    /// CallSite candidates resolved through the persisted accelerator, ascending — the
    /// same order the sweep would have produced. `None` means sweep instead.
    ///
    /// An empty candidate set is the case that matters most across many graphs: the term
    /// reaches nothing here, so this graph contributes no CallSite work at all.
    call_site_candidates: Option<Candidates>,
    /// The candidate list is not merely a superset: every node in it satisfies the
    /// clause, so the WHERE re-check over it is redundant.
    call_site_exact: bool,
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
        let mut streams: Vec<PostingsMerge<'a>> = sides
            .iter()
            .map(|pairs| PostingsMerge::new(idx, pairs))
            .collect();
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
    /// How many distinct dictionary tests the leaves perform.
    tests: usize,
    /// What to do for each node type, indexed by tag. Types outside `tags` are skipped.
    tag_plans: Vec<TagPlan>,
    /// The clause restricted to CallSite's raw strings, with its leaves, when the
    /// CallSite type takes the indexed path.
    call_site: Option<(PredTree, Vec<StringPredicate>)>,
    /// Some leaf reads a synthetic key, whose truth depends on the graph: the per-type
    /// plans are then settled per source instead of once here.
    has_synthetic: bool,
    /// The clause the rows are checked against when the pattern carried a property
    /// map: the map's equalities conjoined with the WHERE clause, if any. `None` when
    /// the WHERE clause alone is the whole test.
    clause: Option<Expr>,
    /// A conjunct of the WHERE clause was left out of the tree because no leaf could
    /// be made of it (`n.graphId IS NOT NULL`, `n.line > 10`, `NOT ...`). The
    /// candidates are then a superset of the answer, so no survivor is ever taken as
    /// verified: every one goes through the full WHERE clause.
    relaxed: bool,
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
        let tags = match np.labels.first() {
            None => (0..graphite_storage::node::TAG_COUNT as u8).collect::<Vec<u8>>(),
            Some(l) => label_tags(l)?,
        };
        if np.labels.len() > 1 {
            return None;
        }
        // A property map `{k: v}` is `n.k = v` for every entry: planned like a
        // conjunct of the WHERE clause, and re-checked with it. Only literal values;
        // anything else stays with the matcher.
        let mut clause: Option<Expr> = where_clause.cloned();
        for (key, value) in &np.properties {
            if !matches!(
                value,
                Expr::Literal(
                    crate::ast::Literal::Str(_)
                        | crate::ast::Literal::Int(_)
                        | crate::ast::Literal::Float(_)
                )
            ) {
                return None;
            }
            let eq = Expr::Comparison {
                op: crate::ast::CmpOp::Eq,
                left: Box::new(Expr::Property {
                    expr: Box::new(Expr::Variable(variable.clone())),
                    key: key.clone(),
                }),
                right: Box::new(value.clone()),
            };
            clause = Some(match clause {
                Some(w) => Expr::And(Box::new(eq), Box::new(w)),
                None => eq,
            });
        }
        let combined = clause.as_ref()?;
        let mut relaxed = false;
        let mut tree = collect_tree(combined, &variable, &mut relaxed)?;
        let tests = assign_test_ids(&mut tree);
        let mut preds = Vec::new();
        tree.leaves(&mut preds);
        if preds.is_empty() {
            return None;
        }
        // Per type: which leaves it can answer raw, and whether it needs decoding at all.
        // Annotation keys are settled per graph, since any dictionary string can be one.
        let has_synthetic = preds.iter().any(|p| is_synthetic_key(p.prop()));
        // A synthetic leaf is unknown here: it makes its type generic until a source
        // folds it, and every source re-plans when any leaf is synthetic.
        let tag_plans: Vec<TagPlan> = (0..graphite_storage::node::TAG_COUNT as u8)
            .map(|tag| {
                if !tags.contains(&tag) {
                    return TagPlan::Skip;
                }
                tag_plan(&tree, tag, &|_| false, &|_| Fold::Unknown)
            })
            .collect();
        let call_site = match &tag_plans[TAG_CALL_SITE_NODE as usize] {
            TagPlan::CallSite(t) => {
                let mut leaves = Vec::new();
                t.leaves(&mut leaves);
                Some((t.clone(), leaves))
            }
            _ => None,
        };
        Some(ScanPlan {
            variable,
            tags,
            tree,
            tests,
            tag_plans,
            call_site,
            has_synthetic,
            clause: if np.properties.is_empty() {
                None
            } else {
                clause
            },
            relaxed,
        })
    }

    /// Whether a survivor a stream reports as verified may skip the WHERE clause: never
    /// when a conjunct was dropped from the plan.
    fn verified(&self, stream_verified: bool) -> bool {
        stream_verified && !self.relaxed
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
        let where_clause = self.clause.as_ref().or(where_clause);
        self.run_inner(
            ex,
            ev,
            row,
            where_clause,
            &mut |value, provenance, verified| {
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
            },
        )
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
        // Which raw CallSite field each key names, decided once per query rather than
        // once per value: the four string-id keys, the graph id in cross mode and the
        // node id come straight off the record; anything else takes the general path.
        let fields: Vec<RawField> = keys.iter().map(|k| RawField::of(k, ex.cross)).collect();
        let all_raw = fields.iter().all(|f| !matches!(f, RawField::General));
        let empty = Row::new();
        let where_clause = self.clause.as_ref().or(where_clause);
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
            let node = match &value {
                Value::Node(n) => *n,
                _ => return Ok(true),
            };
            let graph = ex.graph(node.source);
            let raw = if all_raw {
                graph.call_site_strings(node.id)
            } else {
                None
            };
            let values: Vec<Value> = match raw {
                // One record read for the whole row instead of one per column.
                Some(cs) => fields
                    .iter()
                    .map(|f| match f {
                        RawField::CallerClass => Value::str(graph.str(cs.caller_class)),
                        RawField::CallerName => Value::str(graph.str(cs.caller_name)),
                        RawField::CalleeClass => Value::str(graph.str(cs.callee_class)),
                        RawField::CalleeName => Value::str(graph.str(cs.callee_name)),
                        RawField::GraphId => {
                            Value::str(ex.sources[node.source as usize].id.clone())
                        }
                        RawField::Id => Value::Int(node.id as i64),
                        RawField::General => unreachable!("all keys are raw fields"),
                    })
                    .collect(),
                None => keys.iter().map(|k| ev.property(&value, k)).collect(),
            };
            sink(values, ex.sources[node.source as usize].id.clone())
        })
    }

    /// The matching nodes themselves, after the WHERE where the plan was not exact,
    /// in scan order. For callers that need the references rather than rows.
    pub fn matching_refs(
        &self,
        ex: &Executor,
        ev: &Evaluator,
        where_clause: Option<&Expr>,
        sink: &mut dyn FnMut(NodeRef) -> CypherResult<bool>,
    ) -> CypherResult<bool> {
        let empty = Row::new();
        let where_clause = self.clause.as_ref().or(where_clause);
        self.run_inner(ex, ev, &empty, where_clause, &mut |value, _, verified| {
            let Value::Node(n) = value else {
                return Ok(true);
            };
            if !verified {
                if let Some(w) = where_clause {
                    let mut r = Row::with_capacity(1);
                    r.insert(self.variable.clone(), Value::Node(n));
                    if ev.eval(w, &r)?.as_bool() != Some(true) {
                        return Ok(true);
                    }
                }
            }
            sink(n)
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
        emit: &mut Emit<'_>,
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
        // Only the CallSite type is planned ahead in batches; every other type is
        // decided when the sweep reaches its graph. A graph the CallSite prefilter
        // rules out is still visited for the other types it holds.
        let cs_tree: Option<&PredTree> = self.call_site.as_ref().map(|(t, _)| t);
        let cs_preds: &[StringPredicate] = self
            .call_site
            .as_ref()
            .map(|(_, p)| p.as_slice())
            .unwrap_or(&[]);
        let all_sources: Vec<SourceIdx> = (0..ex.sources.len() as SourceIdx).collect();
        let sources: Vec<SourceIdx> = match cs_tree {
            Some(t) => all_sources
                .iter()
                .copied()
                .filter(|s| may_match(ex.graph(*s), t))
                .collect(),
            None => Vec::new(),
        };
        // The batched, parallel planning below is for the CallSite type. It also covers
        // the Annotation type when the clause names CallSite properties, since an
        // annotation's value pairs can carry any key: that type is settled per graph
        // after the graph's CallSite records, as it always was.
        let cs_only = cs_tree.is_some()
            && !self.has_synthetic
            && self.tag_plans.iter().enumerate().all(|(tag, p)| {
                tag == TAG_CALL_SITE_NODE as usize
                    || matches!(p, TagPlan::Skip)
                    || (tag == TAG_ANNOTATION_NODE as usize && matches!(p, TagPlan::Generic))
            });
        if !cs_only {
            // Types other than CallSite are swept per graph, in source order, with the
            // CallSite plan for that graph built on the spot when it has one.
            return self.run_by_source(ex, ev, row, &all_sources, cs_tree, cs_preds, emit);
        }
        // Planned in batches, in parallel within each: see `batch_schedule`.
        for batch in batch_schedule(&sources) {
            ex.cancel.check()?;
            let plans: Vec<SourcePlan> = if batch.len() > 1 {
                batch
                    .par_iter()
                    .map(|s| {
                        build_source_plan(
                            *s,
                            ex.graph(*s),
                            &ex.sources[*s as usize].id,
                            cs_tree.expect("CallSite plan"),
                            cs_preds,
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
                            &ex.sources[*s as usize].id,
                            cs_tree.expect("CallSite plan"),
                            cs_preds,
                            self.tests,
                        )
                    })
                    .collect()
            };
            for sp in &plans {
                if !self.sweep_call_sites_of(ex, sp, row, emit)? {
                    return Ok(false);
                }
                if matches!(
                    self.tag_plans[TAG_ANNOTATION_NODE as usize],
                    TagPlan::Generic
                ) && !self.sweep_annotations(ex, sp.source, row, emit)?
                {
                    return Ok(false);
                }
            }
        }
        Ok(true)
    }

    /// The Annotation records of one graph, by the plan the graph's dictionary allows:
    /// a key no string in it spells reaches no annotation, so the type is often skipped
    /// outright; otherwise its raw fields are swept or every record goes through WHERE.
    fn sweep_annotations(
        &self,
        ex: &Executor,
        source: SourceIdx,
        row: &Row,
        emit: &mut Emit<'_>,
    ) -> CypherResult<bool> {
        let graph = ex.graph(source);
        let tag = TAG_ANNOTATION_NODE;
        if graph.ids_by_tag(tag).is_empty() {
            return Ok(true);
        }
        let plan = tag_plan(
            &self.tree,
            tag,
            &|key| {
                !matches!(key, "name" | "class" | "member" | "values")
                    && graph.strings.index_of(key).is_none()
            },
            &|p| synthetic_truth(ex, source, p),
        );
        let mut stream = match plan {
            TagPlan::Skip | TagPlan::CallSite(_) => return Ok(true),
            TagPlan::All => TypeStream::All {
                ids: graph.ids_by_tag(tag),
                pos: 0,
            },
            TagPlan::Generic => TypeStream::Generic {
                ids: graph.ids_by_tag(tag),
                pos: 0,
            },
            TagPlan::Column(t) => {
                let graph_id = &ex.sources[source as usize].id;
                let mut memos = 0usize;
                let Some(tree) = ColumnTree::resolve(graph, graph_id, &t, tag, &mut memos) else {
                    return Ok(true);
                };
                let ids = tree.row_ids(graph, tag);
                TypeStream::column(tree, ids, memos)
            }
        };
        let provenance: Option<Value> = (ex.cross
            && !row.contains_key(super::pipeline::INTERNAL_PROVENANCE_KEY))
        .then(|| Value::list(vec![Value::Str(ex.sources[source as usize].id.clone())]));
        let verified = stream.verified();
        while let Some(id) = stream.next(ex, graph)? {
            ex.tick()?;
            let value = Value::Node(NodeRef { source, id });
            if !emit(value, provenance.as_ref(), self.verified(verified))? {
                return Ok(false);
            }
        }
        Ok(true)
    }

    /// Every type of one graph, in tag order, each by its own plan.
    ///
    /// Graphs are prepared in batches ahead of the sweep -- each graph's column
    /// resolutions and CallSite plan, in parallel across the batch -- and then swept
    /// in source order, so the row order is the sequential one while the planning,
    /// which is most of the cost of a broad term over many graphs, uses every core.
    /// The batches follow the CallSite schedule: one graph, one per thread, then
    /// doubling, so a LIMIT met early never pays for preparing what it will not read.
    #[allow(clippy::too_many_arguments)]
    fn run_by_source(
        &self,
        ex: &Executor,
        ev: &Evaluator,
        row: &Row,
        sources: &[SourceIdx],
        cs_tree: Option<&PredTree>,
        cs_preds: &[StringPredicate],
        emit: &mut Emit<'_>,
    ) -> CypherResult<bool> {
        let _ = ev;
        for batch in batch_schedule(sources) {
            ex.cancel.check()?;
            let prepared: Vec<Prepared> = if batch.len() > 1 {
                batch
                    .par_iter()
                    .map(|s| self.prepare_source(ex, *s, cs_tree, cs_preds))
                    .collect()
            } else {
                batch
                    .iter()
                    .map(|s| self.prepare_source(ex, *s, cs_tree, cs_preds))
                    .collect()
            };
            for p in prepared {
                if !self.sweep_prepared(ex, row, p, emit)? {
                    return Ok(false);
                }
            }
        }
        Ok(true)
    }

    /// Plan one graph: settle the per-type plans it needs settled per graph, resolve
    /// its column trees and build its CallSite plan.
    fn prepare_source(
        &self,
        ex: &Executor,
        source: SourceIdx,
        cs_tree: Option<&PredTree>,
        cs_preds: &[StringPredicate],
    ) -> Prepared {
        let graph = ex.graph(source);
        let graph_id: &str = &ex.sources[source as usize].id;
        // With a synthetic key in the clause the per-type plans depend on this
        // graph's id: fold the leaves for it and plan again.
        let synthetic = |p: &StringPredicate| synthetic_truth(ex, source, p);
        let source_plans: Option<Vec<TagPlan>> = self.has_synthetic.then(|| {
            (0..graphite_storage::node::TAG_COUNT as u8)
                .map(|tag| {
                    if !self.tags.contains(&tag) {
                        TagPlan::Skip
                    } else {
                        tag_plan(&self.tree, tag, &|_| false, &synthetic)
                    }
                })
                .collect()
        });
        let plans: &[TagPlan] = source_plans.as_deref().unwrap_or(&self.tag_plans);
        let cs_plan: Option<SourcePlan> = match &plans[TAG_CALL_SITE_NODE as usize] {
            TagPlan::CallSite(t) => {
                let leaves: Vec<StringPredicate>;
                let (t, preds): (&PredTree, &[StringPredicate]) = if source_plans.is_some() {
                    let mut out = Vec::new();
                    t.leaves(&mut out);
                    leaves = out;
                    (t, &leaves)
                } else {
                    (cs_tree.expect("CallSite plan"), cs_preds)
                };
                may_match(graph, t)
                    .then(|| build_source_plan(source, graph, graph_id, t, preds, self.tests))
            }
            _ => None,
        };
        let mut streams: Vec<PreparedStream> = Vec::new();
        for &tag in &self.tags {
            let plan = &plans[tag as usize];
            // An annotation's keys are whatever strings its value pairs carry, so a
            // key absent from this graph's dictionary reaches no annotation here.
            let annotation_plan;
            let plan = if tag == TAG_ANNOTATION_NODE && matches!(plan, TagPlan::Generic) {
                annotation_plan = tag_plan(
                    &self.tree,
                    tag,
                    &|key| {
                        !matches!(key, "name" | "class" | "member" | "values")
                            && graph.strings.index_of(key).is_none()
                    },
                    &synthetic,
                );
                &annotation_plan
            } else {
                plan
            };
            let stream = match plan {
                TagPlan::Skip => continue,
                TagPlan::All | TagPlan::Generic => {
                    if graph.ids_by_tag(tag).is_empty() {
                        continue;
                    }
                    PreparedStream::Whole {
                        tag,
                        verified: matches!(plan, TagPlan::All),
                    }
                }
                TagPlan::CallSite(_) => {
                    if cs_plan.is_none() {
                        continue;
                    }
                    PreparedStream::CallSite
                }
                TagPlan::Column(t) => {
                    let mut memos = 0usize;
                    match ColumnTree::resolve(graph, graph_id, t, tag, &mut memos) {
                        Some(tree) => PreparedStream::Column {
                            tag,
                            tree: Box::new(tree),
                            memos,
                        },
                        None => continue,
                    }
                }
            };
            streams.push(stream);
        }
        Prepared {
            source,
            cs_plan,
            streams,
        }
    }

    /// Sweep one prepared graph's types, merged by node id: the Kotlin server walks an
    /// unlabelled scan in id order across types, and a LIMIT must cut the same rows.
    fn sweep_prepared(
        &self,
        ex: &Executor,
        row: &Row,
        prepared: Prepared,
        emit: &mut Emit<'_>,
    ) -> CypherResult<bool> {
        let source = prepared.source;
        let graph = ex.graph(source);
        let provenance: Option<Value> = (ex.cross
            && !row.contains_key(super::pipeline::INTERNAL_PROVENANCE_KEY))
        .then(|| Value::list(vec![Value::Str(ex.sources[source as usize].id.clone())]));
        let cs_plan = prepared.cs_plan;
        let mut streams: Vec<(Option<u32>, TypeStream)> = Vec::new();
        for stream in prepared.streams {
            let stream = match stream {
                PreparedStream::Whole { tag, verified } => {
                    let ids = graph.ids_by_tag(tag);
                    if verified {
                        TypeStream::All { ids, pos: 0 }
                    } else {
                        TypeStream::Generic { ids, pos: 0 }
                    }
                }
                PreparedStream::CallSite => match cs_plan
                    .as_ref()
                    .and_then(|sp| CallSiteStream::new(graph, sp))
                {
                    Some(cs) => TypeStream::CallSite(cs),
                    None => continue,
                },
                PreparedStream::Column { tag, tree, memos } => {
                    let ids = tree.row_ids(graph, tag);
                    TypeStream::column(*tree, ids, memos)
                }
            };
            streams.push((None, stream));
        }
        for (head, stream) in streams.iter_mut() {
            *head = stream.next(ex, graph)?;
        }
        streams.retain(|(head, _)| head.is_some());
        // The smallest head across streams is the next node in id order.
        while let Some(best) = streams
            .iter()
            .enumerate()
            .filter_map(|(i, (h, _))| h.map(|id| (id, i)))
            .min()
            .map(|(_, i)| i)
        {
            let (head, stream) = &mut streams[best];
            let id = head.take().expect("a head");
            ex.tick()?;
            let value = Value::Node(NodeRef { source, id });
            if !emit(value, provenance.as_ref(), self.verified(stream.verified()))? {
                return Ok(false);
            }
            *head = stream.next(ex, graph)?;
            if head.is_none() {
                streams.swap_remove(best);
            }
        }
        Ok(true)
    }

    /// The CallSite records of one planned source, through its plan.
    fn sweep_call_sites_of(
        &self,
        ex: &Executor,
        sp: &SourcePlan,
        row: &Row,
        emit: &mut Emit<'_>,
    ) -> CypherResult<bool> {
        let source = sp.source;
        let graph = ex.graph(source);
        // Every row from this source carries the same provenance, so it is built once
        // here and cloned in -- an `Arc` bump per row -- rather than assembled per row
        // from a fresh list, a sort and two allocations. Only when the base row has
        // none of its own; a row that already names graphs is merged the general way.
        let provenance: Option<Value> = (ex.cross
            && !row.contains_key(super::pipeline::INTERNAL_PROVENANCE_KEY))
        .then(|| Value::list(vec![Value::Str(ex.sources[source as usize].id.clone())]));
        let Some(mut stream) = CallSiteStream::new(graph, sp) else {
            return Ok(true);
        };
        while let Some(id) = stream.next(ex)? {
            ex.tick()?;
            let value = Value::Node(NodeRef { source, id });
            if !emit(value, provenance.as_ref(), self.verified(stream.verified))? {
                return Ok(false);
            }
        }
        Ok(true)
    }
}

/// The CallSite candidates of one planned source, produced one at a time in ascending
/// id order, so that they can be merged with other types' candidates by id.
///
/// A materialised candidate list (or the whole tag) is walked in place; a disjunction's
/// union is merged a chunk at a time instead, so a satisfied LIMIT never pays for the
/// postings it does not read. The first merge chunk is small and each one doubles:
/// pulling a full sweep chunk first meant merging sixty-five thousand ids to satisfy a
/// LIMIT of two hundred.
struct CallSiteStream<'a> {
    graph: &'a Graph,
    sp: &'a SourcePlan,
    slice: Option<&'a [u32]>,
    merge: Option<Lazy<'a>>,
    indexed: bool,
    hits: Vec<u32>,
    merged: Vec<u32>,
    hit_pos: usize,
    offset: usize,
    want: usize,
    done: bool,
    /// When the accelerator answered exactly, the candidates are the matches:
    /// re-checking WHERE would decode four strings per record to re-derive what the
    /// dictionary already decided. Only an exact plan skips it.
    verified: bool,
}

impl<'a> CallSiteStream<'a> {
    fn new(graph: &'a Graph, sp: &'a SourcePlan) -> Option<CallSiteStream<'a>> {
        let indexed = sp.call_site_candidates.as_ref();
        let slice: Option<&'a [u32]> = match indexed {
            Some(Candidates::Nodes(nodes)) => Some(nodes.as_slice()),
            Some(Candidates::Union(_) | Candidates::Intersect(_)) => None,
            None => Some(graph.ids_by_tag(TAG_CALL_SITE_NODE)),
        };
        if slice.is_some_and(<[u32]>::is_empty) {
            return None;
        }
        let merge = match (indexed, usable_index(graph)) {
            (Some(Candidates::Union(pairs)), Some(idx)) => {
                Some(Lazy::Union(PostingsMerge::new(idx, pairs)))
            }
            (Some(Candidates::Intersect(sides)), Some(idx)) => {
                Some(Lazy::Intersect(IntersectMerge::new(idx, sides)))
            }
            _ => None,
        };
        if slice.is_none() && merge.is_none() {
            return None;
        }
        Some(CallSiteStream {
            graph,
            sp,
            slice,
            merge,
            indexed: indexed.is_some(),
            hits: Vec::new(),
            merged: Vec::new(),
            hit_pos: 0,
            offset: 0,
            want: MERGE_FIRST_CHUNK,
            done: false,
            verified: sp.call_site_exact && !sp.no_prefilter,
        })
    }

    /// Fill `hits` with the next chunk's survivors; false when there is no next chunk.
    fn refill(&mut self, ex: &Executor) -> CypherResult<bool> {
        loop {
            if self.done {
                return Ok(false);
            }
            let chunk: &[u32] = match self.slice {
                Some(ids) => {
                    if self.offset >= ids.len() {
                        self.done = true;
                        return Ok(false);
                    }
                    let end = (self.offset + SWEEP_CHUNK).min(ids.len());
                    let c = &ids[self.offset..end];
                    self.offset = end;
                    c
                }
                None => {
                    match self.merge.as_mut() {
                        Some(m) => m.next_chunk(self.want, &mut self.merged),
                        None => {
                            self.done = true;
                            return Ok(false);
                        }
                    }
                    self.want = (self.want * 2).min(SWEEP_CHUNK);
                    if self.merged.is_empty() {
                        self.done = true;
                        return Ok(false);
                    }
                    &self.merged[..]
                }
            };
            ex.cancel.check()?;
            self.hits.clear();
            self.hit_pos = 0;
            if self.indexed || self.sp.no_prefilter {
                // Already narrowed, or never narrowed: either way WHERE decides.
                self.hits.extend_from_slice(chunk);
            } else {
                sweep_call_sites(self.graph, chunk, self.sp, &mut self.hits);
            }
            if !self.hits.is_empty() {
                return Ok(true);
            }
        }
    }

    fn next(&mut self, ex: &Executor) -> CypherResult<Option<u32>> {
        if self.hit_pos >= self.hits.len() && !self.refill(ex)? {
            return Ok(None);
        }
        let id = self.hits[self.hit_pos];
        self.hit_pos += 1;
        Ok(Some(id))
    }
}

/// One node type's candidates of one graph, in ascending id order.
/// One graph, planned and ready to sweep.
struct Prepared {
    source: SourceIdx,
    cs_plan: Option<SourcePlan>,
    streams: Vec<PreparedStream>,
}

/// One type of a prepared graph.
enum PreparedStream {
    /// Every record of the type: already verified, or left to WHERE.
    Whole { tag: u8, verified: bool },
    /// The graph's CallSite plan.
    CallSite,
    /// The type's column tree, resolved, and how many record leaves it has.
    Column {
        tag: u8,
        tree: Box<ColumnTree>,
        memos: usize,
    },
}

/// Batches start at one graph and double from there. A satisfied LIMIT usually lands
/// in the first batch, and a dense term costs real planning per graph, so a first
/// batch of sixteen on four cores meant four graphs planned serially per core before
/// a single row could be produced -- half a millisecond on a query that then took one
/// graph's rows and stopped.
///
/// The first is a single graph planned on the calling thread: a term dense enough to
/// fill its LIMIT from one graph gets its rows without a thread fan-out. The second is
/// a parallel batch, one graph per thread, for a term that needed a few more. Past
/// that the term is rare, and a rare term is missing from most graphs -- so what
/// remains is mostly establishing emptiness, at a few microseconds per graph, and the
/// cost of doing that is dominated by how many times the work is fanned out and
/// joined, not by the work. Bounded, not unbounded: doubling up to four per thread.
/// Planning everything left in one batch meant a LIMIT satisfied by the sixth graph
/// waited for the other fifty-nine to be planned; capping the batch keeps a late
/// first hit's wait proportional to where it lands.
fn batch_schedule(sources: &[SourceIdx]) -> Vec<&[SourceIdx]> {
    let threads = rayon::current_num_threads().max(1);
    let mut batches: Vec<&[SourceIdx]> = Vec::new();
    let mut rest = sources;
    let mut size = 1usize;
    while !rest.is_empty() {
        let (head, tail) = rest.split_at(size.min(rest.len()));
        batches.push(head);
        rest = tail;
        size = if size == 1 {
            threads
        } else {
            (size * 2).min(threads * 4)
        };
    }
    batches
}

enum TypeStream<'a> {
    /// Every record of the type; WHERE decides.
    Generic {
        ids: &'a [u32],
        pos: usize,
    },
    /// Every record of the type, each already known to satisfy the clause.
    All {
        ids: &'a [u32],
        pos: usize,
    },
    /// The column rows that satisfy the resolved plan; exact.
    Column {
        tree: ColumnTree,
        ids: &'a [u32],
        pos: usize,
        /// Per-thread state for the row tests; `memos` sizes it.
        memos: usize,
        state: RowState,
        /// Hits of the last chunk tested across threads, when the tree reads records.
        hits: Vec<u32>,
        hit_pos: usize,
    },
    CallSite(CallSiteStream<'a>),
}

impl<'a> TypeStream<'a> {
    fn column(tree: ColumnTree, ids: &'a [u32], memos: usize) -> TypeStream<'a> {
        let state = tree.state(memos);
        TypeStream::Column {
            tree,
            ids,
            pos: 0,
            memos,
            state,
            hits: Vec::new(),
            hit_pos: 0,
        }
    }
}

impl TypeStream<'_> {
    fn verified(&self) -> bool {
        match self {
            TypeStream::Generic { .. } => false,
            TypeStream::All { .. } => true,
            TypeStream::Column { .. } => true,
            TypeStream::CallSite(s) => s.verified,
        }
    }

    fn next(&mut self, ex: &Executor, graph: &Graph) -> CypherResult<Option<u32>> {
        match self {
            TypeStream::Generic { ids, pos } | TypeStream::All { ids, pos } => {
                if *pos >= ids.len() {
                    return Ok(None);
                }
                let id = ids[*pos];
                *pos += 1;
                Ok(Some(id))
            }
            TypeStream::Column {
                tree,
                ids,
                pos,
                memos,
                state,
                hits,
                hit_pos,
            } => {
                if !tree.reads_records() {
                    while *pos < ids.len() {
                        let i = *pos;
                        *pos += 1;
                        if i % SWEEP_CHUNK == 0 {
                            ex.cancel.check()?;
                        }
                        if tree.matches(graph, i, ids[i], state) {
                            return Ok(Some(ids[i]));
                        }
                    }
                    return Ok(None);
                }
                // Reading records is the costly test: a chunk of rows at a time,
                // across threads, and the chunk's hits handed out one by one, so a
                // LIMIT met early still reads one chunk.
                loop {
                    if *hit_pos < hits.len() {
                        let id = hits[*hit_pos];
                        *hit_pos += 1;
                        return Ok(Some(id));
                    }
                    if *pos >= ids.len() {
                        return Ok(None);
                    }
                    ex.cancel.check()?;
                    let start = *pos;
                    let end = (start + SWEEP_CHUNK).min(ids.len());
                    *pos = end;
                    const ROW_SUBCHUNK: usize = 4096;
                    let found: Vec<Vec<u32>> = ids[start..end]
                        .par_chunks(ROW_SUBCHUNK)
                        .enumerate()
                        .map(|(k, sub)| {
                            let mut st = tree.state(*memos);
                            let base = start + k * ROW_SUBCHUNK;
                            sub.iter()
                                .enumerate()
                                .filter(|(j, &id)| tree.matches(graph, base + j, id, &mut st))
                                .map(|(_, &id)| id)
                                .collect()
                        })
                        .collect();
                    *hits = found.concat();
                    *hit_pos = 0;
                }
            }
            TypeStream::CallSite(s) => s.next(ex),
        }
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

/// True when some leaf reads a CallSite property the index does not cover.
fn needs_raw_sweep(tree: &PredTree) -> bool {
    match tree {
        PredTree::Leaf(p) => is_raw_call_site_prop(p.prop()) || is_synthetic_key(p.prop()),
        PredTree::Or(cs) | PredTree::And(cs) => cs.iter().any(needs_raw_sweep),
    }
}

/// A CallSite leaf as the record sweep tests it.
enum RawLeaf {
    /// One of the four indexed strings: the record's string id is in the set.
    Field(usize, StringBitset),
    /// The caller's or callee's signature, composed from the record's string ids and
    /// remembered per distinct method, in the memo at this index.
    Signature { callee: bool, memo: usize },
    /// The line number as text, or against a number.
    Line(Option<f64>),
    /// The node id as text, or against a number.
    NodeId(Option<f64>),
    /// A synthetic key tested per node: the graph's prefix, then the id.
    Synthetic { prefix: String, with_id: bool },
}

/// The CallSite tree with each leaf ready to test one record.
enum RawTree {
    Leaf(RawLeaf, StringPredicate),
    Or(Vec<RawTree>),
    And(Vec<RawTree>),
}

/// A signature leaf's memo, keyed by the method's string ids -- class, name,
/// parameters, return type -- so a method with a thousand call sites or locals
/// composes its signature once.
#[derive(Default)]
struct SignatureMemo {
    memo: std::collections::HashMap<Box<[u32]>, bool>,
    key: Vec<u32>,
    text: String,
}

impl SignatureMemo {
    /// Test the method descriptor starting at `start` in `data` against `p`.
    fn matches(&mut self, graph: &Graph, data: &[u8], start: usize, p: &StringPredicate) -> bool {
        use graphite_storage::io::read_i32_at;
        let params = read_i32_at(data, start + 8).max(0) as usize;
        let end = start + 16 + params * 4;
        // class, name, parameters, return type; the count is implied.
        self.key.clear();
        self.key.push(read_i32_at(data, start) as u32);
        self.key.push(read_i32_at(data, start + 4) as u32);
        let mut at = start + 12;
        while at < end {
            self.key.push(read_i32_at(data, at) as u32);
            at += 4;
        }
        if let Some(&hit) = self.memo.get(self.key.as_slice()) {
            return hit;
        }
        let key = &self.key;
        let text = &mut self.text;
        text.clear();
        text.push_str(graph.str(key[0]));
        text.push('.');
        text.push_str(graph.str(key[1]));
        text.push('(');
        for (i, param) in key[2..key.len() - 1].iter().enumerate() {
            if i > 0 {
                text.push(',');
            }
            text.push_str(graph.str(*param));
        }
        text.push(')');
        let hit = match p.transform {
            Transform::None => p.matches_raw(text),
            Transform::Lowercase => {
                let lowered: String = text.chars().flat_map(|c| c.to_lowercase()).collect();
                p.matches_raw(&lowered)
            }
        };
        self.memo.insert(key.clone().into_boxed_slice(), hit);
        hit
    }
}

/// Per-thread state of the raw sweep: one signature memo per signature leaf.
struct RawSweepState {
    memos: Vec<SignatureMemo>,
    text: String,
}

/// One CallSite record's raw fields, located without decoding it.
struct RawRecord {
    /// `[caller_class, caller_name, callee_class, callee_name]`, as `CALL_SITE_PROPS`.
    fields: [u32; 4],
    /// Byte offsets of the caller's and callee's descriptors.
    caller: usize,
    callee: usize,
    line: i32,
    id: u32,
}

impl RawRecord {
    fn read(data: &[u8], offset: usize, id: u32) -> RawRecord {
        use graphite_storage::io::read_i32_at;
        let p = offset + graphite_storage::node::NODE_HEADER_BYTES;
        let caller_params = read_i32_at(data, p + 8).max(0) as usize;
        let callee_at = p + 16 + caller_params * 4;
        let callee_params = read_i32_at(data, callee_at + 8).max(0) as usize;
        let callee_end = callee_at + 16 + callee_params * 4;
        RawRecord {
            fields: [
                read_i32_at(data, p) as u32,
                read_i32_at(data, p + 4) as u32,
                read_i32_at(data, callee_at) as u32,
                read_i32_at(data, callee_at + 4) as u32,
            ],
            caller: p,
            callee: callee_at,
            line: read_i32_at(data, callee_end),
            id,
        }
    }
}

impl RawTree {
    fn build(
        graph: &Graph,
        graph_id: &str,
        idx: Option<&graphite_storage::callsite_index::CallSiteStringIndex>,
        tree: &PredTree,
        memo: &mut Memo,
        memos: &mut usize,
    ) -> RawTree {
        match tree {
            PredTree::Leaf(p) => {
                let number = || leaf_number(p);
                let leaf = match p.prop() {
                    key if is_synthetic_key(key) => RawLeaf::Synthetic {
                        prefix: synthetic_prefix(graph_id, p),
                        with_id: key != "graphId",
                    },
                    "callee_signature" | "caller_signature" => {
                        let index = *memos;
                        *memos += 1;
                        RawLeaf::Signature {
                            callee: p.prop() == "callee_signature",
                            memo: index,
                        }
                    }
                    "line" => RawLeaf::Line(number()),
                    "id" => RawLeaf::NodeId(number()),
                    prop => {
                        let field = CALL_SITE_PROPS
                            .iter()
                            .position(|c| *c == prop)
                            .expect("a CallSite leaf");
                        let n = graph.strings.len();
                        let mut set = StringBitset::new(n);
                        let resolved = idx.and_then(|idx| resolve_strings(graph, idx, p, memo));
                        match resolved {
                            Some(ids) => ids.iter().for_each(|&s| set.set(s as usize)),
                            // The index declined the term: the dictionary decides.
                            None => (0..n as u32)
                                .filter(|&s| predicate_matches(graph, p, s))
                                .for_each(|s| set.set(s as usize)),
                        }
                        RawLeaf::Field(field, set)
                    }
                };
                RawTree::Leaf(leaf, p.clone())
            }
            PredTree::Or(cs) => RawTree::Or(
                cs.iter()
                    .map(|c| RawTree::build(graph, graph_id, idx, c, memo, memos))
                    .collect(),
            ),
            PredTree::And(cs) => RawTree::And(
                cs.iter()
                    .map(|c| RawTree::build(graph, graph_id, idx, c, memo, memos))
                    .collect(),
            ),
        }
    }

    fn eval(&self, graph: &Graph, data: &[u8], r: &RawRecord, st: &mut RawSweepState) -> bool {
        match self {
            RawTree::Or(cs) => cs.iter().any(|c| c.eval(graph, data, r, st)),
            RawTree::And(cs) => cs.iter().all(|c| c.eval(graph, data, r, st)),
            RawTree::Leaf(RawLeaf::Field(i, set), _) => set.get(r.fields[*i] as usize),
            RawTree::Leaf(RawLeaf::Line(number), p) => {
                // `-1` is stored for an unknown line, which reads as null.
                if r.line < 0 {
                    return false;
                }
                number_or_text(*number, r.line as u32, p)
            }
            RawTree::Leaf(RawLeaf::NodeId(number), p) => number_or_text(*number, r.id, p),
            RawTree::Leaf(RawLeaf::Synthetic { prefix, with_id }, p) => {
                st.text.clear();
                st.text.push_str(prefix);
                if *with_id {
                    let mut buf = [0u8; 10];
                    st.text.push_str(u32_text(r.id, &mut buf));
                }
                p.matches_raw(&st.text)
            }
            RawTree::Leaf(RawLeaf::Signature { callee, memo }, p) => {
                let start = if *callee { r.callee } else { r.caller };
                st.memos[*memo].matches(graph, data, start, p)
            }
        }
    }
}

/// A number read off the record against the leaf: equal to the literal number, or as
/// text through the string predicate.
fn number_or_text(number: Option<f64>, value: u32, p: &StringPredicate) -> bool {
    match number {
        Some(n) => n == value as f64,
        None => {
            let mut buf = [0u8; 10];
            p.matches_raw(u32_text(value, &mut buf))
        }
    }
}

/// Every CallSite record of the graph that satisfies the tree, in id order, each
/// tested from its raw fields.
fn raw_sweep(graph: &Graph, graph_id: &str, tree: &PredTree, tests: usize) -> Vec<u32> {
    let idx = usable_index(graph);
    let mut memo = Memo::new(tests);
    let mut memos = 0usize;
    let raw = RawTree::build(graph, graph_id, idx, tree, &mut memo, &mut memos);
    let ids = graph.ids_by_tag(TAG_CALL_SITE_NODE);
    let data = graph.nodedata();
    let chunks: Vec<Vec<u32>> = ids
        .par_chunks(SWEEP_CHUNK)
        .map(|chunk| {
            let mut st = RawSweepState {
                memos: (0..memos).map(|_| SignatureMemo::default()).collect(),
                text: String::new(),
            };
            chunk
                .iter()
                .copied()
                .filter(|&id| {
                    graph.node_offset(id).is_some_and(|offset| {
                        let record = RawRecord::read(data, offset, id);
                        raw.eval(graph, data, &record, &mut st)
                    })
                })
                .collect()
        })
        .collect();
    chunks.concat()
}

fn build_source_plan(
    source: SourceIdx,
    graph: &Graph,
    graph_id: &str,
    tree: &PredTree,
    preds: &[StringPredicate],
    tests: usize,
) -> SourcePlan {
    let pruned = |candidates: Candidates, exact: bool| SourcePlan {
        source,
        call_site: Vec::new(),
        call_site_candidates: Some(candidates),
        call_site_exact: exact,
        no_prefilter: false,
    };
    if needs_raw_sweep(tree) {
        return pruned(
            Candidates::Nodes(raw_sweep(graph, graph_id, tree, tests)),
            true,
        );
    }
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
                Conjunction::Intersect(sides) => return pruned(Candidates::Intersect(sides), true),
                Conjunction::Sweep(conjuncts) => {
                    return SourcePlan {
                        source,
                        call_site: conjuncts,
                        call_site_candidates: None,
                        call_site_exact: true,
                        no_prefilter: false,
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
                        call_site_candidates: None,
                        call_site_exact: true,
                        no_prefilter: false,
                    };
                }
                if cost > MERGE_FLOOR {
                    return pruned(Candidates::Union(sets.pairs()), true);
                }
                // Small enough to materialise, and the ranges are already in hand: the
                // generic evaluation below would look every string's postings up a
                // second time to arrive at the same union.
                return pruned(Candidates::Nodes(sets.nodes(idx)), true);
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
            call_site_candidates: None,
            call_site_exact: false,
            no_prefilter: true,
        };
    }
    build_sweep_plan(source, graph, preds)
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
    let property = CALL_SITE_PROPS.iter().position(|c| *c == p.prop())?;
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
        PredTree::Or(children) => children.iter().map(|c| side_proxy(graph, idx, c)).sum(),
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
            let Some(property) = CALL_SITE_PROPS.iter().position(|c| *c == p.prop()) else {
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
    let proxies: Vec<Option<usize>> = children.iter().map(|c| side_proxy(graph, idx, c)).collect();
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
fn build_sweep_plan(source: SourceIdx, graph: &Graph, preds: &[StringPredicate]) -> SourcePlan {
    let n = graph.strings.len();
    // Group predicates by property once, then make a single pass over the dictionary.
    // A separate pass per property would re-read (and re-lowercase) every string.
    let mut by_property: [Vec<&StringPredicate>; 4] = [vec![], vec![], vec![], vec![]];
    for p in preds {
        if let Some(i) = CALL_SITE_PROPS.iter().position(|c| *c == p.prop()) {
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
            call_site_candidates: Some(Candidates::Nodes(Vec::new())),
            call_site_exact: true,
            no_prefilter: false,
        };
    }
    // The bitset sweep tests exactly the disjunction: a record hits when any tested
    // property carries a string one of that property's predicates matched. Nothing
    // looser, so its survivors need no WHERE re-check either.
    SourcePlan {
        source,
        call_site: vec![sets],
        call_site_candidates: None,
        call_site_exact: true,
        no_prefilter: false,
    }
}

/// A WHERE clause reduced to the parts this pushdown understands.
///
/// The production queries are not flat disjunctions. They look like
/// `(a CONTAINS X OR b CONTAINS X) AND (a CONTAINS Y OR b CONTAINS Y)` — two broad
/// searches narrowed against each other. Treating only `OR` meant every one of those
/// declined the pushdown entirely and fell to the generic evaluator, which is orders of
/// magnitude slower. Keeping the tree lets `AND` do what it is there for: intersect.
#[derive(Clone)]
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
/// The pushable tree of `e`, or `None` when nothing in it can be pushed.
///
/// A disjunction is pushable only when every side is: candidates for `a OR b` are the
/// union of both sides' candidates, and a side without a plan has no candidates to
/// contribute. A conjunction is pushable when either side is: the candidates of one
/// side alone are a superset of `a AND b`, and the WHERE clause, re-evaluated on every
/// survivor, removes the rest. Dropping a side is recorded in `relaxed`, which keeps the
/// plan from taking any survivor as already verified. This is what lets
/// `n.graphId IS NOT NULL AND (coalesce(toString(n.value), '') CONTAINS 'x' OR ...)`
/// use the string indexes instead of decoding every node of every graph.
fn collect_tree(e: &Expr, variable: &str, relaxed: &mut bool) -> Option<PredTree> {
    match e {
        Expr::Or(a, b) => Some(PredTree::Or(vec![
            collect_tree(a, variable, relaxed)?,
            collect_tree(b, variable, relaxed)?,
        ])),
        Expr::And(a, b) => {
            match (
                collect_tree(a, variable, relaxed),
                collect_tree(b, variable, relaxed),
            ) {
                (Some(a), Some(b)) => Some(PredTree::And(vec![a, b])),
                (Some(one), None) | (None, Some(one)) => {
                    *relaxed = true;
                    Some(one)
                }
                (None, None) => None,
            }
        }
        _ => {
            let mut out = Vec::new();
            if expand_keys_predicate(e, variable, &mut out) {
                return Some(PredTree::Or(out.into_iter().map(PredTree::Leaf).collect()));
            }
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
                StrOp::Regex => return push_regex(left, right, variable, out),
            };
            push_predicate(op, left, right, variable, out)
        }
        Expr::Comparison {
            op: crate::ast::CmpOp::Eq,
            left,
            right,
        } => {
            push_predicate(PushOp::Equals, left, right, variable, out)
                || push_numeric(left, right, variable, out)
                || push_numeric(right, left, variable, out)
        }
        Expr::In { right, .. } => push_member(right, variable, out),
        _ => false,
    }
}

/// A leaf that no dictionary answers: planned per type by its shape alone.
fn shaped_leaf(property: Prop, shape: LeafShape, literal: String) -> StringPredicate {
    StringPredicate {
        property,
        shape,
        op: PushOp::Equals,
        literal,
        transform: Transform::None,
        regex: None,
        via_to_string: false,
        from_keys: false,
        trigrams: std::sync::Arc::new(None),
        signature: 0,
        test: 0,
    }
}

/// Record one `<property> = <number>` predicate. Only equality: a number and a string
/// are unequal, so the leaf is false wherever the property holds a string, while an
/// ordering comparison falls back to comparing their text and is left to WHERE.
fn push_numeric(left: &Expr, right: &Expr, variable: &str, out: &mut Vec<StringPredicate>) -> bool {
    let text = match right {
        Expr::Literal(crate::ast::Literal::Int(i)) => i.to_string(),
        Expr::Literal(crate::ast::Literal::Float(f)) => f.to_string(),
        _ => return false,
    };
    // Bare `<variable>.<property>` only: a transformed operand is a string.
    let Expr::Property { expr, key } = left else {
        return false;
    };
    if !matches!(expr.as_ref(), Expr::Variable(v) if v == variable) {
        return false;
    }
    out.push(shaped_leaf(prop_name(key), LeafShape::Numeric, text));
    true
}

/// Record one `<anything> IN <variable>.<property>` predicate: null, hence false in
/// WHERE, wherever the property is not a list.
fn push_member(right: &Expr, variable: &str, out: &mut Vec<StringPredicate>) -> bool {
    let Expr::Property { expr, key } = right else {
        return false;
    };
    if !matches!(expr.as_ref(), Expr::Variable(v) if v == variable) {
        return false;
    }
    out.push(shaped_leaf(
        prop_name(key),
        LeafShape::Member,
        String::new(),
    ));
    true
}

/// The longest run of literal text a `=~` pattern requires, when the pattern is
/// simple enough to be sure of one.
///
/// Accepted: literal characters, backslash-escaped metacharacters, and `.`, `.*`, `.+`,
/// `.?` wildcards. Anything else -- classes, groups, alternation, anchors, a quantifier
/// on a literal -- makes the required text uncertain, and the pattern is not pushed.
/// `None` also for a pattern with no literal text at all, such as `.*`.
pub(crate) fn regex_required_literal(pattern: &str) -> Option<String> {
    const META: &[char] = &[
        '\\', '.', '^', '$', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}',
    ];
    let mut best = String::new();
    let mut run = String::new();
    let mut chars = pattern.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '\\' => match chars.next() {
                Some(n) if META.contains(&n) => run.push(n),
                _ => return None,
            },
            '.' => {
                if matches!(chars.peek(), Some('*' | '+' | '?')) {
                    chars.next();
                }
                if run.chars().count() > best.chars().count() {
                    best = std::mem::take(&mut run);
                } else {
                    run.clear();
                }
            }
            c if META.contains(&c) => return None,
            c => run.push(c),
        }
    }
    if run.chars().count() > best.chars().count() {
        best = run;
    }
    (!best.is_empty()).then_some(best)
}

/// Record one `<property> =~ <pattern>` predicate when the pattern's required text is
/// known and the evaluator can run the pattern; lowercased operands are left alone,
/// since the pattern would then apply to text the dictionary does not hold.
fn push_regex(left: &Expr, right: &Expr, variable: &str, out: &mut Vec<StringPredicate>) -> bool {
    let pattern = match right {
        Expr::Literal(crate::ast::Literal::Str(s)) => s.clone(),
        _ => return false,
    };
    let Some(literal) = regex_required_literal(&pattern) else {
        return false;
    };
    let compiled = crate::eval::compile_regex(&pattern);
    if matches!(*compiled, crate::eval::CompiledRegex::Unsupported(_)) {
        return false;
    }
    let Some((property, Transform::None, via_to_string)) = property_operand(left, variable) else {
        return false;
    };
    let trigrams = std::sync::Arc::new(if literal.is_ascii() {
        graphite_storage::callsite_index::literal_trigrams(&literal)
    } else {
        None
    });
    let signature = graphite_storage::callsite_index::literal_signature(&literal);
    out.push(StringPredicate {
        property,
        shape: LeafShape::Text,
        op: PushOp::Regex,
        literal,
        transform: Transform::None,
        regex: Some((pattern, compiled)),
        via_to_string,
        from_keys: false,
        trigrams,
        signature,
        test: 0,
    });
    true
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
        Some((property, transform, via_to_string)) => {
            // Trigrams exist only for an ASCII literal. The index was written from the
            // JVM's `String.lowercase()`, which is context-sensitive (a final sigma
            // lowers differently from a medial one), while a Rust `char` lowers alone;
            // a non-ASCII literal's trigrams could therefore miss the string that
            // matches it, and every pruning step treats `None` as "cannot prune".
            let trigrams = std::sync::Arc::new(if literal.is_ascii() {
                graphite_storage::callsite_index::literal_trigrams(&literal)
            } else {
                None
            });
            let signature = graphite_storage::callsite_index::literal_signature(&literal);
            out.push(StringPredicate {
                property,
                shape: LeafShape::Text,
                op,
                literal,
                transform,
                regex: None,
                via_to_string,
                from_keys: false,
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
/// A projected key that can be answered from a CallSite record's raw string ids
/// without decoding the node.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RawField {
    CallerClass,
    CallerName,
    CalleeClass,
    CalleeName,
    /// Only in cross-graph mode; otherwise `graphId` is not a property at all.
    GraphId,
    Id,
    /// Anything else: `ev.property` decides.
    General,
}

impl RawField {
    fn of(key: &str, cross: bool) -> Self {
        match key {
            "caller_class" => RawField::CallerClass,
            "caller_name" => RawField::CallerName,
            "callee_class" => RawField::CalleeClass,
            "callee_name" => RawField::CalleeName,
            "graphId" if cross => RawField::GraphId,
            "id" => RawField::Id,
            _ => RawField::General,
        }
    }
}

/// How one node type exposes one property to the scan.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Exposure {
    /// A string id at a fixed offset in the record: `slot` into `RAW_STRING_FIELDS`.
    Column(usize),
    /// One of CallSite's four strings, answered by the CallSite index.
    CallSite,
    /// The type has no such property, or not a string one: a string predicate on it is
    /// never true, and the type need not be looked at for that leaf.
    Absent,
    /// `graphId`, `elementId` or `qualifiedId`: synthesised from the graph id and the
    /// node id, so the leaf folds to a constant per graph, or needs the node id.
    Synthetic,
    /// A value only decoding the record reveals: the type must go through WHERE.
    Dynamic,
    /// `id`, tested as text: read off the column rows, never from a dictionary.
    NodeId,
    /// `type` on a type that stores no such property: the node's type name, one
    /// answer for the whole type.
    TypeName,
    /// A CallSite record answers it without being decoded, though not through the
    /// string index: a signature composed from its string ids, its line, its id.
    Raw,
    /// A field at a fixed place in the record, read without decoding the rest.
    Record(RecordField),
}

/// A raw field of a non-CallSite record, by its byte offset after the header.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RecordField {
    /// A method descriptor: its signature.
    Method(usize),
    /// A Return node's actual type: a flag byte, then a string id when set. At the
    /// end of the method descriptor, which is found from its parameter count.
    ActualTypeAfterMethod,
    /// A 32-bit integer, as text or as a number.
    I32(usize),
    /// A 64-bit integer, as text or as a number.
    I64(usize),
    /// A boolean, as text.
    Bool(usize),
}

/// The non-CallSite fields read raw, by type and property, for a leaf of `shape`.
fn record_field(tag: u8, property: &str, shape: LeafShape) -> Option<RecordField> {
    use graphite_storage::node::*;
    Some(match (tag, property, shape) {
        (TAG_LOCAL_VARIABLE | TAG_PARAMETER_NODE, "method", LeafShape::Text) => {
            RecordField::Method(8)
        }
        (TAG_RETURN_NODE, "method", LeafShape::Text) => RecordField::Method(0),
        (TAG_RETURN_NODE, "actual_type", LeafShape::Text) => RecordField::ActualTypeAfterMethod,
        (TAG_INT_CONSTANT, "value", _) | (TAG_PARAMETER_NODE, "index", _) => RecordField::I32(0),
        (TAG_LONG_CONSTANT, "value", _) => RecordField::I64(0),
        (TAG_BOOLEAN_CONSTANT, "value", LeafShape::Text) => RecordField::Bool(0),
        (TAG_FIELD_NODE, "static", LeafShape::Text) => RecordField::Bool(12),
        _ => return None,
    })
}

/// The CallSite properties answered raw off the record, beyond the four indexed ones.
fn is_raw_call_site_prop(property: &str) -> bool {
    matches!(
        property,
        "callee_signature" | "caller_signature" | "line" | "id"
    )
}

#[cfg(test)]
fn exposure(tag: u8, property: &str) -> Exposure {
    exposure_of(tag, property, false, None)
}

/// What `tag` exposes for a leaf: the property, whether the operand was `toString()`,
/// and the leaf itself when its literal decides whether a number or boolean could ever
/// satisfy it.
/// What `tag` exposes for a leaf of any shape.
fn leaf_exposure(tag: u8, p: &StringPredicate) -> Exposure {
    use graphite_storage::node::*;
    let property = p.prop();
    let numeric_typed = matches!(
        (tag, property),
        (
            TAG_INT_CONSTANT | TAG_LONG_CONSTANT | TAG_FLOAT_CONSTANT | TAG_DOUBLE_CONSTANT,
            "value"
        ) | (TAG_PARAMETER_NODE, "index")
            | (TAG_CALL_SITE_NODE, "line")
    );
    // An enum's value is its first constructor argument and a resource value is
    // whatever the file held: either can be a number or a list.
    let any_typed = matches!(
        (tag, property),
        (TAG_ENUM_CONSTANT | TAG_RESOURCE_VALUE_NODE, "value")
    ) || tag == TAG_ANNOTATION_NODE;
    let exposure = match p.shape {
        LeafShape::Numeric => {
            if is_synthetic_key(property) {
                Exposure::Absent
            } else if property == "id" {
                Exposure::NodeId
            } else if numeric_typed || any_typed {
                Exposure::Dynamic
            } else {
                Exposure::Absent
            }
        }
        LeafShape::Member => {
            if any_typed {
                Exposure::Dynamic
            } else {
                Exposure::Absent
            }
        }
        LeafShape::Text if property == "id" => {
            if p.via_to_string && number_text_can_satisfy(p) {
                Exposure::NodeId
            } else {
                Exposure::Absent
            }
        }
        LeafShape::Text => match exposure_of(tag, property, p.via_to_string, Some(p)) {
            // `n.type` falls back to the node's type name where nothing else is
            // stored under that key; `keys(n)` does not list it, so a leaf from
            // there does not see it.
            Exposure::Absent if property == "type" && !p.from_keys => Exposure::TypeName,
            other => other,
        },
    };
    // A CallSite record's signatures, line and id are read raw by the record sweep
    // instead of decoding the record: what a search over every property, which names
    // all of them, would otherwise cost on the most numerous type. Likewise the
    // method of a local, a parameter or a return node, and the numbers a record
    // holds at a fixed offset: what remains dynamic is the open-ended (annotations,
    // enum arguments, resource values) and floating point, whose Kotlin text is not
    // reproduced here.
    match exposure {
        Exposure::Dynamic | Exposure::NodeId
            if tag == TAG_CALL_SITE_NODE && is_raw_call_site_prop(property) =>
        {
            Exposure::Raw
        }
        Exposure::Dynamic => match record_field(tag, property, p.shape) {
            Some(field) => Exposure::Record(field),
            None => Exposure::Dynamic,
        },
        other => other,
    }
}

/// Fold a `type` leaf against the type name of `tag`.
fn type_name_truth(tag: u8, p: &StringPredicate) -> bool {
    let name = graphite_storage::node::tag_type_name(tag);
    match p.transform {
        Transform::None => p.matches_raw(name),
        Transform::Lowercase => p.matches_raw(&name.to_ascii_lowercase()),
    }
}

fn exposure_of(
    tag: u8,
    property: &str,
    via_to_string: bool,
    leaf: Option<&StringPredicate>,
) -> Exposure {
    use graphite_storage::node::*;
    if is_synthetic_key(property) {
        return Exposure::Synthetic;
    }
    // A property whose value is a number or a boolean: a string predicate on it is
    // never true, unless the operand was `toString()` and the literal looks the part.
    let non_string = match tag {
        TAG_INT_CONSTANT | TAG_LONG_CONSTANT | TAG_FLOAT_CONSTANT | TAG_DOUBLE_CONSTANT => {
            (property == "value").then_some(false)
        }
        TAG_BOOLEAN_CONSTANT => (property == "value").then_some(true),
        TAG_FIELD_NODE => (property == "static").then_some(true),
        TAG_PARAMETER_NODE => (property == "index").then_some(false),
        TAG_CALL_SITE_NODE => (property == "line").then_some(false),
        _ => None,
    };
    if let Some(boolean) = non_string {
        let possible = via_to_string
            && leaf.is_some_and(|p| {
                if boolean {
                    boolean_text_can_satisfy(p)
                } else {
                    number_text_can_satisfy(p)
                }
            });
        return if possible {
            Exposure::Dynamic
        } else {
            Exposure::Absent
        };
    }
    if tag == TAG_CALL_SITE_NODE {
        return if CALL_SITE_PROPS.contains(&property) {
            Exposure::CallSite
        } else if matches!(property, "callee_signature" | "caller_signature") {
            Exposure::Dynamic
        } else {
            Exposure::Absent
        };
    }
    if let Some(slot) = graphite_storage::columns::raw_string_field(tag, property) {
        return Exposure::Column(slot);
    }
    let dynamic = match tag {
        TAG_ENUM_CONSTANT => property == "value",
        TAG_LOCAL_VARIABLE | TAG_PARAMETER_NODE => property == "method",
        TAG_RETURN_NODE => property == "method" || property == "actual_type",
        TAG_RESOURCE_VALUE_NODE => matches!(property, "value" | "format" | "profile"),
        TAG_RESOURCE_FILE_NODE => property == "profile",
        // An annotation exposes `values` and then any key its own value pairs carry.
        TAG_ANNOTATION_NODE => true,
        _ => false,
    };
    if dynamic {
        Exposure::Dynamic
    } else {
        Exposure::Absent
    }
}

/// Fold a synthetic-key leaf for one graph: `Some(true)` when every node of the graph
/// satisfies it, `Some(false)` when none can, `None` when the node id decides.
///
/// `qualifiedId` and `elementId` read `<graph id>:<node id>`, `graphId` the graph id;
/// none exist outside cross-graph mode. A graph id never contains a colon and a node
/// id is a run of digits, so a literal splits at its colon into a graph-id part and
/// a node-id part, and each side is settled on its own.
fn synthetic_truth(ex: &Executor, source: SourceIdx, p: &StringPredicate) -> Fold {
    match fold_synthetic(ex.cross, &ex.sources[source as usize].id, p) {
        Some(b) => Fold::Known(b),
        None => Fold::PerNode,
    }
}

/// What a synthetic-key leaf folds to for a graph.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Fold {
    /// The same answer for every node of the graph.
    Known(bool),
    /// The node id decides: the leaf is tested per node, from the id alone.
    PerNode,
    /// No graph in hand yet: the type is generic until a source folds the leaf.
    Unknown,
}

/// The text a synthetic key reads before the node id, for a leaf tested per node:
/// `<graph id>:` for `qualifiedId` and `elementId`, lowercased when the leaf is.
fn synthetic_prefix(graph_id: &str, p: &StringPredicate) -> String {
    let gid: String = match p.transform {
        Transform::None => graph_id.to_string(),
        Transform::Lowercase => graph_id.chars().flat_map(|c| c.to_lowercase()).collect(),
    };
    if p.prop() == "graphId" {
        gid
    } else {
        format!("{gid}:")
    }
}

fn fold_synthetic(cross: bool, graph_id: &str, p: &StringPredicate) -> Option<bool> {
    if !cross {
        return Some(false);
    }
    if p.op == PushOp::Regex {
        return None;
    }
    let gid: String = match p.transform {
        Transform::None => graph_id.to_string(),
        Transform::Lowercase => graph_id.chars().flat_map(|c| c.to_lowercase()).collect(),
    };
    let lit = p.literal.as_str();
    if p.prop() == "graphId" {
        return Some(match p.op {
            PushOp::Equals => gid == lit,
            PushOp::Contains => gid.contains(lit),
            PushOp::StartsWith => gid.starts_with(lit),
            PushOp::EndsWith => gid.ends_with(lit),
            PushOp::Regex => unreachable!(),
        });
    }
    let all_digits = |s: &str| !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit());
    let mut parts = lit.split(':');
    let head = parts.next().unwrap_or("");
    let tail = parts.next();
    if parts.next().is_some() {
        return Some(false);
    }
    match (p.op, tail) {
        // No colon: the literal lies wholly in the graph id or wholly in the node id.
        (PushOp::Contains, None) => {
            if gid.contains(head) {
                Some(true)
            } else if all_digits(head) {
                None
            } else {
                Some(false)
            }
        }
        (PushOp::StartsWith, None) => Some(gid.starts_with(head)),
        (PushOp::EndsWith, None) => {
            if head.is_empty() {
                Some(true)
            } else if all_digits(head) {
                None
            } else {
                Some(false)
            }
        }
        (PushOp::Equals, None) => Some(false),
        // One colon: `head` ends the graph id, `tail` starts the node id.
        (PushOp::Contains, Some(tail)) => {
            if !gid.ends_with(head) {
                Some(false)
            } else if tail.is_empty() {
                Some(true)
            } else if all_digits(tail) {
                None
            } else {
                Some(false)
            }
        }
        (PushOp::StartsWith, Some(tail)) => {
            if gid != head {
                Some(false)
            } else if tail.is_empty() {
                Some(true)
            } else if all_digits(tail) {
                None
            } else {
                Some(false)
            }
        }
        (PushOp::EndsWith, Some(tail)) => {
            if gid.ends_with(head) && all_digits(tail) {
                None
            } else {
                Some(false)
            }
        }
        (PushOp::Equals, Some(tail)) => {
            if gid == head && all_digits(tail) {
                None
            } else {
                Some(false)
            }
        }
        (PushOp::Regex, _) => unreachable!(),
    }
}

/// What the scan does for one node type.
enum TagPlan {
    /// No record of this type can satisfy the clause.
    Skip,
    /// Every record of this type satisfies the clause: emitted without decoding.
    All,
    /// Every record goes through WHERE: some leaf needs the record decoded.
    Generic,
    /// The clause, restricted to the leaves this type answers raw, for the CallSite
    /// index.
    CallSite(PredTree),
    /// The clause, restricted to the leaves this type answers raw, for the columns.
    Column(PredTree),
}

enum Pruned {
    False,
    /// Every record of the type satisfies the subtree.
    True,
    Generic,
    Tree(PredTree),
}

/// Restrict the clause to what `tag`'s records expose. A leaf the type lacks is false;
/// a disjunction of only such leaves is false; a conjunction with one is false. A leaf
/// only decoding can answer makes the whole type generic.
fn prune(
    tree: &PredTree,
    tag: u8,
    dynamic_is_absent: &dyn Fn(&str) -> bool,
    synthetic: &dyn Fn(&StringPredicate) -> Fold,
) -> Pruned {
    match tree {
        PredTree::Leaf(p) => {
            // A key an annotation's value pairs may carry: only decoding tells.
            if p.from_keys && tag == TAG_ANNOTATION_NODE {
                return Pruned::Generic;
            }
            let truth = |b: bool| if b { Pruned::True } else { Pruned::False };
            match leaf_exposure(tag, p) {
                Exposure::Column(_) | Exposure::CallSite | Exposure::Raw => {
                    Pruned::Tree(tree.clone())
                }
                Exposure::Record(_) => Pruned::Tree(tree.clone()),
                Exposure::Absent => Pruned::False,
                Exposure::Synthetic => match synthetic(p) {
                    Fold::Known(true) => Pruned::True,
                    Fold::Known(false) => Pruned::False,
                    Fold::PerNode => Pruned::Tree(tree.clone()),
                    Fold::Unknown => Pruned::Generic,
                },
                Exposure::TypeName => truth(type_name_truth(tag, p)),
                // The node id is read off the type's id list.
                Exposure::NodeId => Pruned::Tree(tree.clone()),
                // An annotation's `type` is its type name when no value pair spells
                // the key; the other keys are simply missing then.
                Exposure::Dynamic if dynamic_is_absent(p.prop()) => {
                    if p.prop() == "type" && p.shape == LeafShape::Text && !p.from_keys {
                        truth(type_name_truth(tag, p))
                    } else {
                        Pruned::False
                    }
                }
                Exposure::Dynamic => Pruned::Generic,
            }
        }
        PredTree::Or(children) => {
            let mut kept = Vec::new();
            let mut generic = false;
            for c in children {
                match prune(c, tag, dynamic_is_absent, synthetic) {
                    Pruned::False => {}
                    Pruned::True => return Pruned::True,
                    Pruned::Generic => generic = true,
                    Pruned::Tree(t) => kept.push(t),
                }
            }
            if generic {
                return Pruned::Generic;
            }
            match kept.len() {
                0 => Pruned::False,
                1 => Pruned::Tree(kept.pop().unwrap()),
                _ => Pruned::Tree(PredTree::Or(kept)),
            }
        }
        PredTree::And(children) => {
            let mut kept = Vec::new();
            let mut generic = false;
            for c in children {
                match prune(c, tag, dynamic_is_absent, synthetic) {
                    Pruned::False => return Pruned::False,
                    Pruned::True => {}
                    Pruned::Generic => generic = true,
                    Pruned::Tree(t) => kept.push(t),
                }
            }
            if generic {
                return Pruned::Generic;
            }
            match kept.len() {
                0 => Pruned::True,
                1 => Pruned::Tree(kept.pop().unwrap()),
                _ => Pruned::Tree(PredTree::And(kept)),
            }
        }
    }
}

fn tag_plan(
    tree: &PredTree,
    tag: u8,
    dynamic_is_absent: &dyn Fn(&str) -> bool,
    synthetic: &dyn Fn(&StringPredicate) -> Fold,
) -> TagPlan {
    match prune(tree, tag, dynamic_is_absent, synthetic) {
        Pruned::False => TagPlan::Skip,
        Pruned::True => TagPlan::All,
        Pruned::Generic => TagPlan::Generic,
        Pruned::Tree(t) if tag == TAG_CALL_SITE_NODE => TagPlan::CallSite(t),
        Pruned::Tree(t) => TagPlan::Column(t),
    }
}

/// The sorted string ids of one column that satisfy one predicate, resolved once per
/// column and remembered there, as the Kotlin column index remembers them.
fn resolve_column_leaf(
    graph: &Graph,
    column: &graphite_storage::columns::StringColumn,
    p: &StringPredicate,
) -> std::sync::Arc<Vec<u32>> {
    let key = (p.op as u8, p.transform as u8, p.test_text().to_string());
    if let Some(hit) = column.cached(&key) {
        return hit;
    }
    let unique = column.unique();
    let ids: Vec<u32> = if p.is_seekable() {
        // Contiguous in the dictionary: take the column's ids inside that range.
        let range = seek_range(graph, p);
        let lo = unique.partition_point(|&s| (s as usize) < range.start);
        let hi = unique.partition_point(|&s| (s as usize) < range.end);
        unique[lo..hi].to_vec()
    } else {
        let candidates: Option<Vec<u32>> = match (p.literal.is_ascii(), p.trigrams.as_ref()) {
            (true, Some(trigrams)) if !trigrams.is_empty() => {
                column.trigram_candidates(&graph.strings, trigrams)
            }
            _ => None,
        };
        match candidates {
            Some(mut c) => {
                c.retain(|&id| predicate_matches(graph, p, id));
                c
            }
            None => {
                // No trigram help: test the column's distinct strings, never the
                // whole dictionary.
                const CHUNK: usize = 4096;
                if unique.len() >= CHUNK * 2 {
                    unique
                        .par_chunks(CHUNK)
                        .map(|chunk| {
                            chunk
                                .iter()
                                .copied()
                                .filter(|&id| predicate_matches(graph, p, id))
                                .collect::<Vec<u32>>()
                        })
                        .reduce(Vec::new, |mut a, b| {
                            a.extend(b);
                            a
                        })
                } else {
                    unique
                        .iter()
                        .copied()
                        .filter(|&id| predicate_matches(graph, p, id))
                        .collect()
                }
            }
        }
    };
    let ids = std::sync::Arc::new(ids);
    column.remember(key, ids.clone());
    ids
}

/// One leaf of a column plan, resolved for one graph: which column it reads and
/// which string ids satisfy it.
struct ColumnLeaf {
    slot: usize,
    matched: std::sync::Arc<Vec<u32>>,
    /// The same ids as a bitset when there are enough of them to make the binary
    /// search per record the slower test.
    bits: Option<StringBitset>,
}

impl ColumnLeaf {
    #[inline]
    fn holds(&self, string_id: u32) -> bool {
        match &self.bits {
            Some(b) => b.get(string_id as usize),
            None => self.matched.binary_search(&string_id).is_ok(),
        }
    }
}

/// A column plan resolved against one graph: the tree with each leaf's matching ids.
enum ColumnTree {
    Leaf(ColumnLeaf),
    /// The node id, as text or as a number, against the predicate.
    NodeId {
        number: Option<f64>,
        pred: StringPredicate,
    },
    /// A synthetic key, tested per node: the graph's prefix and the node id.
    Synthetic {
        prefix: String,
        with_id: bool,
        pred: StringPredicate,
    },
    /// A raw field of the record, found from the node id; `memo` indexes the
    /// per-thread state.
    Record {
        field: RecordField,
        number: Option<f64>,
        pred: StringPredicate,
        memo: usize,
    },
    Or(Vec<ColumnTree>),
    And(Vec<ColumnTree>),
}

/// Per-thread state of a column tree's row tests: one memo per record leaf and a
/// text buffer. Separate from the tree so that one tree can test rows on every
/// thread at once.
#[derive(Default)]
struct RowState {
    signatures: Vec<SignatureMemo>,
    /// `actual_type` outcomes per string id, per leaf.
    strings: Vec<std::collections::HashMap<u32, bool>>,
    text: String,
}

/// The literal as a number, for a numeric leaf.
fn leaf_number(p: &StringPredicate) -> Option<f64> {
    match p.shape {
        LeafShape::Numeric => p.literal.parse::<f64>().ok(),
        _ => None,
    }
}

/// Decimal text of `n` in a stack buffer.
fn u32_text(n: u32, buf: &mut [u8; 10]) -> &str {
    let mut i = buf.len();
    let mut n = n;
    loop {
        i -= 1;
        buf[i] = b'0' + (n % 10) as u8;
        n /= 10;
        if n == 0 {
            break;
        }
    }
    std::str::from_utf8(&buf[i..]).expect("ascii digits")
}

impl ColumnTree {
    /// `None` when no record can match: an `And` with an empty leaf, or an `Or` of
    /// nothing but empty leaves.
    fn resolve(
        graph: &Graph,
        graph_id: &str,
        tree: &PredTree,
        tag: u8,
        memos: &mut usize,
    ) -> Option<ColumnTree> {
        match tree {
            PredTree::Leaf(p) => {
                let slot = match leaf_exposure(tag, p) {
                    Exposure::Column(slot) => slot,
                    Exposure::Synthetic => {
                        return Some(ColumnTree::Synthetic {
                            prefix: synthetic_prefix(graph_id, p),
                            with_id: p.prop() != "graphId",
                            pred: p.clone(),
                        })
                    }
                    Exposure::NodeId => {
                        return Some(ColumnTree::NodeId {
                            number: leaf_number(p),
                            pred: p.clone(),
                        })
                    }
                    Exposure::Record(field) => {
                        let memo = *memos;
                        *memos += 1;
                        return Some(ColumnTree::Record {
                            field,
                            number: leaf_number(p),
                            pred: p.clone(),
                            memo,
                        });
                    }
                    _ => return None,
                };
                let column = graph.string_column(slot);
                let matched = resolve_column_leaf(graph, column, p);
                if matched.is_empty() {
                    return None;
                }
                let bits = (matched.len() > 8).then(|| {
                    let mut b = StringBitset::new(graph.strings.len());
                    for &id in matched.iter() {
                        b.set(id as usize);
                    }
                    b
                });
                Some(ColumnTree::Leaf(ColumnLeaf {
                    slot,
                    matched,
                    bits,
                }))
            }
            PredTree::Or(children) => {
                let kept: Vec<ColumnTree> = children
                    .iter()
                    .filter_map(|c| ColumnTree::resolve(graph, graph_id, c, tag, memos))
                    .collect();
                if kept.is_empty() {
                    None
                } else {
                    Some(ColumnTree::Or(kept))
                }
            }
            PredTree::And(children) => {
                let mut kept = Vec::with_capacity(children.len());
                for c in children {
                    kept.push(ColumnTree::resolve(graph, graph_id, c, tag, memos)?);
                }
                Some(ColumnTree::And(kept))
            }
        }
    }

    /// Fresh per-thread state for this tree's row tests.
    fn state(&self, memos: usize) -> RowState {
        RowState {
            signatures: (0..memos).map(|_| SignatureMemo::default()).collect(),
            strings: (0..memos).map(|_| Default::default()).collect(),
            text: String::new(),
        }
    }

    /// True when some leaf reads the record or the node id, row by row: the rows are
    /// then tested a chunk at a time across threads.
    fn reads_records(&self) -> bool {
        match self {
            ColumnTree::Leaf(_) => false,
            ColumnTree::NodeId { .. }
            | ColumnTree::Synthetic { .. }
            | ColumnTree::Record { .. } => true,
            ColumnTree::Or(cs) | ColumnTree::And(cs) => cs.iter().any(|c| c.reads_records()),
        }
    }

    /// Test row `row` of the type's id list, whose node id is `id`.
    #[inline]
    fn matches(&self, graph: &Graph, row: usize, id: u32, st: &mut RowState) -> bool {
        match self {
            ColumnTree::Leaf(l) => l.holds(graph.string_column(l.slot).string_ids()[row]),
            ColumnTree::NodeId { number, pred } => number_or_text(*number, id, pred),
            ColumnTree::Synthetic {
                prefix,
                with_id,
                pred,
            } => {
                st.text.clear();
                st.text.push_str(prefix);
                if *with_id {
                    let mut buf = [0u8; 10];
                    st.text.push_str(u32_text(id, &mut buf));
                }
                pred.matches_raw(&st.text)
            }
            ColumnTree::Record {
                field,
                number,
                pred,
                memo,
            } => {
                use graphite_storage::io::read_i32_at;
                let Some(offset) = graph.node_offset(id) else {
                    return false;
                };
                let data = graph.nodedata();
                let at = offset + graphite_storage::node::NODE_HEADER_BYTES;
                match *field {
                    RecordField::Method(o) => {
                        st.signatures[*memo].matches(graph, data, at + o, pred)
                    }
                    RecordField::ActualTypeAfterMethod => {
                        let params = read_i32_at(data, at + 8).max(0) as usize;
                        let flag = at + 16 + params * 4;
                        if data.get(flag).copied().unwrap_or(0) == 0 {
                            return false;
                        }
                        let string = read_i32_at(data, flag + 1) as u32;
                        *st.strings[*memo]
                            .entry(string)
                            .or_insert_with(|| predicate_matches(graph, pred, string))
                    }
                    RecordField::I32(o) => {
                        let v = read_i32_at(data, at + o) as i64;
                        number_or_text_i64(*number, v, pred)
                    }
                    RecordField::I64(o) => {
                        let hi = read_i32_at(data, at + o) as u32 as u64;
                        let lo = read_i32_at(data, at + o + 4) as u32 as u64;
                        number_or_text_i64(*number, ((hi << 32) | lo) as i64, pred)
                    }
                    RecordField::Bool(o) => {
                        let text = if data.get(at + o).copied().unwrap_or(0) != 0 {
                            "true"
                        } else {
                            "false"
                        };
                        pred.matches_raw(text)
                    }
                }
            }
            ColumnTree::Or(cs) => cs.iter().any(|c| c.matches(graph, row, id, st)),
            ColumnTree::And(cs) => cs.iter().all(|c| c.matches(graph, row, id, st)),
        }
    }

    /// A column the tree reads, if any: its rows are then the type's id list.
    fn first_slot(&self) -> Option<usize> {
        match self {
            ColumnTree::Leaf(l) => Some(l.slot),
            ColumnTree::NodeId { .. }
            | ColumnTree::Synthetic { .. }
            | ColumnTree::Record { .. } => None,
            ColumnTree::Or(cs) | ColumnTree::And(cs) => cs.iter().find_map(|c| c.first_slot()),
        }
    }

    /// The node ids the tree's rows stand for, ascending: a column's when it reads
    /// one, else every node of the type.
    fn row_ids<'g>(&self, graph: &'g Graph, tag: u8) -> &'g [u32] {
        match self.first_slot() {
            Some(slot) => graph.string_column(slot).node_ids(),
            None => graph.ids_by_tag(tag),
        }
    }
}

/// `number_or_text` for a signed 64-bit value.
fn number_or_text_i64(number: Option<f64>, value: i64, p: &StringPredicate) -> bool {
    match number {
        Some(n) => n == value as f64,
        None => p.matches_raw(&value.to_string()),
    }
}

type Prop = std::borrow::Cow<'static, str>;

/// `<variable>.<key>` as a leaf property: a name the scan reads raw keeps its static
/// spelling; any other name is carried as is, and the per-type exposure decides that
/// only an annotation could hold it.
fn prop_name(key: &str) -> Prop {
    match PUSHABLE_PROPS.iter().find(|p| **p == key) {
        Some(p) => std::borrow::Cow::Borrowed(p),
        None => std::borrow::Cow::Owned(key.to_string()),
    }
}

fn property_operand(e: &Expr, variable: &str) -> Option<(Prop, Transform, bool)> {
    match e {
        Expr::Property { expr, key } => match expr.as_ref() {
            Expr::Variable(v) if v == variable => Some((prop_name(key), Transform::None, false)),
            _ => None,
        },
        Expr::FunctionCall { name, args, .. } => {
            let lower = name.to_ascii_lowercase();
            match lower.as_str() {
                "tolower" | "tolowercase" => {
                    let (p, _, via) = property_operand(args.first()?, variable)?;
                    Some((p, Transform::Lowercase, via))
                }
                "coalesce" => {
                    let (p, t, via) = property_operand(args.first()?, variable)?;
                    Some((p, t, via))
                }
                // `toString` of a string is the string; of a number or boolean it is the
                // only form a string predicate can match, which `exposure` weighs.
                "tostring" => {
                    if args.len() != 1 {
                        return None;
                    }
                    let (p, t, _) = property_operand(&args[0], variable)?;
                    Some((p, t, true))
                }
                _ => None,
            }
        }
        _ => None,
    }
}

/// `any(k IN keys(<variable>) WHERE <test of toString(<variable>[k]) or <variable>[k]>)`:
/// the shape of a search over every property. One leaf per key a node map can carry,
/// as a disjunction, which the per-type pruning then narrows to the keys each type has;
/// a type with open-ended keys decodes instead. Only the leaf structure is used for
/// planning -- rows of a decoded type are still judged by the original clause.
fn expand_keys_predicate(e: &Expr, variable: &str, out: &mut Vec<StringPredicate>) -> bool {
    let Expr::PredicateFunction {
        name,
        variable: key_var,
        list,
        predicate: Some(predicate),
    } = e
    else {
        return false;
    };
    if !name.eq_ignore_ascii_case("any") {
        return false;
    }
    match list.as_ref() {
        Expr::FunctionCall { name, args, .. }
            if name.eq_ignore_ascii_case("keys")
                && matches!(args.as_slice(), [Expr::Variable(v)] if v == variable) => {}
        _ => return false,
    }
    // The operand must read `<variable>[k]` or `properties(<variable>)[k]`, possibly
    // under toString().
    let is_node_map = |e: &Expr| -> bool {
        match e {
            Expr::Variable(v) => v == variable,
            Expr::FunctionCall { name, args, .. } => {
                name.eq_ignore_ascii_case("properties")
                    && matches!(args.as_slice(), [Expr::Variable(v)] if v == variable)
            }
            _ => false,
        }
    };
    let subscripted = |operand: &Expr| -> Option<bool> {
        let (inner, via) = match operand {
            Expr::FunctionCall { name, args, .. } if name.eq_ignore_ascii_case("tostring") => {
                (args.first()?, true)
            }
            other => (other, false),
        };
        match inner {
            Expr::Subscript { expr, index } => match index.as_ref() {
                Expr::Variable(k) if k == key_var && is_node_map(expr) => Some(via),
                _ => None,
            },
            _ => None,
        }
    };
    let (op, left, right): (PushOp, &Expr, &Expr) = match predicate.as_ref() {
        Expr::StringOp { op, left, right } => (
            match op {
                StrOp::Contains => PushOp::Contains,
                StrOp::StartsWith => PushOp::StartsWith,
                StrOp::EndsWith => PushOp::EndsWith,
                StrOp::Regex => return false,
            },
            left,
            right,
        ),
        Expr::Comparison {
            op: crate::ast::CmpOp::Eq,
            left,
            right,
        } => (PushOp::Equals, left, right),
        _ => return false,
    };
    let Some(via_to_string) = subscripted(left) else {
        return false;
    };
    let Expr::Literal(crate::ast::Literal::Str(literal)) = right else {
        return false;
    };
    for property in ALL_KEYS {
        // Trigrams only for an ASCII literal, as `push_predicate` reasons.
        let trigrams = std::sync::Arc::new(if literal.is_ascii() {
            graphite_storage::callsite_index::literal_trigrams(literal)
        } else {
            None
        });
        out.push(StringPredicate {
            property: std::borrow::Cow::Borrowed(property),
            shape: LeafShape::Text,
            op,
            literal: literal.clone(),
            transform: Transform::None,
            regex: None,
            via_to_string,
            from_keys: true,
            trigrams,
            signature: graphite_storage::callsite_index::literal_signature(literal),
            test: 0,
        });
    }
    true
}

/// Could a number's `toString()` satisfy the predicate at all? Integers print as digits
/// with an optional sign; floating point adds a point, an exponent, `Infinity` and
/// `NaN`. A literal with any other character never matches.
fn number_text_can_satisfy(p: &StringPredicate) -> bool {
    let text = p.test_text();
    text.chars()
        .all(|c| c.is_ascii_digit() || "+-.EeInfityNa".contains(c))
}

/// Could a boolean's `toString()` -- `true` or `false` -- satisfy the predicate?
fn boolean_text_can_satisfy(p: &StringPredicate) -> bool {
    ["true", "false"].iter().any(|b| match p.op {
        PushOp::Equals => *b == p.literal,
        PushOp::Contains => b.contains(&p.literal),
        PushOp::StartsWith => b.starts_with(&p.literal),
        PushOp::EndsWith => b.ends_with(&p.literal),
        PushOp::Regex => true,
    })
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
            let Some(property) = CALL_SITE_PROPS.iter().position(|c| *c == p.prop()) else {
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
        // A synthetic key is not in any dictionary, and a number's text is not either:
        // their trigrams say nothing about whether the graph can match.
        // Nor does a number, a list test, a node id, or a type name.
        PredTree::Leaf(p)
            if p.shape != LeafShape::Text
                || is_synthetic_key(p.prop())
                || p.via_to_string
                || matches!(p.prop(), "id" | "type" | "method")
                || is_raw_call_site_prop(p.prop()) =>
        {
            true
        }
        PredTree::Leaf(p) => match p.trigrams.as_ref() {
            Some(trigrams) if !trigrams.is_empty() => idx.may_contain_all(trigrams),
            _ => true,
        },
        PredTree::Or(children) => children.iter().any(|c| may_match(graph, c)),
        PredTree::And(children) => children.iter().all(|c| may_match(graph, c)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use graphite_storage::node::*;

    fn leaf(property: &'static str, op: PushOp, literal: &str) -> PredTree {
        PredTree::Leaf(StringPredicate {
            property: std::borrow::Cow::Borrowed(property),
            shape: LeafShape::Text,
            op,
            literal: literal.to_string(),
            transform: Transform::None,
            regex: None,
            via_to_string: false,
            from_keys: false,
            trigrams: std::sync::Arc::new(graphite_storage::callsite_index::literal_trigrams(
                literal,
            )),
            signature: graphite_storage::callsite_index::literal_signature(literal),
            test: 0,
        })
    }

    fn kind(p: &Pruned) -> &'static str {
        match p {
            Pruned::False => "false",
            Pruned::True => "true",
            Pruned::Generic => "generic",
            Pruned::Tree(_) => "tree",
        }
    }

    #[test]
    fn exposure_follows_the_record_layouts() {
        assert_eq!(exposure(TAG_STRING_CONSTANT, "value"), Exposure::Column(0));
        assert_eq!(exposure(TAG_INT_CONSTANT, "value"), Exposure::Absent);
        assert_eq!(exposure(TAG_ENUM_CONSTANT, "value"), Exposure::Dynamic);
        assert_eq!(
            exposure(TAG_CALL_SITE_NODE, "callee_class"),
            Exposure::CallSite
        );
        assert_eq!(exposure(TAG_CALL_SITE_NODE, "value"), Exposure::Absent);
        assert_eq!(exposure(TAG_ANNOTATION_NODE, "value"), Exposure::Dynamic);
        assert!(matches!(
            exposure(TAG_ANNOTATION_NODE, "name"),
            Exposure::Column(_)
        ));
        assert!(matches!(
            exposure(TAG_FIELD_NODE, "type"),
            Exposure::Column(_)
        ));
        assert_eq!(exposure(TAG_LOCAL_VARIABLE, "method"), Exposure::Dynamic);
    }

    #[test]
    fn pruning_keeps_only_what_a_type_answers() {
        let value = leaf("value", PushOp::Contains, "abc");
        let callee = leaf("callee_class", PushOp::Contains, "abc");
        let never = &|_: &str| false;
        let unknown = &|_: &StringPredicate| Fold::Unknown;
        // A leaf the type lacks is false; a disjunction of one raw and one absent leaf
        // keeps the raw one; a conjunction with an absent leaf is false.
        assert_eq!(
            kind(&prune(&value, TAG_CALL_SITE_NODE, never, unknown)),
            "false"
        );
        assert_eq!(
            kind(&prune(&value, TAG_STRING_CONSTANT, never, unknown)),
            "tree"
        );
        let or = PredTree::Or(vec![value.clone(), callee.clone()]);
        match prune(&or, TAG_CALL_SITE_NODE, never, unknown) {
            Pruned::Tree(PredTree::Leaf(p)) => assert_eq!(p.prop(), "callee_class"),
            other => panic!("expected the callee leaf, got {}", kind(&other)),
        }
        match prune(&or, TAG_STRING_CONSTANT, never, unknown) {
            Pruned::Tree(PredTree::Leaf(p)) => assert_eq!(p.prop(), "value"),
            other => panic!("expected the value leaf, got {}", kind(&other)),
        }
        assert_eq!(kind(&prune(&or, TAG_INT_CONSTANT, never, unknown)), "false");
        let and = PredTree::And(vec![value.clone(), callee.clone()]);
        assert_eq!(
            kind(&prune(&and, TAG_CALL_SITE_NODE, never, unknown)),
            "false"
        );
        assert_eq!(
            kind(&prune(&and, TAG_STRING_CONSTANT, never, unknown)),
            "false"
        );
        // A dynamic leaf sends the type through WHERE, unless the graph rules it out.
        assert_eq!(
            kind(&prune(&value, TAG_ANNOTATION_NODE, never, unknown)),
            "generic"
        );
        assert_eq!(
            kind(&prune(&or, TAG_ANNOTATION_NODE, never, unknown)),
            "generic"
        );
        // A key no string in the graph's dictionary spells cannot be an annotation's.
        let absent = &|key: &str| key == "value" || key == "callee_class";
        assert_eq!(
            kind(&prune(&or, TAG_ANNOTATION_NODE, absent, unknown)),
            "false"
        );
        let only_value = &|key: &str| key == "value";
        assert_eq!(
            kind(&prune(&or, TAG_ANNOTATION_NODE, only_value, unknown)),
            "generic"
        );
        assert_eq!(
            kind(&prune(&value, TAG_ENUM_CONSTANT, never, unknown)),
            "generic"
        );
    }

    #[test]
    fn tag_plans_route_each_type() {
        let value = leaf("value", PushOp::Contains, "abc");
        let never = &|_: &str| false;
        let unknown = &|_: &StringPredicate| Fold::Unknown;
        assert!(matches!(
            tag_plan(&value, TAG_STRING_CONSTANT, never, unknown),
            TagPlan::Column(_)
        ));
        assert!(matches!(
            tag_plan(&value, TAG_CALL_SITE_NODE, never, unknown),
            TagPlan::Skip
        ));
        assert!(matches!(
            tag_plan(&value, TAG_LONG_CONSTANT, never, unknown),
            TagPlan::Skip
        ));
        assert!(matches!(
            tag_plan(&value, TAG_ANNOTATION_NODE, never, unknown),
            TagPlan::Generic
        ));
        let callee = leaf("callee_class", PushOp::Contains, "abc");
        assert!(matches!(
            tag_plan(&callee, TAG_CALL_SITE_NODE, never, unknown),
            TagPlan::CallSite(_)
        ));
        assert!(matches!(
            tag_plan(&callee, TAG_STRING_CONSTANT, never, unknown),
            TagPlan::Skip
        ));
    }

    #[test]
    fn regex_required_literal_reads_simple_patterns() {
        assert_eq!(regex_required_literal(".*Foo.*").as_deref(), Some("Foo"));
        assert_eq!(
            regex_required_literal("com.example.*").as_deref(),
            Some("example")
        );
        assert_eq!(
            regex_required_literal("com\\.example\\..*").as_deref(),
            Some("com.example.")
        );
        assert_eq!(regex_required_literal("a.b").as_deref(), Some("a"));
        assert_eq!(regex_required_literal("plain").as_deref(), Some("plain"));
        assert_eq!(regex_required_literal(".+x.?yz"), Some("yz".to_string()));
        assert_eq!(regex_required_literal(".*"), None);
        assert_eq!(regex_required_literal("ab*c"), None);
        assert_eq!(regex_required_literal("[a-z]+"), None);
        assert_eq!(regex_required_literal("a|b"), None);
        assert_eq!(regex_required_literal("^abc$"), None);
        assert_eq!(regex_required_literal("(abc)"), None);
        assert_eq!(regex_required_literal("\\d+abc"), None);
        assert_eq!(regex_required_literal("abc\\"), None);
    }

    #[test]
    fn regex_predicates_push_when_the_pattern_is_simple() {
        let parse = |q: &str| {
            let clauses = crate::parser::parse(q).unwrap();
            let crate::ast::Clause::Match { patterns, .. } = &clauses[0] else {
                panic!("expected MATCH")
            };
            let where_clause = match clauses.get(1) {
                Some(crate::ast::Clause::Where(e)) => Some(e.clone()),
                _ => None,
            };
            (patterns.clone(), where_clause)
        };
        let (p, w) = parse(r#"MATCH (n) WHERE n.callee_class =~ '.*Foo.*' RETURN n"#);
        assert!(ScanPlan::build(&p, w.as_ref()).is_some());
        let (p, w) = parse(r#"MATCH (n) WHERE n.callee_class =~ '[a-z]+' RETURN n"#);
        assert!(ScanPlan::build(&p, w.as_ref()).is_none());
        let (p, w) = parse(r#"MATCH (n) WHERE toLower(n.callee_class) =~ '.*foo.*' RETURN n"#);
        assert!(ScanPlan::build(&p, w.as_ref()).is_none());
    }

    fn parse_where(q: &str) -> (Vec<crate::ast::Pattern>, Option<Expr>) {
        let clauses = crate::parser::parse(q).unwrap();
        let crate::ast::Clause::Match { patterns, .. } = &clauses[0] else {
            panic!("expected MATCH")
        };
        let where_clause = match clauses.get(1) {
            Some(crate::ast::Clause::Where(e)) => Some(e.clone()),
            _ => None,
        };
        (patterns.clone(), where_clause)
    }

    /// `MATCH (n) WHERE n.graphId IS NOT NULL AND (<string search>)`: the shape that
    /// used to decode every node of every graph because one conjunct had no leaf.
    #[test]
    fn an_unpushable_conjunct_relaxes_the_plan_instead_of_dropping_it() {
        let (p, w) = parse_where(
            "MATCH (n) WHERE n.graphId IS NOT NULL AND (coalesce(toString(n.value), '') \
             CONTAINS 'Expected' OR coalesce(toString(n.name), '') CONTAINS 'Expected' \
             OR coalesce(toString(n.id), '') CONTAINS 'Expected') RETURN n",
        );
        let plan = ScanPlan::build(&p, w.as_ref()).expect("the string side plans the scan");
        assert!(plan.relaxed);
        let mut leaves = Vec::new();
        plan.tree.leaves(&mut leaves);
        assert_eq!(
            leaves
                .iter()
                .map(|l| l.prop().to_string())
                .collect::<Vec<_>>(),
            ["value", "name", "id"]
        );
        // A relaxed plan never lets a survivor skip the WHERE clause, whatever the
        // stream says about it.
        assert!(!plan.verified(true));
        assert!(!plan.verified(false));

        // The conjunct may sit on either side, and under other conjunctions.
        for q in [
            "MATCH (n) WHERE n.callee_class CONTAINS 'java' AND n.line > 20 RETURN n",
            "MATCH (n) WHERE NOT n.callee_name = 'x' AND n.callee_class CONTAINS 'java' RETURN n",
            "MATCH (n) WHERE n.callee_name IS NULL AND n.callee_class CONTAINS 'java' AND n.line > 1 RETURN n",
            "MATCH (n) WHERE (n.callee_class CONTAINS 'java' AND n.line > 20) OR n.callee_name = 'x' RETURN n",
        ] {
            let (p, w) = parse_where(q);
            let plan = ScanPlan::build(&p, w.as_ref()).unwrap_or_else(|| panic!("{q}"));
            assert!(plan.relaxed, "{q}");
        }
    }

    #[test]
    fn a_plan_without_a_dropped_conjunct_still_trusts_its_streams() {
        let (p, w) = parse_where(
            "MATCH (n) WHERE n.callee_class CONTAINS 'java' AND n.callee_name = 'toString' RETURN n",
        );
        let plan = ScanPlan::build(&p, w.as_ref()).expect("planned");
        assert!(!plan.relaxed);
        assert!(plan.verified(true));
        assert!(!plan.verified(false));
    }

    #[test]
    fn nothing_pushable_or_an_unpushable_disjunct_means_no_plan() {
        for q in [
            "MATCH (n) WHERE n.graphId IS NOT NULL RETURN n",
            "MATCH (n) WHERE n.graphId IS NOT NULL AND n.line > 20 RETURN n",
            "MATCH (n) WHERE n.line > 20 OR n.callee_class CONTAINS 'java' RETURN n",
            "MATCH (n) WHERE n.callee_class CONTAINS 'java' OR (n.line > 20 AND n.id IS NOT NULL) RETURN n",
        ] {
            let (p, w) = parse_where(q);
            assert!(ScanPlan::build(&p, w.as_ref()).is_none(), "{q}");
        }
    }

    fn synthetic_leaf(property: &'static str, op: PushOp, literal: &str) -> StringPredicate {
        match leaf(property, op, literal) {
            PredTree::Leaf(p) => p,
            _ => unreachable!(),
        }
    }

    /// `synthetic_truth` for a cross-graph executor over one source named `gid`.
    fn fold(gid: &str, property: &'static str, op: PushOp, literal: &str) -> Option<bool> {
        fold_synthetic(true, gid, &synthetic_leaf(property, op, literal))
    }

    #[test]
    fn synthetic_keys_fold_per_graph() {
        assert_eq!(
            fold("app", "qualifiedId", PushOp::Contains, "app:"),
            Some(true)
        );
        assert_eq!(
            fold("app", "qualifiedId", PushOp::Contains, "pp"),
            Some(true)
        );
        assert_eq!(
            fold("app", "qualifiedId", PushOp::Contains, "acme"),
            Some(false)
        );
        assert_eq!(
            fold("app", "qualifiedId", PushOp::Contains, "acme:"),
            Some(false)
        );
        // Digits could be in the node id: the id decides.
        assert_eq!(fold("app", "qualifiedId", PushOp::Contains, "12"), None);
        assert_eq!(fold("app", "qualifiedId", PushOp::Contains, "app:12"), None);
        assert_eq!(
            fold("app", "qualifiedId", PushOp::Contains, "a:b"),
            Some(false)
        );
        assert_eq!(
            fold("app", "qualifiedId", PushOp::Contains, "a:1:2"),
            Some(false)
        );
        assert_eq!(
            fold("app", "elementId", PushOp::StartsWith, "ap"),
            Some(true)
        );
        assert_eq!(
            fold("app", "elementId", PushOp::StartsWith, "app:"),
            Some(true)
        );
        assert_eq!(fold("app", "elementId", PushOp::StartsWith, "app:7"), None);
        assert_eq!(
            fold("app", "elementId", PushOp::StartsWith, "pp:"),
            Some(false)
        );
        assert_eq!(fold("app", "qualifiedId", PushOp::EndsWith, "7"), None);
        assert_eq!(
            fold("app", "qualifiedId", PushOp::EndsWith, "p"),
            Some(false)
        );
        assert_eq!(fold("app", "qualifiedId", PushOp::EndsWith, "app:7"), None);
        assert_eq!(fold("app", "qualifiedId", PushOp::Equals, "app:7"), None);
        assert_eq!(
            fold("app", "qualifiedId", PushOp::Equals, "app:x"),
            Some(false)
        );
        assert_eq!(
            fold("app", "qualifiedId", PushOp::Equals, "app"),
            Some(false)
        );
        assert_eq!(fold("app", "graphId", PushOp::Equals, "app"), Some(true));
        assert_eq!(fold("app", "graphId", PushOp::Contains, "x"), Some(false));
    }

    #[test]
    fn synthetic_keys_plan_the_whole_type_or_none_of_it() {
        let (p, w) = parse_where(r#"MATCH (n) WHERE n.qualifiedId CONTAINS "app:" RETURN n"#);
        let plan = ScanPlan::build(&p, w.as_ref()).expect("planned");
        assert!(plan.has_synthetic);
        let yes = &|_: &StringPredicate| Fold::Known(true);
        let no = &|_: &StringPredicate| Fold::Known(false);
        let never = &|_: &str| false;
        assert!(matches!(
            tag_plan(&plan.tree, TAG_CALL_SITE_NODE, never, yes),
            TagPlan::All
        ));
        assert!(matches!(
            tag_plan(&plan.tree, TAG_CALL_SITE_NODE, never, no),
            TagPlan::Skip
        ));
        assert!(matches!(
            tag_plan(&plan.tree, TAG_LOCAL_VARIABLE, never, yes),
            TagPlan::All
        ));
        // Together with a CallSite leaf: the CallSite type keeps its indexed subtree
        // when the synthetic leaf is false, and takes everything when it is true.
        let (p, w) = parse_where(
            r#"MATCH (n) WHERE n.qualifiedId CONTAINS "app:" OR n.callee_class CONTAINS "x" RETURN n"#,
        );
        let plan = ScanPlan::build(&p, w.as_ref()).expect("planned");
        assert!(matches!(
            tag_plan(&plan.tree, TAG_CALL_SITE_NODE, never, no),
            TagPlan::CallSite(_)
        ));
        assert!(matches!(
            tag_plan(&plan.tree, TAG_CALL_SITE_NODE, never, yes),
            TagPlan::All
        ));
        assert!(matches!(
            tag_plan(&plan.tree, TAG_STRING_CONSTANT, never, no),
            TagPlan::Skip
        ));
    }

    #[test]
    fn keys_search_expands_per_type() {
        let (p, w) = parse_where(
            r#"MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS "Ids") RETURN n"#,
        );
        let plan = ScanPlan::build(&p, w.as_ref()).expect("planned");
        let never = &|_: &str| false;
        let no = &|_: &StringPredicate| Fold::Known(false);
        // CallSites: the four indexed strings and the two signatures are read raw
        // off the record; a string constant is a column; an integer constant cannot
        // print letters; annotations always decode.
        assert!(matches!(
            tag_plan(&plan.tree, TAG_CALL_SITE_NODE, never, no),
            TagPlan::CallSite(_)
        ));
        assert!(matches!(
            tag_plan(&plan.tree, TAG_STRING_CONSTANT, never, no),
            TagPlan::Column(_)
        ));
        assert!(matches!(
            tag_plan(&plan.tree, TAG_INT_CONSTANT, never, no),
            TagPlan::Skip
        ));
        assert!(matches!(
            tag_plan(&plan.tree, TAG_BOOLEAN_CONSTANT, never, no),
            TagPlan::Skip
        ));
        assert!(matches!(
            tag_plan(&plan.tree, TAG_NULL_CONSTANT, never, no),
            TagPlan::Skip
        ));
        assert!(matches!(
            tag_plan(&plan.tree, TAG_ANNOTATION_NODE, never, no),
            TagPlan::Generic
        ));
        // `properties(n)[k]` is the same map.
        let (p, w) = parse_where(
            r#"MATCH (n) WHERE any(k IN keys(n) WHERE properties(n)[k] = "Ids") RETURN n"#,
        );
        let plan = ScanPlan::build(&p, w.as_ref()).expect("planned");
        assert!(matches!(
            tag_plan(&plan.tree, TAG_STRING_CONSTANT, never, no),
            TagPlan::Column(_)
        ));
        // A digit literal can print from a number: an integer is read off its
        // record, a double decodes.
        let (p, w) = parse_where(
            r#"MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS "10") RETURN n"#,
        );
        let plan = ScanPlan::build(&p, w.as_ref()).expect("planned");
        assert!(matches!(
            tag_plan(&plan.tree, TAG_INT_CONSTANT, never, no),
            TagPlan::Column(_)
        ));
        assert!(matches!(
            tag_plan(&plan.tree, TAG_DOUBLE_CONSTANT, never, no),
            TagPlan::Generic
        ));
        // Every node's `id` prints digits too: tested off the type's id list, even
        // for a type with no columns.
        assert!(matches!(
            tag_plan(&plan.tree, TAG_NULL_CONSTANT, never, no),
            TagPlan::Column(_)
        ));
        assert!(matches!(
            tag_plan(&plan.tree, TAG_STRING_CONSTANT, never, no),
            TagPlan::Column(_)
        ));
        // "true" could print from a boolean, read off its record.
        let (p, w) = parse_where(
            r#"MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS "ru") RETURN n"#,
        );
        let plan = ScanPlan::build(&p, w.as_ref()).expect("planned");
        assert!(matches!(
            tag_plan(&plan.tree, TAG_BOOLEAN_CONSTANT, never, no),
            TagPlan::Column(_)
        ));
        // Without toString a number never satisfies a string predicate.
        let (p, w) =
            parse_where(r#"MATCH (n) WHERE any(k IN keys(n) WHERE n[k] CONTAINS "10") RETURN n"#);
        let plan = ScanPlan::build(&p, w.as_ref()).expect("planned");
        assert!(matches!(
            tag_plan(&plan.tree, TAG_INT_CONSTANT, never, no),
            TagPlan::Skip
        ));
        // Other quantifiers and other list sources are left to the evaluator.
        let (p, w) = parse_where(
            r#"MATCH (n) WHERE all(k IN keys(n) WHERE toString(n[k]) CONTAINS "x") RETURN n"#,
        );
        assert!(ScanPlan::build(&p, w.as_ref()).is_none());
        let (p, w) = parse_where(
            r#"MATCH (n) WHERE any(k IN ["value"] WHERE toString(n[k]) CONTAINS "x") RETURN n"#,
        );
        assert!(ScanPlan::build(&p, w.as_ref()).is_none());
    }

    fn kind_of(plan: &TagPlan) -> &'static str {
        match plan {
            TagPlan::Skip => "skip",
            TagPlan::All => "all",
            TagPlan::Generic => "generic",
            TagPlan::CallSite(_) => "callsite",
            TagPlan::Column(_) => "column",
        }
    }

    fn plans(query: &str) -> Vec<&'static str> {
        let (p, w) = parse_where(query);
        let plan = ScanPlan::build(&p, w.as_ref()).expect("planned");
        plan.tag_plans.iter().map(kind_of).collect()
    }

    #[test]
    fn unknown_properties_reach_only_annotations() {
        // No type stores `fullName`; `class` is a Field and an Annotation column. An
        // annotation's value pairs can spell any key, so that type decodes.
        let p =
            plans(r#"MATCH (n) WHERE n.fullName CONTAINS "x" OR n.class CONTAINS "Ids" RETURN n"#);
        assert_eq!(p[TAG_STRING_CONSTANT as usize], "skip");
        assert_eq!(p[TAG_CALL_SITE_NODE as usize], "skip");
        assert_eq!(p[TAG_FIELD_NODE as usize], "column");
        assert_eq!(p[TAG_ANNOTATION_NODE as usize], "generic");
        // Per graph, a key no string spells reaches no annotation either; `class`
        // is a raw field and still does.
        let (pt, w) = parse_where(r#"MATCH (n) WHERE n.fullName CONTAINS "x" RETURN n"#);
        let plan = ScanPlan::build(&pt, w.as_ref()).expect("planned");
        let unknown = &|_: &StringPredicate| Fold::Unknown;
        assert!(matches!(
            tag_plan(
                &plan.tree,
                TAG_ANNOTATION_NODE,
                &|k| k == "fullName",
                unknown
            ),
            TagPlan::Skip
        ));
        let p = plans(r#"MATCH (n) WHERE n.name CONTAINS "a" AND n.code CONTAINS "b" RETURN n"#);
        assert!(p.iter().enumerate().all(|(tag, k)| {
            *k == if tag == TAG_ANNOTATION_NODE as usize {
                "generic"
            } else {
                "skip"
            }
        }));
    }

    #[test]
    fn numeric_equality_plans_only_numeric_types() {
        let p = plans(r#"MATCH (n) WHERE n.value = 105873 RETURN n"#);
        assert_eq!(p[TAG_INT_CONSTANT as usize], "column");
        assert_eq!(p[TAG_LONG_CONSTANT as usize], "column");
        assert_eq!(p[TAG_DOUBLE_CONSTANT as usize], "generic");
        assert_eq!(p[TAG_STRING_CONSTANT as usize], "skip");
        assert_eq!(p[TAG_BOOLEAN_CONSTANT as usize], "skip");
        assert_eq!(p[TAG_CALL_SITE_NODE as usize], "skip");
        assert_eq!(p[TAG_ENUM_CONSTANT as usize], "generic");
        assert_eq!(p[TAG_RESOURCE_VALUE_NODE as usize], "generic");
        assert_eq!(p[TAG_ANNOTATION_NODE as usize], "generic");
        // Either operand order; a float literal too.
        let p = plans(r#"MATCH (n) WHERE 1.5 = n.value RETURN n"#);
        assert_eq!(p[TAG_FLOAT_CONSTANT as usize], "generic");
        assert_eq!(p[TAG_STRING_CONSTANT as usize], "skip");
        let p = plans(r#"MATCH (n) WHERE n.line = 5 AND n.value CONTAINS "x" RETURN n"#);
        assert!(p.iter().enumerate().all(|(tag, k)| {
            *k == if tag == TAG_ANNOTATION_NODE as usize {
                "generic"
            } else {
                "skip"
            }
        }));
        let p = plans(r#"MATCH (n) WHERE n.line = 5 OR n.value CONTAINS "x" RETURN n"#);
        assert_eq!(p[TAG_CALL_SITE_NODE as usize], "callsite");
        assert_eq!(p[TAG_STRING_CONSTANT as usize], "column");
        // `id` is a number on every node, read off the id list; a synthetic key
        // never is. A CallSite reads its id in the record sweep.
        let p = plans(r#"MATCH (n) WHERE n.id = 5 RETURN n"#);
        assert!(p.iter().enumerate().all(|(tag, k)| {
            *k == if tag == TAG_CALL_SITE_NODE as usize {
                "callsite"
            } else {
                "column"
            }
        }));
        let p = plans(r#"MATCH (n) WHERE n.graphId = 5 RETURN n"#);
        assert_eq!(p[TAG_STRING_CONSTANT as usize], "skip");
        // An ordering comparison compares text where the value is not a number: not
        // planned.
        let (pt, w) = parse_where(r#"MATCH (n) WHERE n.value > 5 RETURN n"#);
        assert!(ScanPlan::build(&pt, w.as_ref()).is_none());
    }

    #[test]
    fn membership_plans_only_list_types() {
        let p = plans(r#"MATCH (n) WHERE "app" IN n.graphIds RETURN n"#);
        assert!(p.iter().enumerate().all(|(tag, k)| {
            *k == if tag == TAG_ANNOTATION_NODE as usize {
                "generic"
            } else {
                "skip"
            }
        }));
        let p = plans(r#"MATCH (n) WHERE "x" IN n.value RETURN n"#);
        assert_eq!(p[TAG_ENUM_CONSTANT as usize], "generic");
        assert_eq!(p[TAG_RESOURCE_VALUE_NODE as usize], "generic");
        assert_eq!(p[TAG_STRING_CONSTANT as usize], "skip");
    }

    #[test]
    fn type_name_folds_per_type() {
        let p = plans(r#"MATCH (n) WHERE n.type = "CallSiteNode" RETURN n"#);
        assert_eq!(p[TAG_CALL_SITE_NODE as usize], "all");
        assert_eq!(p[TAG_STRING_CONSTANT as usize], "skip");
        // A type that stores `type` answers from its column instead.
        assert_eq!(p[TAG_FIELD_NODE as usize], "column");
        let p = plans(r#"MATCH (n) WHERE toLower(n.type) CONTAINS "constant" RETURN n"#);
        assert_eq!(p[TAG_INT_CONSTANT as usize], "all");
        assert_eq!(p[TAG_RETURN_NODE as usize], "skip");
        // An annotation whose value pairs spell no `type` key falls back the same way.
        let (pt, w) = parse_where(r#"MATCH (n) WHERE n.type = "AnnotationNode" RETURN n"#);
        let plan = ScanPlan::build(&pt, w.as_ref()).expect("planned");
        let unknown = &|_: &StringPredicate| Fold::Unknown;
        assert!(matches!(
            tag_plan(&plan.tree, TAG_ANNOTATION_NODE, &|k| k == "type", unknown),
            TagPlan::All
        ));
        assert!(matches!(
            tag_plan(&plan.tree, TAG_ANNOTATION_NODE, &|_| false, unknown),
            TagPlan::Generic
        ));
    }

    #[test]
    fn call_site_signatures_and_lines_are_swept_raw() {
        let p =
            plans(r#"MATCH (n) WHERE n.callee_signature CONTAINS "java.lang.String)" RETURN n"#);
        assert_eq!(p[TAG_CALL_SITE_NODE as usize], "callsite");
        assert_eq!(p[TAG_STRING_CONSTANT as usize], "skip");
        let (pt, w) = parse_where(
            r#"MATCH (n) WHERE toLower(n.caller_signature) CONTAINS "x" OR n.callee_name = "y" RETURN n"#,
        );
        let plan = ScanPlan::build(&pt, w.as_ref()).expect("planned");
        let (tree, leaves) = plan.call_site.as_ref().expect("a CallSite tree");
        assert!(needs_raw_sweep(tree));
        assert_eq!(leaves.len(), 2);
        // A line prints digits; a letter literal cannot come from it.
        let p = plans(r#"MATCH (n) WHERE toString(n.line) STARTS WITH "12" RETURN n"#);
        assert_eq!(p[TAG_CALL_SITE_NODE as usize], "callsite");
        let p = plans(r#"MATCH (n) WHERE toString(n.line) STARTS WITH "ab" RETURN n"#);
        assert_eq!(p[TAG_CALL_SITE_NODE as usize], "skip");
        // The digit search over every key, once the synthetic keys are folded for a
        // graph: CallSites raw, columns test the id, the numeric constants decode.
        let (pt, w) = parse_where(
            r#"MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS "105873") RETURN n"#,
        );
        let plan = ScanPlan::build(&pt, w.as_ref()).expect("planned");
        let never = &|_: &str| false;
        let no = &|_: &StringPredicate| Fold::Known(false);
        let at = |tag: u8| kind_of(&tag_plan(&plan.tree, tag, never, no));
        assert_eq!(at(TAG_CALL_SITE_NODE), "callsite");
        assert_eq!(at(TAG_STRING_CONSTANT), "column");
        assert_eq!(at(TAG_INT_CONSTANT), "column");
        assert_eq!(at(TAG_NULL_CONSTANT), "column");
        assert_eq!(at(TAG_DOUBLE_CONSTANT), "generic");
        // The method of a local, a parameter or a return node is composed from the
        // record, as a CallSite's signatures are; a return node has no columns.
        let p = plans(r#"MATCH (n) WHERE n.method CONTAINS "main(" RETURN n"#);
        assert_eq!(p[TAG_LOCAL_VARIABLE as usize], "column");
        assert_eq!(p[TAG_PARAMETER_NODE as usize], "column");
        assert_eq!(p[TAG_RETURN_NODE as usize], "column");
        assert_eq!(p[TAG_STRING_CONSTANT as usize], "skip");
        let p = plans(r#"MATCH (n) WHERE n.actual_type CONTAINS "String" RETURN n"#);
        assert_eq!(p[TAG_RETURN_NODE as usize], "column");
        let p = plans(
            r#"MATCH (n) WHERE toString(n.index) = "0" OR toString(n.static) = "true" RETURN n"#,
        );
        assert_eq!(p[TAG_PARAMETER_NODE as usize], "column");
        assert_eq!(p[TAG_FIELD_NODE as usize], "column");
        assert_eq!(p[TAG_LOCAL_VARIABLE as usize], "skip");
    }

    #[test]
    fn property_maps_plan_as_equalities() {
        let (p, w) = parse_where(
            r#"MATCH (n:CallSite {callee_class: "java.lang.String", callee_name: "length"}) RETURN n"#,
        );
        assert!(w.is_none());
        let plan = ScanPlan::build(&p, w.as_ref()).expect("planned");
        assert!(matches!(
            plan.tag_plans[TAG_CALL_SITE_NODE as usize],
            TagPlan::CallSite(_)
        ));
        assert!(plan.clause.is_some());
        let (_, leaves) = plan.call_site.as_ref().expect("a CallSite tree");
        assert_eq!(leaves.len(), 2);
        // With a WHERE clause the map is conjoined with it; a number is a numeric
        // leaf; a non-literal value leaves the pattern to the matcher.
        let p = plans(r#"MATCH (n {value: 0}) WHERE n.callee_class CONTAINS "x" RETURN n"#);
        assert!(p.iter().enumerate().all(|(tag, k)| {
            *k == if tag == TAG_ANNOTATION_NODE as usize {
                "generic"
            } else {
                "skip"
            }
        }));
        let p = plans(r#"MATCH (n {value: 0}) RETURN n"#);
        assert_eq!(p[TAG_INT_CONSTANT as usize], "column");
        assert_eq!(p[TAG_STRING_CONSTANT as usize], "skip");
        let (p, w) = parse_where(r#"MATCH (n {value: n.name}) RETURN n"#);
        assert!(ScanPlan::build(&p, w.as_ref()).is_none());
    }

    #[test]
    fn node_ids_print_as_decimal() {
        let mut buf = [0u8; 10];
        assert_eq!(u32_text(0, &mut buf), "0");
        assert_eq!(u32_text(105873, &mut buf), "105873");
        assert_eq!(u32_text(u32::MAX, &mut buf), "4294967295");
    }

    #[test]
    fn batches_start_small_and_stay_bounded() {
        let sources: Vec<SourceIdx> = (0..64).collect();
        let batches = batch_schedule(&sources);
        assert_eq!(batches[0].len(), 1);
        let threads = rayon::current_num_threads().max(1);
        assert!(batches.iter().all(|b| b.len() <= threads * 4));
        assert_eq!(batches.iter().map(|b| b.len()).sum::<usize>(), 64);
    }
}
