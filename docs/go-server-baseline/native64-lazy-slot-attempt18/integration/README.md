# Independent A18 integration on committed A17

Bounded verdict: no new correctness blocker found. Full module race/vet and all current complete-response comparisons pass with the explicitly enumerated preexisting state variation. No performance conclusion is made.

## Exact inputs

Clean base fb4434df4545c195fdee379d109266bbdaaab4d1 was extracted using git archive for graphite-server, graphite-explore and CONVENTIONS.md. Its 2436 module files plus 59 external reference files exactly equal the previous tested A17-on-A16+B input inventory. Thus A12, A13, tuple, CDE, A15, B, A16 and A17 remain present. No lazyedge or filtered relationship changes are included.

The A18 author manifest 5565f5e27c2fac6367ef1054fc34372110769c3671d80a5d341230893580db0b verified all 276 files. Patch 259d1be1cef97757ec5a012f20d24c597c862e9c6b29d3840b34a268e973a26d applies cleanly without conflicts. All three changed files match author bytes: lazy_filtered.go (one functional loop), candidate.go (comment only), lazy_filtered_slot_test.go (new test). Final candidate has 2437 module files plus the same 59 external files. No production, existing tests, root/provider files, or author freeze was edited during independent review. Source archive/inventories and exact patch are provided here.

## Mechanism and ownership review

The slot is allocated after the existing indexed candidate branch returns, only when rowOrders is nil. The generic loop fully reads the same owned Node through the same source Next before assigning graph, graphID and node. The slot is query-local and sequential; it is neither shared across workers nor stored in evaluator, Store or a cache. No task source, iterator, context check, reader or Close call changed. All A17 Store poll sites remain byte-identical to committed base.

The registry guard is necessary: e.bind registers the scratch binding map by reference when rowOrders is nonnil. That branch continues to construct the old owned nodeValue. Whole output values freeze in lazyProject; nested lists, maps, comprehensions and scalar list additions freeze at their existing construction boundaries. CASE/coalesce/unary transport a slot only synchronously. freezeCandidate is shallow; the container construction boundaries, not a nonexistent recursive freeze, establish ownership. Matching/WHERE, projection order (including overwritten aliases), Java equality, provenance merge, and qualified later-source consumption remain unchanged.

The author's guard-disabled negative-control artifact is verified within the author manifest and proves the escape check can fail. Independent execution also used the final new tests against an exact original-production overlay of the already-existing canonical lazy_filtered.go path: all five top-level tests and 152 subtests actually executed and passed. This is not a newly-added-file overlay with zero tests.

## Independent results

Full module go test -race -count=1 ./... and go vet ./... exit 0. All 76 current capture paths are retained: 73 exact; three differ only at 51 mappedView boolean leaves in count-40 source histories. Both source counts and precise paths/types are checked. There are no retained-state or public-response changes in this run. Original corpus remains 1044 equal out of 1048 with the same four F mismatches. All B595 complete JSON-value responses and 53 ordered design traces pass; the prior 166 numeric spelling differences remain explicitly listed (8 rows, 98 params, 60 spec), not called byte-equal.

Six actual named boundary tests ran three times under race in a separate review-module copy, including A15 binding cleanup/task join, generic request cancellation/join, B wave failure/join, A18 standard cancellation/independent requests and registry fallback. An additional independent test uses two distinct Stores with the same IDs, inline property matching, comprehension binding and nested DISTINCT whole-node output. It asserts the actual retained source Store, graph ID and node IDs for all four rows and materializes the owned rows after closing both Stores. All 18 top-level executions passed; test source and raw logs are retained. Review-module query vet also passed.

The independent test initially used lowercase names for qualifiedNode's exported fields and failed compilation. That exact test source and compile log are preserved; only the test field names were corrected, then all six named tests actually ran. No production fix or weakened expectation was needed. Author earlier sqrt-to-left test correction and intentional disabled-guard failure remain immutable in the verified author evidence.

No JVM, HTTP, real64 runtime or performance experiment was launched here. Correctness checks may have overlapped parent shipping HTTP, not a timing claim. All processes are terminal before handing the candidate to the parent for the separately controlled measurement. These finite corpora do not prove every language behavior or eliminate the four known F cases.
