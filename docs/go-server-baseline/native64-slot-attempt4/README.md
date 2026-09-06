# Attempt 4: reuse a single-node WHERE candidate

Hypothesis: avoid boxing a complete decoded node or method into a new interface
value for every rejected candidate. A borrowed slot serves property predicates;
values are frozen before entering containers, retained binding rows or output.
Node decoding and error order must stay in the existing enumeration phase.

Decision: keep the verified allocation reduction, with the default HTTP timeout
failure explicitly unresolved. This is not a complete HTTP parity or P95 pass.
The unchanged native base also times out on the same full-64 HTTP query.

## Candidate result and limits

The complete candidate patch SHA-256 is
`6084e090235c6f6505e17e05fafe657023ba148b1d70667be20454dd13d6955a`;
its 14 source files are listed in `candidate-source-final.json`. Profile binary
SHA-256 is `d13e93d94e755f5d8a3d3f7cc319969b6d4ecea8f19a81ff4d4ee705542de9da`.
All source changes are over the exact native base below. The real fixture was
freshly hashed again before the candidate ran; its catalog and all three profile
output bodies match.

| Shape | Base → candidate allocated bytes | Base → candidate CPU seconds | Base → candidate execute + marshal seconds |
|---|---:|---:|---:|
| wrapped-zeroHitBroadContains | 55,093,306,872 → 42,656,840,592 | 103.565 → 93.482 | 64.053 → 62.580 |
| global-wide-four-properties-zero | 24,431,143,712 → 11,994,716,656 | 41.058 → 30.540 | 23.378 → 21.677 |
| supplemental-method-count | 2,441,487,928 → 2,441,488,968 | 0.791 → 0.792 | 0.772 → 0.778 |

Each WHERE shape allocates about 12.44 GB less: approximately 23% and 51%.
GC cycles fall from 9 to 7 and 4 to 2. Request-end heap falls from 8.89 to
7.39 GB and 8.11 to 6.61 GB; post-forced-GC heap remains about 6.28 GB.
These are not peak RSS values. The no-WHERE control adds seven allocations,
1,040 bytes of fixed bookkeeping; one noisy observation is not a regression
conclusion. Profiling still attributes most wrapped allocation to Java string
conversion and substantial CPU to memory copying. No string algorithm changed.

Forty main JVM query cases pass, along with full-module race/vet. The
[independent slot audit](../candidate-slot-readonly-audit/README.md) passes 198
complete queries against the original boxed path and 4,318 expression/error/
lifetime comparisons. The initial cleanup deleted a rejected candidate's binding
key, changing six cross-graph whole-row string orders. Final cleanup keeps the
key with a nil value; all six differences disappear. No initial variant was
profiled, and its failing correctness evidence remains in the audit directory.

The default HTTP replay completed only **3/42** responses: the wrapped zero-hit
query returned HTTP 504, while the following two responses were HTTP 200 and
fully matched main. The fourth request was interrupted when the runner was
stopped; 39 queries therefore have no completed result in this replay. Raw
responses and the explicit stop record are in `full42/`. A fresh unmodified
native base also returned HTTP 504 for that first query on all 64 graphs; see
`base-http-timeout/`. This establishes an existing native deadline failure,
not successful parity with main's HTTP 200. Both dedicated runtimes exited.

The change is retained for reduced allocation and independently checked value
semantics while subsequent work addresses the deadline failure. No repeated
latency samples or warm/cold P95 acceptance were collected, and no 10× claim is
made. A later full 42-query run must be a new record, never replace this failure.

## Current native baseline

Base revision: `4124bfc44bedd7915e238b7a08f2524b48ed47b6`. This includes the current
query/Gson compatibility changes and shared Java string implementation. Earlier
Attempt 2 profiles at `a7de0bec` exclude later functional changes and are not the
comparison baseline for this experiment.

All 64 real shards at `/tmp/pr113-exp037-fixture.nXn4fg` were loaded and every
catalog count checked. All 1,152 frozen fixture files were freshly hashed:
10,338,207,518 bytes, 19,431,891 nodes, 20,448,885 edges, 1,374,983 methods and
5,046,935 call sites. `base/fixture-verification.json` identifies the immutable
source manifest. No original fixture was modified.

| Shape | Total allocated bytes | Request GC cycles | User + system CPU seconds | Execute + marshal seconds |
|---|---:|---:|---:|---:|
| wrapped-zeroHitBroadContains | 55,093,306,872 | 9 | 103.565 | 64.053 |
| global-wide-four-properties-zero | 24,431,143,712 | 4 | 41.058 | 23.378 |
| supplemental-method-count | 2,441,487,928 | 0 | 0.791 | 0.772 |

All three complete output bodies match the expected main-derived values. The
Method count is a supplemental no-WHERE control, outside the fixed 42 HTTP
queries. Request GC pause totals are 1,091,625 ns, 415,667 ns and zero respectively;
those are sums across each request, not maximum individual pauses.

The wrapped allocation profile attributes approximately 24.16 GiB to UTF16
conversion, 6.30 GiB to CodePoints and 11.60 GiB directly to nodeCandidates.
CPU samples attribute 43.66% flat to memmove and 30.69% cumulative to gcDrain.
Cumulative CPU percentages overlap and must not be added. This hypothesis targets
node boxing only; Unicode conversion changes belong to a separate experiment.

These are single profiled native observations, not HTTP samples or P95s. CPU
profiling and host co-tenancy affect duration. Two forced GCs before and after
each query are outside the measured request. Allocation counters and sampled
heap differences have slightly different instrumentation boundaries. Heap after
forced GC is retained heap, not peak RSS. The wrapped observation exceeds the
default HTTP timeout, so it cannot establish that this base passes the current
42-query HTTP gate.

## Reproduction

From repository root, use a fresh worktree at the exact revision and a fresh
output directory:

```sh
python3 docs/go-server-baseline/native64-slot-attempt4/run-profile.py \
  --worktree /tmp/graphite-go-slot-base-4124bfc4 \
  --out /tmp/graphite-slot-profile-fresh
```

The runner rejects an existing evidence directory, verifies the complete frozen
fixture, records all source hashes and the exact binary/build/runtime command,
then profiles `ExecuteCross` plus root response marshaling after full loading.
`profile-query.go` is the exact harness; `config.json` fixes all graph and query
inputs. Native binary SHA-256 is
`14fe28c3e6eb185ffed45d91713467403e0dd8da692610d361b70fcbddc73c64`.
`base/profiles` retains raw CPU/heap files, counter snapshots and output bytes;
`base/pprof-commands.json` records the derived profile table commands.

`replay-http.py` runs the complete frozen 42-query response gate on all 64
graphs with default timeout/admission settings. It keeps each full response and
checked headers, including failures; the recorded run was stopped as above.
A successful single replay would still be
correctness evidence only; the paired cold/warm and concurrency P95 acceptance
remains separate.
