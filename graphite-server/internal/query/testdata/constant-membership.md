# ResourceValueNode Constant membership

Main `4e328b0109e13c896b74004823fb049fcb19251a` defines ResourceValueNode as a ConstantNode implementation. NodePropertyAccessor resolves the labels Constant and ConstantNode to that interface. Native previously recognized only kind names ending in Constant, omitting resource values.

The production change is one return expression in query/properties.go: include ResourceValueNode when matching either Constant label. No node sequence, Store accessor, labels() output, property value, resource-file membership, parser, or materialization behavior changes. This patch is independent of the EAGER encounter-order fix and is based on native `f85418822fbc01e902e08514b4e25aea2a517ef2`.

The complete main oracle contains 26 queries, each repeated three times with stable columns, rows, or errors. It covers Constant/ConstantNode/case variants, ID 28's whole resource value and properties, both multi-label orders, WHERE, OPTIONAL, UNWIND, aggregation, resource-file exclusion, and cross-graph provenance. Explicit ORDER BY makes complete supertype enumeration comparisons independent of main MAPPED Class identity ordering. The captured fixture is EAGER; class membership itself is independent of load mode.

Regenerate from the Go module directory:

```
python3 internal/query/testdata/regenerate-constant-membership.py /path/to/graphite-explore.jar
```

The corpus records the exact main commit, jar digest, and Java 17.0.18+0 Homebrew ARM64 target. A separate test verifies the concrete Constant membership of all 16 node kinds. Full module race tests and vet pass. No benchmark, 64-graph runtime, or commit was performed. EAGER grouping, MAPPED identity-based ordering, and LIMIT 0 remain separate work.
