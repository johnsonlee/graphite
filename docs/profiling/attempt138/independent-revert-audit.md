# Attempt 138 显式回退独立核验

核验通过。回退提交 `27de1f5ebd318fb5f60b24596712a5b3a6a3836e` 的 130 个 main/JMH 文件，逐文件读取原始字节后，全部与冻结 main `4e328b0109e13c896b74004823fb049fcb19251a` 一致；168 个 test 文件全部与候选前 `e6c932c5e1d0fb7b583ceb9e14c8ef88ec9d9694` 一致。当前工作区对应的 298 个文件也与回退提交字节一致。路径集合相等，没有漏检的新增或删除 source/test 文件。

相对候选 `470df7cea888240b87380f1a4a650638ea713815`，恢复的 source/test 恰好四个：MappedCallSiteStringIndexView.kt、MappedWebGraphBackedGraph.kt、GraphStoreTest.kt、ParallelDistinctDisjunctionTest.kt。候选依赖的 mapped 零扫描断言与新增五项测试随候选保留在历史提交，不残留于回退生产树。

原有 ParallelDistinctDisjunctionTest 的四项测试全部保留：

- 四关键词各自独立命中、重叠去重及原物理顺序。
- selected tuples 的过滤与原物理顺序。
- raw 与 lowercase 对同一字符串的状态隔离。
- 同一 lowercase transform 的不同关键词状态隔离及完整无命中。

文件仍有四个 `@Test`，且恢复既有 raw scan = 1 断言。全部 tests 与 e6c 一致，不应写成全部 tests 与冻结 main 一致：CrossGraphCypherExecutorTest.kt 与 ParallelDistinctDisjunctionTest.kt 是 e6c 已有且继续保留的两个差异。

从 e6c 到回退提交的最终差异共 196 个文件，全部位于 `docs/`；无已跟踪工作区修改。审计时另见根目录八个未跟踪文件：`1.853B`、`12,442`、`12,743`、`1928.2`、`6.280B`、`7,797`、`8,003`、`993,095,680`。它们不在提交 diff 中，本审计没有创建或删除它们，因此不将工作区表述为完全干净。

收据 `docs/profiling/attempt138/revert-source-receipt.json` 的 130/168 数量及回退声明与独立结果一致。详细逐文件 SHA、提交间 docs 路径清单及测试名称保存在 `independent-revert-audit.json`，重算脚本为 `independent-revert-audit.py`。本次没有运行 Java、构建、测试或性能任务；源码回退不代表可以豁免失败测量，也不代表新 CI 已通过。
