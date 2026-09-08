# Independent pinned-main filtered aggregation oracle

Reference main is `4e328b0109e13c896b74004823fb049fcb19251a`; the existing executable
JAR SHA256 is `91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d`.
All executions here are synthetic correctness observations. No durations or
performance conclusions are derived from them.

`main.json` contains 194 scenarios, each executed twice with a fresh request context
and unlimited work budget, on independent physical source copies. The two executions
within one scenario share graph state, and capture retained/mapped state before
and after. Public columns, complete rows (including metadata), scalar JVM types,
exception type/message and full error stacks are preserved. There are 338 successful
responses and 50 failures; every repeated public response is identical. Failures
are evidence of the original behavior, not removed or converted to successes.

Coverage includes all five supported concrete types and generic Node, single/two/64
sources, null and missing counted properties, numeric/nonproperty counted expressions,
global DISTINCT, residual WHERE, graph constraints, parameters, string operators,
IN, exists, lowercase and regex, plus malformed persisted records/offsets and missing
indexes. It also includes excluded query shapes to protect planner fallbacks.
The 27 added routing scenarios include CrossGraph with one/two/64 sources and both
SourceScopeApplied values, equality/IN/conflicting graph constraints and residual
numeric conditions. Three unqualified single-graph graphId guards all return count0
without metadata. These use the public main execution constructors, preserving the
distinction between constructor scope and query-internal source pruning.
Four further cases verify the public provenance array's Java UTF-16 sorting, using
nonordered input graph IDs and U+E000/U+10000/emoji boundaries. COUNT(*), a missing
counted property yielding zero, DISTINCT string count and a counted expression all
produce `[a, android, hive, tika, z, U+10000, emoji, U+E000]`. This differs from both
source order and Unicode codepoint/UTF-8 order. The original main adapter explicitly
sorts the internal provenance set before exposing public metadata.
This matrix does not cover the full contract: preflight threshold edges, global
memory denial, all malformed sidecar variants and finite budget/cancellation remain
additional work. `contract.md` identifies their expected source behavior.

Three findings corrected assumptions made during implementation:

* Exact public malformed traversal query `MATCH (n:NoSuchLabel) WHERE true RETURN n`
  returns zero rows. On the same original malformed bytes, `WHERE false RETURN n`
  and `WHERE 1/0=0 RETURN n` throw `IllegalArgumentException: Unknown node tag: -1`.
  Main's root unknown-label routing bypasses the local filtered-count zero branch.
  An existing Go test expecting all three to fail conflicts with the actual JVM.
* Duplicate graph IDs are rejected by CrossGraphCypherExecutor construction with
  `IllegalArgumentException: Graph ids must be unique`. The executor's uniqueness
  validation must be distinguished from its internal aggregation provenance set.
* Annotation queries matching `name='Audit'` count null `caller_name` as zero but
  retain both matching graph IDs. A separate annotation field `callee_name=12`
  counts as a nonnull numeric value and deduplicates globally across graphs.

`posting-main.json` adds nine direct observations of main's actual
`MappedCallSiteStringIndex.PostingRanges.aggregate`. Controlled raw-property callback
reads and work batch receipts prove the sparse DISTINCT boundary: three unique
matches and counted-property postingCount=11 use directory traversal; postingCount
12 or13 use ascending raw node IDs `[1,3,65]`. Injected raw read failures appear only
in the sparse branch. Duplicate predicate postings are charged but count once.
Negative/out-of-capacity posting IDs produce actual JVM bitset array exceptions,
including the unsigned-shift index in each error message. These primitives exercise
the real JVM implementation; they do not replace a public end-to-end malformed
DISTINCT fixture at the same boundary.

All 21,561 original fixture files remained byte-identical after the extended public
run and error-scheduling repeats. Main added 280 files: node offsets/type indexes for fixtures
without them and four reconstructed CallSite indexes. Both complete manifests are
retained as deterministic gzip JSON, and `fixture-audit.json` names every addition.
The original repository fixtures were never opened by the JVM; only cloned copies
under the recorded disposable root were used. Go comparisons should reconstruct
from the case specifications, not reuse post-main clone files with added indexes.

`error-scheduling-main.json` captures a bounded 200 fresh graph-load trials (four
original offset-corruption scenarios, 50 trials each, two requests per trial).
These runs use the default main scheduler with no thread gates, executor replacement,
contention injection or worker override. All 400 public errors are the same
IndexOutOfBoundsException with null message. The failing source g00 remains unretained
and unmapped; g01 remains unmapped but its retained flag is either false or true:
suffixes0/1/2/7 respectively observe false9/10/9/6 times and true91/90/91/94 times.
This independently proves the one source-state coordinate is nondeterministic when
the sibling query fails. It does not authorize ignoring other failed-query state
differences. The verifier checks every invariant and both observed values for every
scenario; the raw before/after states remain intact.

`store/string_aggregation_test.go` compares the native primitive with the nine JVM
observations using an existing tiny persisted fixture and controlled CSR/raw-field
inputs. It verifies the 11/12/13 boundary, OR deduplication, invalid posting error
messages, sparse selected-SID failure versus dense success, unused malformed raw
data for COUNT(*), cancellation during selected-property consumption, a fresh request
after cancellation, and closed-store behavior. The Go API has no raw-reader callback,
so it cannot directly assert the JVM callback-read list/work batches; the tests use
controlled bytes to distinguish which branch consumes the raw property instead.
The deliberately injected JVM callback exception and a Go invalid-SID exception are
different fault mechanisms, and are not claimed to have identical public messages.
The targeted store tests and race run pass; their first fixture offset error was
corrected and recorded in the receipt.

Reproduce from the repository root using a fresh destination and Java17. The helper
compiles only against the original main JAR; it does not replace any main classes.

```sh
python3 docs/go-server-baseline/native-filtered-string-aggregation/prepare-oracle.py /tmp/graphite-count-oracle-fresh
mkdir -p /tmp/graphite-count-oracle-fresh/classes
javac -cp /tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar -d /tmp/graphite-count-oracle-fresh/classes docs/go-server-baseline/native-filtered-string-aggregation/FilteredCountOracle.java docs/go-server-baseline/native-filtered-string-aggregation/PostingAggregateOracle.java
java -Xmx512m -cp /tmp/graphite-count-oracle-fresh/classes:/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar FilteredCountOracle /tmp/graphite-count-oracle-fresh/fixtures /tmp/graphite-count-oracle-fresh/cases.json /tmp/graphite-count-oracle-fresh/main.json
java -Xmx256m -cp /tmp/graphite-count-oracle-fresh/classes:/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar PostingAggregateOracle /tmp/graphite-count-oracle-fresh/fixtures/expression-000/g00 /tmp/graphite-count-oracle-fresh/posting-main.json
python3 docs/go-server-baseline/native-filtered-string-aggregation/verify-oracle.py
```

The last command verifies the checked-in captures, including query definitions,
types, repeat responses, important boundary observations and unchanged source files.
It does not claim the native implementation passes; its independent comparison is
the Go execution test. The initial primitive helper compile failure and successful
corrected compile are recorded in `oracle-receipt.json`; no failed engine result
was suppressed. `oracle-inputs.json` hashes the main sources, JAR and helper classes.
