# 查询性能优化空间评估（2026-11，main `58215b9`）

本报告评估当前最新代码（`main` 头 `58215b9`，PR #211 合并后）的查询性能还有多大优化空间。
评估对象是生产路径：Rust 后端 `graphite serve`（`backend/explore` + `backend/cypher` + `backend/storage`），
负载是仓库唯一的性能验收负载 real64（Android-all、Tika、Hive、Kotlin compiler 四个真实 JAR 各切 16 片，
共 64 张持久化图：145,294 个类，19,438,099 个节点，5,051,914 个 CallSite，21,081,153 条边）。

结论先行：

1. **目录内 73 个用例里，67 个的隔离 p50 已在 0.5–2 ms，另有 2 个在 6–10 ms**，其中约 0.4–0.6 ms 是 HTTP 往返与 JSON 的固定底价。
   这一层再优化的绝对收益不超过 1 ms/请求，不值得作为主攻方向。
2. **真正的引擎尾部只剩 4 个形状，隔离 p50 都是 92–96 ms**：`schema-relationship-histogram`、`shape-count-traversal`
   （全边扫描）、`shape-group-count`、`shape-with-aggregate`（64 图分组计数合并）。栈采样显示四者各自由一个热点主导，
   都有 5–50 倍的确定性收益空间（见 §4）。
3. **压力态（4 并发）下最大的损失不是引擎，而是 rayon 全局线程池的队头阻塞**：一个使用 `par_iter` 的 95 ms 查询会让
   任何同时进入 rayon 并行段的廉价查询等满它的剩余时间。`shape-equality` 隔离 0.93 ms、压力 p50 94.6 ms；
   `targeted/global-wide-four-properties` 1.06 → 82 ms；`shape-traverse-filtered` 1.38 → 55 ms；
   `dense/…-wrapped-case-insensitive-distinct` p95 259 ms。本地定向实验复现了该机制（§3.3）。修掉它能把 73 个用例中
   十余个用例的压力 p50/p95 拉回隔离值，是**单项收益最大的改动**。
4. **目录外的生产形状才是生产 P95（README 记录 ~15 s）的来源，而且其中一个形状已经明显退化**：全属性搜索
   `any(k IN keys(n) WHERE toString(n[k]) CONTAINS …)` 的 `count(*)` 在 64 图上现在要 **47 s**（仓库自己的历史记录是 1.30 s），
   `LIMIT 25` 需 180 ms、零命中 335 ms（首次 1.46 s）。按标签拆解，45 s 全部来自 Field/Parameter/Return 三类节点：
   自声明类型（#174）加入 `keys(n)` 后，这三类节点的全属性谓词落回通用求值器逐节点解释（3.66M 节点 × 12 µs）。
   数值等值 `n.value = 105873` 要读完所有整数常量记录（24–27 ms）。这一族的根因是字符串索引只覆盖 CallSite 的四个属性、
   声明类型文本没有任何列；补上"保存器写出全属性（含声明类型文本）的列与三元组索引"后有 10–100 倍空间，
   退化的那一行有 100 倍以上，是**对生产环境最有价值、也最紧急的一项**。
5. **首次使用成本仍然很高**：一个属性的有序读取证明（`validated_ordered_call_site`）首次需 220–570 ms，
   `collect` 的记录证书 389 ms；它们按图×属性缓存，但每次进程重启都要重付，且全部记在第一个碰到它的请求上。
   由保存器把证明写成 sidecar（与现有 `graph.declared-presence` 同一机制）或就绪后空闲预热都能消掉这部分。
6. **专用路径之外是一道悬崖**：与目录用例只差一个词的查询（`WHERE size(c) > 40`、第二个排序键、`min/max`、
   `count(DISTINCT)`）慢 3–4 个数量级（0.9 ms → 15.1 s，1.3 ms → 4.4 s，0.5 ms → 3.7 s），因为它们落回每行
   `IndexMap` + 解释执行的通用管线，约 3 µs/行。这不是某个形状的问题，而是通用管线每行常数开销的问题；
   它决定了生产上任何未被认领形状的延迟，是中期最值得投入的结构性改动。

下文给出数据、方法、每一项的机制分析、预期收益、代价与约束。原始样本与脚本见
[`docs/query-performance-headroom-2026-11/`](query-performance-headroom-2026-11/)。

## 1. 评估范围、数据来源与方法

### 1.1 代码与数据

| 项 | 值 |
|---|---|
| 代码 | `main` `58215b9`（PR #211 合并后的最新提交） |
| 托管基准 | PR #211 的 `benchmark-regression-gate` 运行 [42912084315](https://github.com/johnsonlee/graphite/actions/runs/42912084315)，base `907bc9a` / head `2275a50`（head 与 `58215b9` 的产品代码一致），完整 `report.json` 从 artifact 13809622988 读取 |
| 本地语料 | 用本次代码的 `Fixture64GraphPreparation` 从四个固定 JAR 重新构建的 64 张图（与 CI 同一程序、同一 JAR 指纹），5.0 GB |
| 本地环境 | 4 vCPU，15 GiB，Linux 6.18，`cargo build --release`（评测用）与 `--release` + line tables（采样用，同一源码） |
| 协议 | 复刻 `benchmark/src/native.rs` 的 73 用例目录与采样规则：首次使用 1 次、预热 2 次、压力 20 次×4 工作线程按轮交错、隔离 40 次×1 工作线程；延迟止于完整读取 HTTP 响应体；按用例取最近秩 p50/p95，不跨用例合并 |

按 `CONVENTIONS.md` 的规定，**本地测量只是诊断证据，不构成性能验收**；本报告中每个"预期收益"都是待验证的假设，
验收仍需在 real64 多图负载上按既定协议做 base/candidate 配对测量。本地数据的用途是把托管运行的现象拆解到代码路径。

### 1.2 托管运行与本地运行的一致性

托管运行器与本地机器都是 4 vCPU，两边的形状高度一致（表 2），托管值普遍偏慢 1.5–3 倍（运行器噪声与更慢的存储），
四个重形状两边都在 100–200 ms 量级，三个"压力态被放大"的廉价用例两边都被放大到 55–110 ms。这给了本地拆解托管现象的信心。

