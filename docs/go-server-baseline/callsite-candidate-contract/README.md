# CallSite candidate-provider eligibility contract

This is a bounded source audit and tiny correctness oracle, not an implemented
optimization or performance result. The first stage below is deliberately a
capability boundary: all other queries continue through the complete evaluator.
It does not narrow the project's 100% main-parity objective.

Reference main is `4e328b0109e13c896b74004823fb049fcb19251a`, using the existing
standalone jar and Java 17.0.18. `native-snapshot.json` identifies the independent
Go source copy at `dfe6c4203fe39d997950c260d94a1df5445c94cd`. No production source,
Attempt 5 worktree, or real-64 fixture was modified. No benchmark was run.

## First-stage admissible plan

All of these conditions must hold before substituting candidate enumeration:

1. This is the initial MATCH of the query branch, with its one initial empty
   binding row. It is mandatory, contains exactly one pattern with one named
   node, no relationship, path binding, or inline properties. The variable is
   unbound. The label list has exactly one label resolving through the existing
   resolver to CallSiteNode (`CallSiteNode` or `CallSite`, including the same
   case handling). Arbitrary variable names are matched structurally in the AST.
2. The **whole** WHERE is an atom or a tree of ORs of admissible atoms. An atom
   is `operand = term`, `operand CONTAINS term`, `operand STARTS WITH term`, or
   `operand ENDS WITH term`. No negated mode, AND, regex, comparison reversal,
   arbitrary true/false arm, or unsupported OR arm is partially extracted.
3. `operand` is one of the four non-null CallSite string properties
   `caller_class`, `caller_name`, `callee_class`, `callee_name` on that variable,
   possibly wrapped by the grammar below. It is not a similarly named field on
   another variable, a map lookup, signature, line number, or provenance field.
4. `term` is a string Literal, or a Parameter whose supplied immutable execution
   value is a string. A missing, null, numeric, or other parameter falls back.
   Read that value directly; do not run the general expression evaluator during
   planning. Calls, arithmetic, casts and row Variables are not constants for
   this stage, even when a human can simplify them.
5. Every needed reader mode and transform is available and the reader satisfies
   the validity, encounter-order, and lifecycle contract below. Otherwise the
   original enumeration is used before any candidate is emitted.

A bounded wrapper grammar, with at most one lowercasing operation, is:

```text
P := variable.caller_class | variable.caller_name
   | variable.callee_class | variable.callee_name
I := P | toString(I) | coalesce(I, literal-empty-string)
L := toLower(I) | toLowercase(I)
O := I | L | toString(O) | coalesce(O, literal-empty-string)
```

Only ordinary calls with exactly the displayed argument count, no DISTINCT or
star, and the existing case-insensitive function-name resolution qualify.
Reject a second lowercasing operation in this first stage. Track identity versus
LOWERCASE as a typed simplification; do not execute arbitrary AST calls. Because
P is a non-null string for a CallSite, these identity wrappers have no visible
value conversion and the literal fallback has no effect or error. This includes
`toLower(toString(coalesce(site.callee_name, '')))`. It does **not** include an
ignored extra argument such as `toString(P, 1/0)` or a dynamic coalesce fallback.

Use the existing Java string semantics for exact matching and Java ROOT
lowercasing, including WTF-8/UTF-16 boundaries. Do not lowercase the right operand:
`toLower(P) CONTAINS 'CAL'` is false for `callee`. Empty/short/non-ASCII strings
require a correct directory scan or unavailable result when the trigram path
cannot handle them; they are never evidence that there are zero candidates.

## What remains in the evaluator

The provider returns only source-local candidate node identities, optionally a
superset of matches. Decode each candidate and evaluate the original pattern and
WHERE. Retain the original projection, whole-value functions, aggregation,
DISTINCT, ORDER BY, LIMIT, UNION and final serialization pipeline. Do not add a
projection-only fast path or push a limit into each source in this stage.

An OR union deduplicates **node identity within one graph**, not property values
or projected tuples. Merge selected ranges by canonical node-data byte offset,
not numeric ID, then preserve source encounter order. Equal node IDs in graphs
`a` and `b` remain distinct qualified nodes. Global DISTINCT/aggregation must
still merge all contributing provenance; an already-seen output tuple cannot
justify dropping later sources. Original row binding and rowOrders operations
must introduce the variable/provenance in their established order.

The safety argument is restricted to the admitted total, pure predicate:
nonmatching nodes cannot produce a WHERE error, and no rejected node would reach
projection. Moving a throwing or row-dependent operand violates that argument.
Calls evaluate **all** arguments eagerly; OR evaluates both sides here. Cases
9–13 below prove that a successful first arm and a non-null coalesce input do
not hide another operand's exception. Cases 14–15 prove that an empty graph
must not acquire a new exception from planning. Inline properties have their
own ordered, short-circuit evaluation before WHERE (cases 16–17), so even a
syntactically simple indexed WHERE cannot bypass them.

