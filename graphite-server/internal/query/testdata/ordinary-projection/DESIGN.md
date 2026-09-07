# Ordinary projection: evidence and execution design before implementation

Effective native base: `53f3e7561d504b6773fec3f2a4f3b02a421a0e23`, exactly `bbdfae2a07b629c40d1ef0db6d156bff500989c9` plus the frozen DISTINCT patch SHA256 `5fee9b67291155baddb3a1db4889d0980e299bd1a0c4a5c7923ee671267fa787`. This baseline commit is an independent worktree snapshot; the root and prior freeze are unchanged.

Main: `4e328b0109e13c896b74004823fb049fcb19251a`, Java 17.0.18. The first evidence set runs 20 history scenarios, 55 queries per JVM, in three independent JVMs. Complete JSON values are identical across the three processes. Raw bytes differ because the diagnostic `Map.of` state object's member order differs; result row order is never normalized. The native-before capture runs the unchanged effective base. Capture mode is explicitly not acceptance verification.

## Concrete counterexamples

The target is `MATCH (n) WHERE n.caller_name CONTAINS 'other' RETURN n.caller_name AS x LIMIT 1`. The bad fixture has a corrupt unprojected CallSite return-type SID, not a corrupt caller_name.

| Context | Main cold target | Retained state after first target | Second identical target |
| --- | --- | --- | --- |
| Single bad graph | IndexOutOfBoundsException | true | success, x=other, metadata single |
| Cross clean a / bad b | IndexOutOfBoundsException | false,false | failure again |
| Cross bad a / clean b | IndexOutOfBoundsException | false,false | failure again |
| Graph route a, bad a / clean b | IndexOutOfBoundsException | true,false | success from a |
| Graph route a, clean a / bad b | success from a | true,false | success from a |

On the native base all five cold scenarios fail twice and do not publish the projection retained marker. This includes graph-route clean a / bad b, where main must never consume b.

A successful DISTINCT warmup retains both indexes. The cross clean/bad target then succeeds because retained small-index strategy selects serial source consumption, stopping at clean a. The cross bad/clean target still fails: ordinary global multi-source raw projection is not eligible merely because both indexes are retained. A zero-hit cross DISTINCT warmup releases both markers, restoring the cold failure. A zero-hit single-source DISTINCT warmup retains its index and allows the target to succeed.

## Proposed execution layers

1. **AST planning.** Recognize main's mandatory single-node MATCH/complete direct-string WHERE/nonaggregate RETURN/literal LIMIT fast shape independently of projection eligibility. Keep inline properties, OPTIONAL, relationship/path patterns, WITH, ORDER/SKIP, star, residual predicates and unsupported wrappers on the existing generic path unless main supplies a separate explicit accelerated branch. Use AST structure, not query spelling.
2. **Retained-only ordinary projection.** `executeIndexedStringProjectionRows` allows CallSite or untyped Node, disallows untyped sources containing Annotation, and requires every predicate and projection property to be one of the four CallSite strings. Single source tries the retained capability without initializing it. Multiple sources require graph-scoped/prefer-persisted execution and all required retained indexes. This direct path reads each requested SID only, not the node header or unrelated fields.
3. **Cold candidate generation and consumption.** Failure of direct projection falls through within the same logical request. Candidate strategy can load/build a retained index before yielding the first decoded node; a subsequent decoder or projection failure does not roll back a successful initialization. Serial raw candidates, preferred persisted candidates, preferred mapped views, and parallel cold candidates have different initialization policies. Prepared sidecar presence must never set retained state by itself. Candidate nodes are decoded lazily, then projected; no whole-core certificate may move a later node error ahead of an earlier consumed projection error.
4. **Forty-source leading projection.** Main separately permits a bounded raw leading probe for short CONTAINS terms and small LIMIT, and an already initialized mapped-view leading projection for supported shared matchers. These support graphId in the leading projection but are not the retained-only ordinary API. A failed bounded probe can decline after inspecting its bounded prefix; its side effects and errors must remain visible before the next branch. The mapped-view path reads all four raw integers before materializing required strings.
5. **Source scheduling.** Below 40 sources, retained small-index strategy may select serial consumption; otherwise eligible property/literal projections use complete speculative waves. At 40+, consume a leading graph, then main's source-ordered rolling window, canceling/joining only its unconsumed suffix when LIMIT is reached. Ordinary rows preserve multiplicity and originating provenance; they do not use DISTINCT's later-source provenance reconciliation. Concurrency tests assert consumed-prefix rules and joined cancellation, not a single scheduler interleaving as a universal result.
6. **Lifecycle.** Add explicit read-only access to retained and initialized-view state, with separate initialization operations for candidate policy. Reuse the frozen representation readers and lifetime lock. Publish only completed initializations; retain successful initialization across downstream failures; keep raw fallback and mapped view distinct from retained state. Close/cancel must retain the prior safety guarantees.

## Source anchors

Pinned `QueryPipeline.kt`: fast filtered limit 1292, direct filter 1539, ordinary rows 1637, leading projection 1837, retained-only projection 1891, strategy predicates 2003/2051, task runners 2409/2479/2572, and direct candidates 2838.

Pinned `MappedWebGraphBackedGraph.kt`: ordinary projection 745, initialized mapped-view projection 773, bounded raw projection 801, candidate dispatch 906, candidate fallback and initialization 971, retained/prepared capabilities 1707. Pinned `MappedCallSiteStringIndex.kt`: retained strategy 114 (`retainedBytes <= 1 MiB`), ordinary `projectRows` 163, selective property reads and result-cache publication.

## Necessary classification question

The corpus's full-node corrupt SID currently surfaces as native CypherException with an implementation error string, while main throws IndexOutOfBoundsException. Initialization/consumption semantics can be tested separately, but exact ordinary error outcomes will need a narrow typed string-table-reference decoder cause and query-boundary mapping, analogous to the existing node-tag change. Store's existing error text should remain intact for other consumers. This is a specific dependency to report before implementing, not permission to rewrite unrelated format errors.

The target remains complete parity; this block does not declare the declined generic executor differences solved, and uses no 64-graph or performance runtime.
