# Attempt 140 old34 独立审计

**原34局部门槛通过，仅可继续 v3。** 六份原始 TSV 的 204 个完整14字段签名按查询顺序精确匹配同一冻结 oracle；JMH query/success/failure/timeout、64图、所有资源和总 work 核对一致。独立重算 regression=true、每组严格 P95 进步=true，与原 comparator/local-progress 相符；target10x=false，尚未接受候选。

| Pair / 顺序 | main → candidate P95 ms | speedup | CPU s | heap GiB | RSS GiB |
|---|---:|---:|---:|---:|---:|
| 1 / candidate-base | 42.972042→42.149667 | 1.019511x | 1.507567→1.434618 | 4.388→3.998 | 4.909→4.489 |
| 2 / base-candidate | 51.234250→49.121708 | 1.043006x | 1.590003→1.502798 | 4.384→3.999 | 4.892→4.495 |
| 3 / candidate-base | 49.196834→39.949834 | 1.231465x | 1.535388→1.418618 | 4.381→3.990 | 4.907→4.471 |

P95 是每轮34个不同查询的 nearest-rank第33位；六份都由 wrapped case-insensitive DISTINCT dense 决定。它不是单条查询三次样本的 P95。首组仅 1.01951x，现有3组不能证明稳定 CI 收益。

所有非 latency 字段相同，包括 hit/access/source/work/counters；每轮总 work 均58,071,626。计时变化不能直接归因到某一个节点操作。

保留31/102个变慢观测。four-properties-targeted 仅pair1满足双界限：9.771291→11.652083ms（+19.2481% / +1.880792ms），其它两组未重复。four-properties-dense三组都变慢，第三组+17.6356%但仅+0.680332ms，因此没有同一查询两组同时 >15% 且 >1ms；不能删去这些原值。CPU/heap/RSS均未超资源界限。

运行命令核对旧协议的 cold index、CPU4、gc profiler、64图和正确性verify；candidate记录hash匹配构建receipt，root采集的old34结束图内容receipt与control相同。本审计只读小TSV/JSON；v3计时期间没有重新hash大JAR/图、运行Java或触发新测量。

## 全34查询配对表

| 查询 ID | Pair1 main→candidate ms | Pair2 main→candidate ms | Pair3 main→candidate ms |
|---|---:|---:|---:|
| global-wide-four-properties-zero | 264.116750→250.971042 | 262.084667→244.991167 | 263.459250→255.598666 |
| global-wide-four-properties-targeted | 9.771291→11.652083 † | 10.093750→9.882125 | 9.934416→10.452417 |
| global-wide-four-properties-dense | 4.034125→4.108459 | 4.131125→4.242917 | 3.857709→4.538041 |
| global-wide-class-pair-zero | 1.980209→2.015708 | 2.449500→1.908750 | 1.895166→1.932416 |
| global-wide-class-pair-targeted | 4.397166→4.148667 | 4.899209→4.083167 | 4.414000→4.373500 |
| global-wide-class-pair-dense | 0.959125→0.904417 | 1.038083→0.978625 | 0.843375→0.959834 |
| global-wide-name-pair-zero | 1.751417→1.756917 | 2.046875→1.789625 | 1.595208→1.847541 |
| global-wide-name-pair-targeted | 4.129417→4.611708 | 4.874416→4.140459 | 4.001833→4.351500 |
| global-wide-name-pair-dense | 0.846458→0.774000 | 0.836959→0.761250 | 0.781084→0.825917 |
| global-wide-caller-class-zero | 1.630500→1.532500 | 1.835291→1.667417 | 1.538209→1.512000 |
| global-wide-caller-class-targeted | 2.762625→2.907792 | 3.397334→2.667583 | 3.013917→2.440708 |
| global-wide-caller-class-dense | 0.531208→0.603292 | 0.560166→0.633958 | 0.621125→0.530917 |
| global-wide-callee-class-zero | 1.419917→1.517500 | 1.588875→1.212209 | 1.314834→1.582375 |
| global-wide-callee-class-targeted | 2.101041→2.435625 | 2.465125→2.184875 | 2.131875→2.505167 |
| global-wide-callee-class-dense | 0.467041→0.487125 | 0.490375→0.456583 | 0.431625→0.548042 |
| global-wide-provenance-zero | 1.162542→1.141958 | 1.213750→1.101333 | 1.011208→1.189208 |
| global-wide-provenance-targeted | 3.359208→3.006834 | 3.033000→3.027583 | 2.822709→3.061667 |
| global-wide-provenance-dense | 0.803708→0.771959 | 0.850084→0.816666 | 0.843792→0.826333 |
| global-wide-aliased-zero | 1.001416→0.996459 | 0.985041→0.973167 | 1.286125→0.946083 |
| global-wide-aliased-targeted | 3.301625→3.335334 | 3.436709→3.372000 | 3.811750→3.121625 |
| global-wide-aliased-dense | 0.737584→0.741375 | 0.745542→0.762000 | 0.887000→0.717167 |
| global-wide-parameterized-zero | 0.976500→0.957333 | 0.929375→0.934250 | 1.197542→0.907000 |
| global-wide-parameterized-targeted | 2.208625→2.134416 | 2.139833→2.114500 | 2.286833→2.088584 |
| global-wide-parameterized-dense | 0.754250→0.706084 | 0.793959→0.744417 | 0.778917→0.713875 |
| global-wide-wrapped-case-insensitive-zero | 1.349416→1.087375 | 1.380875→1.016958 | 1.354583→1.031542 |
| global-wide-wrapped-case-insensitive-targeted | 2.436417→2.109166 | 2.643625→2.119333 | 2.985375→2.184709 |
| global-wide-wrapped-case-insensitive-dense | 1.361542→1.328000 | 1.435250→1.376834 | 1.564459→1.354292 |
| global-wide-wrapped-case-insensitive-distinct-zero | 3.734917→3.405458 | 3.528417→3.232542 | 3.527625→3.040333 |
| global-wide-wrapped-case-insensitive-distinct-targeted | 29.250708→24.092292 | 32.040125→33.121708 | 20.057541→22.438167 |
| global-wide-wrapped-case-insensitive-distinct-dense | 42.972042→42.149667 | 51.234250→49.121708 | 49.196834→39.949834 |
| global-wide-distribution-broad-all-64 | 0.782459→0.777917 | 0.867334→0.803625 | 0.853750→0.786625 |
| global-wide-distribution-localized-early | 1.202167→1.231709 | 1.290666→1.230834 | 1.255084→1.198000 |
| global-wide-distribution-localized-late | 4.006916→3.954416 | 4.281000→3.960417 | 4.392041→3.943958 |
| global-wide-distribution-localized-middle | 2.744875→2.682792 | 2.853584→2.754833 | 2.798625→2.674875 |

† 表示该次同时超过 >15% 与 >1ms，不能据单次越界改写原重复规则。后续真实 v3 或 exact-head CI 的任何失败仍必须保留，不得用此旧34结果豁免。

原始审计数据：[independent-old34-audit.json](independent-old34-audit.json)；复算：[independent-old34-audit.py](independent-old34-audit.py)。原结果：[global-wide-status.json](old34-pairs/global-wide-status.json) / [report](old34-pairs/global-wide-report.md)。
