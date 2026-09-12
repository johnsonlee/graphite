# raw DISTINCT 的具名 IntPredicate：只读筛选

**结论：转发确实存在，独占成本仍未证实。** 没有找到完全相同的历史尝试；已完成的编译日志也不支持“这层转发已经消除”。因此不能仅以重复或已内联为由否定该假设，但现有证据不足以把它提升为有实质收益依据的候选，更没有10x依据。可以保留为一个低置信、可证伪的单一机制假设；本轮停止于审计，不启动141、不实施或测量。

先纠正两个数：隐藏 `IntPredicate.test(int)` 的运行时方法长度是 **61 bytes**；**57是其中invokestatic的BCI**。静态节点callback为 **790 bytes、15个参数（14个捕获值加nodeId）**，不是16参数。

## 冻结字节码与编译证据

独立Python读取冻结JAR（SHA `a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe`）的常量池、BootstrapMethods、Code和LocalVariableTable，确认：

- worker方法190 bytes，在BCI108创建IntPredicate；LambdaMetafactory的implementation handle直接指向精确790-byte节点callback，捕获描述符14项。
- callback槽0..13依次为inspected、abort、graph、accounting、selectedIdValues、projectedPropertyIndexes、seenValues、rows、targetSize、stringIds、predicates、predicatePropertyIndexes、exactMatchSets、matchStates，槽14为nodeId。
- 隐藏lambda class并不在冻结JAR中，也没有保存其运行时class转储。61 bytes由三份原XML方法元数据确认，BCI57的invokestatic、目标及15项参数由编译parse记录交叉核对；不能声称独立读取了完整隐藏class的每个opcode。

| 录制 | wrapper已发布编译 | wrapper→790-byte callback 的结果 | MappedNodeTypeIndex→test |
|---|---|---|---|
| profile-1 | C1 4211 | `callee is too large` | C1 4231/4232：`no static binding` |
| profile-2 | C1 4206/4211，C2 4221 | C1同上；C2 `hot method too big`，`direct_call bci=57` | C1 4225/4226：`no static binding` |
| profile-3 | C1 4244，C2 4247 | C1同上；C2 `hot method too big`，`direct_call bci=57` | C1 4262/4263：`no static binding` |

这些是对应已完成编译任务的实证，不能外推为每次调用的机器码成本。targeted窗口含callback的应用Java快照分别29/31、27/31、22/25，合计78/87；其下wrapper帧合计70个`JIT compiled`、8个`Interpreted`、0个`Inlined`。JFR的`JIT compiled`标签本身不区分C1/C2。

完整录制中wrapper作为叶帧只有0/1/1次，后两次都在targeted且为BCI57。下层wrapper帧通常只是挂起在调用点，不能将78个包含callback的样本算成wrapper独占成本或参数搬运成本。节点callback第一次C2编译完成在targeted开始后52.2225/32.6720/36.507542 ms，而窗口总长42.704375/37.748709/37.1725 ms；分别在窗口后、末段、接近末尾。这是本录制的编译完成时点，不是编译CPU或可省耗时。

## 历史边界

检索全部三份chronological attempts，匹配行和hash已保存。没有找到完全相同的“每worker具名IntPredicate直接承载原raw DISTINCT body”。该否定检索仅覆盖日志及当前源码，不能代表所有未记录改动。

- Attempt136（源日志4887行）只替换节点body内range/iterator traversal；没有去掉外部predicate对象。它的拒绝继续有效。
- Attempt140（5427行）将predicate属性索引List变IntArray；同一body内部访问变化，已拒绝。本假设必须保持List和`predicates.indices.any`。
- Attempt130（4120行）修改另一处raw-leading循环；不能复用该失败方向或其收益假设。
- Attempt133/138（4354/5160行）用postings投影selected或sparse候选；本假设保持raw、selected和fallback路径。
- Attempt139（5282行）inline的是持久索引validator回调；虽然都涉及调用形状，具体路径和转换不同，拒绝结果不重开。
- Attempt112（3449行）延迟图级scanner对象及捕获状态构造，已拒绝；它是图级初始化开销，不是逐节点IntPredicate→静态callback转发。
- Attempt006/063（194/1940行）涉及谓词/投影扫描融合，不是这一调用边界。

上述行号均针对 `docs/wrapped-case-insensitive-query-optimization-attempts.md`；可核查文件hash、完整匹配文本和各attempt标题在 `audit.json`。

## 若以后单独检验，必须保持的边界

当前 source：`MappedWebGraphBackedGraph.kt:481–631`，`NodeTypeIndex.kt:59–80`（均位于 `graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/`）。只是把同一worker的一份状态移入`test(int)`；它仍须由现有物理顺序的`forEachIdWhile`调用。

必须保持每worker状态隔离及原引用共享关系，尤其inspected轮询、accounting、rows/seenValues/stringIds私有，abort共享，以及原selected集合、exact集合、matchStates引用和transform/mode/expected隔离。取消轮询先于consume，计费每节点一次，flush和active-worker清理仍在原finally；selected tuple全值判断、null投影、去重、物理顺序及LIMIT短路不变。不能夹带移除Ref对象、复制匹配表、调整预算、缓存、调度或线程池。

具名对象不自动减少总字段访问或编译开销：原callback以局部变量读取捕获参数，新body可能改为重复getfield；Kotlin私有成员访问还可能生成额外accessor。interface调用和大body仍在，JIT profile、编译大小及热度也会改变。将来若要检验，应先证明实际字节码只改变这一调用/状态承载边界，并保持完整值/顺序/来源、取消/预算测试，再按既定真实数据规则证伪；本审计没有给出加速预测或启动授权。

复算：`python3 audit.py`。脚本只读classfile、原XML、原生JFR JSON和历史文本，不调用Java或父分析脚本。`audit.json`保留精确编译任务、样本索引、源码hash和范围限制。
