# Indexed DISTINCT compatibility integration

This change reproduces main's selective indexed DISTINCT projection behavior,
including required-field error timing, retained-index state, source order,
post-LIMIT provenance, and cancellation/join behavior. It does not establish
general Cypher parity or a performance improvement.

`provider/` preserves the original freeze based on `bbdfae2a`.
`independent/` preserves its separate integration into `1d23843a`, including
the independent correction for a nil scoped Store. The original feature panicked
where the previous executor returned empty rows. The correction declines the
indexed path and keeps that public behavior. Four independent test files also
check nullable HTTP error messages, task cancellation/join, and separation of
selective index identity from full-node certification.

Root applied the exact independently integrated patch to `e6c3bd0c`, alongside
the separately verified signal delta. The clean validation archive is
`/tmp/graphite-go-distinct-root-e6c3bd0c`; `source.json` records both patch hashes
and every Go module file. Whole-module `go test -race ./...` and `go vet ./...`
passed. All 17 newly generated oracle files are structurally identical to the
independent freeze, including all classifications, not just successful cases.

| Complete main/native comparison | Cases |
|---|---:|
| Indexed path eligible, equal | 580 |
| Indexed path declined, equal | 159 |
| Declined, both fail with different class/message | 180 |
| Declined, main succeeds and native fails | 122 |
| Declined, both succeed with different result | 7 |
| Total | 1,048 |

Overall 739 of 1,048 match; the 309 declined differences remain work to do.
The provider testdata README describes planner eligibility and each corpus.
Unknown `n.graph_id` projection, ordinary projection and arbitrary expressions
still use other execution paths and must not be described as covered here.

The independent candidate also passed all 42 complete HTTP comparisons on all
64 real persisted graphs with the default 60-second server timeout and exact
main bodies/selected headers. Those raw observations, catalog and identities
are retained under `independent/http/`; elapsed values are diagnostic, not P95
evidence. Its binary remains outside Git at the location recorded in the
independent manifest, with SHA-256 recorded there. Root's combined verification
does not relabel that earlier executable as a newly built HTTP candidate.

`verification.json` records root commands and the 17 matching oracle outputs.
`final-verification.json` checks current source identity and preserved evidence.
Full ordinary projection/history compatibility, remaining malformed-store
differences, custom JVM scheduling settings, and the final paired main/native
P95 benchmark remain outstanding.
