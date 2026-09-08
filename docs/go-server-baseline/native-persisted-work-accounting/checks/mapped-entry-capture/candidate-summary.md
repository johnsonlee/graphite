# Main storage entry correction

The original baseline captures remain frozen. Baseline v2 matches E05 and E06
only, retaining 70 field differences across the ten instrumented controls.
Its seven uninstrumented no-action controls have 54 differences against main,
but are identical to their instrumented counterparts on every available common
field. Baseline v1's overlay vet failure and completed plain capture remain
archived separately.

Both candidate v1 and v2 pass the same ten controls with zero compared
differences, including callback units, work budgets, request-signal identity,
worker interruption, supported live cache state, error class/message, returned
node IDs/kinds, and the phase at which each result occurs. Their seven plain
controls also have zero differences and equal the instrumented counterparts.
Each candidate has 2,575 frozen module files; the actual tracker algorithm is
unchanged, and removing the observer declaration and before/after instrumentation
restores the original file exactly. The original exception object is rethrown
by the observer. The overlay-specific Go vet issue is handled only by
`-vet=off` for that instrumented capture; the plain run uses normal Go vet.
Full production vet is a separate required check.

Only two test files differ between candidate v1 and v2. Candidate v1 already
passed the actual-main comparison before those test corrections: one new test
confused an empty cached ID slice with its nil Go representation, and an earlier
test assumed worker interruption at every yield and at warm construction.
`../entry-test-migration.json` binds these changes and preserves both focused
results. The corrected controls still assert returned IDs, exact interruption
class/message, retained cancellation cause, visited-zero failure, subsequent
periodic-poll behavior, and persistent range state.

The implementation keeps worker interruption distinct from request cancellation.
Main-only entry metadata and ID-cache operations use Store lifetime primitives;
actual loaders and range loops retain their checkpoints and work callbacks.
Cold mapped entry retrieves the real persisted content identity before the
loader's first interruption check. Legacy string identity computation retains
its real work and successful cache before raw identity scanning checks the
worker. Warm mapped entry still checks current regular-file admission before
using its cached view. Retained Split lookup checks all predicates' exact-match
eligibility first, visits them in order, and returns an empty CallSite child
before charging a node-cache hit when all string matches are empty. Prefix and
suffix predicates are admitted under the retained rules. Generic Annotation
children are preserved.

Scope is the storage-node adapter, not public Cypher execution or a performance
measurement. Kotlin iterator construction is represented by explicit Go adapter
initialization. Full Java node shape, JVM stacks/qualified classes, three private
lookup counters, and private post-Close state remain unavailable in Go and are
listed in each raw capture. Native node objects and native error types remain
in the raw output. These ten controls do not prove all uncached retained
matching, parallel scheduling, server functionality, resource accounting,
real64 replay, or P95 acceptance. The independent 111 public scenarios / 482
operations and complete real64/201-case checks must be read separately.