另外发现一个与评估直接相关的 CI 问题：PR #207、#210、#211 的基准评论都写着 "No complete report was produced"，
但 artifact 里 `summary.md`、`report.json` 都完整存在（本报告的托管数据正来自其中）。PR #199 当时已修正过一次同样的
"artifact 下载布局与发布器路径不一致"问题，现在仍复发。它不影响门禁判定，但让每个 PR 的时序表从评论里消失了，
建议先修（`.github/scripts/benchmark/comment.ts:92-94`）。

## 2. 现状基线：73 个用例现在跑多快

表 2 把托管 head（`2275a50`）与本地 `58215b9` 放在一起。"压力"是 4 并发下 20 次的最近秩分位；"隔离"是单并发 40 次。
托管运行只对压力 p50 < 100 ms 的用例做隔离阶段，所以四个重形状与 `shape-equality` 没有托管隔离值。

表 2. 按本地压力 p50 降序的前 12 个用例（完整 73 行见附录 A）

| 用例 | 托管 压力 p50 / p95 | 本地 压力 p50 / p95 | 托管 隔离 p50 | 本地 隔离 p50 / p95 | 本地首次使用 |
|---|---:|---:|---:|---:|---:|
| shape-with-aggregate | 191.0 / 208.4 | 176.0 / 211.5 | – | 94.8 / 121.9 | 91.9 |
| shape-group-count | 182.2 / 250.2 | 142.4 / 205.1 | – | 92.4 / 106.8 | 574.1 |
| shape-count-traversal | 153.1 / 167.9 | 133.2 / 157.2 | – | 95.4 / 110.7 | 103.1 |
| schema-relationship-histogram | 143.6 / 310.0 | 125.2 / 253.4 | – | 95.9 / 116.4 | 105.9 |
| shape-equality | 109.0 / 122.5 | 94.6 / 114.1 | – | **0.93 / 1.13** | 2.0 |
| targeted/global-wide-four-properties | 60.5 / 103.8 | 82.0 / 110.5 | 0.63 | **1.06 / 1.36** | 2.5 |
| shape-traverse-filtered | 69.1 / 89.6 | 55.0 / 87.4 | 0.93 | **1.38 / 1.69** | 1.3 |
| shape-distinct | 18.1 / 38.4 | 15.2 / 25.6 | 6.9 | 9.4 / 11.4 | 10.8 |
| dense/global-wide-wrapped-case-insensitive-distinct | 10.2 / 19.3 | 11.1 / 259.4 | 4.8 | 6.0 / 9.0 | 5.9 |
| shape-order-by | 3.5 / 11.3 | 4.9 / 14.6 | 0.69 | 1.30 / 1.61 | 218.7 |
| dense/global-wide-four-properties | 5.1 / 13.6 | 4.1 / 7.0 | 1.39 | 1.97 / 2.38 | 3.4 |
| shape-skip | 4.8 / 7.8 | 4.0 / 10.5 | 1.39 | 1.77 / 2.46 | 2.1 |

按隔离 p50 分层，73 个用例现在是这样的分布（本地）：

| 层 | 用例数 | 隔离 p50 范围 | 说明 |
|---|---:|---|---|
| 固定底价层 | 46 | 0.5–1.2 ms | 零命中、靶向命中、单图即满 LIMIT 的扫描、`count(*)` 走类型索引；`RETURN 1` 的 HTTP 往返本身 0.6 ms |
| 单图工作层 | 21 | 1.2–2.0 ms | 稠密词在第一张图里扫字典/记录直到 200 行；`labels(n)`、`keys(n)` 直方图从类型索引回答 |
| 中等 | 2 | 6–10 ms | `shape-distinct`（9.4 ms）、稠密 `wrapped-case-insensitive-distinct`（6.0 ms）：跨 64 图合并的有界 DISTINCT |
| 引擎尾部 | 4 | 92–96 ms | 两个全边扫描聚合、两个 64 图分组计数 |

对照 2026-11-05 PR #199 当时的托管报告，`shape-collect` 5,714 → 1.6 ms、`shape-order-by` 6,742 → 3.5 ms、
`shape-group-count` 810 → 182 ms、`shape-distinct` 895 → 18 ms、`shape-with-aggregate` 674 → 191 ms：
Attempt 157–162 与 312–314 的工作已经把秒级尾部全部消掉。**剩下的尾部是 100 ms 量级，接下来每一步的绝对收益都会比之前小一个数量级。**

### 2.1 重复性与生产混合负载

第 2 轮本地压力运行（跳过首次使用阶段）与第 1 轮一致：四个重形状隔离 p50 92.2–96.5 ms 对 92.4–95.9 ms，
`shape-equality` 压力 p50 89.7 对 94.6 ms，`targeted/global-wide-four-properties` 64.2 对 82.0 ms，
其余用例隔离 p50 的差异都在 0.3 ms 以内（完整两轮见附录 A）。

生产日志形状的混合负载（`backend/bench/backtest.py`，170 个从语料采样的查询，单客户端，另一个种子预热）：

| 混合 | P50 | P95 | max | 成功 |
|---|---:|---:|---:|---:|
| v2（50 类对 `RETURN n`、40 `value CONTAINS`、30 四属性 OR、…） | 0.9 ms | 4.2 ms | 8.7 ms | 170/170 |
| v3（v2 的三分之一改写为 `=~` 模式） | 1.0 ms | 3.1 ms | 5.4 ms | 170/170 |

与 `rust-explorer-fixture64-p95.md` 记录的 0.9 / 3.8 ms（v2）、1.0 / 3.4 ms（v3）相同：
**这一族生产最常见的窄查询已经没有可见的退化，也几乎没有剩余空间。**

## 3. 时间去哪了

### 3.1 固定底价：HTTP 与 JSON

`RETURN 1` 本地 0.6 ms（托管约 1 ms）；零命中的 64 图查询 0.5–0.7 ms，与 `RETURN 1` 几乎相同。
对 46 个用例来说，引擎已经不是主要成本。路径上的固定开销是 axum/hyper 一次 HTTP/1.1 往返、`spawn_blocking` 一次
线程切换（历史测量 0.1–0.15 ms）、`serde_json` 一次紧凑序列化（50 KB 约 0.1 ms）。这里理论上还能省 0.1–0.3 ms
（例如在 tokio 工作线程上直接执行已知廉价的查询、复用响应缓冲），但绝对值太小，而且 `spawn_blocking` 是并发守卫的
设计前提，不建议动。

