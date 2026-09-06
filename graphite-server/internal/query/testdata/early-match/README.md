# General early LIMIT evaluation and lazy MATCH

This is a separate functional compatibility change, not a performance experiment.
The effective native base is `cced41856dd8b4ad5cd41ca4eb9dd59f463fadae`:

1. Count conversion `55621d1c218279b21b92c2d32a834f25142ddd14`.
2. Explicit prerequisite cherry-pick `a960b949` of root EAGER encounter-order fix
   `3f08ecf2`.
3. Explicit prerequisite cherry-pick `cced4185` of root Constant membership fix
   `d9844d42`.

The freeze delta is relative to this effective base; those prerequisite changes
are not counted again as part of the lazy-MATCH delta. Reference main remains
`4e328b0109e13c896b74004823fb049fcb19251a`, Java 17.0.18, unmodified standalone
jar. No root production file, real-64 runtime or performance dataset was changed.

## Results, with all denominators preserved

| Corpus | Before | After |
| --- | --- | --- |
| 60 scenarios × scoped/cross × MAPPED/EAGER | 200/240 exact | **240/240 exact** |
| Previous count corpus | 312/320 exact | **320/320 exact** |
| Previous filtered LIMIT0 corpus | 180/180 exact | **180/180 exact** |
| Corrupt-tail supplement, MAPPED scoped/cross | 0/12 exact | **8/12 exact**, four existing decoder-error discrepancies |
| Main scalar dispatch traces | Separate observation | 8/8 traces show two calls; eight native tests agree |

The new primary corpus fixes 40 differences. The original eight count-corpus
skips are removed, with all complete main records asserted. The original count
and LIMIT0 evidence is not rewritten; previous-count-after.json is a new native
receipt. All primary before/after arrays retain columns, ordered rows, source
metadata, nulls and error class/message. No matching primary case regresses.

The tiny traversal fixture has eight IntConstants with self-loops, cycles,
mixed edge labels, incoming/outgoing arcs and multiple paths. Its single concrete
node class makes both mapped and eager enumeration deterministic for these
checks; this does not claim a solution to JVM Class-identity order for arbitrary
MAPPED supertype scans. The source is the existing TraversalOracle.java and
persisted testdata/traversal. All main loads use disposable copies.

## Exact general-pipeline mechanism

Pinned QueryPipeline.kt:5360–5390 finds the first mandatory MATCH and the first
LIMIT. It admits early evaluation only when:

- That MATCH has exactly one pattern and no WHERE.
- LIMIT follows it; intervening clauses contain only non-DISTINCT RETURNs with
  no aggregate expressions. WITH, WHERE, another MATCH, UNWIND, ORDER BY and SKIP
  prevent this pushdown.
- The LIMIT expression may be a literal, parameter, arbitrary scalar expression,
  or row-variable reference. This is broader than the earlier literal-zero guard.

Main evaluates that expression in **empty bindings before the branch starts**.
It keeps only a positive converted count. A negative or zero value means no early
MATCH cap, not an immediate error or empty result. The ordinary LIMIT clause
still evaluates the expression again later against its current first projected
row and applies the result. No value or function-call result is memoized between
these stages. An eligible LIMIT exception can therefore precede even a WITH or
UNWIND prefix exception. Disallowed shapes preserve ordinary error order.

For positive counts, executeMatch (3854–3869) obtains each input's pattern matches
lazily, stops at the early count, combines them, and truncates the combined result.
The per-input pattern budget is the full early count: main does not reduce it to
the remaining global row capacity before the next input. Graph sources share
that pattern budget; it is never reset per graph.

matchPatternLazily (4000–4030) composes node and relationship sequences with
flatMap. The new native callback chain preserves that depth-first order, including
fixed and variable-length relationships, zero-length paths, direction, node and
edge constraints, repeated-edge exclusion, bound variables, qualified identity,
path construction and provenance. An accepted row reaching the budget stops
further node iteration, target-node decoding and recursive expansion. No later
projection is evaluated for a row that main never matched.

The existing unbounded materialized path remains in place when no early cap
applies. Its helpers delegate to callbacks that always continue, retaining the
existing API and data lifecycle. No index-specific candidate plan is introduced.
The new seams are:

```text
walkNodeCandidatesUntil(..., accept func(any) bool) bool
matchRelationshipUntil(..., accept func(matchState) bool) bool
matchPatternUntil(..., accept func(matchState) bool) bool
```

False means the consumer stopped; true means exhaustion. Existing void-consumer
walkNodeCandidates and slice-returning matchRelationship remain wrappers. The
Attempt 6 indexed WHERE provider can keep its existing interfaces; this general
early-limit qualification excludes WHERE and does not push limits into sources.

