# C / D / E lazy consumer author freeze

The three new query files and one engine hook implement plan-neutral lazy filtered
node consumers using storage's final `mainStringCandidatesWithPolicy` factory.
This is a functional change. No 64-graph runtime, performance measurement or
commit was made in this worktree.

## Apply and identity

- Worktree: `/tmp/graphite-go-lazy-cde-87aaf0ad`.
- Historical commit: `87aaf0ad`; effective input is ordinary + generic synchronization
  + final main-candidate source. Exact input tree:
  `baf9ae489cae2477bc52c7a83561cfd0ff449560`.
- Final tree: `faf2bee8fe4fc845442cf775f212cd70b0a96bbd`.
- `consumer.patch` is the independently mergeable delta against that effective
  input. `combined-from-87.patch` includes all provider dependencies and is for
  reconstruction, not a second change to apply.
- `production-files.json` identifies the four production outputs.
  `source-production-check.json` proves all 16 frozen provider production files
  were preserved byte for byte. Provider patch and manifest hashes are in
  `commands.json`. Root's later A12/A13/tuple changes are not in this historical
  input and must be retained independently during integration.
- Hook follows existing indexed/ordinary/generic consumers. B pagination/ORDER
  must follow this consumer; CDE declines SKIP/ORDER. Storage's independent B
  change handles the older generic DISTINCT dispatch overlap for SKIP.

## Validation

Final `go test -race ./...` and `go vet ./...` exited zero. Logs and exact command
context are retained under evidence. Original 1,048 complete main responses were
replayed: 1,016 equal, with B28 and F4 remaining. The previous 580 DISTINCT and
122 ordinary eligible responses remain equal. All C8/D8/E6 now match. The new
620 complete main response comparisons comprise 480 broad, 96 conjunction edge,
36 actual-16-CPU source-wave, and 8 independent routing cases. Test helper and
fixture files, semantic and wire oracles are in oracle and in consumer.patch.

The independent review manifest is copied here (upstream SHA
`ecc45f8a7e196aa3f4fab911d5f6a9537ca3a2bf8a1d7d1ea4c8bca29f920d6e`).
Its bounded checks include race repetitions, cancellation/fresh-request/Close,
80 historical test-oracle associations, and all four output hashes. It found a
real scoped route bug: main's bounded generic nonqualified nodeCandidates ignores
selected sources. Before/after all eight counterexample responses are preserved
by that review. The local fix is scoped only to that consumer. Final review also
confirmed the one-line bounded guard preventing a synthetic MaxInt32 limit on D.

## Failed observations and correction

Initial broad comparisons contained an oracle comparator query-field mismatch;
the next comparisons exposed actual routing differences and twelve apparent
whole-node string differences. All logs and full failed outputs are retained.
Java semantic JSON recorded lone U+D800, whereas actual tiny main HTTP emitted
one ASCII question mark through UTF8 encoding. Two real HTTP bodies, field UTF16
unit probes and byte receipts are retained in evidence/string-boundary.
`wire-audit.json` proves exactly twelve value conversions in the original 480,
with every other field unchanged. Original semantic files remain unchanged;
the corrected oracle uses Java UTF8 output. No production string change or input
masking was made. The protocol's initial closed-store panic was separately fixed
at the consumer's established ordinarySourceFailure boundary and then retested.

The final original corpus is in evidence/original-final. Earlier native controls,
raw failures and oracle generation commands remain alongside it. Disposable
per-query graph directories and class files are omitted from this delivery;
reproducible persisted input fixtures and all Java sources are included.

## Remaining scope

B28 streaming pagination/ORDER and F4 unlabeled MAPPED order remain separate.
Method, unknown-label, relationship/path, optional and aggregate dispatch is not
newly implemented here. This freeze does not claim equivalence for arbitrary
Java Thread.interrupt/GraphWork histories, configurable global memory budgets,
or every JVM scheduling/HotSpot exception history. It preserves the source
provider's documented capability boundaries rather than replacing them with the
older materializing scan. There is no performance or P95 claim.
