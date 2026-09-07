# pinned main retained-index cache / scheduling audit

Read-only audit of main `4e328b0109e13c896b74004823fb049fcb19251a`; no native
production edits, frozen artifact changes, 64-graph runs or performance measurements.
All byte values below are **main's logical reservation estimates**, not native allocation
or measured JVM heap use.

## Scheduling boundary

`MappedCallSiteStringIndex.kt:92–115`: `retainedBytes` is the complete live
`reservation.bytes`; `prefersSerialScan` is **<= 1,048,576** bytes, inclusive.
It includes structural CSR/signatures/postings, optional exact tuple index, and three
query caches. Consequently the hint can cross the boundary in either direction.

`MappedWebGraphBackedGraph.kt:1695–1720`: the hint returns that comparison only when
an in-memory `callSiteStringIndex` already exists, the requested type is exactly CallSite,
and all predicates use supported raw string properties. Otherwise it returns false.
A sidecar file or mapped-view object alone is not a live retained index.
`QueryPipeline.kt:1986–2020` additionally requires qualified multi-source execution,
worker count >1, no nested worker, and safe projection expressions. It scans applicable
candidate types and nonempty sources; **any** source whose strategy permits parallel work
can enable parallel execution. Other applicable types such as Annotation can therefore
keep the parallel gate open even if CallSite indexes are small. A non-null strategy
answer takes precedence over the separate prepared-index hint. The selected task mode is
chosen before executing that operation; cache mutations affect later decisions, without
reselecting an already-running branch.

## Three independent access-order LRUs

All three are `LinkedHashMap(..., true)`, maximum **32 entries and 2 MiB per cache**.
They share the index reservation and global index budget. String lengths in estimates
are Java UTF16 units; aliases, object identity, actual compact-string representation and
shared-string deduplication do not reduce the estimates.

| Cache | Key | Entry estimate |
| --- | --- | --- |
| matching strings | `(transform, mode, expected)`; **property omitted** | `104 + 2*expected.length + 4*matchedSIDCount` |
| matching nodes | ordered full predicates `(property, transform, mode, expected)` plus limit; only positive limits <=200 | `144 + 2*sum(property.length+expected.length) + 4*nodeCount` |
| projected rows | ordered full predicates, ordered projected properties (including repeats), limit | `128 + 2*keyCharacters + 64*rowCount + 8*valueReferences + sumNonNullCells(40+2*value.length)` |

Projection aliases are absent from the key. Duplicate properties and duplicate string
cells count repeatedly. Predicate order, duplicate predicates, transform and exact RHS
case remain significant. String matches can be shared across properties. Its cache is
used only with lowercase-trigram eligibility, non-EQUALS modes, and expected length >=3.
Sources: index lines 73–90, 339–363, 2049–2153, 2724–2740.

On insertion, an individually oversized entry is skipped before eviction. Otherwise
eldest entries are removed until both per-cache caps permit insertion, **then** the global
reservation is grown. Overflow/global-budget rejection can therefore leave prior entries
evicted without admitting the new one. Global budget defaults to half max heap and can be
set by `graphite.webgraph.callSiteStringIndexBudgetBytes`; it is shared across graphs.
`tryGrowTo` permits equality with the remaining budget. Sources: 188–234, 434–451,
2155–2205. The three per-cache counters are not a substitute for total reservation bytes.

A cache hit refreshes access order. Node/projection hits do so **before** charging work;
a throwing work callback therefore still refreshes recency. String cache hits do not
charge work in that getter. Duplicate concurrent publication uses `containsKey` for
string/node caches (no refresh), but `projectedRows[key]` for row caches (refresh).

## Publication, cancellation and empty results

- Matching-string results publish after that predicate's full matching computation;
  a later predicate or query failure does not roll them back.
- Matching nodes publish only when their lazy sequence is resumed to completion.
  Consuming the final yielded item without requesting termination is insufficient.
  An empty matching-range result publishes its empty entry immediately.
