### Method discovery 4-graph aggregate wall time

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=count]` | 381.044 ms/op | 398.352 ms/op | +4.5% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=order]` | 487.376 ms/op | 437.219 ms/op | -10.3% | - | 15% | **PASS** |
### Method discovery 4-graph aggregate CPU time

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=count]` | 670000000.0 # | 890000000.0 # | +32.8% | 890000000.0 -> 1050000000.0 # (+18.0%) | 15% | **FAIL** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=order]` | 1230000000.0 # | 1060000000.0 # | -13.8% | - | 15% | **PASS** |
### Method discovery 4-graph aggregate RSS after query

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=count]` | 2224050176.0 # | 2120589312.0 # | -4.7% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=order]` | 2266288128.0 # | 2177679360.0 # | -3.9% | - | 15% | **PASS** |
### Method discovery 4-graph aggregate RSS query delta (advisory)

This metric is reported for context and does not block the regression gate.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=count]` | 34029568.0 # | 38076416.0 # | +11.9% | - | 15% | **INFO** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=4,scenario=order]` | 289886208.0 # | 121569280.0 # | -58.1% | - | 15% | **INFO** |