Mandatory matches after WITH/UNWIND, correlated inputs, already-bound variables,
OPTIONAL, anonymous nodes, multiple patterns, traversal, inline properties,
unsupported labels/functions/operands and incomplete ORs all fall back in this
first stage. Cases 18–22 include row-dependent parameters, nonnode bindings,
old node bindings and OPTIONAL's retention of an old binding. Later extensions
need separate proofs; they are not exceptions to these initial preconditions.

## Reader interface and failure boundary

An implementation should distinguish `available with zero IDs`, `unavailable`,
and an execution error. It may expose a lazy candidate iterator after validating
all data needed to avoid a late partial fallback. If a lazy range fails, buffer
before emission or restart without duplicating/reordering previously emitted
rows. Missing, unsupported, stale or rejected optional indexes must not become
an empty answer. Context cancellation propagates as cancellation, not fallback.
Cancellation checks are required during directory scans, posting union, and
node iteration; retained/mapped reader lifetime must outlive every iterator.

The companion [index audit](../callsite-index-readonly-audit/README.md) defines
v2 layout, typed-field CRC, identity trust, posting validation, transforms and
optional capability semantics. Trigrams are filters and require exact string
checks. Header inventory alone does not validate a full index. Main's sidecar
identity trust is not a cryptographic proof of all graph contents.

A further whole-engine parity boundary remains open: the current Go generic
walk decodes nodes before label checks, so skipping unrelated malformed core
records could hide a decode error. Pure WHERE analysis alone does not prove
error timing for corrupt core graph data. Index validation does not validate
all skipped node payloads. Before claiming full malformed-graph equivalence,
obtain a pinned-main corrupt-core oracle and define whether load-time validation
or the reference planner's own skip behavior governs that case. This contract
makes no new claim that corrupt graphs are out of scope.

## Unlabelled production shapes and Annotation counterexamples

Both principal string-query shapes in
[Attempt 5 config](../native64-string-predicate-attempt5/config.json) use
`MATCH (n)` with no label: the lower/coalesce four-field OR and the raw four-field
OR. **The label-only stage above cannot address either principal profile
bottleneck.** The supplemental Method count is a separate shape.

A subsequent pure-predicate type inference must reason about every node kind,
including AnnotationNode dynamic values. The four field names do not imply
CallSite. The two additional actual-main counterexamples use a graph containing
only AnnotationNode 101, with `values['caller_class']='Example'`:

```cypher
MATCH (n) WHERE n.caller_class CONTAINS 'Exam'
RETURN id(n) AS id, n.caller_class AS caller

MATCH (n) WHERE toLower(toString(coalesce(n.caller_class,''))) CONTAINS 'exam'
RETURN id(n) AS id, n.caller_class AS caller
```

Both return `{id:101, caller:'Example'}` in scoped main and one row per source
with correct a/b metadata in cross main. The complete four results and fixture
constructor are in `annotation-main-oracle.json` and `AnnotationOracle.java`.
This proves answer semantics on the tiny in-memory graph; it does not claim
which persisted capability was chosen. Main source independently lists
Annotation as eligible for these string properties and explicitly declines two
untyped indexed projection paths when any source contains Annotation.

For the unlabelled extension, a possible conservative candidate union is indexed
CallSites plus an ordinary Annotation scan, but only after a kind-by-kind proof
that all other kinds cannot make the **complete** predicate true. For wrappers
with an empty needle, null becomes empty and all 16 tiny node kinds match (case
26); even CallSite-plus-Annotation is then incomplete. Missing/numeric/complex
Annotation values and `toString` conversion must also be accounted for; this
contract does not certify that extension. Never infer no Annotations from the
absence of a matching CallSite index entry.

## Actual tiny oracle: 28 queries, 56 complete results

`cases.json` preserves exact queries and parameter values. Each runs scoped and
cross over sources `a`,`b`. `main-oracle.json` retains full columns, rows,
provenance, exception class and message. The mapped fixture has one CallSite,
id 24, `Example.run -> Example.callee`; empty cases use an actual empty graph.
`main-native-differences.json` retains both sides of every discrepancy.

