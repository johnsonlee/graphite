### Method discovery 36-graph scan RSS query delta (advisory)

This metric is reported for context and does not block the regression gate.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=contains]` | 923500544.0 # | 874053632.0 # | -5.4% | - | 15% | **INFO** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=or]` | 469606400.0 # | 888057856.0 # | +89.1% | - | 15% | **INFO** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=regex]` | 1064263680.0 # | 807755776.0 # | -24.1% | - | 15% | **INFO** |
