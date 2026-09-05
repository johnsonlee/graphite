# 冻结 main raw DISTINCT 逐节点审计

结论：现有样本重复指向 raw DISTINCT 的节点处理，但还不能从大块 lambda leaf 分离出一个新的、已证明占主导的成本。136 已试过此循环的 range/iterator 替换；086 已试过小 exact set 的线性替代；138 已试过用 posting 投影绕开扫描，均不能作为待重试的新方向。未被 136 单独改过的 mapped 寻址/读取、List<Int> 取值与拆箱，以及 selected-only 投影 tuple 构造都有源码或栈证据；这些证据不等于优化收益，也不能解释之前拒绝尝试的退化原因。

本审计仅重读源码及既有三份 phase JSON，没有运行 Java、构建、性能任务或修改仓库。当前 HEAD 为 `aede4c82f66a925ba9df3fc8588c6e1399c17f61`；下列四个生产文件逐字节等于 frozen main `4e328b0109e13c896b74004823fb049fcb19251a`。完整输入 SHA256、栈、分类和复算结果见 `README.json`，复算命令 `python3 audit.py`。没有重新解码或重新 hash 大 JFR。

## 实际执行顺序

以下源码路径均相对仓库；简称 `Graph` 为 `graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedWebGraphBackedGraph.kt`，其余三个文件在同目录。

| 层次 | 已核实操作与源码位置 | 不能混淆的边界 |
|---|---|---|
| 查询准备 | Graph:397–403 先得到 exact matching string IDs；Graph:501–504 将谓词/投影属性转换为 `List<Int>`；Graph:542 在节点循环外构建每谓词 `IntOpenHashSet`。 | 不能把集合构建当成每节点成本。 |
| selected 准备 | Graph:506–535 已有本次调用内 String→ID 与 `(property,id)` membership 复用；转换 selected tuples，Graph:536 对空可行集合提前返回。 | 再引入相同局部缓存不是新机会；准备阶段的字典查询不等于 per-node 查询。 |
| 每 worker | Graph:555–559 创建结果、seenValues、复用的四元素 `IntArray`、预算缓冲器。 | 四元素数组不是每节点分配。 |
| 节点遍历 | Graph:565 调用 `MappedNodeTypeIndex.forEachIdWhile`；NodeTypeIndex.kt:59–80 先取得类型范围，再逐项 `buffer.getInt(offset)`，调用 Java `IntPredicate.test(int)`。 | mapped override 不走默认 `Sequence/drop/take`，也不走 `MappedNodeIdIterator`；没有逐节点类型判断或 Node 对象反序列化。 |
| 中断和预算 | Graph:566–573 定期检查 abort/interrupt，然后每个被检查节点 `accounting.consume()`；Graph:614 finally flush。 | 批量计费不代表可以跳过节点计费；短路/早退和异常必须保留 flush、取消与 worker 收尾。 |
| 四字段取址 | Graph:2448–2461 的 **inline** `withRawCallSiteStringIds`：先由 NodeOffsetIndex.kt:75–77 的 `getLong` 找物理 offset，再读 caller 参数数以跳过变长 descriptor，取四个字符串 ID。Graph:575–578 写复用数组。 | 此处 action 不应按非 inline Kotlin callback 推断每节点 Function1/装箱。源码链每检查节点含类型索引 1 次 Int、节点数据 5 次 Int、offset 1 次 Long，共 7 次绝对 mapped 读取；这不是硬件 load/miss 数，JIT 消除/合并和 cache 状态未知。 |
| OR 判定 | Graph:579–596 按原谓词顺序短路。Graph:580 取 `predicatePropertyIndexes[index]` 并拆箱，用它取 primitive array；Graph:581 查相应 primitive exact set。 | exact set 存在时直接返回这一谓词结果，不执行 `stringTable.get`、transform 或 fallback match-state。无 exact set 时才在每个未知字符串状态首次执行匹配；不能把两条路径成本相加。 |
| selected ID 筛选 | 仅谓词命中且 selected 非 null，Graph:599–603 构造投影 ID List 并用 HashSet 查完整 tuple。 | 这是每个谓词命中节点的投影工作，非所有扫描节点；List<Int> boxing 不代表所有值都新分配 Integer（小值可缓存）。独立属性命中不证明同一节点完整 tuple 命中。 |
| 可见值和 DISTINCT | Graph:604–608 按投影顺序解码、构造可见值 List、seenValues 去重；仅新的 distinct row 再查一次 offset。Graph:611 以本 worker targetSize 停止，Graph:666–672 按 worker 顺序合并去重。 | 不能按 node ID 重排。null/重复投影、首个物理 encounter 与 LIMIT、跨图全部 provenance 都仍须保持。额外 offset 读取不发生在每个未命中节点。 |

