### Method discovery 17-graph scan RSS query delta (advisory)

This metric is reported for context and does not block the regression gate.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=contains]` | 422830080.0 # | 696541184.0 # | +64.7% | - | 15% | **INFO** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=or]` | 418967552.0 # | 228925440.0 # | -45.4% | - | 15% | **INFO** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=regex]` | 420974592.0 # | 388747264.0 # | -7.7% | - | 15% | **INFO** |
