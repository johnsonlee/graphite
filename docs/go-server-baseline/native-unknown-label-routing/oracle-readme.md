# Unknown-label routing: original JVM contract and observations

This is an independent correctness oracle for main
`4e328b0109e13c896b74004823fb049fcb19251a`. The original executable JAR SHA256 is
`91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d`.
No Go code or earlier experiment evidence was changed. No performance measurements
were made. The source-constructor experiment remains independent.

`cases.json` specifies 120 bounded cases. `main.json` and `repeat-main.json.gz`
contain two complete original-JVM executions on independent physical graph copies:
240 calls total. Each run has 96 successful responses, 22 IllegalArgumentExceptions
and two CypherExceptions. All failures occur during execution, with no fixture-load
failures. Complete public columns/rows, top-level JVM value types, errors including
null messages, causes/stacks, phase and before/after retained/mapped states are
preserved. Public fields and source states match between runs without exceptions
or state normalization. The initial 114-case pilot remains archived separately.

## Case matrix

| Group | Cases | Boundaries |
|---|---:|---|
| Label order | 60 | Ten label sequences × clean/bad node0/bad CallSite24 × single/cross |
| Clauses and bindings | 32 | Sixteen queries × clean/bad node0; COUNT, OPTIONAL, UNWIND, bound values, MATCH/commas, UNION/ALL and LIMIT |
| Known-label controls | 6 | Actual Method, CallSite and IntConstant rows on clean/bad node0 |
| Source selection | 8 | Known-first/unknown-first × WHERE selects good/bad graph × scope false/true |
| Relationships and seek | 8 | Start/target label constraints and elementId lookup × clean/bad node0 |
| WHERE failure order | 6 | Unknown WHERE false/1÷0 and known:unknown WHERE false, single/cross |

Label sequence order is encoded directly in the original query string and is never
sorted or normalized. Sources retain explicit input order. Cross-graph cases use
separate physical stores. Scope controls pass the original public
`CrossGraphCypherExecutor(sources, context, graphSourceScopeApplied)` constructor.
Single-graph cases use `CypherExecutor(graph, context)`. Every context uses the
original Long.MAX_VALUE work budget and an uncancelled original cancellation signal.

## Actual behavior that constrains the native fix

`QueryPipeline.executeWithActiveBudget` routes any branch containing an unknown
node label to `executeGeneralClauses` before root graph-ID pruning and fast paths.
This dispatch does not mean the entire branch returns empty immediately.

`nodeElementCandidates` (`QueryPipeline.kt:4140`) first checks whether **any** label
is Method. If so it chooses metadata-backed Method candidates. Otherwise its first
label determines the concrete class; an unknown first label yields no candidates.
Existing bindings are considered only after that source choice. General node
constraints check all labels after a candidate is obtained. The original source
order and deferred reads therefore matter:

* `Missing:IntConstant` avoids the corrupt node; `IntConstant:Missing` decodes it
  and raises `IllegalArgumentException: Unknown node tag: -1`. The same distinction
  holds for `Missing:CallSite` versus `CallSite:Missing` with the CallSite record
  corrupted. Node superclass variants preserve the same first-label behavior.
* `Method:Missing`, `Missing:Method`, and `Method:IntConstant:Missing` return no rows
  even with a corrupt ordinary node or CallSite. They first choose Method metadata,
  then reject the incompatible full label list. Positive Method controls prove the
  fixture contains an actual Method and is not accidentally empty.
* Unknown-label WHERE false and WHERE1/0 both return no rows without reading the bad
  node or evaluating the WHERE expression. Known-first `IntConstant:Missing WHERE
  false` still reads the bad node before the full label/WHERE constraints reject it.
* An earlier typed MATCH or first comma-separated typed pattern can fail on corrupt
  data before a later unknown pattern. Reversing the pattern order can produce no
  rows without that read. Existing bindings and empty intermediate rows are kept.
* COUNT over an empty unknown match returns Long zero. OPTIONAL MATCH yields its
  null binding; COUNT(*) over that optional row is one while COUNT(n) is zero.
  Earlier UNWIND values survive OPTIONAL null binding. UNION/UNION ALL still execute
  the other branch and retain its rows/metadata.
* LIMIT0 returns no rows; LIMIT−1 produces the exact negative-count error. LIMIT1/0
  raises `CypherException: Division by zero`, including when no node could match.
  Returning empty before general LIMIT evaluation would incorrectly hide this error.
* In a cross query with good and corrupt sources, `IntConstant:Missing` still reads
  the corrupt source even when WHERE graphId selects the good source, for both scope
  flags. Unknown-label general dispatch preempts root pruning. Reversing the label
  order avoids candidate reads altogether. These observations distinguish WHERE
  selection from supplying a physically pruned source list to the API.
* A relationship target labelled Missing can still trigger an error when its
  neighbor node is decoded. An unknown start label can prevent traversal entirely.
  The exact elementId seek likewise reads the requested node before resolving its
  unknown label (`QueryPipeline.kt:627–630`). Neither is equivalent to a blanket
  unknown-label empty fast path.

