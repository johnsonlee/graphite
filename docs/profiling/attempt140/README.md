# Attempt 140：primitive 属性索引，已拒绝

仅将 mapped raw DISTINCT 的 `predicatePropertyIndexes` 从 `List<Int>` 改为
`IntArray`。此前源码/BCI profiling 定位了逐节点的 `List.get` 与
`Number.intValue` 访问；本轮保留 OR 遍历、投影、索引策略、缓存、预算、取消及线程池。
这是对具体读取点的单一假设，不是 136 的 while 改写、138 的 posting 方案或 139 的校验改动。

**结论：拒绝并显式回退。** 原 34 条 P95 三组都有进步，但补充集
`mixed-four-few-rows` 在两组中重复超过 >15% 且 >1 ms。完整正确性不能豁免性能失败，
没有启动候选 CI、重跑测量或放宽门槛。原始失败原因尚未定位，不能单凭源码差异归因。

| 门槛 | 已验证结果 |
| --- | --- |
| 构建 | 187 项 WebGraph tests / 6 suites 全通过；detekt、JMH 打包和测试排除检查通过 |
| 字节码 | 目标节点回调 Code 790→780 字节；该访问点的 List.get / cast / intValue 变为 iaload，其他 List 和装箱操作保留 |
| v3 正确性控制 | 36 条查询、6,171 行完整值/顺序/来源正确，真实图输入前后相同 |
| 原 34 条 | 204 个完整 oracle 签名正确，原回归门槛及每组严格 P95 进步都通过 |
| 补充 36 条三组配对 | 216 个输出、37,026 行完整值/顺序/来源正确；重复性能退化，拒绝 |
| 方法级/端到端候选 CI | 本地拒绝后未运行，性能证据不可用，不能写成通过 |

| 原 34 条 pair | main → candidate P95 ms | main → candidate CPU s |
| --- | ---: | ---: |
| 1 C/B | 42.972042 → 42.149667 | 1.507567 → 1.434618 |
| 2 B/C | 51.234250 → 49.121708 | 1.590003 → 1.502798 |
| 3 C/B | 49.196834 → 39.949834 | 1.535388 → 1.418618 |

原 34 条全程 work 为 58,071,626，全部非 latency TSV 字段在每个配对中一致。
31/102 个变慢观测仍保留。该 P95 是 34 个不同查询的第 33 个顺序统计量。
CPU、heap、RSS 原值及完整比较见 [原报告](old34-pairs/global-wide-report.md)
和 [独立复算](independent-old34-audit.md)，不从这张局部表推断最终收益。

| `mixed-four-few-rows` | main → candidate ms | 结论 |
| --- | ---: | --- |
| Pair 1 | 351.063167 → 271.282708 | 改善，仍完整保留 |
| Pair 2 | 372.238792 → 727.403042 | +95.4130%，+355.164250 ms |
| Pair 3 | 223.453459 → 297.224584 | +33.0141%，+73.771125 ms |

失败条件是 `(A AND B) OR (C AND D)` 的普通投影。纯 `A OR B OR C OR D`
也完整覆盖单图早/中/晚、两图、55 图和 64 图，每组均有普通投影与 DISTINCT。
补充集全部 108 对中有 57 个变慢观测、5 处 work 变化，其他 TSV 字段无变化。
每查询每侧只有三个观测，不称 P95。v3 不记录逐查询 CPU/heap/RSS，不补造资源结论。
[纯四词与完整审计](independent-v3-audit.md)、[全部 36 条配对表](v3-pairs/README.md)。

基线为 frozen main `4e328b0109e13c896b74004823fb049fcb19251a`；候选父提交是
139 的显式回退 `aede4c82f66a925ba9df3fc8588c6e1399c17f61`。真实输入为 Android14、
Tika2.9.2、Hive4.0.0、Kotlin compiler2.0.21 各 16 个持久化 class shards，共64图，
不是64个独立应用。Java17/macOS，4 active CPUs，8GiB JMH heap；原 benchmark 为
`LargeBroadQueryPressureBenchmark.replayBroadQueries`，固定零 warmup/单迭代/单 fork。
两套三组配对均为 C/B、B/C、C/B；原34为 cold-on-replay，v3为每查询清索引，不能混用。

候选源码 SHA-256 `120d23247d266e4b9a57c7a53644154307725b32156531ce62822f914dfb98ba`；
immutable JAR SHA-256 `fb58d962d349a5c526bec229b996f08d94800702688ed202c1e7e771c3e4e357`。
JAR 与完整隔离 checkout 保存在 `/private/tmp/graphite-attempt140._5jztd0a`；
受保护 main JAR 从未重建或改写。

[预定计划](measurement-plan.json)、[构建交接](implementation-build-summary.md)、
[根线程核验](root-premeasurement-audit.json)、[独立方案审计](independent-plan-audit.md)、
[控制审计](independent-control-audit.md)、[最终拒绝](decision.json)、
[复制哈希](copy-receipt.json) 均保留原始输入与命令。
本轮没有接受的优化，CallSite 池仍须移除，最终 10x 尚未达到。

失败尝试提交为 `4215b66e462675baeb3e1b1f2013cf7e6de01812`。
显式回退恢复全部 130 个 main/JMH 文件与冻结 main 逐字节相同，168 个测试文件
与候选父提交相同；[回退核验](revert-source-receipt.json)。原始诊断和测量全部保留。