### 3.2 引擎尾部：四个重形状的栈采样

用带行号表的 release 二进制起同一服务，对每个形状循环请求并用 gdb 采样 30 次（只统计忙线程）。
聚合结果在 [`profiles/`](query-performance-headroom-2026-11/profiles/)。

**`schema-relationship-histogram` 与 `shape-count-traversal`（各 ~95 ms）**：65/65 与 34/35 个忙样本都在
`partition::sweep_edges`——`PartitionPlan` 的 `Shape::Hop` 对每张图的 CSR 做一次完整边扫描，按
(源类型, 标签, 目标类型) 计数并记录最早的边。21M 条边 ÷ 4 核 ≈ 95 ms，每条边约 18 ns，已经接近这个扫描本身的极限。
另外 7/65 个样本在 `partition::ascending`：每个请求都重新遍历每个类型的 id 列表检查是否升序，而 `Graph` 在加载时
已经算好了 `ids_by_tag_sorted`。这两个形状每次请求都从头扫 21M 条边，**而图是不可变的，扫描结果（每图一张
16×256×16 的槽表）与请求无关**。

**`shape-group-count` 与 `shape-with-aggregate`（各 ~92–95 ms）**：22/22 与 21/21 个忙样本在
`fastpath::bounded_groups::bounded_call_site_groups` 的 `HashMap<&str, Group>::entry`：
其中约 1/3 在 `hashbrown::reserve_rehash`（map 没有容量提示，边插入边扩容）、约 1/3 在 SipHash
（`std::hash::random::RandomState`）、其余是键比较（`memcmp`）与拷贝。64 图的 `callee_class` 去重值合并后约
数万组，但合并前每图各自的组数之和要大得多，这一路径是**单线程**的（§3.3 的实验也证实它不占用 rayon 池）。
有序读取证明本身已按图×属性缓存（`ordered_call_site_valid` 的 `OnceLock`），不在稳态成本里。

**`shape-distinct`（9.4 ms）**：13/14 个忙样本在为 200 个胜出值重建跨图 provenance：对每个值在每张图的字典上
`index_of` 二分（`java_cmp` 走 UTF-16 逐码元比较），200 × 64 = 12,800 次二分。

### 3.3 并发态的放大：rayon 全局池的队头阻塞

表 2 里 `shape-equality`、`targeted/global-wide-four-properties`、`shape-traverse-filtered` 隔离都在 1 ms 上下，
压力态 p50 却是 55–95 ms，并且托管与本地一致。为了确认机制，做了定向实验（`scripts/interfere.py`）：
廉价查询单独测 30 次，再在另一个线程持续循环一个重查询时测 30 次。

| 廉价查询 | 重查询（1 个后台线程循环） | 廉价查询单独 p50 / p95 | 重查询下 p50 / p95 | 重查询单独 p50 |
|---|---|---:|---:|---:|
| `shape-equality`（`callee_name = 'valueOf'`） | `schema-relationship-histogram` | 0.55 / 1.23 | 0.81 / **92.4** | 95.5 |
| `shape-inequality`（`callee_name <> 'valueOf'`） | `schema-relationship-histogram` | 0.87 / 1.18 | 0.82 / 4.70 | 92.5 |
| `shape-equality` | `shape-group-count` | 0.54 / 0.98 | 0.83 / 1.19 | 93.7 |

机制：`sweep_edges` 走 `ex.sources.par_iter()`，64 个每图任务进入 rayon 全局池的工作线程本地队列；
`shape-equality` 的 `valueOf` 是稠密词，走记录扫描 `sweep_call_sites`，而它在 `ids.len() ≥ 16,384` 时用
`par_chunks` 把块测试交给同一个 rayon 池（`scan.rs:1484`）。从 tokio 阻塞线程注入的作业要等某个 rayon 工作线程的
本地队列清空才会被取走，也就是要等重查询基本跑完——廉价查询的延迟因此恰好等于重查询的剩余时长。
`shape-inequality` 不走并行段，只受 CPU 争用影响（p95 4.7 ms）；`shape-group-count` 是单线程路径，所以不制造阻塞。
压力协议里 4 个工作线程按轮交错，每轮都有两个边扫描形状各占满池 95 ms，于是紧随其后、带并行段的用例每轮被放大一次：
这解释了 `shape-equality` 压力 p50（不是 p95）就有 94.6 ms，以及 `dense/…-distinct` 压力 p95 259 ms、
`broad-all-64/…` p95 98 ms、`targeted/global-wide-class-pair` p95 74.6 ms 这类"隔离很快、压力尾巴很长"的行。

这是服务级的问题：生产环境里任何一个全图并行聚合都会把同一时刻进入并行段的交互式查询拖到它的时长。
`CLAUDE.md` 的"用户批准的查询尾部协议（2026-10-13）"把廉价用例的压力 p95 当作"碰撞上界"只做 2 倍/250 ms 的
健全性检查，正是因为观察到了这种碰撞；但它的根因是可以修的，修掉后碰撞上界会自然收紧。

## 4. 优化空间：按收益排序

每项给出：证据、机制、预期收益（先 ms 后倍数，按 `CLAUDE.md` 的报告规则）、改动面、对 CPU/RSS/正确性约束的影响。
"预期"均为本地诊断推断，需按 real64 协议验收。

### 4.1 消除 rayon 全局池的队头阻塞（压力 p50/p95，覆盖十余个用例）

- **证据**：§3.3。
- **机制与做法**（任选其一或组合）：
  1. 把全图扫描类并行（`partition::rows` 的 `Shape::Hop`、`TypeStream::Column` 的 `par_chunks`、`sweep_call_sites`）
     切成细粒度任务（例如每 65,536 个节点/边一个任务），让注入作业能在任务间隙被取走；rayon 的 `par_iter` 对 64 个
     源默认按线程数均分成大块，细分需要显式 `with_min_len(1)` 或改用按块的 `par_chunks`。
  2. 给"重"的全图聚合一个独立的小线程池（例如 `cores - 1` 个线程的 `rayon::ThreadPool`），交互式扫描留在全局池；
     或者反过来，为并发守卫的 4 个许可各配一个固定池。
  3. 在扫描循环里插入 `rayon::yield_now()` 风格的让渡点，让排队的作业先跑。
