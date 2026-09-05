# Attempt 141 原34独立终态审计

**拒绝 141。** 全部六轮完成且 204 个完整14字段 oracle 签名正确；pair1 CPU 单组违反15%资源界限，pair1/2 的全局 P95 没有严格进步。不能用 pair3 改善抵消。原 comparator 由离线 Node 独立重算，完整 status 深相等，exit1 保留；没有 Java、查询、构建或性能重跑。

| Pair / 顺序 | main → candidate P95 ms | speedup | CPU s | heap GiB | RSS GiB | strict P95进步 |
|---|---:|---:|---:|---:|---:|---|
| 1 / candidate-base | 41.608625→118.184625 | 0.352065x | 1.476806→1.819397 | 4.387891→3.931302 | 5.040817→4.452118 | False |
| 2 / base-candidate | 48.693708→55.151042 | 0.882915x | 1.492257→1.572952 | 3.938361→4.098012 | 4.455643→4.603470 | False |
| 3 / candidate-base | 61.887459→46.230833 | 1.338662x | 1.644617→1.519967 | 4.389676→4.020625 | 4.894363→4.528976 | True |

pair1 CPU 增加 23.198105%；资源界限是任一单组 >15% 即失败，并不要求重复两次。Heap/RSS 没有超限。P95 是每轮34个查询的 nearest-rank第33位，不是某条 query 三次样本的 P95。

各组 P95 对应查询：
- Pair 1: main global-wide-wrapped-case-insensitive-distinct-dense; candidate global-wide-wrapped-case-insensitive-distinct-dense.
- Pair 2: main global-wide-wrapped-case-insensitive-distinct-dense; candidate global-wide-wrapped-case-insensitive-distinct-dense.
- Pair 3: main global-wide-wrapped-case-insensitive-distinct-dense; candidate global-wide-wrapped-case-insensitive-distinct-dense.

保留全部 57/102 个变慢观测；没有相同 query 两次同时 >15% 且 >1ms，也没有 aggregate/wrapped P95 的重复双阈值失败。这不豁免 strict progress 或资源失败。全部非 latency TSV 字段相同（包括 work、命中来源、访问来源和扫描/索引计数）；每轮总 work=58,071,626，不能据此断言 CPU 改变来自哪一个指令。

输入绑定：原冻结 oracle 与 140 使用的 oracle 逐字节相同；34条完整签名、JMH totals、正 latency、P50/P95/max、每轮 CPU/heap/RSS 和 candidate 2+2 worker topology 均核对。命令固定 C/B,B/C,C/B、原64图 cold/零 warmup/单 fork/gc profiler；base/candidate 记录 hash 与构建凭据一致。Root 已重扫1088个输入文件并记录 sameAsControl；独立验证该 reference JSON 的 hash、64图/1088文件 inventory。本审计不冒称又重 hash 物理图/JAR。

本轮不运行 v3 配对或候选 CI，候选最终未接受，target10x=false。原样保留 comparator errors/targetErrors；拒绝不改写为仅未达到最终目标。

## 全34查询配对表

