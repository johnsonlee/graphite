# Same-binary A/A diagnostic protocol (declared before execution)

This diagnosis tests process/run variability. It cannot replace or reverse the failed
original main-versus-candidate performance acceptance result.

Both variant labels launch the same candidate native binary at the same absolute path,
SHA256 `b089844defbf59bbad8670f156199f1fed07f63dd19321966d467b933b4cefa6`.
Its Rust sources are unchanged between the earlier local build and candidate357a6206.
Both consume the same final Signal iOS, SwiftPM and Signal Server graphs as the original
A/B. Record and verify graph fingerprints, binary digest and corrected harness digest.

Run the query operation only, with the existing four declared queries, exact graph
scopes, row order and response caps. Preserve three paired rounds (label order AB, BA,
AB), fresh server per label/round, five warmups per case, 100 measured requests per case
at concurrency1 then4, full response consumption and nearest-rank p50/p95. These are
six separate instances of the identical executable, not two different code versions.
Use the corrected Mach-tick CPU collector and retain raw counters and all samples,
readiness failures, warmups, parity checks and process resources in a new output
directory. Do not alter or pool this journal with the original A/B evidence.

The primary diagnostic is the absolute spread in the slow join's p50/p95 and its batch
CPU across all six processes, with each labeled pair and round shown separately.
Retain all other cases as controls and preserve lifetime peak RSS. Report the full
spread, including adverse runs; do not choose a favorable round or append adaptive
retries. The existing A/B failures remain failures regardless of this experiment.

Record relevant allocator/thread environment overrides, power/thermal snapshots and
background process CPU before/after execution. Avoid concurrent builds, tests or other
benchmarks. Do not change affinity, QoS, allocator configuration or server flags.

If same-binary variability is material relative to the original39ms/62ms join p95
changes, investigate execution environment and scheduling with new declared diagnostics.
That does not prove the candidate is equivalent to main. If this control is stable,
a separately declared reversed-label A/B can distinguish reproducible binary effects
from the original temporal order; it must retain the original evidence as well.

Prepared configuration: `server-diagnostic-aa-proposed.json`.
Planned new directory: `lifecycle-query-aa-diagnostic`.
Status: prepared only; no server or benchmark was launched by this diagnostic review.
