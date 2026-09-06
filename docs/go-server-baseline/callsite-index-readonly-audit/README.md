# Read-only audit: consume main's persisted CallSite string index

**Conclusion:** Go can read the existing sidecar format directly. No new on-disk
format or query-text template list is needed. The first useful seam is an
optional, lazy Store capability returning exact candidate node IDs in canonical
encounter order; the normal query pipeline can retain projection, aggregation,
DISTINCT, ordering and cross-graph provenance semantics. This is an implementation
proposal based on source and a header inventory, **not a performance result**.

Reference source: pinned main `4e328b0109e13c896b74004823fb049fcb19251a` in
`/tmp/graphite-go-main-baseline-clone-4e328b0`. Native source examined at recorded
HEAD `90787051c4f92da8e4fa9077b613ae36507d67b2`; the root's subsequent Slot work is
outside this snapshot. `source-manifest.json` records the exact files inspected.
No production code was changed, graph runtime started, full index payload scanned,
benchmark run or fixture file modified by this audit.

## Confirmed real-64 inventory

`inventory.py` reads `graphs.tsv`, file metadata, each index's 76-byte header and
8-byte footer, and two identity sidecars. Reproduce from repository root:

```sh
python3 docs/go-server-baseline/callsite-index-readonly-audit/inventory.py /tmp/pr113-exp037-fixture.nXn4fg docs/go-server-baseline/callsite-index-readonly-audit/fixture64-inventory.json
```

`fixture64-inventory.json` records all 64 paths, relevant file sizes/mtimes,
headers and identities. All 64 have `graph.callsite-string-index`, all use v2,
and every computed layout length matches the actual file size. Every header's
32-byte identity equals its `graph.callsite-string-content.identity` sidecar.
All also have `graph.strings.identity`, node data, node offsets and type index.

| Header/file fact | Sum over 64 graphs |
| --- | ---: |
| Index file bytes | 368,089,488 |
| CallSites | 5,046,935 |
| String-table entries | 2,793,940 |
| Trigram postings | 31,587,846 |
| Four property CSR payload bytes | 93,029,824 |
| Trigram signature bytes | 22,351,520 |
| Trigram posting bytes | 252,702,768 |

Individual index files are 4,160,404–7,894,004 bytes. Unique property-directory
entries total 126,308 caller classes, 423,532 caller names, 372,571 callee classes
and 612,447 callee names. These are disk/layout counts, not retained Go heap
measurements. The inventory **does not establish** that the full CRC, posting
values or semantic identities recomputed from graph content are valid. It also
does not prove which main execution path any earlier benchmark selected.

## Exact v2 layout

Filename: `graph.callsite-string-index`. All integers are big-endian Java
`DataOutput` values. Header size is **76 bytes**:

| Byte offset | Encoding | Meaning |
| --- | --- | --- |
| 0 | int32 | Magic `0x47524353` (`GRCS`) |
| 4 | int32 | Version `2` |
| 8 | int32 | String-table entry count `S` |
| 12 | int32 | CallSite count `C` |
| 16 | 32 raw bytes | Graph CallSite content identity |
| 48 | 4 × int32 | Unique string counts `U[0..3]` |
| 64 | int32 | Trigram posting count `T` |
| 68 | int64 | Estimated retained heap bytes (reference accounting) |

After the header, **for each property in order**
`caller_class`, `caller_name`, `callee_class`, `callee_name`:

1. `U[p]` int32 used string IDs, strictly increasing.
2. `U[p]` int32 exclusive cumulative posting ends, strictly increasing to `C`.
3. `C` int32 node IDs, grouped by the string-ID directory. A row's posting range
   starts at the preceding end, or zero. Within each range node IDs are ordered
   by **node-data byte offset**, not by numeric ID.

There are no per-array length prefixes. Then `S` int64 trigram signatures, `T`
int64 trigram postings, and one int64 holding an unsigned CRC32 value. Exact size:

`76 + Σp(8*U[p] + 4*C) + 8*S + 8*T + 8`.

CRC32 covers the preceding logical fields, including the header, but uses
**little-endian bytes for each int32/int64 value**, unlike the big-endian disk
encoding. The 32-byte content identity is fed to CRC unchanged. The footer is
excluded; trailing bytes are rejected. CRC over the raw file prefix is incorrect.
A trigram
posting is `(int64(hash) << 32) | uint32(stringId)`, sorted in Java long order.
The hash is `(u0*31 + u1)*31 + u2` over three **UTF-16 units of Java ROOT-lowercased
text**. Each hash occurs at most once per string. Only strings used by at least
one of the four properties contribute trigram postings. A hash collision is
possible, so candidates must always undergo the exact predicate. Signatures are
a 64-bit filter, with bits `hash&63` and
`(hash xor (hash >>> 11) xor (hash << 7))&63` set for each trigram; they are not an
exact membership proof.