- **预期收益**：`shape-equality` 压力 p50 94.6 → ~1 ms，`targeted/global-wide-four-properties` 82 → ~1 ms，
  `shape-traverse-filtered` 55 → ~1.5 ms；十余个用例的压力 p95 回到隔离值的 2 倍以内。重形状自身延迟基本不变
  （方案 2 会让它们慢 ~25%）。
- **约束**：不改变任何结果；CPU 总量不变；RSS 不变。需要在 4 并发压力协议下验收，隔离态看不到这项收益。
- **改动面**：`backend/cypher/src/engine/partition.rs`、`scan.rs`，可能加一个池的构造；中等。

### 4.2 边扫描聚合：缓存每图的边槽表（两个用例各 95 → <2 ms）

- **证据**：§3.2，`sweep_edges` 占满样本；图不可变。
- **做法**：`sweep_edges` 的输出只依赖 (图, 方向, 源类型集合, 目标类型集合, 关系类型集合)；默认（无标签、无类型）
  的那一份——也就是 `schema-relationship-histogram`、`shape-count-traversal` 以及任何 `MATCH (a)-[r]->(b)` 聚合
  共用的那一份——可以按图缓存在 `Graph` 上（`OnceLock`，每图 65,536 个 i64 + u64 槽，几乎全部为零，实际只占用
  被命中的槽），或由保存器预先写成 sidecar（与 `graph.declared-keys` 同一绑定机制：绑定 `graph.nodedata` 布局摘要
  与边文件摘要）。带标签/类型的变体可以从默认表投影出来（标签字节 → 关系类型是逐字节判定的；类型集合是槽索引的
  子集），不需要重扫。
- **预期收益**：两个用例 95 → 1–2 ms（第一次仍要扫一遍，或改由加载/保存承担）；同时消掉 §4.1 里最大的两个阻塞源。
  `ascending` 每请求重查改读 `ids_by_tag_sorted`，另省 ~10%。
- **约束**：首次成本必须明确归属（按 `CLAUDE.md`"延迟工作不是免费的"）：就绪后空闲预热 64 图约 380 ms CPU，
  或保存器写 sidecar 则加载几乎不付。RSS 增量每图 < 1 MB。
- **改动面**：`partition.rs` 与 `storage/graph.rs`（缓存）；若做 sidecar，还涉及 Kotlin `GraphStore` 保存器；中等。

### 4.3 分组计数合并：更便宜的哈希与并行（两个用例各 ~93 → 20–35 ms）

- **证据**：§3.2，全部样本在 `HashMap<&str, Group>` 插入，1/3 是扩容，1/3 是 SipHash。
- **做法**：
  1. 先用各源的组头数之和给 map 预留容量（`property_values` 的长度在证明通过后已知），消掉 `reserve_rehash`；
  2. 换用 `FxHash`/`ahash`（图数据不是攻击面，且 map 只活在一个请求内）；
  3. 每源先在自己的线程里扫描组头（`par_iter` 但遵守 §4.1 的粒度），再做一次 64 路合并；或者利用各图字典有序这一
     已证明事实，用 k 路归并按字符串顺序合并，完全不哈希；
  4. `grouped_with::try_execute` 与 `bounded_call_site_groups` 共享同一合并，一次改动覆盖两个用例。
- **预期收益**：单线程 93 → 35 ms（去扩容 + 换哈希约 2.5 倍），并行后 → 15–20 ms。
- **约束**：首次出现顺序、并列顺序、provenance 不变（现有测试已覆盖）；CPU 总量略降；RSS 不变。
- **改动面**：`fastpath/bounded_groups.rs`；小到中等。

### 4.4 首次使用成本：证明与列的构建（每次进程重启后 0.2–1.5 s/形状）

- **证据**：本地首次使用列：`shape-group-count` 574 ms（`callee_class` 的有序读取证明）、`shape-order-by-desc`
  537 ms（`callee_name`）、`shape-collect` 389 ms（CallSite 记录证书）、`shape-order-by` 219 ms（`caller_class`）、
  `value-contains-node` 388 ms（`StringConstant.value` 列与三元组索引）、全属性搜索零命中 1,460 ms。
  历史记录（Attempt 157/158）在托管运行上看到同量级。
- **机制**：读取器不信任文件，第一次用到时逐条验证索引覆盖完整节点域（`validated_ordered_call_site` 走完每张图
  的全部 CallSite 记录），非 CallSite 属性的列在第一次用到时从记录建出来；都按 `Graph` 实例缓存。
- **做法**：
  1. 保存器写证明：索引由同一次保存从同一节点布局写出，它可以把"索引覆盖完整域"绑定到 `graph.nodedata` 的布局摘要
     （`graphite.nodeLayout.sha256` 已有）与索引文件摘要，读取器验证摘要而不是逐条验证——这正是
     `graph.declared-presence` 已采用的方式；
  2. 保存器写非 CallSite 属性的字符串列与三元组索引（见 §4.5），加载时映射而不是首次构建；
  3. 退一步的办法是就绪后在空闲时预热（像 C4 预热那样让渡流量），把成本从第一个请求挪到空闲 CPU。
- **预期收益**：首次使用 200–1,500 ms → 与稳态相同；对 p50/p95 的稳态值没有影响，但对可用性（重启后前几秒）
  和按首次请求计费的客户端有直接影响。
- **约束**：`CLAUDE.md` 关于 sidecar 的要求：绑定每个答案所依赖的全部字节；格式版本不能改（基准门禁用 base 代码读
  candidate 的图）。改动涉及 Kotlin 保存器与 Rust 读取器两侧。

### 4.5 目录外的生产形状：覆盖全部字符串属性的索引（生产 P95 的主体）

- **证据**（本地，64 图，单客户端，首次 / 预热中位）：

表 4.5. 目录外探针（本地 64 图，单客户端；首次 = 进程就绪后第一次，预热 = 之后 5 次的中位，慢于 3 s 的只重复 1–2 次）

