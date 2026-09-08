# Public source construction and empty graph-ID qualification

Actual main reference: `4e328b0109e13c896b74004823fb049fcb19251a`.
Its executable JAR SHA256 is
`91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d`.
This directory's oracle uses original public JVM APIs and cloned tiny persisted
correctness fixtures. It does not measure performance or replace engine classes.

`cases.json` and `main.json` contain 298 scenarios. `repeat-main.json.gz` contains
the same 298 cases run on fresh physical fixture copies. Complete public rows,
columns, top-level value classes, error classes/messages/causes/stacks, failure
phase and physical-store retained/mapped states are preserved. Public fields,
failure phases and source states are identical across the two runs. The verifier
does not compare error stack frame order, but both raw stacks remain archived.

The first 264 definitions remain byte-for-byte equivalent as JSON values to the
original corpus, in the same order. Original cases, main observations and fresh
repeat observations are retained as `initial264-*.gz`; the initial native failing
capture is owned by the implementation agent. The 34 appended cases cover only
empty-ID downstream identity and constructor/parse/cancellation precedence.

## Observed order of operations

The helper loads each distinct physical store, creates an actual cancellation
signal and context, creates each `CypherGraph`, constructs `CrossGraphCypherExecutor`,
then invokes its public execute method. A store may be reused by multiple distinct
graph IDs; close happens once per physical store. The `phase` records the operation
that returned a result or threw. Graph ID validation occurs during construction,
so no query parser or execution fallback can bypass it.

| Inputs | Actual failure/result and phase |
|---|---|
| Duplicate nonempty graph IDs | constructor: IllegalArgumentException, `Graph ids must be unique` |
| Two empty graph IDs | Same constructor failure |
| One empty graph ID | Accepted, including scoped=false and scoped=true |
| Empty graph ID plus distinct nonempty ID | Accepted; each remains its own namespace |
| Same Store under distinct IDs | Accepted; filtered count includes each source and distinct provenance |
| Empty sources | Accepted; RETURN produces explicit empty metadata; filtered COUNT returns Long zero |
| Null Graph | sources: NullPointerException, naming `CypherGraph.<init>, parameter graph` |
| Duplicate source list containing null Graph | Null Graph construction fails before executor uniqueness validation |
| Duplicate IDs with invalid query or pre-cancelled context | Constructor failure still wins |
| Unique IDs with invalid query and cancelled context | CypherParseException wins over cancellation |
| Unique IDs with overflowing Long literal and cancelled context | NumberFormatException wins over cancellation |
| Unique IDs, valid query and cancelled context | CypherQueryCancelledException |
| Unique IDs, timeout cancellation reason and valid query | CypherQueryTimeoutException with original 17ms message |
| Cancelled context and runtime unknown function/division by zero/bad substring argument | Cancellation wins over the runtime error |

The cancellation setup uses `new CypherCancellationSignal()`, `signal.cancel()`, or
`signal.cancel(new CypherQueryTimeoutException(17L))`, followed by the original
`CypherExecutionContext(new CypherExecutionBudget(Long.MAX_VALUE), signal)`.
It does not simulate cancellation through a thread interrupt or a fabricated
exception. Both default cancellation and an explicit timeout reason are observed.

These results follow the original sources: `CrossGraphValues.kt:26` has a nonnull
Graph parameter but no nonempty-ID condition; `QueryPipeline.kt:317` checks unique
source IDs in its initializer; `CypherExecutor.kt:493–504` constructs that pipeline
before execute; `CypherExecutor.execute` parses before creating/consulting the
execution tracker. The existing query's runtime cancellation still precedes its
runtime function or arithmetic evaluation.

## Empty string is a qualified namespace

`""` must not be used as an unqualified sentinel. Main distinguishes absent graph
qualification from an explicitly supplied empty graph ID:

* A matched node exposes graphId `""`, elementId `":2"`, qualifiedId `":2"`, and
  metadata `{"graphIds":[""]}`. Its complete materialized node map retains these
  properties. A literal RETURN has explicit `{"graphIds":[]}` instead.
* A Method's `graphId(m)`, `properties(m)`, `keys(m)` and materialized map preserve
  the empty graphId property. The existing all-types fixture supplies an actual
  Method (`Example.run(int)`) so this is a positive observation, not a zero-row check.
* `graphId(n)=''` and `n.graphId=''` select the empty namespace; the Method equivalent
  also matches. UNION retains provenance on the matching aggregate row and empty
  metadata on the literal row.
* A relationship and its containing path both expose graphId `""`; endpoint element
  IDs begin with `":"`. `graphId(path)` is valid in this original engine. Conversely
  `elementId(relationship)` returns null in these captures and is not invented.
* `elementId(n)=':0'` selects only the empty-ID graph when `['','g']` both contain
  local node0. Namespace identity survives relationship rebinding: two graphs with
  15 relationships each produce COUNT(DISTINCT r)=30 after reusing the bound r.
* Zero-hop paths in those two namespaces remain distinct. Their materialized nodes,
  graph IDs and metadata remain qualified despite having no relationships.

Typed native Edge parameter normalization is a separate existing parity concern.
This oracle does not add such a parameter or claim that issue is covered.

## Fixture provenance and preserved preparation failure

The helper copies original `candidate-index/clean`, `main-string-source/all-types`
and `traversal` fixtures, then calls the original `GraphStore.ensureNodeIndex` on
those disposable copies before mapped load. No original repository fixture is
opened by the JVM. There are 5,836 original files per final run; all remain unchanged
and none are removed. Main adds 152 node index/offset/type index files per run.
The complete before/after manifests and the paths of every generated file are
retained. This is correctness setup, not untimed preparation for a performance claim.

The first helper run omitted index preparation and observed 12 traversal load
failures. Those full original observations and case definitions remain in
`initial-fixture-precondition-main.json.gz` and `initial-cases.json.gz`. The helper
was corrected to invoke main's own setup API; the initial Method controls also
switched from a fixture with no Methods to the existing all-types fixture. No
engine failure from an admitted final query was discarded.

`oracle-inputs.json` hashes the pinned JAR, original constructor/qualification/
cancellation sources, helper source, preparation script and compiled helper.
`commands.json` records exact successful commands. Compilation, both final JVM
processes and the verifier exited0. The final captures contain 90 successful
queries and 208 intentional failures per run; a process exit0 indicates complete
capture, not that every query succeeded. `oracle-manifest.json` lists only the
oracle-owned artifacts; native tests and real64 captures have separate ownership.

Reproduce with a fresh disposable directory and Java17:

```sh
python3 docs/go-server-baseline/native-source-constructor/prepare.py /tmp/graphite-source-constructor-fresh
mkdir -p /tmp/graphite-source-constructor-fresh/classes
javac -cp /tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar -d /tmp/graphite-source-constructor-fresh/classes docs/go-server-baseline/native-source-constructor/SourceConstructorOracle.java
java -Xmx512m -cp /tmp/graphite-source-constructor-fresh/classes:/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar SourceConstructorOracle /tmp/graphite-source-constructor-fresh/fixtures /tmp/graphite-source-constructor-fresh/cases.json /tmp/graphite-source-constructor-fresh/main.json
python3 docs/go-server-baseline/native-source-constructor/verify.py
```

The last command verifies the archived original-JVM evidence. Native parity is
proved separately by executing the Go test against these complete observations.
