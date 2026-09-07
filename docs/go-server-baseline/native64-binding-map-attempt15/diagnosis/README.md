# Prefix regression: one falsifiable allocation hypothesis

The existing evidence points to a newly allocated expression binding map, rather than to a need to reverse the main-compatible source semantics. In the candidate's repeated prefix query, the allocation delta attributes3091.75MB flat (88.04% of sampled allocation) to `genericDistinctScanner.next.func1`, line309: a new `map[string]any{variable:value}` for every projected item. The selected-row provenance completion path accounts for nearly all of this work. The proposed next experiment is **one scanner-owned reusable binding map**, preserving the same evaluator and freeze calls, candidate reads, projections and provenance completion.

This directory contains only analysis of existing profiles and source. No production edits, new query execution, JVM, real64 server or benchmark was performed. The recorded measurements are single sequential diagnostics, not P95 or a general speedup claim. CPU sampling was disabled in the original captures, and no CPU profile was read here.

## Evidence and exact runtime identity

Both original programs were based on102364876ccf5917bba099a6d8e07e14f0fa28c3 with the recorded candidate source patch. Their actual still-present executables were independently rehashed:

- base: `921c9df1b6cfbbbe1ef885b8c805ec9eeb46d0ead1ae3afef40c0fae41743fdc`
- candidate: `f124285325e1cb445fef6cecb25f6a61afddbf3e76295728edf9914afbb10cae`

The copied source files match each capture's recorded source manifest. Profile file hashes, original identity and completion receipts, exact `go tool pprof` arguments, six exit0 statuses, stdout and stderr are preserved. `capture.py` is the read-only reproduction driver. The original allocation sampling rate is524288 bytes; profile-derived allocation is an estimate and must be distinguished from the exact runtime TotalAlloc delta.

| Existing repeated query | base elapsed s | candidate elapsed s | base TotalAlloc B | candidate TotalAlloc B | base user+system CPU s | candidate user+system CPU s |
|---|---:|---:|---:|---:|---:|---:|
| class prefix |2.485200667|11.54889|1054277592|3759881000|16.217181|54.964670|
| dense CONTAINS control |4.505828750|0.295037458|367932272|268426608|21.108569|1.795060|

All four complete response hashes match their original main responses. Neither prefix repetition performed a GC during the measured request. Thus “more GC pauses caused the regression” is contradicted by these receipts. The aggregate CPU numbers above are process resource-usage deltas, not CPU-profile samples. The allocation profile can localize bytes; it cannot prove what fraction of elapsed or CPU time those bytes caused.

## Why prefix reaches this code

The exact prefix query selects `n.graph_id` plus caller/callee class/name and uses a four-arm wrapped STARTS WITH disjunction with LIMIT250. `compileIndexedDistinct`, lines76–85, admits the four CallSite string properties and `graphId`, `class`, `name`; it correctly declines `graph_id`. The generic DISTINCT compiler admits that projection. Changing the query tographId, dropping the unknown field or expanding raw admission without main evidence would change semantics, not repair this regression.

The base generic scanner used the direct property/literal switch and strict A6/A7 candidate preparation. The main-source candidate still uses a borrowed candidateSlot, but `mainSource=true` selects the full evaluator branch at `generic_distinct.go:309` before the direct switch. Each of five projected items receives a freshly constructed one-entry binding map. In the source-completion phase `remaining` repeatedly calls `next(ctx,true)` and evaluates `finalItems`, even after250 visible rows have been selected, to collect full source provenance.

The candidate source-history state also differs legitimately. Before the repeated prefix request, all64 base stores report neither mappedView nor retainedIndex; all64 candidate stores report both, with tupleCapacity0. The new main source first tries mapped matching and falls back to retained loading when STARTS WITH cannot use the mapped CONTAINS matcher. Repeated requests use retained posting-range iteration; `mainIndexNodeIDs` appears in the candidate profile. The main source's type lookup passes its full type count as the source-local limit, not the global visible250. Therefore source completion cannot be truncated at250 or replaced by the old strict certificate without reintroducing the independently demonstrated main compatibility differences.

