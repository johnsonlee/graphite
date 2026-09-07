# Lazy main string candidates: correctness fixtures

Pinned main is `4e328b0109e13c896b74004823fb049fcb19251a`. Its runnable JAR SHA256 is
`91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d`.
These are small correctness fixtures, not performance workloads.

`CandidateSourceOracle.java` invokes actual `CypherExecutor` or
`CrossGraphCypherExecutor`, recording complete rows/errors and retained/mapped
index state before and after each request. The 41 original cases, 11 concrete
property cases and six offset cases contain 58 scenarios, 116 target responses
and six warm-up responses. No query names are used by the production planner.
The all-types fixture is the existing JVM v3 fixture plus the node index produced
by `GraphStore.ensureNodeIndex`; its original node/string/metadata bytes are kept.

Reproduce from the repository root with Java17. Set `MAIN_JAR` to the pinned JAR
and `ORACLE_OUT` to a fresh disposable directory. The downloaded JAR is only the
existing pinned oracle; no archive from a different release is substituted.

```sh
mkdir -p "$ORACLE_OUT/classes"
python3 graphite-server/internal/query/testdata/main-string-source/prepare-fixtures.py "$ORACLE_OUT/fixtures"
javac -cp "$MAIN_JAR" -d "$ORACLE_OUT/classes" graphite-server/internal/query/testdata/ordinary-projection/HistoryOracle.java graphite-server/internal/query/testdata/main-string-source/CandidateSourceOracle.java
java -Xmx256m -cp "$ORACLE_OUT/classes:$MAIN_JAR" CandidateSourceOracle "$ORACLE_OUT/fixtures" graphite-server/internal/query/testdata/main-string-source/cases.json "$ORACLE_OUT/main.json"
java -Xmx256m -cp "$ORACLE_OUT/classes:$MAIN_JAR" CandidateSourceOracle "$ORACLE_OUT/fixtures" graphite-server/internal/query/testdata/main-string-source/property-cases.json "$ORACLE_OUT/property-main.json"
java -Xmx256m -cp "$ORACLE_OUT/classes:$MAIN_JAR" CandidateSourceOracle "$ORACLE_OUT/fixtures" graphite-server/internal/query/testdata/main-string-source/offset-cases.json "$ORACLE_OUT/offset-main.json"
```

Run `go test -race ./internal/query ./internal/store` from `graphite-server`.
`TestMainStringSource*` checks complete response fields against these oracles.
Full state observations remain in the JSON: cancelled speculative 40-source
tasks can publish different numbers of completed mapped views, including between
two main runs. We do not normalize those observations or claim their counts are
deterministic. Additional tests check natural exhaustion, owned values, typed
Java equality, source-wave cancellation and Close, and main's cold-versus-warm
posting-range consumption using a real cancellable context.

The offset oracle is specifically representation-sensitive: an unselected bad
ID90 offset fails in the one/two-source paths, but succeeds in main's 40-source
mapped-view path. A selected bad ID2 offset still fails. This prevents replacing
lazy main candidates with A6's stricter all-node certificate or validating all
posting offsets before choosing a range.

The new source is reusable; this change only adds the generic DISTINCT consumer
without SKIP/ORDER. It does not implement streaming pagination, relationship
candidates, global JVM index-memory reservations, configurable JVM worker/budget
settings, or eager serial-storage preference inference. Existing fallbacks and
the A6/A7 certificate remain separate. The source's fixed range cache matches
main's direct-mapped keys and admission-after-complete-validation semantics;
Go does not yet share main's process-wide reservation budget.
