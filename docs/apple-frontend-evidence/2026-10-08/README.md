# Apple frontend validation evidence

See [the report](../../apple-frontend-validation.md) for conclusions and measurement boundaries.
The directory date is UTC; local measurements continued into October 9 in Asia/Seoul.

These artifacts preserve successful and failed observations. A journal `ok=true`
means execution/result validation succeeded; it does not mean performance acceptance.
The formal main/candidate comparison did not pass the declared latency/CPU constraints.

- `apple-final-correctness-audit.json`, source/index/lowering comparisons and exact-type assertions document restored source coverage and signature corrections.
- `lifecycle-construction-final/`, `lifecycle-loading-final/` and `lifecycle-query-final/` contain the formal paired samples, recorded protocols and process logs. The analysis JSON files retain every sample and aggregation.
- `lifecycle-query-aa-diagnostic/` is a separate same-binary diagnosis. It cannot replace or be pooled with the formal A/B evidence. Competing work was observed during it; see `server-diagnostic-aa-contention.jsonl` and the early environment snapshot.
- `server-codegen-diagnosis.json` and its script inspect build fingerprints and object-code sections; `native-source-equivalence.json` binds the earlier native binary to the final production source state.
- `query-case-diagnosis/` retains the original unfiltered-join timeout. The later prefiltered query is a different workload, not recovery of that timeout.
- Release run and hosted calibration directories preserve job outcomes, raw frontend observations and preparation failures. Hosted Signal and local Signal use different documented commits/toolchains; their shapes and timings must not be mixed.

Original evidence bytes are retained, including absolute paths from the preparation
machine. Those paths are provenance, not portable download locations. Reproduction
requires preparing the pinned public upstream sources, index stores and graph inputs
using the commands/configuration documented in the report. Large source trees,
compiler index stores, persisted graphs and executable binaries are excluded here;
their hashes and upstream revisions are recorded.

The original formal server journals contain an incorrect live-CPU unit. Their values
remain unchanged. `server-analysis.json` derives corrected Mach-tick values using the
host timebase 125/3 and checks them against independent `wait4` CPU. The corrected
harness records raw ticks and the host timebase in the separate A/A journal. Wall
time, process peak RSS and construction/lifetime `wait4` CPU were unaffected.
