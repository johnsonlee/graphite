# Full ordered real64 filtered-count CPU attribution

The baseline is native `9237131f`, exported into an isolated source directory.
Only its timing command is instrumented: CPU profiling brackets case index 838,
`filtered-count-zero`. All 1,267 original cases execute in original order on a
fresh clone of the real 64-graph, 1,152-file dataset. No certification work is
moved into setup. The original error at index 821 remains, with full replay
followed by exit 1 at the all-success gate.

Independent verification proves all 1,266 success signatures and the one known
error match the pinned original main reference. There are no new errors or
timeouts; all graph files and the isolated instrumented source/binary inputs are
unchanged. The controller is terminal with exit 0 after validating the expected
child exit 1 and generating pprof reports. This is profiling evidence, not a
per-case P95 or an uninstrumented latency sample.

The CPU profile contains 9.74 seconds of sampled CPU over an 11.92-second profile
interval. The following are cumulative shares; parent and child rows overlap.

| Path | Sampled CPU | Share of sampled CPU |
| --- | ---: | ---: |
| prepareIndexedNodePositions | 9.25 s | 94.97% |
| CertifyCallSiteCandidates | 5.16 s | 52.98% |
| CertifyCallSiteTrigrams | 4.03 s | 41.38% |
| CandidateNode (inside candidate certification) | 2.99 s | 30.70% |
| lowerTrigramHashes (inside trigram certification) | 2.51 s | 25.77% |

The measured MemStats interval allocated 2,184,529,752 bytes in 49,620,709
allocations; NumGC and PauseTotalNs deltas are both zero. That interval includes
profiler start/stop overhead and is not an independent allocation benchmark.
The evidence confirms that eager candidate and trigram certification dominate
this first filtered count. Main's specialized storage aggregation avoids that
generic full-node consumer, as audited in
`../native64-latency-pilot/next-count-path.md`.

`run1/source.patch` contains the only instrumentation change relative to the
archived baseline. The full instrumented command, source/binary hash manifest,
runtime/fixture receipts, CPU profile, raw memory counters and complete compact
query observations are retained. Initial line-level pprof output could not resolve
the isolated source path; `certificate-source.txt` and `trigrams-source.txt`
resolve it with an explicit source search root. The original outputs remain.
`verify.py` independently compares every result against the archived original
main canonical responses; `verification.json` records its evidence hashes.

The next optimization must reproduce main's filtered-string aggregation path,
including its admission, provenance, distinct-value handling and lazy/error
semantics. Removing the generic scanner's certificates indiscriminately would
change a different consumer and is not supported by this profile.
