from pathlib import Path
from collections import Counter
import json,csv,hashlib,base64,re
root=Path(__file__).parent
sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
def readj(p):return json.loads(Path(p).read_text())
def canonical(x):
 if x is None:return 'null'
 if isinstance(x,str):
  replacements={'"':'\\"','\\':'\\\\','\n':'\\n','\r':'\\r','\t':'\\t'}
  return '"'+''.join(replacements.get(c,('\\u%04x'%ord(c)) if ord(c)<32 else c) for c in x)+'"'
 if isinstance(x,list):return '['+','.join(canonical(v) for v in x)+']'
 if isinstance(x,dict):return '{'+','.join(canonical(k)+':'+canonical(v) for k,v in x.items())+'}'
 raise AssertionError(type(x))
paths={(pair,side):root/f'v3-pair-{pair}-{side}' for pair in range(1,4) for side in ['base','candidate']}
assert all((p/'run.json').exists() and readj(p/'run.json').get('status')=='complete' for p in paths.values()),'Six complete runs required; no report written'
receipt=readj(root/'v3-pairs-receipt.json'); assert receipt['status']=='complete' and receipt['verifiedObservations']==216 and receipt['notP95'] is True
catalog=readj(paths[1,'base']/'catalog.json'); ids=[q['id'] for q in catalog['queries']]; assert len(ids)==len(set(ids))==36
assert catalog['schema']=='graphite-wide-query-oracle-v3'; byid={q['id']:q for q in catalog['queries']}; logical={q['id']:q for q in catalog['logicalCases']}; assert len(logical)==18
columns=['n.caller_class','n.caller_name','n.callee_class','n.callee_name']; verified=[]; observations={}; totalrows=0; inventoryHash=None; classes=None
for (pair,side),p in paths.items():
 r=readj(p/'run.json'); assert (r['requestedForks'],r['completedVerifiedForks'],r['queryCount'])==(1,1,36)
 assert r['resetMode']=='per-query-cold' and r['performanceGate'] is False
 assert r['inputs']['runtimeJar']['sha256']==receipt['jarHashes'][side]
 assert r['inputs']['trustedJar']['sha256']==catalog['jarSha256']==receipt['jarHashes']['base']
 for key in ['manifest','catalog','workloads','adapterSource','runnerSource','verifierSource']:
  assert sha(r['inputs'][key]['path'])==r['inputs'][key]['sha256'],(pair,side,key)
 assert sha(p/'catalog.json')==r['inputs']['catalog']['sha256'] and sha(p/'workloads.tsv')==r['inputs']['workloads']['sha256']
 assert (p/'graph-content-before.json').read_bytes()==(p/'graph-content-after.json').read_bytes()
 ih=sha(p/'graph-content-before.json'); assert ih==r['graphContentSha256']; inventoryHash=inventoryHash or ih; assert ih==inventoryHash
 inv=readj(p/'graph-content-before.json');assert len(inv)==64 and [g['id'] for g in inv]==catalog['inputGraphs']; assert all(len(g['files'])>0 for g in inv)
 actualclasses={str(f.relative_to(p/'classes')):sha(f) for f in (p/'classes').rglob('*.class')}; assert actualclasses==r['compiledClasses']; classes=classes or actualclasses; assert actualclasses==classes
 workload=list(csv.DictReader((p/'workloads.tsv').open(),delimiter='\t')); assert [w['id'] for w in workload]==ids
 observed=list(csv.DictReader((p/'fork-001.tsv').open(),delimiter='\t')); full=[json.loads(x) for x in (p/'fork-001-rows.jsonl').read_text().splitlines()]; summaries=r['queries']; assert len(observed)==len(full)==len(summaries)==36
 assert [x['id'] for x in observed]==[x['id'] for x in full]==[x['id'] for x in summaries]==ids
 for spec,w,obs,actual,summary in zip(catalog['queries'],workload,observed,full,summaries):
  assert base64.b64decode(w['queryBase64']).decode()==spec['query']; assert w['distinct']==str(spec['distinct']).lower()
  assert obs['workloadIdentity']==hashlib.sha256(spec['query'].encode()).hexdigest()
  assert actual['columns']==columns and actual['rows']==spec['expectedRows']; totalrows+=len(actual['rows'])
  normalized=[{'values':row['values'],'graphIds':row['graphIds']} for row in actual['rows']]
  assert obs['digest']==hashlib.sha256(canonical(normalized).encode()).hexdigest()
  assert int(obs['rowCount'])==len(actual['rows']) and obs['outcome']=='success' and obs['inputSourceCount']=='64' and obs['resetMode']=='per-query-cold'
  assert obs['projection']==('distinct-properties' if spec['distinct'] else 'properties')
  assert obs['hitGraphIds']==','.join(sorted({g for row in actual['rows'] for g in row['graphIds']}))
  value=int(obs['latencyNanos']);assert value>0 and summary['sampleCount']==1 and summary['latencyNanosInForkOrder']==[value] and summary['minLatencyNanos']==summary['maxLatencyNanos']==summary['medianLatencyNanos']==value and summary['empiricalP95LatencyNanos'] is None
  for k in ['graphWorkUnits','filteredNodeLimitFastPathExecutions','generalFallbackExecutions']:assert int(obs[k])>=0
  assert set(obs)==set(observed[0])
 observations[pair,side]={o['id']:o for o in observed}
 command=readj(p/'fork-001-command.json');cp=command[command.index('-cp')+1].split(':'); assert cp==[str(p/'classes'),r['inputs']['runtimeJar']['path']]
 assert command[-5:]==[r['inputs']['manifest']['path'],r['inputs']['workloads']['path'],str(p/'fork-001'),'all','per-query-cold']
 ref=readj(p/'fork-001-reference-check.json');assert ref['passed'] and ref['queryCount']==36 and ref['verifiedFullRowsAndProvenance'] and not ref['errors'] and ref['catalogSha256']==r['inputs']['catalog']['sha256']
 verified.append({'pair':pair,'side':side,'full36RowsVerified':True,'actualResultRows':sum(len(a['rows']) for a in full),'graphInventoryBeforeAfterEqual':True,'runtimeJarSha256':r['inputs']['runtimeJar']['sha256'],'fileHashes':{f:sha(p/f) for f in ['run.json','fork-001.tsv','fork-001-rows.jsonl','fork-001-command.json','fork-001-reference-check.json','graph-content-before.json','graph-content-after.json']}})
