# Per-case real64 latency sampling

The [three-state smoke archive](evidence/smoke-3b5ecae5-v1/README.md) preserves one
main/Go pair per state from engine `3b5ecae5`. Each runtime completed the original
1,267 cases: 1,266 successes and the same original error. All 3,801 state/case
rows remain in the summary. With only one sample per case, P95 and 10× acceptance
remain unproven; this smoke checks the measurement tools and evidence chain.

This tool preserves the pinned main workload: all 1,267 cases in original order,
including `four-or-graph-id-targeted`, on 64 real persisted graphs. The graph-file
manifest and workload hashes are fixed. It does not replace the full server
compatibility or required PR benchmark gates.

Each paired trial launches one fresh main process and one fresh Go process, each
on its own audited graph clone. Each process runs one complete ordered workload.
The default is 200 pairs per state, with runtime order alternating between pairs
and state order rotating. There is no discarded process/JIT warmup. Cold clears
indexes once before the entire workload; startup-prepared prepares indexes while
loading. These are not operating-system cold-cache measurements, and later cases
retain their original history within the workload.

The timer includes context creation, source selection, persistent worker dispatch,
parsing, execution, materialization and receipt of the result. Canonicalization,
validation and writing outputs are outside it. Main retains its original timer,
executor, sampler, work counters and benchmark methods. Go's timer is unchanged
from the dispatch-inclusive pilot. Builds and tests must finish before timing;
`run.py` holds a host-local measurement lock and runs its children serially.
Uncontrolled desktop activity is recorded in before/after process inventories.

## Warm diagnostic continuation

The original warm setup executes all 1,267 prewarm cases, then fails its original
all-success gate because case821 failed. Its formal measured invocation is not
prepared. The distinct state `warm-after-failed-prewarm` captures that entire
prewarm, actually invokes and retains the original failing gate, checks the
original signatures, then performs a separate diagnostic continuation through the
original reset/GC/sampler tail and measured benchmark method. The final original
gate still fails and processes still exit1. It must never be called a passing
original warm benchmark. Both prewarm ledgers remain separate from timed samples.

Native permits continuation only for the exact complete pinned workload's known
failure, never for missing cases, other errors, timeouts or row-count failures.
Its compact prewarm records are written and released before setup GC. Main
signature validation uses an explicitly named diagnostic reference that includes
the original failure; it is not a valid all-success correctness oracle.

## Running

First compile/audit the Java launcher with `python3 main-tooling/build.py` and run
`python3 run-tests.py` from this directory (the latter creates a new tests folder).
Then run the absolute-path scripts below from the repository root, using new
output directories:

```
python3 docs/go-server-baseline/native64-p95-sampling/prepare.py --output /absolute/new-build
python3 docs/go-server-baseline/native64-p95-sampling/run.py --build /absolute/new-build --output /absolute/new-campaign --pairs 200
python3 docs/go-server-baseline/native64-p95-sampling/summarize.py --controller /absolute/new-campaign/controller.json --output /absolute/new-summary.json
```

`prepare.py` snapshots the native module, hashes transitive build dependencies
including embedded data, freezes compiler/JVM/classpath inputs and verifies the
internal engine sources match the recorded commit. The controller also binds
`build.json`, so resume cannot silently switch executables or configuration.
Every runtime hashes the relocated graph manifest and all original fixture files
before/after use. Independent pair verification checks the complete original
case metadata, every canonical success signature and original failure, both
warm phases, source order, distinct physical paths and nonoverlapping intervals.
Generated graph clones are removed only after both children exit and the pair
passes verification. Failed or incomplete trials remain preserved and are never
overwritten or automatically resampled by resume.

The normalized schema has one JSON document per verified pair. The controller
lists each document's hash, state and verification flags. `summarize.py` retains
all cases in all three states, reports per-runtime samples, failures, censored
samples and distributions, and uses nearest-rank `ceil(0.95*n)`. Fewer than200
samples are explicitly insufficient; values from tool smoke checks are not P95
acceptance evidence. Timeout deadlines are censored values and never inserted
into the exact latency distribution. Failure times remain separately labelled.
No percentile is pooled across different cases or states.

## Acceptance remains open

The threshold for each successful case is `main P95 / Go P95 >= 10`. The current
controller sets `measurementAcceptanceEligible=false`: main's original failure,
Go's missing equivalent resource/work accounting, and remaining server fidelity
prevent overall acceptance even if individual thresholds pass. Main failed-query
messages in observer-free timing records are explicitly reference-derived; the
current original timer records the exception class, not its message. The summary
exits1 when acceptance is unproven, while still writing its complete report.
