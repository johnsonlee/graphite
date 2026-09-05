# Attempt 138 原 34 查询配对结果独立审计

六份原始 TSV / JMH JSON 已核验。204 个结果的全部 14 个 correctness 字段逐行匹配 oracle，oracle 与 Attempt 137 留存的冻结基线副本字节一致。三组的局部 regression 与每组严格 P95 改善条件通过；10x 为 false，CI 验收尚未建立。此次只读取既有文件，没有运行 Java、构建或新测量。

## 每组 P95 与资源

每份运行有 34 个查询，P95 按 nearest-rank 取升序第 33 个值。六份运行的决定行均为 `global-wide-wrapped-case-insensitive-distinct-dense`。这里不是把三次运行本身算成 P95。

| Pair / 顺序 | P95 base → candidate（ms） | 比值 | CPU（秒） | 峰值 heap（bytes） | 峰值 RSS（bytes） |
|---|---:|---:|---:|---:|---:|
| 1 / candidate-base | 56.735000 → 19.398625 | 2.925x | 1.573797 → 1.275374 | 4706892000 → 4698982256 | 5287346176 → 5221515264 |
| 2 / base-candidate | 53.315166 → 22.332625 | 2.387x | 1.576525 → 1.305471 | 4708784752 → 4411949112 | 5274632192 → 4903976960 |
| 3 / candidate-base | 37.081625 → 20.843541 | 1.779x | 1.445943 → 1.256500 | 4710885464 → 4579727624 | 5273092096 → 5095473152 |

CPU、heap、RSS 三组均降低。P50 第三组从 1.651375 增至 1.908208 ms（+15.55%、+0.256833 ms），仍须保留。正常 wrapped shape 的第一组 P95 从 2.308417 增至 2.449000 ms；两者都未同时超过原 gate 的 +15% 与 +1 ms。所有发布的 P50/P95、wrapped shape P95 和资源分数均与独立重算 / 原始 JMH 分数一致。

## 工作量与访问语义

仅两个 DISTINCT 查询的非延迟列发生变化，三组一致：

| 查询 | graphWorkUnits | parallelScanCount | peakActiveWorkers |
|---|---:|---:|---:|
| wrapped DISTINCT targeted | 106,706 → 2,370 | 2 → 0 | 3 → 0 |
| wrapped DISTINCT dense | 283,544 → 22,365 | 2 → 1 | 不变 |

总工作量每份 base 58,071,626、candidate 57,706,111，减少 365,515；第一行 zero 本身仍为 57,642,093，主导全回放总工作量。逐行的 hitGraphIds、输入 source、访问 graph 列表/数量、executionPath 和 indexLookupCount 均无变化。不能把 indexLookupCount=0 解释为没有 mapped projection；它是既有特定诊断计数。

base 的前三慢行总是 zero / DISTINCT dense / DISTINCT targeted；candidate 总是 zero / DISTINCT dense / four-properties targeted。六份 zero 都是最大值，因 nearest-rank 定义而没有成为 P95。执行路径与工作量变化支持候选确实减少相关工作，但不足以把所有延迟变化归因于单一内部阶段。

## 全部逐行变化

102 个配对观察中，47 个 candidate 更慢；没有任何一项同时超过 +15% 且 +1 ms，因而也没有重复满足该条件的 query ID。以下三项三组都变慢，不能省略：

- `global-wide-class-pair-dense`
- `global-wide-aliased-dense`
- `global-wide-distribution-broad-all-64`

下表保留全部 34 查询的三组 base → candidate 延迟（ms，显示到 6 位）；JSON 保存完整 ns、百分比、慢行标志及每列差异。