# Bind recorded immutable JAR identities; do not hash large JAR/graph files during the root's acceptance work.
build=readj(root/'build-receipt.json');assert receipt['jarHashes']['candidate']==build['jmhJarSha256'] and receipt['jarHashes']['base']==build['baselineJarSha256After']
old34=readj(root/'old34-pairs/local-progress.json');assert old34['regressionPassed'] and old34['strictProgressEveryPair']
expectedOrder=[(1,'candidate'),(1,'base'),(2,'base'),(2,'candidate'),(3,'candidate'),(3,'base')]
logged=[(int(m.group(1)),m.group(2)) for m in re.finditer(r'^VERIFIED (\d+) (base|candidate) 36$',(root/'v3-pairs.log').read_text(),re.M)];assert logged==expectedOrder
pure=[]
for name,lc in logical.items():
 if not name.startswith('or-four-'):continue
 assert lc['ast']==['or',0,1,2,3] and len(lc['terms'])==len(set(lc['terms']))==4 and all(lc['terms'])
 assert all(a not in b for i,a in enumerate(lc['terms']) for j,b in enumerate(lc['terms']) if i!=j)
 assert len(lc['termExclusiveMatchCounts'])==4 and all(v>0 for v in lc['termExclusiveMatchCounts'])
 positions=[i for i,c in enumerate(lc['perGraphMatchingCounts']) if c];assert positions==lc['advertisedHitGraphPositions'];assert [catalog['inputGraphs'][i] for i in positions]==lc['hitGraphIds']
 for suffix in ['rows','distinct']:
  q=byid[name+'-'+suffix];assert q['expectedHitGraphIds']==lc['hitGraphIds'] and q['totalMatches']==sum(lc['perGraphMatchingCounts'])
  where=q['query'].split(' WHERE ',1)[1].split(' RETURN ',1)[0];assert ' AND ' not in where and where.count(' OR ')==15
  for term in lc['terms']:
   assert all(where.count("toLower(coalesce(n."+prop+", '')) CONTAINS '"+term.replace("'","''")+"'")==1 for prop in ['caller_class','caller_name','callee_class','callee_name'])
 pure.append({'id':name,'terms':lc['terms'],'hitGraphPositions':positions,'hitGraphCount':len(positions),'matchingNodes':lc['totalMatches'],'exclusiveCounts':lc['termExclusiveMatchCounts']})
