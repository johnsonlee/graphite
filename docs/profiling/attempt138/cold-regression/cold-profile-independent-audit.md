# 已拒绝 138 冷态录制：独立审计

**核验通过，但尚未定位原始回归的原因。138 继续保持 rejected。** 这两份录制是一次 base 与一次 candidate 诊断，每份 JVM 交替运行 40 次 rows 和 40 次 DISTINCT；不能把 40 次重复当作 40 个独立版本配对，也不能用录制时延重判原本已失败的三组无 async-profiler 测量。

## 正确性、窗口与输入

重新逐行检查两份 TSV、两份 full rows JSONL 和 workload：160 个查询、32,000 个完整结果行的值、顺序、graphIds、四列 columns、query SHA、digest、rowCount、success、64 sources、per-query-cold 全部匹配。录制使用 oracle-v2 的两条 pure-four-OR query；已确认这两条的完整 query 对象（含所有 expectedRows）与 oracle-v3 完全一致。80 个 ID 严格按 rows/distinct × 00–39 顺序，无缺失或额外项。

现有 ProfileWindows 输出的 80 个 window/版本逐项与 TSV 绑定一致；纳秒时间戳重算 duration、非重叠、ordinal 和 gap 全部通过。JFR 本身不存 query ID，因此绑定依赖顺序和精确方法 trace 的原分析器提取；本审计没有重新解析原生 JFR。正 gap 表示 TSV 内未被 outer trace 包含的时间，已保留，没有当作错误过滤。

- base：gap 62333–7209292 ns；累计正 gap 16681035 ns。
- candidate：gap 64666–6134916 ns；累计正 gap 15247162 ns。

命令均关联已冻结的 base / rejected138 JAR，1ms CPU、256KiB allocation sampling、CPU=4、同一80条 workload；candidate 记录 JAR hash 与 build/old34 收据一致。classpath 前置目录仅一个 `MultiKeywordProfileRunner.class`，没有 engine class 覆盖。`input-receipt` 记录64图输入；capture 脚本仅在末尾 graph identity 和两个 JAR hash 与开始值相等后才写 completed。这里核对了命令/收据/脚本及 helper hash，没有再次读取整个 JAR、图或 JFR 来重算大文件 hash。

## 采样守恒与分母

320 份 CPU/allocation collapsed 文件逐条重算：每个 window 的全线程权重 = 逐线程权重之和 = 全部 collapsed 权重 = leaf 权重之和。递归 frame 在 inclusive 计数中按每个 stack 去重，所有发布的 global/thread top-inclusive 和 top-leaf 权重均匹配。汇总方法匹配、每个 query 的方法交集和 top leaves 与 root summary 一致。missing/truncated stack event 均为0；这不等于运行时每次调用都采到了。

CPU模式按 ActiveSetting 的 `event=cpu` 判定；同时存在 `engine=wall` 不应把它改称 wall samples。这里只读 native分析结果，未混入 companion JVM 的 CPU样本。窗口内 CPU base 15,034 / candidate 16,054；录制全部 CPU 则为31,250 /31,942，其余属于查询窗口外，不能塞进查询分母。

| 投影 / 版本 | 全部 CPU | 应用 CPU | JIT线程 CPU | GC线程 CPU | 其他后台 CPU | 全部allocation采样权重（bytes） |
|---|---:|---:|---:|---:|---:|---:|
| rows / base | 1263 | 937 | 200 | 0 | 126 | 999293536 |
| distinct / base | 13771 | 12254 | 1236 | 15 | 266 | 49256372208 |
| rows / candidate | 1290 | 1024 | 227 | 0 | 39 | 953156192 |
| distinct / candidate | 14764 | 13159 | 1262 | 13 | 330 | 49131067264 |

以上线程类别互斥；应用指请求线程和既有 graph/segment/CallSite workers。JIT/GC只是同时间窗内的线程采样，不能因果归属某个查询阶段。allocation 数字是 NewTLAB/OutsideTLAB 所记录的**采样权重**，不是整次实际分配 bytes 或对象数。