| 查询 | Pair 1 | Pair 2 | Pair 3 |
|---|---:|---:|---:|
| `global-wide-four-properties-zero` | 256.072875 → 266.834750 | 260.655458 → 247.018959 | 257.960042 → 257.989959 |
| `global-wide-four-properties-targeted` | 9.843917 → 9.916042 | 9.825583 → 9.800917 | 10.394250 → 10.629792 |
| `global-wide-four-properties-dense` | 4.039166 → 4.027917 | 4.078208 → 3.967334 | 4.124500 → 4.137542 |
| `global-wide-class-pair-zero` | 1.907250 → 1.867833 | 2.071750 → 2.039500 | 2.237167 → 2.103375 |
| `global-wide-class-pair-targeted` | 4.673083 → 4.051791 | 4.045375 → 4.380541 | 4.340209 → 4.172167 |
| `global-wide-class-pair-dense` | 0.978042 → 0.978417 | 0.891542 → 1.012958 | 0.900500 → 0.957500 |
| `global-wide-name-pair-zero` | 2.085584 → 1.928833 | 2.007459 → 1.576000 | 1.651375 → 1.908208 |
| `global-wide-name-pair-targeted` | 4.700292 → 3.982958 | 4.776750 → 4.270458 | 4.088167 → 4.235458 |
| `global-wide-name-pair-dense` | 0.905666 → 0.766583 | 0.838125 → 0.781625 | 0.741959 → 0.828292 |
| `global-wide-caller-class-zero` | 1.340291 → 1.445958 | 1.352959 → 1.322458 | 1.502917 → 1.399875 |
| `global-wide-caller-class-targeted` | 2.924084 → 2.799750 | 2.793209 → 2.967792 | 2.933625 → 2.847750 |
| `global-wide-caller-class-dense` | 0.511750 → 0.654916 | 0.565458 → 0.528000 | 0.597041 → 0.505458 |
| `global-wide-callee-class-zero` | 1.385292 → 1.249459 | 1.416000 → 1.485041 | 1.278458 → 1.245292 |
| `global-wide-callee-class-targeted` | 2.183834 → 2.170250 | 2.164667 → 2.589958 | 2.036250 → 2.028584 |
| `global-wide-callee-class-dense` | 0.459667 → 0.473042 | 0.683167 → 0.482875 | 0.519125 → 0.502334 |
| `global-wide-provenance-zero` | 1.306084 → 1.044416 | 1.068209 → 1.157666 | 1.033792 → 1.206292 |
| `global-wide-provenance-targeted` | 3.138500 → 2.812000 | 3.054250 → 3.079750 | 2.923541 → 2.933167 |
| `global-wide-provenance-dense` | 0.852250 → 0.814042 | 0.889875 → 0.874250 | 0.805833 → 0.813333 |
| `global-wide-aliased-zero` | 0.989083 → 0.929208 | 1.026625 → 1.073250 | 1.000542 → 0.938334 |
| `global-wide-aliased-targeted` | 3.251167 → 3.536500 | 3.273542 → 3.581750 | 3.514417 → 3.206083 |
| `global-wide-aliased-dense` | 0.767458 → 0.787750 | 0.751916 → 0.784167 | 0.737209 → 0.741375 |
| `global-wide-parameterized-zero` | 1.220958 → 1.058292 | 0.940333 → 1.074125 | 0.901292 → 0.953292 |
| `global-wide-parameterized-targeted` | 2.227292 → 2.263458 | 2.150042 → 2.307167 | 2.309417 → 2.225500 |
| `global-wide-parameterized-dense` | 0.754625 → 0.789208 | 0.783084 → 0.822334 | 0.764208 → 0.747750 |
| `global-wide-wrapped-case-insensitive-zero` | 1.114625 → 1.399375 | 1.095792 → 1.281792 | 1.153375 → 1.070958 |
| `global-wide-wrapped-case-insensitive-targeted` | 2.308417 → 2.449000 | 2.500250 → 2.374208 | 2.377542 → 2.253625 |
| `global-wide-wrapped-case-insensitive-dense` | 1.450958 → 1.381208 | 1.449458 → 1.404250 | 1.430250 → 1.439500 |
| `global-wide-wrapped-case-insensitive-distinct-zero` | 3.245541 → 3.096791 | 3.243000 → 3.328417 | 3.243708 → 3.108875 |
| `global-wide-wrapped-case-insensitive-distinct-targeted` | 28.644500 → 2.547541 | 32.279792 → 3.011500 | 23.301375 → 2.743500 |
| `global-wide-wrapped-case-insensitive-distinct-dense` | 56.735000 → 19.398625 | 53.315166 → 22.332625 | 37.081625 → 20.843541 |
| `global-wide-distribution-broad-all-64` | 0.780875 → 0.801375 | 0.803125 → 0.845750 | 0.782375 → 0.813792 |
| `global-wide-distribution-localized-early` | 1.219791 → 1.388292 | 1.256541 → 1.305375 | 1.347042 → 1.319417 |
| `global-wide-distribution-localized-late` | 4.145500 → 3.986667 | 4.137750 → 3.930083 | 4.018875 → 3.842833 |
| `global-wide-distribution-localized-middle` | 2.989709 → 3.013000 | 2.882458 → 2.858500 | 2.716125 → 2.822667 |

## 输入与结论边界

六个保存的命令均为既有 `LargeBroadQueryPressureBenchmark.replayBroadQueries`、64 graphs、global-wide、indexState=cold、一个 fork/measurement、零 warmup、Java 17 CPU=4、GC profiler 与 verify oracle。每次开始为冷索引，回放后续查询会复用先前初始化的索引；这不是每个查询都冷，也不是稳定 JIT 状态的证明。

候选记录 JAR SHA 与独立构建收据一致；本次没有重新 hash 整个 JAR 或真实图文件。14 字段包含 workload identity、outcome、row count、response bytes、digest；此次验证既有结果签名，没有重新执行完整行 oracle。全部文件 hash、102 组逐行比较、top 3、资源与工作量明细见 `independent-old34-audit.json`，可用 `independent-old34-audit.py` 重算。

本轮局部进展有直接证据，但不是最终验收：三组 P95 比值约 2.925x / 2.387x / 1.779x，都未达到 10x。必须保留后续真实 v3 与 exact-head CI 的独立约束，不据本结果宣称稳定 CI 收益。
