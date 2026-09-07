# Attempt 11: generic direct-property DISTINCT streaming

The original working directory and command logs say `attempt9`. The parent subsequently
assigned Attempt 9 to a rejected selected-SID cursor design and Attempt 10 to binary SID
search. An intermediate delivery status called this candidate Attempt 10. Those original
paths and oracle records have not been renamed or rewritten; its final number is 11.

This candidate changes one hypothesis: fuse matching and direct projection, retaining
only the distinct rows needed for LIMIT/SKIP. It runs **after** `indexedDistinct` declines,
so it neither replaces nor claims to fix the raw indexed projection regression.
Its effective base is `1d23843ac1e79e00bacd87befa60980b65181293` plus the independently
verified DISTINCT integration patch
`8b51d784f8d70674b24ef45ec4a1b4140086f416c0b3dbffa688dc51a3cc50c4`.
No performance measurement, 64-graph execution, or commit was performed for this candidate.

## Scope and implementation

The AST gate accepts a single mandatory named node MATCH with a compilable direct
string disjunction followed by DISTINCT RETURN and a positive literal LIMIT. Projection
items can be any direct property of that node or literal: property names are not a query
workload whitelist. Duplicate aliases overwrite their original slot while columns retain
all aliases. ORDER BY, WITH, arbitrary functions, aggregates, paths, multiple patterns,
inline properties and OPTIONAL remain on the existing executor. The predicate compiler
is the existing main-derived direct-string compiler, including parameter strings, IN,
raw/lower/lower(coalesce(...,'')), OR and its empty-coalesced-term rejection.

Main's literal count conversion is retained, including its nonliteral SKIP behavior in
this specialized path. With SKIP, the complete WHERE is evaluated after direct filtering
and DISTINCT uses Cypher keys. Without SKIP, Java Map/List equality distinguishes numeric
box types and signed zero; NaNs compare by their canonical Java wrapper identity. A hash
bucket is followed by structural equality. Hash values are not exposed as Java hashCode.
The mixed-value fixture verifies distinct int/long/float/double, signed zero, nested lists,
enum references, strings, null and booleans through complete public results.

Each source uses a demand-driven producer. A request advances the existing A6/A7 candidate
walker; its scan fallback reads QueryNodeIDs and CandidateNode, preserving full-node
consumption and the typed failure mapping. CandidateNode protects mapped bytes against
concurrent Close. The producer pauses on each borrowed slot; projections copy properties
into reusable values. Only retained rows receive binding maps. Every exit cancels and
joins producers, including task failures. No slot pointer is published.

Scoped LIMIT stops without an unsolicited next-node decode. Qualified execution exhausts
remaining sources to collect every selected row's provenance, even when a projected
graphId makes a later duplicate impossible. Initial local distinct batches are bounded by
LIMIT+SKIP; selected-value suffix scans reuse their values. Existing index preparation and
candidate ID sets remain unchanged and can still require space proportional to candidates.
This change bounds retained binding rows, not the complete query's memory usage.

The source-order fixed waves use the existing main-derived task runner and default worker
count rules. Merging and selected-row suffix consumption match the concrete 1/2/9/40-source
oracles below. Main also has a prepared-index `prefersSerialScan` strategy gate; exact task
selection after every possible Store preparation history is **not established** here.
Concurrent failures from differently damaged sources could expose that remaining boundary.
The query result, not the task completion order, determines returned row order.

## Oracle and verification evidence

Oracle: Java 17.0.18 ARM64, main
`4e328b0109e13c896b74004823fb049fcb19251a`, actual executable JAR SHA256
`91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d`.
The JVM is an independent correctness oracle only; native execution has no JVM backend.
`GenericOracle.java`, `GenericFaultOracle.java` and `GenerateGeneric.java` call the actual
public executors and persisted writer. `reproduce.py` writes to a separate output directory.

- `main.jsonl`: 320/320 complete result comparisons. Forty queries cover arbitrary
  direct Annotation properties, mixed Java values, duplicate aliases, `$metadata`,
  graphId, stringified annotation values, literals, LIMIT and SKIP; MAPPED/EAGER and
  scoped/2/9/40 sources are tested.
- `edge-main.jsonl`: 120/120 checks. 112 use complete result/error comparison; eight
  use direct UTF16 column/key/value assertions because Go encoding/json cannot recover
  lone-surrogate keys. These assert the main key order and last-alias overwrite, as well
  as nonliteral/null/fractional SKIP/LIMIT and Method/unknown/Node/Constant labels.
- Three `*-main.jsonl` files: 432 clean/corrupt cases, with full raw Java UTF16 strings
  retained. `wire_oracles.py` produces separate final-wire equivalents. Clean 144/144
  match main; total **360/432** match main. Twenty-seven cases improve scoped early stop
  relative to the effective base. The remaining **72** mismatches are identical to the
  base: a main storage lookup can skip a corrupt nonmatching record while the existing
  native A6 certificate falls back to full decoding. This candidate does not certify
  those 72 as compatible. `generic-fault-audit.json` records main/base/candidate outputs.
- Existing indexed DISTINCT suite: all 1,048 complete outputs were replayed. 580 raw
  eligible cases still match main. Only two outputs change from the effective base,
  both correcting scoped early-stop errors. Total main equality is 741/1,048; 307
  existing declined-path differences remain (180 error differences, 120 main-success /
  native-error, seven successful-result differences). The unchanged 580 cannot be used
  to claim all 1,048 pass.
- Demand/read-error/cancellation/Close and AST eligibility tests assert concrete values
  and errors. The entire native module passes `go test -race ./...` and `go vet ./...`.

The independent main Graph-proxy source-consumption audit used during design is frozen at
`/tmp/graphite-main-distinct-fallback-audit`, manifest
`ef3eb0775a184d42c8742702fd7f79db79242f1f31ab0191ff299aaef545a3a1`.
Its 24 cases prove scoped one-read versus qualified complete later-source consumption,
including late decode errors. Arbitrary WHERE/RETURN expressions from that audit remain
outside this candidate's AST gate; their existing general executor behavior is unchanged.
