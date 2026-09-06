# Independent consumed-node-tag integration

The frozen functional patch is `f640741a90acea132d6e859440eff4988c1998940219ddaf1e0e409fa59d91aa`,
child commit `1cb2cef2e32fc2043bc7681e5cf2858cef20250a`. `frozen-manifest.json` and
`candidate.patch` retain its exact identity. It introduces a typed Store error
while preserving the unsigned Store diagnostic; actual query consumption maps to
main's IllegalArgumentException with signed-byte text. The loader's 400 and REST
node/subgraph 500 responses use their respective main wire shapes. Other errors,
missing IDs, truncation and statuses are not reclassified by text matching.

The frozen evidence includes 104 complete main/native HTTP comparisons, 72
load/read/query/cross lifecycle assertions, and the prior twelve early-limit
corrupt-tail cases, now all equal. See
`../../../graphite-server/internal/server/testdata/node-tag/README.md` for exact
source locations, preserved before/after bodies, load modes and regeneration.
The originally observed missing-index partial-file side effect is separately
preserved and remains unimplemented; it is not counted as fixed by this patch.

Root first reconstructs `4182478e` plus all 45 exact frozen files. Attempt 6 adds
one new actual-read boundary, CandidateNode in the prepared index walker, that
was absent from the child's base. A separate fault-injection test prepares a valid
candidate walker, sequentially corrupts only private fixture node tags, then
consumes it. The initial four cases produce native CypherException, recorded in
`candidate-consumption-before.log`. After routing only that consumed error through
failNodeRead, tags 16/127/128/255 produce the required class and 16/127/-128/-1
messages, with no candidate published. `candidate-consumption-after.log` records
all four passes. This mutation is deliberate error injection, not a claim of
support for arbitrary live mutation of immutable persisted stores.

Certification failures still choose the existing unavailable/fallback path.
Neither certificate semantics nor candidate selection/order changes here.
All 45 frozen source/evidence files remain byte-identical. The additional
production change and permanent regression test are hashed in
`integration-source-final.json`.

The final clean archive starts from `274cd678`, so it also includes the integrated
CLI/JAR bridge. Full native-module `go test -race ./...` and `go vet ./...` pass;
`verification-final.json` identifies that archive, exact commands and exits.
The earlier independent archive and its failed/passing boundary checks are retained
in `setup.json` and `verification.json`, without replacing original evidence.
These are semantic and lifecycle checks on tiny correctness fixtures. No 64-graph
performance run or P95 claim is made. Raw projection, malformed-index persistence,
full runtime parity and the 10× main-relative P95 target remain open.
