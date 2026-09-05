# Attempt 137 显式回退 CI 独立审计

回退 exact head `e6c932c5e1d0fb7b583ceb9e14c8ef88ec9d9694`：unit `33988848638` 成功；benchmark `33988848640` 失败。两个 run 均已终态，完整快照及 SHA 见 `ci-audit.json`。本次没有重跑 CI、修改 gate 或进行新的测量。

## 已确认失败

Method scan 的原始 JMH CPU 与最终 status 数值一致；下列初次及反向确认均超过既有 15% 门槛：

| 场景 | 初次 CPU（秒） | 反向确认 CPU（秒） |
|---|---:|---:|
| 17 graphs / OR | 1.95 → 3.17（+62.56%） | 2.18 → 3.41（+56.42%） |
| 36 graphs / contains | 5.00 → 5.88（+17.60%） | 5.11 → 6.02（+17.81%） |

两组 shard 的初次及确认共 12 个场景，base/candidate 响应签名一致，且每个场景的 `requestsSucceeded` 为 1。证据位于 `method17-scan/`、`method36-scan/`；逐文件 hash 及核对结果在 `method-confirmed-failures.json`。

Global-wide 的失败包含实际 regression gate，不能仅解释成回退未满足严格 progress。34 行 nearest-rank P95 为第 33 个升序值，三组均由 wrapped case-insensitive DISTINCT dense 行决定：

| Pair / 顺序 | P95 base → revert（ms） | CPU（秒） | 峰值 heap（bytes） | 峰值 RSS（bytes） |
|---|---:|---:|---:|---:|
| 1 / candidate-base | 125.214119 → 144.742573 | 4.01 → 3.83 | 4520574760 → 4350047352 | 5313597440 → 5212008448 |
| 2 / base-candidate | 121.341803 → 148.690291 | 3.81 → 3.75 | 4308587416 → 4317114696 | 5118930944 → 5125427200 |
| 3 / candidate-base | 112.354387 → 150.906486 | 3.76 → 3.86 | 4518451560 → 4316075408 | 5322489856 → 5190070272 |

P95 三组均同时超过 +15% 和 +1 ms；7 个 aligned query ID 至少两组满足同一退化条件（完整逐组数值见 JSON）：

- `global-wide-name-pair-zero`：2 组。
- `global-wide-caller-class-targeted`：2 组。
- `global-wide-aliased-zero`：2 组。
- `global-wide-wrapped-case-insensitive-distinct-zero`：2 组。
- `global-wide-wrapped-case-insensitive-distinct-dense`：3 组。
- `global-wide-class-pair-targeted`：2 组。
- `global-wide-provenance-zero`：2 组。

aggregate P95 与 DISTINCT shape P95 的错误描述对应同一 dense 行，不是两项独立工作。CPU/heap/RSS 原始分数与发布值一致，没有达到资源退化阈值。`regressionPassed`、`progressAchieved`、`targetAchieved` 都是 false。

## 正确性与输入核验

Global-wide 六份 TSV 的 204 个结果，按全部 14 个 correctness 字段（包括 query identity、行数、response bytes、digest）逐行匹配冻结 oracle；每对的 hitGraphIds、source/access 数量与列表、graphWorkUnits、parallelScanCount、indexLookupCount、executionPath 全部相同。没有在本次审计中重新执行查询，也没有把摘要核对表述为重新检查完整行对象。

Routing 三种状态 cold / warm / startup-prepared 全部通过。六份 TSV 的 6,822 个结果匹配 1,137 个 oracle 签名；2/8/64 图宽度下的 P50/P95 已从 TSV 独立重算并与 status 相符。

Global-wide 与 routing 两份 evidence manifest 共 51 个文件的 SHA 全部匹配；base/candidate SHA、graphs.tsv、fixture-provenance.tsv 及两次 fixture reproducibility 的语义 hash 均一致。记录的 base/candidate JAR 内容 hash 都为 `ddfed3136e1c443d1cbd1cc96285de133fb84ba2a6a252b43326f82020250275`。本次仅核验托管 artifact 的记录与 hash 链，没有重新读取原始 JAR 或图文件。

## 判断边界

记录中的二进制内容相同不构成豁免或原因解释：这次 CI 按现行门槛失败，应保留结果。Method whole-process CPU 包括 reference oracle、客户端、server/engine 与后台工作；现有 artifact 不能把此次 Linux CI 退化归因给具体阶段。

原 candidate `536f585a` 的 unit 成功，benchmark `33988242513` 最终 cancelled；取消前已完成的两个 Method 4 CPU 双次失败足以拒绝。其 large-corpus / routing / global-wide 未完成，不能写成通过。候选证据与 terminal receipt 单独保存在相邻 `../ci/`。

重算脚本：`recompute-audit.py`；机器可读结果：`ci-audit.json`。
