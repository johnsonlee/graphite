# 按属性合并 raw DISTINCT 精确 OR 的可行性与历史审计

**排除为下一候选，不启动 141，也不为它新增 profiling。** 旧 34 条决定 P95 的 targeted/dense DISTINCT 都只有一个词跨四属性，每属性已最多检查一次。按属性 union 不减少这两条路径的 membership 次数、raw 节点读取、exact 字典发现或 provenance 图数，不能作为当前去池/10x 的实质工作量下降方向。

这里没有修改生产、构建、Java、计时或新录制。`audit.py`/`audit.json` 保存三本 chronological log 的搜索结果、冻结源码 hash、实际 query 文本和可重算的每属性谓词数量。四个核查生产文件逐字节等于 frozen main 4e。文本检索没有发现同属性多个 exact 集合 union 的完全相同历史实现；这只是已检索材料的结论，不能证明未归档试验不存在。

## 真实目标是否有重复检查

| 已核对的 catalog 查询 | 按属性谓词数 | 最多 membership 检查数：原→union |
|---|---|---:|
| 旧34 wrapped DISTINCT targeted | 1/1/1/1 | 4→4 |
| 旧34 wrapped DISTINCT dense (`get`) | 1/1/1/1 | 4→4 |
| v3 六个 `or-four-*-distinct`：单图早/中/晚、两图、55图、64图 | 4/4/4/4 | 16→4 |

数字来自实际 catalog 的 WHERE 子句，不是性能测量。16→4 仅对执行到末尾的 OR 检查次数成立；早命中本来会短路，具体动态减少量未知。它不减少每节点取四字段，也不减少扫描节点或补全来源的图数。ordinary rows 不属于这个 DISTINCT 方法。即使为无重复组设原路返回，它最多维持旧34检查数量；将其解释为改善旧 P95 的方案仍缺机制。

若以后单独关心多关键词纯 OR，可以先用已有真实导出做无计时的候选集合基数/overlap 和短路上下界筛选，并报告所用参考扫描不是生产线程的实际扫描前沿。当前不足以值得补采或实施；不要把额外数据采集变成继续微调的默认步骤。

## 语义上可行，但需要严格范围

源码路径均相对仓库。`Graph` 简称 `graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedWebGraphBackedGraph.kt`，`View` 和 `Index` 分别为同目录 `MappedCallSiteStringIndexView.kt` / `MappedCallSiteStringIndex.kt`。

- **只适用完整 exact 集合。** Graph:397–414 先取得每谓词 exact IDs；Graph:542 在循环外建 IntOpenHashSet，:579–596 按原序 OR。对属性 p 定义 `U[p]=union(exactIds[i] where property[i]=p)`，则原 OR 与 `exists p: rawId[p] in U[p]` 的真值相同。必须保留属性维度；全局不带属性的 union 会错误接受“字符串在另一属性才允许匹配”的节点。四属性 mask 也必须检查对应位，不能只判断 mask 非零。
- **transform/literal 不能在 exact 发现前混为一谈。** View:69–81 的 key 是 transform/mode/expected，:205–240 再精确验证；:516–518 仅接受适用的 CONTAINS 长词和 raw/LOWERCASE 条件。Index:366–377 有不同的 retained 支持边界。只要每个集合独立、完整、精确，materialized 后在同属性上合并不同 transform 的 true-ID 集合仍是合法 OR；不能因此共享错误的 lowercase/raw cache 或丢失字面量大小写。Graph:3079–3094 对非 ASCII 回到准确 lowercase 语义。
- **null exact 返回不是空集。** 不支持的短词、mode、transform、缺 sidecar、损坏索引应保持原 fallback；不能拿可编译子集 union 当完整 OR。Graph:534–541 的非 exact match-state 路径须继续按 `StringPredicateKey(transform,mode,expected)` 隔离，不能照搬按属性单个状态缓存。
- **不是 compound AST 重写。** 存储方法表示平面 OR，不能把 `(A AND B) OR (C AND D)` 展平成四个 OR。任何保留 residual 的上层计划也不能因 union 而丢弃 residual，不能把动态 Annotation 同名属性混入四字段 CallSite 假设。Graph:377–384 限定实际 CallSite 类型和支持属性。
- **selected 不变。** Graph:506–536 的 selected 字符串 ID/属性 membership 必要条件、:599–603 的完整 tuple 检查必须保留。四个单值各自存在不证明同一节点 tuple 存在；不能用 per-property flags 替代完整 selected tuple。null/graphId/重复列投影仍按 :604–608 与上层来源封装保留；四个实体字段的有效 ID 范围不能与 null sentinel -1 混用。
- **顺序与 provenance 不变。** 只能改匹配真值计算，保留 :565 的物理遍历、:607 的 distinct、:611 的 LIMIT 和 :666–672 的有序合并；不能以集合顺序重排结果，不能全局 LIMIT 满后省略匹配 tuple 的后续来源检查。

