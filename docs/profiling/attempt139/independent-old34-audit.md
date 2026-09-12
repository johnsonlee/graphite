# Attempt 139 原 34 查询配对结果独立审计

**139 按预案拒绝，不能以 comparator 的 regressionPassed=true 判作成功。** 第二组不满足每组严格 P95 改善，且显著变慢。已独立核对六份 TSV/JMH JSON、204 个完整14字段 correctness签名、原始资源分数和逐行变化；结果与保存状态一致，但拒绝结论来自原始数字及额外 progress要求。未进行重测或启动 Java/build。

## 三组 P95 与资源

每份运行有34条查询，nearest-rank P95是升序第33个值；不是三次运行之间的P95。

| Pair / 顺序 | P95 base → candidate（ms） | 比值 | base决定行 → candidate决定行 | CPU（秒） | 峰值heap（bytes） | 峰值RSS（bytes） |
|---|---:|---:|---|---:|---:|---:|
| 1 / candidate-base | 118.032459 → 112.874042 | 1.045701x | DISTINCT dense → DISTINCT dense | 1.748474 → 1.545742 | 4707272760 → 3848620960 | 5242175488 → 4349771776 |
| 2 / base-candidate | 58.594458 → 137.031292 | 0.427599x | DISTINCT dense → DISTINCT dense | 1.424759 → 1.463312 | 4714048784 → 3840744152 | 5248040960 → 4345102336 |
| 3 / candidate-base | 54.235000 → 49.776750 | 1.089565x | DISTINCT targeted → DISTINCT dense | 1.446098 → 1.226040 | 4308588208 → 3712829360 | 4859772928 → 4393041920 |

第二组 dense 行为58.594458→137.031292ms，增加78.436834ms（+133.863913%）；P95比值约0.427599x，直接违反所有配对都必须改善的预设条件。第三组base的P95决定行为targeted，而candidate为dense，不能将六份P95都称为dense。

CPU第一/三组分别降低11.59%/15.22%，第二组增加2.71%；heap/RSS三组均下降。资源改善不能替代P95进展要求。P50三组都增加（完整值保存在JSON），不能省略这些非目标行的观察。

## 正确性、工作及来源

六份完整14字段签名按顺序匹配34行oracle，204个结果全部success；oracle与之前Attempt137留存的冻结副本字节一致。queryCount/successCount均34、failure/timeout均0、graphCount均64；TSV行数、唯一ID、positive latency、rowCount、query workload identity和digest全部检查。这里核验既有完整摘要，没有重新执行查询或重建全行结果。

每对全部非latency TSV字段均相同，包含hitGraphIds、source/access图列表及数量、executionPath、parallelScanCount、indexLookupCount、peakActiveWorkers和graphWorkUnits。六份总work都为58,071,626，其中第一条zero57,642,093、DISTINCT targeted106,706、DISTINCT dense283,544。字节码移除callback边界没有改变这些逻辑工作/路径诊断计数；计数一致不证明运行时耗时一致。

六份最慢行均为four-properties-zero，base269.958292/270.159708/262.383750ms，candidate162.657292/151.147417/172.094625ms；它是最大值，按34项nearest-rank定义不是P95。后两慢行是dense/targeted，其第三组base先后顺序交换。

## 全部逐行变化与门槛区别

102个配对观察中55个candidate更慢。唯一同时超过+15%且+1ms的是第二组DISTINCT dense；没有同一ID在两组满足该条件，因此既有重复regression comparator通过。wrapped DISTINCT shape与aggregate P95也只有这一组超过门槛，资源没有超过15%。这不豁免every-pair progress失败，10x也为false。

以下三项在三组都变慢，保留原始观察：

- `global-wide-name-pair-zero`
- `global-wide-caller-class-dense`
- `global-wide-callee-class-dense`

