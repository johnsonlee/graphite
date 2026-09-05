# DISTINCT 阶段诊断独立审计

现有三份录制通过本次独立 Python 复核：102 条完整 correctness signature 与冻结 oracle 按顺序相同；每份 34 个 outer 窗口、192 个内部阶段调用，CPU/分配的三阶段与线程分区全部守恒。未发现改变这些录制正确性描述的问题。本次没有运行 Java、重新采样或修改 JAR/生产文件。

审计从 JSON 中的原始 call 时间戳重新求跨线程区间并集，并与原 ProfileWindows 输出及完整 collapsed 权重交叉核对；没有用第二个 JFR decoder 重读事件。已核对小型 JFR/TSV/分析输入 hash。输入 JAR/64 图在采样前后相同的结论来自 input-receipt.json、input-after-receipt.json 和已审查 capture.py 的断言；本次没有重算大图/JAR hash。

## 窗口与样本守恒

| 录制 | 查询 / phase calls | 所有 CPU 样本 | TLAB/OutsideTLAB 采样权重（bytes） | allocation events | 首查询未追踪 TSV 时间（ms） |
| --- | --- | --- | --- | --- | --- |
| 1 | 34 / 192 | 1363 | 1,249,117,184 | 4573 | 8.037625 |
| 2 | 34 / 192 | 1201 | 1,252,262,880 | 4591 | 7.719750 |
| 3 | 34 / 192 | 1218 | 1,229,194,208 | 4503 | 7.914000 |

核验包括每查询、每 metric 的 weight/eventCount/missingStack/truncatedStack，以及 eventType/state 和每线程权重；initial + provenance + other 等于 outer，outer 的完整 collapsed 之和也相等。所有录制 CPU/alloc 样本的 missing/truncated stack 均为 0。`Metric.add` 用 frame-name set 避免同一事件递归帧重复 inclusive 计数；不同函数的 inclusive 仍可重叠，不能把 top inclusive 相加。top 列表有限长，也不能作为全部栈的守恒分母。

三份记录各只有 zero/targeted/dense 三条 wrapped DISTINCT 进入该内部方法：zero 64 initial；targeted 64 initial；dense 1 initial + 63 provenance。其他 31 条没有这两个内部 trace。targeted 三次均返回 12 行、work 106,706；dense 三次均返回 200 行、work 283,544。它们的两次 raw scan 计数相同，不代表其余字符串索引/来源检查工作相同。

| 录制 / query | outer ms | initial union ms | provenance union ms | other ms | TSV - outer μs |
| --- | --- | --- | --- | --- | --- |
| 1 / targeted | 30.229875 | 29.332084 | 0.000000 | 0.897791 | 52.417 |
| 1 / dense | 55.217000 | 5.079916 | 47.671875 | 2.465209 | 54.709 |
| 2 / targeted | 31.118083 | 30.259956 | 0.000000 | 0.858127 | 70.001 |
| 2 / dense | 39.489917 | 5.700375 | 31.285250 | 2.504292 | 59.500 |
| 3 / targeted | 25.923583 | 25.315584 | 0.000000 | 0.607999 | 65.583 |
| 3 / dense | 42.790541 | 6.081208 | 33.777083 | 2.932250 | 59.709 |

dense 的 63 个来源调用 duration 相加分别为 93.707 / 60.964 / 65.976 ms，实际跨线程并集是 47.672 / 31.285 / 33.777 ms；直接相加会把并行调用重复算作 wall。各阶段互不重叠，other 是 outer 的补集。区间按 [start,end) 归属，每个样本只分到一个时间阶段；这不是样本自身调用栈的阶段因果判定。

外层正 gap 不应被当成误绑的充分证据：首查询有 7.720–8.038 ms 未追踪时间，明确留在 untracedTsvNanos；focused 两条只有 52.417–70.001 μs。当前规则仅在 trace 超出 TSV 超过 1 ms 时拒绝，与 ProfileWindows 相同。本次没把正 gap 填进 initial/provenance，也没有把它删掉来凑守恒。

## 阶段里的应用与背景活动

以下 CPU 列是“该时间区间内：所有样本 / 应用线程样本 / compiler 样本”。应用仅指 request、graph、segment、legacy scan 线程集合；余数是 GC/采样器/其他线程。线程分类互斥，compiler 兼查线程名和 CompileBroker::compiler_thread_loop 栈；不把背景活动算作应用阶段的因果成本。

