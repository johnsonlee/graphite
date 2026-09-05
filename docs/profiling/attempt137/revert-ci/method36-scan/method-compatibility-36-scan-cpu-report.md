### Method discovery 36-graph scan CPU time

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=contains]` | 5000000000.0 # | 5880000000.0 # | +17.6% | 5110000000.0 -> 6020000000.0 # (+17.8%) | 15% | **FAIL** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=or]` | 4210000000.0 # | 4380000000.0 # | +4.0% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=regex]` | 6620000000.0 # | 5880000000.0 # | -11.2% | - | 15% | **PASS** |
