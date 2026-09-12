### Method discovery 36-graph scan wall time

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=contains]` | 4294.5 ms/op | 4304.5 ms/op | +0.2% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=or]` | 2429.1 ms/op | 2500.0 ms/op | +2.9% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=regex]` | 5062.4 ms/op | 4987.5 ms/op | -1.5% | - | 15% | **PASS** |
### Method discovery 36-graph scan CPU time

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=contains]` | 5000000000.0 # | 5880000000.0 # | +17.6% | 5110000000.0 -> 6020000000.0 # (+17.8%) | 15% | **FAIL** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=or]` | 4210000000.0 # | 4380000000.0 # | +4.0% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=regex]` | 6620000000.0 # | 5880000000.0 # | -11.2% | - | 15% | **PASS** |
### Method discovery 36-graph scan RSS after query

A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence
interval overlap, and blocks only when the confirmation also exceeds 15%.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=contains]` | 4417040384.0 # | 4371660800.0 # | -1.0% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=or]` | 4198289408.0 # | 4162678784.0 # | -0.8% | - | 15% | **PASS** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=regex]` | 4598263808.0 # | 4473135104.0 # | -2.7% | - | 15% | **PASS** |
### Method discovery 36-graph scan RSS query delta (advisory)

This metric is reported for context and does not block the regression gate.

| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |
|---|---:|---:|---:|---:|---:|:---:|
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=contains]` | 923500544.0 # | 874053632.0 # | -5.4% | - | 15% | **INFO** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=or]` | 469606400.0 # | 888057856.0 # | +89.1% | - | 15% | **INFO** |
| `cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=36,scenario=regex]` | 1064263680.0 # | 807755776.0 # | -24.1% | - | 15% | **INFO** |