Source: `MappedCallSiteStringIndex.kt` `writePersistent` (720),
`PropertyCsr.writePersistent` (1026), `readPersistent` (1476), trigram generation
(2611–2659), constants (2695–2711); `MappedCallSiteStringIndexView.kt` `load` (260).

Correction after the initial audit: commit `0ce67544` incorrectly described CRC
as operating on big-endian file bytes. A main-generated tiny-index reader test
exposed that error. `persistentChecksum` (1617–1648) uses `updateInt`/`updateLong`
(2681–2691), whose shifts feed low-order bytes first; the mapped view validator
uses `reverseBytes` before its buffer updates (448–499). The independent
`crc-endianness-check.py` validates this against one real persisted index and
records both the matching typed-field CRC and the nonmatching raw-file CRC.
The original 64-header inventory remains unchanged; this correction does not
claim full 64-file CRC/CSR validation or a performance result.

## Identity and validity contract

`graph.strings.identity` is 32-byte SHA-256. When derived, hash big-endian int32
string count, then for each ordered string: int32 **UTF-8 byte length**, followed
by Java UTF-8 bytes. This is not a UTF-16-unit hash. Java replaces isolated
surrogates with `?` for this encoding; native fallback can use
`javastring.WireString` before encoding. Main trusts an existing 32-byte string
identity instead of recomputing it.

`graph.callsite-string-content.identity` is also 32 bytes. When derived, SHA-256
starts with the string identity, then int32 CallSite count. For CallSites in
persisted encounter order append node ID int32, node-data offset int64, and four
property string IDs int32 in the directory order above. It covers the four
search strings, their node identity/order and the string table; it does not hash
every node field or graph edge. Main trusts an existing correctly sized content
identity; if it is missing/unreadable/wrong-sized, main derives it from mapped
node fields. No graph-version, mtime or file-length is embedded in these 32 bytes.
Consequently the sidecar comparison is **not independent proof against a stale
or jointly replaced identity file**. Replicating main's trust model must be
stated explicitly rather than claiming a cryptographically verified whole graph.

Both main readers require exact magic/version, matching `S`/`C`, matching
32-byte identity, `0 <= U[p] <= S`, positive `T`, valid strictly ordered directory
IDs, valid ends terminating at `C`, valid node-ID capacity, ordered trigram
postings whose low int32 is a valid string ID, and a valid complete CRC.
The retained reader additionally compares its retained-byte estimate exactly
and validates each posting range's strictly increasing node offset while
reading. The mapped view requires only a positive estimate, exact total file
size (also capped at `Int.MAX_VALUE`), and checks posting ranges' nonnegative,
strictly increasing offsets lazily before yielding them. Its validation cache
is bounded. A lazy range failure returns unavailable so a raw scan can take over.
CRC/format checking alone does not establish that every posting belongs to a
CallSite with the stated property; the persisted identity is the source trust
boundary, not a per-posting semantic cross-check.

Source: `StringTable.kt` (70–147), `GraphStore.kt` (690–785),
`MappedWebGraphBackedGraph.kt` (217–230, 2370–2444),
`MappedCallSiteStringIndexView.kt` (159–194, 260–418).

## Eligible operations and query semantics

The existing CSR directories can support exact predicates on all four known
non-null String fields without decoding the rest of each CallSite. Test only
distinct directory strings, then expand matching postings. Equality, prefix,
suffix and contains are feasible; the retained main capability exposes all four
modes, with either no transform or LOWERCASE. Scanning directory values gives a
correct fallback for short terms and transforms unsuitable for trigrams. A direct
binary search by *string value* requires separately establishing string-table
ordering; directory ordering alone only guarantees increasing string IDs.

The existing mapped main **trigram** view is narrower: `CONTAINS`, expected
UTF-16 length at least 3, and either LOWERCASE transform or raw matching with an
all-ASCII expected term. For raw ASCII it lowercases the expected term only to
locate trigram candidates, then rechecks raw contains. For LOWERCASE the supplied
expected operand is not lowercased a second time. It checks all expected trigram
spans for absence, chooses the shortest span, then applies the exact predicate
to candidate strings. It does not blindly return a trigram's postings as matches.

A native planner may recognize this expression structure for arbitrary aliases,
literals and type-checked parameters. Typed simplifications can recognize
`toLower(toString(coalesce(n.callee_class, '')))` as LOWERCASE of a known non-null
CallSite string field: toString/coalesce are redundant for that field. This must
be a semantic rule, not a comparison against the 42 recorded query texts.
Raw, lowercased, short, Unicode, parameterized and unseen terms need correctness
cases. `javastring` provides the required Java casing/UTF-16 operations.

