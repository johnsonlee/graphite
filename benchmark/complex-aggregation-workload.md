# Complex aggregation candidate workload

This candidate workload adapts the **query shapes** of analytical graph workloads to Graphite's code graphs. Its target is the P95 latency of slow queries and complex aggregations under representative multi-graph load, especially the production observation of tens-of-seconds P95 with 50+ graphs. It is not a verbatim LDBC implementation, an official LDBC result, or a reproduction of that production incident.

The inspiration is [LDBC SNB BI](https://ldbcouncil.org/benchmarks/snb/bi/), which stresses aggregation and joins over large portions of a graph; its [public Cypher implementation](https://github.com/ldbc/ldbc_snb_bi) is a reference for workload design. The six queries here are original adaptations to CallSite properties and physical DATAFLOW edges, not translations with an asserted one-to-one mapping to numbered LDBC queries.

## Status and execution boundary

[`complex_aggregation_queries.json`](complex_aggregation_queries.json) is a separate candidate catalog, **not yet part of the trusted 73-case controller or its acceptance claims**. Entries contain `id`, `query`, `shape`, and `purpose`. Keeping this stage separate avoids turning uncharacterized analytical queries into an unbounded CI critical path. The existing catalog and historical measurements remain unchanged.

All six queries put `LIMIT 100` only on final ordered output. None truncates the input or intermediate rows before the essential aggregate, join, or DISTINCT. This bounds transport volume, not computation. A query's shape does not establish that it is slow on a particular dataset: actual degree, cardinality, skew, overlap and concurrent interference determine its cost.

Production measurements must execute each query through the native `/api/cypher` endpoint over the declared set of 50+ real persisted graphs and fully consume the body. Record actual source identities and `graphCount`; inspect contributing graphs and source work as well. Merely loading many graphs while selecting one is not a multi-graph load test. These queries do not invent edges between independently stored graphs: DATAFLOW traversal stays within a source, while the global aggregates merge contributions across sources.

## Six query shapes

| ID | Code-analysis meaning | Work exercised | Required semantic checks |
| --- | --- | --- | --- |
| `bi-caller-dependency-rollup` | Calls and distinct target classes per graph/caller | Group by graph/caller/target, then sum counts and count groups | Same caller name in different graphs remains separate; repeated calls increase calls, not distinct target count |
| `bi-global-target-support` | Target-class popularity across the entire source set | Local caller/target aggregation followed by global counts and graph DISTINCT | Identical target strings merge across graphs; count caller–graph pairs, not caller strings alone |
| `bi-two-hop-dataflow` | Two-step dataflow paths starting at CallSites | Two relationship joins, path multiplicity, distinct starting and ending nodes | Preserve parallel-edge multiplicity, relationship uniqueness and graph-qualified node identity |
| `bi-dataflow-degree-histogram` | Distribution of nonzero CallSite DATAFLOW outdegrees | Join rows, group by source node, then group by degree | Counts outgoing edges, including parallel edges; zero-degree sources are excluded here |
| `bi-dataflow-optional-sinks` | Per-graph CallSite counts, outgoing edges and sinks | OPTIONAL MATCH, per-node count, CASE, second aggregate | Keep zero-degree sources; `count(b)` for an unmatched optional row is zero; unrelated edge types do not count |
| `bi-dependency-co-use` | Target classes used together by the same caller class | `collect(DISTINCT)`, double UNWIND, pair filtering, global aggregation | An unordered target pair contributes once per caller–graph pair; graph support is distinct across those contributions |

The co-use query preserves the full combinatorial work. The execution engine may stream and compact aggregation state without materializing every intermediate row. If a caller has `d` distinct target classes, double UNWIND considers `d²` ordered combinations before the predicate keeps `d(d−1)/2` unordered pairs. This is a structural workload property, **not a measured cost or speedup**. Likewise, two-hop fan-out depends on real degree distributions; a fixture with almost uniform degree one will not exercise skew merely because the query has two hops.

Native `Method` nodes are virtual metadata and have no physical adjacency. The initial catalog therefore does not invent `(CallSite)-[:CALL]->(Method)` edges. Any future Method enrichment must use the supported signature/source identity semantics and receive its own correctness coverage.

## Correctness evidence

`backend/cypher/tests/complex_aggregation_workload.rs` executes all six catalog entries against a deterministic property graph in both Graphite and OpenCypherM23 dialects, plus empty input checks. The modeled `graphId` properties are not native multi-source leases and do not prove native scope or performance.

The nonempty fixture has two components with repeated caller names and shared target classes. It includes duplicate CallSite values, parallel DATAFLOW edges, zero-degree CallSites and a CALL edge that must not count as DATAFLOW. Expected columns and complete ordered scalar results are explicit:

- Target X: 5 calls, 4 caller–graph pairs, support from 2 graphs; target Y: 2 calls, 2 pairs, 2 graphs.
- Two-hop paths: gA has 4 paths, 2 distinct starts and 2 distinct destinations; gB has 1 of each.
- Degree histogram: gA has two degree-1 sources and one degree-3 source; gB has two degree-1 sources.
- OPTIONAL totals: gA has 4 sources, 5 edges, 1 sink; gB has 3 sources, 2 edges, 1 sink.
- Co-use XY: 2 caller–graph pairs, support from 2 graphs.

Graphite currently returns floating-point SUM for integer inputs; OpenCypherM23 preserves integer SUM. The test explicitly checks those different types rather than normalizing them. Counts remain integer in both dialects. Empty grouped input produces no rows with the declared columns.

A separate correctness-only native execution smoke on **two real persisted Tika shards** completed all six queries with HTTP 200, complete consumed bodies, `graphCount=2`, and nonempty output within the final 100-row bound. It is an execution/scope check, not a complete independent real-data oracle and not a performance run. The native binary was reused from PR 211's local evidence; its backend source/manifests/lockfile closure matched the initial assessed main revision `58215b98`. This historical smoke does not claim source identity with the later optimized branch HEAD. See [`complex-aggregation-smoke/summary.json`](../docs/query-performance-evidence/2026-11-11/complex-aggregation-smoke/summary.json) for binary identity and response hashes. Full response snapshots are retained beside it. No latency or throughput was collected.

Run the deterministic semantic checks with:

```bash
cargo test --locked -p graphite-cypher --test complex_aggregation_workload
```

## Admission to the P95 workload

Before adding these entries to the trusted controller:

1. Validate their real-data meaning on the chosen production-representative source set. Record graph/node/edge/CallSite counts, maximum and distribution of degrees, group cardinality, same-name overlap, and response sizes. Preserve zero-result cases, but do not treat them as evidence of exercising an expensive join.
2. Establish a complete result oracle or independently verified expectations, including ordering, duplicates, absent/null fields and provenance. The tiny fixture proves semantics; HTTP success on two real shards does not replace this oracle.
3. Run a fast correctness-only producer-to-consumer handoff before a costly run: restore prepared inputs, load them, execute all candidates and consume complete responses. Reuse unchanged reviewed artifacts and identify the source closure being reused. Do not change graph formats or build/save just to add query cases.
4. Declare the workload and protocol before measurement: every request covers multiple real graphs; include the slow cases regardless of P50; fix concurrency, arrival pattern, sampling, cache state, parameter/graph set, timeouts and resource settings. Keep cold, warm and changed-workset observations separate. The existing four-worker test has validation gaps and must not be relabeled sustained saturation.
5. Evaluate **P95 absolute milliseconds and uncertainty per case**, with matched baseline/candidate runs, full results and all failures retained. P50 is diagnostic only. A faster P50, mean or overall pipeline cannot compensate for worse P95. Profile separately to avoid counting profiling overhead in acceptance samples. Do not sum stage P95 values to estimate total P95.
6. Keep correctness, availability, cancellation/recovery and the CPU/RSS constraints: each resource may increase at most 5% against the matching baseline unless the user has explicitly approved a scoped exception. The separate local-query controller now records CPU and kernel peak RSS with the boundaries documented in `README.md`; the trusted CI controller's correctness/OOM gate does not establish those performance limits. Keep those local observations separate from any future official policy change. JAR JVM settings remain 4 GiB, with the hard 8 GiB ceiling on all JVMs.
7. Change the reviewed controller catalog, case counts, protocol coverage assertions, documentation and meaningful tests together. Per `README.md`, a PR cannot replace its own trusted target-base harness; new official workload policy becomes effective after landing through that workflow. Required CI correctness/OOM checks and production-representative performance acceptance are separate results.

Do not automatically repeat a newly discovered tens-of-seconds query for every candidate and every round before understanding the cost. First use correctness and path diagnostics to estimate the full critical path, then run the predeclared P95 experiment. Timeout, OOM, rejection and partial output remain failures or explicitly reported capacity outcomes; never remove them to improve the successful-request percentile.

The missing production query texts and workload distribution remain material: these six entries extend complexity coverage, but cannot establish that the production tens-of-seconds incident has been reproduced or fixed.
