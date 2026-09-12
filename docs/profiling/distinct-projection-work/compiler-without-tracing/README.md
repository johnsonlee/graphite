# 冻结 main：关闭 method tracing 后的编译诊断

三次独立 JVM 的原 34 条查询全部通过 14 字段 oracle，共 102 条；64 个真实图的 1,088 个文件前后 hash 不变。受保护的冻结 JAR 未修改或重建。仅增加 HotSpot 编译日志选项，保留原 GC profiler，没有 method tracing、async-profiler，也未人为增加预热或强制编译选项。

新的证据排除了“该节点循环在此次短回放中完全没有 C2 编译”的说法：三份运行时日志都发布了这个精确 790-byte callback 的 C2 nmethod，随后都有一次 unstable_if / reinterpret 事件，其内联调用栈是 IntOpenHashSet.contains BCI 37 → callback BCI 322。

| fork | 首次 C1 发布（JVM 秒） | C2 发布 | C2 trap | 再次 C2 排队但无发布 |
|---|---:|---:|---:|---:|
| 1 | 7.633 | 7.647 | 7.648 | 7.671 |
| 2 | 7.575 | 7.589 | 7.591 | 7.616 |
| 3 | 7.643 | 7.666 | 7.666 | 7.696 |

冻结 JAR 的 javap 显示 contains BCI 37 比较查询 key 与初始非空槽位中的 key，失败分支进入后续线性探测。这是去优化发生的位置；记录未提供此次 key、槽位值或完整分支历史，不能直接说是碰撞次数或命中率导致整体瓶颈。callback BCI 322 正是 exactMatchSets 的 contains 调用点。

C2 trap 后有该 callback 的 make_not_entrant 以及新的 C1 nmethod。后续 C2 任务排队但在日志结束前未见 nmethod 发布，且存在未完成的 compiler fragment；这不等于编译失败。编译日志中的 parse 阶段 uncommon_trap 是生成代码的描述，不能混入运行时 trap 计数。

旧采样中的 Interpreted/C1 标签不能代表没有 tracing 的完整执行状态。本轮既没有查询时间窗口，也不是 tracing 开关的配对试验，不能据此确定哪条查询承担了去优化时间，更不能把日志运行耗时当作新代码收益或解释既有失败。

summary.json 保留了 JVM 记录的实际参数，包括 JMH 自动加入的 compiler-blackhole 选项与临时 CompileCommandFile 路径。临时文件内容没有另行保存，不能声称已逐份核验其完整字节；本轮命令相对原模板只新增编译日志选项。

下一步证据需要回答：这个运行时转折发生在初始选择还是来源补全，以及去优化后的工作是否占据慢查询的关键路径。当前不启动生产优化；单纯缩短代码、按属性合并 OR 或移除线程池仍没有 10x 收益证据。

可复算：`python3 analyze.py`。`summary.json` 保存全部原始事件属性、已完成/未完成编译任务、每条查询耗时与非耗时差异；`fork-*-command.json`、`runs.json`、`plan.json`、`completion.json` 和前后图清单保存范围与输入凭据。
