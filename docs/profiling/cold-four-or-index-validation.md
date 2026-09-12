# 纯四关键词 OR 冷查询的索引验证装箱证据

现有采样支持调查完整 mapped-view 验证中的逐元素泛型回调装箱，但尚未证明任何修改的收益。
这里仅重算已有证据，没有修改生产代码、测试、校验规则或性能 gate，也没有启动 JVM 或新测量。

## 样本与分母

输入是 `four-or/cpu-analysis/collapsed` 中 40 个 `or-four-broad-distinct-00..39` 查询窗口。
这是同一已有 CPU/alloc 录制中的 40 次冷索引查询，不是 40 个独立 JVM 录制。
每条栈出现 validator 时只计一次 inclusive 权重；装箱统计只按 leaf 归属，不能把不同 inclusive 方法相加。

| CPU 样本 | 数量 |
|---|---:|
| 全线程 | 13,830 |
| 应用查询线程 | 12,243 |
| 包含 `PersistentIndexViewValidator` 的应用栈 | 11,183 |

因此 **80.86% 是全部 CPU 样本的占比**，应用线程内部占比为 **91.34%**。
应用线程包括请求线程、graph worker 和 CallSite segment worker；同期编译等后台活动不算应用查询线程。

| 分配采样权重 | 字节 |
|---|---:|
| 全线程总量 | 49,517,467,648 |
| `Integer.valueOf` / `Long.valueOf` 全部 leaf | 46,663,204,864 |
| 上述装箱 leaf 位于 validator 栈内 | 46,656,651,264 |
| 上述装箱 leaf 位于 validator 栈外 | 6,553,600 |
| validator 内 `Integer.valueOf` leaf | 15,036,579,840 |
| validator 内 `Long.valueOf` leaf | 31,620,071,424 |

装箱 leaf 合计占全部采样分配权重 **94.24%**，不是说所有这些字节都来自 validator。
validator 的 Long 部分中，默认空回调栈占 1,143,996,416 字节，显式验证回调栈占 30,476,075,008 字节。
这些是记录的 NewTLAB / OutsideTLAB 加权采样字节，不是精确总分配、对象数或每次回调成本。
validator 内另有 `HeapByteBuffer.<init>` leaf 2,550,175,744 字节；它对应 scratch 分配，不能与回调装箱合并成同一假设。

## 装箱边界与调用次数

