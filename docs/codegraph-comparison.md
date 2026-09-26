# Graphite and CodeGraph: measured differences

Graphite makes program relationships queryable: combine predicates, traverse value relationships, and compute aggregates through MCP. This page compares Graphite 2.8.0 with [colbymchenry/codegraph](https://github.com/colbymchenry/codegraph) 1.6.0 using real published JVM projects.

| Task | Graphite | CodeGraph 1.6.0 |
| --- | --- | --- |
| Query interface for agents | Cypher patterns, filters and aggregation through MCP | Dedicated search, callers, callees, impact and source-context tools; no general SQL/Cypher MCP tool |
| Kotlin property access invoking a Java method | Verified calls to FqName.isRoot() in five IrPluginContextImpl paths | Those source methods were indexed, but the corresponding edges were absent |
| Count a high-fan-in method's callers | One aggregate query returns the full count | Dedicated MCP callers tool caps responses at 100, without a pagination parameter |
| Commons Lang caller lookup, persistent MCP | 0.106–0.111 ms median across five runs | 0.255–0.294 ms median across five runs |

## Compiler-established semantics in a real project

In Kotlin compiler 2.0.21, `fqName.isRoot` is property syntax for a call to the Java method `org.jetbrains.kotlin.name.FqName.isRoot()`.

In `org/jetbrains/kotlin/backend/common/extensions/IrPluginContextImpl.kt`, five paths use this property: `resolveMemberScope`, `referenceClass`, `referenceTypeAlias`, `referenceFunctions`, and `referenceProperties`. Graphite identifies these callers. CodeGraph indexed the source methods but its persisted graph contained no edges from that file to an `isRoot` node. Source inspection and `javap -c -p` independently confirmed the calls.

The full requests returned 87 bytecode caller signatures from Graphite and 10 source callers from CodeGraph. These counts are not a recall score: compiler-generated methods and source methods have different identities. The five checked paths establish a concrete semantic difference. [Raw responses](codegraph-comparison/kotlin-compiler-mcp.json) · [Source, SQL and bytecode audit](codegraph-comparison/kotlin-semantic-check.json)

## Programmable queries for high-fan-in methods

For Guava 33.4.0-jre, this query returns 1,016 distinct bytecode callers:

```cypher
MATCH (c:CallSiteNode)
WHERE c.callee_class = 'com.google.common.base.Preconditions'
  AND c.callee_name = 'checkNotNull'
RETURN count(DISTINCT c.caller_signature) AS callers
```

CodeGraph's database contained 997 distinct source caller nodes for these definitions, while its MCP callers tool returned 100 entries with a “100 found” heading. Its handler clamps the requested limit to 100. Graphite's default row response also has a cap, but reports truncation explicitly; aggregation computes the count without returning every caller. [Raw responses](codegraph-comparison/guava-mcp.json) · [Aggregate query and SQL counts](codegraph-comparison/guava-counts.json)

The different counts reflect different graph models and are not presented as a coverage score. CodeGraph's SQLite database remains accessible to a separately written client; the comparison here concerns the tools exposed to agents through MCP.

## Repeated equal-result performance measurement

Input: Apache Commons Lang 3.17.0 sources for CodeGraph and the corresponding binary JAR for Graphite. Question: callers of `StringUtils.isBlank`. Both returned the same nine source method identities; Graphite included JVM signatures and CodeGraph included source locations.

Hardware: Apple M3 Max, 64 GiB RAM, macOS 14.3. Five independently started server pairs; each run used five warmups followed by 30 measured requests per tool, alternating request order. Measurements cover persistent MCP stdio request/response, including serialization, excluding startup. Watchers and CodeGraph telemetry were disabled.

| Per-run result range | Graphite | CodeGraph |
| --- | ---: | ---: |
| Median latency | 0.106–0.111 ms | 0.255–0.294 ms |
| P95 latency | 0.128–0.177 ms | 0.403–0.458 ms |

Graphite had 2.38–2.65× lower median latency in this particular workload. This is a selective caller lookup, not a general speedup claim or a large-scale saturation test. [All five runs](codegraph-comparison/commons-lang3-mcp-five-runs.json) · [Environment](codegraph-comparison/environment.json)

## Reproduce the MCP measurement

Install Graphite 2.8.0, CodeGraph 1.6.0 and Python 3. Download the Commons Lang binary and source JARs listed in [inputs.json](codegraph-comparison/inputs.json), verifying their SHA256 values. Extract the source JAR's Java files into `corpus/source/`, then run:

```bash
codegraph init --yes corpus/source
graphite build commons-lang3-3.17.0.jar -o corpus/graph
python3 docs/codegraph-comparison/measure_mcp.py corpus
```

Use `GRAPHITE_BIN` and `CODEGRAPH_BIN` to select specific executables. The script writes requests, raw responses, individual timings and summaries to `corpus/mcp-comparison.json`. Run it five times, preserving each output, to repeat the process-level experiment. Verify the nine callers before interpreting timings.

## Scale evidence

Graphite also built a 3,458,328-node graph from the Kotlin compiler artifact and served the selective caller query above. Its binary includes dependencies absent from the source archive, so build time and node counts are not directly comparable. The [64-graph public demonstration](public-scale-demo.md) and README's 100M+ production figures document larger Graphite deployments separately; they are not head-to-head CodeGraph measurements.
