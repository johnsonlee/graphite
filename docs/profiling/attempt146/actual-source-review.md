# Attempt 146 实际源码与新增测试独立审查

**实际 diff 通过本轮有界源码审查：它精确实现原 empty fast-path 方案，未发现需要阻止构建的语义问题。** 性能及构建结果不属于这个源码结论；本审查没有运行 Java、测试、构建或计时。

候选目录 `/private/tmp/graphite-attempt146.lztftoe1/candidate`，当前 HEAD/候选未提交改动的基底为 `cf300347247174a74bbbd188e89c36c094ae2bdd`（145）。唯一 tracked production diff 为 GraphTaskScheduler.kt:426 的一行 `if (children.isEmpty()) return primaryFailure`。独立将此行从现文件移除后，剩余字节与 `git show HEAD:…` 完全相同。因此没有附带修改 child scope、非空清理、notify、cap、lane、context restore、结果/取消/lease 发布或调度粒度。源码树中唯一新增文件是 GraphTaskEmptyChildrenTest.kt；构建生成的 `.kotlin/` 不属于源码变更。

| 文件 | SHA256 |
|---|---|
| GraphTaskScheduler.kt | `7a155b69b020bad81b5babc98901583b39161069233f7a5dcd7d80dfce7db3b5` |
| GraphTaskEmptyChildrenTest.kt | `23b386f16fb7174ab5bbc033e58666178c3325332ec9a22fa9f0aade6dbdae24` |

两项 hash 均与当前 checks-inputs.json 完全匹配。实际同步论证仍成立：第一次锁中先关闭 registration 再判空；同 monitor 内的 register 无法随后插入 child。非空分支保持原样，最终 execute 临界区照常发布真实退出。non-local return 由 Kotlin synchronized 释放 monitor，不保留锁跨 caller 返回。

## 新测试实际覆盖

测试一个 JUnit 方法在各自独立 GraphTaskScheduler(2) 中运行正常和 cancel(false) 两个分支，无 CPU-count early return。它不使用性能时间判据；5 秒仅为同步失败超时，get(0)验证当前未完成。

- **从未有 child**：owner 开始后没有成功注册子组，先断言 accepting=true，立即调用 finishChildren。owners/canaries 在 owner 外部建立。关闭后的 newGroup 尝试明确断言失败，不会构成已注册 child。
- **显式清理与异常身份**（38–43）：两次带 original 调用均 assertSame，空参数调用 assertNull，关闭后 accepting=false 且新注册被拒绝。原异常在这里是清理方法的参数/返回值，没有真正抛出为 task failure；task 最后正常返回17。非空 failure→Future cause 的既有测试仍需保留，不能把该测试描述为完整异常投递测试。
- **尚未真实退出**（58–63）：callback 停在 release latch，cancel 分支仅取消这个 running task、不中断线程。检查 isDone=false、callbackExited=false、completion queue 空、get(0)超时；没有把 Future 自身 CancellationException 当成 callback 已退出的证据。
- **FIFO canary 与 cap**（66–76）：同一次 scheduler monitor 内先提交同 group replacement，再提交其他 group canary；owner group maxConcurrentTasks=1。owner 占一个物理 worker，只有另一个 spare worker可领取。canary 先到证明排在它前面的 replacement 此时不可领取；随后仍断言 replacement/owner 未完成。若清理误释放 cap，spare worker 必须先执行 replacement，无法满足这组有序断言。这检验真实 slot 生命周期，不只是睡眠未看见工作。
- **正常与 cancel(false) 最终结果**（77–89）：release 后正常 get=17；取消分支 get 抛 CancellationException。两个分支都检查 callbackExited=true、无中断、context.isCancelled 与分支一致，replacement最后返回23、事件为 replacement。finally 释放 latch 并 cancelAndJoin 两个组，外层 scheduler.use 负责线程退出。

该测试是保持既有行为的 guard，不要求 parent 必须失败；本次不是新修复语义的 red/green 证明。没有检查 monitor 次数或臆测加速。对真实非空 children、并发注册、祖先取消、REQUEST completion 重入、helper 和真实 cap 发布仍依赖原有测试群；本轮未重新执行这些测试。

## 构建/诊断边界

本文件落盘时尚未据 final-build-receipt 验证最终 JAR，不沿用145或144的测试/控制结果。本次源审查只读取小源码和 checks 输入记录。后续最终构建可另核实际 XML、终态日志与 source/JAR receipt，不需要重新读取真实图或重复大 payload hash。

原 bounded-review 中的 childDrain 是 probe-on 墙钟包络；没有 entry-empty 计数，也不是锁耗时或可移除成本。没有从一行改动推断 gate 收益，全部原性能/正确性门槛保持。
