# DISTINCT 的实际工作与候选基数

Attempt 137 已因重复 Method CPU 回归被拒绝并回退。这里继续核对冻结 main
的已有录制与真实导出，并归档被拒绝 Attempt 133 的残余工作诊断。
这里的证据不构成新候选验收或收益结论。

## 应用线程中真正执行的路径

三份已有 JFR 的离线重算，将请求线程和 graphite 查询／CallSite 扫描线程与
JIT、资源采样器等后台线程分开。重算后的全部 102 个查询摘要与原阶段分析
精确一致，186 个阶段指标和 722 个线程分区守恒。

| 场景／阶段 | 第 1 份应用 CPU 样本 | 第 2 份 | 第 3 份 |
|---|---:|---:|---:|
| 定向查询初始选择：raw DISTINCT 路径／全部应用 | 75 / 76 | 74 / 79 | 60 / 66 |
| 密集查询来源补全：raw DISTINCT 路径／全部应用 | 53 / 123 | 46 / 87 | 28 / 80 |
| 密集查询来源补全：匹配字符串发现／全部应用 | 69 / 123 | 33 / 87 | 49 / 80 |

raw 路径包含准备工作和子调用，不等同于仅节点循环的独占 CPU。
这里的 raw 与字符串发现同栈交集为零，但 inclusive 指标通常不能相加。
应用样本中没有完整索引校验栈；不能把冷查询的校验装箱收益推到这两条
warm 查询上。样本较少且录制包含 method tracing，不据这些比例预测加速倍数。

[完整叶子／inclusive 统计](phase-application/application-summary.json)、
[独立重算审计](phase-application/phase-application-audit.md)。

## 200 个选定元组在剩余图里有多少候选

对认证的 5,046,935 个 CallSite 导出独立全量扫描，按照源顺序和物理遍历
顺序选出 dense `get` 查询的前 200 个不同四字段元组。选择在首图第 1,012
个导出节点时完成。再扫描完整导出，逐一计算每图、每元组、每列的 posting
逻辑基数，以及完整元组的真实出现次数。

| 来源 | 选中元组数 | 完整元组命中节点 | 对可行元组取最短属性 posting 后的长度之和 |
|---|---:|---:|---:|
| 首图 Android 00 | 200 | 262 | 1,583 |
| Tika 00 | 11 | 12 | 53 |
| 其余 62 图 | 0 | 0 | 0 |

后续 63 图中的 **53 是逐元组 posting 长度之和，不是 53 个唯一节点**。
其中有 12 次完整命中、41 次 anchor posting 中的非匹配节点遭遇；跨元组探测
可能重复遇到相同节点。这份数据没有“各列分别存在、但整个元组完全不存在”
的可行元组；这种一般情况仍必须正确处理。

冻结 main 的一次单查询 correctness control 返回的全部 200 行，其值、列顺序、
行顺序及完整来源与参考结果完全一致。当前图文件内容还与此前认证的完整
64 图 inventory 一致，控制 JAR SHA256 与受保护的冻结 JAR 一致。
这些检查不是新的 before/after 性能比较。

[完整 census](selected-tuple-census/census.json)、
[独立全量审计](selected-tuple-census/independent-census-audit.md)、
[控制结果](selected-tuple-census/correctness-receipt.json)、
[输入核对范围](selected-tuple-census/input-check-receipt.json)。

## 基数小不等于实现已经便宜

现有 mapped view 没有公开的 shortest selected-tuple API；heap index 的同类
helper 不能被当作免费可用的 mapped 功能。选段必须在返回 LIMIT 前完整检查
物理顺序，还需承担字符串 ID 解析、property directory 查找、投影、去重、预算
和取消检查。53 不是引擎 work units，更不是 latency 上限。

已拒绝的 Attempt 133 曾加入 mapped selected-tuple 查找，密集查询 work 从
283,544 降到 30,652，但三次耗时仍为 45.541、61.050、50.092 ms，且定向查询
成为 P95。Attempt 135 已尝试将 selected tuple 可行性判断提前到字符串发现前，
同样失败。不能把其中任一方案重新命名为新收益。

残余诊断已完成：按历史源码重建的 133 版本在来源补全的 64 个应用 CPU 样本中，
56 个同时位于 selected-tuple 字符串解析和 findId；这是同栈重叠计数。
调用内重复字典解析说明，小 posting 数量遗漏了实际成本。
[完整残余诊断](rejected133-residual/README.md) 保留重构来源、CPU／allocation
口径、原始观测和独立审计；它不推翻 133 的拒绝，也不预测新候选收益。

[源码 API、回退路径和语义边界审计](source-work-audit.md) 逐项列出完整索引校验、
选段顺序校验、源顺序、LIMIT、重复／空投影、完整来源及预算取消约束。

## 回退 139 后补齐的节点与并发定位

本轮只分析冻结 main 的既有录制，没有新候选或新测量。
[逐节点审计](raw-node-audit/README.md) 核实 raw 循环没有逐节点 Node 解码，
已有 selected 字符串/属性缓存，且 exact set 在循环外构建。
[图级调用重叠审计](graph-call-overlap/README.md) 独立复算 576 个调用：
targeted 初始阶段约 97% 的有调用区间仅存在一个图级调用；dense 来源补全
约 95–97% 存在两个。图内 segment 工作不包含在这个并发数中，不能推算删池收益。