| 探针 | 查询 | 首次 ms | 预热中位 ms | 响应字节 |
|---|---|---:|---:|---:|
| http-floor | `RETURN 1`（HTTP 底价） | 23.2 | 0.6 | 127 |
| value-contains-node | `n.value CONTAINS 'Spooler' RETURN n LIMIT 25` | 388.2 | 1.5 | 2,320 |
| value-contains-http | `n.value CONTAINS 'http' … LIMIT 25` | 1.1 | 1.1 | 18,392 |
| value-contains-absent | `n.value CONTAINS` 零命中 | 5.6 | 0.7 | 92 |
| value-contains-count | `n.value CONTAINS 'http' RETURN count(*)` | 7.4 | 5.4 | 1,461 |
| class-pair-node | 类对 `CONTAINS … RETURN n LIMIT 25`（生产最常见） | 144.7 | 0.8 | 10,266 |
| all-props-limit | 全属性搜索 `LIMIT 25` | 202.0 | 179.8 | 8,255 |
| all-props-absent | 全属性搜索零命中 | 1,460.5 | 334.7 | 92 |
| all-props-count | 全属性搜索 `count(*)` | 46,954.7 | 45,075.8 | 434 |
| numeric-eq-limit | `n.value = 105873 … LIMIT 25` | 27.4 | 26.4 | 92 |
| numeric-eq-count | `n.value = 105873 RETURN count(*)` | 23.0 | 23.9 | 141 |
| qualifiedId-contains | `n.qualifiedId CONTAINS '1234'` | 2.5 | 2.3 | 9,153 |
| hop-callee-contains | `(c)-[:DATAFLOW]->(n) WHERE n.callee_class CONTAINS … RETURN c,n LIMIT 25` | 11.7 | 10.7 | 14,102 |
| hop-callee-count | 同上 `count(*)` | 22.5 | 21.8 | 415 |
| string-seed-paths | `(s:StringConstant)-[*1..3]-(n) WHERE s.value CONTAINS …` | 1.2 | 0.9 | 9,060 |
| explorer-expand | Explorer 节点展开 `{graphId, id}-[r]-(m)` | 0.6 | 0.6 | 861 |
| method-signature-contains | `(m:Method) WHERE m.signature CONTAINS 'parse' LIMIT 25` | 3.0 | 2.6 | 37,626 |
| method-count | `(m:Method) RETURN count(*)` | 0.6 | 0.6 | 1,522 |
| local-type-count | `(n:LocalVariable) WHERE n.type CONTAINS 'String' RETURN count(*)`（10.07M 节点） | 225.5 | 202.6 | 1,521 |
| filtered-group-count | 带谓词的分组计数 `WHERE callee_name CONTAINS … RETURN callee_class, count(*)` | 8.8 | 6.7 | 9,023 |
| eq-count | `callee_class = 'java.lang.String' RETURN count(*)` | 26.1 | 24.8 | 1,520 |
| typed-hop-count | `(a:CallSite)-[r]->(b) RETURN count(*)` | 23.1 | 21.2 | 1,522 |
| dataflow-count | `(a)-[r:DATAFLOW]->(b) RETURN count(*)` | 95.7 | 98.4 | 1,523 |
| caller-name-group | `caller_name, count(*) ORDER BY c DESC LIMIT 20`（首次含 `caller_name` 证明） | 428.9 | 79.2 | 23,309 |
| group-all-rows | 分组计数 `LIMIT 65536`（服务器截到 1000 行） | 148.3 | 149.6 | 514,558 |
| group-no-order | 分组计数不排序 | 123.5 | 125.3 | 351,201 |
| distinct-5000 | `DISTINCT callee_class LIMIT 5000` | 33.6 | 32.6 | 343,998 |
| label-histogram-with | `WITH labels(n) AS l, count(*)` 直方图 | 1.4 | 1.0 | 17,422 |
| two-hop-count | `(a:CallSite)-[r]->(b)-[s]->(c) RETURN count(*)`（通用匹配器） | 6,611.3 | 6,847.6 | 1,522 |
| var-length-count | `(a:StringConstant)-[*1..2]->(b) RETURN count(*)` | 838.6 | 822.3 | 1,522 |
| order-by-line | `ORDER BY n.line DESC LIMIT 200`（非索引键） | 4,683.8 | 4,633.8 | 20,559 |
| order-by-two-keys | 双键 `ORDER BY caller_class, callee_name LIMIT 200` | 4,488.9 | 4,448.3 | 29,849 |
| with-filter-size | `WITH … WHERE size(c) > 40 … LIMIT 200` | 15,807.9 | 15,126.5 | 21,397 |
| collect-distinct | `collect(DISTINCT callee_name)` 后 `size()` | 1,148.8 | 1,169.2 | 1,527 |
| count-distinct | `count(DISTINCT callee_class)` | 757.4 | 790.5 | 1,565 |
| min-max-line | `min(n.line), max(n.line)` | 3,621.7 | 3,688.1 | 1,520 |
| sum-int-constants | `(n:IntConstant) count(*), sum(n.value)` | 93.9 | 94.2 | 1,567 |

其中最重要的一行是**全属性搜索 `count(*)`：47 s**。`rust-explorer-fixture64-p95.md` 在同一语料形状上记录的是 1.30 s，
也就是说这一形状自那以后退化了约 36 倍。按标签拆开（`allprops-by-label.tsv`，每个标签单独 `MATCH (n:Label) WHERE any(…) RETURN count(*)`）：

| 标签 | 节点数 | 耗时 | 路径 |
|---|---:|---:|---|
| LocalVariable | 10,071,916 | 85 ms | 列扫描（`type`、`name`、组合的 `method`） |
| CallSiteNode | 5,051,914 | 232 ms | 索引 + 组合签名 |
| **ParameterNode** | 1,473,753 | **18,849 ms** | 通用求值器逐节点解释 |
| **ReturnNode** | 1,451,922 | **15,811 ms** | 通用求值器逐节点解释 |
| **FieldNode** | 733,312 | **10,402 ms** | 通用求值器逐节点解释 |
| StringConstant | 448,588 | 1.8 ms | 列 + 三元组 |
| IntConstant | 157,407 | 0.8 ms | 数值文本不可能匹配，整型跳过 |
| Method（单独） | – | 48,833 ms | 通用求值器 |

