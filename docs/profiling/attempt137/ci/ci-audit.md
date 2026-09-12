# Attempt137：两个已确认 CI 失败的独立审计

**候选已满足拒绝条件。** exact head `536f585aab37da0888dd021cf9355d73dad1c545`，benchmark run `33988242513` 的两个 Method4 shard 均在 process CPU 初测与确认测量超过15%门槛。无需等待其他性能项翻案。

| shard / scenario | 初测 base → candidate CPU s | 增幅 | 确认 base → candidate CPU s | 增幅 |
| --- | --- | --- | --- | --- |
| aggregate / count | 0.67 → 0.89 | 32.8358% | 0.89 → 1.05 | 17.9775% |
| position / middle | 0.86 → 1.13 | 31.3953% | 0.84 → 1.04 | 23.8095% |

数值逐项对照原始初测/确认 JMH JSON，等于 final cpu-status.json，两个blocked=true。两个shard的wall与RSS-after最终gate均通过；position early/zero的初测CPU异常未获确认，不列作最终失败。RSS-delta是advisory。

四套base/candidate响应签名（aggregate初测/确认各2条，position各3条）排序后全部相等；requestsSucceeded metric每scenario为1，表示action成功，不是HTTP请求次数。签名覆盖scoped/root结果；本审计没有重新执行服务或重建完整行。CPU是整个Method gate action的进程CPU，包含oracle/HTTP/engine等，不是独占engine CPU；本次未确定退化原因。

下载绑定 exact run 的 artifacts：benchmark-method-compatibility-shard-4-aggregate-116-1、benchmark-method-compatibility-shard-4-position-116-1。主要证据在 method4-aggregate/method-compatibility-4-aggregate-cpu-status.json、method4-position/method-compatibility-4-position-cpu-status.json；同目录有原始base/candidate与confirmation JSON/TXT。JMH记录为Linux Temurin17、8GiB、1fork/1measurement、0warmup、4corpus路径。Shard artifact没有独立fixture/JAR content provenance，本报告不虚构该证明。

其他原候选检查仍可能未完成，显式回退push也可能取消它们；未完成/取消结果不可用于声称通过、失败或10x。unit33988242502当前快照保留在JSON，最终状态另行更新。已完成的两个双次CPU失败足以拒绝。

仅只读检查已下载证据，未重试CI、修改代码/PR或启动Java/测量。ci-audit.json保存完整数值、状态快照和输入hash；审计通过表示证据自洽，不表示候选或CI通过。
