# Independent C/D/E lazy consumer review

Status: final targeted review passed. All four C/D/E production files and all sixteen provider files match their frozen manifests exactly; see `final-production-receipt.json`. This is a bounded correctness review, not a claim of complete parity or performance acceptance.

Author worktree: `/tmp/graphite-go-lazy-cde-87aaf0ad`. Independent copies and snapshots are under this directory. No author/root production files were edited, no commit was created, and no real64 or performance run was started. The main executable is the pinned 4e328b0109e13c896b74004823fb049fcb19251a JAR, SHA256 91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d, running Java17.0.18. Go validation uses Go1.22.0 darwin/arm64.

## Confirmed defect and fix

The first candidate restricted a scoped bounded generic scan by graphId except when the constraint map was empty/conflicting. That is narrower than main. `QueryPipeline.tryFastFilteredNodeLimit` invokes `nodeCandidates(type,candidateSources)` before the general graph-scope recursion. `nodeCandidates` at lines4580–4590 ignores candidateSources when the pipeline is not qualified and scans the original graph. This behavior applies both to contradictory constraints and to a single graphId that does not select the scoped graph.

Independent actual-main example:

`MATCH (n) WHERE n.graphId='absent' AND substring('x','bad')='x' RETURN n.id AS x LIMIT 1`

Main scoped execution throws the complete Java String-to-Number ClassCastException; the original candidate returned columns[x],rows[]. Adding DISTINCT or changing the graphId to'b' has the same scoped discrepancy. Qualified absent-source queries return empty; qualified'b' queries consume the matching source and throw. `route-oracle/main-wire.json` contains all eight full actual-main responses. `route-before.log` preserves all four original scoped failures. The author removed the graphIds-empty qualification specifically for nonqualified bounded generic consumption; candidate and unbounded routing retain their distinct main rules. The final independent replay passes all eight without sorting, exception substitution, or reducing the denominator.

## Other reviewed execution boundaries

- DirectStringConjunction chooses required filters according to main's equality preference and evaluates its direct residual after candidate full-node decoding. Generic necessary candidates preserve the original residual evaluation, including eager operand errors. The author's separately captured unknown-property and duplicate-filter cases are included in final validation.
- Streaming unbounded generic iteration evaluates node constraints, WHERE, and projection in source order without pre-materializing the whole scan. Bounded generic non-DISTINCT stops at accepted LIMIT. Qualified DISTINCT continues consuming sources and projections to finish provenance; scoped DISTINCT can stop after its visible limit.
- Source workers own returned Node values. Lazy projection freezes values before handing rows between stages; workers do not share the row-order map, and final column order is rebuilt after join. Existing task orchestration retains cancellation and join responsibility.
- Independent eight-source ordinary and DISTINCT tests cancel the active request, then immediately issue a fresh request and close the Store. They verify a concrete result value and ensure canceled worker contexts do not poison a later request. The required early cancellation checkpoint is asserted to fire rather than treating an untriggered cancellation as a pass. Race repetitions passed.

## Legacy test migration

The original corrupt-native corpus remains80 records. An independent audit reproduces prior indexed/ordinary admissions and the already-existing typed SID error conversion before considering the new lazy admission. Seventy-two records gain lazy admission in that test's planner-only classification; exactly eight have changed complete expected responses after the prior typed error mapping. Every migrated record has one and only one original main fixture/name/cross match, with identical query text. The test still checks complete class/message or columns/rows, and still executes its original candidate-scan comparison.

The first independent audit compared to the raw historical native file without applying the provider's existing typed error conversion and incorrectly counted54 changed results. That failed audit log is retained as `initial-independent-race.log`; its corrected source and successful replay are retained separately. It was an audit-baseline mistake, not a production failure and not an excuse to discard any case.

## Surrogate oracle boundary

`audit_wire.py` and `wire-audit.json` independently compare all480 existing semantic/wire records. Exactly12 changed leaves occur inside result rows; no query, parameter, column, error class, or message changes. Every difference is Java UTF-8 encoding of a lone D800 as one'?', rather than Go JSON decoding of escaped D800 as U+FFFD. The semantic originals remain preserved. Two actual Javalin HTTP200 bodies have verified hashes and contain the same'?' output, and the direct Java units oracle records55296 before wire encoding. This supports the output-encoding correction; it does not authorize converting query input parameters to'?'.

The480 fixture oracle is still a direct executor/serializer comparison, not480 actual HTTP transactions. The two HTTP observations verify this particular encoding boundary. Test numeric JSON normalization is not treated as a proof of arbitrary nonfinite-number or large-number serialization parity.

## Final validation scope

Targeted independent race replay, repeated3, covers the complete480 new corpus, original C8/D8/E6 (22 records),96 conjunction-edge records,36 small source-wave records, the eight new routing counterexamples, planner/cancellation protocol tests, and the complete original80 corrupt-native regression. The corrected independent migration and eight-source cancellation checks also passed repeated10. Query/store vet passed. Exact commands, copied test source, snapshots and raw logs are retained.

The author's full1048 reclassification remains separate evidence; this review does not narrow that denominator. B-group pagination/ordering, F-group process-dependent Class identity order, global budget admission, and precise JVM GraphWork/interruption schedules remain explicit compatibility work. No result here establishes all concurrent schedules, complete100% parity, or any performance improvement.

The final production-only follow-up changes the ordinary generic stop condition to `p.bounded && len(rows) >= p.limit`, removing an artificial Int.MAX_VALUE limit from unbounded D execution. The exact one-line delta and the subsequent complete480/original22/independent regression replay plus vet are preserved. No two-billion-row fixture or synthetic performance claim was introduced.