三个"声明类型"标签（`scan.rs:955-1000` 的 `declaration_tags`）合计 45 s：它们的 `keys(n)` 自 #174 起包含声明类型属性，
这些属性只能从 `graph.types` 表读出，列规划（`tag_plan`）对它们给出 `TagPlan::Generic`，于是整个 `any(k IN keys(n) …)`
谓词对这 3.66M 个节点逐个走通用求值器（§4.6 的栈），每节点约 12 µs。零命中词之所以只要 335 ms，是因为
`declared_text_absent` 的否定摘要能证明该词不在声明文本里、从而移除通用叶；一个**存在**于声明文本里的词（如 `Spooler`）
摘要帮不上忙。`LIMIT 25` 版本 180 ms 只是因为前几张图就凑够了 25 行。这是当前代码里**最大的单项查询性能问题**：
它不在 73 用例目录里，门禁看不到；但它是 Explorer "搜索一切"框的形状，也是 README 记录的生产 P95 家族。

表 4.5 里还有一组更说明问题的行：**与目录用例只差一个词的查询，掉下专用路径后慢 3–4 个数量级**。

| 目录用例（专用路径） | 本地隔离 p50 | 改一个词 | 本地中位 | 倍数 |
|---|---:|---|---:|---:|
| `WITH n.callee_class AS c WHERE c IS NOT NULL RETURN c LIMIT 200` | 0.93 ms | `WHERE size(c) > 40` | 15,127 ms | 16,000× |
| `RETURN n.caller_class ORDER BY n.caller_class LIMIT 200` | 1.30 ms | `ORDER BY n.caller_class, n.callee_name` | 4,448 ms | 3,400× |
| 同上 | 1.30 ms | `RETURN n.caller_class, n.line ORDER BY n.line DESC` | 4,634 ms | 3,600× |
| `RETURN count(*)` | 0.53 ms | `RETURN min(n.line), max(n.line)` | 3,688 ms | 7,000× |
| `RETURN DISTINCT n.callee_class LIMIT 200` | 9.4 ms | `RETURN count(DISTINCT n.callee_class)` | 791 ms | 84× |
| `WITH collect(n.callee_name) AS names RETURN names[0..10]` | 0.82 ms | `collect(DISTINCT …)` 后 `size()` | 1,169 ms | 1,400× |
| `MATCH (a)-[r]->(b) RETURN count(*)` | 95 ms | 两跳 `(a:CallSite)-[r]->(b)-[s]->(c) RETURN count(*)` | 6,848 ms | 72× |

每一行的"慢"都是同一件事：`with_stream`、`ordered_property`、`bounded_*`、`count_star` 等专用路径只认领被证明完全的
精确形状，差一个函数（`size`）、多一个排序键、换一个聚合，就落回通用管线——每个 CallSite 一行 `IndexMap<String, Value>`、
解释执行投影与谓词、全部物化后再排序/聚合，约 3 µs/行（15.1 s ÷ 5.05M）。`partition.rs` 的文件头早已写明"按形状
逐个认领是赢不了的竞赛"；这组数据给出了这场竞赛当前的赔率。它决定了生产上"下一个没见过的形状"的延迟，
也是 §4.6 的理由：**通用管线的每行常数开销才是目录外空间的上界。**


- **机制**：
  - `graph.callsite-string-index`（三元组 + 四个属性的 CSR）只覆盖 CallSite 的四个字符串属性。其余原始字符串属性
    （`StringConstant.value`、`Field.name/type`、`LocalVariable.type` 等）靠 `storage/columns.rs` 在首次使用时
    从记录建列并建三元组索引（`value-contains-node` 首次 388 ms、稳态 1.5 ms 正是这个差别）。
  - 组合属性（`caller_signature`、`method`、`actual_type`）没有任何索引：全属性搜索对每张图的每个不同方法组合一次
    字符串再测试（`SignatureMemo`），这是它零命中仍要 335 ms 的原因；`count(*)` 版本更要把每个幸存节点交给通用求值器
    （§4.6），本地栈采样（`profiles/allprops-count-stack.txt`）显示它在 `fused_rows → stream_match → sweep_prepared →
    eval(predicate_function)` 里逐节点解释 `any(k IN keys(n) WHERE toString(n[k]) CONTAINS …)`。
  - 数值等值 `n.value = 105873` 读完每张图所有整数常量的记录（没有数值列）。
- **做法**：
  1. **先止血**：给声明类型文本一条列路径——`graph.types`（GTY05）已按字符串 id 给出每个 Field/Parameter/Return 的
     声明类型组成，`declared_text_absent` 已经能在词缺席时证明否定；把它推广为"按节点给出声明文本是否包含某词"的列测试
     （组成部分的字符串 id 集合 + 三元组候选，与组合签名 `SignatureMemo` 同一思路），三类标签就不再落回
     `TagPlan::Generic`。预期 47 s → 亚秒（LocalVariable 的同类扫描 10M 节点 85 ms 是参照）。
  2. 把保存器扩展为写出"全部原始字符串属性的列 + 一个覆盖整个字典的三元组索引"（而不只是 CallSite 的四列），
  并为组合属性写出它们的组成（方法 = 类 id + 名 id + 参数 id 列表，已在 `graph.nodedata` 的方法表里）以便
  `signature CONTAINS` 先在"类名/方法名/参数类型"三个字典子集上做三元组收窄再组合验证；给 `IntConstant.value`
  一个按值排序的列。读取器侧 `columns.rs` 已经有列的内存形态，只需增加"映射已持久化的列"的路径。
- **预期收益**：全属性搜索 `count(*)` 47 s → 亚秒（第 1 步即可），再到百毫秒级（第 2 步）；`LIMIT 25` 180 → ~5 ms，
  零命中 335 → ~1 ms（64 图三元组位图直接否决）；数值等值 24–27 ms → ~1 ms；所有非 CallSite 属性的首次使用成本消失。
  生产 P95（README 记录的 ~15 s）主要由这一族构成，这是**对生产环境最有价值的一项**；第 1 步只改读取器，第 2 步改动面
  最大（保存器 + 读取器 + 格式绑定）。
