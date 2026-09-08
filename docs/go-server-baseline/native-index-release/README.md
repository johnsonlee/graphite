# Index release lifecycle

The real64 cold replay first diverged in structural retention after
`class-contains-or-unlabeled-zero` (case index 4). The preceding ordinary query
had loaded a preferred persisted index. Main kept that index when the DISTINCT
query found no rows; Go unconditionally removed its retained marker.

`ReleaseLifecycleOracle.java` runs original main operations using the pinned
main Explore JAR (revision `4e328b0109e13c896b74004823fb049fcb19251a`). It shares
reflection and file-hash helpers with `../native-index-lifecycle/IndexLifecycleOracle.java`.
The three tiny fixture histories are correctness controls, never performance data.
Each executes seven operations and records complete public query responses,
before/after structural state, and persisted index bytes.

The original native implementation fails both preferred-persisted and newly
built trigram retention. The candidate records the preferred-persisted lifetime
policy, releases request caches while retaining those structural indexes, and
resets the policy on explicit benchmark clear. Ordinary prepared indexes still
release; clear followed by preparation verifies that retention does not leak
across invocation boundaries. Existing immutable handles keep their established
store lifetime.

Run `python3 verify.py` to compare the native capture against actual main.
All 21 operations, 45 structural/file observations, and four public responses
match. Main's two raw cache counters are zero throughout; the comparison checks
that precondition and omits those unavailable native counters explicitly.

The first local test invocations also used an incorrect relative output path.
Their logs are preserved. The isolated baseline replay and final candidate use
absolute output paths and preserve complete records. This change does not
resolve mapped posting-range validation differences or establish any P95 claim.