| # | Case | Main scoped result; cross distinction |
| --- | --- | --- |
| 1 | Four properties, arbitrary alias, mixed OR modes | id 24 / name callee; two source-qualified rows |
| 2 | String parameter | id 24; two rows |
| 3–5 | Null, number, absent parameter | Empty in both modes |
| 6 | Nested lower/string/coalesce wrappers | callee; two rows |
| 7 | Typed wrapper, empty needle | id 24; two rows |
| 8 | Lower left with uppercase right | Empty |
| 9 | coalesce(P, 1/0) | Division by zero |
| 10 | toString(P, 1/0) | Division by zero |
| 11 | toLower(P, 1/0) | Division by zero |
| 12 | Successful OR arm then throwing arm | String-to-Number ClassCastException |
| 13 | Failing OR arm then throwing arm | Same ClassCastException |
| 14 | Empty graph, throwing right operand | Empty, no exception |
| 15 | Empty graph, throwing coalesce fallback | Empty, no exception |
| 16 | Inline property throws before rejecting WHERE | Division by zero |
| 17 | First inline property false, next throws | Empty, no exception |
| 18 | Right operand from UNWIND binding | Only term cal / id 24; two source rows |
| 19 | Existing binding is map, not node | Empty |
| 20 | Existing node binding | id 24; two rows |
| 21 | OPTIONAL no match | v null; cross metadata graphIds empty |
| 22 | OPTIONAL no match with old binding | Old id 24 retained; two rows |
| 23 | Overlapping OR, count | 1 scoped, 2 cross with both sources |
| 24 | Global DISTINCT / ORDER / LIMIT | One callee; cross metadata includes a and b |
| 25 | Throwing projection with LIMIT 0 | Empty, no exception |
| 26 | No label, empty coalesced needle | All 16 kinds; canonical mapped order |
| 27 | callee_signature outside index | id 24; two rows |
| 28 | Unsupported OR true arm | id 24; two rows despite indexed arm false |

Independent current-Go replay equals main for **52/56**. The two cases below
differ in each mode, hence four discrepancies, not four query shapes:

- Case 25: main skips `substring(v.callee_name,'bad')` under LIMIT 0; current Go
  throws ClassCastException. An optimizer cannot use existing Go projection
  behavior as proof of main parity. Fix this separately before index integration.
- Case 26: main mapped node order is
  `14,22,8,24,2,16,12,6,26,18,28,0,20,10,4,30`; current Go is numeric
  `0,2,...,30`. Both contain the same 16 IDs but order is part of the complete
  result. Canonical index order must not perpetuate the numeric-order difference.

The separate Annotation supplement adds four actual-main observations; it is
not included in the 56-case native denominator and is not a claimed Go replay.
There are 30 unique main query scenarios total. No failures were excluded.

## Source pointers and reproduction

Pinned main source under `graphite-cypher/src/main/kotlin/io/johnsonlee/graphite/cypher`:

- `QueryPipeline.kt:256`: DIRECT_STRING_NODE_PROPERTIES includes Annotation.
- `QueryPipeline.kt:1845,1907`: untyped indexed projection Annotation guards.
- `QueryPipeline.kt:2968`: DirectStringFilter; literal/parameter string extraction,
  coalesced-empty guard, exactly-one lower argument and exactly-two coalesce args.
  Main's recognizer does not include toString here. Wider native recognition
  requires semantic proof, not an assumption that main used the same path.
- `NodePropertyAccessor.kt:216`: arbitrary Annotation values exposed as properties.

Current Go: `internal/query/eval.go:291` eager arguments and `:327` operand
handling; `engine.go:179` labels and ordered inline properties; `traversal.go:121`
bound-variable handling and generic decoding; `properties.go:15,110` CallSite
and Annotation property values. `internal/cypher/ast.go` provides structural
MatchClause, NodePattern, Binary, Call, Parameter and Literal shapes.

Compile `PlannerOracle.java` and `AnnotationOracle.java` with Java 17 `javac -cp`
the pinned standalone jar into an external temporary classes directory. Copy
`graphite-server/internal/store/testdata/jvm-v3` to a temporary fixture directory
(the main helper may add an index there). `main-command.json` and
`annotation-command.json` record exact successful Java invocations and logs.
Do not point the helper at an original fixture or a running performance server.

For a fresh native comparison, copy the whole recorded Go module outside the
workspace, copy `native_probe_test.go` to that copy's `internal/query`, then run:

```sh
CANDIDATE_CONTRACT_CASES=/absolute/path/cases.json \
CANDIDATE_CONTRACT_OUTPUT=/absolute/path/new-native-observations.json \
go test ./internal/query -run '^TestExternalCandidateContract$' -count=1
```

The recorded native test passed as an observation capture, not as an assertion
that the 56 outputs match main. Compare complete JSON records by case name and
cross flag, preserving row order, columns, nulls, errors and metadata. Write new
receipts for future source changes; do not overwrite this frozen discrepancy set.
`verification.json` hashes the preserved evidence and inspected source files.
