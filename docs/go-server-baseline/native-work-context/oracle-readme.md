# Actual main request work context and cancellation oracle

This correctness oracle executes pinned main
`4e328b0109e13c896b74004823fb049fcb19251a` using Java 17 and the existing
JAR SHA256 `91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d`.
It captures 51 scenarios containing 129 ordered operations. No synthetic
performance measurements are made. The deprecated CLI work-budget option is
not enabled or changed; these are the actual context, budget, tracker and
public executor APIs.

`cases.json` is an ordered list of `{name, mode, budget, fixture, operations}`.
Budgets and consumed work units are decimal strings so the entire signed Java
long range survives readers without floating-point conversion. Modes distinguish
constructor checks, a private tracker contract, public explicit-context
executions, public budget-only executions, and an unbudgeted public control.
Every operation records its original specification, before/after context
snapshot, return value or exact error class/message/stack. Failed executes have
no result value or partial rows. Snapshots include all eight actual diagnostic
counters, remaining budget, cancellation status and current cancellation reason.

`WorkOracle.java` constructs the real main classes. Its reflected
`getWorkTracker$cypher` getter obtains the same tracker owned by the context;
reflection invokes the original `consume`, `checkCancelled` and `record*`
methods. No main implementation is replaced or reimplemented by the oracle.
The archived `javap.stdout` records the actual JVM method surface.

Budget-only `CypherExecutor` does not expose the tracker created for each
execute. Its diagnostics are explicitly unavailable in these records, rather
than inferred, set to zero, or obtained by replacing the tracker factory.
Repeated actual public results prove the per-execute reset behavior. Explicit
contexts do expose cumulative snapshots across repeated use of one executor
and across newly constructed sequential executors.

The operation matrix covers:

- Budget construction with zero, negative one, one and `Long.MAX_VALUE`.
- Negative, zero, exact, sequential and oversized consumption; an oversized
  attempt exhausts the remaining budget. Zero consumption after exhaustion
  succeeds until cancellation is set; the next positive consume fails.
- Default, timeout and custom cancellation reasons, first-reason identity,
  repeated cancellation, and cancellation observed across new executors.
- Negative consume validation before cancellation; maxRows validation before
  parsing/cancellation; syntax parsing before the executor's cancellation
  check. A valid cancelled query fails before pipeline diagnostics advance.
- All eight counters. The explicit counter sequence ends with selections 3,
  pruning executions 2, sources pruned 5, conflicts 1, fast paths 2, filtered
  limit fast paths 1, fallbacks 1, and consumed work 2. Invalid source-count
  arguments do not mutate counters. The record methods remain usable after
  cancellation and do not implicitly check it.
- Shared explicit context consumption, budget-only reset after success and
  failure, graph-free work without graph budget charges, and shared UNION ALL
  budget failure without partial results.

The fixture is a small persisted graph written by actual main, containing four
LocalVariable nodes with names `alpha`, `beta`, `gamma`, `delta`, IDs 10, 20,
30, 40 and explicit node encounter order. The baseline query is:

```cypher
MATCH (n:LocalVariable) WITH n RETURN n.name AS name
```

Main records one general fallback execution and four work units for this
query. Adding `LIMIT 1` after WITH still consumes four units. Calling its
public maxRows overload with zero also consumes four units, then returns no
rows. These observed behaviors prohibit a blanket early return at maxRows
zero or charging only the returned row. Graph-free RETURN and UNWIND examples
advance fallback diagnostics without consuming graph work.

Four additional variants alter only declared name SIDs or node offsets after
actual main writes the graph and prepares its ordinary sidecars. Exact byte
mutations are in `mutations.json`. These verify the order surrounding
`WorkTrackingSequence.next`: it checks the underlying iterator's `hasNext`,
then charges one unit, then calls `next`. Mapped node iteration can decode or
skip missing records during that `hasNext` call.

| Exhausted-budget / missing-record control | Actual main result |
| --- | --- |
| First node has invalid name SID | Decode `IndexOutOfBoundsException` precedes budget failure |
| First offset missing, second node has invalid name SID | Same decode error precedes budget failure |
| First offset missing, next node valid | Budget error when charging the first yielded node |
| All offsets missing | Empty success despite exhausted budget |
| First offset missing, three work units available | Three returned rows and exactly three charged units |

This mapped-iterator boundary does not imply that explicit tracked node seeks
or other storage paths charge work at the same point. Their coverage remains
separate from this oracle.

One bounded private-tracker operation starts four internal workers against a
100-unit tracker. Both captures show exactly 100 successful consumes and four
budget-exceeded worker terminations, with the same diagnostic snapshots.
Individual worker shares are 39/15/5/41 in the first capture and 32/25/36/7 in
the repeat. Those four differing raw fields are retained and explicitly
audited. No separate public executions concurrently reuse a request context.

The two independent JVM captures have 50 completely equal scenario records;
the only differences in the 51st are those four worker shares. Every ordered
public result, constructor outcome, error and stack, cancellation observation,
and diagnostic snapshot repeats exactly. Five fixture variants contain 80
regular files; the independent writers differ only in five timestamp comments
in `forward.properties`. Each run's 816 original case fixture files remains
unchanged, with no added or missing files.

`main-capture` and `repeat-capture` retain full outputs, compile/run receipts,
JDK/JAR/source identities, generated class hashes, source archives, original
fixture archives, and before/after audits. Root `main.json`, `fixtures.tar.gz`,
`fixture-variants.json`, and `mutations.json` are byte-exact copies from the first
capture for Go regression use. Run `python3 run.py /absolute/fresh/output` to
reproduce, or `python3 verify.py` to verify the archived evidence independently.
This matrix does not establish HTTP guard timing arbitration, every graph-work
charging path, full server fidelity, or real64 P95 acceptance.
