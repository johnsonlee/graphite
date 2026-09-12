# Mapped view 取消 helper 修订：原34阶段结果

基于官方PR `5e05242a`，候选JAR `177d9c15cd07181766abbbc5ce275ab0da403c149a8a54e609857ae26eb91996`。只在已有 `checkViewInterrupted()` 末尾加入任务context取消检查；原serial/parallel exact匹配循环保持源码及符号指令原样。parallel range原来的显式context检查保留，因此该路径现在有重复轮询；没有捆绑新查询算法或调度器修改。

串行cancel(false)缺口已有独立基线复现；另三个测试在官方原生产上均准确失败于剩余工作断言，helper修订后三项通过，涵盖checksum加载、posting验证、lazy merge及完整重试/缓存状态。基线失败时未运行到后续缓存和重试断言，不能据此声称基线缓存已污染。

四模块check/lint/JMH通过；常规XML2122案例，另含filteredRelationship7与largeCorpus3，共2132案例零失败/错误/跳过；core/cypher普通test为UP-TO-DATE，其余相关测试实际执行。测试消费回调最初因ReturnCount lint失败，已保留原件，仅将两相邻return合为同顺序短路OR；基线测试和修订测试字节不同，断言及控制条件一致。

JAR25872个class集合相同，9类字节不同；其中83方法只有Kt.checkViewInterrupted的符号指令变化，其余82相等。此比较不包含调试/frames/类metadata，不代表JIT或性能等效；其余class字节相等。

真实CI64（四语料64分片）/P4/8GiB/Java17，原始34原顺序/0warmup/原gc与资源采样，对官方PR和起点main各三对C/B-B/C-C/B。共408完整14字段/order验证通过，12组正常退出，输入和fixture前后相同；每个基线全部paired非时间字段均相同。

| 对照 | P95 base→candidate ms（三对） | 整轮CPU变化 | 原非回退门槛 |
| --- | --- | --- | --- |
| currentpr | 57.680→45.653 / 56.866→70.615 / 47.229→46.194 | -12.49% / +4.99% / +3.70% | PASS |
| startingmain | 105.942→43.120 / 41.688→44.348 / 47.837→39.451 | -5.56% / +12.24% / +2.77% | FAIL |

相对当前PR无重复单查询标记，但第二对P95倍率只有0.805，不能称稳定加速。相对起点main的第5条global-wide-class-pair-targeted两次回退（四属性同关键词raw CONTAINS OR，RETURN caller_class/callee_class，LIMIT200；class-pair指投影，不是双条件AND），完整目标未达成；CPU条件tradeoff不适用。只对当前PR通过不构成最终接受。

| class-pair-targeted 对起点main | base ms | candidate ms | 单次>15%且>1ms |
| --- | ---: | ---: | --- |
| 1 | 4.507 | 5.009 | False |
| 2 | 4.264 | 5.527 | True |
| 3 | 4.372 | 5.696 | True |

full38已完成，结果见下。没有新生产commit/push/CI/merge；候选保持隔离、未接受。

证据：original34-verification.json、两组original34-*-pairs原始JMH/TSV与gate报告、build-receipt.json、class-scope.json、baseline-extra-entry-reproduction及independent-review。

## 完整38结果

两种原reset各三对C/B-B/C-C/B，共12新JVM/456完整行值、顺序、来源验证/228配对通过；完整19逻辑场景、38查询和真实CI64数据绑定未变，所有组正常退出、实际进程收据一致。非时间差共8个pair-field，全部graphWorkUnits：per-query-cold的or-four-single-middle-rows三项和mixed-four-few-rows两项；replay-cold的mixed-four-few-rows三项。

两种模式唯一重复回退均是第21项mixed-four-few-rows。令F(t)表示关键词t在四属性上的OR匹配，其形式为 `(F(A) AND F(B)) OR (F(C) AND F(D))`，各叶子使用toLower(coalesce(...,'')) CONTAINS。数据有两个图命中、共229匹配；LIMIT200后的输出全部来自首图fixture-android-00。独立catalog包含第二个图的匹配计数和LIMIT后的完整期望，不能说返回结果命中了两图。

| reset/对 | base→candidate ms | base→candidate work |
| --- | ---: | ---: |
| per-query-cold 1 | 308.109→631.249 | 100228→252216 |
| per-query-cold 2 | 763.965→798.271 | 304905→304905 |
| per-query-cold 3 | 555.947→764.819 | 221526→294853 |
| replay-cold 1 | 678.454→570.642 | 304905→249974 |
| replay-cold 2 | 157.705→724.221 | 45297→304887 |
| replay-cold 3 | 301.103→512.932 | 103242→226933 |

表中工作量必须由原TSV复核；四个>15%且>1ms慢pair同时多做了work，仅是进一步调查实际扫描数量的证据，不证明每单位成本不变或scheduler因果。该adapter没有CPU/RSS，每查询仅三点，不报告单查询P95。完整独立审计在independent-review/full38-*。

Attempt140已对同名混合查询做过HashMap<Class,...>无标签类型遍历前缀诊断，不能将此视为本轮新发现。现有源码DirectStringCandidatePlan只递归AND，不支持该顶层OR-of-AND；generic过滤路径及NodeTypeIndex类范围顺序需结合本次实际类型前缀验证。原40JVM诊断为孤立查询，不能直接替代当前完整38的前缀/堆/JIT历史，亦不豁免本次失败。

决策：helper修订修复了取消缺口，但相对起点main及full38性能仍未通过，保持隔离、未接受；不新增无依据的生产改动或用另一优化遮盖失败。下一项只复用既有类型前缀机制，验证本次实际扫描量变化；没有新commit/push/CI/merge。全原34相对起点main10×、maxheap8GiB、query worker≤NCPU及真实任务退出要求不变。

[独立完整报告](independent-analysis.md) · [完整原始证据压缩包](evidence.tar.gz) · [逐文件校验](archive-receipt.json)
