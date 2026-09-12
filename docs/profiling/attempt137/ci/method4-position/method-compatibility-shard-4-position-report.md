### Method discovery 4-graph position wall time

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=early]` | 365.356 ms/op | 406.574 ms/op | +11.3% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=middle]` | 383.827 ms/op | 429.805 ms/op | +12.0% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=zero]` | 266.483 ms/op | 247.188 ms/op | -7.2% | - | 15% | **PASS** |
### Method discovery 4-graph position CPU time

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=early]` | 850000000.0 # | 1050000000.0 # | +23.5% | 960000000.0 -> 990000000.0 # (+3.1%) | 15% | **NOISE** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=middle]` | 860000000.0 # | 1130000000.0 # | +31.4% | 840000000.0 -> 1040000000.0 # (+23.8%) | 15% | **FAIL** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=zero]` | 530000000.0 # | 620000000.0 # | +17.0% | 590000000.0 -> 660000000.0 # (+11.9%) | 15% | **NOISE** |
### Method discovery 4-graph position RSS after query

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=early]` | 2131099648.0 # | 2119720960.0 # | -0.5% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=middle]` | 2121953280.0 # | 2207293440.0 # | +4.0% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=zero]` | 2067800064.0 # | 2132774912.0 # | +3.1% | - | 15% | **PASS** |
### Method discovery 4-graph position RSS query delta (advisory)

This metric is reported for context and does not block the regression gate.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=early]` | 34557952.0 # | 38350848.0 # | +11.0% | - | 15% | **INFO** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=middle]` | 34541568.0 # | 209956864.0 # | +507.8% | - | 15% | **INFO** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=zero]` | 5144576.0 # | 165838848.0 # | +3123.6% | - | 15% | **INFO** |
