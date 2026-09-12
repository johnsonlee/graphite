# Attempt 141 独立构建与机制审计

核验通过。`independent-build-audit.py` 独立读取原 XML、Git 源码、JAR class bytes 和已导出的反汇编，不调用根线程的分析脚本，不运行 Java、构建或查询。详细输入哈希和结果见同名 JSON。

- 7 个 XML suite 共 **195 tests，0 failure/error/skip**；包含全部原 187 项、此次 8 项 factory 测试，以及既有 4 项真实持久化接口四词 OR 正确性测试。XML 案例名、内容哈希均与构建收据和原 build 输出吻合。
- Java 17、ActiveProcessorCount=4 的 WebGraph test、detekt、JMH packaging 和 test exclusion 命令及成功日志匹配；再次比较 candidate JAR 与编译后的 Graph class，并验证测试 class 路径不在 JAR 中。
- 全仓库 130 个 main/JMH 文件中仅 `MappedWebGraphBackedGraph.kt` 不同于 frozen main；源码 diff 与保存记录逐字节相同。8 个无关 untracked 文件的大小、mtime 和 hash 均未改变。
- candidate JAR SHA256：`11b8e055adf26bf5f7d18c0d2015f8b938d0ed0b48b96eb7899715f4b70e2af0`，权限 0444。受保护 baseline JAR SHA256 仍为 `a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe`。

工厂先用原每个 IntArray 的长度计算 HashCommon 默认负载因子对应的 key-array payload，饱和求和只影响安全比较。稀疏、参数不一致、非法 property 或 candidate ID 返回 null。有效 ID 全量预检发生在分配前；每个数组的预检和填充都按 1024 个 ID 检查线程中断，入口也检查且不清除中断标志。新增预检、零初始化和填充均有实际内存成本；不增加 storage work 计费的选择已明确记录，原 hash 构造同样没有逐 ID consume。不是整个 preprocess 工作不变。

按属性合并的是已经完成各自 transform/mode 匹配的 ID 集合。某个 predicate 可因同属性另一个 predicate 的 ID 提前为真，但完整 OR 结果不变；已有 4096 个四字段组合枚举独立比较原谓词 OR 和 union 结果。各属性独立，包含重复/零/最后一个 ID、完整 miss、容量跳点、非法值和预先中断断言。原 selected 可行性和投影顺序、逐节点 accounting、flush、worker 生命周期与池均保持。

独立 classfile 解码确认节点 callback **790 → 853 bytes，357 → 391 条静态指令**，多捕获一个 byte[]。新增直接 `baload` 位检查，BCI 315/323 先检查 stringId 下界/上界，BCI 342 读取 byte，BCI 346/347 移位及按位与。保留原 List.get、Number.intValue、OR iterator、IntOpenHashSet.contains fallback 和投影 Integer.valueOf 静态调用点，数量未变。不是全部 List 或装箱消失；也没有引入每节点工厂/访问 helper 调用。

边界：静态字节码不是运行时成本或收益证明。新增测试独立验证工厂语义，实际 64 图分支选择和 v3 输出须另外检查。中途周期性中断仅由源码位置审查支持，新测试直接覆盖的是预先中断。此次审计不代替真实控制回放、配对或最终 CI 验收。
