# Actual main DISTINCT capability boundary

This bounded correctness oracle executes the public Cypher executors in actual
main `4e328b0109e13c896b74004823fb049fcb19251a`, using Java 17 and the existing
JAR SHA256 `91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d`.
The five synthetic persisted graph variants have no corrupt records. They are
not performance workloads; no timings, speedups, allocation, or CPU measurements
were taken.

The earlier generic prefix investigation observed that an initial graph with
only LocalVariable nodes can throw `Distinct projection capability became
unavailable`. These are valid public outcomes, not rejected fixtures. This
matrix separates that initial projection capability failure from later
provenance collection, supported empty results, and planner fallbacks.

`cases.json` declares 46 scenarios with full query text, parameters, ordered
`sources: [{fixture, id}]`, redundant ordered fixture names for replay, and
descriptive `boundary` metadata. The source counts are 1, 2, 39, and 40. Each
source is an independent fresh persisted graph copy; source IDs are `g00`,
`g01`, and so on. Single-source cases use `CypherExecutor`; multiple sources use
`CrossGraphCypherExecutor` with source scope false and a maximum work budget.
No parallelism override is applied.

`CapabilityFixture.java` uses actual main's writer, an explicit persisted node
encounter order, and native load-generated sidecars. The variants are empty,
generic-only, a nonmatching CallSite plus generic nodes, a matching CallSite
plus generic nodes, and a nonmatching CallSite alone. Generic nodes have names
`hit-A` and `hit-B`. All queries use the six-property wrapped OR and term `hit`,
except the declared global-miss control. The main fixture archive has exactly
83 regular file members named `variant/file`. `mutations.json` is empty.

The actual public results are:

| Boundary | Main result |
| --- | --- |
| Initial empty or generic-only graph, 1 or 40 sources | Capability-unavailable failure |
| Same initial graph, 2 or 39 sources | Success; results depend on label and later sources |
| Initial graph has a nonmatching CallSite | Success at all four source counts |
| Empty or generic-only graph after a selected prefix, 2/39/40 sources | Success |
| Generic-only initial graph with LIMIT 0, 1/40 sources | Empty success |
| Generic-only initial graph with LIMIT 2, 1/40 sources | Capability-unavailable failure |
| CallSiteNode label on initial empty/generic-only graph | Same 1/40 failure versus 2/39 success boundary |
| Explicit SKIP 0 or 1 on generic-only initial graph | Same 1/40 failure versus 2/39 success boundary |
| LocalVariable label or unsupported indexed `n.id` projection, one source | Successful other planner path |
| Generic-only initial graph, 40 sources, term absent from entire string table | Capability-unavailable failure |

Thirty scenarios succeed. The sixteen failures all have the exact public
`java.lang.IllegalStateException` class and message `Distinct projection
capability became unavailable`; there are no partial result rows. The raw JSON
retains ordered columns and rows, provenance, exact error messages and stacks,
all eight diagnostics, execution phase, and before/after retained/mapped states.

The storage distinction is explicit in the pinned source frozen inside each
capture's `input-sources.tar.gz`:

- `MappedWebGraphBackedGraph.kt:369` first returns null for unsupported type,
  empty/unsupported predicates, or unsupported projection properties. The
  public planner can decline this indexed path before making that call.
- After valid capability arguments, an already retained index or exact empty
  match/preflight result can return a supported list, including an empty list.
  A serial raw scan can also return an empty list with zero CallSite nodes.
- The split raw helper at line 481 returns an empty list for nonpositive LIMIT
  or an explicitly empty selected set, but returns null for ineligible counts
  (including fewer than its 4,096-node threshold or LIMIT at least node count).
  Those broader large-count thresholds are source observations, not exercised
  by this small matrix.
- The final `callSiteStringIndex` fallback at line 2222 returns null when the
  CallSite count is zero. It also has separate size, persistence-budget, and
  reservation failure branches; those resource boundaries are not tested here.
- `QueryPipeline.kt:211` uses a parallel work consumer for one source, serial
  storage for 2–39 default sources, and split storage for at least 40. The
  ordinary filtered-LIMIT path supplies its mapped-view preference; the indexed
  SKIP path at line 3359 uses the default false preference. The observed zero
  CallSite failure boundary remains the same under both preferences.
- Initial projection at `QueryPipeline.kt:2191` requires a non-null provider
  response and throws the observed error if it is absent. Later provenance
  at line 2303 treats a null CallSite projection as empty and can still consume
  generic candidates. LIMIT 0 returns before either operation.

Two independently compiled JVM captures, with independently written graphs and
fresh case copies, are retained in `main-capture` and `repeat-capture`. All 46
public outputs, all array orders, error stacks, and before/after states match
exactly as parsed JSON. Forty-five scenarios match every field. The exception
is `first-neutral-call-only-40`: `workUnitsConsumed` is 3,089 in the first run
and 3,361 in the repeat. Its other seven diagnostics are identical. This graph
requires progressing beyond an empty leading result into a worker wave, but
the oracle alone does not prove the precise cause of the differing work count.
Both observations are retained; no retry or normalization hides the difference.

The independent writers also differ in the timestamp comment in five
`forward.properties` files; their other 78 files are byte identical. Every
original per-case fixture file is unchanged during execution. Generated named
CallSite index sidecars are separately recorded. Complete commands, exit codes,
class hashes, JAR/JDK/source input identities and audits are archived.

Run `python3 run.py /absolute/fresh/output` to reproduce. Run `python3 verify.py`
to independently verify the frozen archive, input sources, fixtures, and exact
public repetition while explicitly checking the retained diagnostic difference.
This establishes a bounded capability contract, not full server fidelity or
real64 P95 acceptance.
