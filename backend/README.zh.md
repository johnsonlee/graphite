# Graphite Explorer（Rust）

[English](README.md) · 简体中文

这是 `graphite-explore` 的 Rust 实现。它读取相同的磁盘图格式，提供相同的 HTTP API，
可用于替换 Kotlin 服务。

目前，Rust `graphite` CLI 执行 `query`、`serve` 和 `mcp`；`build` 则将字节码分析和图构建
交给 JVM 前端。可运行的入门示例见[快速上手演示](../docs/quickstart-demo.md)。

历史一致性报告记录了 `query` 的 148 项字节级检查、150 项服务端差异检查，以及在
64 图验收数据集上得到完全相同字节结果的 34 个查询。这些数量只说明报告中所测版本
及其覆盖范围，不保证当前所有路由或命令都完全一致。详见
[一致性测试覆盖与未覆盖的内容](../docs/rust-explorer-parity-and-latency.md#what-the-parity-suite-covers-and-what-it-does-not)。

在历史真实数据集比较中——64 张图、1,940 万个节点、冷启动，P95 按不同查询计算——
**Rust 的 P95 延迟约为 Kotlin 服务的 1/16.7**；即使用 Kotlin 最快的一轮与 Rust
最慢的一轮配对，差距也有 10.9 倍。P50 则相差 5.3 倍。

这个数字几乎完全取决于一种查询：Kotlin 的 P95 对应
`global-wide-wrapped-case-insensitive-distinct`，它也是唯一耗时超过 30 ms 的查询类型。
排除这一因素后，差距约为 3 倍，其中约 1–2 倍来自语言层面。Rust 在该查询上的优势
源于专门编写的快速路径，并非语言本身的特性。方法、逐查询数据及原因分析见
[64 图 P95 报告](../docs/rust-explorer-fixture64-p95.md)；另行记录的单图比较见
[一致性与延迟报告](../docs/rust-explorer-parity-and-latency.md)。

## Crate 组成

| Crate | 目录 | 用途 |
|-------|------|------|
| `graphite-storage` | `storage/` | 读取持久化格式，包括 BVGraph 邻接数据、前缀压缩字符串字典、节点记录、元数据、类概览和资源 |
| `graphite-cypher` | `cypher/` | 基于存储层的 Cypher 解析器、求值器和查询流水线 |
| `graphite-explore` | `explore/` | 提供 Explorer API 的 HTTP 服务 |

`graphite` CLI 位于仓库根目录的 `cli/`，不在本目录中。根目录 `Cargo.toml` 定义的
Cargo workspace 包含 `backend/*`、`cli` 和 `benchmark`。`graphite serve` 运行此服务 crate 的
`serve` 模块；`explore/Dockerfile` 将 `graphite` 二进制打包为 `graphite-explore` 容器镜像。

## 构建与运行

```bash
# 在仓库根目录执行
cargo build --release
./target/release/graphite serve --id app /path/to/graph --port 8080
```

任何测量都必须使用 `--release`。普通 `cargo build` 会在 `target/debug/` 下生成未经优化
的二进制。在历史 64 图回测中，debug 构建的 P50 为 6.7 ms、P95 为 42 ms，release
构建则分别为 1.0 ms 和 3.9 ms；debug 构建并不比 Kotlin 服务快。因此，debug 二进制
会在启动时输出警告。

命令行参数与 `graphite serve` 一致：`--data`、`--graph id:path`、`--id`、`--port`、
`--load-mode`、`--topology`、`--max-concurrent-cypher`、`--cypher-max-timeout-ms` 和 `--metrics`。

## 测试

```bash
cargo test                              # 单元测试
cd bench && python3 parity.py           # 与 Kotlin 服务比较的 150 项差异测试
cd bench && python3 parity-cli.py       # graphite query 的 148 项字节级测试
cd bench && python3 bench.py            # 延迟比较
```

`parity.py` 和 `bench.py` 都要求 Kotlin 服务监听 18081 端口，Rust 服务监听 18080 端口，
并使用 `app` 作为图 ID 提供同一张图。

## 多图查询基准测试

当前 CI 使用 [`benchmark/`](../benchmark/README.zh.md) 中的 Rust 控制器，对 base/head
执行条件匹配的构建、加载和真实 64 图查询。正确性、覆盖完整性或 OOM 检查失败会阻止
PR 通过；托管运行器的耗时仅用于报告。控制器保留完整响应，以及每个查询用例的重复请求
延迟样本及 p50/p95 结果。固定工作负载和当前执行协议见该目录的 README。

以下 Python 快照工具仍可用于历史重放与诊断，但不再是当前 CI 的验收流程。比较时应
使用真实的 64 图数据集。原有字符串搜索测试不覆盖排序、聚合、遍历、WITH 流水线或
ID 查找。广覆盖测试复用 `bench/shapes.py` 中的 36 种查询和 3 个模式查询；`all` 还会
保留原有字符串搜索对照用例。

```bash
python3 backend/bench/snapshot.py --manifest /path/to/graphs.tsv \
  --binary ./target/release/graphite --suite all --repetitions 5 \
  --timeout 60 --overall-timeout 600 \
  --responses-dir benchmark-results/responses --out benchmark-results/snapshot.json
```

快照命令会保留完整响应。`fixture64` 保留历史上的搜索加模式查询方案；`fast34` 只选择
搜索查询；`broad` 则选择 36 种查询及模式查询。

这些串行轮次报告各用例的中位数。汇总 p50/p95 描述的是一轮中不同查询的分布，并非
服务端重复请求的百分位，也不是持续负载测试。单图查询耗时仅用于诊断。在声明了多图
工作负载和并发度的条件下测得的重复请求 p50/p95，才是另一类独立的验收证据；仅加载
64 张图，并不能证明每个带限制条件的查询都实际扫描了全部图。

要诊断服务端与客户端的计时边界，可在同一命令中加入
`--diagnostic-metrics-case shape-union`，并使用新的输出目录。这会启用 `/metrics`，
记录请求耗时及调用线程的 CPU 时间，并保留每轮该用例执行前后的指标。严格指标检查
要求默认的四查询并发限制，以及每个测量窗口内恰好一次成功查询。HTTP 耗时截止于响应
构建完成；并发许可持有时间截止于序列化之前；客户端延迟包含完整响应读取。指标的
sum/count 不能用来估计 p95。

历史 CI 会为绝对延迟回退最大的用例执行一次此类诊断，保留完整的 73 查询顺序和五轮
执行，并将结果保存在 `rust-failure-diagnostic` 中，与原始验收结果分开。当前工作流
不再运行这一旧诊断阶段。启用指标及插入抓取操作会改变执行过程，因此诊断
成功既不能证明性能验收通过，也不能消除先前观察到的回退。