The allocation delta supports a narrow conclusion:

- candidate sampled total3511.65MB; line309 flat3091.75MB, cumulative3200.75MB;
- candidate `remaining` cumulative3489.89MB, 99.38%; source cursor/decode cumulative265.16MB, 7.55%; these cumulative sets overlap and must not be added;
- base sampled total1011.21MB, dominated by case conversion, predicate checks, NodeProperty and indexed preparation, rather than the new map-expression line;
- the candidate sampled line accounts for about19.38million allocation objects, not an exact count of decoded nodes. Allocation sampling does not justify inventing a node-read count.

The dense query projects only the four supported string fields and follows indexed/raw projection; its candidate profile is dominated by `distinctRawRows` and `distinctSourceHits`, not generic scanner expression maps. It also benefits from a different retained-index history. The before/after states establish that history difference but do not establish a per-cache hit count. This control should remain in the next experiment to detect unintended changes.

## Single proposed change and non-negotiable semantics

Allocate a one-entry projection binding map once per genericDistinctScanner, keyed by the existing AST variable. For each accepted candidate, bind the current borrowed value, run the unchanged `local.eval` and `freezeCandidate` sequence, then clear the borrowed value on normal return, panic and cancellation. Reuse the map only in the scanner that owns it; never share it across source scanners or requests.

The hypothesis deliberately preserves general expression evaluation instead of introducing a second evaluator or bypassing calls for selected query text. The source path, predicate trust, retained-index initialization, node errors, cache publication and source-order policies do not change. The existing `selected` distinction must remain: initial projection evaluates all items in source order, whereas selected provenance completion evaluates only the final item for each overwritten alias. Do not reuse output row maps, scratch slices that escaped into retained rows, nested containers, or stored main index data.

`eval` creates independent list/map outputs and clones bindings for comprehensions/predicates; whole borrowed node results are frozen by the existing call. Retain that protection and explicitly test it. Workers currently set `rowOrders=nil`; final result order is reconstructed only after task join. Reusing a binding map must not enable shared row-order tracking, change UTF16 alias identity or add internal provenance entries to this expression binding. A completed source task may hand the same scanner to a later remaining-phase task, but no two tasks may use it simultaneously. Preserve `runDistinctTasks` join-before-cursor-close; do not clear a scanner map while its worker is active. Clearing the value to nil on all exits avoids carrying a borrowed slot into a later advance or keeping it alive after a failed request.

## Most informative next verification

First, implement only this allocation-lifetime change in an isolated candidate. Run direct scanner counterexamples covering literal/property/general expressions, whole-node return, nested map/list and comprehension shadowing, duplicate aliases with an earlier throwing expression, selected-phase final-alias behavior, unknown properties includinggraph_id, qualified provenance across all sources, and UTF16/surrogate keys. Include source-task cancellation during evaluator callbacks, subsequent fresh wave reuse and panic cleanup. Verify that completed rows retain owned values after the next advance and Close. Run existing main source corruption/read-order/caches and generic DISTINCT tests plus race/vet; no source-eligibility relaxation is part of this hypothesis.

Only after correctness passes should root authorize another paired real64 diagnostic with exactly the same full request sequence and fixture history. Primary prediction: the new-binding-map allocation site falls from O(consumed candidates × projected items) to O(source scanners), while source plan, cache states and complete responses stay the same. Total request allocation should fall substantially from3759881000 bytes. A large reduction in elapsed/CPU time is plausible but remains unmeasured. If the map hotspot remains, or total allocation/time fails to improve, the hypothesis is falsified or insufficient; preserve that result rather than adding another change to rescue the experiment. Keep the dense control and all full response/provenance checks. No single paired run establishes P95 or the overall10x target.
