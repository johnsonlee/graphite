# Filtered string aggregation: pinned main contract

Source audit, 2026-09-08. Reference main is
`4e328b0109e13c896b74004823fb049fcb19251a`, read from
`/tmp/graphite-go-main-baseline-clone-4e328b0`. This document records source-backed
requirements, not new runtime observations. No builds, tests, synthetic timing,
or real64 processes were run for this audit. The initial six-case latency evidence
and profiling hypothesis are in `../native64-latency-pilot/next-count-path.md`.

## Dispatch and admission

`graphite-cypher/.../QueryPipeline.kt:400–445,1060–1156` is authoritative.
The root planner first detects unknown labels and routes them directly to general
execution (`executeWithActiveBudget:376`), preserving generic decode/error order.
For known labels it applies source routing; after filtered-node-limit handling
it may re-enter a pipeline with the selected sources and `qualified=true`.
Filtered string count follows node count, label histogram, ordered property limit,
and distinct property limit, and precedes streaming filtered MATCH. Preserve this
order because earlier paths can own otherwise overlapping shapes.

The count path requires exactly MATCH, WHERE, RETURN; nonoptional MATCH; one
pattern; no path variable; one node and no relationship; at most one label; no
pattern properties; a named node variable; RETURN without DISTINCT and with one
item. ORDER BY, SKIP, LIMIT, WITH, extra projections and extra clauses do not enter.
In Go's AST, WHERE belongs to MatchClause, so the corresponding branch has two
clauses and its ProjectionClause must have no attached ordering/pagination.

The output column is the alias or the original expression's Cypher spelling.
The helper locally resolves the label before checking whether the return expression
is COUNT or whether the WHERE has a candidate plan. Its unknown-label branch would
return one Long zero row, but the public dispatcher preempts this with general
execution. **Do not introduce this unreachable helper-local zero behavior into
Go's public path.** Actual public oracle expression-020 returns no rows. The
separate malformed unknown-label observation also returns no rows, while general
WHERE false and WHERE1/0 on the same bytes fail during candidate decoding.
Known labels resolve through `NodePropertyAccessor.resolveNodeLabelOrNull`; no
label means the Node superclass. Multiple labels are rejected earlier.

Admit COUNT(*) or case-insensitive `count` with exactly one argument. DISTINCT is
the function's distinct flag. Null counted expression denotes COUNT(*), not a
literal null. A counted expression can otherwise be any expression; only a subset
qualifies for raw storage. Results are signed JVM Long values, including zero.

## Candidate compiler is broader than exact storage admission

`QueryPipeline.kt:2977–3258` separates these concepts:

* Direct filters recognize property-on-left `=`, STARTS WITH, ENDS WITH, CONTAINS;
  the right operand is a string literal or string parameter. Reversed comparisons,
  nonstring parameters and arbitrary expressions do not qualify. Graph-qualified
  properties are excluded. The property can be wrapped with toLower/toLowercase,
  optionally around coalesce(property, '') when the expected term is nonempty.
  Equality or matching uses Java UTF-16 strings and Java lowercase semantics.
* Exact disjunction recursively flattens OR, accepts direct terms, literal-list IN
  expanded into equality predicates, and a single direct term AND exists(the same
  property), in either order. It deduplicates complete filters in encounter order.
  Every resulting property must occur in the supported-type table below.
* A candidate plan first tries direct, then exact disjunction, then the exact
  quoted-literal regex shape `.*\\Qliteral\\E.*` with a nonempty literal containing
  no `\\E`. Regex remains a residual WHERE test. For AND it recursively chooses
  the lower-ranked available candidate plan, retaining the full condition.
  Rank is summed per filter: equality 0, prefix 100, suffix 200, contains 300;
  add 0 for caller_name/callee_name/name, otherwise 20; subtract min(term UTF-16
  length,64). Ties preserve first encounter.

The raw route requires an **exact** direct filter or exact disjunction for the
entire WHERE, and COUNT(*) or a property directly owned by the matched variable,
excluding graphId, elementId and qualifiedId. A candidate plan alone is not proof
that WHERE can be skipped. Graph guards, numeric residuals, regex and complex
counted expressions require binding/evaluation over candidates.

The count helper's `graphIdEquality` searches AND left before right for a graph-id
equality and filters sources in their original order. It does not replace the root
planner's broader source-routing contract. Conflicting predicates still require
full residual evaluation. The candidate plan is compiled from the full condition.

## Typed partials, nulls and provenance

`QueryPipeline.kt:256–269,1159–1289` visits concrete types in this exact order:

| Type | Supported string properties |
|---|---|
| EnumConstant | name |
| LocalVariable | name |
| FieldNode | class, name |
| CallSiteNode | caller_class, caller_name, callee_class, callee_name |
| AnnotationNode | class, name, caller_class, caller_name, callee_class, callee_name |

