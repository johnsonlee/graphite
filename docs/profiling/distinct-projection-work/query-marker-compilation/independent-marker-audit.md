# 查询 marker 与录制配置的独立核验

核验通过，未发现原 marker 验证报告的数值或绑定错误。独立脚本直接读取四份原生 `jfr print` JSON、原 catalog、TSV、14字段 oracle、实际 ActiveSetting 和输入凭据，没有运行 `verify-markers.py`，没有启动 Java、构建或新录制。

四份录制的136个 marker 均满足：严格 ordinal 1..34，query全文、排序JSON参数和catalog/TSV query ID逐项一致；原34中三条参数化查询的String `term`值也完整核对。全部success、exceptionClass为空、markerFailuresBefore=0；显式Java线程ID/名称与内建eventThread一致，同一录制只有一个pressure worker，窗口持续时间为正且互不重叠。136条完整14字段 oracle通过，全部非latency TSV字段与prior base完全相同。独立重算的136个起止纳秒与原报告逐项一致。

| 录制 | marker | ExecutionSample | Compilation | Deoptimization | CompilationFailure | 最大TSV窗口外差值 |
|---|---:|---:|---:|---:|---:|---:|
| control | 34 | 0 | 0 | 0 | 0 | 10.137584 ms |
| profile-1 | 34 | 2,121 | 3,122 | 94 | 3 | 10.202833 ms |
| profile-2 | 34 | 2,123 | 3,105 | 80 | 3 | 10.445125 ms |
| profile-3 | 34 | 2,095 | 3,170 | 99 | 3 | 10.441291 ms |

所有marker duration都小于对应TSV latency，无需超时容差；四个最大正差值均来自首条查询。最小差值分别44.833、48.416、44.084、42.958 μs。该差值保留为 `untracedTsvNanos`，包含窗口外工作，不能算成窗口内executor成本。CompilationFailure数量被保留，不与查询失败混淆；其原因及事件归属不属于本次审计。

每份录制的340个ActiveSetting均通过该JFR自己的event-types表解析数字ID，而非依赖元数据的默认值。profile实际启用ExecutionSample且period=2 ms，Compilation threshold=0 ms，Deoptimization stackTrace=true；marker启用、threshold=0 ns、stackTrace=false。control的相应原生事件disabled。所需设置在第一查询前生效，未见矛盾设置；DataLoss实际启用，四份导出都没有DataLoss事件。

原生JVMInformation确认四个不同进程、同一Java17.0.18+0 / bsd-aarch64构建、指定4 CPU与8 GiB、零额外预热、GC profiler和预定JFR/LogCompilation参数；未加运行时agent或强制编译选项。三份XML与对应JFR的PID/VM参数一致，XML classpath绑定诊断JAR。JMH自动compiler-blackhole/临时CompileCommandFile另列。导出没有OSInformation、CPUInformation或hostname，因此只确认这些已记录的平台/进程/线程身份，不宣称独立证明了物理主机身份。

诊断JAR当前SHA仍为 `2728888d2820fc9721149302612c94b1001bfd38ee5a8c69244fed59fc9b6ef9`，冻结原JAR仍为 `a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe`；四轮前后收据及既有overlay交接证据hash一致。control/profile的四份完整图清单在64图、1,088文件的路径/大小/SHA及manifest顺序上相等。本次未再次读取大型语料做第二遍内容hash。四个进程均exit0且串行结束；JFR原文件、原生JSON、event-types、导出命令/源码凭据hash逐份匹配。

本审计验证的是已有原生导出的内容与来源绑定，没有进行第二次二进制JFR解码，只覆盖导出命令列明的事件类型。无记录DataLoss不等于无采样盲区。marker准备/提交、JFR与编译日志会扰动执行；这些数据不证明性能收益、原候选失败原因或事件与查询之间的因果关系。

复核入口：`python3 independent-marker-audit.py`；完整纳秒窗口、实际设置历史、身份及hash见 `independent-marker-audit.json`。