[源码行和 BCI 定位](raw-source-lines/README.md) 从原 JFR 恢复此前丢弃的
frame 元数据，并绑定到冻结 JAR 的精确 class、行表及 Kotlin SMAP。
原来只能落到大节点 lambda 的 targeted 98 个叶样本，现在可区分 42 个遍历
控制、18 个谓词属性索引读取/拆箱、17 个 raw 寻址/读取、13 个 exact set
选择/检查以及 8 个其他位置。它们是记录位置，不能当作独占耗时。
其中 63 个标记 Interpreted、35 个 C1 compiled；索引已热不等于 JIT 已稳态。
136 的遍历改动与 138 的 posting 改动仍保持拒绝；此诊断不启动 140。

[早期 outer-only 录制交叉检查](raw-frame-sensitivity/README.md) 另核对 102 个
旧窗口、523 个 raw CPU 事件，同样有 Interpreted/C1 节点叶样本。比较 dense
时双方都取 whole-query，不能拿后期 provenance-only 的 43 当作整条查询。
两批不是 tracing 开关的配对实验；早期也缺少逐份采集前后的 JAR hash 收据，
因此不能用该观察证明无 profiler 的编译状态或 tracing 的因果成本。

## 回退 140 后：关闭 tracing 的编译日志

140 的 primitive predicate index 数组已因补充查询重复回归而拒绝并回退。
随后使用未修改的冻结 JAR，按原 34 条协议运行三个独立 JVM，仅增加编译日志。
[完整编译诊断](compiler-without-tracing/README.md) 中 102 条原 oracle 均通过，
所有非耗时字段与基线相同，64 图的 1,088 个文件前后 hash 一致。

三份运行时日志都显示 790-byte 节点 callback 曾生成 C2 代码，随后在内联的
`IntOpenHashSet.contains` BCI 37（callback BCI 322）发生一次
`unstable_if / reinterpret`，并再次生成 C1 代码。后续 C2 排队未发布不等于
编译失败。这补充了之前 Interpreted/C1 样本的解释边界；没有查询时间窗口，
不能据此分配初始选择／来源补全的去优化成本，不能推断既有候选退化的原因。

[按属性合并 OR 的审计](property-or-union-audit/README.md) 同时排除了一个
看似减少工作、实际不作用于旧 P95 的方向：targeted/dense DISTINCT 本来每属性
只有一个谓词，按属性 union 仍是最多四次 membership 检查，不减少扫描或来源
补全。纯四词 OR 的最多 16→4 次检查只是另一类查询的逻辑上界，不能据此启动
下一次优化。上述诊断没有新增生产候选，也没有形成去池或 10x 的验收结果。

## 查询 marker 与编译事件的对应复核

[查询窗口报告](query-marker-compilation/README.md) 将原 34 条查询与原生 JFR 编译、
去优化事件绑定；一个控制 JVM 和三个诊断 JVM 的 136 条结果与完整 oracle 一致，
全部生产 class 和真实图输入保持不变。第 2、3 份 targeted 的 callback 去优化
发生在距离查询结束仅 0.598 / 0.383 ms 时，不能解释之前三十多毫秒；第 1 份
发生在 dense，且后续确实又发布 C2。节点扫描在 targeted Java 栈快照中频繁出现，
但周期性快照不是 CPU 百分比，编译经过时间也不是请求阻塞时间。

[独立报告复核](query-marker-compilation/final-report-audit.md) 已核对原始事件。
[归档凭据](query-marker-compilation/archive-receipt.json) 逐文件记录源、压缩后及
解压后的 hash；原始事件 JSON/XML 以 gzip 保存，JFR、查询清单、构建源码及
审计脚本一并保留。压缩文件解压至同目录同名文件后可复算，原始临时目录保留。
这组编译诊断仅覆盖原 34 条；纯四词 OR 的补充覆盖及冷索引成本另见
[四词 OR 摘要](../main-four-or-summary.zh.md)，两类查询的热点不能互相代替。
本轮没有新生产候选，也未形成去池或 10x 的验收结果。

## 精确匹配结构与去池接点的进一步筛选

[调用转发审计](predicate-object-audit/README.md) 确认隐藏 wrapper 到大 callback
的转发存在，但独占成本没有足够证据；不以该转发启动优化。
[去池调度审计](scheduler-feasibility/README.md) 找到图请求所有权的接点，但现有
固定池没有安全的父子任务协作等待能力，不能直接换池并保留阻塞 join。

[实际匹配集合规模](exact-membership-census/README.md) 从原持久 trigram anchor
复算 exact ID，并与 used-ID 全集穷举及独立属性支持集审计交叉验证。密集查询的
按 ID 属性位表 payload 小于现有哈希 key 数组；稀疏查询恰好相反。因此只允许在
位表不大于原 key payload 时检验这种替换，其余保留原 hash。这不是实测收益，
也不把全候选图的 payload 总和当作实际分配或峰值。
[语义、实际字符串头和 fastutil 容量边界](exact-membership-table-audit/README.md)
记录候选域修正、范围保护、取消、构造成本及历史排重。