Visit a type only when requested node class is assignable from it, and only with
the ordered subset of disjunction filters supported by that type. An absent
CallSite match does not discard Annotation matches. Do not reduce generic Node
count semantics to CallSite-only even though the real64 corpus has no annotations.

For exact raw partials, call storage aggregation only for COUNT(*) or when the
counted property is in that type's supported properties. Pass the property to
storage only for DISTINCT; ordinary COUNT(supported property) uses aggregate count,
because these concrete properties are nonnull. A returned aggregate's count>0
marks `matchedWhere`; non-DISTINCT adds its count, DISTINCT inserts its values.
If the provider returns null/unavailable, use the existing typed direct candidate
path and property accessor. Mark `matchedWhere=true` upon each candidate, **before
skipping a null counted property**. Therefore a source can contribute provenance
to a result of zero. Missing/nonstring counted properties must not be treated as
COUNT(*). The fallback's DISTINCT keys use `cypherValueKey`.

The residual route consumes direct candidates, wraps nodes with nodeValue for
qualified queries, evaluates the complete WHERE and accepts only Boolean true.
Then it marks matchedWhere, evaluates the counted expression and skips null.
DISTINCT normalizes numeric equality (including nested lists/maps), rather than
stringifying values or comparing raw representations. See `CypherValueSemantics.kt:57`.

Across graphs, sum non-DISTINCT partials or union DISTINCT keys globally. Always
return one row. For qualified execution collect source IDs from matchedWhere
partials into an insertion-ordered set in original source order. Omit internal
provenance when this set is empty. Never derive provenance from nonzero final count
or from whether a distinct key was new globally. Public metadata is produced by
the existing result adapter: **CypherExecutor.materializeResult, lines269/287,
sorts graph IDs with Java String natural order (UTF-16 code units)**. The internal
source-ordered provenance set must not be exposed directly as the public array.
Actual public provenance-order-190..193 observes this for ordinary/zero/DISTINCT/
expression counts and graph IDs whose UTF-16 order differs from UTF-8/codepoint
order (U+10000, emoji and U+E000). Preserve sorted output even for count0 with hits.

## Graph parallelism and cancellation

`QueryPipeline.kt:113–169,1097–1134,2409–2475` uses the shared active work tracker
when work tracking is enabled. Parallel partials require qualified execution,
more than one selected source, graphParallelism>1, and no already-active direct
string graph worker. Count calls graph parallelism with graphScoped=false even
if graph sources have been selected. Default source count<40 uses min(sources,
processors,8); >=40 uses min(sources,floor(processors/2)), with one worker for one
CPU. The configured override is parsed and clamped to [1,processors].

