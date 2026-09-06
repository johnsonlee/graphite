# Childless finishChildren：有界源码审查

**建议：可实施这个局部 fast path，未发现注册、取消或真实退出语义阻断；它只避免已判定无子组时的空路径重复收尾。没有性能收益证明。** 本次只读审查 145 `cf300347247174a74bbbd188e89c36c094ae2bdd`，没有生产修改、clone、Java、测试或测量。

保留 owner-context 检查；仅在原第一次 monitor 临界区中加：

```kotlin
acceptingChildren = false
if (children.isEmpty()) return primaryFailure
children.toList()
```

这里的 `return` 是从 `finishChildren` 返回（Kotlin inline synchronized 会释放 monitor），不是 `return@synchronized`。非空分支的快照、逐 child closeFromOwner、异常合并、最终清空全部保持原样。execute.finally 的 context 恢复、runner/cap/lane/finished/completion/notify 顺序完全不改；显式 finishChildren 也不提前结束当前 task。

## 并发与异常核对

| 交错或边界 | 结论与源码依据 |
|---|---|
| 注册先取得锁 | newGroup 在 scheduler.monitor 内调用唯一生产 register 入口。已注册未移除的 child 会出现在 children 中，走原非空分支。不得在锁外预读 empty。 |
| 清理先取得锁 | 先设置 acceptingChildren=false，再判空，形成同一注册关闭边界。后来的 register 在同锁内检查 running && acceptingChildren，因此失败；不会在早返回后漏入新 child。 |
| child 同时 unregister | unregister 也持同 monitor，只能删除。若最后一个 child 已被删除，原 group 的 unregister 位于其 awaitAll/cancelAndJoin/close 收尾之后，已有真实 join 保证不丢失。 |
| cancel(false/true) | cancel 与 child 列表遍历持同 monitor。空列表无需传播；cancelled volatile、祖先 token 和 background runner 中断路径不变。第一次锁后发生的取消仍由原 execute.finally/result 语义处理。fast path 不清除中断、不制造取消异常、不提前释放 lease。 |
| queued task 取消 | NEW task 不进入 execute/finishChildren，原 finished 与 completion 处理完全不变。 |
| 显式清理、重复调用 | 可在当前 owner 内从未有 child 时显式关闭注册；后续仍拒绝注册。重复调用返回传入的同一个 primaryFailure。原有 child 先正常 drain 后再调用也可走空分支，行为不变。仍保留当前 owner-context 身份检查，外部线程不能调用。 |
| 异常身份 | 空分支没有 child 清理可产生的新异常，原路径本来也原样返回 primaryFailure（含 null）。不包装、不删除 suppressed、不清理中断。非空分支原 failure/suppressed 规则不动。 |
| 可见性 | acceptingChildren 的写入和所有合法注册/查询由同 monitor 同步；空路径第一次解锁已发布写入。原第二次加锁仅 children.clear、无 notify，不是注册协议所必需。最终 task 状态仍由 execute 的 monitor 发布。 |

当前源码在空路径中调用 `children.toList()` 并遍历空集合，之后第二次加锁清空；execute 再加锁发布退出。建议从源码层面将该路径的两次 finishChildren monitor 进入减少为一次，保留 execute 的第三处。不能据源码直接断言减少了实际堆分配：空集合转换可能返回共享 empty list，JIT 也可能优化锁/遍历。真正可证明的是省去了这个空路径分支的源码操作，而不是多少纳秒。

## 现有测试和增量缺口

已读 core 测试：

- `parent completion closes unjoined children and rejects its expired context`（310）：真正等待 child finally、task 未提前完成、过期 context 注册失败。
- `parent original failure survives unjoined child cancellation and final cleanup`（351）：非空清理取消和原异常身份。
- `queued contexts reject children and closed phases release parent references`（389）：禁止 queued 注册，已关闭/取消子组移除，parent 最后为空。
- `request explicitly drains captured failure descendants before publishing outcome`（523）：显式清理非空组、随后拒绝注册、第二次清理返回同一异常；第二次调用直接覆盖拟议 empty 分支。
- queued/running/ancestor/helper cancellation（75、102、146、185、247、480）：取消及真实退出相关既有约束。

这些是源码覆盖分析，不是本轮执行结果。建议只补一个聚焦测试：**从未注册过 child 的当前 owner 显式 finishChildren(original)，确认原异常身份、isAcceptingChildren=false、显式旧 context 新注册被拒绝、重复清理一致；保持 owner 尚未返回时 task 未 finished。** 可再用确定性 latch 对 cancel(false) 后 owner 真实 finally/退出作验证，确保无中断且 cancellation 仍被观察；不用统计锁次数或测试实现内部步骤。现有非空、祖先取消、REQUEST completion callback 重入与 lease/cap 测试仍需在候选完整验证中保留。

## 144 已有计时只给观测边界

原 on 录制的 childDrain=callableEndNanos→childrenDrainedNanos，是围绕整个 finishChildren 的墙钟包络，含获取锁、调度、可能的 descendant 等待、时钟读取/探针影响；不是 CPU 纳秒，也不单独测两次 monitor。

| 查询/角色 | 三次 task 数 | childDrain inclusive sum（ms） | 单 task median（ms） |
|---|---|---|---|
| q5 SOURCE | 56 / 60 / 56 | 0.455169 / 0.135375 / 0.363706 | 0.001417 / 0.001292 / 0.001646 |
| q30 SOURCE | 63 / 63 / 63 | 0.040996 / 0.051415 / 0.046041 | 0.000500 / 0.000584 / 0.000500 |
| q30 STORAGE | 177 / 177 / 177 | 0.172547 / 0.203330 / 0.195789 | 0.000625 / 0.000625 / 0.000625 |

记录没有 empty/nonempty-at-entry 字段，Task parent 链也不能证明每次 children 已移除、从未注册过空组或从未有子组；不能把这些 task 数直接当成优化命中计数。上表并发 inclusive sum 不能相加为查询延迟，更不能把全部 childDrain 当可移除量。SOURCE 拥有并已显式 close 的 STORAGE 组时也可能最终进入 childless 路径，不能只按 role 判定。

这个局部改动同时触及全部 task role 的 common cleanup；如果实施的是 common empty fast path，不能只称只优化 STORAGE，REQUEST 显式收尾也会经过它。相比逐 source 方案，它不改变 child 的 owner、生命周期或任务粒度，因此不引入此前“后续 source 等待前 task drain”的新依赖环。

## 第二个方向

**没有找到比该 fast path 证据更强、且不改变 scope/唤醒协议的第二个明显重复开销。** 代码存在 completed() 内 notifyAll 后 execute 再 notifyAll 的形式重复，也存在最终 children.clear；但当前 native/queue 资料没有单独归因其成本，而改通知属于本次明确排除的协议范围。取消 snapshots 在这批录制中未实际触发，也无性能依据。不要为凑候选把它们一起改。

建议把 childless fast path 作为单一小候选接受源码论证，保留性能未知。原34每对严格进步、完整 oracle、逐行回归与资源/峰值、v3单/多图 pure OR、最终 exact-head CI 门槛均不变；不从这个子毫秒量级的探针包络承诺能修复既有 gate。
