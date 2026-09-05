# Attempt 138 combined source review

No concrete correctness defect found in the added `MappedWebGraphBackedGraph.kt` wiring and sparse initial projection. This was a read-only source review, not build/test or performance validation. The reviewer did not change either production file or the workspace.

The graph file contains exactly **79 added lines**. The view contains **131 added lines**. Independent line comparison confirms no existing lines were changed in either file; the two intended files are the only main/JMH differences from frozen main. Existing whole-view validator, heap path, raw projection/scan bodies and scheduler bodies are therefore unchanged. Exact hashes are in `combined-review.json`; `git diff --check` passed for both production files.

## Eligibility and ordering

The entry point still validates CallSite type, predicates and supported projected properties first. New paths require the preferred mapped-view consumer and a usable mapped view; retained heap indexes keep the existing path. The preferred marker extends Split, so exact predicate string IDs are available for the initial path under the same supported matcher restrictions.

For validated directories, `exactMatchesCanFillLimit == false` means the sum of selected posting occurrences is below LIMIT. OR overlap and duplicate terms can inflate this count, so they can prevent an optimization but cannot cause an excessive candidate set to pass the bound. The distinct projected row count cannot exceed union-node count, which cannot exceed summed occurrences. No exact DISTINCT cardinality is inferred from a positive result. Structural inconsistencies are excluded by the existing full view validation; mismatched/unusable matching inputs still cause `matchingNodeIds` to return null.

`matchingNodeIds` validates every selected posting's full physical order eagerly, before returning its lazy merge. A bad range returns null before any projected rows escape; the original raw fallback remains. The lazy merge preserves physical encounter order and deduplicates repeated node IDs, so the new projection can preserve insertion order while deduplicating projected ID tuples. It does not sort by numeric node ID.

## Projection and fallback semantics

Projection IDs retain column order, including repeated/reordered properties. Immutable local `List<Int>` keys distinguish the raw tuple, while negative property indexes use the existing -1/null convention for supported null properties and graphId placeholders. An empty projection produces one empty tuple if the candidate set is nonempty. The unchanged QueryPipeline supplies each source's actual graphId and merges complete provenance; no source selection or result-order policy changed.

The selected-values entry is evaluated before predicate-wide discovery, but only the view's complete-four-property specialization can return a mapped result. Partial selected projections return null and continue the original discovery/raw/retained fallback. Empty selections and unsupported shapes preserve the original result semantics. If a later selected posting is invalid after earlier internal rows were found, `selectedProjectionHits` returns null; those partial rows are discarded and fallback starts normally. Budget and cancellation exceptions propagate instead of being converted to empty results or fallback.

## Work and Kotlin sequence termination

The existing producer accounts directory access, full selected-range validation and merge work. The new consumer additionally accounts each projected candidate and flushes in its own `finally`. Projection counter increments only on a non-null mapped-path result. Those changed mechanism counts are expected and do not demonstrate latency improvement.

A Kotlin sequence's producer `finally` is **not guaranteed to execute when its consumer breaks or throws while the producer is suspended at yield**. This implementation does not rely on that guarantee: the existing merge explicitly flushes accounting immediately **before every yield**, so it has no pending work at suspension. No file/channel/task is held across that yield that requires closing; mapped cursor references and transient arrays can be discarded. The projection consumer's own `finally` flushes its pending work after its body throws, and exceptions raised inside the producer execute the producer's existing `finally`.

Under the valid sparse eligibility bound, the number of candidate nodes is already below LIMIT, so the consumer's LIMIT break is normally unreachable; it remains a defensive check. Normal exhaustion completes the producer and its `finally`. Explicit per-node interruption checks plus existing validation/merge polling preserve cancellation behavior, though the original cancellation/work tests and new path-specific tests still need execution by root.

No validator, persisted format, retained global cache, thread pool or StringTable change was introduced by this wiring. Final correctness and performance acceptance remain unproven until the planned tests, real-data controls and gates complete.