## 样本范围与守恒

输入为 `/private/tmp/graphite-distinct-phase-details/phase-{1,2,3}.json`，每份 34 outer query / 192 phase calls。读取其 `metrics[phase][metric].threadStacks`，独立验证 **186 个 metric 分区**的各线程权重和总权重，并核对 `application-summary.json` 全部 **26 行**。未重新推导 wall union，未把跨线程 phase duration 相加。

原分析器 `DistinctPhaseDetails.java:98–102` 对 `jdk.ExecutionSample` 每事件计 1，对 NewTLAB 计 `tlabSize`、OutsideTLAB 计 `allocationSize`。事件按时间落入既有 outer/phase 窗口（源码:44–48）；这里的阶段归属仅为时间重叠。应用线程仅包括 request worker、cypher scan、CallSite scan/segment；C1/C2 编译线程和其余线程另列，不能归因于某一查询阶段。

raw 范围使用方法名前缀 `MappedWebGraphBackedGraph.parallelRawDistinctCallSiteStringProjection`，**包含其生成的 worker 和 per-node lambda**，不能只匹配入口方法。inclusive 每栈每类别只计一次；各类别可重叠，禁止求和当作总量。leaf 按栈尾计，所有 leaf 权重之和严格等于 raw inclusive；未能展开的 inlined 工作仍可能记在 lambda leaf，不能逐行定位。allocation 数字是采样 TLAB 权重，不是精确已分配字节或对象数。

| 录制/阶段 | 所有 CPU | 应用 CPU | JIT / 其余 CPU | raw inclusive | raw 节点 lambda leaf |
|---|---:|---:|---:|---:|---:|
| 1 targeted initial | 151 | 76 | 66 / 9 | 75 | 32 |
| 2 targeted initial | 151 | 79 | 69 / 3 | 74 | 36 |
| 3 targeted initial | 128 | 66 | 58 / 4 | 60 | 30 |
| 1 dense provenance | 223 | 123 | 100 / 0 | 53 | 21 |
| 2 dense provenance | 155 | 87 | 67 / 1 | 46 | 17 |
| 3 dense provenance | 157 | 80 | 73 / 4 | 28 | 5 |

Targeted initial 的 raw 为应用样本 209/221；其中 98 个样本叶子仅落在大节点 lambda，无法把它们分摊为数组、分支、寻址或 membership。Dense provenance 的另一个独立大类是 mapped predicate discovery，三份 inclusive 为 69/33/49，不能把它算成节点扫描。以上窗口应用 CPU 的 validator 为 0；这是观察值，不证明整个程序无 validator 调用。

## 更具体的可重复证据

