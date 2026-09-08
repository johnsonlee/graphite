# Real64 dispatch-inclusive latency pilot

This pilot uses all 1,267 cases from main revision
`4e328b0109e13c896b74004823fb049fcb19251a` on the immutable 64-graph,
1,152-file real persisted corpus. Query text, parameters, order, scope, configured
timeouts and result constraints remain unchanged. Each runtime/state has one
complete ordered invocation in a fresh process and a separately cloned corpus.
These are single observations, not repeated per-case P95 or acceptance evidence.

## Verified pilot results

All four timed processes are terminal. Cold and startup-prepared each preserve
1,267 exact testcase definitions, 1,266 canonical success signatures and the
same original error, with no new errors or timeouts. All 1,152 graph files are
unchanged in every runtime; the input and executable hashes are unchanged.
The two Go runs use the identical binary
`2ad9652b7814de4833a96f67dde064af266b5fbda452ce7b9eb06ababffe68cf`.
The query engine is the unchanged `81a19b54` candidate plus the new timing command;
the timing command was uncommitted during capture and is identified by the exact
frozen source hashes, rather than attributed to that pre-existing commit.

| State | Successful cases where Go was slower | Successful cases with observed main/Go ratio >=10 | Median of per-case single-sample ratios |
| --- | ---: | ---: | ---: |
| Cold | 1,183 / 1,266 | 0 / 1,266 | 0.1197 |
| Startup-prepared | 1,187 / 1,266 | 66 / 1,266 | 0.1100 |

Each ratio is one main duration divided by one Go duration for the same case.
The median is across different case ratios and is explicitly **not** P95 or a
pooled latency acceptance metric. The pilot shows substantial performance gaps;
it cannot establish the requested per-case 10x P95 improvement.

| Case | Cold main ms | Cold Go ms | Startup main ms | Startup Go ms |
| --- | ---: | ---: | ---: | ---: |
| filtered-count-zero | 1.564 | 11,055.763 | 1.564 | 11,150.093 |
| filtered-count-targeted | 24.882 | 1,958.953 | 21.255 | 1,926.774 |
| filtered-distinct-count-targeted | 30.581 | 3,567.049 | 29.566 | 3,567.600 |
| global-six-or-wrapped-dense | 60.479 | 3,416.966 | 63.121 | 3,331.214 |
| regex-or-zero | 14,298.176 | 24,444.923 | 14,250.663 | 24,465.607 |

`cold-comparison.tsv` and `startup-prepared-comparison.tsv` contain every case,
including the failure. Their JSON reports include exact verification coverage,
input/evidence hashes, the error-message provenance, and measurement limits.
The run order was main cold, Go cold, Go startup-prepared, main startup-prepared.
`environment.json` records hardware and process/warmup policy.
`next-count-path.md` records the source-backed next hypothesis; no query-engine
optimization was made as part of this measurement change.

The new driver passed full-module `go test -race -count=1 ./...` and `go vet ./...`.
The independent comparator passed 18 deliberate data-corruption checks. Initial
test setup and launcher preflight failures remain archived in `test-receipts.json`
and `main-cold-run1/prelaunch-failure.json`.

`native-build-provenance-run3/verification.json` additionally proves that a forced
`go build -a` from 90 dependency packages and 1,008 pre/post hashed inputs produces
a byte-identical executable to both measured Go binaries. This includes all three
embedded Java17 regular-expression data files and the auditor itself. Its
286-file project source snapshot is verified after archive extraction. This is
retrospective reproducible-build evidence: the original timing runner did not
record separate historical pre/post hashes for embedded data. The first build
audit rejected Go's randomized temporary `GOGCCFLAGS` path; the second completed
but was superseded when the auditor added its own source to the frozen inputs.
Both earlier records are retained; run3 is the authoritative final audit.

## Measurement protocol

Main runs its original benchmark with a thin launcher. The executor and original
per-case clock remain untouched. The separate Go command starts its clock before
request context/source selection, dispatches to one persistent worker, and stops
after receiving the complete result/error. Parsing and result materialization are
inside the interval; canonicalization, digesting, validation and output are
outside it. Native collects compact records during the invocation and writes
JSONL afterward. The original correctness observer's old timings are not reused.

Cold clears indexes once before the full ordered workload, not before every
case. Startup-prepared builds/loads indexes while loading graphs. Both request
three garbage collections and 100 ms setup pauses. Loading, setup, HTTP and
response serialization are excluded from the query timer. Operating-system page
cache is not flushed. Main retains its original resource sampler and metrics;
Go work metrics and finite core-budget parity remain unfinished. A diagnostic
comparison does not establish full server or measurement-instrumentation parity.

All benchmark runtimes run serially on the same host. No builds or tests should
run concurrently with a timed invocation. The pilot is one fresh process per
runtime/state with no discarded process warmup, so JIT/runtime initialization
may affect cases according to their original positions. A later P95 experiment
must explicitly freeze fork/warmup policy and balance runtime order across
repetitions; it must repeat whole ordered workloads rather than repeat each
case in place. Require at least 200 samples per runtime/state/case, report
failures/timeouts separately, and use nearest-rank `ceil(0.95*n)-1`.
The objective remains `main P95 / Go P95 >= 10` for every successful case.

The original main case `four-or-graph-id-targeted` fails with
`IllegalStateException: Unsafe expression reached parallel string projection`.
It stays in the case list and outcome ledger. A complete diagnostic capture
still exits 1 at the original all-success gate; error latency is time to failure,
not successful-query acceleration. The unchanged formal warm protocol stops at
this error during prewarm, so no formal warm timing is claimed. Fixing the
reference bug requires an explicitly versioned main reference and full oracle
regeneration; this pilot does not change that reference or bypass its gate.

The first cold launch attempt (`main-cold-run1`) found an incorrect metadata
path assumption before starting a JVM. Both graph audits passed, but the runner
also required a nonexistent `provenance.tsv`. Its failure record is retained;
the corrected runner freezes the actual graph-file manifest and `graphs.tsv`.
`main-cold-run2` is the first actual main cold invocation.

Each actual run preserves command, versions/options, source/classpath/binary
hashes, timestamps, terminal status, and reference/clone/post-run graph audits.
`verify-pilot.py` checks complete ordered coverage, original result signatures,
the sole known failure, immutable inputs, and unchanged graph files before
writing per-case comparisons. Fresh main records the failed exception class;
its per-query message is available only from the original full correctness
capture, and must be identified as reference-derived.

See `main-tooling/README.md` for main launch commands and `run-native.py --help`
for native runs. Use new output directories, and never rebuild/edit frozen
inputs while a runtime is live.
