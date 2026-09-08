# Original-main cold repeatability diagnostic

`run-main-cold.py` is prepared for a later **serial correctness-only** run. It has
not been executed by the preparer. No builds, test suites, Java invocations,
fixture hashing, or graph replays were launched while the existing Go controls
were active. Only source inspection and a static Python AST parse were done.

After the parent confirms the active serial control is terminal, use a fresh
output directory and a unique safe suffix:

```sh
python3 docs/go-server-baseline/native-generic-string-disjunction/state-diagnosis/run-main-cold.py \
  --output /Users/johnsonlee/.codex/benchmarks/graphite/generic-string-main-cold-repeat-v1 \
  --suffix main-repeat-v1
```

The runner requires the exact original `MainReplayCapture` classpath recorded in
`native64-fullcase-replay/capture-classpath-complete.txt` and
`main-cold-complete-classpath-inputs.json`. It compares every original classpath
file hash, makes a private byte-verified copy of all classpath entries, and uses
that frozen classpath with the explicit Java 17.0.18 executable. It records and
checks all JDK files, rejects hidden JVM-option injection, and does not recompile
original classes or alter the original decoder, worker policy, timeout, or gate.

The immutable reference supplies only the clone operation. The JVM receives a
fresh private 64-graph clone after all 1,152 original files are audited. Graph
file resolution must stay within the clone. Only TSV field 2 is relocated;
other field bytes, source order, comments, and line endings are preserved.
Original graphs.tsv remains untouched. The clone and every capture are retained.

Original main executes all 1,267 cold cases. The expected original all-success
gate failure remains runtime exit **1**. That exit is recorded without discarding
results or stopping the comparison. No performance claim can use observer
latencies from this run.

The new raw response stream is compared against original
`main-cold-complete/responses.jsonl[.gz]` without sorting rows, filtering errors,
or waiving state counters. This includes header/prepared states, every case's
before/after states, all seven observed state fields, qualified/simple errors,
null messages, columns, rows, canonical results, case ordering, and captured
case definitions. Original main's correctness/observations manifests are checked
against all successful canonical responses and retained failures.

Outputs include:

- `main-cold/`: unmodified original capture, actual testcase definitions, and
  original main correctness/observation manifests.
- `comparison.json`: full coverage, error sequence, record/public differences,
  state difference counts, and explicit verification issues.
- `state-differences.json.gz`: every differing graph-state field with original
  and repeat values. No first-difference truncation or mappedRangeCount waiver.
- `public-differences.json` and `record-differences.json`: complete differences,
  including missing or extra records.
- `process.json` and `process.log`: actual JVM command/PID/status/exit and output.
- Classpath mappings, explicit input/JDK hashes, and fixture pre/post audits.

Controller exit **0** means this repeat exactly matched the frozen original
capture with expected runtime exit 1 and unchanged inputs/fixtures. It does not
mean the original all-success gate passed. Controller exit **1** preserves all
differences. Preparation failures produce `controller-error.json`; the runner
never deletes earlier output. Original-main repeatability remains unproven until
this runner is actually executed and its output inspected.
