import csv, json, math, hashlib
from pathlib import Path
R=Path(__file__).parent
G=next((R/'global-wide').glob('reference-*'))
PIN='4e328b0109e13c896b74004823fb049fcb19251a'
HEAD='27de1f5ebd318fb5f60b24596712a5b3a6a3836e'
KEYS='id family shape selectivity operator boundary projection targetGraphId workloadIdentity limit outcome rowCount responseBytes digest'.split()
def j(p): return json.loads(p.read_text())
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def rows(p): return list(csv.DictReader(p.open(),delimiter='\t'))
def sig(row): return '|'.join(row[k] for k in KEYS)
def quant(rs,q): return sorted(int(x['latencyNanos']) for x in rs)[math.ceil(len(rs)*q)-1]
def manifest(folder):
 d=j(folder/'evidence-manifest.json'); assert d['baseSha']==PIN and d['candidateSha']==HEAD
 for f,h in d['files'].items(): assert sha(folder/f)==h,(folder,f)
 p=j(folder/'provenance.json'); assert p['baseSha']==PIN and p['candidateSha']==HEAD
 assert sha(folder/'graphs.tsv')==p['manifestSha256']
 assert sha(folder/'fixture-provenance.tsv')==p['fixtureProvenanceSha256']
 f=j(folder/'fixture-reproducibility.json');assert f['passed'] and f['firstProvenanceSha256']==f['repeatedProvenanceSha256'] and f['firstManifestSemanticSha256']==f['repeatedManifestSemanticSha256']
 return {'verifiedFileCount':len(d['files']),'provenance':p,'manifestSha256':sha(folder/'evidence-manifest.json'),'reproducibility':f}
def verify_rows(p,oracle,ordered):
 rs=rows(p);ss=[sig(x) for x in rs]; assert len(rs)==len(oracle) and len({x['id'] for x in rs})==len(rs)
 assert all(x['outcome']=='success' and int(x['latencyNanos'])>0 for x in rs)
 assert (ss==oracle if ordered else sorted(ss)==sorted(oracle)),p
 cp=p.with_suffix('.correctness')
 if cp.exists(): assert ss==cp.read_text().splitlines(),cp
 return rs
out={'schema':'independent-attempt138-revert-ci-audit-v1','head':HEAD,'readOnly':True,'newMeasurements':False,'workflowRuns':{}}
for run in [33991414367,33991414379]:
 p=R/f'run-{run}.json'; d=j(p);assert d['headSha']==HEAD and d['status']=='completed'
 out['workflowRuns'][str(run)]={'conclusion':d['conclusion'],'snapshotSha256':sha(p),'failedJobs':[x['name'] for x in d['jobs'] if x['conclusion']=='failure']}
