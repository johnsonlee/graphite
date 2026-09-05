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
