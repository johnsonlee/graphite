# Attempt 143：在共享 STORAGE 任务组中分段匹配候选字符串

这是142共享调度方向的第二个候选，**首组本地性能验收未通过，继续同方向归因，不进入PR或启动候选CI**。

142的CPU样本显示候选字符串匹配占目标查询计算样本36%–53%；后续阶段/队列诊断中，
匹配与有效队列为空、存在等待worker的状态重叠。143只补上图内任务划分：
Split consumer的anchor达到2048时，按至少1024个候选的粒度请求有限数量的有序片段，
复用共享STORAGE lane、原首段caller、已有取消和实际join协议，按原顺序合并结果。
小anchor、零segment预算、非Split consumer、P=1继续原循环。
未改变谓词、索引格式、缓存、OR计划、验证规则或worker池数量。

## 验证

- 四模块共2101项测试通过：core450、cypher1235、webgraph195、explore221；四detekt通过。
- 两个JMH包和Webgraph JMH测试排除检查通过；最终JAR与编译class逐项核对。
- 新增5项行为测试验证顺序、重复谓词计费、分段边界、预算失败及cancel(false)实际退出。
  另在P=1/2/3配置各验证该测试类；P=1的两项并发测试早退，不能视为并发取消覆盖。
- 真实64持久图分片的36查询控制通过完整值、顺序和来源校验。
- 首次检查的ComplexCondition失败原样保留；仅用takeIf拆分同一准入条件后复查通过。

## 未通过的第一组

| C/B顺序，原34查询 | main | candidate |
|---|---:|---:|
| 全目录P95（ms） | 43.523250 | 47.237166 |
| 进程CPU（s） | 1.475462 | 1.643484 |
| targeted DISTINCT（ms） | 22.061458 | 36.072500 |

P95增加8.53%，不满足每组严格进步；停止该快照的后续验收。
不是全部查询的P95都增加8.53%，也不是单条查询重复测量得到的P95。
没有开始第二/三组、v3性能组或候选CI，不能称优化落地或达到10x。

## 后续仍需区分的问题

142 probe中的targeted查询有57次在anchor建立前早退（-1为哨兵，不能当作数量），
仅7次建立anchor，最多3个、合计14个；这两类路径均不触发新分段分支。
它的回退不能直接算作STORAGE分段成本；新增guard、前序执行、JIT/堆状态和helper可见性变更的间接影响仍未排除。
Dense查询的64图anchor都在2614–4819，总223183个，才是新分段工作的直接目标。
独立审计还发现原始worker峰值门槛未满足：143图任务峰值3、segment峰值2，配置要求2/2。
142第一组已存在同一问题，先前结论遗漏了这个门槛，现明确纠正。两个独立峰值不能相加推断同时live线程数。
源码存在ready pump逻辑退休早于wrapper finally和实际任务退出的窗口，允许替代任务提前进入。
这是可达反例，尚未证明原始测量的峰值3由该交错引起；原TSV也不能定位到某一查询。
优先修复并验证这个独立调度问题，再用保留完整34查询前序的不可变JAR诊断延迟。
[完整逐查询与峰值审计](old34-audit.md)。完整comparator未运行，不能伪造其退出结果。

起点main：`4e328b0109e13c896b74004823fb049fcb19251a`；实验父提交：`81e7c5f93efff4f26054add88f3b3bea4b470591`。
真实fixture64含Android/Tika/Hive/Kotlin四个来源的64个类分片，并非64个独立应用；
manifest SHA256：`fe66cc84f6d8ee95c49b49ad500f921b304f0160c2ae094621683bb4db94ea6b`。
Java17、macOS、ActiveProcessorCount4、8GiB堆，原始JMH零warmup/单次迭代/单fork；CPU配置不是OS亲和性。

Webgraph JAR SHA256：`fd54be8e5d674158b31b9991ca8c1a0f8272fd613b8102c577c6aaa3a5658f08`。
Explore JAR SHA256：`824160188c808ab755bc499d48b6598e514c985fde51e2b2dc4d331e20511bdc`。
完整命令、原始结果、源码和测试凭据：`/private/tmp/graphite-attempt143.zo00pri0`。
