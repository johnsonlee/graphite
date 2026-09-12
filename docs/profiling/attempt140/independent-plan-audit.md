# Attempt 140 独立方案、覆盖与门槛审计

结论：当前候选符合单一表示方式变更，未发现超出授权字面改动的生产语义变化。
本审计只读源码、既有 profiling 和协议文件，没有运行 Java、构建、profiler 或性能任务；
构建/测试仍由专属构建 agent 执行，本审计不把覆盖存在写成候选已通过。

## 改动边界与证据

独立检查正常 clone 的 130 个 main/JMH 文件：相对 frozen main
`4e328b0109e13c896b74004823fb049fcb19251a`，只有
`MappedWebGraphBackedGraph.kt` 改变；168 个 test 文件与 parent
`aede4c82f66a925ba9df3fc8588c6e1399c17f61` 相同。
以冻结文件执行唯一精确文本替换，与候选整个文件逐字节相同：
`parallelRawDistinctCallSiteStringProjection:502` 的 `predicates.map` →
`IntArray(predicates.size)`，按相同位置映射 `requiredCallSiteStringPropertyIndex`。
初始化从迭代 List 改为按索引读该 List；对于内部稳定有序的 predicates，得到相同属性序列。
每节点 `predicates.indices.any`、OR 顺序/短路、exact membership、fallback match-state、
selected/null-selected、投影列表、预算/取消、worker 收尾以及调度均不变。
未改其它三个同名局部索引准备处，没有持久化格式、缓存或池的变化。
这是生产源码相等性检查，不等于宣称整个 class 字节码不变；生成 callback 的捕获类型会改变，
实际 Code 长度和调用点仍须由构建后的机制凭据确认。

新证据是对已有冻结 JFR 补出的 source/BCI/frame metadata，不是新候选性能测量。
独立从 `raw-source-lines/source-mapping.json` 重算 targeted initial 的 98 个节点 lambda 叶样本：
谓词属性索引区域 18 个，其中 BCI 283 `List.get` 为 8 个，BCI 291 `Number.intValue` 为 8 个；
dense provenance 对应区域 3 个。它定位了一个具体访问点，不能将 18 个样本当成独占指令成本、
可节省比例或整体瓶颈占比。这里优化的是每节点读取/拆箱边界，不可声称消除每节点 Integer 分配。

136 修改的是同一方法的 **range/iterator OR 遍历 → while**，该轮因第一组 P95 无严格进步已拒绝。
140 保留那段遍历，只改属性索引存储，两者是不同的操作；136 的 range allocation 权重和任何
历史局部收益不得转移为本轮收益。解释/C1 帧标签只描述带 profiler 的原录制，不能证明无 profiler
验收时的 JIT 状态。138 posting、139 validator 方向同样不被带入或重新接受。

## 已有测试的实际覆盖

| 范围 | 可核实的断言 | 覆盖边界 |
|---|---|---|
| `ParallelDistinctDisjunctionTest:21,44` | 四关键词 × 四属性的 16 谓词；独占/重叠 mask；stored order、DISTINCT、LIMIT；selected 反序输入/缺失 tuple/null-selected；有/无 persisted index；明确 `callSiteParallelScanCount == 1`。 | 单个 mapped graph，固定四列投影；有 sidecar 走 exact sets，无 sidecar 走 raw match-state。 |
| 同类 `:62,87` | raw/lowercase 不共享错误状态；不同 expected terms 隔离；谓词反序、selected 和 complete miss。 | 不能将这类 correctness 图用于性能结论。 |
| `GraphStoreTest:302` 与 `:3392` 内部断言 | retained/mapped exact、raw fallback 周边；split DISTINCT 的 source order/LIMIT、graphId null 占位、部分 selected、零后台 worker；预算异常及真实中断后活动 worker 为零、异常/中断标记保留。 | 各组合分散在既有测试；并非所有断言都执行本次修改的 private 方法。 |
| `CrossGraphCypherExecutorTest:3114,3160` | 跨图 OR DISTINCT 的 source order、LIMIT、完整 provenance。 | 是既有两属性 OR 调度/合并测试，不替代四关键词 mapped 多图 control。 |

没有把覆盖说成完备笛卡尔积：专门的四词 unit tests 没有同时交叉 arbitrary projection
permutation/duplicate/null 与多图。相关 source paths 没有改变，现有 broader tests 保持；
必须通过规定的真实 36 查询全输出 control 才能补上本轮四词单/多图正确性证据。

## 真实覆盖与不变门槛

直接检查固定 v3 catalog：36 查询，其中 **12 条纯 `A OR B OR C OR D`**，每种均有 rows / DISTINCT：

| 纯四词逻辑场景 | 实际命中图数 |
|---|---:|
| single-early / single-middle / single-late | 各 1 |
| few-early-late | 2 |
| broad | 55 |
| all | 64 |

这里是谓词真实命中图数，不是执行器预先选入的 64 图数。全部 pure-four 对应 expectedRows、
值序列和每行完整 graphIds 已存在于冻结 catalog；尚未声称候选已核验它们。

`run-old34-pairs.py`、`run-v3-pairs.py` 与 139 runner **逐字节相同**；fixture manifest hash
仍为 `fe66cc84f6d8ee95c49b49ad500f921b304f0160c2ae094621683bb4db94ea6b`。
计划保持：构建/tests/detekt/JMH exclusions 和 bytecode 检查结束后，先执行一次真实 36 查询
完整值/顺序/provenance control；再按 C/B、B/C、C/B 运行 old34 三组，核验全 14 字段 oracle，
保持 regression 界限且要求**每组完整 global P95 严格进步**。任一组不进步即拒绝。
仅 old34 全过后再执行 v3 三组，按同查询至少两组同时 >15% 且 >1 ms 拒绝。
每 query 三个样本不标 P95，不因 old34 过关豁免新覆盖失败。

条件顺序由 root 统一调度；原样 v3 runner 本身没有读取 old34 verdict 的自动前置断言，
不能脱离调度直接当作获准启动 v3。任何计时期间均不得并跑其它本地 Java/build/profile/export。
只有本地全过才进入 exact-head 全 CI；本地失败保留原值、单 attempt commit 后显式 revert，
不 retry-to-green。139 已永久拒绝，139 回退 CI 的 Method/global/routing 失败仍保留，
不被相同 recorded JAR hash 豁免，不作为本轮候选通过证据。
去掉全部 CallSite 池与 frozen-main 全局 P95 10x 仍是未完成的最终目标。

完整输入 hashes、逐项范围核对和 pure-four 清单见 [independent-plan-audit.json](independent-plan-audit.json)，
只读复算入口 [independent-plan-audit.py](independent-plan-audit.py)。
