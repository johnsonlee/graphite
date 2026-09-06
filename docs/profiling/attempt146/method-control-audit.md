# Attempt146 Method 控制独立审计

33个真实 Method 组合全部完成，原控制进程exit0。逐份原JMH JSON和日志验证 `graphCount ∈ {4,17,36}` × 11场景（zero/early/middle/late/prefix/suffix/contains/regex/or/count/order）各一次，无缺失或重复；每项 `requestsSucceeded.score=1`、原始rawData=[[1.0]]，响应bytes为正。没有配对性能比较，不构成候选接受或CI通过。

原JMH参数为Java17.0.18、P4、8GiB、单线程/单fork/单次测量、无warmup。逐项核对实际JVM args含命令要求的四个真实完整持久图路径与manifest输出。64个输入文件、1,641,948,871 bytes的前后逐文件清单完全相同；四corpus分布及必要图文件完整。JAR前后身份收据与final-build一致，explore SHA为 `5ed837ba61df069664fd132bad9d94db1bfa69aa1b8fb221e7b48737d075338f`。本审计没有再读1.64GB图内容或整JAR做哈希。

manifest共33行，组合与JSON完全对应，含759个格式正确的expected签名项：每组graphCount个service加四个root。已核对service/corpus轮换、root corpus顺序及digest格式。`requestsSucceeded=1`表示整组action通过，**不是每组仅一个HTTP请求**；由源码推导，本控制有11×[(4+4)+(17+4)+(36+4)]=759个测量内请求helper调用，另有setup探路请求。这不是独立网络日志计数。

与145的既有审查可复用部分已验证：当前 `ExplorerMemoryBenchmark.kt` 源SHA逐字相同；两份冻结JAR中15个 `io/johnsonlee/graphite/cli/Method*.class` 相关类payload也逐字相同。没有重新执行Java或修改JAR。

验证边界保留如下（路径均为candidate内 `graphite-explore/src/jmh/kotlin/io/johnsonlee/graphite/cli/ExplorerMemoryBenchmark.kt`）：

- 399–433行从四个实际corpus加载fixture，以index%4重复注册4/17/36个service；并非相应数量的独立数据集。445–453行执行scoped及四个root请求。
- 787–799行只有整个action完成后才增加成功计数；结合原校验源码与成功记录，证明现有校验通过。
- 801–829行manifest在setup生成的是 **expected**，没有保存原始HTTP响应体；本审计不能从manifest独立重放actual response digest。
- 624–629行root ORDER会先对实际signature排序并take，再比摘要，因此不证明raw root行序。
- 638–641行会从已知services重建结果map，未知graph ID可能被过滤；不能宣称已排除所有额外ID。正常非ORDER结果也会规范化后比较，并非原始行序证明。

原始CPU、wall、resident指标仍保存在JSON，但没有baseline配对，不据此声称收益或回归。本文件也不修改既有 `control-and-gate-audit` 的本地快照；候选CI的发起及终态应单独记录。

复算：[method-control-audit.py](method-control-audit.py)；33组合、759签名项、15类payload哈希及输入证据：[method-control-audit.json](method-control-audit.json)。
