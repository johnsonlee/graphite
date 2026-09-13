# 慢查询形状：原因、改动与验证

本次以最新 `main` 的 `144d98ef` 为基线，针对无标签 `value` 搜索、动态属性搜索、`toString` 包装字段和 DATAFLOW 扩展四类形状优化。四类实测慢查询用例均超过 10 倍目标；常规及专项测试全部通过，既有查询的三轮中位数变化为 −5.8% 到 +5.2%。这些结论限定于下述真实数据、搜索文本和运行协议，不推广到所有同形状查询。

## 为什么慢，改了什么

| 查询形状 | 主要成本 | 已实现的处理 |
|---|---|---|
| `MATCH (n) WHERE n.value CONTAINS ...` | 无标签候选覆盖多种节点；逐节点解码后才能判断属性是否适用 | 按实际属性语义选择节点类型，复用存储查询能力；保留具体类型查询已有的索引准入和工作预算行为 |
| `any(k IN keys(n) WHERE toString(n[k]) CONTAINS ...)` | 每个节点重复获取属性、解释表达式和转换值；节点与方法描述符解码本身也昂贵 | 修正节点动态下标，编译受支持的 ANY 形状；从搜索文本选择至多两个必要片段，在 mapped 记录中预筛选，只有候选才解码并执行完整原谓词 |
| `toString(n.caller_class) CONTAINS ...` | 包装表达式原先无法进入直接字符串候选路径；部分路径会在返回第一行前构建索引 | 对可证明语义等价的字符串字段识别包装；使用有界状态的 raw 扫描，保留无法等价处理的类型和表达式回退 |
| `MATCH (c)-[r:DATAFLOW]->(n) WHERE ...` | 从文本左端找起点，再扩展边、读取目标并过滤；稀有或无结果时遍历大量邻接 | 安全的起点条件在扩展前执行，并用保持顺序的候选查询；显式要求惰性 raw 起点扫描。只有目标条件时，先正常扩展首个起点，需要后续起点才预检目标是否存在 |

`LIMIT 50` 限制最终合格结果数量，并不限制检查多少节点或边。当前执行器已有流式 LIMIT 路径，不能简单归因为“LIMIT 总是在全部执行后才生效”。无结果和罕见结果仍可能耗尽候选范围。

目标预检不反向遍历，不初始化反向图。找到一个目标只代表继续原遍历，不能把它当成完整目标集合；证明没有目标时才跳过该图剩余起点。预检会增加真实工作量，必须计入预算，因此极小预算下的成功/超限边界可能变化。

## 动态属性的性能基准必须先有正确语义

`144d98ef` 的节点动态下标 `n[k]` 存在语义缺陷。直接把该版本的动态 ANY 耗时当作正确查询基准，会比较不同结果，不能用来宣称加速。

动态属性查询使用单独的语义参考版本：**`144d98ef` 加且仅加节点动态下标修复**。优化版本必须与这个参考版本比较结果和耗时。其余形状仍与原始 `144d98ef` 比较。原始 main 的动态查询结果可作为缺陷证据，但不进入动态查询加速比计算。

- 基线完整提交：`144d98efa2bcb1f183d4f962b833c234d839d2a9`。
- 语义参考补丁 SHA-256：`1ed0f211886dec1bfb2557983400afb34586405583acfbeef0f3f9c07136557a`。
- 最终候选源码：`b8fc966ee6614806babc79f21fbef625b5f0b0f3`；后续报告提交不改变生产源码。候选 JAR SHA-256：`5cc950355a2a91024a3af468c3f57f54b362ff9b292ec1e0a6dc48ba4d89b940`。
- 120 个 JMH 和 40 个独立资源观测的完整结果、顺序及来源摘要一致；140 个私有副本均已清理，输入文件保持不变。机器可读结果见 [slow-query-shapes-results.json](slow-query-shapes-results.json)。

## 正确性与资源边界

必要片段只产生候选超集，不能替代完整 ANY。片段必须排除数字科学计数法及布尔、null、NaN、Infinity 等生成文本；方法签名的各个片段可以分布在不同存储字段中。Enum、ResourceValue、Annotation 等包含异构值或动态属性的节点保守保留，最后仍按实际 accessor 判断。图 ID 与 qualified/element ID 来自外部命名空间，可能命中时回退正常扫描。