- Row projection publishes after complete `toList()`. Invalid property selection fails
  before matching-node lookup. A raw field failure during mapping prevents that row-cache
  insertion and may prevent node-sequence completion, but earlier string entries survive.
- Cache insertion methods have **no final interruption/work check**. A callback which
  sets the interrupt flag without throwing can be followed by successful publication.
  Do not implement or claim a blanket “cancel/error means no cache mutation” rule.
- LIMIT <=0 exits before key lookup/work/cache mutation. A positive-limit zero-hit
  lookup can retain empty string/node/row entries and their key overhead.

## Structural growth and release

Base in-memory estimate is `464 + 16*N + 8*sum(uniqueCounts) + 8*S`, where N is
CallSite count and S is string-table count. Initial count-array reservation is `64+16*S`;
a successful build shrinks to the retained base. Optional postings add `16+8*T` only
when actually built. For built T=0, oversized/budget-denied postings return null and add
no retained postings array. A completed null build records initialized state, but
`isTrigramPostingsInitialized()` requires a non-null array. Metadata failure clears its
partial state. Persisted `readFrom` **requires T>0** and the exact stored byte estimate;
a CRC-valid T=0 sidecar is not an admitted retained index. Sources: 590–718, 1488–1531,
2210–2250 and mapped-graph 2222–2332.

Exact tuple indexing is enabled for successfully loaded persisted indexes (`readFrom`
1599 sets the flag true), but is disabled for new in-memory constructor calls. A selected
projection needs >=256 selected values, at least one non-null property and >=4096 nodes.
Capacity is the next power of two >=2*N; retained addition is `160+12*capacity`.
A temporary `16+4*S` reservation is released after success; failed/cancelled construction
restores the original reservation. Clearing query caches **does not** release this tuple
index. Sources: 97–110, 800–820, 1775–1878, 2747–2749.

`clearQueryCaches()` removes only the three query caches and subtracts their estimates.
There is no generic end-of-request rollback. The pipeline's release hook occurs at
`QueryPipeline.kt:2232–2237`: an **empty completed source** merged by indexed DISTINCT,
with more than one candidate source. Ordinary and generic DISTINCT requests do not call
this hook universally. `MappedWebGraphBackedGraph.kt:2010–2055` can keep the persisted
index when its split-retention flag is set, or keep a successfully prepared built index
when persistence is enabled and the thread is not interrupted; both branches clear
query caches. Otherwise it closes the index. Forced graph close also closes the mapped
view, can persist a prepared built index, and releases the complete reservation.
The index cache publishers and Close synchronize on the index. A miss computed outside
that monitor can reach publication after Close and fail on the closed reservation;
Close is not a query-wide transactional cache barrier.

## Independent main probe

`CacheLifecycleOracle.java` uses the actual loaded main index and its real public methods
(reflection only exposes the internal object/state). It reuses contract's two-node persisted
fixture, not a performance workload. Nine state assertions in `verify.py` pass twice:

1. Structural state 888 B; complete node lookup produces string+node caches, 1190 B.
2. A row-cache miss using a node-cache hit whose work callback only sets interrupt still
   publishes four repeated 70,000-unit cells per row: 1,121,950 B, serial=false, interrupt=true.
3. Prime a second projection: 1,122,380 B. On a first-projection hit, a callback throws
   `CancellationException("AUDIT_CANCEL")`; byte count is unchanged but LRU order reverses.
4. LIMIT0 leaves the complete state unchanged and never calls the throwing callback.
5. EQUALS zero-hit adds empty node+projection keys: +362 B =1,122,742 B.
6. Clear restores precisely 888 B and empty LRUs; graph Close nulls its retained-index field.

This direct storage callback probe is **not** a claim that native HTTP exposes the same
work callback API or that all end-to-end cancellation races have been tested. It proves
the main state transitions underlying the source audit. Contract's separate 38-request
oracle additionally verifies full Cypher results: 1006 ->1,121,950 ->89,926 B, with the
large projection and subsequent 33 distinct small projection keys switching whether a
bad later source is consumed. Its original files remain untouched.
