# 已拒绝 Attempt 138：冷态纯四词 OR DISTINCT 源码对照

**138 的拒绝结论不变。** 此文解释 `or-four-broad-distinct` 的工作路径，不是重新验收，也不提出下一项实现。原三组 base → candidate 为 149.365542 → 185.582709 ms（+24.25%）、150.640750 → 167.722459 ms（+11.34%）、152.262625 → 192.266333 ms（+26.27%）；第一、三组触发预设重复退化规则。新的 profiler 录制只能诊断，不能替换原始结果或重开 138。

以下行号明确区分冻结 main `4e328b01` 与已拒绝 candidate `470df7ce`。`Graph` 指 `graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedWebGraphBackedGraph.kt`；`View` 指同目录 `MappedCallSiteStringIndexView.kt`。JSON 保存完整路径、两版本 SHA 与方法行号。

## 真实查询与调用边界

`oracle-v3/catalog.json` 中这条查询是四个不同关键词分别匹配全部四个 lowercase 字段，合并为 16 个 OR predicate，投影完整四字段，DISTINCT LIMIT 200。完整命中 55 图、50,461 节点、18,915 distinct tuples；但返回的 200 个 tuple 的完整 provenance **全部只有 fixture-android-00**。55 是全量谓词命中图数，不是这 200 行的来源图数。

首图有 100,606 CallSites、3,370 个匹配节点、1,608 个 distinct 匹配 tuple，足以填满 LIMIT。既有 `QueryPipeline.kt:2180–2268` 的 balanced 流程先执行首图 initial（selected=null）；达到 LIMIT 后，`:2270–2310` 为剩余 63 图检查选中 tuple 的完整 provenance。QueryPipeline 未改；未标注 node label 时的非 CallSite 补查也未改。该推导以现有 64 图、默认 balanced worker 配置和有效持久化索引为前提，实际调用次数仍应由 trace 核对。

## 两版本每阶段的工作差异

| 阶段 | 冻结 main | 已拒绝 138 | 源码位置 |
|---|---|---|---|
| 取得 mapped view | 先加载/校验，已有 view 则复用 | 时序相同；selected 检查仍在取得 view 后 | Graph main:396、1659；candidate:396、1738 |
| 首图 initial 的 exact string discovery | 执行；得到 16 个 predicate 对应 ID 数组 | 同样执行 | Graph main:397–403；candidate:412–418 |
| 首图 initial 的额外探针 | DISTINCT 此处没有 occurrence 探针 | 增加 `exactMatchesCanFillLimit`，posting occurrence 累加至 200 即返回，拒绝 sparse helper | Graph candidate:419–434、490–503；View candidate:215–239 |
| 首图投影 | 原 parallel raw DISTINCT | 对本查询仍是原 raw：3,370 个实际命中意味着有效完整 posting 的 occurrence 上界不可能小于 200 | Graph main:404；candidate:434；raw main:481 / candidate:560 |
| 其余图 selected | 先 exact discovery，再转换 selected；必要条件为空则早退，否则 raw 扫描 | 先 `selectedProjectionHits`；成功包括空结果都直接返回，省去 discovery 与 raw；不支持/坏 posting 返回 null 才进入原 fallback | Graph main:397–410、506–536；candidate:397–411 |
| selected 的字符串与成员解析 | 每次调用的 `HashMap<String,Int>` 与 `HashMap<Long,Boolean>`，重复值和属性成员关系复用 | 同样是每次调用内复用，另构造四 ID 数组及重复列检查 | Graph main:506–535；View candidate:94–96、152–183 |
| selected 的谓词验证 | raw 逐节点对 exact ID set 做短路 OR；predicate discovery 已验证字符串 | 对通过 tuple 可行性检查的每个 tuple 调 `stringMatches` 短路 OR | Graph main:579–600；View candidate:108–114 |
| selected 的 posting 工作 | raw 直接按 nodeTypeIndex 分段遍历，不为此 raw 路径读取/验证 selected property posting 顺序 | 每个可行且谓词匹配 tuple 查四个 posting range，选择最短；完整验证该 range 顺序，再逐节点比较完整四 ID，遇到第一次完整 tuple 命中即停 | View candidate:115–125、134–150、185–197、292–333 |
| 输出顺序 | 各 raw 分段保持物理序，再按 worker 分段序合并；外层仍做 source sort | selected 收集后按 encounterOrder 排序再 take LIMIT；外层不变 | Graph main:555–672；View candidate:131；QueryPipeline:2226 |

`exactMatchingStringIds` 的缓存 key **不含 property**，只有 transform/mode/expected，所以本查询 main 每次调用最多进行四组独立关键词 discovery，而不是 16 次完整 discovery（View main:69–81，candidate:200–212）。每个关键词先查 trigram spans，遇到不存在的 trigram 可以立即返回空；否则取最短 span，解码候选字符串并验证 contains（View main:205–240 / candidate:336–370）。因此不能把“16 OR 分支”直接当成 main discovery 成本乘 16。

