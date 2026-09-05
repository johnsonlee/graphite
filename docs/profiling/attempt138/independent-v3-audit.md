# Attempt 138 v3 完整配对审计

完整值、顺序、来源与输入记录核验通过。发现重复超过参考尺度的退化：mixed-four-few-rows, or-four-broad-distinct

六份 complete run 共216个查询输出、37026行实际值／来源逐项匹配 oracle。每侧每查询只有3个观测，不能称P95、稳定收益或候选接受。顺序C/B、B/C、C/B；每查询清索引，JIT与OS缓存不清。

## 纯四关键词 OR 覆盖

| case | 完整命中图位置/数量 | 匹配节点 | 四词独立命中数 |
|---|---|---:|---|
| or-four-broad | 55 图 | 50461 | [473, 49431, 260, 68] |
| or-four-single-early | [0] | 704 | [673, 6, 24, 1] |
| or-four-single-middle | [31] | 2646 | [2547, 6, 9, 84] |
| or-four-single-late | [63] | 299 | [289, 4, 3, 3] |
| or-four-few-early-late | [0, 63] | 972 | [673, 6, 289, 4] |
| or-four-all | 64 图 | 2455554 | [1215977, 438686, 210146, 236328] |

每词均检索四属性，四词互不包含，AST为纯OR，16个原子谓词。完整命中图来自认证oracle全量统计，不能从LIMIT返回行推断；所有返回元组完整provenance已独立核验。

| query | Pair 1 base → candidate ms | Pair 2 | Pair 3 |
|---|---:|---:|---:|
| or-four-broad-rows | 33.529750 → 35.647583 | 33.106458 → 35.654958 | 36.510625 → 37.502875 |
| or-four-broad-distinct | 149.365542 → 185.582709 | 150.640750 → 167.722459 | 152.262625 → 192.266333 |
| or-four-single-early-rows | 14.927000 → 17.209500 | 15.384958 → 16.587833 | 17.159000 → 17.729125 |
| or-four-single-early-distinct | 146.407833 → 164.092125 | 149.163375 → 156.733750 | 151.997458 → 163.008875 |
| or-four-single-middle-rows | 87.190459 → 101.893083 | 90.530833 → 101.823542 | 93.144000 → 94.802375 |
| or-four-single-middle-distinct | 148.228208 → 164.116334 | 140.102583 → 164.756417 | 148.291709 → 165.038625 |
| or-four-single-late-rows | 147.124958 → 165.750583 | 138.388833 → 161.366666 | 150.904500 → 163.219458 |
| or-four-single-late-distinct | 145.175417 → 165.937417 | 148.621042 → 167.756209 | 149.166250 → 166.611959 |
| or-four-few-early-late-rows | 14.094875 → 17.515833 | 15.508583 → 16.604708 | 15.745000 → 12.283917 |
| or-four-few-early-late-distinct | 146.857375 → 164.358667 | 149.372042 → 162.376375 | 147.665000 → 162.512208 |
| or-four-all-rows | 12.576708 → 16.136750 | 14.642667 → 15.411042 | 14.226584 → 16.138708 |
| or-four-all-distinct | 178.127541 → 164.715167 | 179.068167 → 160.604875 | 180.221000 → 162.793916 |

## 全部变慢观测

