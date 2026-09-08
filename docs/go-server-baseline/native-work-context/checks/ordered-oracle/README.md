# Actual main ordered property LIMIT controls

These 25 scenarios, each with one public execute operation, independently check
the ordered property projection route against pinned main
`4e328b0109e13c896b74004823fb049fcb19251a`. They reuse the existing actual-main
WorkOracle and the five four-LocalVariable fixture variants (80 files), with
fresh actual-main writer output and a fresh graph copy for each scenario.
There are no performance measurements or changes to previous captures.

Both JVM runs exited zero. Their complete parsed records are exactly equal,
including ordered rows/columns, exceptions and stacks, before/after state and
all eight diagnostic counters. All 400 original fixture files per run remain
unchanged. Receipts, source/JDK/JAR identities, compiled classes, fixture
archives and original outputs are retained in `main-capture` and
`repeat-capture`. `archive-supplement.py` verifies both inputs and copied bytes,
and refuses to replace existing root output.

The observations distinguish an admitted ordered heap from generic processing:

| Case family | Actual main diagnostics / result |
| --- | --- |
| Alias ORDER, LIMIT 1/2/10000 | Fast 1, fallback 0, work 4 |
| LIMIT 10001 | Fast 0, fallback 1, work 4 |
| LIMIT 0, including corrupt first record | Empty success, fast 1, work 0 |
| Negative LIMIT -1 | Fallback 1, work 4, exact negative-count error |
| Numeric/bad string, parameter, expression, boolean LIMIT | Fallback 1, work 4; complete results retained |
| Float LIMIT 1.9 | Fast 1, work 4, one returned row |
| Duplicate alias, WITH, SKIP 0, non-alias ORDER expression | Fallback 1, work 4 |
| Multiple sort keys or all-equal sort keys | Fast 1, work 4; deterministic tie encounter order preserved |
| First node offset missing | Fast 1, work 3 |
| All offsets missing | Empty success, fast 1, work 0 |
| Bad first SID | Decode error with fast 0, fallback 0, work 0 |
| Budget 3 for four-node ordered scan | Budget error with fast 0, fallback 0, work 3 |

The WHERE control has fast 1 and fallback 0. Non-optional WHERE is a separate
clause in main's adapter, so this query is rejected by the four-clause
`OrderedPropertyLimitQuery` compiler; another fast route handles it. Overall
fast diagnostics alone therefore do not identify which specialized route ran.

Main's `OrderedPropertyLimitQuery.compile` at QueryPipeline.kt:948 requires
unique projected property columns, sort expressions naming those columns, a
numeric literal bound of at most 10000, and a supported simple node pattern.
The heap loop at line 830 visits every candidate, preserves encounter order on
ties, and records the fast-path diagnostic only after returning successfully
to the planner. The bad-record and exhausted-budget cases verify that timing of
the diagnostic increment; they do not accept merely relabelling fallback work.

The `LIMIT $limit` parameter is parsed by WorkOracle with Gson Map.class, hence
its numeric input is a Java Double. The Go test separately mirrors this input
conversion, while keeping context budget/diagnostic longs exact with UseNumber.
No expected rows, failures or counters are removed from the replay.

`TestExecutionContextMainOrderedOracle` replays all 25 cases. These bounded
checks establish neither every ORDER expression/type combination nor real64
performance or full server fidelity.