- **约束**：新文件必须按 `CLAUDE.md` 的 sidecar 规则绑定节点布局与字典摘要；不改现有文件格式版本；
  加载 RSS 增量需在 10% 内（列可以映射，不必驻留）；构建/保存时间是最低优先级，但要单独报告。

### 4.6 通用求值器的常数开销（所有落到通用路径的形状）

- **证据**：`eval.rs` 的 `Row` 是 `IndexMap<String, Value>`（默认 SipHash），`predicate_function`（`any/all/none/single`）
  对每个节点 `row.clone()`、对列表每个元素 `variable.to_string()` 插入一次、`name.to_ascii_lowercase()` 一次；
  `functions::call` 先进 `temporal::call`（再分配一次小写名）再 `match`。全属性 `count(*)` 的栈顶就是
  `hash_one<RandomState, &String>`（变量名查找）；按标签拆解得到的单节点成本是 **12 µs**（3.66M 节点 45 s），
  而同一谓词在列路径上是 8.5 ns/节点（LocalVariable 10.07M 节点 85 ms）：通用路径与列路径相差三个数量级。
- **做法**：在解析后做一次"绑定解析"：变量引用解析为行槽位（`Vec<Value>` 行 + 作用域表）、函数名解析为枚举、
  谓词函数复用一个作用域帧而不是克隆整行；或者至少把 `Row` 的哈希换成 FxHash 并缓存小写函数名。
- **预期收益**：通用路径每节点开销 3–10 倍下降；它不改变 73 用例目录里的任何数字（目录用例几乎都走了专用路径），
  但决定了"没被专用路径认领的下一个生产形状"会有多慢——`partition.rs` 文件头已经写明，按形状逐个认领是赢不了的竞赛。
- **约束**：错误语义、空值语义、求值顺序必须与现在逐字节一致，TCK（3,897 个场景）与差分测试是现成的护栏；改动面大，
  建议分两步：先 FxHash + 预解析函数名（小改），再做槽位行。

### 4.7 `shape-distinct` 的 provenance 重建（9.4 → ~3 ms）

胜出值的跨图存在性检查用 `strings().index_of`（UTF-16 码元比较的二分，`java_cmp`）做 200 × 64 次；
每图每属性的 CSR 已经给出"哪些 string id 出现过"，而各图字典是有序的：对 ASCII 键先按字节比较、或在证明阶段
顺手建一个 `&str → sid` 的哈希（每图一次、按 `Graph` 缓存）都能把这一步降到亚毫秒。绝对收益 ~6 ms，优先级低。

### 4.8 亚毫秒层：还剩什么

46 个用例隔离 p50 在 0.5–1.2 ms，其中 `RETURN 1` 的 0.6 ms 是上限内的固定成本。可量化的剩余项：
`spawn_blocking` 往返 0.1–0.15 ms；每请求新建 `Executor`、克隆 64 个 `Arc<Graph>`、解析缓存命中；
`result_body` 一次性序列化。稠密词（`dense/*`，1.6–2.0 ms）在第一张图里把三元组候选逐个验证（上千个），
`PropertySets::posting_cost_up_to` 之后退回位图扫描——一个按 (图, 词) 的有界 LRU 字典解析缓存能再省 ~0.5 ms，
但 `rust-explorer-fixture64-p95.md` 已明确拒绝用跨请求缓存来换基准数字，生产上是否值得单独决定。
这一层整体的优化空间 ≤ 1 ms/请求，不建议投入。

### 4.9 相邻项：加载与就绪

本地热页缓存下 64 图就绪 47 s（托管 47.7 s），与 `serve.rs` 注释"加载是一条线程跑 64 张图，45 s CPU 对 45 s 墙钟"
一致：`StringTable::load` 把 Java 序列化的前缀编码字典整个解码进 arena，`build_forward_csr`/`build_backward_csr`
在加载时重建两份 CSR（21M 条边 × 5 B × 2 ≈ 210 MB 堆）。按图并行加载（4 核 → ~13 s）或由保存器直接写出可映射的
CSR 与字典 arena 都能把就绪时间降一个量级；这属于"可用性与稳定性"优先级，不是查询 p50/p95，但它也是 §4.4 预热
成本的归属地。就绪后 RSS 3.7 GB，跑完全部协议后 4.4 GB。

另外，客户端断开不会取消正在执行的查询（`benchmark/README.md` 已声明）：本次一个被客户端放弃的全属性 `count(*)`
继续占满一个核直到 60 s 上限。对 4 个许可的并发守卫来说，这意味着被放弃的重查询会继续占用 1/4 的容量。

### 4.10 JVM（Kotlin）引擎

Kotlin Cypher 引擎是遗留服务器与 Kotlin API 的 `graph.query(...)` 路径，不是生产服务路径；本次没有重新测量。
历史的多图证据（`rust-explorer-fixture64-p95.md`、Attempt 308–310）表明它在同一负载上慢 3–16 倍，并且在
`value CONTAINS` 的 `count(*)`、全属性搜索等形状上会耗尽 8 GiB 堆或返回 HTTP 400。若它仍需维护，
§4.5 的持久化列/索引同样是它最大的空间（`columns.rs` 的设计正是从它借来的）。

## 5. 优先级建议

按 `CLAUDE.md` 的顺序（正确性 > 可用性/稳定性 > 查询 p50/p95 > 构建/保存）与绝对收益排序：