Tasks are submitted up to the bounded worker count, and each completion permits
the next source to start. Merge results in original source order. On a completion
error cancel all submitted futures and join started workers before returning;
rethrow runtime/error causes, otherwise IllegalStateException("Parallel graph scan
failed", cause). Do not return while workers can continue mutating retained state.
Residual parallel workers use a local expression evaluator with the same parameter
resolver and interruption checking. No nested graph workers are started.

Storage receives the work-aware aggregation overload whenever a tracker exists;
it is the shared tracker directly, not an ordinary scan's serial/split/mapped
marker. Consequently aggregation's predicate-range parallelization guard for a
SplitGraphWorkBatchConsumer does not apply to this normal count path. Preserve
batch consumption/finally flush order when implementing finite work tracking.
Existing Go finite work accounting remains a broader parity gap.

## Mapped CallSite preparation and aggregation

`MappedWebGraphBackedGraph.kt:344–368,1619–1657,2222–2340`:

1. Return unavailable unless type is exactly CallSiteNode, predicates nonempty,
   every predicate property supported, and the optional distinct property supported.
2. **Before opening/loading a persisted index**, if no retained index exists,
   CallSite count>=4096, and every predicate is CONTAINS with expected Java string
   length>=16, perform dictionary preflight. Deduplicate by transform/mode/term,
   preserving predicate order and ignoring property. For each unique predicate,
   scan string IDs in order; any matching dictionary string immediately defeats
   preflight. Only if every predicate has no match return aggregate zero. This is
   dictionary-wide, so unrelated matching strings must defeat it too. Flush work
   in finally; no empty retained index is published by this preflight.
3. Otherwise obtain retained callSiteStringIndex: reuse retained first; under its
   lock, no CallSites or count>Int.MAX_VALUE returns unavailable. Try the original
   persisted representation. If persistence memory budget denied, return unavailable.
   If unavailable for other reasons, build from raw four-SID records in two passes,
   validating integer-array bounds as consumed; do not decode unrelated node fields.
   It uses mapped node-offset capacity, **not CallSite count or max posting ID**.
4. Call the retained index's aggregate. Do not substitute the distinct projection's
   mapped view/raw shortcut or add candidate certificates and encounter-order scans.

`MappedCallSiteStringIndex.kt:235–315,1278–1439,1192–1217`:

* Prepare matching posting ranges in predicate order. Shared matching SID caches
  and runtime predicate state are keyed by transform/mode/term, excluding property.
  Reuse original equality, trigram eligibility, known-matches and cache semantics.
  This populates only caches that matchingRanges uses, not node-match/row caches.
* Empty ranges return count0 before allocating the node bitset or resolving the
  distinct property index. This path does not read candidate nodes/offset ordering.
* Otherwise allocate `(nodeIdCapacity+63)>>>6` 64-bit words. Traverse each range's
  postings in range order, charging work for **every posting including duplicates**,
  and set `bitset[nodeId>>>6] |= 1L<<(nodeId&63)`. Invalid IDs fail at the JVM array
  access (unsigned shift), not at a new eager certification. Finally flush the
  posting work; count is sum of word popcounts, naturally deduplicating OR matches.
* COUNT(*) and COUNT(n.supportedProperty) stop here. Do not decode nodes or strings
  corresponding to result IDs. Sparse malformed/unrelated record tails can remain
  unread legitimately.
* DISTINCT has a read-order branch: if `matchedCount*4 <= distinctProperty.postingCount`,
  visit set bits by increasing node ID, read only that raw property SID through
  `rawCallSiteStringPropertyId`, then decode that SID. Otherwise scan the counted
  property's rows in directory order and postings until the first matched node per
  row, then decode that row's string ID and move to the next row. Sparse and dense
  paths can expose different malformed bytes and errors; do not choose solely by
  convenience. Empty distinct sets from a nonempty range aggregate become null;
  the query layer treats null as empty. Values deduplicate by Java String equality,
  not SID identity (different IDs may decode equal values).

## Go API reuse and required separation

`store.DistinctStringIndex` already supplies Directory/Postings, shared string-match
caches, ProjectionStringID (single-property deferred reader), ProjectionString,
and main persisted-reader policies. `query.stringIndexMatches` implements the
shared matching SID cache/trigram logic; `distinctAtomMatches` and Java UTF-16 helpers
are suitable comparison primitives. These avoid the separate strict candidate
certificate path used by the generic scanner.

`PrepareDistinctStringIndex` currently calls CannotMatch **after** persisted load
and starts by preparing offsets. That callback is not a drop-in implementation
of aggregation's preflight ordering. Perform authorized preflight explicitly first,
then request the retained reader without ordinary source-count/raw preferences:
MainSource=true, SkipPreparedPreference=true, SourceCount=1, complete scan limit
is a plausible structural-loading adapter; verify its no-CallSite, cancellation,
memory, publication and lazy-offset behavior before claiming equivalence. Do not
call PrepareCallSiteStringIndex merely for aggregation: it eagerly prepares all
trigrams beyond what matchingRanges may need. Do not mark RetainPersisted unless
main's actual aggregation lifecycle does so.

`mainIndexNodeIDs` is not an aggregate primitive: it initializes an ordering heap,
reads ProjectionNodeOrder, deduplicates by adjacent IDs and may populate node-match
caches. Extract/reuse matching-range preparation, then consume via the aggregation
bitset without those extra effects. Existing `mainCandidateIterator` also has
ordinary source-count and mapped-view policy; a count-specific typed fallback must
follow main's directStringCandidates defaults and concrete type ordering instead.

## Independent verification matrix to implement

The source audit does not replace actual-main observations. Capture public results,
numeric types, exception class/message, state transitions and read counters where
possible on tiny synthetic correctness fixtures; never report their timings.

* Admission positives: COUNT*, COUNT property, DISTINCT property, COUNT expression,
  COUNT DISTINCT expression; all direct operators, transforms, parameter values,
  OR/IN/exists guards, AND residuals and regex candidates. Negatives: nonstring
  parameter, empty coalesce term, reversed comparison, extra clauses/items, optional
  match, unknown/multiple labels, property-constrained pattern and unnamed variable.
* All five concrete types plus generic Node; annotations with null counted fields;
  zero matches, duplicate matches across predicates and duplicate values within and
  across graphs; source scope and contradictory graph guards; count0 with provenance.
* Retained and lazy startup; valid, missing and invalid sidecar; preflight threshold
  below/at4096 and term15/16 UTF-16 units; dictionary hit unrelated to predicate
  property; empty preflight before corrupt index/offset data.
* Sparse boundary matchedCount*4 exactly equal posting count and one above; corrupt
  raw counted SID versus corrupt unrelated tail, offset/tag, negative/out-of-range
  posting IDs and duplicate string table values. Both sparse/dense read order.
* Cancellation before call, during preparation/ranges/bitset/distinct; concurrent
  workers canceled and joined; no erroneous cache publication. Work-aware overload
  selection and budget failure/flush behavior when the missing tracker is ported.
* Finally repeat all original ordered1267 real64 cases in cold/warm/startup state
  comparisons and observer-free paired timing. Keep the original failure. This
  change has no P95 acceptance evidence until repeated full trials establish it.