| 录制 / query | initial CPU all/app/compiler | provenance CPU all/app/compiler | other CPU all/app/compiler | initial / provenance / other 采样分配权重（bytes） |
| --- | --- | --- | --- | --- |
| 1 / targeted | 151/76/66 | 0/0/0 | 3/1/2 | 7077888/0/0 |
| 1 / dense | 22/8/13 | 223/123/100 | 11/4/6 | 524288/13369344/524288 |
| 2 / targeted | 151/79/69 | 0/0/0 | 0/0/0 | 9175040/0/0 |
| 2 / dense | 26/9/17 | 155/87/67 | 12/3/7 | 1048576/13631488/1310720 |
| 3 / targeted | 128/66/58 | 0/0/0 | 3/1/2 | 3407872/0/0 |
| 3 / dense | 22/8/14 | 157/80/73 | 8/1/5 | 1310720/12582912/262144 |

例如 dense provenance 的所有 CPU 223/155/157 中，compiler 为 100/67/73，应用为 123/87/80；不能把所有 223/155/157 当成来源合并算法 CPU。本次 focused 分配样本均来自应用线程，但 TLAB 权重代表采样分配容量，不是整个阶段实际分配精确值；权重所在 leaf 也不代表这些字节全部由同一调用分配。GC/JIT 与某阶段共时不是该阶段触发它们的证据。

## 绑定与漏检边界

- outer 使用 ProfileWindows.queryTrace：exact execute descriptor + broad-query-pressure-worker；34 个互不重叠窗口按时间顺序对应完整 catalog/TSV。JFR 本身没有 query ID，这仍依赖顺序 replay 的假设。
- 内部 phase 只匹配 QueryPipeline owner 与两个方法名（projectSource 和 lambda155/lambda154），没有匹配 descriptor、graph ID 或独立的预期线程表。当前结果的线程只来自 request/graph worker，调用数与源码一致；不能据此证明 63 个 trace 分别对应哪 63 个 graph ID。
- DistinctPhaseWindows 自身只拒绝 phaseCount=0，没有硬性要求 192 次，也不直接检查 catalog 顺序。本次额外要求每条调用数 64/64/1+63，且与已完成 catalog 验证的外层分析交叉核验。它适合本次精确 frozen-JAR 证据；未来换 JAR/lambda 名称需要重新 fail-closed 校验，不能复用这次成功作为完整 trace 的保证。
- 0 missing/truncated sampled stacks 是采样记录质量检查，不证明未采样的执行/分配不存在。遗漏一个没有样本的 phase trace，可能仍通过简单的样本守恒；本次 call census 能加强检查，但不构成第二种 instrumentation 验证。
- union wall 包含调用等待期间；没有区分每个调用中的 engine、queue、锁与调度时间。此次是带 profiler 的阶段诊断，不是新性能验收。

## 旧 133/135 的 P95 转移

按每份 34 行 TSV 的 empirical P95（升序第 ceil(34×0.95)=33 项）重算，所有历史完整 signatures 同样匹配 oracle。输入路径：Attempt 133 为 /private/tmp/graphite-mapped-tuple-evidence.t2461mo1/{base,candidate}-global-wide-{1,2,3}.tsv；Attempt 135 为 /private/tmp/graphite-attempt135.tkrgxwsr/old34-pairs/ 下同名文件。

| attempt / pair | base dense / targeted ms | candidate dense / targeted ms | candidate P95 query |
| --- | --- | --- | --- |
| 133 / 1 | 72.110875 / 60.240667 | 45.540875 / 57.048625 | targeted |
| 133 / 2 | 61.735750 / 35.073375 | 61.050334 / 61.360000 | targeted |
| 133 / 3 | 96.511125 / 61.495209 | 50.091541 / 63.982458 | targeted |
| 135 / 1 | 43.854125 / 21.969542 | 27.758875 / 33.787208 | targeted |
| 135 / 2 | 47.669625 / 20.936541 | 29.935166 / 31.795583 | targeted |
| 135 / 3 | 60.722625 / 41.592959 | 29.106583 / 23.678916 | dense |

133 三组候选 P95 都移到 targeted；135 前两组移到 targeted，第三组仍是 dense。因此不能概括为 135 每组都已迁移。新阶段记录说明 targeted 在这条路径中没有 selected provenance 调用；它的主要 wall 区间是 initial discovery。

作为明确限定的敏感性计算：固定旧 baseline 其余 33 行不变，甚至把 dense 整条 latency 设为 0，第 33 项仍是 targeted；133 三组 P95 最多变为原来的 1/1.197、1/1.760、1/1.569，135 为 1/1.996、1/2.277、1/1.460。连“删除整条 dense 时间”都不足以在这些固定行集得到 10x，单独缩短 dense provenance 更不能据此宣称达标。这个条件计算不是全局理论上限：如果实现同时改变其他 query、缓存或资源竞争，其他行也会变化，须重新测量。它也不允许用 profiler 时间重判已拒绝尝试，或把背景编译解释成失败原因。

完整纳秒数据、每阶段互斥线程分类、102 查询核验和输入 SHA-256 见 independent-phase-audit.json。
