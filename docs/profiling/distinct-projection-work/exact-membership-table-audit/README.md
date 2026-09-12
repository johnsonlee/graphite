# raw DISTINCT 的 stringId 位表：只读筛选

**结论：有足够机制和规模证据继续做离线量化，尚无提速证据，不启动实现或 Attempt141。** 原raw循环中的精确匹配确实调用`IntOpenHashSet.contains`；按ID寻址有机会替换hash/mix/probe，而旧targeted/dense仍最多检查四项、不减少访问节点。实际64图的全字符串域大小已核实，单byte/ID的payload不大，但稀疏命中时建表可能比现有小集合更贵。下一步必须取得实际候选数组长度、构造写入数和需建表的图数，再比较成本边界。

## 候选域：纠正此前过宽表述

须区分四个概念：全字符串ID域 `S=[0,stringTable.size)`；每属性使用ID集合`U_p`；四属性并集`U=∪U_p`；某个精确谓词在`U`中的匹配数组`A_i`。

`MappedCallSiteStringIndex.kt:675、2407–2423`先将四个PropertyCsr使用的ID去重，trigram metadata/postings由这批ID建立（592、675–699）。因此当前候选不包含完全未被CallSite四属性使用的字符串。此前我将“property-independent”误述为可能扫描全S中非CallSite字符串，现撤回该说法。

`MappedCallSiteStringIndexView.kt:69–80、205–237`按transform/mode/expected复用候选，匹配时不按单一property过滤；故实际A可以大于某个`U_p`中的匹配子集，但不等于全S。旧同词四属性通常复用一个A数组，`MappedWebGraphBackedGraph.kt:542`仍逐谓词构造四个HashSet。不能把四个property支持集的各自基数当作这四个set的输入长度。根线程将用实际sidecar anchor postings与导出的字符串复算A，本审计不臆造M。

## 范围与语义

Mapped view加载在`MappedCallSiteStringIndexView.kt:286–288`检查header.stringCount等于真实stringTable.size；389–390验证property ID范围和顺序，403–407验证trigram posting范围/顺序，205–237的实际候选读取再次检查`0 <= stringId < stringCount`，不合格返回null。CRC、identity、候选精确匹配及原fallback义务不能删改。所有谓词都支持时才产生exact数组；短词、EQUALS、不支持transform等仍保留原路径（69–80、516–518）。retained路径也先完成其原候选生成/匹配，不能由这次替换绕过。

对于此方法的纯析取，令位表 `table[id]` 的第p位表示 `id∈∪{A_i | predicate[i].property=p}`，则检查四个节点属性的相应位与原`∨i node[property_i]∈A_i`布尔等价。同属性不同词/transform可在**各自精确匹配完成之后**并入同一属性位；不能把不同transform的匹配缓存混为一项。四位只能表达每属性最终OR，不保留各词独立真值或原短路位置，不能扩展为AND/NOT/逐谓词计数的通用结构。继续按原predicate顺序遍历也可能因同属性union而更早接受；这里只能主张整体OR结果等价，不主张逐谓词操作序列完全相同。

这不是已排除的“靠每属性OR union减少旧34检查数”假设：旧34仍最多四次，目标是替换membership实现。不能将16→4的多词检查上界下降拿来证明旧P95收益。

`withRawCallSiteStringIds`（MappedWebGraphBackedGraph.kt:2448–2462）本身不检查读出的原始stringId范围；合法输入虽有范围保证，当前hash lookup对不在有效域内的ID可以返回false，直接table索引却会越界。必须明确probe范围保护，且表构造遇到无效候选不能静默误判。selected元组仍按全部投影值联合判断，null/重复列/物理顺序/LIMIT保持原逻辑；四个值分别存在不等于同一节点命中。

## 实际64图规模及格式证据

每图只读取`graph.strings`的324 bytes及sidecar的76 bytes，共25,600 bytes；所有结果在`census.json`。`graph.strings`是FrontCodedStringList Java序列化（StringTable.kt:67、105、114），没有通用固定count头。当前64文件的前316-byte schema完全一致，SHA为`27a7b7b31d8b3b6d2d5edc76a83b34051d43ab83551090103e116ee0c3190e2a`；该已验证schema的CharArrayFrontCodedList.n在offset316，ratio在320且为8。每项n均等于sidecar offset8的big-endian stringCount。不可把offset316推广到未验证序列化schema。

