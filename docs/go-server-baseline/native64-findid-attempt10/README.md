# Attempt 10: main-compatible selected SID binary search

The two selected-projection lookup loops now use the loaded StringTable.findId
algorithm from pinned main `4e328b0109e13c896b74004823fb049fcb19251a`: search by
Java UTF-16 order and return the first equal midpoint visited. No table sorting,
deduplication, lookup cache, task/scheduling, index preparation or Postings call
was added or moved. Each lookup still occurs at its original projected-field
boundary and observes cancellation. It reads the already owned string table;
subsequent index reads retain their own Close/error checks.

This also corrects a functional difference. Main accepts serialized string tables
with duplicates or unsorted entries, where binary search differs from Go's old
first linear match. Forty-five actual-main method lookups cover these cases,
missing values and explicit UTF-16 boundaries. Two actual nine-source public
queries now match main's complete result: the old Go implementation incorrectly
included g8 provenance; both main and the candidate include only g0 through g7.

The independent review is preserved under `independent/`, with its original
three source files under `independent-source/`. All 91 manifest entries were
verified before copying. Compiled Java classes remain external at their recorded
paths/hashes. Five new targeted tests, whole-module race/vet, and all 17 complete
oracle JSON comparisons passed. The original corpus remains 739 matches out of
1,048, with 309 existing declined differences; the two new main-query corrections
are additional cases, not a replacement denominator.

Root's clean integration moves only test fixture lookup paths into portable
`internal/query/testdata/distinct-string-id`; production and captured fixture
bytes are identical. That clean archive passed whole-module race/vet again.
`integration/source.json` identifies every shipping file and its hash.

## Real64 control

Base is `eebec091`, candidate is that exact revision plus the two production
files and independently reviewed tests. `base/` and `candidate/` each freshly
verify all 1,152 files, 10,338,207,518 bytes and all 64 catalog entries. The same
five queries run in the same order in two sequential fresh processes. All five
complete serialized responses match pinned main and each other. CPU sampling is
disabled on both sides; heap profiles, forced GC outside request counters and
actual response serialization are unchanged from the preceding control.

| Query | Before allocation (bytes) | After allocation (bytes) | Before execute + marshal (s) | After (s) | Before process CPU (s) | After CPU (s) |
|---|---:|---:|---:|---:|---:|---:|
| Prefix first | 11,136,134,616 | 11,135,934,448 | 20.280 | 21.923 | 25.999 | 27.477 |
| Prefix repeat | 8,549,735,376 | 8,549,772,240 | 11.370 | 11.404 | 18.163 | 18.204 |
| Dense DISTINCT first | 5,770,530,048 | 5,804,032,136 | 53.949 | 8.570 | 260.150 | 43.156 |
| Dense DISTINCT repeat | 334,408,336 | 367,914,936 | 54.051 | 8.770 | 258.078 | 40.931 |
| Ordinary dense after DISTINCT | 5,640,824,208 | 5,640,799,944 | 11.195 | 11.162 | 11.182 | 11.140 |

The measured dense path executes about 6.2–6.3 times faster than the regressed
native base in these observations. It allocates about 33.5 MB more per dense
query because binary comparisons decode UTF-16 units. This is a CPU/latency
improvement with a disclosed allocation cost, not a GC/allocation reduction.
Prefix and ordinary paths do not use this helper; their single-observation
timing variation does not establish improvement or regression on those paths.

These are single instrumented observations with background correctness/build
work. No other64 performance process overlapped. They are not quiet-host repeated
HTTP/P95 samples, main-relative speedups, or peak RSS. Retained heap and complete
request counters are in the raw receipts. This does not establish the overall
10x P95 goal or full functional parity. The independent actual-command all64 HTTP
review passed42/42 HTTP200 with complete main bodies and checked headers, using
the default60-second server deadline. `http-independent/` retains all responses,
source/binary identities and independent verification; its server was stopped.

Reproduce with fresh detached checkouts and fresh output directories:

```sh
python3 docs/go-server-baseline/native64-findid-attempt10/run-profile.py \
  --worktree /tmp/fresh-findid-base --out /tmp/fresh-findid-base-result
python3 docs/go-server-baseline/native64-findid-attempt10/run-profile.py \
  --worktree /tmp/fresh-findid-candidate --out /tmp/fresh-findid-candidate-result
```

Exact source, binary, harness, commands and environment identities are retained
per run. `profile-verification.json` confirms source files stayed unchanged during
measurement. Only the diagnostic command contains the response-serialization
export; the independent HTTP review builds the ordinary shipping command.
