# Private real64 mapped iterator diagnostic

This tool observes six independently opened Android graphs with the internal
`mainCandidateIterator` API, `sourceCount=64`, and `LIMIT 200`. It is neither a
public Cypher replay nor a benchmark. It has no expected range-cache counts.
Recorded query errors are observations; the runner fails only on process or
input-integrity failures.

The runner reads graph IDs from the immutable reference's `graphs.tsv`, verifies
every file in the six selected graphs against the frozen real64 manifest, then
creates fresh APFS reflink copies. Only those new copies enter `Store.OpenMode`.
Every selected reference file and copied file is hashed again after Close;
added, removed, and changed files are retained in the before/after manifests and
fail the integrity check. No previous capture fixture is opened.

Mapped-view preparation reconstructs the observable case-3 starting state.
`prepared-state-comparison.json` compares every original v2 state field with the
newly prepared state and reports any mismatch. The exact-matching probe records
all matching string IDs and texts before invoking the original candidate
constructor, which independently repeats its own exact matching. Both snapshots
are retained so this diagnostic probe's possible state effects remain visible.

The context is `context.Background()`, with no request tracker or local
cancellation; no work budget is injected. Each returned ID is retained in order,
along with its kind, caller-class value, and hash of the complete JSON-encoded
Store node. An aggregate hash includes each complete node JSON plus a newline.
The source module is copied and hashed before injecting the new diagnostic-only
test. Existing production files and tests are never edited.

The root task schedules execution after other runtime work is terminal:

```sh
python3 docs/go-server-baseline/native-persisted-work-accounting/checks/cursor-real64-diagnostic/run.py \
  --module-source /Users/johnsonlee/.codex/benchmarks/graphite/persisted-work-f0838dda-real64-source-v2 \
  --v2-responses /Users/johnsonlee/.codex/benchmarks/graphite/persisted-work-f0838dda-real64-v2/native-cold/responses.jsonl \
  --output /Users/johnsonlee/.codex/benchmarks/graphite/persisted-work-f0838dda-cursor-real64-diagnostic-v1
```

## Recorded result

The root task ran diagnostic v1 to exit 0. All six prepared snapshots match all
eight observed fields of the original native case-3 starting snapshots; those
snapshots also equal the recorded main starting snapshots. Each private iterator
completed without an exception and returned EOF at its requested bound.

| Graph | Exact matching strings | Returned IDs | Range entries after construction and iteration | Original main after case 3 | Original native after case 3 |
| --- | ---: | ---: | ---: | ---: | ---: |
| android-02 | 314 | 79 | 7 | 7 | 0 |
| android-03 | 323 | 55 | 6 | 6 | 0 |
| android-04 | 320 | 106 | 7 | 7 | 0 |
| android-05 | 323 | 5 | 1 | 1 | 0 |
| android-06 | 368 | 200 | 5 | 5 | 0 |
| android-07 | 343 | 168 | 5 | 5 | 0 |

The 200-row result for android-06 reaches the iterator's limit; its EOF does not
assert that the graph has no further matches. The returned IDs and node hashes
are diagnostic observations, not comparisons against per-source JVM rows.

This shows that the frozen Go mapped candidate path can construct the same
number of range-cache entries on each affected graph when run independently
without local cancellation. It rules out an unconditional inability of that
path to match these graphs or publish their ranges. It does not establish why
the original multi-graph query left those entries absent. Equal observed state
does not establish equal complete execution history or unobserved cache state;
private iterator success does not prove the public route, scheduling, or
cancellation behavior. The original real64 differences remain unresolved by
this diagnostic.

The `evidence/` directory preserves all 15 external top-level raw files byte for
byte. `executed-diagnostic_test.go.txt` is copied from the actual executed module
and matches the template SHA-256
`5ed32db7c6f53061c24977d11915cae3a0498622bfde0f2bcdb93b75d7965883`.
The independent archive verification rehashed both actual module directories:
the original has 2,563 files and the executed copy has 2,564, with only the added
diagnostic test differing. All 108 selected fixture files have identical
recorded reference/copy hashes before and after execution. Archiving did not
run Go, JVM, or query code and did not reopen a fixture through Store.