| 量 | payload bytes |
|---|---:|
| 全64图S总和 / 一byte每ID | 2,793,940 |
| 最小 / 最大单图S | 26,540 / 72,531 |
| 最大两张图的一byte表合计 | 142,785 |
| 每ID四位、按图向上取整打包 | 1,396,986 |
| 四个独立Long位图，按64位向上取整 | 1,398,080 |

这些只是数组payload，不是实测峰值/精确堆大小，也不假定64张表同时存活。byte表、四bit打包和四Long位图的指令/初始化成本不同；本轮没有挑选或试跑多种实现。

## 原HashSet容量与预算

冻结JAR的实际IntOpenHashSet/HashCommon classfile已用Python解析，证据见`census.json.fastutilProof`。`IntOpenHashSet(IntArray)`默认load factor=0.75；构造`n=max(2,nextPowerOfTwo(ceil(m/0.75)))`，n大于2^30拒绝，key为`int[n+1]`。因此初始key数组payload为`4*(n+1)`，m是**实际输入IntArray长度**，不能替换为单属性支持集基数。对象头、数组头、对齐、其他成员及现有A数组不包含在该公式内；没有声称精确堆字节。

当前查询预算是work units，没有byte额度（CypherExecutionBudget.kt:17、Graph.kt:59–69）。现有`MappedCallSiteStringIndexMemoryBudget`（MappedCallSiteStringIndex.kt:2157–2207）是进程范围保留索引预算，默认maxHeap/2，可由`graphite.webgraph.callSiteStringIndexBudgetBytes`覆盖；支持tryReserve/grow/shrink/close。它不是已经替新表保留的空间，也不是请求独享预算。若借用，临时占用可能影响其他索引admission，必须显式界定生命周期和拒绝时保留原hash路径，不能无条件按S分配或新增长期缓存。实际8GiB配置不等于任意数据/并发都安全。

构造至少处理每个被遍历的候选ID，写入属性位；同A被多个谓词复用时，重复遍历/合并次数须据真实构造方案记录。显式新增遍历应通过原workConsumer分批计费并周期检查取消，flush/释放必须覆盖异常，不能让新构造在预算已耗尽后继续。当前`map(::IntOpenHashSet)`构造本身未显式计费；新增计费不能被包装成工作量下降。JVM新数组清零有O(S)内存触碰，但不自动对应现有“storage item”单位；必须用容量/内存admission限制并与显式ID遍历区分，不能凭空把零填充当零成本。构造应仍在原selected可行性早退之后，不能夹带Attempt135已拒绝的发现顺序变化。

表应每次graph lookup构造一份，填完后供既有workers只读共享；不复制每worker一份，不改变节点级一次consume、取消轮询、selected/null-selected路径、池或任务数。失败/取消时的reservation必须等所有使用它的任务终止再释放，不能提前宣布内存已释放。

## 历史与下一步界限

三份chronological attempts搜索未找到完全相同的“raw DISTINCT exact集合→按stringId四属性位表”。最近的是`docs/wrapped-case-insensitive-query-optimization-attempts.md:2677`的Attempt086：小于等于8个ID改线性搜索，真实配对P95为0.95x/1.08x/0.88x且逐行回归，已拒绝。它说明contains热点不等于替代结构更快；本方案不是重开086，但必须承受同样反证。

Attempt040的128KiB六元组fingerprint摘要是概率miss筛选；052/055是trigram/属性成员及来源剪枝；074是membership验证时序；136是OR iterator，140是属性索引IntArray。这些不是当前寻址结构，但其旧拒绝均不得重开。检索缺失只限现有三份日志，不证明所有未记录改动都未尝试。

可以继续离线量化：实际A长度及重用、S/M比例、按现有selected可行性规则会构表的图数、构造ID处理数与payload上界。关键未知仍是hash probe的独占成本、建表/清零成本、缓存行为及短回放编译效应。已有JFR位置或去优化分支不能回答这些问题；任何未来测试须保持单一方向并经过原真实正确性与逐轮验收，本报告不预言收益。
