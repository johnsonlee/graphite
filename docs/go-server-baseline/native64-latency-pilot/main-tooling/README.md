# Original-main diagnostic latency launcher

`MainLatencyPilot` calls the original pinned-main `setupTrial`,
`setupInvocation`, `replayBroadQueries`, and `tearDownTrial`. It does not replace
the query executor, install a per-query observer, or implement a second timer.
Latency comes from the original benchmark's observations TSV. Original metrics,
resource sampling, and correctness processing are retained. This is a direct
Cypher diagnostic pilot, with one observation per case, not per-case P95 evidence.

The launcher exports the actual 1,267-case definitions and 64-source order after
trial setup and before invocation setup. It checks the original executor's
identity again after replay. On the known original gate failure, the original
correctness and observation files remain available, `completion.json` records
the failing gate, and the process rethrows the original exception with exit 1.
There is no error allowlist or warm-gate bypass. Supported states are `cold` and
`startup-prepared`.

Build and bytecode verification (no graph load or query execution):

```sh
python3 docs/go-server-baseline/native64-latency-pilot/main-tooling/run-main.py --prepare-only
```

`build-verification.json`, `compile.log`, and `launcher-bytecode.txt` record the
successful compilation, equality of all 330 original classpath inputs to the
verified full replay, original lifecycle calls, and absence of executor mutation
or observer references. This compilation and static verification was completed;
no real graph runtime was launched by the tooling author.

One trial, dispatched only by the coordinating agent after all other benchmark
runtimes are terminal:

```sh
python3 docs/go-server-baseline/native64-latency-pilot/main-tooling/run-main.py --state cold --output docs/go-server-baseline/native64-latency-pilot/main-cold-run1
python3 docs/go-server-baseline/native64-latency-pilot/main-tooling/run-main.py --state startup-prepared --output docs/go-server-baseline/native64-latency-pilot/main-startup-prepared-run1
```

Run these serially. Each output and external fixture directory must be new.
The runner clones the immutable real64 corpus into a new external trial and
checks all 1,152 original files before and after execution, including unexpected
graph-local files. It preserves the original six-column manifest and source
order while relocating only graph paths. It freezes the actual Java launcher,
runtime modules/JVM library, all classpath files, main Kotlin sources, launcher
sources, workload and fixture manifests, and verifies their hashes at exit.
Do not rebuild or edit these inputs during a live run.

Each output directory contains `process.json`, `process.log`, `inputs.json`,
reference and clone preflight audits, a post-run fixture audit, and `capture/`:

- `header.json`: source order, timing boundary, runtime, sample count and state.
- `actual-cases.json`: actual original workload definitions.
- `main-correctness.tsv`: untouched original correctness manifest.
- `main-observations.tsv`: untouched original per-case observations and latency.
- `completion.json`: original gate/exception, executor identity, written outputs.

A complete capture with process exit 1 is still a failed original correctness
gate. Check all 1,267 identities, outcomes, signatures, process and fixture/input
audits independently before using any successful-case latency diagnostically.
The known error case must remain visible as time to failure, never successful
latency. Existing mixed-case `p95LatencyNanos` is not per-case repeated P95.
