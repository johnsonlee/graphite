# Real64 positive-query CPU and allocation diagnosis

Pinned native revision de0608f0, with no production-code changes. All 64 persisted
graphs, 1,152 files and 10,338,207,518 bytes were rehashed against the existing
fixture manifest before each run. Catalog counts are independently asserted:
19,431,891 nodes, 20,448,885 edges, 1,374,983 methods and 5,046,935 CallSites.

The five queries are the main workload's bimodal class prefix (cold/repeat),
wrapped dense DISTINCT (first/repeat), then ordinary dense projection. Exact query
text and complete pinned-main HTTP bodies are in config.json. All five corrected
run responses match, retaining complete row order and provenance arrays; JSON
object member order is ignored for comparison. Results are not truncated for
validation. Runtime identities, raw profiles, commands, fixture checks and
per-request receipts are in run-corrected/; summary.json extracts their counters.

| Query | Allocated bytes | GC cycles | Execution + response serialization (s) |
|---|---:|---:|---:|
| Bimodal prefix, first | 18,222,452,888 | 2 | 32.315 |
| Bimodal prefix, repeat | 15,636,032,040 | 2 | 19.269 |
| Wrapped DISTINCT dense, first | 12,287,401,632 | 1 | 18.663 |
| Wrapped DISTINCT dense, repeat | 10,237,933,488 | 1 | 12.867 |
| Ordinary dense projection | 8,070,837,576 | 1 | 10.715 |

These are instrumented single observations under host co-tenancy, not HTTP/P95,
not a main-relative speedup and not peak RSS. The first query initializes index
certificates; subsequent queries retain completed Store state. The first dense
CONTAINS initializes trigram proof; its repeat retains it. Explicit GC occurs
before and after each query, outside the request counters and CPU interval. The
heap profile allocation difference also includes instrumentation bookkeeping.

For the first prefix query, sampled flat allocation includes about 4.06 GiB in
CodePoints, 1.99 GiB in UTF16, 1.40 GiB in query key construction, 1.22 GiB in
cloneRow and 1.19 GiB in freezeCandidate. Cumulative allocations overlap and must
not be added to flat totals. CPU also includes substantial GC and runtime memory
page handling, so a low pause count alone does not establish low GC cost. This
supports testing ASCII lowercase allocation separately, while the main-compatible
raw DISTINCT path remains separate functional work.

## Preserved failed harness and correction

The initial run/ stopped after its first complete query with exit 2. Its harness
serialized engine Rows directly, so n.graph_id:null was present where the actual
HTTP serializer omits null object fields. The complete first body agrees after
only this null-member transformation; initial-harness-failure.json records the
counterexample. No production fix was needed and no failed run was discarded.

The corrected harness calls Result.ResponseRows and the unchanged production
encodeCypherResponse through a small diagnostic-only exported bridge. Both files
are included in source manifests/new-source snapshots and are not linked into the
shipping command. Comparison canonicalizes object keys while preserving all
arrays/values. Corrected serialization is inside the measured interval. Therefore
the initial and corrected measurements are not treated as identical scopes.

Reproduce with a fresh detached checkout and fresh output directory:

```sh
python3 docs/go-server-baseline/native64-positive-profile-de0608f0/run-profile.py \
  --worktree /tmp/fresh-de0608f0-checkout --out /tmp/fresh-positive-profile
```

The runner installs only the two recorded diagnostic files into that checkout.
Overall functional parity and the requested repeated paired real64 10x P95 goal
remain unproven.