assert len(pure)==6
pairs=[];slower=[];workchanges=[];otherchanges=[];repeats=Counter()
for qid in ids:
 for pair in range(1,4):
  b=observations[pair,'base'][qid];c=observations[pair,'candidate'][qid]; bn=int(b['latencyNanos']);cn=int(c['latencyNanos']);material=cn*100>bn*115 and cn-bn>1000000
  changes={k:{'base':b[k],'candidate':c[k]} for k in b if b[k]!=c[k]}
  for key in ['id','family','shape','projection','workloadIdentity','outcome','rowCount','digest','hitGraphIds','inputSourceCount','resetMode']:assert b[key]==c[key],(qid,pair,key)
  row={'id':qid,'pair':pair,'order':'candidate-base' if pair%2 else 'base-candidate','baseLatencyNanos':bn,'candidateLatencyNanos':cn,'deltaNanos':cn-bn,'relativeIncreasePercent':(cn-bn)/bn*100,'exceedsReference15PercentAnd1ms':material,'baseWorkUnits':int(b['graphWorkUnits']),'candidateWorkUnits':int(c['graphWorkUnits']),'allChangedTsvFields':changes};pairs.append(row)
  if cn>bn:slower.append(row)
  if material:repeats[qid]+=1
  if b['graphWorkUnits']!=c['graphWorkUnits']:workchanges.append(row)
  if set(changes)-{'latencyNanos','graphWorkUnits'}:otherchanges.append(row)