| 查询 ID | Pair1 main→candidate ms | Pair2 main→candidate ms | Pair3 main→candidate ms |
|---|---:|---:|---:|
| global-wide-four-properties-zero | 273.169292→240.580000 | 254.200291→249.464708 | 255.149000→247.220750 |
| global-wide-four-properties-targeted | 9.932791→9.421583 | 10.139083→12.711583 † | 9.515208→9.991458 |
| global-wide-four-properties-dense | 3.993583→4.254583 | 4.259625→4.401542 | 3.985083→4.200959 |
| global-wide-class-pair-zero | 2.052542→1.876250 | 1.834625→2.231709 | 2.068583→2.324292 |
| global-wide-class-pair-targeted | 4.064334→4.437459 | 4.088958→4.553292 | 4.278083→4.193958 |
| global-wide-class-pair-dense | 1.064459→1.068000 | 1.097958→1.000875 | 0.948125→1.084291 |
| global-wide-name-pair-zero | 1.685625→1.973833 | 1.668250→1.784333 | 1.945875→1.678667 |
| global-wide-name-pair-targeted | 4.295250→4.069875 | 4.325792→4.867042 | 4.270125→3.950917 |
| global-wide-name-pair-dense | 0.840459→0.793667 | 0.812042→0.856542 | 0.823500→0.791833 |
| global-wide-caller-class-zero | 1.431625→1.435458 | 1.424292→2.121000 | 1.345167→1.908000 |
| global-wide-caller-class-targeted | 2.870042→2.859667 | 2.453000→3.324000 | 2.394375→2.723083 |
| global-wide-caller-class-dense | 0.542458→0.698583 | 0.763042→0.663708 | 0.560333→0.623000 |
| global-wide-callee-class-zero | 1.128042→1.157500 | 1.432750→1.587958 | 1.436958→1.207417 |
| global-wide-callee-class-targeted | 1.903792→2.134167 | 2.078416→2.574375 | 2.551792→2.020000 |
| global-wide-callee-class-dense | 0.437875→0.529167 | 0.603417→0.480375 | 0.539292→0.493250 |
| global-wide-provenance-zero | 0.889792→1.321167 | 1.107292→1.086000 | 1.337709→1.056375 |
| global-wide-provenance-targeted | 2.648042→3.153708 | 3.265333→3.090916 | 3.205375→3.025459 |
| global-wide-provenance-dense | 0.844500→0.813917 | 0.825250→0.870459 | 0.867791→0.808250 |
| global-wide-aliased-zero | 0.901125→0.987542 | 0.929000→0.929709 | 0.964709→0.893708 |
| global-wide-aliased-targeted | 3.101125→3.383042 | 3.236250→3.263583 | 3.416292→3.165667 |
| global-wide-aliased-dense | 0.749917→0.758750 | 0.719583→0.744917 | 0.777417→0.730167 |
| global-wide-parameterized-zero | 1.045500→1.022167 | 0.958709→1.022041 | 0.953041→1.144750 |
| global-wide-parameterized-targeted | 2.181500→2.203333 | 2.148625→2.249125 | 2.274625→2.278500 |
| global-wide-parameterized-dense | 0.808042→0.719834 | 0.714208→0.750834 | 0.868084→0.718209 |
| global-wide-wrapped-case-insensitive-zero | 1.077709→1.079958 | 0.989708→1.142917 | 1.164667→1.058625 |
| global-wide-wrapped-case-insensitive-targeted | 2.439125→2.222416 | 2.091000→2.367917 | 2.310750→2.168667 |
| global-wide-wrapped-case-insensitive-dense | 1.534291→1.365208 | 1.337083→1.479625 | 1.363334→1.324208 |
| global-wide-wrapped-case-insensitive-distinct-zero | 3.561375→3.453417 | 3.194375→3.193834 | 3.515833→3.097750 |
| global-wide-wrapped-case-insensitive-distinct-targeted | 20.099625→39.705125 † | 37.971042→31.430625 | 35.066583→36.270000 |
| global-wide-wrapped-case-insensitive-distinct-dense | 41.608625→118.184625 † | 48.693708→55.151042 | 61.887459→46.230833 |
| global-wide-distribution-broad-all-64 | 0.757417→0.802875 | 0.763166→0.824583 | 0.804291→0.797417 |
| global-wide-distribution-localized-early | 1.165541→1.235500 | 1.169917→1.202125 | 1.201292→1.185458 |
| global-wide-distribution-localized-late | 4.150542→3.965250 | 3.846000→4.097333 | 4.067458→4.027083 |
| global-wide-distribution-localized-middle | 2.687500→2.804542 | 2.538167→2.963792 | 2.749666→2.696250 |

† 单次同时 >15% 与 >1ms；所有绝对/相对差值、单次标记和非 latency 字段比较保留在 JSON，不删除未重复的退化。

[完整独立原值](independent-old34-audit.json) · [复算](independent-old34-audit.py) · [原 comparator 独立重算](independent-comparator-status.json) · [comparator receipt](independent-comparator-receipt.json)
