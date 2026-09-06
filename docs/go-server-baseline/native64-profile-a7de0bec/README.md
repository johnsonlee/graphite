# Post-load native real64 profiles at a7de0bec

This is an independent diagnostic harness, not an HTTP benchmark, P95 observation, optimization comparison, or speedup claim. Production source is unchanged. The parent ran a separate tiny HTTP correctness oracle on the same host, alongside normal desktop/background services; absolute durations are not used for acceptance.

## Frozen inputs and commands

- Source revision: `a7de0bec67ae4dbd8b1dde621b6d9ce7cecef8d0`.
- The exact `graphite-server` source and `CONVENTIONS.md` were archived before concurrent agents edited the working tree. Archive SHA-256: `1b904f9195ccee303689a358301e22e77f3615c648ce42be985a41d483754f83`.
- An independent `cmd/profile-query` was added only to that temporary source copy. Its complete source is [profile-query.go](profile-query.go); the harness SHA and build command are in [identity.json](identity.json).
- Profile binary SHA-256: `9eeff747f43b385b7cc7a952501ab260fec21e6b3c0570f5ab9f9680b244f952`. Binary and original source archive remain at the absolute paths recorded in `identity.json`.
- Original fixture: `/tmp/pr113-exp037-fixture.nXn4fg`. Original graphs TSV and provenance hashes were checked against the frozen 42-case workload manifest before loading. All 1,152 persisted files, totaling 10,338,207,518 bytes, have recorded SHA-256 identities in [fixture-files.json](fixture-files.json).
- Exact queries, graph paths and oracle responses: [config.json](config.json). Build and execution commands: `identity.json`, [run-command.json](run-command.json). Every offline pprof command: [pprof-commands.json](pprof-commands.json).

Both original workload queries came from the fixed 42-case manifest. That manifest contains no Method/aggregate shape; `MATCH (m:Method) RETURN count(m) AS count` is explicitly supplemental and is not presented as one of the 42.

## Scope and validation

The harness loaded all 64 real graphs in MAPPED mode and validated every graph's node, edge, method and CallSite counts against the saved main catalog before starting profiling. Totals matched: 19,431,891 nodes; 20,448,885 edges; 1,374,983 methods; 5,046,935 CallSites. See [catalog.json](profiles/catalog.json).

The three queries ran sequentially in one process, one request at a time. CPU recording starts after loading/catalog verification and two forced GCs. It covers `ExecuteCross` and marshaling the complete root response envelope, with phase labels. No HTTP dispatch, guard queue, socket, or client overhead is included. The caller row limit is 1,000, matching the root endpoint default. There is one profiled execution of each shape and no warmup/percentile claim.

Heap sampling uses Go's default 512 KiB allocation sampling. `alloc_space`/`alloc_objects` subtract the before-request profile to exclude graph-loading allocations. Two forced GCs before each baseline and after each request allow complete allocation-profile accounting; these GCs are outside the CPU recording and runtime request deltas. The profile difference still includes profiler bookkeeping/post-request instrumentation, so sampled totals are estimates; exact runtime `TotalAlloc` deltas are recorded separately. `inuse_space` after forced GC represents the retained store plus the tiny response. MemStats/Rusage snapshots are preserved in each receipt. Heap-at-request-end is not a peak, and Darwin Rusage Maxrss is process-cumulative, including prior requests/loading.

All complete output objects matched expected data after canonical JSON marshaling, including columns, rows, counts, graph count and provenance. The two original queries matched the saved main preflight response objects, not merely empty row counts. The supplemental query returned 1,374,983 and all 64 graph IDs. This does not claim equality of HTTP wire whitespace.

| Shape | Exact request TotalAlloc bytes | Request mallocs | Request GC cycles | Complete output SHA-256 |
| --- | ---: | ---: | ---: | --- |
| wrapped-zeroHitBroadContains | 38,142,226,112 | 674,142,874 | 3 | `b776d360f1d6eb34c3a0594d0d8239821bbe4f96958a90907764d5a48a6aa1dd` |
| global-wide-four-properties-zero | 32,796,831,280 | 404,267,568 | 3 | `f585f8c837c05a598d43e16848760d2ef6a76ea7dc015ca1ffb187dece3a5e03` |
| supplemental-method-count | 2,441,487,384 | 13,752,883 | 0 | `21daca2ccd004eb51e41c0e51c8d4bad939861279acfa168c4f65b2c269e8a2b` |