参数、null、属性名碰撞、非字符串 `toString`、不受支持的表达式和 inline 属性均保留相应原始求值路径。下推不得改变易变函数或可能报错表达式的行为。图顺序、节点顺序、邻接顺序、重复边结果和 provenance 都属于验证内容；候选不能按输出 LIMIT 截断。

扫描继续支持工作预算和取消。额外预检不免费、不吞异常；返回结果必须继续经过完整 WHERE。合成 fixture 仅用于这些正确性及源访问断言。

动态预筛选最多使用两个字符串匹配状态缓存。字典不超过阈值时，每个缓存采用每字符串一字节的稠密状态，单个上限约 1 MiB；更大字典使用较小的有界碰撞缓存。碰撞只会重复比较，不改变结果。该设计用有限内存减少字符串重复解压；实际查询窗口分配量、峰值内存和延迟仍需实测，不能只从缓存大小推断整体内存改善。

## 测量口径

性能证据使用真实持久化图，固定文件身份，在基线、语义参考和候选之间隔离 fixture 副本及可写索引副产物，避免某个版本生成的索引替另一个版本预热。保留 fixture 文件摘要、JDK/系统、CPU 配额、堆配置、运行命令、fork 顺序和结果摘要。

`COLD` 表示独立映射/进程及按基准协议清理查询索引；**不表示清空操作系统页缓存**。`WARM` 表示在同一映射中执行约定预热后的查询。具体设置应以归档命令和 harness 为准，两者不能混在同一加速比中。

