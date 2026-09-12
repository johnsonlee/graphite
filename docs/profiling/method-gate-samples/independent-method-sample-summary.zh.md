# Method gate 六份主基线录制的独立样本核对

本次 Mac 主基线采样中，参考 expected 构造是显著的 CPU 工作；分配权重主要在含 Cypher 包帧的服务端栈。二者是不同指标，不能把一个阶段的 CPU 占比套到分配，也不能据此解释原 Linux CI 的 base/candidate 失败。未修改 gate、未重新测量。

读取已有唯一 Method JMH 窗口，TSV 哈希匹配，六份 CPU/alloc collapsed 权重与 analysis.json 总量一致，无缺失或截断栈。窗口比 JMH wall 短 10,375–16,083 ns，包含整个场景，不是单 HTTP 请求。此次未重做 JFR 解析或大文件哈希；输入 graph hash 只引用已有 pre-capture receipt，本核对没有补做 posthash。

## CPU：每行使用所有记录样本作分母

以下列互斥且合计等于总数。reference 是 expected 或 expectedByGraph 任一精确方法帧的 union；engine 是任一 io.johnsonlee.graphite.cypher.* 帧的 union。六次两者交集均为零。JMH 非reference 已排除 engine（实际交集也为零），包含客户端请求、解析、响应规范化/摘要校验和计数工作。server 其他为具名 Jetty/graphite-cypher 服务线程上不含 engine 的剩余样本。

| 场景/轮次 | 全 CPU | reference | engine union | JMH 非reference | server 其他 | compiler | GC 后台 | 其他 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 4-count-1 | 285 | 94 (32.98%) | 66 (23.16%) | 7 | 14 | 102 | 0 | 2 |
| 4-count-2 | 285 | 94 (32.98%) | 63 (22.11%) | 6 | 14 | 106 | 1 | 1 |
| 4-count-3 | 287 | 90 (31.36%) | 68 (23.69%) | 11 | 12 | 103 | 0 | 3 |
| 36-or-1 | 2407 | 985 (40.92%) | 267 (11.09%) | 22 | 53 | 441 | 603 | 36 |
| 36-or-2 | 2366 | 983 (41.55%) | 264 (11.16%) | 24 | 38 | 418 | 604 | 35 |
| 36-or-3 | 1923 | 976 (50.75%) | 278 (14.46%) | 21 | 48 | 443 | 113 | 44 |

expectedByGraph 栈权重为：4-count 75/74/72，36-or 786/781/778，全部已包含在 reference union 中，不可再相加。reference 全部位于 JMH 客户端线程。reference CPU leaf 主要为 String.equals、ArrayList iterator 和 expected/predicate 本身；这与参考全列表过滤相符，不能据此量化未采样阶段。

compiler 同时按线程名及 CompileBroker::compiler_thread_loop 原生根栈识别，例如 4-count-1 的匿名 tid 线程有 57 个编译样本。只按 CompilerThread 名字会漏计。GC 后台按 GC/G1 线程名或 ConcurrentGCThread::run 根栈识别。这里不把编译或 GC 的同时出现归到某个查询阶段，也不把 GC CPU 样本解释成暂停时长。其变化会改变所有 CPU 分母：36-or reference 原始样本 985/983/976 相近，而第三次百分比更高，不能只凭百分比判断其工作增加。

engine union 是调用栈归属，不是 engine-exclusive leaf，也不等于严格的纯查询算法边界；其子调用可以落到 webgraph、容器、类加载等包。调用栈中的 Cypher 辅助逻辑也会计入。各线程权重与 leaf 详见 receipt。

## 分配：记录的 TLAB / OutsideTLAB 加权字节

| 场景/轮次 | 全采样字节 | reference | engine union | JMH 非reference | server 其他 | 其他 |
|---|---:|---:|---:|---:|---:|---:|
| 4-count-1 | 61,416,376 | 0 | 58,532,792 | 786,432 | 2,097,152 | 0 |
| 4-count-2 | 67,316,360 | 1,310,720 | 61,549,192 | 1,048,576 | 3,407,872 | 0 |
| 4-count-3 | 62,415,096 | 0 | 59,793,656 | 1,048,576 | 1,572,864 | 0 |
| 36-or-1 | 492,053,456 | 3,670,016 | 473,703,376 | 5,767,168 | 7,602,176 | 1,310,720 |
| 36-or-2 | 490,892,328 | 3,145,728 | 473,328,680 | 7,077,888 | 6,029,312 | 1,310,720 |
| 36-or-3 | 498,105,936 | 5,242,880 | 481,328,720 | 4,194,304 | 6,553,600 | 786,432 |

分配计数没有 compiler/GC 后台权重；这不是说这些线程不会分配。零 reference 采样也不代表零真实分配。分配权重不是精确总分配或对象数，不能把每个 TLAB 的大小都解释成叶帧所创建对象的大小。

含 engine 帧的分配栈重复出现 MappedMethodIndex.Companion.build、Arrays.copyOf 和 Int2LongOpenHashMap.rehash。build 的 leaf 权重：4-count 为 32,001,304 / 33,000,000 / 33,000,000 字节；36-or 为 297,262,144 / 294,266,056 / 297,000,000 字节。这是本次基线采样的稳定现象，不构成新的优化或原 CI 退化归因。

## 限制与产物

没有使用 Mac residentSet 字段作 RSS：该平台实现会退到 Runtime.totalMemory。没有把本次 profile wall time 当验收结果，没有判断原 Linux 失败是噪声。尚未完成的输入 posthash 不由本核对补全或宣称完成。

- 重算脚本：independent-method-sample-audit.py
- 结构化数据与分类规则：independent-method-sample-receipt.json
- 原始输入：method-{4-count,36-or}-{1,2,3}-analysis/analysis.json 及其 collapsed 文件
- 原输入记录：input-receipt.json
