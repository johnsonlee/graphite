# Remaining public oracle differences

Read-only review against pinned main identifies two independent follow-ups.
Neither is implemented by Attempt 21. Public oracle exceptions must be removed
when each behavior is corrected; the current green test is not full equivalence.

## Source constructor

`QueryPipeline.kt:317–319` checks unique graph IDs during construction, before
query parsing and execution (`CypherExecutor.kt:501`). Its error is
`IllegalArgumentException: Graph ids must be unique`. Go `engine.go:42–50`
currently groups duplicate IDs, empty IDs and nil stores into one differently
formatted error. Main's graph value has no corresponding nonempty-ID constraint.

Before changing this guard, capture actual main for duplicate IDs plus invalid
syntax/cancellation, both source-scope markers, one/two empty IDs, empty sources
and a shared store with distinct IDs. Restore the exact ordering and error
contract rather than preserving the extra native empty-ID restriction by default.
Update `cross_test.go` and remove the six duplicate-source oracle exceptions.

## Unknown labels in general execution

Main `QueryPipeline.kt:373–377` routes a branch containing an unknown node label
to general execution before root source pruning and optimized paths. Its ordinary
node candidate selection (`:4140–4167`) prioritizes Method anywhere in the labels,
then resolves the first label; an unknown first label yields no candidates.
All labels/properties are checked after a candidate is read (`:4448–4475`).
Go `engine.go:160` and `traversal.go:127` currently do not reproduce this complete
dispatch/candidate contract. Review shared `scan.go` and `early_match.go` consumers
when changing it; the old `candidate_slot_test.go` expectation must be corrected
using actual main evidence. Remove the two malformed unknown-label exceptions
only after the full path is aligned.

Do not implement a blanket empty result. Label ordering affects reads:
Unknown:CallSite can be empty without node reads, whereas CallSite:Unknown reads
typed nodes first. Method in either position changes candidate selection.
Earlier MATCH clauses, OPTIONAL null bindings, COUNT over empty input, subsequent
UNWIND/projections and other UNION branches still execute. General LIMIT evaluation
can fail first. General elementId seek (`:551–557`, `:627–630`) reads the sought
node before resolving its label. Relationship targets (`:4426`) likewise read
neighbors before matching the target label. Existing bindings require the same
ordering as original main.

Capture these boundaries on actual main, including single/cross graph, scope
markers, bad sources inside/outside root selection, multiple patterns/MATCH,
relationships, UNION/UNION ALL and zero/positive LIMIT. Current malformed oracle
WHERE false and WHERE 1/0=0 queries are unlabeled; only WHERE true has NoSuchLabel.
They do not prove unknown-label/false-predicate behavior.
