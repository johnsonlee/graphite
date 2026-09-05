# Attempt 138 构建独立核验

核验通过。六份原始 XML 共 192 个 testcase，failure / error / skipped 全部为 0，与 `build-receipt.json` 一致；逐项检查 testcase 子元素也没有失败或跳过。构建日志明确执行 `:webgraph:test`、`:webgraph:detekt`、`:webgraph:jmhJar`、`:webgraph:verifyJmhJarExcludesTests`，均没有 UP-TO-DATE / FROM-CACHE 标记，结尾为 `BUILD SUCCESSFUL in 2m 2s`。记录命令使用 Java 17、ActiveProcessorCount=4、--no-daemon。

新增五项都实际运行并通过：

- selected tuples 最早物理顺序、LIMIT 与拼接不存在的 tuple。
- selected 重复/null 列以及 partial projection / 未投影谓词的回退。
- checksum 正确但 chosen posting 后部失序，LIMIT 1 仍须完整校验并回退。
- empty selected、预算拒绝、取消异常原实例传播、重试正确性。
- sparse initial 的重排列、重复列及 null / graphId 列语义。

ParallelDistinctDisjunctionTest 原有四项也全部运行并通过，保留四关键词各自独立命中、重叠去重、物理顺序、selected 与不同 transform / 同 transform 不同词的覆盖。两份测试源码 SHA 与交接时完全一致。

对冻结 main `4e328b0109e13c896b74004823fb049fcb19251a` 逐字节比较全部 130 个 main/JMH 文件，只有收据所列的两个生产文件不同。当前源码 SHA 与 prebuild、build 两份收据完全一致：

- MappedCallSiteStringIndexView.kt：`7cd7b21f0ac451f265f4014cfa2c0cee09cc4630318c4f3092a897eba668f0aa`
- MappedWebGraphBackedGraph.kt：`7ec58e121beabf3d792cc31075d9a399cd4fe3055e94865e1347bd0ad1e9bac4`

另独立读取冻结 JAR 的 ZIP central directory：30,469 个条目中，没有混入该模块 72 个 test output 条目。为避免干扰正在进行的测量，没有重新读取整个 JAR 计算 SHA；`2c419ac0b9d996af0890d1c857f81fa3479c170f59306fe35517a6e90cf7b5bf` 是构建收据记录的冻结 hash。本次审计未启动 Java、构建或性能任务，未修改源码或测试。

这些 synthetic 测试证明结果及路径语义，不证明性能收益或 10x 目标。逐 XML SHA、测试名及用时、源码 hash、日志 hash 和重算边界详见 `independent-build-audit.json`；重算脚本为 `independent-build-audit.py`。
