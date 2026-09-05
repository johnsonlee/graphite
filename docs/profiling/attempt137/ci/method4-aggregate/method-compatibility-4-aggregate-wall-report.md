### Method discovery 4-graph aggregate wall time

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=count]` | 381.044 ms/op | 398.352 ms/op | +4.5% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=order]` | 487.376 ms/op | 437.219 ms/op | -10.3% | - | 15% | **PASS** |
