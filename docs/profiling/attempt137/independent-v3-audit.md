# Attempt 137：v3 三组配对独立审计

**完整结果与来源核验通过；没有观察到按旧34参考尺度重复出现的实质退化。** 这只是三组诊断记录，不是 v3 数值验收 gate，也不是 P95 或稳定性证明。共有9个 candidate 比 base 慢的观测，以及4处 graphWorkUnits 差异，均保留如下。不能概括为36条查询全都改善。

只读取已有文件并用 Python 重算；未启动 Java、构建、新测量或重试。`v3-pairs-receipt.json` 已为 complete，六份 run.json 均 complete、每份1个 verified fork；顺序 candidate/base、base/candidate、candidate/base。每查询开始前清索引（per-query-cold），不清 JIT/OS page cache，所有 run 的 empiricalP95LatencyNanos 都为 null。

## 完整性和正确性

- 六份各36查询，共216个完整结果、37,026行实际 values/graphIds，按完整 catalog ID 顺序逐一与 expectedRows 相等。四投影 columns、值、物理结果顺序、重复行/DISTINCT、每行 graphIds 和行数均核验。
- workload base64 解码后的实际查询字符串逐一等于 catalog；TSV workloadIdentity 等于查询 SHA-256；重新规范化实际 rows 后的 SHA-256 等于 TSV digest。scope=64、resetMode=per-query-cold、outcome=success、正 timing、投影类型与 summary 唯一 latency 均核验。
- 六份 catalog/workloads 副本与输入 hash 相同；六份编译 adapter class hash 一致。graph-content-before/after inventories 逐字节一致，六次共同 hash 为 `1eb97cabd94cb61459610d1360f7955f9a5cf1a7ada6782f125492729be129c6`。本次核对这些记录及小文件 hash，没有重新 hash 大图/JAR。
- trusted JAR 恒为冻结 main；runtime JAR 分别是 base/candidate，匹配最终 receipt：base `a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe`；candidate `b129029382bc6f0e8491c97c9057830c5993902b9cbeae7f21e6df10865a1fb7`。输入采样前后未改的检查由 run.py/配对脚本执行并记录，本次不重做大文件扫描。

## 纯四关键词 OR 的单图/多图覆盖

独立核验 v3 的18个 logical IDs/36查询顺序。六个 pure-four case 的 AST 均为 `[or,0,1,2,3]`，四个词非空、互异、互不包含；实际查询每个词搜索全部四属性，外层为纯OR，没有混成AND或简化成两词。每词 exclusive count 都大于0；这是完整导出 oracle 的 census，不是从 LIMIT 返回结果猜测命中图。

| logical case | 完整命中位置 / 图数 | 匹配节点数 | 四词 exclusive counts |
| --- | --- | --- | --- |
| or-four-broad | catalog中的55位置 / 55 | 50,461 | [473, 49431, 260, 68] |
| or-four-single-early | [0] | 704 | [673, 6, 24, 1] |
| or-four-single-middle | [31] | 2,646 | [2547, 6, 9, 84] |
| or-four-single-late | [63] | 299 | [289, 4, 3, 3] |
| or-four-few-early-late | [0, 63] | 972 | [673, 6, 289, 4] |
| or-four-all | 0..63 / 64 | 2,455,554 | [1215977, 438686, 210146, 236328] |

单图 early/middle/late 分别为 manifest 0/31/63；两图是0+63；all为全部64图。完整命中图集与每查询返回行涉及的 graphIds 必须区分：LIMIT200 往往只留下前面图的结果，DISTINCT 的 selected rows 来源也可能只是完整命中集的子集。已逐行验证完整 expected provenance，未要求每个 full-hit graph 必须出现在返回值中。

下表全部为独立观测的 **base → candidate 毫秒**，没有池化或生成三次P95：

