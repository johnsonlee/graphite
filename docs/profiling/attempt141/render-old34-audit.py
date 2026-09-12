from pathlib import Path
import json,hashlib
r=Path(__file__).resolve().parent;d=json.loads((r/'independent-old34-audit.json').read_text());after=json.loads((r/'old34-input-after-receipt.json').read_text());reference=Path(after['reference']);assert hashlib.sha256(reference.read_bytes()).hexdigest()==after['referenceSha256'];g=json.loads(reference.read_text());assert len(g)==after['graphs']==64 and sum(len(x['files']) for x in g)==after['files']==1088 and after['sameAsControl']
for i in range(1,4):
 for side in ['base','candidate']:
  cmd=json.loads((r/'old34-pairs'/f'{side}-global-wide-{i}-command.json').read_text())
  for flag,want in [('-wi','0'),('-i','1'),('-f','1')]:assert cmd[cmd.index(flag)+1]==want
  assert cmd[2]==str(r/'candidate-jmh.jar') if side=='candidate' else cmd[2]=='/private/tmp/graphite-next-baseline.T2FTs9/graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar'
  assert 'agentlib' not in ' '.join(cmd) and 'StartFlightRecording' not in ' '.join(cmd)
assert json.loads((r/'independent-comparator-status.json').read_text())==json.loads((r/'old34-pairs/global-wide-status.json').read_text())
d['originalComparatorIndependentFullStatusEquality']=True;d['independentComparatorReceipt']='independent-comparator-receipt.json';d['inputAfterReceipt']=after;d['inputAfterReferenceHashAndInventoryVerified']=True;d['pairedCommandsExactJarAndColdZeroWarmupSingleForkVerified']=True
(r/'independent-old34-audit.json').write_text(json.dumps(d,indent=2)+'\n')
lines=['# Attempt 141 原34独立终态审计','','**拒绝 141。** 全部六轮完成且 204 个完整14字段 oracle 签名正确；pair1 CPU 单组违反15%资源界限，pair1/2 的全局 P95 没有严格进步。不能用 pair3 改善抵消。原 comparator 由离线 Node 独立重算，完整 status 深相等，exit1 保留；没有 Java、查询、构建或性能重跑。','','| Pair / 顺序 | main → candidate P95 ms | speedup | CPU s | heap GiB | RSS GiB | strict P95进步 |','|---|---:|---:|---:|---:|---:|---|']
for p in d['pairs']:
 b,c=p['sides']['base'],p['sides']['candidate'];lines.append(f"| {p['pair']} / {p['order']} | {b['p95LatencyNanos']/1e6:.6f}→{c['p95LatencyNanos']/1e6:.6f} | {p['p95Speedup']:.6f}x | {b['processCpuNanos']/1e9:.6f}→{c['processCpuNanos']/1e9:.6f} | {b['peakUsedHeapBytes']/2**30:.6f}→{c['peakUsedHeapBytes']/2**30:.6f} | {b['peakResidentSetBytes']/2**30:.6f}→{c['peakResidentSetBytes']/2**30:.6f} | {p['strictP95Improvement']} |")
p1=d['pairs'][0];lines+=['',f"pair1 CPU 增加 {p1['resourceIncreasePercent']['processCpuNanos']:.6f}%；资源界限是任一单组 >15% 即失败，并不要求重复两次。Heap/RSS 没有超限。P95 是每轮34个查询的 nearest-rank第33位，不是某条 query 三次样本的 P95。",'','各组 P95 对应查询：']
for p in d['pairs']:lines.append(f"- Pair {p['pair']}: main {', '.join(p['sides']['base']['p95QueryIds'])}; candidate {', '.join(p['sides']['candidate']['p95QueryIds'])}.")
lines+=['',f"保留全部 {len(d['slowerObservations'])}/102 个变慢观测；没有相同 query 两次同时 >15% 且 >1ms，也没有 aggregate/wrapped P95 的重复双阈值失败。这不豁免 strict progress 或资源失败。全部非 latency TSV 字段相同（包括 work、命中来源、访问来源和扫描/索引计数）；每轮总 work={d['pairs'][0]['sides']['base']['graphWorkUnits']:,}，不能据此断言 CPU 改变来自哪一个指令。",'','输入绑定：原冻结 oracle 与 140 使用的 oracle 逐字节相同；34条完整签名、JMH totals、正 latency、P50/P95/max、每轮 CPU/heap/RSS 和 candidate 2+2 worker topology 均核对。命令固定 C/B,B/C,C/B、原64图 cold/零 warmup/单 fork/gc profiler；base/candidate 记录 hash 与构建凭据一致。Root 已重扫1088个输入文件并记录 sameAsControl；独立验证该 reference JSON 的 hash、64图/1088文件 inventory。本审计不冒称又重 hash 物理图/JAR。','','本轮不运行 v3 配对或候选 CI，候选最终未接受，target10x=false。原样保留 comparator errors/targetErrors；拒绝不改写为仅未达到最终目标。','','## 全34查询配对表','','| 查询 ID | Pair1 main→candidate ms | Pair2 main→candidate ms | Pair3 main→candidate ms |','|---|---:|---:|---:|']
for q in d['alignedRows']:
 cells=[f"{p['baseNanos']/1e6:.6f}→{p['candidateNanos']/1e6:.6f}"+(' †' if p['above15PercentAnd1ms'] else '') for p in q['pairs']];lines.append('| '+q['id']+' | '+' | '.join(cells)+' |')
lines+=['','† 单次同时 >15% 与 >1ms；所有绝对/相对差值、单次标记和非 latency 字段比较保留在 JSON，不删除未重复的退化。','', '[完整独立原值](independent-old34-audit.json) · [复算](independent-old34-audit.py) · [原 comparator 独立重算](independent-comparator-status.json) · [comparator receipt](independent-comparator-receipt.json)']
(r/'independent-old34-audit.md').write_text('\n'.join(lines)+'\n')
files=['independent-old34-audit.py','independent-old34-audit.json','independent-old34-audit.md','render-old34-audit.py','independent-comparator-status.json','independent-comparator-report.md','independent-comparator-receipt.json']
receipt={'terminalAuditPassed':True,'candidateAccepted':False,'candidateDecision':'rejected-old34','files':[{'path':name,'bytes':(r/name).stat().st_size,'sha256':hashlib.sha256((r/name).read_bytes()).hexdigest()} for name in files],'noJavaBuildQueryPerformanceRerun':True}
(r/'independent-old34-audit-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps({'candidateDecision':receipt['candidateDecision'],'files':files,'allCorrectness':204,'slower':len(d['slowerObservations']),'cpuPercent':p1['resourceIncreasePercent']['processCpuNanos']}))
