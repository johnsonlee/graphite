# Attempt135 targeted DISTINCT 独立采样核对

这六份录制不足以解释原无 profiler 退化，不能用于推翻 Attempt135 的拒绝结论。新 lazy/callback 的 CPU inclusive 样本很少，且全部与真正的字符串候选发现重叠；没有采到 callback 自身的 exclusive leaf。没有证据据此断言 callback、线程调度或编译是退化原因。

范围是 `global-wide-wrapped-case-insensitive-distinct-targeted`，每份第 29/34 个查询窗口。读取已有 ProfileWindows 结果和完整 collapsed stacks，没有运行 Java 或新测量。六份原 JFR/TSV 哈希匹配分析记录，CPU 与 allocation collapsed 权重守恒，无缺失或截断栈，全部查询成功且结果摘要相同。采样顺序为 candidate/base、base/candidate、candidate/base。

## CPU 样本

表中 raw、发现和 lazy 是每条栈至多计一次的 inclusive union。候选把发现移入 raw 函数，因此候选 raw 与发现/lazy 重叠，不能相加；“raw 排除发现”单独给出可比边界。

| 配对 | 版本 | 全线程 | 应用 | 编译 | raw inclusive | raw 排除发现 | 发现 inclusive | lazy/callback inclusive | 每节点 lambda leaf |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | main | 195 | 103 | 85 | 96 | 96 | 3 | 0 | 58 |
| 1 | candidate | 186 | 97 | 80 | 90 | 88 | 2 | 2 | 46 |
| 2 | main | 121 | 59 | 59 | 55 | 55 | 1 | 0 | 16 |
| 2 | candidate | 121 | 59 | 56 | 57 | 56 | 1 | 1 | 26 |
| 3 | main | 104 | 59 | 43 | 54 | 54 | 1 | 0 | 30 |
| 3 | candidate | 104 | 51 | 53 | 46 | 46 | 0 | 0 | 15 |

应用线程细分（request / graph worker / segment worker）：main 为 1/38/64、1/23/35、2/23/34；candidate 为 2/35/60、0/24/35、1/21/29。编译线程同窗口出现不等于请求因编译阻塞。

新增栈为 `distinctStringPropertyDisjunction$lambda$11/12/13` 与 `UnsafeLazyImpl.getValue`。三份 candidate 的 lazy/callback exclusive leaf 都是 0；这表示采样未捕获自身叶帧，不表示零成本。main 的 exact discovery leaf 为 1/0/0，candidate 为 0/0/0。

各版本共同的叶帧包括原始节点扫描 lambda、`MappedNodeTypeIndex.forEachIdWhile`、`IntOpenHashSet.contains`、范围迭代和 mapped buffer 访问。main 的 `IntOpenHashSet.contains` leaf 前两次是 12/8；candidate 的范围迭代 leaf 前两次是 8/3。如此少的样本、不同的内联/编译状态和共享热点不足以识别稳定的新增热点。

## 分配采样

单位是 NewTLAB / OutsideTLAB 的采样加权字节，不是精确总分配，也不能把包含 callback 的栈权重解释成 callback 对象大小。

| 配对 | 版本 | 总采样字节 | raw 排除发现 | 发现 | lazy/callback（与发现重叠） | iterator leaf | getIndices leaf |
|---|---|---:|---:|---:|---:|---:|---:|
| 1 | main | 7,864,320 | 6,815,744 | 786,432 | 0 | 3,145,728 | 2,621,440 |
| 1 | candidate | 7,077,888 | 6,553,600 | 262,144 | 262,144 | 3,670,016 | 2,097,152 |
| 2 | main | 4,194,304 | 3,932,160 | 0 | 0 | 1,835,008 | 1,835,008 |
| 2 | candidate | 4,718,592 | 3,932,160 | 0 | 0 | 786,432 | 1,835,008 |
| 3 | main | 4,194,304 | 3,932,160 | 262,144 | 0 | 2,621,440 | 1,310,720 |
| 3 | candidate | 4,456,448 | 3,670,016 | 0 | 0 | 1,835,008 | 524,288 |

所有采样分配均落在应用线程。新增 lazy/callback 的 allocation exclusive leaf 也是 0/0/0。总分配权重变化方向不一致，不能据此认定发生或不存在精确分配回归。

## 执行阶段和结论边界

六次都是 12 行、LIMIT 200、64 图访问、2 次 raw scan、106706 work units，结果来源为 kotlin-compiler-11/15。`QueryPipeline.projectSource` 传入 `selectedValues=null`；12 小于 200 触发提前返回，根本不进入 selected tuple 来源补全阶段。因此这是候选对无 selected 路径的诊断，不是 dense selected feasibility 收益的诊断。

现有 `worker-bytecode-comparison.json` 报告每节点 predicate lambda 360 行和 worker lambda 101 行，在仅归一常量池编号和 lambda 数字名称后相同。这份独立核对读取并记录该证据的哈希，没有重新运行 javap。主要 raw 热点同时存在于 main，不能因它占比高就归因于新增 callback。变化位于 outer raw/provider 准备；此处没有足够样本定位原始退化原因。

本次带 profiler 的查询耗时 main/candidate 分别为 38.215458/36.025583、23.452375/23.584625、23.110334/21.727709 ms，仅留作窗口上下文。它们没有重现先前约 54% 和 52% 的无 profiler 退化，不能替代原验收样本，也不能证明原退化是噪声。

可复核产物：`compare-targeted-independent.py`、`independent-targeted-comparison-receipt.json`、`independent-targeted-comparison.log`。receipt 含输入 SHA256、逐录制线程计数、inclusive/leaf、互斥阶段组合与完整证据路径。本次核对未改动原始录制或生产代码。
