### Method discovery 17-graph scan wall time

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=contains]` | 2371.7 ms/op | 2330.4 ms/op | -1.7% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=or]` | 1358.9 ms/op | 1513.3 ms/op | +11.4% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=regex]` | 2711.9 ms/op | 2722.2 ms/op | +0.4% | - | 15% | **PASS** |
### Method discovery 17-graph scan CPU time

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=contains]` | 2960000000.0 # | 2930000000.0 # | -1.0% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=or]` | 1950000000.0 # | 3170000000.0 # | +62.6% | 2180000000.0 -> 3410000000.0 # (+56.4%) | 15% | **FAIL** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=regex]` | 3450000000.0 # | 3430000000.0 # | -0.6% | - | 15% | **PASS** |
### Method discovery 17-graph scan RSS after query

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=contains]` | 3150807040.0 # | 3302526976.0 # | +4.8% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=or]` | 3126579200.0 # | 2720440320.0 # | -13.0% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=regex]` | 3069059072.0 # | 3119738880.0 # | +1.7% | - | 15% | **PASS** |
### Method discovery 17-graph scan RSS query delta (advisory)

This metric is reported for context and does not block the regression gate.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=contains]` | 422830080.0 # | 696541184.0 # | +64.7% | - | 15% | **INFO** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=or]` | 418967552.0 # | 228925440.0 # | -45.4% | - | 15% | **INFO** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=regex]` | 420974592.0 # | 388747264.0 # | -7.7% | - | 15% | **INFO** |