| query / pair | base ms | candidate ms | delta ms | 增幅 | 同时超过参考尺度 |
|---|---:|---:|---:|---:|---|
| or-single-early-rows / 2 | 88.265041 | 90.064958 | 1.799917 | 2.0392% | 否 |
| or-single-early-distinct / 2 | 243.305583 | 286.589084 | 43.283501 | 17.7898% | 是 |
| or-single-early-distinct / 3 | 271.103334 | 286.956583 | 15.853249 | 5.8477% | 否 |
| and-single-early-rows / 1 | 36.738000 | 39.196083 | 2.458083 | 6.6908% | 否 |
| and-single-early-rows / 2 | 38.574584 | 39.919083 | 1.344499 | 3.4855% | 否 |
| and-single-early-rows / 3 | 23.968459 | 40.083958 | 16.115499 | 67.2363% | 是 |
| and-single-early-distinct / 1 | 171.187208 | 186.337584 | 15.150376 | 8.8502% | 否 |
| and-single-early-distinct / 2 | 178.915125 | 179.263917 | 0.348792 | 0.1949% | 否 |
| and-single-early-distinct / 3 | 172.313250 | 186.326083 | 14.012833 | 8.1322% | 否 |
| or-single-middle-rows / 1 | 106.235583 | 110.880833 | 4.645250 | 4.3726% | 否 |
| or-single-middle-rows / 2 | 110.439417 | 113.490542 | 3.051125 | 2.7627% | 否 |
| or-single-middle-rows / 3 | 112.336500 | 118.084583 | 5.748083 | 5.1168% | 否 |
| or-single-middle-distinct / 3 | 150.031458 | 178.125208 | 28.093750 | 18.7252% | 是 |
| and-single-middle-rows / 1 | 93.249625 | 97.923583 | 4.673958 | 5.0123% | 否 |
| and-single-middle-rows / 2 | 95.736666 | 100.075167 | 4.338501 | 4.5317% | 否 |
| and-single-middle-rows / 3 | 94.594875 | 100.775583 | 6.180708 | 6.5339% | 否 |
| and-single-middle-distinct / 1 | 149.779958 | 167.808667 | 18.028709 | 12.0368% | 否 |
| and-single-middle-distinct / 2 | 154.873084 | 155.725542 | 0.852458 | 0.5504% | 否 |
| and-single-middle-distinct / 3 | 153.620417 | 169.369625 | 15.749208 | 10.2520% | 否 |
| or-single-late-rows / 1 | 146.875667 | 159.323750 | 12.448083 | 8.4753% | 否 |
| or-single-late-rows / 2 | 145.064167 | 163.352459 | 18.288292 | 12.6070% | 否 |
| or-single-late-rows / 3 | 150.269250 | 164.080000 | 13.810750 | 9.1907% | 否 |
| or-single-late-distinct / 1 | 148.722500 | 160.449708 | 11.727208 | 7.8853% | 否 |
| or-single-late-distinct / 2 | 132.763458 | 144.779875 | 12.016417 | 9.0510% | 否 |
| or-single-late-distinct / 3 | 146.996458 | 162.933875 | 15.937417 | 10.8420% | 否 |
| and-single-late-rows / 1 | 148.413750 | 160.190625 | 11.776875 | 7.9352% | 否 |
| and-single-late-rows / 2 | 146.516333 | 163.887708 | 17.371375 | 11.8563% | 否 |
| and-single-late-rows / 3 | 149.648584 | 161.517250 | 11.868666 | 7.9310% | 否 |
| and-single-late-distinct / 1 | 146.992542 | 162.981167 | 15.988625 | 10.8772% | 否 |
| and-single-late-distinct / 2 | 146.842000 | 163.869458 | 17.027458 | 11.5958% | 否 |
| and-single-late-distinct / 3 | 148.364958 | 163.866375 | 15.501417 | 10.4482% | 否 |
| or-few-early-late-rows / 3 | 13.739458 | 15.096458 | 1.357000 | 9.8767% | 否 |
| or-few-early-late-distinct / 1 | 145.237459 | 163.759709 | 18.522250 | 12.7531% | 否 |
| or-few-early-late-distinct / 2 | 142.602750 | 161.505083 | 18.902333 | 13.2552% | 否 |
| or-few-early-late-distinct / 3 | 150.194125 | 158.910625 | 8.716500 | 5.8035% | 否 |
| or-few-early-middle-rows / 2 | 15.329584 | 16.143000 | 0.813416 | 5.3062% | 否 |
| or-few-early-middle-rows / 3 | 14.903792 | 16.512958 | 1.609166 | 10.7970% | 否 |
| or-few-early-middle-distinct / 1 | 144.868250 | 159.120000 | 14.251750 | 9.8377% | 否 |
| or-few-early-middle-distinct / 2 | 145.522459 | 160.613125 | 15.090666 | 10.3700% | 否 |
| or-few-early-middle-distinct / 3 | 151.583167 | 152.941875 | 1.358708 | 0.8963% | 否 |
| or-broad-all-rows / 1 | 5.159417 | 5.729208 | 0.569791 | 11.0437% | 否 |
| or-broad-all-rows / 3 | 5.956750 | 6.051708 | 0.094958 | 1.5941% | 否 |
| and-broad-all-distinct / 1 | 982.674208 | 999.307958 | 16.633750 | 1.6927% | 否 |
| and-broad-all-distinct / 2 | 983.731542 | 1003.665000 | 19.933458 | 2.0263% | 否 |
| and-broad-all-distinct / 3 | 989.546291 | 1019.198375 | 29.652084 | 2.9965% | 否 |
| mixed-four-few-rows / 1 | 210.399375 | 271.733125 | 61.333750 | 29.1511% | 是 |
| mixed-four-few-rows / 3 | 312.862792 | 720.623958 | 407.761166 | 130.3323% | 是 |
| mixed-four-few-distinct / 2 | 38626.091875 | 38848.919833 | 222.827958 | 0.5769% | 否 |
| and-zero-disjoint-graphs-rows / 1 | 146.672041 | 163.900041 | 17.228000 | 11.7459% | 否 |
| and-zero-disjoint-graphs-rows / 2 | 147.624083 | 163.223041 | 15.598958 | 10.5667% | 否 |
| and-zero-disjoint-graphs-rows / 3 | 148.010291 | 165.355541 | 17.345250 | 11.7189% | 否 |
| and-zero-disjoint-graphs-distinct / 1 | 145.451916 | 165.964417 | 20.512501 | 14.1026% | 否 |
| and-zero-disjoint-graphs-distinct / 2 | 146.894541 | 167.120583 | 20.226042 | 13.7691% | 否 |
| and-zero-disjoint-graphs-distinct / 3 | 150.394084 | 153.386167 | 2.992083 | 1.9895% | 否 |
| or-four-broad-rows / 1 | 33.529750 | 35.647583 | 2.117833 | 6.3163% | 否 |
| or-four-broad-rows / 2 | 33.106458 | 35.654958 | 2.548500 | 7.6979% | 否 |
| or-four-broad-rows / 3 | 36.510625 | 37.502875 | 0.992250 | 2.7177% | 否 |
| or-four-broad-distinct / 1 | 149.365542 | 185.582709 | 36.217167 | 24.2473% | 是 |
| or-four-broad-distinct / 2 | 150.640750 | 167.722459 | 17.081709 | 11.3394% | 否 |
| or-four-broad-distinct / 3 | 152.262625 | 192.266333 | 40.003708 | 26.2728% | 是 |
| or-four-single-early-rows / 1 | 14.927000 | 17.209500 | 2.282500 | 15.2911% | 是 |
| or-four-single-early-rows / 2 | 15.384958 | 16.587833 | 1.202875 | 7.8185% | 否 |
| or-four-single-early-rows / 3 | 17.159000 | 17.729125 | 0.570125 | 3.3226% | 否 |
| or-four-single-early-distinct / 1 | 146.407833 | 164.092125 | 17.684292 | 12.0788% | 否 |
| or-four-single-early-distinct / 2 | 149.163375 | 156.733750 | 7.570375 | 5.0752% | 否 |
| or-four-single-early-distinct / 3 | 151.997458 | 163.008875 | 11.011417 | 7.2445% | 否 |
| or-four-single-middle-rows / 1 | 87.190459 | 101.893083 | 14.702624 | 16.8627% | 是 |
| or-four-single-middle-rows / 2 | 90.530833 | 101.823542 | 11.292709 | 12.4739% | 否 |
| or-four-single-middle-rows / 3 | 93.144000 | 94.802375 | 1.658375 | 1.7804% | 否 |
| or-four-single-middle-distinct / 1 | 148.228208 | 164.116334 | 15.888126 | 10.7187% | 否 |
| or-four-single-middle-distinct / 2 | 140.102583 | 164.756417 | 24.653834 | 17.5970% | 是 |
| or-four-single-middle-distinct / 3 | 148.291709 | 165.038625 | 16.746916 | 11.2932% | 否 |
| or-four-single-late-rows / 1 | 147.124958 | 165.750583 | 18.625625 | 12.6597% | 否 |
| or-four-single-late-rows / 2 | 138.388833 | 161.366666 | 22.977833 | 16.6038% | 是 |
| or-four-single-late-rows / 3 | 150.904500 | 163.219458 | 12.314958 | 8.1608% | 否 |
| or-four-single-late-distinct / 1 | 145.175417 | 165.937417 | 20.762000 | 14.3013% | 否 |
| or-four-single-late-distinct / 2 | 148.621042 | 167.756209 | 19.135167 | 12.8751% | 否 |
| or-four-single-late-distinct / 3 | 149.166250 | 166.611959 | 17.445709 | 11.6955% | 否 |
| or-four-few-early-late-rows / 1 | 14.094875 | 17.515833 | 3.420958 | 24.2709% | 是 |
| or-four-few-early-late-rows / 2 | 15.508583 | 16.604708 | 1.096125 | 7.0679% | 否 |
| or-four-few-early-late-distinct / 1 | 146.857375 | 164.358667 | 17.501292 | 11.9172% | 否 |
| or-four-few-early-late-distinct / 2 | 149.372042 | 162.376375 | 13.004333 | 8.7060% | 否 |
| or-four-few-early-late-distinct / 3 | 147.665000 | 162.512208 | 14.847208 | 10.0547% | 否 |
| or-four-all-rows / 1 | 12.576708 | 16.136750 | 3.560042 | 28.3066% | 是 |
| or-four-all-rows / 2 | 14.642667 | 15.411042 | 0.768375 | 5.2475% | 否 |
| or-four-all-rows / 3 | 14.226584 | 16.138708 | 1.912124 | 13.4405% | 否 |

共有86个变慢观测、40处work变化、0处其他TSV字段变化。完整108对全部字段差异保留于JSON；没有因未超过尺度而删除负向观测。参考尺度仅描述重复程度，不是新设v3 acceptance gate。

## 校验和解释边界

六份catalog/workloads、query SHA、完整actual rows digest、每行provenance、rowCount及summary唯一latency均核对。六份编译adapter class相同；graph-content-before/after逐字节相同且六份共同hash为 `1eb97cabd94cb61459610d1360f7955f9a5cf1a7ada6782f125492729be129c6`。脚本在所有录制完成后核对两份JAR当前hash，匹配成对运行记录。没有重扫大图文件，也没有启动Java。

工作量变化可来自不同执行路径或并发提前停止，单凭计数不能逐项归因；不能把work变化当作CPU或速度预测。没有v3的CPU/heap/RSS对应gate。全部36条latency/work表见 [README](v3-pairs/README.md)，精确结果见 [JSON](independent-v3-audit.json)。
