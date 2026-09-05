# Attempt136 已完成结果的独立核对

结论：拒绝条件被独立复现。现有 comparator 的 regressionPassed=true 仅代表其回归检查通过；第一组 P95 退化，strictProgressEveryPair=false，10x 目标也未达到。regressionOnly 模式下 status.passed=true 不是本轮接受条件。后两组改善不能抵消第一组失败。

六份 JSON/TSV 均为 Java17、4 active CPUs、8GiB heap、cold、64图、34查询、单fork单measurement。204/204 条完整正确性签名（14字段）逐条、逐顺序等于 oracle；oracle 与旧独立主基线文件逐字节相同。全部成功，failure/timeout 为0。逐查询来源、输入图数、访问图ID、工作量和parallelScanCount六次一致。每次总工作量58,071,626。原始全部row值不在TSV中，本核对通过完整canonical digest验证一致性，没有重新执行查询。

## P95 与两条 DISTINCT 行

P95 独立按 ceil(34×0.95)=第33个有序延迟重算，六次均由 dense DISTINCT 决定，与JMH JSON及status一致。这是34个异质查询的套件分位数，不是同一查询重复请求的P95。

| 配对顺序 | dense / P95 main→candidate ms | 变化 | targeted main→candidate ms | 变化 |
|---|---:|---:|---:|---:|
| 1 candidate-base | 47.074708 → 50.419250 | +7.10% | 24.229000 → 38.935916 | +60.70% |
| 2 base-candidate | 65.733291 → 55.483833 | -15.59% | 42.839250 → 30.707583 | -28.32% |
| 3 candidate-base | 47.527125 → 41.615750 | -12.44% | 37.553583 → 29.260708 | -22.08% |

dense工作量始终283,544；targeted始终106,706。dense为200行、来源android-00/tika-00；targeted为12行、来源kotlin-compiler-11/15。完整digest与精确来源ID保存在JSON。三组P95 speedup为 0.933665x, 1.184729x, 1.142047x。

## 全轮资源

以下为整轮34查询的进程CPU和峰值计数，不能归属到某一条查询。GiB按2^30字节换算，JSON保留精确整数。

| 配对 | CPU main→candidate s | peak used heap main→candidate GiB | peak RSS main→candidate GiB |
|---|---:|---:|---:|
| 1 | 1.499329 → 1.552886 | 4.381704 → 4.028640 | 4.894684 → 4.528015 |
| 2 | 1.659594 → 1.511979 | 4.061252 → 3.954383 | 4.684845 → 4.444290 |
| 3 | 1.590953 → 1.491688 | 4.381388 → 4.374230 | 4.906586 → 4.901901 |

资源值逐项与现有status核对一致；本审查没有重写或放宽回归规则。CPU第一组略升，后两组下降；heap/RSS本次各组未增加。这些结果不改变strict-progress失败。

证据目录：`old34-pairs/` 下六份 JSON/TSV、command、oracle.correctness、global-wide-status.json 和 local-progress.json。`independent-results-audit.json` 保存所有输入小文件SHA256、34条oracle、204条延迟/结果摘要、三组重算值及边界说明。没有启动新的Java、v3回放或CI性能尝试。
