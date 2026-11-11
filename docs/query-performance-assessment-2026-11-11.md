# Graphite 当前查询性能与优化空间评估

评估日期：2026 年 11 月 11 日，Asia/Seoul。目标版本：`58215b986f8c1be68b0480bc209e00311bc80dc1`，评估时本地 HEAD 与远端 HEAD 一致，初始工作区干净。

**目标澄清（2026 年 11 月 11 日）：解决慢查询和复杂聚合查询，优化指标聚焦 P95 latency。P50 仅作辅助诊断，不作为优化目标或候选排序依据，P50 改善不能抵消 P95 退化。** 正确性、可用性、稳定性及既定 CPU/RSS 约束仍须满足。

**结论：优先降低全图关系聚合、跨图分组合并及混合并发下慢查询的 P95。** 最新可复用的真实 64 图测量中，最慢一组的 p95 为 123–310 ms；73 类查询中有 56 类 p95 低于 10 ms，作为回归保护，不作为主要提速对象。这些是 Linux CI 上的当前代码观察，不能解释为生产延迟承诺。查询类别数量也不是生产流量占比。

**生产适用范围补充：用户报告真实生产 50+ 图的 P95 经常达到几十秒。下文 64 图数据来自四组 JAR 分片、四 worker 且有验证间隙，仅可用于该测试环境内排序；不能代表生产持续压力，也不能据此把生产优化队列限定为这几条百毫秒查询。生产慢查询及其复杂聚合形状应优先复现，现有 73 类作为回归保护。**

当前证据支持确定该测试环境内的优化顺序，**不支持给出全系统还能提速 30%、2 倍或 10 倍的预测**。慢项中确有可消除的重复工作；具体可回收多少，需要最新版本真实多图 profile 和逐项 A/B。CPU 与峰值 RSS 尚缺测量，性能最终验收未成立。

## 证据范围与可靠性

### 最新版本的实测身份

