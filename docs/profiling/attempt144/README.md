# Attempt 144：图任务真正退出后才释放并发名额

这是142/143共享调度方向的继续修正。原142第一组、143第一组测得graph peak3、segment peak2，
原门槛要求2/2。源码中prepared ready pump的逻辑退休早于wrapper finally和GraphTask真实退出，
因此替代pump存在提前进入的窗口。这个窗口可从源码构造，尚未证明它就是原始录制峰值3的交错。

本次只增加每个任务组的实际并发名额：后台、REQUEST helper和显式inline统一计数，
函数结束、子任务排空、context恢复后才释放；prepared pump传入原workerCount。
其余组默认总数不受该新增限制，原STORAGE背景预算与caller参与方式保留。
submit不等待名额；满组的显式runInline在登记任务前失败，不阻塞调用方。
同组任务占满名额后同步等待同组新任务不是支持的使用方式；现有生产嵌套使用独立子组。

G是单个prepared任务组的图并发上限；多个请求的全局graph计数可能超过单请求G。
NCPU限制的是共享资源创建的全部live worker（含空闲worker），不是全部JVM或已有外部调用线程。
未新增线程池、改查询谓词、调整143分段粒度、改变索引格式或验收门槛。

父提交：f8e25ff67eab62b2dff2f2b5bcf9a7b7f5d421f6。保留143的过滤分段，以便本次仅检验准入修正。
起点main、真实64图、原34查询顺序及补充36查询均沿用前次固定输入。

## 验证与结果

四模块2105项测试通过（core454、cypher1235、webgraph195、explore221），四模块lint、两个JMH包构建及测试排除检查通过。
新4项测试覆盖函数返回后的子组排空、cancel(false)、REQUEST helper共同准入、inline满组无副作用及STORAGE默认兼容。
P4额外98项相关测试通过；真实64图的36查询完整正确性控制通过。
已有饱和测试原本要求一个G8查询占满15个root名额，失败发生在待测prepared查询提交之前；
现改为足够数量的独立阻塞查询，保留全部取消、结果和来源断言以及超时。先前失败日志及XML均保留。
第一次lint ReturnCount失败作等价条件整理后通过，也完整保留。

| 原34查询配对顺序 | main P95 ms |144 P95 ms|main CPU s|144 CPU s|144 graph/segment峰值|
|---|---:|---:|---:|---:|---:|
|1 C/B|56.366500|50.270833|1.616511|1.677603|2/2|
|2 B/C|45.109875|41.362917|1.562337|1.613527|2/2|
|3 C/B|51.830000|79.423625|1.491585|1.883004|2/2|

三组均满足原worker峰值条件。第三组P95增加53.24%、CPU增加26.24%；
原完整comparator已在这三组既有数据上执行，exit1，报告CPU超界及class-pair/targeted两组重复回退。
未重新录制数据或追加验收样本。P95是34个不同查询的目录分位数，不是单查询重复采样P95。
[完整原门槛结果](old34-pairs/global-wide-report.md)与[独立逐查询审计](old34-audit.md)。

**本次修复准入缺陷的证据成立，整个候选仍未通过性能验收。** 这是共享调度方向第三个失败候选，
保留在隔离实验历史，不放弃方向、不进入PR、不启动候选CI，10x仍未达到。
下一步单独关闭已发现的Method提交中途失败清理缺口，并沿实际回退路径诊断净成本。
没有证据证明失败仅因为分段力度不足，也没有证据证明整个共享调度方向错误。

[源码与协议审计](source-review.md)；[独立构建证据审计](build-evidence-audit.md)。
不可变命令、数据、源码、测试和哈希保存在 `/private/tmp/graphite-attempt144.n04dsqwu`。
Webgraph JAR SHA256：`07716beb51ba37d78a8a0a10dfbe3aac2add309c3f261c63287bdfdea855219a`。
Explore JAR SHA256：`1c6fb9e9ac28e4f1494089ad65c3c17b6556c8dc60e123092b0d30ffcf423891`。

