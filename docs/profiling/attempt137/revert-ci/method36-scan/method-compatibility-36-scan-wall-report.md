### Method discovery 36-graph scan wall time

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=contains]` | 4294.5 ms/op | 4304.5 ms/op | +0.2% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=or]` | 2429.1 ms/op | 2500.0 ms/op | +2.9% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=regex]` | 5062.4 ms/op | 4987.5 ms/op | -1.5% | - | 15% | **PASS** |
