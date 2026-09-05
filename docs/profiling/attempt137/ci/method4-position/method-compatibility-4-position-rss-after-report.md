### Method discovery 4-graph position RSS after query

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=early]` | 2131099648.0 # | 2119720960.0 # | -0.5% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=middle]` | 2121953280.0 # | 2207293440.0 # | +4.0% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=zero]` | 2067800064.0 # | 2132774912.0 # | +3.1% | - | 15% | **PASS** |