| 优先级 | 项 | 影响的指标 | 预期绝对收益 | 改动面 / 风险 |
|---|---|---|---|---|
| P0 | §4.1 rayon 队头阻塞 | 73 用例中十余个的压力 p50/p95；生产中任何并发交互式查询 | 单用例 55–95 ms → ~1 ms；压力 p95 尾巴（74–259 ms）回到隔离值 | 中 / 低：不改结果，只改调度 |
| P0（紧急） | §4.5 第 1 步：声明类型文本的列路径 | 全属性搜索 `count(*)` 与任何命中声明文本的全属性谓词 | 47 s → 亚秒；`LIMIT 25` 180 → ~10 ms | 中 / 低：只改读取器，否定摘要已有同样的数据来源 |
| P0 | §4.5 第 2 步：全属性字符串索引与数值列 | 目录外生产形状、生产 P95 | 180–1,460 ms → 1–5 ms；`count(*)` 亚秒 → 百毫秒；数值等值 25 → ~1 ms | 大 / 中：保存器 + 读取器 + sidecar 绑定 |
| P1 | §4.2 边槽表缓存 | 两个重用例 + 它们制造的阻塞 | 95 → 1–2 ms（×2） | 中 / 低 |
| P1 | §4.3 分组合并 | 两个重用例 | 93 → 15–35 ms（×2） | 小 / 低 |
| P1 | §4.4 首次使用证明持久化或预热 | 重启后首个请求、可用性 | 200–1,500 ms 首次成本归零 | 中 / 中（格式绑定） |
| P1 | §4.6 通用管线的每行常数开销（绑定解析、槽位行、更便宜的哈希） | 所有落到通用路径的形状：目录外秒级查询、"差一个词"的悬崖 | 每行 3–10 倍（15 s → 2–5 s 量级；配合有界物化再降） | 大 / 中（TCK 与差分测试是现成护栏） |
| P2 | §4.9 并行加载、持久化 CSR | 就绪时间 | 47 s → ~13 s 或更低 | 中 / 低 |
| P3 | §4.7 DISTINCT provenance | 一个用例 | 9 → 3 ms | 小 |
| 不建议 | §4.8 亚毫秒层 | 46 个用例 | ≤ 1 ms | – |

把 P0/P1 全部做完后，73 用例目录的预期形态是：隔离 p50 全部 ≤ 35 ms（最大为分组合并），压力 p95 ≤ 隔离 p50 的 2 倍，
目录最大值从 95 ms 降到约 20–35 ms；也就是说**目录内还有约 3–5 倍的尾部空间与 50–100 倍的碰撞空间，
目录外的生产形状有 10–100 倍空间**。之后目录本身应补入生产形状（全属性搜索及其 `count(*)`、数值等值、带谓词的分组计数、
两跳计数 6.8 s、变长路径计数 0.8 s），否则门禁会继续对生产 P95 视而不见——本次 47 s 的退化正是这样漏过的。

## 6. 约束与验收提醒

- 本报告的所有收益数字都是本地单机诊断推断；按 `CONVENTIONS.md` 与 `CLAUDE.md`，验收只能在 real64 多图负载上按
  既定协议（73 用例、4 并发、20 压力 + 40 隔离、完整响应消费）做 base/candidate 配对测量，CPU 与峰值 RSS 各自独立
  满足 10% 容差，JVM 堆上限 8 GiB 不动。
- 每个 sidecar 新文件都要绑定它每个答案依赖的全部字节（节点布局、字典、索引），不能改 `graph.metadata` 尾部，
  不能提升记录格式版本（门禁用 base 代码读 candidate 的图）。
- 把工作从查询挪到加载/保存/预热必须单独计量并声明，不能让延迟工作在两个门禁之间消失。
- 不引入"返回上一次响应字节"式的结果缓存去换基准数字；若为生产引入跨请求缓存，要在 warm/warm 配对下单独测。
- 每个尝试一条记录，追加到 `docs/cross-graph-cypher-optimization-attempts.md`（引擎）或
  `docs/webgraph-optimization-attempts.md`（存储/保存器），延续现有编号。

## 附录 A. 完整数据

- 托管 head（`2275a50`）73 用例：[`hosted-head-cases.tsv`](query-performance-headroom-2026-11/hosted-head-cases.tsv)
  （从 artifact 13809622988 的 `report.json` 提取；列为压力 p50/p95 的 base/head 与隔离 p50/p95 的 head）。
- 本地 `58215b9` 第 1 轮（含首次使用）：[`local-run1.tsv`](query-performance-headroom-2026-11/local-run1.tsv)，
  全部样本 [`local-run1.json`](query-performance-headroom-2026-11/local-run1.json)。
- 本地第 2 轮（无首次使用阶段，重复性）：[`local-run2.tsv`](query-performance-headroom-2026-11/local-run2.tsv)，
  [`local-run2.json`](query-performance-headroom-2026-11/local-run2.json)。
- 栈采样聚合：[`profiles/`](query-performance-headroom-2026-11/profiles/)。
- 目录外探针：[`probes.tsv`](query-performance-headroom-2026-11/probes.tsv)；全属性 `count(*)` 按标签拆解：
  [`allprops-by-label.tsv`](query-performance-headroom-2026-11/allprops-by-label.tsv)。
- 生产混合（`backend/bench/backtest.py --mix v2/v3`，170 查询，单客户端）：
  [`backtest-v2.txt`](query-performance-headroom-2026-11/backtest-v2.txt)、
  [`backtest-v3.txt`](query-performance-headroom-2026-11/backtest-v3.txt)。
- 干扰实验原始输出：[`interference.txt`](query-performance-headroom-2026-11/interference.txt)。
- 脚本：[`scripts/`](query-performance-headroom-2026-11/scripts/)（协议复刻 `pressure.py`、干扰 `interfere.py`、
  探针 `probes.py`、采样 `sample.sh` + `aggregate.py`、起服务 `start-server.sh`）。

## 附录 B. 复现步骤

```bash
./gradlew :webgraph:jmhJar :webgraph:prepareBenchmarkFixtures --no-daemon
F=frontend/jvm/webgraph/build/benchmark-fixtures
java -Xmx4g -XX:+UseG1GC -Dandroid.jar.path=$F/android-all-14-robolectric-10818077.jar \
  -Dtika.jar.path=$F/tika-app-2.9.2.jar -Dhive.jar.path=$F/hive-exec-4.0.0.jar \
  -Dkotlin.compiler.jar.path=$F/kotlin-compiler-embeddable-2.0.21.jar \
  -cp frontend/jvm/webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar \
  io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation /path/to/fixture64
cargo build --release --locked -p graphite-cli
docs/query-performance-headroom-2026-11/scripts/start-server.sh target/release/graphite /path/to/fixture64/graphs.tsv 18080
python3 docs/query-performance-headroom-2026-11/scripts/pressure.py --manifest /path/to/fixture64/graphs.tsv \
  --native-queries benchmark/src/native_queries.json --out run.json
```
