### Method discovery 4-graph string CPU time

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=late]` | 1020000000.0 # | 910000000.0 # | -10.8% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=prefix]` | 1110000000.0 # | 1400000000.0 # | +26.1% | 1290000000.0 -> 1550000000.0 # (+20.2%) | 15% | **FAIL** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=suffix]` | 1260000000.0 # | 1340000000.0 # | +6.3% | - | 15% | **PASS** |