本评估复用 [PR 211 的完整 CI 运行](https://github.com/johnsonlee/graphite/actions/runs/42912084315)，而非重跑未变化的代码与输入：

| 项目 | 身份或结果 |
| --- | --- |
| 被测 base | `907bc9adf24dbd238d72c08127827e6481cc6fe7` |
| 被测 head | `2275a50661a26cbcc397d8881e50a976571bee36` |
| 当前 HEAD 与被测 head 的共同 Git tree | `90b0c0f7fbedbce64850457eaad221d28b2436d0` |
| base 与 head 的共同原生二进制 SHA256 | `b2691765c9b24c1da0db603c220642160ffc2173b86c9c7f05bfdbec521a4d94` |
| 原报告 | `passed: true`，`performanceAcceptance: false` |
| 完整报告 artifact | [13809622988](https://github.com/johnsonlee/graphite/actions/runs/42912084315/artifacts/13809622988) |
| 请求、响应及收据 artifact | [13809902215](https://github.com/johnsonlee/graphite/actions/runs/42912084315/artifacts/13809902215) |

`git diff 2275a506 HEAD` 为空，两者整树一致，因此被测 head 的结果可作为当前提交的环境限定观察。两臂原生二进制完全相同，**下文 base/head 差值仅展示波动，不能归因成查询实现的改善或回退**。图 manifest 文件哈希不同，不能因此直接断言输入文件逐字节相同；控制器验证了真实源 JAR、分片身份、查询与完整结果的匹配。

PR 评论仍显示“No complete report was produced”，但下载的 report artifact 实际包含完整通过报告。这里以 artifact 内容和原始请求为依据，不以评论摘要替代证据。

本次独立验证完成：

- 两臂各 4,399 请求，共 8,798 请求；每臂包括 73 次首次 oracle、146 次 warmup、1,460 次 pressure、2,720 次 isolated。
- 全部请求记录 passed，全部响应收据 HTTP 200 且 body 完整；逐份核对 body 字节数与 SHA256。
- 全部响应 `graphCount=64`，`rowCount` 与实际行数一致；73 类查询的解析后完整响应在重复请求及两个版本之间一致，保留数组顺序及字段缺失语义。
- 从原始记录重算全部 pressure/isolated 分位数，与报告一致，未剔除慢样本。范围及语义断言也由原控制器通过。

归档的[独立审计结果](query-performance-evidence/2026-11-11/audit.json)、[完整响应审计](query-performance-evidence/2026-11-11/body-audit.json)、[原报告](query-performance-evidence/2026-11-11/ci-report.json)及压缩请求记录可复查。这里的正确性成立范围是对应查询、输入和检查，不是全部 Cypher 语义的形式证明。

### 测量协议及限制

数据来自 Android-all、Tika、Hive、Kotlin compiler 四组真实 JAR，各分 16 片，共 64 个持久化图。每条被测请求范围是全部 64 图，不是加载多图后只查单图。分片之间仍属于四组来源，不能直接代表 64 个任意独立项目、跨项目调用连接或任意生产图规模。

生产 Rust HTTP 服务执行 73 类查询。每类先 oracle，再两次 warmup；pressure 为四个 worker、按 round 交错、每类 20 次。每类 pressure p50 小于 100 ms 才追加并发 1 的 isolated 40 次；本次有 68 类符合条件。分位数逐类计算，不将不同查询混成一个 p95。20 次 nearest-rank p50/p95 分别是排序后第 10/19 个样本，尾部估计较粗。

请求时延覆盖发送请求到**完整 HTTP body 消费**，包括每次 `Connection: close` 的连接成本；JSON 校验、哈希和写盘在 client-cycle 中。worker 完成校验后才发下一请求，因此这是**带验证间隙的闭环混合负载**，不是持续饱和负载、固定到达率测试或系统最大 QPS 证明。客户端完整读完的边界也不同于服务端完成响应构造、释放 permit 的时刻。

isolated 位于 pressure 后，缓存与热状态不同；首次 oracle 不是每个用例独立冷启动，文件系统缓存未受控。单批 20 次的 p95 不应承载精确的小幅回归结论。本报告列出 min/max 展示实际变异，不补造置信区间；独立多轮和固定采样设计留给候选验收。

当前控制器没有查询窗口总 CPU 和进程峰值 RSS 数据。OOM gate 通过不能替代 CPU/RSS 各不超过基线 5% 的证据。协议详见 [benchmark README](../benchmark/README.md) 及 `benchmark/src/native.rs:482,574,1144`。

## 当前最值得关注的查询

下表单位全部为 ms，按 head p95 降序。Δ 为 head 减 base，先列绝对变化，不将百分比作为优先级依据。这些 base/head 是相同原生二进制的两臂观察。

| 查询 | base p50 | head p50 | Δp50 | base p95 | head p95 | Δp95 | head 最小至最大 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 关系类型与两端标签统计 | 157.419 | 143.606 | -13.813 | 290.189 | 310.032 | +19.843 | 129.879–315.913 |
| callee_class 分组计数 | 190.034 | 182.226 | -7.808 | 248.669 | 250.179 | +1.510 | 147.377–282.826 |
| WITH 分组后过滤 | 184.041 | 191.033 | +6.992 | 239.027 | 208.443 | -30.584 | 136.681–233.875 |
| 关系遍历总数 | 152.165 | 153.119 | +0.954 | 173.334 | 167.937 | -5.397 | 133.266–170.480 |
| callee_name 等值搜索 | 108.841 | 108.999 | +0.159 | 129.808 | 122.533 | -7.276 | 84.731–125.328 |
| targeted 四属性搜索 | 57.945 | 60.485 | +2.540 | 90.198 | 103.815 | +13.617 | 1.294–109.123 |
| 过滤后的关系遍历 | 64.039 | 69.052 | +5.013 | 97.745 | 89.612 | -8.133 | 50.278–91.110 |
| targeted class pair 搜索 | 2.796 | 5.954 | +3.157 | 53.230 | 61.413 | +8.183 | 0.750–81.694 |
| callee_class DISTINCT | 20.973 | 18.114 | -2.858 | 38.521 | 38.350 | -0.172 | 14.097–43.468 |

对应的主要查询形状如下；完整 catalog 在 [native_queries.json](../benchmark/src/native_queries.json)。

```cypher
MATCH (a)-[r]->(b)
RETURN labels(a) AS a, type(r) AS t, labels(b) AS b, count(*) AS c
ORDER BY c DESC LIMIT 40

MATCH (n:CallSite)
RETURN n.callee_class, count(*) AS c ORDER BY c DESC LIMIT 20

MATCH (n:CallSite)
WITH n.callee_class AS c, count(*) AS k WHERE k > 5
RETURN c, k LIMIT 50

MATCH (a)-[r]->(b) RETURN count(*)

MATCH (n:CallSite) WHERE n.callee_name = 'valueOf'
RETURN n.caller_class LIMIT 200
```

全 catalog 的 head 分布为：p50 小于 10 ms 的 64 类、10–100 ms 的 4 类、至少 100 ms 的 5 类；p95 对应为 56、11、6 类。这说明投入应聚焦慢项及其对其他请求的影响，不能用低基数查询的大百分比变化取代绝对用户收益。

当前 `ORDER BY caller_class LIMIT 200` 的 p50/p95 为 **3.451/11.261 ms**，`collect(...)[0..10]` 为 **1.615/5.991 ms**，`count(*)` 节点计数为 **1.386/3.418 ms**。旧记录中的秒级 ORDER BY/collect 已不是本版本现状；旧单图 JMH 数据也不用于本评估的优化收益或性能验收。

### 并发干扰比快查询本体更值得研究

| 查询 | 四 worker p50/p95 | 后续 isolated p50/p95 |
| --- | ---: | ---: |
| targeted 四属性搜索 | 60.485 / 103.815 | 0.627 / 0.653 |
| targeted class pair | 5.954 / 61.413 | 0.632 / 0.669 |
| 过滤后的关系遍历 | 69.052 / 89.612 | 0.932 / 0.989 |
| DISTINCT | 18.114 / 38.350 | 6.943 / 8.683 |

差异说明混合负载环境值得优先诊断。不能把这些差值全算为可回收优化空间：阶段顺序、缓存、宿主噪声、内存带宽和执行任务竞争尚未拆开。前五个最慢用例因历史协议的 p50≥100 ms 筛选规则，没有 isolated 数据，不能将“无数据”解释为无并发影响。后续针对慢查询的诊断必须显式包含这些用例，不能沿用 P50 筛选来决定哪些尾延迟问题值得研究；历史样本及原协议保持不变。

另有本机 M3 Max 的旧 PR 211 证据，其原生源码闭包与 HEAD 相同，但 JVM producer/build 有变化，且存在背景任务和固定 AB 顺序限制。该环境 group-count p50/p95 约 42.688/46.017 ms，关系统计 14.613/15.036 ms。它提示绝对延迟强依赖环境；不与 Linux 样本混合，也不冒充当前整树验收。原资料位于 `/private/tmp/graphite-pr211-final-benchmark/fresh-run/` 与相邻独立审计文件。

## 已有优化与剩余实现成本

当前代码已有解析缓存、索引聚合、CSR、字符串字典/trigram、单跳锚定、分区加权聚合、流式 LIMIT、bounded top-k、紧凑投影与标量直接 JSON 序列化。注册表锁只覆盖快照获取，多图扫描已使用 Rayon。继续做“加索引、加缓存、加并行”这种笼统改造，容易重复已有能力或突破资源约束。

### 第一优先级是关系聚合的重复扫描

按当前查询形状与源码分派，`engine/pipeline.rs:421` 可选择 `PartitionPlan`；`engine/partition.rs:981` 对各图并行执行 `sweep_edges`。`partition.rs:1635,1675` 每次构造目标节点 tag 数组，扫描 CSR，再按 source tag、关系 label、target tag 统计并记录首次出现位置。本次没有运行路径 telemetry，后续 profile 需确认实际分派与耗时占比。

关系 histogram 需要这些分类，但纯 `MATCH (a)-[r]->(b) RETURN count(*)` 也会做分类后再加权聚合。两者直接对应 p95 310.032 ms 与 167.937 ms 的已测慢项。

**候选一：为严格匹配的纯 directed 单跳 count 提供有效边总数特化。** 可避免不必要分类，先保留原扫描而减少工作，再考虑经验证的摘要复用。不能直接返回 `Graph::edge_count()`：后者是底层 targets 长度，不证明所有端点都满足 matcher 的可见标签与有效性语义。

**候选二：不可变图的有效边统计摘要。** 缓存足够小的类型 histogram、有效总数及必要的 first encounter 信息，避免重复请求重扫全部边。键必须包含图实例/内容版本、方向及适用语义，不能只用可复用的图名称。失配形状继续走原路径。

这是目前最明确的结构性空间，仍需测量扫描占总时间的比例。懒建摘要的首次成本必须记在冷查询中；持久化摘要必须单列 build/save、load、CPU、RSS，不能把查询工作移到启动后宣布消失。节点 tag 数组常驻可能比小 histogram 更占 RSS，不建议先缓存全量节点级结构。

### 第二优先级是跨图分组的重复字符串合并

`engine/fastpath.rs:157,225` 和 `engine/grouped_with.rs:112,160` 均复用 `bounded_call_site_groups`。`engine/fastpath/bounded_groups.rs:67` 建全局 HashMap，遍历各图的 CSR property values，用字符串 hash 合并计数；`:108` 已用 heap 选 K，`:137` 只给获选项构造 Row。

因此这两条 p95 分别为 250.179 ms、208.443 ms 的查询形状已有 top-k 与 CSR 快路径；当所有 source 的索引证明成功时，无需解码每个 CallSite。本次未记录运行时路径，仍需排查索引不可用或证明失败导致的回退。在快路径命中的前提下，值得研究的是跨图所有 distinct 字符串在每次请求上的重复 hash、合并和 provenance 工作。

候选包括按字符串有序的多路合并、减少字符串重复处理、按不可变 source 集合缓存受限的合并摘要。索引已提供 posting length，单纯缓存单图计数未必有收益。全局摘要缓存需限制总量，并覆盖 source 增删替换、相同图名不同内容、并发构建、取消与淘汰；固定热 key 的好成绩不代表变化工作集收益。

`Graph::validated_ordered_call_site` 在 `backend/storage/src/graph.rs:721` 已用 OnceLock 缓存验证结果，不能把热路径慢归因为每次重验整份 CSR。合并 admission 中的小重复检查可做后续整理，不作为首轮收益目标。

### 第三优先级是并发调度与等值查询归因

`engine/scan.rs:760,882,1320` 已按 source 批次并行准备、按原序消费并支持 LIMIT 提前停止；`partition.rs:981` 也使用并行 source 扫描。这些任务共享 Rayon pool，并不是每请求创建一套线程池。

需要验证四个请求叠加时的任务排队、内存带宽和临时分配。可研究每请求 fan-out、批次大小和公平性，但不得靠减少已接受请求、提高 429 或提前释放 permit 改善 p95。默认并发门限 4 已存在，增加线程也不能自动解决带宽竞争。

等值搜索 `callee_name='valueOf'` 已可在 `scan.rs:1494` 用字典 binary seek，再走 postings 与紧凑投影。它仍出现 108.999/122.533 ms，必须先区分实际快路径命中、source pruning/准备、匹配分布、冷 mmap、并发任务等待和响应构造。不能从延迟大就推断“等值搜索没用索引”。若 profile 证明准备成本突出，再比较单 equality leaf 的借用式 sid/posting 迭代，减少 memo、Vec 和计划重建。

### 正确性与可用性优先检查

`backend/explore/src/query_job.rs:15` 与 `guard.rs:211` 已正确将 permit 保留到执行和响应构造完成。`routes.rs:1476,1598` 对复合值仍递归物化，并构造完整响应 String；`materialize.rs:24` 的物化和 serializer 内未见取消轮询。

由此存在需要验证的风险：大 list/path/node 返回可能在执行完成后长时间序列化，超时或取消难以及时停止，延长容量占用。**这是源码识别的风险，尚未复现本版本故障**。先补针对性行为验证，再决定序列化检查点与直接 serializer 设计，不提前释放 permit。当前网络探针也不能证明 disconnect cancellation 或 native work-budget 支持；README 已明确限定这些能力。

标量已经直写 JSON，复合值则按字段临时物化，不是整个响应 JSON 树全部同时常驻。可先移除 wrapper Vec、减少复合对象分配，再研究完整流式输出。需保留 null 字段省略、float 格式、行序、属性覆盖与 provenance；不能用截断大结果获得时延改善。

### 其他值得保留的方向

| 方向 | 具体剩余成本 | 优先级与边界 |
| --- | --- | --- |
| 通用多 pattern / OPTIONAL | `matching.rs:159,225` 按源顺序枚举；`pipeline.rs:668` 某些回退将匹配全部收集 | 真实慢查询命中后再升优先级；保留 LIMIT、首次遇见顺序、异常可见性和关联变量 |
| Hop 锚点选择 | `hop.rs:38` 优先取第一个可下推 conjunct，而非最小候选 | 可利用已有 posting 大小；不能重做 ScanPlan 已有成本优化或引入更贵估计 |
| Row 与邻接分配 | `matching.rs:202,347,414,476,572` clone Row、collect 邻接 | 大量匹配、多跳更可能受益；先测分配占比，再评估可回滚 slot 或迭代器 |
| 通用 ORDER BY LIMIT | `pipeline.rs:1217` bounded 入场受限；`:2754` 普通路径全量排序 | 只扩展可证明安全的类型与形状；当前 catalog 的 ordered 快项不值得优先重做 |
| 冷索引与相同 term 并发 miss | `graph.rs:983`、`columns.rs:232` 懒建；`scan.rs:3494` cache→计算→remember 可重复计算 | 有界 singleflight 可研究，需新 term、淘汰、取消后重试和冷请求；已有每列 32 项/512K IDs 限额 |
| parser cache | `parser.rs:67` 全局 Mutex 下 Vec 线性查找和移动 | 已有 1024 项 dialect/text 缓存；高 QPS 短查询 profile 证明占比后再换 O(1) LRU |
| registry snapshot | `registry.rs:275` 每请求构建 source 快照 | 锁持有区很短，无争用证据；高图数下才考虑 generation 快照复用及热替换 RSS |

通用变长路径的组合爆炸和必须输出的数据量仍有下界；不能承诺靠微优化消除全部复杂查询成本。当前 catalog 对 OPTIONAL、复杂相关多 pattern、深路径、大复合输出及参数工作集覆盖不足，源码机会不能冒充这些形状的性能实测。

## 优化空间如何量化

在当前测量环境，310 ms 级 histogram 和 200 ms 级分组比 1–5 ms 搜索具有更大的绝对收益预算。但“当前耗时”不是“可回收耗时”。建议用 profile 的可优化占比 f 与局部提速 s 计算条件估计：

`新总耗时 / 旧总耗时 = (1 − f) + f / s`

例如，只有实测证明某项占 60%，且该项确实加快两倍，总时间才有约 30% 下降的理论估计；若占 20%，即使该项完全消失，总改善也至多 20%。这是假设计算，未测得本项目的 f 或 s；排队、缓存与争用还可能使实际结果偏离。

研究顺序以慢查询的绝对 P95 和复杂聚合的实际成本为依据。以下为重点工作队列；共享扫描路径的优化可同时覆盖第一项和第四项。未指定业务 SLO，也没有候选实测，不预设统一的下降百分比。

| 优先顺序 | 查询 | 当前 P95 ms | 优化重点 |
| --- | --- | ---: | --- |
| 1 | 关系 histogram | 310.032 | 重复边扫描、分类统计和并发尾部成本 |
| 2 | group-count | 250.179 | 跨图字符串分组合并及竞争 |
| 3 | WITH aggregate | 208.443 | 分组后过滤的重复合并工作 |
| 4 | count-traversal | 167.937 | 纯总数查询避免冗余分类扫描 |

候选结论必须列出相同代表性多图负载下 baseline P95、candidate P95、绝对降低毫秒数、样本量及不确定性。P95 未改善的候选不能仅凭 P50 改善宣布达到目标；P50 仍原样记录，避免隐藏行为变化。慢请求对应的执行阶段和并发竞争需要单独定位，不能用平均 profile 占比直接预测 P95，也不能把不同阶段的 P95 相加。

这种量级足以值得针对性实验；对几毫秒查询重复追逐百分比改善，收益优先级更低。不存在可用的生产请求频率、SLO 和瓶颈 profile，因此不计算全站平均节省、最大吞吐或总体加速倍数。若摘要复用显著减少 O(E) 扫描，个别热聚合可能有更大收益，但冷首次成本与资源限制可能成为决定因素。

## 验证路径与反馈周期

### 本次评估为何不重跑完整流水线

已获取与当前 HEAD 整树相同的完整测量，并独立复核全部样本、响应收据及结果。最新 CI 的主 benchmark job 从 22:11:44 至 22:46:56 UTC，约 **35 分 12 秒**，harness job 另约 58 秒。仅一臂 real64 构建/保存约 443 秒，两臂还包含 whole-JAR 正确性、编译、启动及传输，不能用总时间除以 worker 数估算。

这次读取已有 report、下载约 20.3 MB raw artifact，再进行秒级离线复算，形成短反馈周期。没有新性能候选，不重复运行未变化的 expensive gate，也不跑单图或合成性能测试。未修改生产代码。

### 后续实验的固定边界

第一步先做稳定性行为检查和最小 producer→consumer 正确性 smoke，包括准备产物、传输/恢复、实际加载消费；合成 fixture 只用于正确性。确认路径和输入可消费后，再启动昂贵测量。现有本机真实 64 图在 `/private/tmp/graphite-pr211-final-benchmark/fresh-run/results/frontend/{base,head}/graphs/`，可用于准备查询诊断，但必须先核实 manifest、图格式及 producer 身份；其旧 producer 不能替代最新完整构建验收。

新优化以当前 `58215b98` 为增量基线；若主张恢复某历史回归，最终还需与该回归前的接受基线比较，不能只比父提交。每个假设独立 commit，按既有 `docs/*-optimization-attempts.md` 续写实验历史；有效增量保留组合，最终累计候选单独验收。

建议预声明以下诊断与验收设计，不在结果出来后挑选统计量：

| 阶段 | 预先固定的工作 | 产出 |
| --- | --- | --- |
| 正确性和可用性 | 相关模块测试、lint、两个 dialect 的相关语义；大输出取消、超时、permit 释放与恢复 | 先通过行为门，不用时延掩盖错误 |
| 路径定位 | 真实多图慢项记录所选路径、source 数、扫描节点/边、候选数、聚合 key 数、输出字节；低扰动计时分开 | 解释扫描、合并、调度和序列化占比；profile 运行不混入验收样本 |
| 增量 A/B | 固定真实 64 图、4 并发、完整 body；单个候选对 HEAD；匹配 CPU/内存/缓存/构建设置 | 全部样本与失败、P95 绝对毫秒改善及不确定性、CPU、峰值 RSS；P50 为辅助记录 |
| 冷热与变化工作集 | 同 term 热查询、新 term、淘汰后查询、首次索引；固定顺序或预声明交替顺序 | 防止热缓存专用优化及工作转移 |
| 累计验收 | 原有 73 类覆盖加具体 changed-path 用例；在受控机器多轮交替 baseline/candidate | 各用例独立结论，不能用一种工作负载抵消另一种退化 |
| 容量稳定性补充 | 如评估饱和能力，独立固定到达率/持续负载协议，记录 accepted/rejected、错误、超时及资源 | 不与现有带间隙闭环数据混算，也不只报成功请求 p95 |

诊断先聚焦关系 histogram、group-count、WITH aggregate 和 count-traversal；等值搜索作为后续慢查询归因及混合负载保护项，避免 73 类全部重跑后才发现数据不可用。最终仍保留完整相关覆盖。可在专用诊断协议中预声明每类每臂 200 个请求、三个独立交替批次、并发 4；这个样本量是后续建议，不是本次已执行。固定规则逐轮以 nearest-rank P95、绝对毫秒变化、原始范围及预声明的区间估计评价候选，另保留 P50 辅助数据，不能将不同查询池化。需要扩大官方协议时先按仓库 trusted harness 流程审查，而不是通过临时开关绕过。

CPU 按各操作窗口记录 user+system，RSS 记录独立进程峰值；冷启动与热查询分开，考虑峰值指标不能在同一进程随意重置。控制器、客户端和 server 资源区分；严格比较同环境、同图、同结果的完整消费边界。JAR JVM 固定 4 GiB，任何 JVM 不超过 8 GiB，不能靠增堆重试 OOM。Rust RSS 与 JVM heap 是不同约束。

### 当前能与不能得出的验收结论

| 维度 | 本次结论 |
| --- | --- |
| 结果正确性 | 对 73 类真实多图请求、两臂全部响应一致和原 gate 检查成立；HEAD 的 Rust/TCK 等 CI 也通过 |
| 可用性与稳定性 | 原 native 并发/非法输入/恢复检查和 OOM gate 通过；长时间饱和、断连取消、大结果序列化取消仍无完整结论 |
| 查询 p50/p95 | 当前环境的实测观察已验证；无新候选，未声称优化或回归恢复 |
| 加载 | head registry-ready 47,739.270 ms；启动至首次 oracle 完成 49,290.181 ms，各一次、文件缓存未受控 |
| build/save | head real64 443,173.160 ms，base 448,747.267 ms，差 -5,574.106 ms；单次报告观察，不能代替查询结论 |
| CPU | 原生查询 CPU 未测，≤5% 约束未获证明 |
| 峰值 RSS | 未测，≤5% 约束未获证明；OOM 通过不等价 |
| 端到端 | 有各阶段观察，没有新的 matched 累计候选 E2E 恢复证据 |

按用户本次明确的目标，优先级为正确性 > 可用性与稳定性 > 慢查询和复杂聚合查询的 P95 > build/save。P50 保留为辅助观察，不与 P95 并列为优化目标。后续同时满足 CPU 与 RSS 各不超过匹配基线 5% 的约束，不能互相抵消。既有 declared-type native64 加载约 1–2 秒及 CPU 的用户批准例外仍是限定的功能开销，不作为待消除 blocker；本次未重新测量该例外，也不将其扩展到查询或 RSS。

## 可复现证据与全部查询

本报告新增文件只包含文档、保留证据和离线复算脚本；没有性能优化实验，因此没有新增优化尝试编号，也未改写任何历史单图数据。

复算归档请求及全部分位数：

```bash
python3 docs/query-performance-evidence/2026-11-11/verify.py
```

复算完整响应需先下载上述 raw artifact，再传入解压后的根目录：

```bash
python3 docs/query-performance-evidence/2026-11-11/verify.py /path/to/extracted-evidence
```

脚本会核对 8,798 个请求、全部 pressure/isolated 统计；带原始 artifact 时进一步核对完整 body、receipt SHA256、结果一致及 64 图范围。SHA256 保存在 `audit.json`。原始请求完整保留于同目录两个 `.json.gz`，没有丢弃慢样本或失败。历史源代码定位以目标 SHA 为准；新增报告会改变工作区 tree，不应与被测产品 tree 混淆。

下表为当前代码对应 head 的全部 73 类观察，全部为 ms。每类 pressure N=20；有 isolated 数据者 N=40。破折号表示按协议未采样，不是零。按 pressure p95 排序，不代表生产流量权重。

| 用例 | pressure p50 | pressure p95 | isolated p50 | isolated p95 |
| --- | ---: | ---: | ---: | ---: |
| schema-relationship-histogram | 143.606 | 310.032 | — | — |
| shape-group-count | 182.226 | 250.179 | — | — |
| shape-with-aggregate | 191.033 | 208.443 | — | — |
| shape-count-traversal | 153.119 | 167.937 | — | — |
| shape-equality | 108.999 | 122.533 | — | — |
| targeted/global-wide-four-properties | 60.485 | 103.815 | 0.627 | 0.653 |
| shape-traverse-filtered | 69.052 | 89.612 | 0.932 | 0.989 |
| targeted/global-wide-class-pair | 5.954 | 61.413 | 0.632 | 0.669 |
| shape-distinct | 18.114 | 38.350 | 6.943 | 8.683 |
| dense/global-wide-wrapped-case-insensitive-distinct | 10.225 | 19.335 | 4.791 | 5.230 |
| shape-label-scan-field | 3.495 | 18.995 | 0.642 | 0.813 |
| dense/global-wide-four-properties | 5.132 | 13.583 | 1.386 | 1.449 |
| shape-all-nodes | 5.162 | 12.195 | 1.298 | 1.329 |
| schema-key-histogram | 5.604 | 11.321 | 1.398 | 1.447 |
| shape-function-calls | 5.787 | 11.268 | 1.503 | 1.522 |
| shape-order-by | 3.451 | 11.261 | 0.693 | 0.714 |
| shape-label-scan-method | 4.424 | 11.163 | 0.686 | 0.807 |
| shape-labels-fn | 4.644 | 9.807 | 1.064 | 1.088 |
| dense/global-wide-wrapped-case-insensitive | 4.474 | 9.480 | 1.565 | 1.634 |
| shape-label-scan-callsite | 4.871 | 9.478 | 0.735 | 0.770 |
| shape-wide-contains | 4.495 | 9.119 | 0.573 | 0.601 |
| dense/global-wide-parameterized-as-literal | 4.927 | 8.893 | 1.382 | 1.723 |
| dense/global-wide-aliased | 4.583 | 8.801 | 1.522 | 1.873 |
| dense/global-wide-provenance | 4.073 | 8.750 | 1.391 | 1.453 |
| shape-inequality | 2.304 | 8.710 | 0.665 | 0.703 |
| shape-coalesce | 4.093 | 8.582 | 1.058 | 1.079 |
| dense/global-wide-class-pair | 4.626 | 8.404 | 1.337 | 1.417 |
| dense/global-wide-caller-class | 3.807 | 8.266 | 1.234 | 1.282 |
| shape-cartesian | 3.034 | 8.234 | 0.445 | 0.475 |
| shape-label-scan-constant | 3.936 | 8.169 | 0.728 | 0.781 |
| shape-skip | 4.836 | 7.783 | 1.390 | 1.480 |
| shape-order-by-desc | 3.313 | 7.200 | 0.597 | 0.648 |
| shape-with-filter | 2.609 | 7.010 | 0.688 | 0.710 |
| dense/global-wide-callee-class | 3.890 | 6.948 | 1.234 | 1.344 |
| shape-case-expr | 1.819 | 6.905 | 0.623 | 0.665 |
| localized-middle/global-wide-four-properties | 2.938 | 6.874 | 0.829 | 0.933 |
| shape-in-list | 2.109 | 6.580 | 0.522 | 0.561 |
| broad-all-64/global-wide-four-properties | 3.978 | 6.352 | 1.366 | 1.437 |
| shape-regex | 2.659 | 6.054 | 0.654 | 1.094 |
| shape-union | 1.626 | 6.015 | 0.618 | 0.641 |
| shape-collect | 1.615 | 5.991 | 0.406 | 0.450 |
| shape-one-hop | 2.940 | 5.921 | 1.355 | 1.828 |
| shape-path | 2.767 | 5.849 | 0.904 | 0.954 |
| shape-null-check | 2.435 | 5.823 | 0.684 | 0.707 |
| zero/global-wide-class-pair | 1.528 | 5.799 | 0.422 | 0.453 |
| zero/global-wide-aliased | 0.812 | 5.790 | 0.401 | 0.431 |
| shape-two-hop | 2.105 | 5.662 | 0.774 | 0.973 |
| dense/global-wide-name-pair | 3.436 | 5.398 | 1.272 | 1.319 |
| shape-var-length | 2.021 | 5.314 | 0.686 | 0.711 |
| zero/global-wide-provenance | 1.167 | 5.293 | 0.405 | 0.433 |
| localized-late/global-wide-four-properties | 3.165 | 5.156 | 0.862 | 0.912 |
| zero/global-wide-wrapped-case-insensitive-distinct | 1.490 | 5.061 | 0.441 | 0.468 |
| shape-one-hop-typed | 2.768 | 4.999 | 1.251 | 1.556 |
| shape-id-lookup | 2.445 | 4.994 | 0.514 | 0.543 |
| targeted/global-wide-provenance | 1.354 | 4.887 | 0.638 | 0.660 |
| shape-incoming | 2.667 | 4.862 | 0.803 | 1.765 |
| zero/global-wide-caller-class | 1.237 | 4.570 | 0.422 | 0.447 |
| localized-early/global-wide-four-properties | 1.791 | 4.550 | 0.646 | 0.661 |
| schema-label-histogram | 2.419 | 4.529 | 0.854 | 0.920 |
| targeted/global-wide-name-pair | 2.108 | 4.520 | 0.620 | 0.656 |
| targeted/global-wide-parameterized-as-literal | 1.374 | 3.969 | 0.628 | 0.649 |
| zero/global-wide-parameterized-as-literal | 0.988 | 3.827 | 0.406 | 0.430 |
| targeted/global-wide-caller-class | 1.613 | 3.708 | 0.619 | 0.645 |
| targeted/global-wide-callee-class | 1.184 | 3.508 | 0.617 | 0.631 |
| zero/global-wide-four-properties | 1.306 | 3.506 | 0.452 | 0.546 |
| targeted/global-wide-aliased | 1.461 | 3.435 | 0.629 | 0.656 |
| shape-count-label | 1.747 | 3.421 | 0.380 | 0.412 |
| shape-count-all | 1.386 | 3.418 | 0.384 | 0.420 |
| zero/global-wide-callee-class | 1.194 | 3.406 | 0.416 | 0.447 |
| targeted/global-wide-wrapped-case-insensitive-distinct | 1.686 | 3.246 | 0.714 | 0.745 |
| zero/global-wide-name-pair | 1.067 | 2.985 | 0.422 | 0.454 |
| targeted/global-wide-wrapped-case-insensitive | 1.840 | 2.383 | 0.642 | 0.668 |
| zero/global-wide-wrapped-case-insensitive | 0.845 | 1.917 | 0.396 | 0.423 |