The two zero-hit requests ended with approximately 36.50 GB and 31.32 GB HeapAlloc. After forced GC, each returned to approximately 6.27 GB. Those are observed end states, not sampled peaks. Full receipts, CPU/heap profiles, allocation-object tables, and complete output files are under each `profiles/<shape>/` directory.

## Attribution from these samples

For wrapped/raw zero-hit respectively, `runtime.gcDrain` accounts for 66.22%/61.82% cumulative CPU samples; `runtime.scanobject` accounts for 59.29%/54.44%. These overlapping cumulative shares must not be added. The runtime request pause totals are only 295,250/355,749 nanoseconds. The profiles identify concurrent garbage-collector scanning and allocation work, not long stop-the-world pauses.

The largest flat allocation sites are:

- `matchPattern.func1`: 40.85%/47.17% of sampled request allocation. For wrapped, `bound[name] = value` accounts for about 5.23 GiB, and appending full `matchState` records/one-element path-node slices accounts for about 9.20 GiB.
- `nodeCandidates`: 29.26%/34.04% flat. The wrapped line sample attributes about 10.33 GiB to passing a full `qualifiedNode` through an interface at `traversal.go:145`.
- Wrapped expression evaluation adds 9.40% flat; lowercase builder growth adds 3.44%. `Store.Node` and child decoding allocations together account for 6.77% cumulative, substantially less than generic candidate/row construction in this run.
- In supplemental Method count, copying provenance slices accounts for 29.76% flat sampled allocation (about 704.97 MiB). `matchPattern.func1` contributes 43.71%; the aggregate materializes candidate/group/value structures although its output is one row. The short CPU profile has only 690 ms of samples and is unsuitable for strong CPU percentage conclusions.

The retained-heap profile after each request attributes 67.46% to `Store.loadEdges`, 15.98% to the node-location index load closure, and 13.11% cumulative to metadata decoding. This describes the persistent scan base; it is distinct from transient query allocation.

## Next falsifiable hypotheses

1. For a correctly guarded single-node MATCH without relationships/path binding, stream predicate evaluation and retain only accepted rows instead of constructing all 19.4 million states first. Preserve source order, expression/error semantics, metadata, DISTINCT and LIMIT behavior. Prediction: the state-slice/map retention sites shrink materially, request-end HeapAlloc and allocation decrease, and collector scan sample share declines. Falsify by identical full-output real64 reruns showing unchanged allocation/retention, or by any output/semantic mismatch. Implement and record as one separate optimization attempt.
2. After that, use a typed candidate representation for supported property-discovery shapes to avoid repeatedly boxing a full node. Prediction: `nodeCandidates` flat allocation at the interface conversion disappears/reduces. Use all original 42 shapes and unsupported-shape fallback tests to establish semantic boundaries; do not extrapolate from zero-hit alone.
3. Separately, accumulate count/provenance for a guarded ungrouped Method count without materializing all method rows and repeatedly copying growing provenance sets. Prediction: the observed 2.44 GB allocation and the 704.97 MiB provenance-copy site collapse, while exact count and all 64 graph IDs remain. Grouped aggregates, DISTINCT, aliases, WHERE, empty sources and mixed projections must either preserve semantics or remain on the general path.
4. A separate storage hypothesis is compact immutable adjacency rather than per-node outgoing/incoming maps of full Edge structs. Prediction: retained heap attributed to `loadEdges` falls with identical actual-edge hashes and traversal results. This profile provides a memory target, not evidence that such a rewrite improves latency.

No optimization was implemented or committed during this diagnostic task. Any candidate must have its own chronological attempt, complete real-data correctness/performance validation, and separate commit under repository conventions.
