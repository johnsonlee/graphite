# Exact main64 testcase ingestion in Go

The Go workload loader now preserves all **1,267 actual main cases**, including
all fields, original query text, original graph/replay order, 321 parameter maps,
explicit source selections, row ranges, timeout overrides and workload identities.
The complete fixture is byte-identical to the independently exported main corpus.

`cmd/graphite-benchmark-cases` authenticates its input by SHA256 and emits a
reviewable typed AST and execution-input record for every case. All 1,267 parse
successfully. The output explicitly records zero opened graphs, zero executed
queries and no runtime-parity or performance claim. Existing output files are
never overwritten. `command.json` reproduces the actual invocation.

`verify-inputs.py` independently compares every decoded field and execution input
against the actual JVM export. `input-verification.json` records the checks:

| Executor input | Cases |
|---|---:|
| All64, no preselected-scope marker | 952 |
| Request-selected1 | 192 |
| Request-selected2 | 96 |
| Request-selected8 | 24 |
| Request-selected64 | 3 |

The selected64 cases retain `sourceScopeApplied=true`; an equal-sized unscoped
source list retains false. Predicate routing is left to the executor. The loader
does not sort source IDs, interpolate parameters into literals or replace the
K64 zero-hit first case with a different warmup. Tests assert exact corpus hash,
complete semantic roundtrip, all family counts, MATCH/RETURN structures and
parameter references, concrete four-property OR AST operators/variables/bindings,
list parameter order, selected-source ownership and rejection of lost fields,
unknown graph identities and duplicated case IDs. Fullmodule race and vet pass.

## Next runtime requirements

This completes definition ingestion and syntax checks, not testcase execution.
The current query API does not yet carry main's request-selected scope marker
into its planner. Main's explicit-selected HTTP path also sets that marker
(ExploreRoutes.kt:834), so the missing propagation is a functional issue as well
as a benchmark-driver issue. Its planner effect includes persisted-source
preference in QueryPipeline.tryFastFilteredNodeLimit.

The cold/warm/startup-prepared states also need corresponding native lifecycle
operations. Main clearStringPropertyIndexes clears retained and mapped views,
raw match/projection caches and admissions while preserving graph ownership;
its close may persist an initialized trigram index. Closing/reopening the whole
Go Store cannot simply be asserted equivalent. Full real64 result and relevant
source-state comparisons in all three states remain required before P95 runs.
