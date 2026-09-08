# Mapped-entry Go baseline capture

The frozen baseline matches **2 of10** actual-main controls on all available compared fields (E05 request-signal cancellation and E06 budget exhaustion). Its other8 controls remain failures, with70 leaf/phase-list differences. The noninstrumented7-case run retains54 differences; all seven corresponding cases have exactly the same actual Go behavior in the instrumented run after excluding explicitly observer-only fields. These figures describe storage-method correctness, not public Cypher or performance acceptance.

The baseline comes exclusively from `/Users/johnsonlee/.codex/benchmarks/graphite/persisted-work-f0838dda-real64-source-v3`:2568 existing module files, byte-preserved. Three separate adapter test files are injected into its copied module; the resulting2571-file source archive is preserved for each attempt. The changing workspace production files were not used. Tests are opt-in (`GRAPHITE_MAPPED_ENTRY_OUTPUT`) and do not claim automatic coverage when ordinary module checks skip this artifact runner.

The adapter calls the production `mainCandidateIterator` directly and uses its returned complete storage nodes. It does not call Execute, ordinaryRows or RETURN expression evaluation. A lookahead adapter's hasNext performs one actual candidate/node-decoding pull and buffers the resulting Node; next returns that buffered Node without a second pull. Iterator-construction is explicitly adapter initialization, not a claimed native Go Sequence API. Existing extra e.check and decoder context polls are retained as observable production behavior.

Worker cancellation uses context.WithCancel. Request cancellation uses the actual NewExecutionContext/Cancel, independently, without bind. The instrumented run intercepts **actual ExecutionContext.consume invocations** before/after their original body, records the units and actual remaining work, and performs the declared cancellation action only after the first successful real callback. No work units or expected failures are synthesized. The original function body remains byte-for-byte intact; removing only the recorded observer insertion and declaration exactly restores the original production file. The same original panic instance is rethrown. This is explicitly an instrumented frozen-copy capture, not execution of identical production bytes.

`-overlay` supplies that single temporary file; the only other additions are adapter tests. V1's plain7 run completed with the real baseline failures. Its instrumented command was blocked by Go1.22's implicit vet resolving the tagged test observer symbol against the unmodified source; that failed attempt, source archive, command and full log remain in `failed-v1`. V2 disables implicit vet **only for the instrumented artifact command**; it compiles and executes with `-race`. The ordinary plain command retains vet. This does not replace or weaken the production module's separate vet gate.

## Observed baseline results

| Case | Main work | Go work | Baseline result |
| --- | ---: | ---: | --- |
| E01 cold entry interrupted | 1 | 0 | Too-early construction cancellation; wrong raw error message. |
| E02 warm view absent entry interrupted | 1 | 0 | Go fails construction; main succeeds through empty iteration. |
| E03 warm view hit entry interrupted | 9 | 0 | Too-early construction cancellation; wrong raw error message. |
| E04 callback interrupts warm view hit | 9 | 9 | Construction boundary, callbacks[2,7] and cache agree; actual error message differs. |
| E05 callback cancels request signal | 2 | 2 | All compared fields match, including same original cancellation reason. |
| E06 budget1 | 1 | 1 | All compared fields match, including original callback failure and remaining0. |
| E07 retained cached empty entry interrupted | 0 | 0 | Go fails construction; main succeeds with an empty sequence. |
| E08 retained cached hit entry interrupted | 1 | 0 | Go fails construction; main returns node74 and EOF. |
| E09 validated range entry interrupted | 9 | 0 | Go fails construction; main constructs then fails first hasNext, cache1 retained. |
| E10 callback interrupts validated range | 9 | 9 | Construction/first-hasNext boundary, callbacks[2,7] and cache agree; actual error message differs. |

All warmups match their actual-main comparable values. Original Go cancellation errors remain recorded as nativeType `*errors.errorString`, message `context canceled`. The comparator also assigns them the cross-language semantic class CancellationException, but **does not replace the native type or message with JVM values or count message differences as passes**. Main records `java.util.concurrent.CancellationException`, message `Mapped CallSite string index view interrupted`; E04 and E10 therefore still fail, each with two message observations (lookup and failing step). `baseline-summary.json` puts both raw error identities/messages and every case's actual phase sequence side by side; the complete native error objects and all70 differences remain in `baseline-v2/instrumented.json`.

## Compared and unavailable observations

Every available logical phase compares outcome, error simple class/message, callback/signal cause identity, node ID/kind, all eight actual request diagnostics, remaining budget, independent worker/signal cancellation state, and12 mapped/retained cache/initialization fields. Every real callback compares phase, order, units, before/after diagnostics/state and its original failure. Missing or extra phases are retained as differences, not silently omitted.

The three JVM lookup counters (parallelScanCount, lookupEntryCount, indexLookupCount) have no corresponding Go observer and remain explicitly unavailable, not fabricated. JVM-qualified class/stack and full Java object structure remain raw evidence; native type/full store.Node are recorded separately, with only node ID/kind treated as comparable shape. Main's private afterClose state remains raw; Go's public state getter returns ErrStoreClosed, so private post-close fields are not asserted equivalent. The fixture's17 actual-writer files and both frozen JVM groups are reused unchanged.

The observer-equivalence comparison excludes only actual callback events/their callback-failure identity (not observable without instrumentation), temporary fixture directory paths, and derived expected-result differences. It keeps result, all actual diagnostics, cache/state, raw error fields and every phase. All seven no-action cases are equal. `observer-comparison.json`, `instrumentation.json`, source/adapter/oracle manifests and `verify.py` preserve that evidence.

Both V2 test commands and its controller are terminal exit1 because actual-main differences remain; these are not reported as passing tests. V2 plain PID36522 and instrumented PID36577 have ended. Verification of archival bytes and observer equivalence passes separately. No JVM or performance benchmark was run for this adapter, and the fixed-worker/64-graph references were not changed.