## Discriminating oracle cases

The 60 exact queries/parameters are in cases.json. They include:

- Later projection cast errors disappear when a positive early budget stops
  matching before those rows; DISTINCT, ORDER, SKIP, WHERE and aggregate controls
  continue to evaluate the full applicable input.
- Literal, parameter and expression counts, zero/null/negative/error counts,
  old bindings, prefixes and a later throwing UNION branch.
- A LIMIT expression that evaluates to 1 in empty bindings but 3 in projected
  bindings returns only the one row available from matching. Reversing those
  values can expose a later projection error before final LIMIT truncation.
- Source `a` followed by `b` with limit 10 retains all eight a rows and the first
  two b rows, with their actual metadata; no per-source cap is substituted.
- Single-hop directions, variable paths with zero/positive minimum and bounded
  or unbounded maximum, relationship chains, and a repeated relationship binding.
- A zero-hop accepted path stops before a throwing edge constraint is visited.
- A deterministic cancellation context interrupts within the lazy traversal,
  after parsing; no new maximum cancellation latency claim is made.

## Scalar call counts: independent JDI evidence

FunctionTrace.java launches FunctionTraceTarget against the **unmodified** main
jar with the JDK debugger interface. It records actual CypherFunctions.dispatch
method entries and full stacks, without substituting a random source, clock,
function implementation or execution parameter. Each of rand and timestamp,
scoped/cross and MAPPED/EAGER, is captured separately (eight process runs).

For `MATCH (n:IntConstant) RETURN n.id AS id LIMIT rand()*0+$l`, and its timestamp
variant with l=1, every trace has exactly two dispatches. The first stack contains
computeEarlyLimit; the second contains the ordinary projected LIMIT evaluator.
The multiplication removes nondeterminism from the result, not from execution.
Raw target stdout and stderr are preserved in each trace JSON. trace-commands.json
records commands and successful exits.

Native tests observe existing context checks at scalar-call entry through stack
frames, without replacing either function. They require the same two stages,
`early` then `projected`, and the concrete single-row result. This checks that the
implementation neither caches the first value nor evaluates only to reproduce
an error while discarding the matching budget. An initial attempted parameter-map
read counter was discarded during development because main copies parameters;
it was not used as evidence of scalar call counts.

## Corrupt-tail boundary, including the unresolved differences

CorruptionOracle.java loads a disposable MAPPED eight-node fixture, then changes
only the final node's type byte at file offset 75 to 127. Native tests repeat the
same mutation after loading a separate copy. The first three bounded node,
single-hop and zero-hop queries all return id 0 without touching the corrupt
record. A throwing projection after one accepted node reports main's
ClassCastException before that unneeded tail is read. These eight scoped/cross
observations now match main exactly; the old materialized implementation read the
tail first. The expected failing old test log is retained separately.

Two consuming queries (limit 8 and unlimited), in both modes of source selection,
still fail as they must. Their existing decoder classification differs:

- Main: IllegalArgumentException, `Unknown node tag: 127`.
- Native: CypherException, `node 7: unknown node tag 127`.

corrupt-differences.json retains all four full records. The permanent test asserts
that these consuming cases still error, but does **not** claim their error wire
shape is fixed. Correcting core-decoder classification is a separate task. This
supplement is not folded into a claimed all-passing primary corpus.

## Reproduction and frozen evidence

EarlyMatchOracle.java is the full-output oracle; its two exact commands and logs
are main-MAPPED-command.json / main-EAGER-command.json. Compile the development
Java helpers into a disposable directory with Java 17 and the pinned jar. JDI
helpers additionally use --add-modules jdk.jdi. No JVM is needed by the native
runtime or Go tests.

For an external native module copy, copy native_capture_test.go into
internal/query and set EARLY_MATCH_FIXTURE to the absolute traversal fixture,
EARLY_MATCH_MODE to MAPPED or EAGER, CANDIDATE_CONTRACT_CASES to cases.json and
CANDIDATE_CONTRACT_OUTPUT to a new output file. Run the capture test named
TestExternalCandidateContract. Use the original jvm-v3 fixture and count cases
for a fresh previous-count receipt. before-command.json records the exact base
Go overlay (engine.go/traversal.go from the effective base, new helper absent).

Corrupt tests write complete observations when EARLY_CORRUPT_OUTPUT names a new
file. The ordinary regression tests do not write evidence or mutate original
fixtures. All main helpers and debugger children have exited. Full-module race
and vet results, commit, patch and source hashes are in the external freeze;
verification.json hashes this evidence. Keep earlier captures immutable when
validating future changes. No 64-graph benchmark or performance claim is made.
