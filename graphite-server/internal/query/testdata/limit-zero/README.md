# Main filtered literal zero-limit evaluation boundary

This is a functionality fix based on pinned main
`4e328b0109e13c896b74004823fb049fcb19251a`, Java 17.0.18. It is not a general
LIMIT pushdown or performance optimization. The independent worktree starts at
native `dfe6c4203fe39d997950c260d94a1df5445c94cd`.

`PlannerOracle.java` runs the unmodified main standalone jar against a disposable
copy of the tiny persisted `internal/store/testdata/jvm-v3` fixture. It records
all 90 scenarios twice: scoped and cross with graph IDs `a`, `b`. Empty cases
use a separate empty graph. Exact commands and complete rows/errors are retained;
no real-64 process, benchmark, fixture mutation or synthetic performance claim
is involved.

## Result and precise change

Before the fix, 110/180 complete observations equal main and 70 differ. After,
168/180 equal main and 12 differ: **58 discrepancies are fixed, none introduced**.
`before-differences.json` and `after-differences.json` preserve every discrepancy,
including complete error classes/messages and columns/rows. The denominator
always remains 180.

Main's QueryPipeline has explicit early empty returns in
`tryFastFilteredNodeLimit` (1292–1320) and
`tryStreamingFilteredMatchLimit` (3311–3340). The latter covers the former's
zero-result branch, and additionally admits anonymous nodes and relationship
patterns. Its guard requires exactly initial MATCH, WHERE, RETURN, optional
ORDER BY/SKIP, then literal LIMIT; mandatory one-pattern MATCH without a path
binding; no star projection or aggregation. It does not require a particular
label, WHERE purity, simple projection or absent inline node properties.

When the literal converts to a count <= 0, main returns static projection columns
and no rows **before evaluating MATCH, inline properties, WHERE, projection,
ORDER BY or SKIP**. DISTINCT does not prevent this. A negative literal SKIP
causes the streaming guard to decline; a nonliteral SKIP is treated as zero at
this guard, not evaluated. Thus even `SKIP 1/0 LIMIT 0` skips that error in an
otherwise eligible branch. Unary `-1` is not a Literal AST, so it is not the
same guard input as the literal string `'-1'`.

The native change adds a matching branch-level guard plus local literal count
conversion. General evaluator.count is unchanged. It keeps complete ordinary
execution for a parameter or arithmetic LIMIT, WITH/UNWIND prefixes, WITH
projection, OPTIONAL, multiple patterns, path binding, star, and aggregates.
Branches remain independently executed in UNION, so an error in another branch
is not swallowed. Cancellation is checked before this guard.

Selected discriminating captured outcomes:

| Query shape | Main behavior |
| --- | --- |
| MATCH + WHERE + throwing projection + literal LIMIT 0 | Empty; no projection error |
| Same with DISTINCT, ORDER, SKIP 1, SKIP 1/0 | Empty |
| WHERE 1/0, unknown function, or throwing inline node property in eligible shape | Empty |
| Same projection with parameter LIMIT 0 or arithmetic `1-1` | Projection ClassCastException |
| Same without WHERE, or with preceding WITH | Projection ClassCastException |
| Throwing aggregate input, OPTIONAL, bound path, multiple patterns | Ordinary error retained |
| RETURN 1/0 LIMIT 0 without MATCH | Division by zero |
| Eligible empty branch UNION RETURN 1/0 | Later branch Division by zero |
| literal `0.9`, `'0'`, null, false, overflowing integer string | Count converts to zero; empty |
| literal Unicode decimal `'١'` or positive fraction 1.9 | Positive count; projection error |
| unary `-0.0` or parameter null | General path, projection error |
| negative string literal SKIP `'-1'` | Guard declines; projection error |

The Java conversion helper already used by native strings is reused for Unicode
BMP decimal digits and overflow behavior. The local numeric helper clamps as
Java/Kotlin does; no expression or parameter is evaluated to qualify the guard.

## Explicit remaining independent discrepancy

The six `with-prefix-*` scenarios `skipneg`, `fraction`, `string`, `null`, `false`,
and `negative`, each in two modes, expose existing **general** SKIP/LIMIT count
conversion/error classification. Main accepts conversions that current Go
rejects, and uses IllegalArgumentException for a negative count. These 12 outputs
are preserved in full. They are not fixed in this commit at the parent's explicit
scope request. The regression test labels these 12 as skipped; it asserts all
168 other complete main records and separately checks cancellation. A passing
test suite therefore does not mean all 180 scenarios match or full parity is done.

## Reproduction

Compile PlannerOracle.java with Java 17 javac, using the pinned standalone jar as
classpath and a disposable output classes directory. Copy the tiny fixture to
a temporary location; main may create index sidecars there. The exact successful
invocation is in main-command.json. The jar SHA256 is in verification.json.

The main capture is independent of native source. To recapture native before or
after, copy native_capture_test.go from this directory into internal/query of an
external module copy, then set CANDIDATE_CONTRACT_CASES to the absolute cases.json
and CANDIDATE_CONTRACT_OUTPUT to a new output file; run:

```sh
go test ./internal/query -run '^TestExternalCandidateContract$' -count=1
```

The original before capture used a Go overlay replacing engine.go with the exact
base file (before-command.json records it); the new standalone guard was unused.
Both native observation arrays contain 180 complete records. Compare JSON values
without sorting row/column arrays or dropping nulls, metadata, or exceptions.
Use a new output directory for later revisions rather than overwriting this set.

Validation: `go test -race ./...` and `go vet ./...` from graphite-server; logs and
source/evidence hashes are retained in the freeze receipt. The production delta
is engine.go's guard call and new limit_zero.go only.