out['method']=j(R/'method-job-results.json')
iteration=j(R/'global-wide/global-wide-status.json')
assert iteration['currentHead']==HEAD and iteration['currentPrBase']==PIN
out['iteration']={k:iteration[k] for k in ['passed','iterationPassed','regressionPassed','progressAchieved','targetAchieved','requireTarget','currentHead','currentPrBase','lastAcceptedRef','errors','targetErrors']}
status=j(G/'global-wide-status.json');oracle=(G/'base-global-wide-oracle-seed.correctness').read_text().splitlines();assert len(oracle)==34
out['globalWide']={'status':{k:status[k] for k in ['passed','regressionPassed','targetAchieved','errors','targetErrors']},'integrity':manifest(G),'oracleResultCount':204,'pairs':[],'repeatedAlignedFailures':[]}
violations={}
workkeys='hitGraphIds inputSourceCount accessedGraphIds accessedGraphCount graphWorkUnits parallelScanCount indexLookupCount executionPath'.split()
for i in range(1,4):
 rr={k:verify_rows(G/f'{k}-global-wide-{i}.tsv',oracle,True) for k in ['base','candidate']}
 run=status['runs'][i-1];pair={'pair':i,'order':run['order'],'sides':{},'alignedWorkDifferences':[]}
 for side,rs in rr.items():
  met=j(G/f'{side}-global-wide-{i}.json')[0]['secondaryMetrics']; m={k:v['score'] for k,v in met.items()}
  assert m['queryCount']==34 and m['successCount']==34 and m['failureCount']==0
  summary={'p50LatencyNanos':quant(rs,.5),'p95LatencyNanos':quant(rs,.95)}
  for k,v in summary.items():assert v==m[k] and v==run[('base'+k[0].upper()+k[1:]) if side=='base' else k]
  for k in ['processCpuNanos','peakUsedHeapBytes','peakResidentSetBytes']:
   summary[k]=m[k];assert m[k]==run[('base'+k[0].upper()+k[1:]) if side=='base' else k]
  summary['top3']=[{'id':x['id'],'latencyNanos':int(x['latencyNanos']),'graphWorkUnits':int(x['graphWorkUnits'])} for x in sorted(rs,key=lambda x:int(x['latencyNanos']),reverse=True)[:3]]
  pair['sides'][side]=summary
 for b,c in zip(rr['base'],rr['candidate']):
  changes={k:[b[k],c[k]] for k in workkeys if b[k]!=c[k]}
  if changes:pair['alignedWorkDifferences'].append({'id':b['id'],'changes':changes})
  bn,cn=int(b['latencyNanos']),int(c['latencyNanos'])
  if cn*100>bn*115 and cn>bn+1000000:violations.setdefault(b['id'],[]).append({'pair':i,'baseNanos':bn,'candidateNanos':cn})
 pair['p95Speedup']=pair['sides']['base']['p95LatencyNanos']/pair['sides']['candidate']['p95LatencyNanos'];assert pair['p95Speedup']==run['p95Speedup']
 out['globalWide']['pairs'].append(pair)
out['globalWide']['repeatedAlignedFailures']=[{'id':k,'pairs':v} for k,v in violations.items() if len(v)>=2]
assert len(out['globalWide']['repeatedAlignedFailures'])==6
T=R/'routing';oracle=(T/'base-single-source-oracle.manifest').read_text().splitlines();assert len(oracle)==1137
out['routing']={'integrity':manifest(T),'oracleResultCount':6822,'states':{},'passed':j(T/'graph-routing-status.json')['passed']}
for state in ['cold','warm','startup-prepared']:
 st=j(T/f'graph-routing-{state}-status.json');rr={k:verify_rows(T/f'{k}-graph-routing-{state}.tsv',oracle,False) for k in ['base','candidate']}
 for width in st['graphSetLatencyByWidth']:
  for side in ['base','candidate']:
   subset=[x for x in rr[side] if int(x['selectedGraphCount'])==width['width'] and x['shape'] in ['graph-id-in-literal-wrapped-contains','graph-id-in-parameter-wrapped-contains']]
   assert len(subset)==width['sampleCount']
   for q,label in [(.5,'P50'),(.95,'P95')]: assert quant(subset,q)==width[side+label]
 out['routing']['states'][state]={k:st[k] for k in ['passed','errors','p50Speedup','p95Speedup','graphParameterP50Speedup','graphParameterP95Speedup','resources','graphSetLatencyByWidth']}
out['limitations']=['Checks compare hosted TSV signatures with their frozen oracle and published digests; no query or fixture replay occurred.','Artifact manifest hashes and provenance are verified; the original hosted JARs/graphs were not rehashed here.','Equal recorded JAR payload hashes do not explain or waive confirmed timing failures.','Method whole-process CPU includes client, reference oracle, engine and background work; this audit does not attribute Linux CI regressions to an engine phase.','Candidate138 was rejected locally by repeated v3 regressions before candidate CI; this audit only covers explicit production revert CI.']
(R/'ci-audit.json').write_text(json.dumps(out,indent=2)+'\n')
print(json.dumps({'output':str(R/'ci-audit.json'),'integrityFiles':out['globalWide']['integrity']['verifiedFileCount']+out['routing']['integrity']['verifiedFileCount'],'globalWorkDifferences':[len(p['alignedWorkDifferences']) for p in out['globalWide']['pairs']],'repeatedAligned':out['globalWide']['repeatedAlignedFailures'],'workflowRuns':out['workflowRuns']},indent=2))
