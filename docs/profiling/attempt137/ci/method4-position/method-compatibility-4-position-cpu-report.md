### Method discovery 4-graph position CPU time

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=early]` | 850000000.0 # | 1050000000.0 # | +23.5% | 960000000.0 -> 990000000.0 # (+3.1%) | 15% | **NOISE** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=middle]` | 860000000.0 # | 1130000000.0 # | +31.4% | 840000000.0 -> 1040000000.0 # (+23.8%) | 15% | **FAIL** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=zero]` | 530000000.0 # | 620000000.0 # | +17.0% | 590000000.0 -> 660000000.0 # (+11.9%) | 15% | **NOISE** |