repeated={q:n for q,n in repeats.items() if n>=2}
result={'correctnessAndIntegrityPassed':True,'supplementalPairedRejectionScreen':True,'notP95':True,'candidateAccepted':False,'localV3RejectionTriggered':bool(repeated),'localDecision':'reject' if repeated else 'eligible-for-exact-head-CI-only','verifiedObservations':216,'verifiedActualRows':totalrows,'graphInventorySha256':inventoryHash,'jarHashes':receipt['jarHashes'],'order':expectedOrder,'verifications':verified,'pureFourOrCoverage':pure,'pairs':pairs,'slowerObservations':slower,'workChangedObservations':workchanges,'otherFieldChanges':otherchanges,'prespecifiedThreshold':{'relativeStrictlyGreaterPercent':15,'absoluteStrictlyGreaterNanos':1000000,'existingV3RejectionRule':True},'repeatedMaterialRegressions':repeated,'limitations':['One observation per query in each run; three values per side are not P95 or stability evidence.','Full oracle values/order/provenance independently compared; authenticated export/reference not recomputed from all graph nodes again.','Recorded graph-content before/after inventories match byte-for-byte across runs. No large graph-content rehash by this audit; runner records the pre/post scan.','Recorded JAR identities match build and paired-run receipts; this independent audit did not rehash large JAR/graph files.','No Java, build, profile, new performance measure or retry started by this audit.','No per-query CPU/heap/RSS acceptance measurements in this v3 supplement; original34 and hosted CI remain separate.']}
(root/'independent-v3-audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
fmt=lambda n:f'{n/1e6:.6f}'
byquery={q:[r for r in pairs if r['id']==q] for q in ids}
report=['# Attempt 140 v3 完整配对审计','',('**本地拒绝。** 既定 v3 重复回归界限触发；old34 通过不能豁免，不能推进候选 CI。' if repeated else '**v3 未触发重复回归拒绝。** 仍不能接受候选，须完成 exact-head CI。'),'','完整值、顺序、来源与输入记录核验通过。'+('发现重复超过既定双界限的退化：'+', '.join(repeated) if repeated else '未发现同一查询至少两组同时超过 >15% 且 >1ms 的既定退化界限。'),'','六份 complete run 共216个查询输出、'+str(totalrows)+'行实际值／来源逐项匹配 oracle。每侧每查询只有3个观测，不能称P95、稳定收益或候选接受。顺序C/B、B/C、C/B；每查询清索引，JIT与OS缓存不清。','','## 纯四关键词 OR 覆盖','','| case | 完整命中图位置/数量 | 匹配节点 | 四词独立命中数 |','|---|---|---:|---|']
for c in pure:report.append(f"| {c['id']} | {c['hitGraphPositions'] if c['hitGraphCount']<=2 else str(c['hitGraphCount'])+' 图'} | {c['matchingNodes']} | {c['exclusiveCounts']} |")
report+=['','每词均检索四属性，四词互不包含，AST为纯OR，16个原子谓词。完整命中图来自认证oracle全量统计，不能从LIMIT返回行推断；所有返回元组完整provenance已独立核验。','','| query | Pair 1 base → candidate ms | Pair 2 | Pair 3 |','|---|---:|---:|---:|']
for q in ids:
 if q.startswith('or-four-'):report.append('| '+q+' | '+' | '.join(fmt(r['baseLatencyNanos'])+' → '+fmt(r['candidateLatencyNanos']) for r in byquery[q])+' |')
report+=['','## 全部变慢观测','','| query / pair | base ms | candidate ms | delta ms | 增幅 | 同时超过既定双界限 |','|---|---:|---:|---:|---:|---|']
for r in slower:report.append(f"| {r['id']} / {r['pair']} | {fmt(r['baseLatencyNanos'])} | {fmt(r['candidateLatencyNanos'])} | {fmt(r['deltaNanos'])} | {r['relativeIncreasePercent']:.4f}% | {'是' if r['exceedsReference15PercentAnd1ms'] else '否'} |")
report+=['',f"共有{len(slower)}个变慢观测、{len(workchanges)}处work变化、{len(otherchanges)}处其他TSV字段变化。完整108对全部字段差异保留于JSON；没有因未超过尺度而删除负向观测。这是预先规定的 v3 拒绝界限；任意重复越界都必须拒绝，未越界也仍须完成 exact-head CI。",'','## 校验和解释边界','','六份catalog/workloads、query SHA、完整actual rows digest、每行provenance、rowCount及summary唯一latency均核对。六份编译adapter class相同；graph-content-before/after逐字节相同且六份共同hash为 `'+inventoryHash+'`。两份JAR记录hash与构建/成对运行收据匹配；本审计没有重新hash大JAR。没有重扫大图文件，也没有启动Java。','','不同执行路径或并发提前停止可能影响工作量，当前数据不能区分原因；不能把work变化当作CPU或速度预测。v3 输出没有每查询 CPU/heap/RSS 数据，本审计不生成资源收益结论；原34资源检查与最终CI是独立门槛。全部36条latency/work表见 [README](v3-pairs/README.md)，精确结果见 [JSON](independent-v3-audit.json)。']
(root/'independent-v3-audit.md').write_text('\n'.join(report)+'\n')
out=root/'v3-pairs';out.mkdir(exist_ok=True)
md=['# Attempt 140：全部36条v3配对观测','','补充配对观测，每查询每侧3个观测，不是P95，不提前接受候选。C/B、B/C、C/B；实际值、顺序和完整来源全部验证。详见 [独立审计](../independent-v3-audit.md) / [精确JSON](../independent-v3-audit.json)。','','## 延迟（毫秒，base → candidate）','','| query | Pair 1 | Pair 2 | Pair 3 |','|---|---:|---:|---:|']
for q in ids:md.append('| '+q+' | '+' | '.join(fmt(r['baseLatencyNanos'])+' → '+fmt(r['candidateLatencyNanos']) for r in byquery[q])+' |')
md+=['','## 工作量（base → candidate）','','| query | Pair 1 | Pair 2 | Pair 3 |','|---|---:|---:|---:|']
for q in ids:md.append('| '+q+' | '+' | '.join(str(r['baseWorkUnits'])+' → '+str(r['candidateWorkUnits']) for r in byquery[q])+' |')
md+=['','重复超过 >15% 且 >1ms 既定双界限的查询：'+(json.dumps(repeated,ensure_ascii=False) if repeated else '无')+'。这是预先规定的重复回归拒绝界限，不能单独确立接受。所有变化字段及负向观测均保留在独立JSON，原34与完整CI仍按各自协议判断。']
(out/'README.md').write_text('\n'.join(md)+'\n')
print({'verifiedObservations':216,'actualRows':totalrows,'slower':len(slower),'workChanges':len(workchanges),'otherChanges':len(otherchanges),'repeatedMaterialRegressions':repeated})
