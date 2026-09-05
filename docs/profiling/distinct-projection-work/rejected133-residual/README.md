# 被拒绝 Attempt 133 的残余工作诊断

这是按历史 commit `2b98f6929893be2cd572a250d884ba1701efb1dc` 精确源码重建的
**诊断 JAR，不是当时测量的原 JAR**。130 个 main/JMH 文件逐项与冻结 main 比较，
只有 Attempt 133 原来的两个生产文件不同，且文件哈希与历史记录吻合。
构建命令和诊断 JAR 哈希见 [源码凭据](source-receipt.json)、
[构建命令](build-command.json)、[构建结果](build-receipt.json)。

冻结 main 和重建诊断版本各录制一次原 34 条 replay，仅用于解释残余工作。
68 个 oracle 签名正确，阶段完整栈与 outer collapsed 逐栈守恒；图及 JAR 哈希
核对见 [采集完成凭据](profiles/completed.json)、[独立审计](profiles/residual-audit.md)。

密集 DISTINCT 来源补全中，诊断版本的应用 CPU 样本为 64：59 个在
`selectedProjectionHits`，其中 **56 个同时在 selectedTupleStringIds 和 findId**。
这是重叠 inclusive 计数，不能相加。冻结 main 的对应 findId 样本为 2 / 103。
诊断版本 findId 下 sampled allocation 为 **38,273,024 bytes**，main 为
**2,621,440 bytes**；这是 TLAB／outside-TLAB 采样权重，不是精确对象数或总分配量。
JIT 线程均不在应用样本分母中。

源码解释了遗漏的工作：历史 133 每个元组重复解析字符串，而 main raw 路径已
有调用内字符串 ID 与对应属性 membership 缓存。小 posting 基数没有计入这些
重复字典解码及临时字符串成本。完整计数、同栈交集和分配叶子见
[独立机器报告](profiles/residual-audit.json)、[CPU 摘要](profiles/residual-summary.json)。

**本诊断不推翻 Attempt 133 的 CI 拒绝，也不证明任何新候选有收益。**
一次带 tracing 的录制不能证明稳定加速、生产 P95 或优化上限。
原始观测 [main TSV](profiles/base.tsv)、[诊断 TSV](profiles/rejected133.tsv) 保留，
但不得当作候选验收数据使用。原始 JAR/JFR、完整 phase JSON 和 collapsed 栈留在
`/private/tmp/graphite-rejected133-mechanism-ivkvn783/`，没有复制进仓库。
归档命令中的绝对路径记录当时执行环境；复算脚本依赖该原始目录的完整数据。
[复制清单与哈希](copy-receipt.json) 记录至多末尾换行归一化，JSON 原值不变。
