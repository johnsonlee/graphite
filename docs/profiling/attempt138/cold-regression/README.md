# Attempt 138 冷四词 OR 回归诊断

138 已拒绝并显式回退，结论不因这份诊断改变。此处比较冻结 main JAR 与
原被拒绝 138 JAR，每版一个新 JVM，各执行 40 次普通投影及 40 次 DISTINCT，
每条查询前清理字符串索引，同时采集 CPU／分配事件。没有重跑接受测试。

原生录制与独立导出均使用此前 55 图纯四词 OR 的两条查询；审计确认 v2 与 v3
对应查询的文本及全部预期结果完全相同。160 次查询的 32,000 行完整值、顺序、
来源均正确；运行前后所有图文件与两个冻结 JAR 的哈希一致。

## 可确认的运行路径

完整条件命中 55 图，但所选 200 个 DISTINCT 元组只来自 Android 00。
首图有 3,370 个匹配节点和 1,608 个完整 DISTINCT 元组，所以 138 的稀疏初始
posting 分支不会替代首图 raw 路径；其余 63 图执行 selected provenance。

Main 原来就有调用内 String→ID 和 property/value 成员缓存；138 不是首次
增加这种缓存。新增 posting 方案可以省下其他图的谓词候选发现，但会处理
selected tuple 的必要条件、最短 posting 查找与校验。189 个不同字符串与
228 个 property/value 组合只给静态上界，不能代替实际工作或耗时。
[完整源码审计](cold-regression-source-audit.md)。

## 40 个 DISTINCT 查询窗口的采样

| 指标 | Main | 被拒绝 138 |
|---|---:|---:|
| 全部 CPU 样本 | 13,771 | 14,764 |
| 应用查询线程 CPU 样本 | 12,254 | 13,159 |
| 包含完整 validator 的应用 CPU 样本 | 11,216 | 12,420 |
| 不包含 validator 的应用 CPU 样本 | 1,038 | 739 |
| raw 投影含 generated worker 的 CPU 样本并集 | 498 | 371 |
| 谓词候选发现 CPU 样本 | 250 | 9 |
| 新 selectedProjectionHits CPU 样本 | 0 | 86 |
| findId CPU 样本 | 50 | 54 |
| validator 采样分配权重（bytes） | 48,960,411,632 | 48,880,719,744 |
| findId 采样分配权重（bytes） | 66,060,288 | 63,700,992 |

Inclusive 样本有重叠，不能逐项相加；findId 在 main raw / candidate selected
父栈内。raw 表格采用方法及其 generated worker 的并集；summary 中另保留
仅入口方法的计数，不能把入口计数当作完整 raw 工作。
应用线程分类排除 JIT、GC、录制器和资源采样线程。320 份 CPU/分配 collapsed
及逐线程分区全部守恒；分配权重不是精确分配字节或对象数。

两版完整 validator 的 `javap -c -p` 输出逐字节相同，SHA256 为
`2d32c910fd0040d9b19249b32946d4ac3928883672ee944b042cd661a8136973`。
它证明 JVM 字节码相同，不证明 JIT 机器码或运行时间相同。
新增 posting validation/anchor 没有样本，也不能据此断言调用次数为零。

**这份证据尚未定位原始回归原因。** 应用 CPU 多了 905 个样本，但 validator
多了 1,204 个，非 validator 部分反而减少。不能把原非采样配对里的
36–40 ms 退化归因于新增 selected posting，也不能以此豁免重复退化。
[完整独立审计](cold-profile-independent-audit.md)、[全部采样统计](summary.json)。

## 已有 JVM 事件的补充边界

离线读取配套 JVM JFR，按同一查询窗口截取顶层 GCPhasePause，并合并重叠区间。
40 个 DISTINCT 窗口内 GC pause 合计 main 1.792750 ms、candidate 1.774250 ms；
已记录 deoptimization 事件分别 22、23 个。仅凭这些事件不能解释此前非采样
配对的延迟差异；它们也不覆盖所有可能的停顿或运行时成本。
Compilation 的配置记录门槛为 100 ms，保留的少数编译事件不代表全部编译工作；
后台编译与请求窗口重叠不等于同量的请求阻塞。
[离线事件统计及脚本](runtime-summary.json)。

原始 JFR、完整 runtime-events JSON 及精确录制命令在
`/private/tmp/graphite-attempt138.7ihszrob/cold-regression-profile`。
仓库保留概要、原始查询 TSV、校验凭据、字节码及分析脚本；此处没有新候选、
新的 P95 或优化接受结论。
