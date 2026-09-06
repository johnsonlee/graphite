# General SKIP/LIMIT conversion and row-binding compatibility

This independent functional delta starts at `795e970b470f3359dfe09cec8b4363373c69079e`
(the prior filtered literal zero-limit fix). It reproduces pinned main
`4e328b0109e13c896b74004823fb049fcb19251a` using Java 17.0.18 and the unmodified
standalone jar. No root production file, real-64 runtime, performance workload
or original graph fixture was touched.

## Complete observations

160 tiny scenarios run scoped and cross (`a`, `b`): **320 complete observations**.
Before: 78 equal, 242 different. After conversion/row-binding changes: **312
equal, 8 different**. Thus 234 discrepancies are fixed and no matching case
regresses. Full before, after and main arrays and every discrepancy are retained.
The eight remaining cases are not removed from the denominator.

The previous LIMIT0 suite is also rerun: **180/180 equal**, including all 12
previously recorded general-count discrepancies. `previous-limit-zero-after.json`
is a new receipt; the original LIMIT0 capture and verification are unchanged.
The old test's 12 explicit skips are removed. The new count test asserts 312 full
records and identifies the eight remaining lazy-MATCH cases as skipped with a
specific reason. These are finite correctness results, not full-engine parity
or performance evidence.

## Reference behavior and production delta

Main `QueryPipeline.kt:540–545` computes an optional early MATCH limit; its
ordinary loop at 589–595 evaluates SKIP and LIMIT separately. Each expression
uses the **first current projected row**, or empty bindings if no rows remain.
SKIP uses the rows after projection, DISTINCT and ordering. LIMIT then uses the
first row remaining **after SKIP**, not the original row, source bindings, or
always-empty bindings.

Examples from the captured corpus:

- `UNWIND [2,1,0] AS x RETURN x LIMIT x` retains 2,1.
- Adding ORDER BY x makes the first row 0, so LIMIT x returns no rows.
- `UNWIND [2,1,0] AS x RETURN x SKIP 1 LIMIT x` retains only 1.
- `RETURN 1 AS x SKIP 9 LIMIT 1/0` still throws Division by zero, despite empty
  post-SKIP rows. Evaluation is not replaced by an early empty result.

`evaluateToInt` at 5390 and `Number.toCypherInt` at 5407 define conversion:

| Value | Converted count |
| --- | --- |
| Number | Truncate toward zero via long, then clamp to signed int32 |
| Numeric NaN | 0 |
| Numeric positive/negative Infinity | int32 maximum/minimum |
| String accepted by Kotlin toLongOrNull | Parsed signed long, clamped to int32 |
| Invalid/overflowing integer string | 0 |
| Null, Boolean, list, map, other nonnumber/nonstring | 0 |

String parsing permits an ASCII sign and Java-recognized BMP decimal digits;
whitespace, fractions, exponents and supplementary digits are not accepted.
For example `'٢'` means 2, `'1.9'` means 0, and integer-string overflow means 0
rather than saturating. Strings `'NaN'`/`'Infinity'` mean 0; the numeric results
of `toFloat('NaN')`/`toFloat('Infinity')` follow numeric rules instead.

Conversion itself permits negatives. Main List.drop/List.take then throw
`IllegalArgumentException: Requested element count N is less than zero.`
where N is the converted/clamped count. The native code now matches this class,
message, and evaluation position. A value -0.9 truncates to zero and is allowed;
-1.9 becomes -1 and raises the error. A prior projection, WHERE, grouping, sort
or SKIP exception is preserved where main follows ordinary clause execution.

Production changes:

- New count.go provides one pure shared value conversion, reusing the existing
  Java Unicode integer parser and Java numeric clamp.
- engine.go passes the proper first projected row to each general count
  evaluation and raises the reference negative-count error after conversion.
- limit_zero.go reuses the same pure conversion. Its eligibility, literal-only
  rule, and treatment of nonliteral SKIP are unchanged. It still does not
  evaluate arbitrary expressions or reject negative values during conversion.

No general predicate/projection reordering or traversal change is made here.

## Remaining independent mechanism: computeEarlyLimit

Four scenarios, each scoped/cross, still differ. Main's general pipeline may
pre-evaluate a LIMIT in empty bindings before any clause, when its first
mandatory one-pattern MATCH and intervening RETURN meet computeEarlyLimit's
restrictions. It also uses a positive result to stop **lazy pattern matching**.
In these cases a LIMIT Division by zero precedes a projection, inline-property,
WITH-prefix, or UNWIND-prefix String-to-Number ClassCastException. Native's
current materialized MATCH reports the later conversion error first.

`conversion-differences.json` preserves all eight complete expected/actual
records. Merely forcing an earlier exception, or evaluating then discarding a
positive count, would not reproduce the actual mechanism: candidate consumption,
relationship paths, later errors and repeated rand/timestamp evaluation could
change. Full computeEarlyLimit plus lazy traversal is therefore a separate
functional delta, explicitly authorized as follow-up. This commit does not
pretend its converter implements that planner or fix cases by query names.

## Reproduction and evidence

PlannerOracle.java and main-command.json capture the exact unmodified main
invocation. The fixture is a disposable copy of internal/store/testdata/jvm-v3;
main may create index sidecars only in that copy. Main observations include
columns, all ordered rows, nulls, source metadata, and error class/message.

To recapture native in an external module copy, copy native_capture_test.go from
this testdata directory into internal/query, set CANDIDATE_CONTRACT_CASES to
cases.json and CANDIDATE_CONTRACT_OUTPUT to a new file, and run:

```sh
go test ./internal/query -run '^TestExternalCandidateContract$' -count=1
```

The before snapshot is the exact base commit. The conversion-only output is
native-after-conversion.json. The previous suite uses its original cases.json.
Do not overwrite old evidence when testing future revisions. verification.json
hashes all new evidence; the external freeze contains commit/patch/source hashes
and full-module race/vet logs. No timing or speedup claim is made.