The safest first integration is a single unbound CallSite node pattern with a
complete predicate consisting of supported atoms or an OR of supported atoms.
Merge posting ranges by offset, deduplicate node IDs within each source, and feed
candidate nodes to the existing WHERE evaluator/pipeline. A false-positive
candidate is acceptable if rechecked; a false negative is not. An AND can later
intersect sets or use a necessary condition, but arbitrary predicate reordering
can change null/error/short-circuit behavior. Do not eagerly evaluate a row-
dependent or throwing operand merely to form an index plan, especially on empty
graphs. Unsupported/dynamic predicates, functions, labels and correlated bindings
must retain the ordinary path. Predicate constants must be semantically safe to
resolve in the same execution context, not evaluated as a new planning phase
with different error behavior.

Direct projections of the four strings are possible later, with a small raw
four-string-ID accessor for other projected fields. Index directories alone do
not give an O(1) node -> all-property mapping. `id`, source/graph ID, other node
properties and whole-node output must use their real values and source context.
`caller_signature`, `callee_signature` and `line` are not persisted in this index.
Arbitrary projections remain valid when the ordinary executor handles matched
nodes. Counts can count deduplicated node matches; summing overlapping OR posting
lengths is incorrect. DISTINCT across projected values or tuples is different
from node deduplication and must preserve merged graph provenance. Equal numeric
node IDs in two graphs remain two source-qualified identities. Do not apply a
per-graph LIMIT before global ORDER BY/DISTINCT/aggregation/UNION, or drop a
cross-graph provenance contribution because a tuple was already seen. OPTIONAL
MATCH and bound-variable behavior must remain with the existing executor.

Source: `Graph.kt` string capability contracts (24–42, 214–298),
`MappedCallSiteStringIndexView.kt` (208–240, 516–520),
`MappedWebGraphBackedGraph.kt` raw fields (2448–2539).

## Missing/corrupt index, cancellation and lifecycle

Main's mapped loading defaults to no eager preparation, with persistence enabled.
The property `graphite.webgraph.prepareCallSiteStringIndexOnLoad=true` enables
preparation; explicit `false` also disables persistent-index use. Index save is
best effort, uses a temporary file plus atomic replacement when available, and
propagates cancellation. GraphStore deletes a stale index before saving a new
graph. Sidecar generation/preparation should not be added to native read-only
Open implicitly.

The cross-graph persistent-only loader returns unavailable for missing, corrupt,
or memory-budget-denied files so graph/segment raw scanning can continue. It does
not silently build the full heap index there. Other main call paths may use the
existing in-memory builder if admitted, so it would be wrong to claim that main
never rebuilds. The retained index budget defaults to half the Java max heap,
with `graphite.webgraph.callSiteStringIndexBudgetBytes` override; this is an
implementation admission policy, not the index format. The mapped view has its
own consumer eligibility and bounded validation cache. A failed mapped-view
load is remembered as unavailable until graph close; a simply absent file is
checked before that flag is set. First relevant queries perform validation and
must obey cancellation/work accounting. Cancellation is propagated, not treated
as corruption or a successful empty result.

On graph close main releases its index/cache resources; request-cache release
can retain a persistent structural index for split cross-graph reuse, clearing
query-result caches. Native can start with no query-result cache: one immutable
validated view owned by Store, published under a lock, with lifetime bounded by
Store's existing graph lease and Close. Optional unsupported/missing/corrupt must
be distinguishable from “valid index, zero matches.” Never fall back after rows
have already been emitted if that would duplicate rows; validate all selected
ranges before publishing or use a restartable buffer. Partial cancellation or
partial validation must not poison the cached unavailable state.

Source: `GraphStore.kt` (65–72, 584, 886–960),
`CallSiteIndexPersistenceInput.kt` (14–55),
`MappedWebGraphBackedGraph.kt` (1659–1694, 2009–2061, 2337–2424).

## Native seam and smallest falsifiable implementation

Current Store already owns `Strings []string`, `NodeCount`, `NodeSpan`, a private
node-ID -> `(offset, tag)` map, an optional node-data mapping and type buckets.
Public `NodeIDs`/`NodesOfKind` return copied ID slices; `Node(id)` decodes a full
record, including both MethodDescriptors and argument data. It does not read or
expose this string index, raw field IDs or canonical offsets. The current generic
query `nodeCandidates` walks **all NodeIDs for non-Method labels** and only tests
labels after decoding. `matchSingleNode` reduces intermediate row creation but
still uses that full candidate decoding path. The root's Slot work may change
this; integration should target a candidate-provider boundary rather than clone
an outdated traversal implementation.

