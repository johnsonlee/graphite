# Attempt 141 源码独立审查

**PASS：未发现阻断缺陷。** 只读比较 isolated clone 与 parent b94b8caa；没有执行 Java、测试、构建或测量。把 imports、exact structure 选择、热循环位表检查及新 factory 四处改动反向还原后，整个原生产文件逐字符等于 parent；其余 core/cypher/webgraph production 无变化。精确 hash 与 measurement-plan hash 在 source-review.json。

- **容量与分支**：factory 位于 MappedWebGraphBackedGraph.kt:3076。实际调用 HashCommon.arraySize(ids.size, Hash.DEFAULT_LOAD_FACTOR)，按每个旧 set 的 (n+1)×4 算 payload，以 Long 在 S 饱和。每步累积<=Int.MAX_VALUE，单项<=约4 GiB，相加不可能 Long overflow。稀疏只做 property/长度/capacity 检查，不扫 candidate IDs。
- **构造与回退**：dense 在分配前检查全部 candidate ID 范围，非法即返回 null，调用方走完整旧 hash 构造；没有丢弃非法 ID 后发布部分表。合法构造的 bit 是四个物理 property；同 property 多谓词 union 保持最终 OR 真值，可能提前短路但没有新增 per-predicate work 语义。对节点 ID 的安全范围 false 与合法候选 hash 集中不可能包含该越界 ID 一致。
- **变化范围**：原 predicate indices.any、属性 List、selected feasibility/null 早返回、字符串/属性 membership 缓存、lazy raw 分支、per-node consume/finally flush、调度/pool 和 worker 生命周期都未改。整个源文件反向还原验证比仅核对局部片段更严格。
- **预算选择合理**：新工厂只遍历已经生成的 IntArray，没有 graph/stringTable/posting 读取；原 IntOpenHashSet(IntArray) 构造也不计 storage work。Graph.kt:61 的约定是 storage item inspected，不能把每次临时内存遍历自动再计一份 graph work，更不能改测试掩盖真正新增存储读取。本次没有这种存储读取，所以不传 workConsumer 是合理选择。额外成本仍必须记录：完整 range 预检 + 填表两遍候选 IDs、S-byte 零初始化，不能称作免费。
- **取消边界**：进入 factory 和 range/fill 每个数组的周期位置读取 isInterrupted，不清除 flag。仅置 CypherCancellationSignal 而不 interrupt 的取消不会在 factory 被发现，仍由后续原节点 accounting/flush 检查；不声称即时 signal polling 或响应时间不变。先前计划中的 consume(0) 只是可选增强建议，不是 storage 计费要求或本次阻断条件。ByteArray 零初始化本身也不是可中断分段过程。
- **验收不变**：measurement-plan 保持原 34 三对每组严格 P95 进步、完整 oracle/逐行/资源门槛，之后 v3 无重复回归，再 exact-head CI。源码通过不是测试或性能通过，不证明去池/10x。

只读查看了 8 个新增 helper tests 的范围；未把测试源码存在当作已执行证据。构建、实际 callback bytecode 和真实数据结果由根线程独占执行及后续核验。
