# 完整38：混合查询的类型遍历工作量诊断

**12个完整回放的工作量算式全部匹配：True。候选仍未接受，10x未达成。**

官方PR04ae与取消helper177d保持不变；真实CI64、P4、maxheap8g、38条原查询/顺序、两种原reset、每种三对C/B–B/C–C/B。12 JVM实际退出、456完整值/顺序/来源、228配对、768类型映射独立验证。

当前q21 `(A AND B) OR (C AND D)` 走generic nodes(Node)过滤，其直接路径不调用被改helper。当前可信数据独立推导：第200个匹配CallSite在该类型内第45,297个位置，完整200行与可信main结果相同。

各fork的实际总扫描量 = 同一fork在CallSite之前的其他类型节点数 + 45,297。类型范围按Class键HashMap的实际顺序遍历。顺序在index加载后不再修改，因此查询全部输出后的只读快照可绑定该fork的遍历；这不是对旧已退出fork顺序的重建。

| reset | pair | 基线→候选 ms | 基线→候选扫描节点 | 其中前置其他类型节点 B→C |
|---|---:|---:|---:|---:|
| per-query-cold | 1 | 734.563 → 538.070 | 300010 → 216905 | 254713 → 171608 |
| per-query-cold | 2 | 663.446 → 184.504 | 266599 → 45571 | 221302 → 274 |
| per-query-cold | 3 | 190.928 → 372.685 | 45297 → 133297 | 0 → 88000 |
| replay-cold | 1 | 568.724 → 153.913 | 264546 → 45297 | 219249 → 0 |
| replay-cold | 2 | 212.605 → 152.444 | 69963 → 45297 | 24666 → 0 |
| replay-cold | 3 | 193.168 → 165.021 | 55350 → 49918 | 10053 → 4621 |

记录全部原诊断回退标记：
- per-query-cold: or-four-single-early-rows
- replay-cold: or-few-early-middle-rows, or-four-broad-rows

所有非时间字段差异保留在 numeric-summary.json，共7个查询配对记录。每查询/版本/reset只有3点，不称P95；此adapter不测CPU/RSS。

适用范围：实际类型顺序精确解释此批q21的扫描量差异，不能由此把全部耗时变化归因于它，不能宣称helper无开销，也不能豁免原full38回退、原34相对起点main失败或CI。新诊断修改了adapter，不是优化验收批次；即使调用在最后，新增方法及常量池引用仍可能影响前序类加载/JIT，不能声称timed查询零扰动。后置检查还可影响teardown/整进程时间。

编译审计：重新编译的原adapter class与冻结实际class逐字节相同；逆向删除单次后置调用后9个原方法符号指令/异常区间相同，只有1个inspection方法新增。首次stream lambda导致scope失败的完整准备已保留，修订为for循环后通过。符号比较排除debug/frames/metadata，不推导JIT等价。

与Attempt140的区别：旧40次只做孤立q21，未保留完整前序；这里执行了完整38，并从当前可信CallSite导出独立计算前缀。旧当前fixture有226个文件字节不同，64typeindex相同；不把旧45297残差当输入。

下一步：结束此类型顺序观察器。保留取消helper为未接受候选，处理原34第5条相对起点main的重复回退，先区分官方PR既有成本和helper新增成本；不叠加混合OR或类型排序优化。全原34最终10x、maxheap8g、NCPU上限及取消实际退出要求不变，未新增production commit/push/CI/merge。

原始路径、精确命令和JAR/fixture/oracle身份见complete38-pairs/run.json；type-audit.json验证实际768映射，current-prefix/保留独立推导。归档不包含外部原始JAR及9.7GB图，需要原路径对应内容才能重跑。

[独立完整报告](independent-analysis.md) · [完整原始证据压缩包](evidence.tar.gz) · [逐文件校验](archive-receipt.json)