Recommended bounded first patch, not implemented here:

1. Add a lazy, read-only v2 index view in Store using checked byte offsets over
   mapped bytes. Reuse the existing immutable store string table and offset map.
   Separate `available=false` from successful empty results and from cancellation.
   Validate the full file CRC/directories before publishing the view; validate
   selected posting order before yielding. Store.Close releases the mapping.
2. Add an internal raw accessor returning `[4]int32` plus a canonical offset
   without constructing a Node. A CallSite record begins with int32 ID + byte tag;
   caller fields begin at offset+5, caller parameter count is at fields+8, callee
   fields at `fields + (4 + callerParameterCount)*4`. Check all arithmetic, bounds,
   tags and string IDs. This supports legacy identity derivation, validation and
   later projections; it does not itself skip query semantics.
3. Expose a typed candidate iterator for an OR of four-field contains predicates,
   applying the existing trigram eligibility plus exact Java string checks.
   For ineligible terms either scan the relevant CSR strings or return unavailable
   to the existing executor. Integrate only a complete pure recognized predicate
   initially; leave result materialization/aggregation to the ordinary pipeline.
4. Verify on main-produced small correctness fixtures: non-monotonic/gapped node
   IDs, duplicate OR hits, two sources with the same IDs, lower/raw and UTF-16
   edge cases, wrapped property expressions, short/missing terms, unknown
   predicates, projections/ORDER/LIMIT/DISTINCT/aggregate/OPTIONAL semantics,
   absent/wrong-version/truncated/bad-CRC/bad-range/stale-identity cases and
   cancellation during identity/CRC/range validation. Assert concrete ordered IDs
   and complete results, and assert zero full-node decodes for rejected candidates.
   These are correctness/source-access assertions, not synthetic benchmarks.
5. Only after independent semantic verification, use the existing pinned real-64
   before/after harness to test the hypothesis that reducing full-node decode
   and repeated transformation reduces allocation/CPU. Separate first-index
   validation from reused-view queries, preserve complete output hashes and
   include unsupported-path controls. No speedup is established by this memo.

Unresolved by this read-only audit: full CRC/posting validation of the 64 files,
actual route/admission selection in prior main timing runs, the best Go lifetime
or memory budget under concurrent leases, and performance of the proposed reader.

### First delivery boundary: reader only, no query optimization

The root's chosen next milestone is narrower than steps 1–3 above: implement and
independently validate the optional **reader only**, with no query integration,
sidecar writer, eager preparation or query-result cache. A proposed API boundary
(not existing code) is `Store.TryCallSiteStringIndex(ctx) (view, available, error)`:

- `available=false, error=nil`: absent, unsupported, malformed, checksum-invalid
  or identity-incompatible optional index; the graph itself remains usable.
- `available=true`: the complete validated view, including the possibility of
  zero matches. All backing mapping ownership remains with Store until Close.
- `error!=nil`: cancellation/deadline or an actual required graph-content failure
  encountered while deriving missing identity, which must not be hidden as a
  successful empty result. The implementation must decide how to expose an
  existing core-data corruption using Store's normal error path; this memo does
  not establish all malformed-graph exception timing parity.

For this bounded reader stage, validate **all** CSR posting ranges against
Store's offsets before exposing them, rather than reproducing the mapped main
view's lazy selected-range checks. Expose immutable directories and posting
iteration internally; any public candidate operation must promise increasing
node-data offset with source-local deduplication, never numeric-ID order.
Missing optional indexes and corrupted optional indexes can share the fallback
behavior but should remain distinguishable in diagnostics. Do not swallow a
cancellation, cache a partially validated mapping, or close a mapping when one
query finishes while another graph lease still uses it.

CRC32 plus comparison to a trusted 32-byte identity protects against ordinary
truncation/corruption and mismatched independently generated sidecars. It is not
an adversarial authenticity scheme. An attacker can recompute CRC, reuse or forge
an identity file, and place wrong-but-in-range node IDs into different property
rows while maintaining ordering and counts. Main's validators do not recompute
all node/property associations or prove complete per-property node coverage.
A stronger semantic validator could compare each posting to the raw four-string
accessor and verify exact coverage, but that is an additional explicit scope,
not something established by a successful main-format CRC/CSR validation.
The planned full real-64 CRC/CSR validation is a later **correctness** exercise;
it must not be described as a throughput or memory benchmark. This audit has
not performed it, and has not started a second 64-graph query runtime.
