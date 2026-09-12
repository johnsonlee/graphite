### Method discovery 4-graph aggregate CPU time

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=count]` | 670000000.0 # | 890000000.0 # | +32.8% | 890000000.0 -> 1050000000.0 # (+18.0%) | 15% | **FAIL** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=order]` | 1230000000.0 # | 1060000000.0 # | -13.8% | - | 15% | **PASS** |