本仓库的 `CypherBenchmark` 在 setup 中生成合成图，按 `CONVENTIONS.md` 不作为延迟、吞吐、分配量或加速比证据。相关查询形状证据使用真实持久化 `SlowQueryShapesBenchmark`，并用 Android/LargeCorpus 查询基准覆盖已有行为。端到端检查使用 `LargeCorpusPerformanceGateTest`。本地验收之后已创建 [PR #128](https://github.com/johnsonlee/graphite/pull/128)，托管 CI `benchmark-regression-gate` 的实时状态以 PR 页面为准。本地检查不能替代 PR 的必需检查，也不冒充 `CypherBenchmark` 类的真实数据结果。

查询窗口分配量与 JMH 生命周期分配量分别报告。包括加载、预热、清理和验证的 profiler 汇总不能标成一次查询的分配量。命中与无结果分别测量，并同时检查性能改善和原有快速场景的回退。

环境为 Apple M3 Max（16 核、64 GiB）、Java 17.0.18，基准 JVM 固定
`-Xmx8g -XX:ActiveProcessorCount=4`。目标数据是 Android 14 的真实持久化图，
5,938,826 个节点；扩展回归使用 Tika 2.9.2、Hive 4.0.0、Kotlin compiler 2.0.21。
完整输入文件 SHA-256 见 `/tmp/graphite-slow-shapes-evidence/fixture-protocol-v2/fixtures.json`。
最终三份基准使用相同 harness SHA-256：
`39008c47663e97278b8aafc21633acb576c504f265479d589e9b28ebc06bcd0a`。

目标 JMH 命令如下，对每个固定 JAR 运行三个交替顺序的独立批次。
原始 main 运行除 dynamic 外的八个 queryName；语义参考只运行两个 dynamic；
候选运行全部十个 queryName。`COLD`、`WARM` 分开产生分数。

```bash
java -jar "$revision_jar" '.*SlowQueryShapesBenchmark.execute' \
  -p corpus=android -p queryName="$query_cases" -p cacheState=COLD,WARM \
  -f 1 -wi 0 -i 1 -foe true -prof gc -rf json -rff "$result_json" \
  -jvmArgsAppend "-Dandroid.graph.path=$android_graph"
```

查询窗口 CPU/分配量另用独立进程运行每个 queryName，一次冷查询和一次热查询：

```bash
java -Xmx8g -XX:ActiveProcessorCount=4 -Dandroid.graph.path="$android_graph" \
  -cp "$revision_jar" io.johnsonlee.graphite.webgraph.SlowQueryShapesCorrectness \
  android "$query_case"
```

已有查询回归精确覆盖 `AndroidQueryBenchmark.mapped_*` 和
`LargeCorpusQueryBenchmark.mapped_*` 中的 `simpleNodeMatch`、`intConstantFilter`、
`countStar`、`singleHopRelationship`、`returnDistinct`。每种数据各五个方法，
每个版本/方法/轮次都复制独立 fixture，三轮交替顺序，JMH 参数为
`-f 1 -t 1 -wi 2 -i 3 -w 500ms -r 500ms -prof gc`。这批分数是预热后的方法耗时，
不能与目标 SingleShot 的冷查询分数混算。

## 最终测量与检查

目标 JMH 的 120 个观测已完成。下表为每个版本三个独立 JVM 的耗时中位数，单位 ms；加速比为两者中位数之比。全部有序结果摘要一致，120 个私有副本已清理，原始数据未被修改。独立资源测量和广泛回归也已完成。

| 用例 | 冷：参考 → 候选 | 冷加速 | 热：参考 → 候选 | 热加速 |
|---|---:|---:|---:|---:|
| valueHit | 3128.52 → 124.73 | 25.08× | 2964.12 → 48.54 | 61.07× |
| valueMiss | 3166.22 → 115.02 | 27.53× | 2918.94 → 36.04 | 81.00× |
| dynamicHit | 11341.84 → 613.07 | 18.50× | 10962.17 → 519.49 | 21.10× |
| dynamicMiss | 11193.08 → 615.63 | 18.18× | 10854.24 → 522.07 | 20.79× |
| wrappedCallerHit | 3815.49 → 163.89 | 23.28× | 3523.14 → 130.82 | 26.93× |
| wrappedCallerMiss | 3858.47 → 153.27 | 25.17× | 3471.60 → 51.67 | 67.19× |
| dataflowSourceHit | 10706.08 → 129.36 | 82.76× | 10196.10 → 74.47 | 136.92× |
| dataflowSourceMiss | 10599.03 → 118.15 | 89.71× | 10027.27 → 36.79 | 272.59× |
| dataflowTargetMiss | 10591.71 → 692.92 | 15.29× | 10282.97 → 298.63 | 34.43× |
| dataflowTargetHit | 260.10 → 236.55 | 1.10× | 157.93 → 127.92 | 1.23× |

四类原慢查询的命中/未命中用例均超过 10 倍目标。原本较快的 `dataflowTargetHit` 作为回归保护用例，保持约 1.10 倍冷、1.23 倍热提升；不将其宣称为 10 倍。必要片段不适用的搜索文本和其他回退形状也不在这项 10 倍实测结论范围内。


资源计数是每个版本/用例各一次独立查询窗口观测，包含窗口内后台 Java 线程，单位为十进制 MB；不是峰值堆、存活对象或 JMH 全生命周期分配量。CPU 加速比是进程 CPU 时间之比，不能与墙钟时间加速比混用。

| 用例 | CPU 冷 / 热加速 | 冷分配 MB：参考 → 候选 | 热分配 MB：参考 → 候选 |
|---|---:|---:|---:|
| valueHit | 16.28× / 21.80× | 7151.66 → 42.35 | 7145.47 → 33.30 |
| valueMiss | 17.30× / 42.87× | 7160.20 → 43.47 | 7104.14 → 31.83 |
| dynamicHit | 15.18× / 19.41× | 41277.52 → 17.03 | 41786.41 → 2.78 |
| dynamicMiss | 15.49× / 18.96× | 41098.87 → 12.52 | 41691.38 → 2.13 |
| wrappedCallerHit | 12.28× / 12.75× | 9163.11 → 36.62 | 9144.56 → 5.23 |
| wrappedCallerMiss | 12.61× / 21.93× | 8261.78 → 36.29 | 8249.56 → 27.68 |
| dataflowSourceHit | 45.44× / 69.35× | 34045.41 → 43.03 | 33999.61 → 33.49 |
| dataflowSourceMiss | 46.39× / 126.82× | 33979.45 → 43.58 | 33999.61 → 31.57 |
| dataflowTargetMiss | 7.51× / 9.14× | 34044.83 → 327.78 | 33998.48 → 359.91 |
| dataflowTargetHit | 0.99× / 1.06× | 236.44 → 236.39 | 221.66 → 221.48 |

慢用例的分配量均显著下降。`dataflowTargetMiss` 的 CPU 改善为 7.51× / 9.14×，低于其墙钟提速，未将其描述为 CPU 也超过 10 倍；快速 targetHit 的 CPU 与分配量基本持平。

- 环境、fixture 身份与复现命令已列于上文；原始命令、JSON 和日志位于 `/tmp/graphite-slow-shapes-evidence/final-paired/`。
- 完整检查：`./gradlew check koverLog --max-workers=2` 通过，耗时 3 分 17 秒。常规测试 2,498 个、单独内存契约测试 7 个、真实大图端到端测试 3 个，均无失败或跳过；所有现有 lint 和覆盖率门槛通过。另有前端 `node --test graphite-explore/src/test/js/ui-state.test.js` 通过。
- 行覆盖率：core 98.1149%、cypher 98.004%、explore 98.0353%、query 98.9011%、sootup 98.4449%、webgraph 96.892%。XML 证据归档在 `/tmp/graphite-slow-shapes-evidence/final-check/`。
- PR CI 复核修正：初始 WebGraph 的 96.892% 包含三个仅在 `src/jmh` 中的辅助类，共 75 行。这些类不在应用 JAR 中。沿用已有基准辅助类排除规则，精确排除这三个名称后，应用覆盖率为 **98.1019%**（5,892 行覆盖、114 行未覆盖）；生产类和 CI 的 98% 门槛不变。重新运行 `:webgraph:koverLog :webgraph:koverXmlReport :webgraph:detekt`，包含 193 个 WebGraph 测试，全部通过。原始测量快照保留初始统计值，不改写历史证据。
- Android / LargeCorpus 既有查询回归：20 个用例、120 个分数验证完成，120 个私有副本全部清理，共同输入未改变。
- 既有查询耗时中位数变化为 −5.8% 到 +5.2%，没有超过 10% 的增加。首轮 Android simpleNodeMatch +16.85%、Tika intConstantFilter +13.12% 的信号在反向轮次变为改善；三轮中位数分别为 −1.0%、+5.2%。保留全部原始分数，未删除较慢观测。
- 真实大图端到端检查：Hive 30,642 ms、Kotlin compiler 20,726 ms、Tika 19,034 ms，均在现有时间上限内；每个测试独立使用 4 GiB 堆。该检查证明通过现有门槛，不等于同机 main/candidate 端到端加速比。
- CI `benchmark-regression-gate`：已在 PR #128 启动；是否通过以 PR 最新提交的检查记录为准。
- 四类实测慢用例满足墙钟时间 10 倍目标；原本较快的 targetHit 保持性能，未达到也未声称达到 10 倍。数字型或不安全文本片段等回退形状没有 10 倍证据。

逐次假设、测量及保留/撤销决定见 [实验记录](cross-graph-cypher-optimization-attempts.md)。

既有查询的分配量也保留了增加项：countStar 每次多约 80 B（约 5.1%），Tika/Kotlin compiler 的 simpleNodeMatch 多约 3,280 B（约 1.3–1.4%）。代码复核确认每个 evaluator 新建空动态谓词缓存带来固定的小对象成本；不能据此归因全部差值，也不声称所有资源完全零增加。该组分配量是正常预热 JMH profiler 口径。

| 数据集 | 方法（mapped_ 前缀） | main ms/op | 候选 ms/op | 耗时变化 |
|---|---|---:|---:|---:|
| android | simpleNodeMatch | 0.072412 | 0.071680 | -1.0% |
| android | intConstantFilter | 0.149047 | 0.147771 | -0.9% |
| android | countStar | 0.000728 | 0.000748 | +2.8% |
| android | singleHopRelationship | 0.522491 | 0.517554 | -0.9% |
| android | returnDistinct | 0.136195 | 0.128348 | -5.8% |
| tika | simpleNodeMatch | 0.074223 | 0.076770 | +3.4% |
| tika | intConstantFilter | 0.024276 | 0.025546 | +5.2% |
| tika | countStar | 0.000738 | 0.000750 | +1.6% |
| tika | singleHopRelationship | 0.025936 | 0.026098 | +0.6% |
| tika | returnDistinct | 0.123853 | 0.130062 | +5.0% |
| hive | simpleNodeMatch | 0.072159 | 0.073878 | +2.4% |
| hive | intConstantFilter | 0.029103 | 0.028352 | -2.6% |
| hive | countStar | 0.000740 | 0.000760 | +2.7% |
| hive | singleHopRelationship | 0.577014 | 0.572371 | -0.8% |
| hive | returnDistinct | 0.039786 | 0.039686 | -0.3% |
| kotlin-compiler | simpleNodeMatch | 0.053790 | 0.054338 | +1.0% |
| kotlin-compiler | intConstantFilter | 0.037460 | 0.037552 | +0.2% |
| kotlin-compiler | countStar | 0.000727 | 0.000730 | +0.5% |
| kotlin-compiler | singleHopRelationship | 0.283487 | 0.281430 | -0.7% |
| kotlin-compiler | returnDistinct | 0.045359 | 0.044677 | -1.5% |