| query | pair1 | pair2 | pair3 |
| --- | --- | --- | --- |
| or-four-broad-rows | 36.211833 → 31.664250 | 35.188750 → 32.375458 | 33.895541 → 33.606834 |
| or-four-broad-distinct | 150.348959 → 110.617500 | 150.440625 → 108.686250 | 148.464666 → 108.552125 |
| or-four-single-early-rows | 16.635291 → 13.157500 | 15.193625 → 12.260959 | 13.770250 → 10.842041 |
| or-four-single-early-distinct | 150.636167 → 107.259000 | 147.706500 → 105.220958 | 151.335709 → 108.240208 |
| or-four-single-middle-rows | 92.030708 → 67.710541 | 91.278875 → 64.618291 | 93.942167 → 64.911625 |
| or-four-single-middle-distinct | 142.849917 → 100.693875 | 145.811375 → 105.054708 | 146.085292 → 105.841500 |
| or-four-single-late-rows | 149.902000 → 104.911792 | 149.991708 → 105.368083 | 147.816792 → 103.949041 |
| or-four-single-late-distinct | 146.021333 → 107.122208 | 146.671333 → 105.041458 | 147.453667 → 105.215416 |
| or-four-few-early-late-rows | 14.628500 → 11.866667 | 16.868167 → 11.518875 | 13.774834 → 10.161875 |
| or-four-few-early-late-distinct | 149.107208 → 105.174791 | 147.826750 → 104.959834 | 148.020916 → 104.259792 |
| or-four-all-rows | 14.694958 → 11.140417 | 15.879042 → 12.649416 | 11.771583 → 12.676041 |
| or-four-all-distinct | 181.821084 → 135.619667 | 183.114583 → 135.706958 | 175.411958 → 133.763666 |

这12条×3组共有35个观测更快；例外是第三组 or-four-all-rows（11.771583→12.676041 ms，+7.683%）。or-four-broad-rows 第三组仅33.896→33.607ms，差距很小。没有从这个表推断稳定提升，六份冷回放也不是六份所有环境都重置的实验。

## 所有36条查询的变慢观测

以下完整保留所有candidate更慢的9个观测；完整108组比较在JSON中。参考尺度为旧34的相对>15%且绝对>1ms；只用于描述重复程度，不在此创设v3 acceptance gate。

| query / pair | base ms | candidate ms | delta ms | 相对增量 | 超过参考尺度 |
| --- | --- | --- | --- | --- | --- |
| or-few-early-middle-rows / 2 | 10.841375 | 11.218416 | 0.377041 | 3.4778% | 否 |
| or-broad-all-rows / 2 | 4.570417 | 5.663708 | 1.093291 | 23.9210% | 是 |
| or-broad-all-rows / 3 | 5.154916 | 5.495000 | 0.340084 | 6.5973% | 否 |
| and-broad-all-distinct / 1 | 990.739833 | 1005.690625 | 14.950792 | 1.5091% | 否 |
| and-broad-all-distinct / 2 | 975.796417 | 995.122792 | 19.326375 | 1.9806% | 否 |
| and-broad-all-distinct / 3 | 975.570167 | 987.786625 | 12.216458 | 1.2522% | 否 |
| mixed-four-few-distinct / 1 | 38794.365542 | 39131.338958 | 336.973416 | 0.8686% | 否 |
| mixed-four-few-distinct / 3 | 38000.350417 | 39289.910333 | 1289.559916 | 3.3935% | 否 |
| or-four-all-rows / 3 | 11.771583 | 12.676041 | 0.904458 | 7.6834% | 否 |

只有 pair2 or-broad-all-rows 同时超过两个条件；同一查询 pair3 也稍慢，但幅度未过尺度，因此没有至少两组超过该尺度的查询。and-broad-all-distinct 三组都更慢（约1.25–1.98%）；mixed-four-few-distinct 两组更慢，第三组绝对增加1.289560秒、相对约3.39%。这些是保留的负向观测，不能因未触发参考尺度就抹掉，也不能未经测量把它们解释成噪声。

## 工作量和解释边界

| query / pair | base work | candidate work | delta |
| --- | --- | --- | --- |
| mixed-four-few-rows / 1 | 227190 | 100227 | -126963 |
| mixed-four-few-rows / 2 | 304905 | 105233 | -199672 |
| mixed-four-few-rows / 3 | 304631 | 276591 | -28040 |
| or-four-single-middle-rows / 3 | 31119327 | 31185346 | 66019 |

其余104组 query-work 相等；所有配对的结果digest/rowCount/hitGraphIds、query hash、scope/reset、filteredNodeLimitFastPathExecutions/generalFallbackExecutions 相等。不能把旧34全部work相同的结论移用到v3。并发下可有投机扫描/提前终止造成计费差异，但本次没有线程级证据解释这4项，故不将其归因为SAM，也不推断新增了计划优化。

本次独立源/bytecode审查支持“primitive callback 边界已改变”，不是所有延迟变化的因果证明。这里没有CPU/heap/RSS的对应数值gate，三次per-query观测也不是P95；最终10x、old34和hosted CI仍依各自协议判断。本文只确认现有v3资料完整且未见重复超过参考尺度的退化，不提前接受Attempt137。完整数据见 `independent-v3-audit.json`。
