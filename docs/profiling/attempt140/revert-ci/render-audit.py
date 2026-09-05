from pathlib import Path
import json,hashlib,re,collections
R=Path(__file__).parent
def j(p):return json.loads(p.read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
a=j(R/'ci-audit.json');term=j(R/'terminal-receipt.json');rows=a['globalWide']['alignedRows'];byid={}
for x in rows:byid.setdefault(x['id'],[]).append(x)
table=['# 回退 CI：全部34查询原值','', '每条3组 base→revert。† 表示单次同时超过 >15% 与 >1 ms；本次无同查询两次越界。', '', '| 查询 ID | Pair1 ms | Pair2 ms | Pair3 ms |','|---|---:|---:|---:|']
for q,rs in byid.items():table.append('| '+q+' | '+' | '.join(f"{x['baseNanos']/1e6:.6f}→{x['candidateNanos']/1e6:.6f}"+(' †' if x['above15PercentAnd1ms'] else '') for x in rs)+' |')
(R/'global-full-table.md').write_text('\n'.join(table)+'\n')
report='''# Attempt 140 显式回退 CI 终态审计

Exact head `b94b8caa8dea10d1d2ddb74a0a0c39a3ab5351f0`：unit
[33995836241](https://github.com/johnsonlee/graphite/actions/runs/33995836241) **success**，benchmark
[33995836230](https://github.com/johnsonlee/graphite/actions/runs/33995836230) **failure**。
40 个 benchmark jobs：36 success、2 failure、2 legacy-disabled skipped。
失败 job 仅 `global-wide-pressure-evidence` 与 `benchmark-regression-gate`。

**此次实际阻断是每组 P95 必须严格进步，而不是重复 regression gate 失败。**
Global `regressionPassed=true`，`progressAchieved=false`，`targetAchieved=false`，
`iterationPassed=false`，`requireTarget=false`。第 1、3 组未比 last accepted frozen main 改善，
因此在不要求最终10x的 iteration gate 下仍然失败，不能把它只写成“未达10x”。

## Global 原值与独立门槛重算

| Pair / 顺序 | main → revert P95 ms | CPU s | heap bytes | RSS bytes |
|---|---:|---:|---:|---:|
'''
for p in a['globalWide']['pairs']:
 b,c=p['sides']['base'],p['sides']['candidate'];report+=f"| {p['pair']} / {p['order']} | {b['p95LatencyNanos']/1e6:.6f}→{c['p95LatencyNanos']/1e6:.6f} | {b['processCpuNanos']/1e9:.2f}→{c['processCpuNanos']/1e9:.2f} | {int(b['peakUsedHeapBytes'])}→{int(c['peakUsedHeapBytes'])} | {int(b['peakResidentSetBytes'])}→{int(c['peakResidentSetBytes'])} |\n"
report+='''
六份原 TSV 的 nearest-rank 第33位与 JMH/status P95 完全一致；每轮分母是34条不同查询，
不是某条查询重复样本的P95。所有P95均为 wrapped case-insensitive DISTINCT dense。
第1组 **+148.1979% / +177.712843 ms**；第3组也变慢，虽未超过重复 regression 的相对界限，
仍违反单独要求的严格进步。没有将失败界限放宽或重跑。

独立重算完整102对：**45个变慢观测、10个单次双界限越界、0个同查询重复越界**。
只有第1组 aggregate P95 / DISTINCT shape P95 超过相对>15%且绝对>1ms，未重复；
CPU/heap/RSS三组均未超过15%资源界限。故原 regression-only PASS 与 strict-progress FAIL
并不矛盾。全部负向原值见 [全34表](global-full-table.md)，未删去单次越界。

204个完整14字段 correctness 签名逐行匹配冻结 oracle；三组对应查询的
hitGraphIds、inputSourceCount、accessedGraphIds/Count、graphWorkUnits、parallelScanCount、
indexLookupCount、executionPath 均相同。本次是原签名/摘要核验，没有重新执行查询或解码原始完整结果行。
[Global 原报告](global-wide/global-wide-report.md) / [状态](global-wide/global-wide-status.json)。

## Method 与 routing

12个 Method compatibility shards 及 aggregate gate 均 success，method-level JMH、large-corpus
亦 success。这里来自 exact-head [终态 job 记录](method-job-results.json)；没有终态失败 shard，
也未声称重新审计所有Method原始JMH样本。两项 legacy external-evidence skipped 不是通过的性能比较。

Routing cold / warm / startup-prepared **全部通过**。六份 TSV 的6,822个完整签名与1,137条
独立 oracle 一致。独立重算每状态576条graph-id和192条request-selected的P50/P95，
所有对应latency行与发布status逐项一致；2/8/64图宽度P50/P95与缩放界限亦核验。
这些界限通过不代表每行都变快，不将139回退的startup-prepared失败沿用到本次。
[Routing 原报告](routing/graph-routing-report.md) / [完整状态](routing/graph-routing-status.json)。

## 身份、范围与决定

Global+routing evidence manifest共51个文件SHA全匹配；base/candidate revision、图清单、
fixture provenance与重复生成语义hash均一致。精确head comparator内容SHA匹配两份artifact的
provenance，见 [源码绑定receipt](comparator-source-receipt.json)。两边记录的JAR内容hash同为
`ddfed3136e1c443d1cbd1cc96285de133fb84ba2a6a252b43326f82020250275`。
核验的是托管artifact记录和哈希链，未重新取得hosted原JAR或图文件。
**记录的二进制相同不豁免strict-progress失败，也不能单凭它解释为噪声或指定失败原因。**

原候选140已经因v3 `mixed-four-few-rows`两组重复越界被永久拒绝，未进入候选hosted CI。
本次只覆盖其显式生产回退head，不改变原拒绝决定；CallSite池与最终全局P95 10x目标未完成。

约55秒一次的只读监测共16次快照后正常退出；没有workflow dispatch/rerun、源码修改、push、
PR修改、Java/build/profiler或本地性能任务。
[dispatch receipt](dispatch-receipt.json)、[终态receipt](terminal-receipt.json)、
[unit快照](terminal-unit.json)、[benchmark快照](terminal-benchmark.json)、
[下载凭据](download-receipt.json) 保留exact SHA。
[独立复算JSON](ci-audit.json) / [脚本](recompute-audit.py) 保存原值、完整102对和门槛推导。
审计一致说明证据吻合，不代表CI通过。
'''
(R/'ci-audit.md').write_text(report)
readme='''# Attempt 140 回退 CI

Head `b94b8caa8dea10d1d2ddb74a0a0c39a3ab5351f0`：unit **success**，benchmark **failure**。

本次 global regression-only **PASS**，实际失败的是 **P95每组严格进步**：

| Pair | main → revert P95 ms | 严格进步 |
|---|---:|---|
| 1 | 119.915932→297.628775 | 否 |
| 2 | 127.839829→118.118335 | 是 |
| 3 | 110.626223→117.182906 | 否 |

没有同一查询重复越过>15%且>1ms，也没有CPU/heap/RSS越界；仍不能豁免第1/3组进步失败。
`requireTarget=false`，所以实际阻断不是仅未达最终10x。
12个Method shards、Method aggregate、method-level JMH、large-corpus和routing三状态均成功。
原候选140的v3拒绝保持不变，此处是回退head，不能写成候选通过。

[完整审计](ci-audit.md) / [JSON](ci-audit.json)、[全34查询配对原值](global-full-table.md)、
[终态receipt](terminal-receipt.json)、[状态receipt](status-receipt.json)、
[Global原状态](global-wide/global-wide-status.json)、[Routing原状态](routing/graph-routing-status.json)。

204个global与6,822个routing完整签名核验一致，51个原文件hash通过。
记录的两边JAR内容hash相同不豁免失败，不证明失败原因。
没有重跑CI或新增Java/build/性能任务。
[下载凭据](download-receipt.json)、[审计文件hash](audit-receipt.json)、[复算脚本](recompute-audit.py)。
复算脚本依赖本临时目录完整原TSV/JMH文件；精简归档后应在完整目录执行。
'''
(R/'README.md').write_text(readme)
status={'head':term['head'],'status':'complete','runs':term['runs'],'benchmarkFailedJobs':a['workflowRuns']['33995836230']['failedJobs'],'globalRegressionPassed':True,'strictProgressEveryPair':False,'insufficientProgressPairs':[1,3],'targetAchieved':False,'routingAllStatesPassed':True,'allMethodShardsAndGatePassed':True,'monitorExitCode':0,'candidate140RejectionUnchanged':True,'sourceEqualityDoesNotWaiveFailure':True}
(R/'status-receipt.json').write_text(json.dumps(status,indent=2)+'\n')
for name in ['README.md','ci-audit.md']:
 for target in re.findall(r'\]\(([^)]+)\)',(R/name).read_text()):
  if not target.startswith('https://') and target!='audit-receipt.json':assert (R/target).is_file(),target
files=[p for p in R.rglob('*') if p.is_file() and not p.name.startswith('poll-') and p.name!='audit-receipt.json']
(R/'audit-receipt.json').write_text(json.dumps({'head':term['head'],'status':'complete','allMarkdownLinksValid':True,'newMeasurements':False,'candidate140RejectionUnchanged':True,'filesSha256':{str(p.relative_to(R)):sha(p) for p in files}},indent=2)+'\n')
print(json.dumps({'status':'complete','stableEvidenceFileCount':len(files),'report':str(R/'ci-audit.md')},indent=2))
