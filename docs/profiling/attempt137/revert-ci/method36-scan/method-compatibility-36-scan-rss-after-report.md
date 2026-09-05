### Method discovery 36-graph scan RSS after query

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=contains]` | 4417040384.0 # | 4371660800.0 # | -1.0% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=or]` | 4198289408.0 # | 4162678784.0 # | -0.8% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=regex]` | 4598263808.0 # | 4473135104.0 # | -2.7% | - | 15% | **PASS** |