## DISTINCT 的方法权重

| 方法栈 union（应用线程） | base CPU | candidate CPU | base allocation权重（bytes） | candidate allocation权重（bytes） |
|---|---:|---:|---:|---:|
| validator | 11216 | 12420 | 48960411632 | 48880719744 |
| mappedLoad | 11296 | 12488 | 48994490352 | 48913749888 |
| candidateDiscovery | 250 | 9 | 29884416 | 262144 |
| rawProjection | 326 | 212 | 121110528 | 11534336 |
| selectedProjection | 0 | 86 | 0 | 87818240 |
| selectedStringIds | 0 | 75 | 0 | 83886080 |
| findId | 50 | 54 | 66060288 | 63700992 |
| selectedAnchor | 0 | 0 | 0 | 0 |
| postingValidation | 0 | 0 | 0 | 0 |
| initialLimitProbe | 0 | 1 | 0 | 0 |

这是 inclusive union，行之间会重叠，禁止相加。例如 base findId 的50个CPU都在rawProjection内；candidate findId54个都在selectedStringIds内，selectedStringIds75个又都在selectedProjection86个内。candidate allocation 的63,700,992 bytes findId权重包含在83,886,080 bytes selectedStringIds中，再包含于87,818,240 bytes selectedProjection。validator∩mappedLoad CPU为base11,209/candidate12,416，不与各自validator总量完全相等；以实际可见栈为准，不补造缺少的父帧。

应用CPU增加905个样本（12,254→13,159），validator增加1,204个（11,216→12,420）；应用内非validator部分反而从1,038降为739。validator allocation权重48,960,411,632→48,880,719,744 bytes略降。新增selectedProjection仅86个CPU样本，当前证据不能把原36–40ms退化归因到这个调用或posting工作。selectedAnchor/postingValidation没有样本，**不能推出调用次数为0**。

rawProjection有一个重要命名边界：root pattern匹配实际方法入口，未包含缺少该父frame的同名前缀 generated worker lambda。独立扩展为方法或其generated lambda的union后：

| raw完整命名族union | base | candidate |
|---|---:|---:|
| CPU | 498 | 371 |
| allocation采样权重（bytes） | 160,694,272 | 54,788,096 |

其中仅generated lambda且无入口frame的部分CPU为172/159、allocation权重39,583,744/43,253,760 bytes。原summary的入口统计本身正确，但不应把326→212称为所有raw worker工作的完整数量。root后续新增 `rawProjectionIncludingWorkers` 字段并保留入口字段；本审计已对更新后的summary重新计算，包括新增字段及其方法交集，全部一致。扩展union仍不能定位墙钟退化。

## 工作计数及判断

40次rows每版本均为1,152,827 work units；40次DISTINCT base均57,697,051、candidate均57,699,276，与原三组被拒绝的该query工作计数一致。签名与工作重复一致说明采到了同一工作负载，不能将工作单位折算成CPU时间或分配。

另外独立逐字节比较了两个已导出的 `javap -c -p` validator文本，与收据一致，SHA均为 `2d32c910fd0040d9b19249b32946d4ac3928883672ee944b042cd661a8136973`，命令指向对应冻结JAR。这证明所导出的validator字节码指令一致，不等于JIT机器码、执行次数或运行环境一致，也不建立运行时因果。

尚缺乏证据解释原回归的具体原因。共同validator仍占主要CPU和allocation采样权重；其样本增长不等于其实际代码变慢，也不能把JIT/GC共现归因给selected路径。此次没有修改生产、门槛或拒绝决定，没有启动Java/构建/新测量。机器可读核验、逐collapsed SHA/权重、线程分组与方法交集见 `cold-profile-independent-audit.json`；重算脚本为 `cold-profile-independent-audit.py`。
