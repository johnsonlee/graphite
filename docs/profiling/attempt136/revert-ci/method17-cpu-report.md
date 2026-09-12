### Method discovery 17-graph aggregate CPU time

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=count]` | 1830000000.0 # | 1860000000.0 # | +1.6% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=order]` | 1740000000.0 # | 2130000000.0 # | +22.4% | 1940000000.0 -> 3230000000.0 # (+66.5%) | 15% | **FAIL** |
