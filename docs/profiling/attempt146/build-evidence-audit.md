# Attempt 146 构建证据独立核验

只读核验通过：315 项构建输入与当前文件一致，checks exit=0，日志 BUILD SUCCESSFUL。55 份保存 XML 共 2107 项测试，failure/error/skipped 均为0。未运行 Java、构建、查询或读取图。

| 模块 | 测试 | 本次 test | 本次 detekt |
|---|---:|---|---|
| core | 455 | executed | executed |
| cypher | 1236 | executed | FROM-CACHE |
| webgraph | 195 | executed | FROM-CACHE |
| explore | 221 | executed | FROM-CACHE |

两个 JMH 包任务和 webgraph:verifyJmhJarExcludesTests 均实际执行；没有声称存在 Explore 同名 exclusion 任务。GraphTaskEmptyChildrenTest 保存 XML 为1项成功；该单项源码内部遍历正常与 cancel(false) 两个分支，非2个 JUnit test，也没有 CPU 早退。

两个146冻结 JAR 以及对应145冻结 JAR的完整 SHA256 均独立读取匹配各自收据。独立读取 JAR 中全部 io/johnsonlee/**/*.class 并与145对照：**两包实际均有 GraphTask、GraphTaskContext、GraphTaskGroup、GraphTaskScheduler 四类内容不同，其余该命名空间类无差异**，详见 JSON 原列表。这个事实是读取后得到的，不是用预期结果过滤。额外三类逐字节比较：仅屏蔽 constant-pool SMAP 字符串、SourceDebugExtension、LineNumberTable 的精确字节范围后，剩余字节全部相同；因此其差异限定为上述调试数据，不能把它们记成原始 payload 相同。

当前已编译 main 类逐项匹配 JAR：webgraph 683、explore 958 个唯一类，均与 root receipt 一致。GraphTask.class 另记录两版 payload SHA256并确认 current=compiled；两包均不存在 GraphTaskEmptyChildrenTest 及其嵌套类路径。此测试排除核对仅针对新测试；webgraph完整排除任务通过另有日志依据。

- Webgraph：`120808180cebddac06522ece2eef37089db99fc08e2864090e91cfa85a66cee9`
- Explore：`5ed837ba61df069664fd132bad9d94db1bfa69aa1b8fb221e7b48737d075338f`

本报告不把 Kotlin module/resource/JAR ZIP metadata 称为全相等；上面的差异范围是全部 io/johnsonlee class payload，另有 current compiled-main 绑定。未将315项输入全称生产文件，其中含 build 与既有诊断源码。

这是构建身份与正确性证据，不是性能验收、v3完整输出审计或 CI 通过。脚本保留原输入、XML、任务行、全部差异名单与哈希，见 [JSON](build-evidence-audit.json) 和 [复算脚本](build-evidence-audit.py)。
