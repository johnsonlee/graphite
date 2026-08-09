# Explorer topology graph

Graphite Explorer can derive a service-level call topology from all webgraphs
configured at startup. The `--graph` arguments are the service catalog; no
separate catalog or second graph load is required.

```bash
graphite serve --data /data/graphs \
  --graph orders:orders-graph \
  --graph billing:billing-graph \
  --topology /rules/company-topology.cypher
```

At startup Graphite:

1. loads every configured service graph into `GraphRegistry` once;
2. acquires leases for those same graph instances;
3. executes the topology Cypher over their read-only qualified union;
4. aggregates result rows into an immutable in-memory topology graph; and
5. serves that snapshot from `GET /api/topology`.

The topology graph is not persisted beside the service graphs. Dynamic graph
loads, replacements, and unloads rebuild the snapshot from the current
registry without reloading unchanged graphs.

## Query contract

A topology query must return these aliases:

- `sourceGraph`: id of a loaded caller graph;
- `targetGraph`: id of a loaded provider graph.

It may return:

- `protocol`: relation category, defaulting to `call`;
- `operation`: API or operation name;
- `weight`: positive integral weight, defaulting to `1`;
- `evidence`: human-readable evidence for the match.

Rows are aggregated by `(sourceGraph, targetGraph, protocol)`. Weights are
summed, operation and evidence values are deduplicated, self-relations are
discarded, and graphs with no relations remain as isolated topology nodes.
Unknown graph ids, missing aliases, invalid weights, invalid Cypher, and more
than 100,000 combined rows fail startup instead of publishing a partial graph.

One file can express multiple company-specific rules with Cypher `UNION`. The
single `--topology` option can also point at a directory when keeping rules in
separate sorted `.cypher` files is more maintainable.

## JMH verification

The comparison used the Android fixture on the same JDK 17 process settings.
The before and after filters were identical; lower is better. These are smoke
measurements, not a statistically powered performance comparison.

| Benchmark | Before | After | Observed delta |
|---|---:|---:|---:|
| Android graph build | 25,925.284 ms | 24,795.518 ms | -4.36% (one SingleShot observation) |
| Mapped graph load | 186.553 ms | 188.332 ms | +0.95% |
| Simple mapped query | 0.110 ms | 0.112 ms | +0.002 ms |
| Build-save-load-query | 32,100.627 ms | 32,592.089 ms | +1.53% |

The `-4.36%` build value is not evidence that this change made Android graph
building faster. It is one observation from one fork and one SingleShot
measurement per revision, without repeated interleaved before/after forks.
Moreover, this Explorer-only change does not modify the graph-build path. The
result is therefore reported only as a smoke check that did not reveal a gross
regression in that run; no causal build improvement is claimed.

The query delta is inside the before-run 99.9% confidence interval
(`0.110 ± 0.032 ms`). The build-save-load-query result is also a single-shot
observation on a path this change does not modify. The mapped-load delta is
below the 5% smoke-check threshold, but should likewise not be interpreted as
a precise effect size.

Feature-specific JMH results:

| Benchmark | Result |
|---|---:|
| Load 3 mapped service graphs | 573.033 ms |
| Load the same graphs and build topology | 555.776 ms |
| Query built topology through HTTP | 0.059 ms/op |

The startup pair is consistent with topology construction reusing loaded
instances, but its ordering should not be read as a negative topology cost.
Reuse is established structurally by `TopologyService` acquiring leases from
`GraphRegistry`; this short run is only a regression smoke check.

## Heap baseline

`TopologyHeapBenchmark` loads three mapped copies of the Android-scale graph,
forces GC, records that loaded-service-graph heap as the baseline, and then
builds controlled topology snapshots at three scales. A 1 ms sampler records
the build-window peak. The retained value is recorded after forced GC while
the resulting `TopologyService` and immutable snapshot remain strongly
reachable. GC work is in JMH invocation fixtures and is excluded from the
reported build time.

The table reports the median of three measured SingleShot invocations after
one warmup invocation, on JDK 17 with `-Xmx8g`. Heap deltas are calculated
within the same fork and invocation sequence; absolute heap values are not
subtracted across JVM processes.

| Matched rows | Relations | Build p50 | Loaded graphs baseline p50 | Sampled peak delta p50 | Retained delta p50 |
|---:|---:|---:|---:|---:|---:|
| 100 | 10 | 5.312 ms | 215.9 MiB | below sampler/TLAB resolution | 0.021 MiB |
| 10,000 | 100 | 30.517 ms | 216.0 MiB | 22.0 MiB | 1.170 MiB |
| 100,000 | 1,000 | 129.479 ms | 216.2 MiB | 222.0 MiB | 11.623 MiB |

At the enforced 100,000-row limit, the measured total used heap peaked at
about 438.2 MiB and settled at about 227.8 MiB after GC: a 222.0 MiB transient
increase and an 11.623 MiB retained increase over the three-graph baseline.
The transient is dominated by Cypher result materialization; the retained
snapshot is bounded by relation count and the 100 operation/evidence values
kept per relation.

This controlled query isolates row and snapshot scaling. A company rule that
scans different node types or computes more complex expressions can have a
different transient profile, so these numbers are a reproducible baseline,
not a universal production heap guarantee. Run it with:

```bash
./gradlew :explore:jmh \
  -Pjmh.filter='TopologyHeapBenchmark.android_buildTopologyHeap'
```
