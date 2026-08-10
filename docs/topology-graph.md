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
4. aggregates result rows and writes an Explorer-internal topology snapshot;
5. maps that snapshot read-only from `${tempRoot}/graphite/<UUID>/`; and
6. serves that snapshot from `GET /api/topology`.

The topology graph is not persisted beside the service graphs. Its internal
format is deliberately separate from the public WebGraph/`GraphStore` format,
so this feature does not change the public storage version. Each rebuild writes
a new UUID directory; the previous directory is deleted after outstanding HTTP
response leases close. Normal shutdown deletes the current directory. Dynamic
graph loads, replacements, and unloads rebuild from the current registry
without reloading unchanged graphs.

The final snapshot and pre-rendered API JSON are mapped rather than retained as
object graphs on the JVM heap. Cypher result rows and aggregation state are
still transiently materialized while building, so peak build memory depends on
the company query and result shape.

## Query contract

A topology query must return these aliases:

- `source`: id of a loaded caller graph;
- `target`: id of a loaded provider graph.

It may return:

- `protocol`: relation category, defaulting to `call`;
- `operation`: API or operation name;
- `weight`: positive integral weight, defaulting to `1`;
- `evidence`: human-readable evidence for the match.

Rows are aggregated by `(source, target, protocol)`. Weights are
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

Feature-specific JMH results after moving the topology snapshot to mapped
temporary storage:

| Benchmark | First p50 | Repeated p50 |
|---|---:|---:|
| Load 3 mapped service graphs | 542.001 ms | 566.018 ms |
| Load the same graphs and build topology | 555.122 ms | 549.536 ms |
| Query built topology through HTTP | 0.049 ms/op mean | — |

The two startup runs reverse their ordering: the observed build-minus-load
difference changes from +13.121 ms to -16.482 ms. This short SingleShot test
therefore cannot resolve the small topology cost from graph-load and operating
system noise. It did not reveal a stable regression, but it is not evidence of
a speedup or a statistical proof of a sub-1% effect. HTTP latency improved from
the pre-change 0.060 ms/op mean to 0.049 ms/op in the same benchmark setup.
Graph reuse is established structurally by `TopologyService` acquiring leases
from `GraphRegistry`; service graphs are not loaded a second time.

## Heap baseline

`TopologyHeapBenchmark` loads three mapped copies of the Android-scale graph,
forces GC, records that loaded-service-graph heap as the baseline, and then
builds three 100,000-row topology shapes. A 1 ms sampler records the build heap
peak. It reads the mapped API JSON completely so the RSS measurement includes
resident mapped pages. The next invocation records post-GC retained heap and
process RSS while the `TopologyService` remains reachable. It also reports the
exact UUID-directory file size. GC and RSS probes are outside the reported
build/read time.

The table reports the median of three measured SingleShot invocations after
one warmup invocation, on JDK 17 with `-Xmx8g`. Heap deltas are calculated
within the same fork and invocation sequence; absolute heap values are not
subtracted across JVM processes.

| Shape | Relations | Build/read p50 | Heap peak delta p50 | Retained heap delta p50 | Retained RSS delta p50 | Topology files | Mapped JSON |
|---|---:|---:|---:|---:|---:|---:|---:|
| Details-heavy (100 details/relation, 256-byte evidence padding) | 1,000 | 284.270 ms | 298.000 MiB | 0.008 MiB | 35.344 MiB | 69.587 MiB | 33.953 MiB |
| Relation-heavy (one detail on each relation) | 100,000 | 255.335 ms | 242.000 MiB | 0.008 MiB | 15.750 MiB | 28.643 MiB | 15.132 MiB |
| Long strings (100 details/relation, 1 KiB evidence padding) | 1,000 | 694.965 ms | 384.523 MiB | 0.008 MiB | 118.062 MiB | 228.278 MiB | 113.299 MiB |

The old 11.623 MiB synthetic retained-heap result is neither a production
estimate nor an upper bound. With mapped storage, retained JVM heap is nearly
flat, while disk footprint and resident mapped pages scale with relation detail
and rendered JSON size. RSS is inherently noisier than used heap because the
operating system controls mapped-page residency; the table reports the median
of the measured post-GC deltas and should be used as a scenario baseline, not a
guaranteed maximum.

Build peak heap has not disappeared: Cypher currently materializes result rows
and the builder aggregates them before writing the snapshot. A company rule
that scans different node types, computes larger values, or has a different
cardinality can have a different transient profile. Run the reproducible
benchmark with:

```bash
./gradlew :explore:jmh \
  -Pjmh.filter='TopologyHeapBenchmark.android_buildTopologyHeap'
```
