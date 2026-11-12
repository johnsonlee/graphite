# 秒级复杂查询 P95 优化：增量实验与累计验收

目标是减少真实多图负载中的秒级 P95。基线是 PR212 合并提交
`69ba5f3072c4984d0e6cdb98eeeb107cd365bccd`；累计产品源码固定为
`868158af5723a931e936c7bcb6212edc78f0b805`。后续文档提交不改变产品源码。
本页记录六次增量 pilot，**不把增量相加，也不宣称所有性能约束已经通过**。

累计基线/候选的100样本确认、73类回归及必要的加载诊断，作为本实验历史的
[累计验收附录](https://github.com/johnsonlee/graphite/releases/download/benchmark-evidence-seconds-p95-final-2026-11-12/final-assessment.md)
独立发布；其[不可变证据发布页](https://github.com/johnsonlee/graphite/releases/tag/benchmark-evidence-seconds-p95-final-2026-11-12)
附完整原始数据和 SHA256 清单。这样产品提交冻结后，远端 CI 与本地最终测量可并行，
无需为追加数据重复运行相同产品验证。PR 在附录、审计及最终提交 CI 完成前保持 draft；
CI 成功本身不代表本地性能验收通过。

## 工作负载与边界

macOS14.3、Apple M3 Max、16逻辑CPU、64GiB RAM。复用四组真实 JAR 的64个持久化分片，
每个请求明确查询全部64图；不是一个图的计时，也不是复制图冒充更大图数。
并发4，每类首次1请求、预热2请求、pressure20请求；每个配对276份完整响应。
P95使用每类样本的nearest-rank第19位，P50辅助诊断；轮次之间不pool。
此为有客户端校验间隙的闭环混合压力，不是固定到达率或饱和吞吐测试。
所有原始失败、样本、typed body、receipts和输入封存均保留。

加载CPU为新进程启动至64图registry就绪后停止的wait4 user+system；加载RSS为该进程
内核峰值。查询CPU为完整混合pressure窗口；查询RSS为查询进程生命周期内核峰值，
包含加载、first-use及warmup，不能重置为pressure-only峰值，也不是每个query独立RSS。
各比较的CPU/RSS限制独立为+5%，不互相抵消。首次请求、pressure和完整操作边界分别报告。
未新增或重测图构建/保存的性能；JAR correctness CI 保持4GiB heap，硬上限8GiB未提高。

控制器从可信基线构建；基线原生二进制复用先前冻结构建，171个相关源文件及Cargo输入
与合并基线逐SHA相等。所有候选从独立干净提交构建并记录binary/source/toolchain身份。
[协议与身份](query-performance-evidence/2026-11-12/seconds-p95/README.md)说明复现方式。

## 每次增量的实际观察

下列数字均为父版本→候选的毫秒，不能代替最终main→累计候选比较。

| Attempt | 改动 | 目标P95 ms | 绝对变化 ms | 限制或不利观察 |
| --- | --- | ---: | ---: | --- |
|319|OPTIONAL直接流入原聚合状态|10977.785→10818.636|-159.150|幅度小，其他未改路径也同向波动，不证明明确归因|
|320|紧凑源节点degree计数|6013.233→3888.162|-2125.071|加载CPU+5.544%超限；rollup/global/co-use P95上升|
|321|邻接迭代免临时Vec|4123.576→4156.256|+32.680|目标P95未改善；混合CPU-1.243%、RSS-1.878%，仅保留为待累计验证的资源增量|
|322|双列StrId内联分组键|rollup2814.430→2650.357；global3065.649→2916.908|-164.073；-148.742|加载CPU+7.072%超限；co-use+16.039、OPTIONAL+710.830ms|
|323|OPTIONAL紧凑source/count(b)状态|11585.851→8283.724|-3302.127|混合CPU-12.617%、RSS-17.961%；degree P95+466.730ms|
|324|两跳紧凑count/DISTINCT状态|4241.492→3286.695|-954.797|查询RSS+6.613%超限；OPTIONAL P95+416.850ms|

六个pilot共1656份完整响应全部通过独立类型/顺序/provenance/receipt核验，未发生查询失败
或OOM；单轮20样本不能建立精确置信边界。每轮min/max、全部P50/P95与资源绝对值见
[逐轮审计索引](query-performance-evidence/2026-11-12/seconds-p95/README.md)。
保留有益增量不表示父版本比较或累计方案已满足所有限制。

## 代码如何减少工作

- OPTIONAL保留原matcher和未匹配补null，只对已证明的count形状融合；随后将精确
  graphId/source/count(b)分组改为NodeRef键。sink仍生成count0；重复seed继续贡献计数。
- degree原本已流式聚合，本轮去掉每边通用key/value Vec与通用group state，不声称新增流式遍历。
- 邻接迭代保留CSR切片提前校验、边顺序、self-loop处理、取消与trail语义；公开Vec接口保留。
- 两跳继续使用原matcher，只以紧凑节点身份集合计算DISTINCT，按实际graphId合并分组。
  相同外部图ID不抹去物理source身份；所有group完成count溢出检查后才应用后续LIMIT。
- 双列分组仅在单tag、无过滤、排序索引和证明可用的列策略下使用[StrId;2]；其他情况回退。

预算约束、未证明安全的形状和属性图（除319原聚合融合的已验证范围）保留泛型路径。
最终累计模块/integration465项测试及clippy通过；真实Tika多来源、双方言、重复外部图ID、
null/sink、顺序、溢出、取消及budget覆盖分别保留。合成fixture仅用于正确性，从不提供性能证据。

## 为什么仍需累计验收

已有完整响应显示5051914个CallSite、2077094个DATAFLOW sink及2974820条该关系。
非sink节点数恰好等于边数，所以这套语料的CallSite出度只有0或1；OPTIONAL不会扩大行数，
这解释了仅去中间Vec的收益为何有限，但不能代表生产高扇出图。
两跳则产生4489362条路径，仍须逐路径处理，未声称消除了组合复杂度。

320/322没有修改加载代码，wait4计量与停止边界也未发现错误；但全部pilot为AB，候选加载
发生在基线重查询后。相同冻结二进制在相邻轮次加载CPU变化约2.4%–3.1%，说明有变异，
**不能证明超限只是噪声或豁免限制**。累计比较使用预声明BA100及独立73类回归，保留
每项结论；若资源仍超限或顺序结论冲突，追加单独、预声明的匹配加载验证，而非挑选有利轮次。

本地64图、四并发无法证明生产50+项目的P95已恢复；生产请求分布、热点、图大小、高扇出、
持续并发和服务可用性仍需生产代表性验证。毫秒级回归观察保持可见，但不是本轮反复优化目标。