## 资源、预算、取消不是免费前处理

原始 exactMatchSets 在 Graph:542 构建，没有为每次 hash 插入单独调用 work consumer；真正 raw 节点计费在 :573，失败/finally flush 在 :614 和 BufferedGraphWorkConsumer:3106–3124。因此按属性合并可能减少 CPU membership 调用，却**不会自然减少现有 graphWorkUnits**。不能删除原节点、字典或 CRC 的计费去制造下降。

记字符串总数 S、每属性 union 大小 U[p]、输入 exact 数组总长 M。稀疏集合容量随 `sum U[p]` 和 hash 表负载变化；四份 dense bitset 仅位存储就约 `4*ceil(S/64)*8` 字节，另有对象头，单份四位/字符串 ByteArray 约 S 字节；如用 IntArray 则约 4S。它们不一定比稀疏集合省内存，且置零/合并会增加准备工作。不能同时无界保留旧 P 个 sets 和新结构，不能跨图复用局部 string IDs，不能偷偷加持久缓存。

现有 `MappedCallSiteStringIndexMemoryBudget`（Index:2157–2205）可做 reservation/释放，但不会自动覆盖这里的新临时数组。可选准备若需要新预算，应明确大小上界、并发图数、拒绝后原路 fallback 与 finally 释放；不能把 O(S) 初始化和 O(M) 合并藏成无界、不响应取消的“免费”阶段。如何为新增 storage-ID 预处理计费须明确，不能擅自宣称沿用未变化工作量。Graph:566–573 的 abort/interrupt 检查、所有 worker join/清理，以及 View 已有验证/CRC/cancel 时序均不可弱化。

## 历史对应，避免换名字重试

以下引用 `docs/wrapped-case-insensitive-query-optimization-attempts.md`：

| 记录 | 已有方向及与本假设的关系 |
|---|---|
| 004 :88、006 :194（保留） | 每查询字符串状态、跨属性同 key 共享，融合节点 OR 扫描；不能再声称 union 首次避免“每谓词扫描整图”。 |
| 053 :1612、056 :1738（保留） | exact-ID raw 扫描，以及同 transform/mode/term 的 property-independent 候选发现复用。View 已做此复用；再次合并同词发现不是新方向。 |
| 086 :2677（拒绝） | 小 exact set 改线性数组匹配；热点 contains 不证明换表示获益。bitset不是字面相同实现，但若只在旧34替换会员测试表示，属于同类微调，未减少逻辑工作。 |
| 103 :3178（拒绝） | 将 raw-leading 谓词上限从4放宽128；本方案不得捎带改变多词路径准入。 |
| 130 :4120 | 保留的是另一条 bounded raw-leading primitive loop；其中 shared matcher 特化等局部方案也被拒绝。检索材料没有该局部特化的完整源码，不能据名称断言绝无交叉；不能复用其局部收益。 |
| 136 :4887（拒绝） | 正是 raw DISTINCT 的 range/iterator→while；union 若重新写遍历，不能包装成该已失败改动再试。 |
| 138 :5160（拒绝） | selected 最短 posting + sparse initial 投影，属于绕开扫描的不同方向；按属性 union 不能捎带恢复。 |
| 140（最新记录） | predicatePropertyIndexes 改 IntArray，旧34通过但 v3 重复退化后明确回退；不能用本次按属性名称重新引入它，亦不能将 BCI 283/291 当排他时间或收益。 |

另外 `docs/cross-graph-cypher-optimization-attempts.md:569` 的 Attempt012 是按 node ID 的 lazy candidate union；`Index:1389` 的 matchedNodeBitSet 属于 node-ID 空间。它们不是 raw string-ID per-property union，不可直接借用或混淆 ID 空间。

## 现有测试覆盖与缺口

`ParallelDistinctDisjunctionTest.kt:21` / :41 已验证四个不同词×四属性、exclusive/overlap、物理顺序/LIMIT、selected，有/无 sidecar；:63 / :83 验证 transform 和不同词状态隔离。不过后两项没有 sidecar，同属性多词混合 raw/LOWERCASE 的 exact-union 场景并未被它们独立覆盖。

`GraphStoreTest.kt:3392` 的整组 mapped split 测试包含预算失败、worker 中断/收尾与 selected/null 投影；:4912/:4944/:5082 覆盖 raw/lowercase cache、Unicode、query-local/work；:5246 覆盖索引预算满后原 fallback。这些不能自动证明未来新增 union/bitset 准备阶段的取消、内存拒绝/释放、同属性 mixed-transform 完整性。当前不新增测试或实现，因为针对旧34目标已无工作量下降理由。

**最终结论：从下一候选队列丢弃。** 多关键词纯 OR 的合并真值有合理的独立数学假设，但没有证据能削减旧34 P95 驱动工作；目前只值得保留这份范围判断，不能据此启动141、补采或声称去池/10x进展。