| 查询 | Pair 1 base → candidate（ms） | Pair 2（ms） | Pair 3（ms） |
|---|---:|---:|---:|
| `global-wide-four-properties-zero` | 269.958292 → 162.657292 | 270.159708 → 151.147417 | 262.383750 → 172.094625 |
| `global-wide-four-properties-targeted` | 9.512250 → 10.260709 | 10.170208 → 9.404791 | 9.696208 → 9.691042 |
| `global-wide-four-properties-dense` | 3.904375 → 3.898666 | 3.884208 → 4.004083 | 4.166334 → 3.973958 |
| `global-wide-class-pair-zero` | 1.901167 → 1.896250 | 2.005750 → 1.919708 | 2.002125 → 1.796708 |
| `global-wide-class-pair-targeted` | 4.278875 → 4.360667 | 4.247250 → 4.309417 | 4.136334 → 4.042167 |
| `global-wide-class-pair-dense` | 1.081625 → 0.900917 | 0.913291 → 1.075292 | 1.163292 → 1.163833 |
| `global-wide-name-pair-zero` | 1.745459 → 1.855166 | 1.706917 → 1.999417 | 1.749208 → 1.800834 |
| `global-wide-name-pair-targeted` | 4.220084 → 4.359708 | 4.412583 → 4.283875 | 4.291083 → 4.331625 |
| `global-wide-name-pair-dense` | 0.782750 → 0.734000 | 0.741292 → 0.771500 | 0.779208 → 0.819083 |
| `global-wide-caller-class-zero` | 1.324833 → 1.592250 | 1.520250 → 1.416333 | 1.392333 → 1.524625 |
| `global-wide-caller-class-targeted` | 2.527959 → 2.758042 | 3.129666 → 2.386416 | 2.610875 → 2.802084 |
| `global-wide-caller-class-dense` | 0.505875 → 0.630292 | 0.593042 → 0.677875 | 0.465667 → 0.555333 |
| `global-wide-callee-class-zero` | 1.333416 → 1.179042 | 1.318916 → 1.176042 | 1.517791 → 1.457292 |
| `global-wide-callee-class-targeted` | 2.115416 → 2.098791 | 2.129833 → 2.114083 | 2.054375 → 2.469333 |
| `global-wide-callee-class-dense` | 0.431083 → 0.491750 | 0.448917 → 0.461792 | 0.415708 → 0.450250 |
| `global-wide-provenance-zero` | 0.967166 → 1.200083 | 1.137792 → 1.084500 | 0.957291 → 1.150416 |
| `global-wide-provenance-targeted` | 2.802667 → 2.897750 | 2.926208 → 2.909291 | 2.669625 → 2.954750 |
| `global-wide-provenance-dense` | 0.852958 → 0.850542 | 0.805792 → 0.839000 | 0.808333 → 0.831250 |
| `global-wide-aliased-zero` | 0.924500 → 0.979709 | 0.937959 → 0.918167 | 0.898500 → 1.002709 |
| `global-wide-aliased-targeted` | 3.229042 → 3.315708 | 3.237500 → 3.063125 | 3.088542 → 3.229666 |
| `global-wide-aliased-dense` | 0.770250 → 0.807917 | 0.730125 → 0.790917 | 0.784916 → 0.749167 |
| `global-wide-parameterized-zero` | 0.932209 → 1.035209 | 0.952291 → 0.921959 | 0.886625 → 0.970542 |
| `global-wide-parameterized-targeted` | 2.132042 → 2.229417 | 2.203084 → 2.079583 | 1.969750 → 2.320625 |
| `global-wide-parameterized-dense` | 0.787500 → 0.734500 | 0.756000 → 0.718834 | 0.741916 → 0.769250 |
| `global-wide-wrapped-case-insensitive-zero` | 1.150459 → 1.120708 | 1.118958 → 1.096584 | 1.025125 → 1.062125 |
| `global-wide-wrapped-case-insensitive-targeted` | 2.273875 → 2.239167 | 2.199833 → 2.418458 | 2.052375 → 2.352208 |
| `global-wide-wrapped-case-insensitive-dense` | 1.552000 → 1.357500 | 1.335167 → 1.429542 | 1.531792 → 1.369750 |
| `global-wide-wrapped-case-insensitive-distinct-zero` | 3.126875 → 3.054917 | 3.075292 → 3.198541 | 3.175458 → 3.144834 |
| `global-wide-wrapped-case-insensitive-distinct-targeted` | 63.760417 → 62.274292 | 34.781459 → 36.108041 | 54.235000 → 37.631125 |
| `global-wide-wrapped-case-insensitive-distinct-dense` | 118.032459 → 112.874042 | 58.594458 → 137.031292 | 53.548125 → 49.776750 |
| `global-wide-distribution-broad-all-64` | 0.850916 → 0.834500 | 0.799250 → 0.879333 | 0.765833 → 0.829208 |
| `global-wide-distribution-localized-early` | 1.299500 → 1.254875 | 1.247125 → 1.287625 | 1.235584 → 1.243000 |
| `global-wide-distribution-localized-late` | 4.300333 → 4.183625 | 4.015209 → 4.444791 | 4.239250 → 4.187667 |
| `global-wide-distribution-localized-middle` | 2.895500 → 2.754333 | 2.701334 → 3.092375 | 2.772625 → 3.178041 |

## 输入和结论边界

保存命令均为原 `LargeBroadQueryPressureBenchmark.replayBroadQueries`、64 graphs、global-wide、indexState=cold、CPU=4、GC profiler、verify oracle。每次回放从冷索引开始，后续查询可复用已加载索引；不应把全部34项都称为每查询冷态或稳定JIT测量。

候选记录的JAR SHA `9d0bfd1d6cfcb9891c064a3a3784d7742c45b5692be004e1ce90996e02cec4ca` 与build收据一致；base为既有受保护冻结JAR。此次只读取既有证据，没有再次hash整个图/JAR。所有输入文件SHA、各次P50/P95/shape P95、top3、资源、102条变化及门槛重算见 `independent-old34-audit.json`，脚本为 `independent-old34-audit.py`。

局部机制验证和资源下降不改变判定：139已拒绝，不继续额外v3配对或candidate CI，也不以这次单组异常的猜测原因再重测。本文不推断JIT、调度或GC导致第二组回归。