| raw 内类别 | targeted initial 三份 inclusive（括号为 leaf） | dense provenance 三份 inclusive（括号为 leaf） | 解释 |
|---|---|---|---|
| `IntOpenHashSet.contains` | 6(3), 6(6), 6(4) | 5(5), 3(3), 9(9) | 是 primitive membership/hash 路径，不能称为 ID boxing 分配；086 已试其小集合替代。 |
| mapped 读取实现 union | 4(4), 5(5), 3(2) | 5(5), 2(2), 1(1) | 包含 DirectByteBuffer / ScopedMemoryAccess / Unsafe 的 getInt/getLong 等栈；无法据此证明页错误、cache miss、随机访问或 bounds check 是主因。 |
| `MappedNodeOffsetIndex.offset` | 3(0), 3(0), 1(0) | 0, 0, 0 | 与前行有重叠；有源码执行义务，低/零采样不能推断调用少或可忽略。 |
| `ArrayList.get` 或 `Integer.intValue` union | 6(3), 4(4), 5(4) | 1(1), 0, 1(1) | 支持列表读取/拆箱仍执行；仅方法栈不足以区分每个 List 存取点。列表初始化 boxing 与 per-node 拆箱不是同一件事。 |

Selected dense provenance 的 `Integer.valueOf` allocation leaf 权重为 **786,432 / 1,048,576 / 786,432**，全部栈含 per-node lambda；与 Graph:600–602 的投影 ID List 构造相符。完整原栈在 JSON。对应 raw 总 allocation 权重为 12,058,624 / 11,272,192 / 9,175,040；不能把其中 StringTable 准备/解码、ArrayList、iterator 等全部归到 ID tuple。Targeted initial 的同一 Integer boxing leaf 权重为 0，并且 null-selected 源码路径直接跳过该构造。因此 selected tuple 分配不是一个可直接解释 targeted initial 主体工作的结论。

Range/iterator allocation 在这批 targeted initial 中为 4,456,448 / 7,340,032 / 2,883,584，dense provenance 为 4,456,448 / 4,456,448 / 3,145,728。它确有采样支持，但已经由 136 直接实施和拒绝；这些不同录制的权重不用于重开验收。

## 历史边界和当前可下的结论

引用 `docs/wrapped-case-insensitive-query-optimization-attempts.md`：

- **086，:2677**：将至多 8 个 exact IDs 改线性比较，work 不变，配对 P95 0.95x/1.08x/0.88x、重复行回归，已拒绝。当前 contains 样本不能重启同一假设。
- **130，:4120**：原始 bounded raw-leading `rawCallSiteStringProjection` 的 primitive loop，属于另一条循环；保留它不代表 parallel DISTINCT 的相同写法已获益。
- **136，:4887**：正是这里的 `predicates.indices.any`→while。已有 bytecode 证明 `nextInt` 为 primitive、`any` inline；其主要移除对象为 range/iterator，而不是谓词索引 boxing。三组中一组 P95 退化而 strict progress 失败，已拒绝。列表拆箱、buffer 寻址及 selected tuple 构造未被该变更消除。
- **138，:5160**：稀疏 initial posting merge + selected 最短 posting，绕开一部分上述 raw 工作；old34 改善后仍因真实 v3 重复回归拒绝。不能把原 raw 热点存在当成重做 posting 投影的许可。其冷回归原因也未被归因。
- **020，:550**：曾消除 selected **anchor posting probe** 的数组/key 分配并用 primitive hash+精确 tuple 比较；不是本循环的 List 投影，但属于相关已试方案。已回退，不能照搬历史局部收益。
- **123/124/125/128，:3847/:3890/:3931/:4044**：分别涉及 mapped validation 的读取时机、选中整段校验、全量 rank/validation、有限状态缓存。这些不是 raw 节点循环的新成本证据；任何寻址改动都不能暗中回到 eager 全图 offset/rank/index 方案。**139，:5282** 的 validator callback inline 亦属于不同路径且已拒绝。

可独立保留的观察是：扫描仍承担“类型 ID→物理 offset→变长字段定位”的读取链；谓词索引仍通过 boxed List 读取；selected 命中节点仍组装投影 ID List。这些分别尚未由 136 的 while 变化直接处理，也不等于 138 posting 方案之外已经成立的新优化。当前证据只能定位到操作类别，不能确认它们是未覆盖的主导瓶颈，不能预测加速，更不能承诺旧 34 查询 P95 或 10x 目标。保持全部取消、计费、物理顺序、完整 tuple 验证和跨图 provenance 义务，不实施 Attempt 140。
