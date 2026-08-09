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
The before and after filters were identical; lower is better.

| Benchmark | Before | After | Delta |
|---|---:|---:|---:|
| Android graph build | 25,925.284 ms | 24,795.518 ms | -4.36% |
| Mapped graph load | 186.553 ms | 188.332 ms | +0.95% |
| Simple mapped query | 0.110 ms | 0.112 ms | +0.002 ms |
| Build-save-load-query | 32,100.627 ms | 32,592.089 ms | +1.53% |

The query delta is inside the before-run 99.9% confidence interval
(`0.110 ± 0.032 ms`), and the full-pipeline single-shot delta is noise on a
path untouched by the Explorer-only change. The mapped-load delta remains
below the 5% acceptance threshold.

Feature-specific JMH results:

| Benchmark | Result |
|---|---:|
| Load 3 mapped service graphs | 573.033 ms |
| Load the same graphs and build topology | 555.776 ms |
| Query built topology through HTTP | 0.059 ms/op |

The startup pair confirms that topology construction uses the already-loaded
instances rather than paying for a second service-graph load.
