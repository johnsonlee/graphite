### Method discovery 4-graph string wall time

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=late]` | 433.964 ms/op | 407.194 ms/op | -6.2% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=prefix]` | 690.716 ms/op | 751.401 ms/op | +8.8% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=suffix]` | 720.493 ms/op | 743.375 ms/op | +3.2% | - | 15% | **PASS** |
