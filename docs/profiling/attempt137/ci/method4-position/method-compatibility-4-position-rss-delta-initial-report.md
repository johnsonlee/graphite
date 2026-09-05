### Method discovery 4-graph position RSS query delta (advisory)

This metric is reported for context and does not block the regression gate.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=early]` | 34557952.0 # | 38350848.0 # | +11.0% | - | 15% | **INFO** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=middle]` | 34541568.0 # | 209956864.0 # | +507.8% | - | 15% | **INFO** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=zero]` | 5144576.0 # | 165838848.0 # | +3123.6% | - | 15% | **INFO** |
