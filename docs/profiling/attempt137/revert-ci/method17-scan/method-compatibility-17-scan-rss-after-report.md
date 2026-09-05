### Method discovery 17-graph scan RSS after query

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=contains]` | 3150807040.0 # | 3302526976.0 # | +4.8% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=or]` | 3126579200.0 # | 2720440320.0 # | -13.0% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=regex]` | 3069059072.0 # | 3119738880.0 # | +1.7% | - | 15% | **PASS** |