[MappedCallSiteStringIndexView.kt:465](../../graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndexView.kt#L465)
接受 `(Int) -> Unit`，474 行逐元素调用；
[486](../../graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndexView.kt#L486)
接受 `(Long) -> Unit`，496 行逐元素调用。这些泛型函数接口形成 primitive 到 boxed 参数的边界。
`Integer.reverseBytes` / `Long.reverseBytes` 本身使用原始类型，并不是这里的装箱来源。
实际分配仍取决于 JIT 内联、逃逸分析及整数缓存，不能把源码调用数当成分配对象数。

对一个成功完整验证的 sidecar，记 N 为 CallSite 数，S 为 string 数，Uₚ 为每属性唯一 string 数，P 为 trigram posting 数：

- Int 回调数为 `2ΣUₚ + 4N`：
  [389、394、399](../../graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndexView.kt#L389)
  分别遍历 property string IDs、posting ends 和四属性 node postings。
- Long 回调数为 `S + P`：
  [403](../../graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndexView.kt#L403)
  的 signature 段也逐元素调用默认空回调，405 行的 trigram 段调用显式验证回调。

仅从现有 fixture64 每个 sidecar 读取前 76 字节，共 4,864 字节，得到：

| 64 图头部合计 | 数量 |
|---|---:|
| N | 5,046,935 |
| S | 2,793,940 |
| ΣUₚ | 1,534,858 |
| P | 31,587,846 |
| Int 回调（64 views 各完整验证一次） | 23,257,456 |
| Long 回调（64 views 各完整验证一次） | 34,381,786 |

这是每个 view 成功完整验证一次的源码推导，不是 JFR 记录的实际调用频度，也不证明每个查询都打开了全部 views。
验证失败可提前退出；已打开的 view 可以复用。头部 SHA256 仅标识所读的 76 字节，不认证 sidecar 正文或 CRC。

## 必须保留的验证义务

- [load:273–338](../../graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndexView.kt#L273)：
  文件边界、magic/version、string/CallSite 数量、content identity、目录维度和期望总字节数。
- [validatePersistentIndex:377–410](../../graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndexView.kt#L377)：
  property string IDs 合法且严格递增；posting ends 严格递增、无越界且最终等于 N；
  node ID 在容量范围内；trigram postings 非降序且 string ID 合法；原 CRC 覆盖与校验值。
- [updateInt/Long/Bytes:446–462](../../graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndexView.kt#L446)
  及数组循环的 CRC 字节序和覆盖内容、工作计费数量及异常传播。
  [468、478、490、500](../../graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndexView.kt#L468)
  的分块取消检查与预算提交边界也必须保留，不能以减少回调为由跳过验证。
- [validatedPostingCursor:161–190](../../graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndexView.kt#L161)：
  被查询选中的 posting range 必须在输出任何节点（包括 LIMIT 1）之前验证完整 encounter order。
  CRC 正确不能代替该语义检查。无效 range 继续 fallback，预算拒绝和取消继续传播；
  不扩大全图 node-offset 读取，也不改变现有有界验证缓存的预算、碰撞及释放义务。

## 已尝试方向的边界

以下记录均来自 [优化尝试日志](../wrapped-case-insensitive-query-optimization-attempts.md)，不构成新的性能证据。

| Attempt | 已做内容及结果 | 此次不可混淆的边界 |
|---|---|---|
| 098 | prefilter property IDs 改 bulk ByteBuffer CRC；拒绝、回退 | 不是泛型回调装箱实验；不能把 CRC 批处理包装成新方向，也不能把旧结果泛化到所有验证成本 |
| 123 | 去掉 view load 时全量随机 node-offset 顺序读取；保留 | 不恢复全量随机读取 |
| 124 | selected posting 完整顺序检查后才能输出；保留语义要求 | 校验不可删除；当时未缓存形式也未满足全部 gate |
| 125 | 临时 rank 表并在 load 时验证全部 posting 顺序；拒绝、回退 | 不新增全图 rank/顺序扫描 |
| 128 | selected-range cache 改有预算的固定 primitive 数组；保留 | 已处理 boxed map 的保留成本；当前逐元素泛型回调是另一处边界 |

可独立验证的窄假设是减少已有完整验证的逐元素回调装箱，保持所有上述义务和执行时序。
这不是授权删除校验、改 CRC 策略、引入全图扫描或新缓存。

## 与旧 P95 的关系及复核方式

旧 34 查询的 `global-wide-wrapped-case-insensitive-distinct-dense` 在 cpu-3/4/5 中，
validator 的 CPU 和 allocation 样本都为 0。不能声称冷验证方向可以改善这些 warm 窗口的 P95，
也不能从零采样严格证明零开销。对旧目标的实际影响未知，必须以后用同协议真实数据验证。

附 [重算脚本](cold-four-or-index-validation/recompute.py)、[64 图头部记录](cold-four-or-index-validation/header-census.json)
和 [receipt](cold-four-or-index-validation/receipt.json)。脚本重算已有 aligned collapsed 结果，不重新解析 JFR，
不生成新测量。复核命令：

```sh
python3 docs/profiling/cold-four-or-index-validation/recompute.py \
  --manifest /private/tmp/pr113-attempt131-ascii.JqgmHw/fixture64/graphs.tsv \
  --cold-collapsed /private/tmp/graphite-main-profiling-n50joikp/four-or/cpu-analysis/collapsed \
  --old-profile-root /private/tmp/graphite-main-profiling-n50joikp \
  --output docs/profiling/cold-four-or-index-validation
```
