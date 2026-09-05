### Method discovery 17-graph scan CPU time

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=contains]` | 2960000000.0 # | 2930000000.0 # | -1.0% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=or]` | 1950000000.0 # | 3170000000.0 # | +62.6% | - | 15% | **FAIL** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=regex]` | 3450000000.0 # | 3430000000.0 # | -0.6% | - | 15% | **PASS** |