候选的 sparse initial helper 在其他稀疏查询确会调用既有 `matchingNodeIds`：先对所有相关 ranges 做完整校验，再以 PriorityQueue 按物理 node offset 合并、去重复 node ID，最后按投影值 distinct。位置为 Graph candidate:503–525、View candidate:242–290。**但首图已经足量的本查询不会走这一段 merge/projection**，不能用它的理论节省解释本次 55 图回归。

## 新增开销与不能夸大的节省

`StringTable.kt:40–53` 的 `findId` 在已加载表上用二分搜索；每次比较读取并转为 String。候选没有新增跨图缓存，每张图 ID 空间不同；main 原 raw selected 转换已具有同样的 invocation-local 字符串与成员缓存，不能把 138 描述为“首次消除每 tuple 重复 findId”。

这个 selected 集合包含 189 个互异字符串，四个属性各有 14 / 81 / 28 / 105 个互异值，共 228 个 property/value 组合。两版本在一次完整遍历且没有提前拒绝时，findId/membership 唯一 key 数受这些数量限制；每图会重新开始，实际调用会因早退更少。不能直接乘 63 后宣称实际访问数或耗时。main 还可能因 exact discovery 全空而在 selected 转换前返回，candidate 则先做 tuple 可行性工作。

候选在已有 membership 成功后，`selectedTupleAnchor` 仍为每个可行 tuple 逐属性调用 `postingRange`，每次再二分搜索并创建 range；没有复用各 tuple 间的 postingRange 结果（View candidate:185–197、324–333）。验证缓存只保存某个 property/row 的 valid 标志；首次验证会分配覆盖整段的 `LongArray`、读取每项 node order 并严格检查递增，然后才允许 LIMIT/首命中退出。cache hit 避免重验，但不缓存 tuple 的命中结果；碰撞或预算不允许缓存时可能再验证（View candidate:292–321、694 之后）。这些都是相对原 raw selected 扫描新增的工作，但实际 chosen ranges 的长度和缓存命中尚未知。

对 selected 字符串求谓词值也与 main exact ID set 判断不同。当前 200 个 tuple 若通过可行性检查，按 catalog 的 16 分支顺序首次命中位置分布为第 5 项 173 个、第 6 项 4 个、第 7 项 3 个、第 8 项 20 个。这只是对已知值的静态核验，**不是 63 个图实际 stringMatches 调用数**。`stringMatches` 调用 lowercase 转换（Graph main:3064–3076、3127）；是否产生新 String、JIT 是否消除对象，以及与 MutableString discovery 的相对分配，要看采样，不能从调用名推断精确 bytes。

这 200 个 tuple 在其余 63 图都没有真正命中。对有效索引，candidate selected 分支最终返回空，所以本查询不能把“大量非空结果排序”列为已证实的主耗时；空列表收尾仍会执行，但主要可能发生的额外工作在可行性/谓词/anchor/验证/匹配之前。某图全量有 OR 命中，不代表任何选中 tuple 在该图同一节点出现，拼接式成员存在也不能跳过四 ID 校验。

## 校验、预算和冷暖差异的界限

两版本 full mapped load 的 header/content identity、完整 CRC、property IDs/ends 范围、posting node ID 范围及 trigram 顺序校验完全未改（View main:263–433、436–514；candidate:394–564、567–645）。138 也没有留下 137 的 primitive SAM 改动。冷态必须承担原完整 validator 成本，不能说 selected 新路径跳过它。完整 property posting 物理顺序验证则是另一义务：candidate 在使用该 posting 前额外调用既有 `validatedPostingCursor`，不能由“CRC 正确”代替，更不能在首命中或 LIMIT 1 后才验尾部。

取消检查保留于 load 每 chunk、tuple 每轮及 posting 轮询；budget 经既有消费者传播，finally flush 不变。新增 selected 每属性/谓词/查 posting 计费，而 main selected 转换本身并不逐列计费，`findId` 二分也没有 workConsumer 参数。因此 graphWorkUnits 的单位不是 CPU 指令、墙钟或分配 bytes，两个路径的工作数不可机械换算耗时。

原 v3 本查询三次的 work 都是 57,697,051 → 57,699,276（+2,225），但延迟分别多约 36.22 / 17.08 / 40.00 ms。差异不能仅由这个计数解释。旧 34 回放的后部 dense/targeted 则有已有 mapped view 复用，已观测 work 分别 283,544 → 22,365、106,706 → 2,370；它们的谓词、tuple 分布和生命周期也不同。先前 dense 窗口 validator 为 0 个 CPU 样本，既不代表绝对零验证，也不能外推 per-query-cold 的效果。

源码允许两种权衡同时存在：节省原来的 discovery/raw 扫描，以及增加 tuple 准备、逐 tuple 谓词、range 查询与整段顺序验证。**哪项占此次回归的多少目前未知**；冷态共享 validator、JIT/GC 与线程活动更不能仅凭共现归因。root 的一次 base / rejected138 录制应核对这些栈的样本权重及边界，不用录制延迟重判已拒绝的 138。本审计未运行 Java、构建或新性能任务，也未重新 hash 整个 JAR/图文件。
