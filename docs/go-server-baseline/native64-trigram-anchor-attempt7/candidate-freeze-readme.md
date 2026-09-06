# Attempt7: certified shortest trigram anchor

Candidate worktree: `/tmp/graphite-go-trigram-attempt7-4182478e`.
Base: root commit `4182478e` (full SHA in manifest), containing Attempt6, count/early-limit semantics, and the corrected A6 cancellation test. This patch does not include the later `bbdfae2a` node-tag consumption change or CLI commits. On integration, preserve root's `failNodeRead(err)` at CandidateNode consumption; this patch does not modify that block.

The sole optimization hypothesis is to replace full property-directory scans with the shortest persisted trigram span for eligible CONTAINS arms. Necessary cold completeness validation is included in the same measured query path. There is no signature filter, lowercase/result cache, equality/prefix/suffix optimization, new node enumerator, HTTP change, or query-text whitelist. No performance measurement, 64-graph process, or commit was run by this task.

## Runtime behavior

All Attempt6 admission rules, property membership/null inference, MAPPED-only guard, Annotation fallback, context/error handling, source order, candidate publication and exact WHERE/projection remain. Each admitted OR arm independently uses an anchor or falls back to the existing dictionary logic.

An anchor requires CONTAINS and at least three UTF-16 units. Raw RHS additionally requires ASCII and is lowercased for lookup only. A LOWER arm hashes its original RHS. Candidate strings are transformed and compared using existing exact evaluator semantics with the original RHS. Empty/short strings, raw non-ASCII, and the other admitted operators stay on dictionary matching. An OR with unsafe null inference or an unsupported expression still takes the existing full-scan fallback.

`CallSiteStringIndex.TrigramAnchor(ctx, hashes)` holds the existing reader lifetime RLock throughout bounds search and copying. It binary-searches each hash span without materializing it, returns an available-empty slice if any hash is absent, and allocates/copies only the shortest span. It checks cancellation before/after the operation and while iterating hashes/copying. Returned IDs own their memory. Private exact-key binary search checks `(hash,SID)` without copying posting arrays.

`Store.CertifyCallSiteTrigrams(ctx, view)` first requires the existing A6 core/order/CSR certificate. It visits each SID used by the four certified directories once, computes full Java ROOT lowercase and distinct UTF-16 hashes, then proves each required pair is present. Extra pairs are allowed because exact matching removes false positives; signatures are unused. Missing pairs cache an invalid proof and keep A6 dictionary matching. Context cancellation or Close do not cache a result, clear/wake pending work, and propagate the error. Publication and cached return use the existing lock order, reader lifetime then certificate state, and final context/closed checks. Certificates belong only to one immutable Store lifetime.

The proof runs within the first eligible query. Its lowercasing, hash maps, directory copies and exact-key lookups are real cold preparation costs. It retains only certificate status afterward, not a lowercase cache. A common needle can have a large anchor. Close is observed at the next guarded read or publication; casing polls context but does not hold a mapped-memory lock. No wall-time or speedup guarantee is made before parent-owned real64 measurements.

## Correctness evidence

`internal/query/testdata/trigram-index/` contains the frozen audit's main-generated tiny fixture, exact source/jar receipts, UTF-16 primitive/index observations, and 232 full query observations. `supplement/` adds 64 actual-main mixed-OR observations. Both corpora were stable across three independent JVM runs after canonicalizing only JSON object key order; array order and all values/exceptions remain intact. The source is pinned main `4e328b0109e13c896b74004823fb049fcb19251a`, Java17.0.18+0 ARM64. Java is used offline for the oracle only.

The Go test runs all 296 observations through enabled trigram, forced-A6 dictionary, and forced full scan, against clean, missing-posting, and extra-posting sidecars: 2,664 complete columns/rows/error comparisons. Missing/extra sidecars are controlled mutations of main's real writer output with valid typed numeric CRC, counts and retained-byte estimates. The native results are compared to CLEAN main plus forced native paths; this is not a claim that malformed main itself produced those same outputs. Existing A6 corrupt-main gaps remain separate.

Vectors cover raw/wrapped four-field OR, different RHS case and ordering, fallback operators/Greek contexts, empty-null untyped inference, CJK, combining marks, U+0130 expansion, supplementary and isolated surrogate units, and a RHS starting at a low surrogate inside a valid pair. `aaz`/`ab[` demonstrate a full hash collision, while `aaa`/`acc` demonstrate a signature-only collision. Additional reader/store tests assert concrete anchor membership, shortest-span selection, available-empty vs unavailable, detached copies, cached valid/invalid proofs, canceled publication/retry, close before publication, waiting cancellation, concurrent close and copy-lifetime protection. Query cancellation checks every actually reached preparation point, allowing variation in map-sort comparison counts.

The old A6 cancellation test now explicitly selects dictionary mode, retaining its original coverage premise; A7 has its own cancellation enumeration. Production changes are limited to `query/string_candidates.go`, one Store state field, and new `store/callsite_trigrams.go` / `store/trigram_certificate.go`.

## Commands

From the module directory:

```
go test ./internal/query ./internal/store
go test -race ./...
go vet ./...
```

All pass; exact command statuses/logs are retained here. `git diff --check` passes. Offline fixture regeneration (no HTTP or benchmark):

```
python3 internal/query/testdata/trigram-index/regenerate.py /path/to/pinned-main/graphite-explore/build/libs/graphite-explore.jar
```

The archived audit README/manifest describe the earlier read-only evidence snapshot. This delivery manifest records the actual candidate source and testdata byte hashes, including the new supplement/mutations. JSON object serialization order can vary between JVM processes; semantic regeneration checks canonicalize object keys only. No root or earlier frozen worktree was edited.