The expected native change must preserve these downstream differences, and remove
old oracle exceptions only after actual execution matches. This directory does not
claim that a native implementation has been changed or verified.

## Fixture derivation and audit

All graphs are copies of the existing JVM-produced all-types fixture at
`graphite-server/internal/query/testdata/main-string-source/all-types`. It contains
ordinary nodes, a CallSite, Method metadata, and edges; it already has the original
node index. The original ten source files are hashed and are never opened by JVM
execution. Both final runs independently create 185 physical graph copies.

Mutation offsets are derived from each clone's actual `graph.nodeindex`, not guessed:

| Node | Original index tag | Node-data record offset | Tag-byte offset | Mutation |
|---|---:|---:|---:|---|
| 0 | 0 (IntConstant) | 8 | 12 | unsigned255, decoded as signed−1 |
| 24 | 12 (CallSiteNode) | 233 | 237 | unsigned255, decoded as signed−1 |

The preparation script decodes every index record as ID/tag/offset, finds the
requested ID uniquely, verifies the node-data ID and tag agree, and records the
index record byte position. If a node-offset file exists, it independently checks
its stored offset too. `mutations.json` preserves all 77 explicit changes with
original and mutated file hashes. The before-copy manifest proves all 1,850 copied
files match the original fixture. The before-JVM manifest differs only in those
77 declared `graph.nodedata` files. No file changes or disappears during either JVM
run; 370 node-offset/type-index sidecars are generated per run and fully listed.

`verify.py` checks source-file hashes, both copy/mutation/runtime audit stages,
complete response and state equality across runs, and the decisive order/error
observations above. `oracle-inputs.json` hashes original main source, JAR and helper;
`commands.json` and `oracle-receipt.json` record successful terminal executions.
The oracle manifest explicitly lists its own files and does not capture other
agents' mutable files.

Reproduce with Java17 and fresh output directories:

```sh
python3 docs/go-server-baseline/native-unknown-label-routing/prepare.py /tmp/graphite-unknown-label-fresh
mkdir -p /tmp/graphite-unknown-label-fresh/classes
javac -cp /tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar -d /tmp/graphite-unknown-label-fresh/classes docs/go-server-baseline/native-unknown-label-routing/UnknownLabelOracle.java
java -Xmx512m -cp /tmp/graphite-unknown-label-fresh/classes:/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar UnknownLabelOracle /tmp/graphite-unknown-label-fresh/fixtures /tmp/graphite-unknown-label-fresh/cases.json /tmp/graphite-unknown-label-fresh/main.json
python3 docs/go-server-baseline/native-unknown-label-routing/verify.py
```

The last command verifies the archived original-JVM captures. Native execution,
module tests and real64 correctness/state replay belong to the later implementation.


## Bounded typed-source and node-offset extension

The final corpus has 140 cases, run twice on fresh physical copies (280 actual JVM calls). The original 120 cases and captures, including their manifest and audit, are preserved verbatim inside `initial120-oracle.tar.gz`; their full public results and source states match the final corpus prefix. The six predicate-order cases also retain the older 114 prefix archives.

Cases 120–127 change only node24's raw tag from CallSite (12) to IntConstant (0), preserving the persisted type index. The legacy index proves node24's record starts at byte233 and its tag at237. Main returns `{id:24,type:IntConstant,value:5}` for a fresh sole CallSite candidate. Duplicate/multiple CallSite labels, a bound CallSite variable, and CallSite seek reject the actual IntConstant. Bound IntConstant and IntConstant seek accept it. The cross-graph control preserves both the mutated g0 IntConstant and clean g1 CallSite with complete metadata and types.

Cases 128–136 explicitly install the original JVM-generated mapped offset/type indexes from the first 120-case clean control, then modify node24's eight-byte offset slot at byte200. Both reference sidecars are independently reconstructed byte-for-byte from all original legacy-index entries by `verify.py`. The stored value encodes offset+1: stored0 is decoded−1 and returns no node; stored−1 is decoded−2 and throws `java.io.EOFException` with a **null** message, including the seek controls. Aliasing slot24 to node0's offset8 (stored9) returns the decoded node's actual ID0/type IntConstant/value−17 on the fresh sole CallSite scan. The seek controls return empty. All three results are actual public observations, not assumptions about malformed offset behavior.

Cases 137–139 seek IDs−1,31,2147483647 while node0 has tag255. The mapped offset table has31slots (max validID30). All three return empty without decoding the malformed node; the final case uses cross-graph qualified elementId. This does not generalize to every planner path.

Each run copies 207 original physical fixtures (2070 files), declares 18 sidecar additions and97 exact file mutations, and hashes fixtures before/after JVM execution. All preexisting files remain unchanged during execution; main adds396 mapped sidecars. Two-round complete public result/error/message/type/source-state comparisons have zero differences. There are113 successes,22 IllegalArgumentException,2 CypherException and3 EOFException per run. No performance measurements are made.
