# Attempt 141：拒绝密集 DISTINCT 属性位表

**拒绝，随后显式回退。** 195 项测试、36 条补充查询完整结果以及原 34 条的 204 个完整签名均正确，但三组原有配对中前两组 P95 变慢，第一组 CPU 也超出原回归门槛。第三组改善不能抵消失败；不继续 v3 性能配对，不启动候选 CI，不重跑取绿。

冻结 main：`4e328b0109e13c896b74004823fb049fcb19251a`；候选父提交：`b94b8caa8dea10d1d2ddb74a0a0c39a3ab5351f0`。候选 JAR SHA256 为 `11b8e055adf26bf5f7d18c0d2015f8b938d0ed0b48b96eb7899715f4b70e2af0`；完整临时目录 `/private/tmp/graphite-attempt141.sb7dlq4r`。

## 唯一改动和依据

在 raw DISTINCT 的 exact membership 中，仅当全字符串地址域 S 的 byte 表 payload 不大于原所有 IntOpenHashSet key 数组 payload 合计时，使用每 ID 四属性位表；否则保留原 hash。实际 sidecar anchor 与独立 used-ID 穷举的 1,216 组计数/ID hash 一致：旧 dense 和全 64 图四词 OR 满足条件，稀疏查询不满足。这个依据只是数据结构规模，不是收益。

原 predicates.indices.any、List 属性索引、selected 可行性/缓存、索引格式、节点访问、计费、flush、线程池及 join 保持。新表在分配前验证候选范围，非法候选回退；probe 越界视为未命中。预检和填表周期检查线程中断；只置请求信号的取消仍由随后原 accounting/flush 观察。新增内存遍历与清零不是免费操作，也没有被当作新的 storage item 读取计费。

独立字节码确认节点 callback 790→853 bytes，多捕获一个 byte[]，新增直接 baload/range/bit 检查，保留原 hash fallback、List 与 iterator 静态点。不能把字节码形状、payload 上限或第三组改善当成加速原因。

## 正确性与真实配对

195 tests、detekt、JMH 包装及 test-exclusion 全通过。v3 控制完整核对 36 查询、6,171 行的值/顺序/所有来源；原 34 三组的 204 份 14 字段 oracle 均通过。全部非 latency TSV 字段与对应 main 相同。所有 64 图/1,088 文件前后 hash 一致。

输入是 Android14、Tika2.9.2、Hive4、Kotlin compiler2.0.21 各 16 个真实持久分片，共 5,046,935 CallSites；不是合成性能图。原协议 Java17、4 active CPUs、8GiB、JMH 零 warmup/单次回放及 GC profiler 不变。配对顺序 C/B、B/C、C/B；没有并发构建、其他 Java、导出或 profiler。P95 是每组原 34 条的 nearest-rank 第 33 个耗时，不是同一查询重复抽样的 P95。

| 组/顺序 | main → candidate P95 ms | main → candidate CPU s | 严格进步 |
|---|---:|---:|---|
| 1 C/B | 41.608625 → 118.184625 | 1.476806 → 1.819397 | 否；CPU 亦超过15% |
| 2 B/C | 48.693708 → 55.151042 | 1.492257 → 1.572952 | 否 |
| 3 C/B | 61.887459 → 46.230833 | 1.644617 → 1.519967 | 是 |

各组 P95 均对应 wrapped DISTINCT dense。保留所有 57/102 个变慢观测；无重复超过 +15% 且 +1ms 的单查询，但“每组 P95 必须进步”和资源门槛仍明确失败。CPU/heap/RSS 和完整逐行结果见独立审计。没有证据确定退化来自新增分支、JIT、清零或其他成本，不能将其解释为噪声后忽略。

方法级与端到端候选 CI 没有运行，不能宣称通过。回退后的 CI 独立验证，源码与 main 相同不构成 CI 已绿的证据。CallSite 线程池仍存在，最终 10x 未达到。

- [计划与固定验收协议](measurement-plan.json)
- [完整源码 diff](candidate-source.diff)、[候选新增测试](candidate-test.kt)
- [独立构建与机制](independent-build-audit.md)、[完整控制审计](independent-control-audit.md)
- [原 34 独立审计](independent-old34-audit.md)、[原 gate 状态](old34-pairs/global-wide-status.json)
- [决定](decision.json)、[根独立 P95/输入复算](root-decision-audit.json)
- [构建命令](build-command.json)、[控制命令](v3-control-command.json)、[原 34 runner](run-old34-pairs.py)

拒绝的候选提交为 `22a428c8438b7358ea2c62e774c87d3ea5b9ec7a`。随后恢复生产文件并移除依赖被拒绝 factory 的新增测试；全 130 main/JMH 与 frozen main、全 168 原测试与父提交逐字节一致。候选测试源码保留于报告。见 [回退核验](revert-source-receipt.json)。回退 CI 尚未判定。
