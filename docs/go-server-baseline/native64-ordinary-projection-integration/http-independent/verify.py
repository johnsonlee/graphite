"""Independent full-record gate after the HTTP runner; correctness only."""
from pathlib import Path
from decimal import Decimal
import json,hashlib,subprocess,os,socket
here=Path(__file__).resolve().parent
read=lambda p:json.loads(p.read_text())
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
source=read(here/'source-identity.json');identity=read(here/'http/identity.json')
summary=read(here/'http/summary.json');actual=read(here/'http/observations.json')
main=read(Path(identity['frozenMainResponses']))
cfg=read(Path(source['runner']).parent/'config.json');catalog=read(here/'http/catalog.json')
assert summary['complete'] is True and summary['cases']==summary['required']==42 and summary['mismatches']==[]
assert len(actual)==len(main)==42
assert catalog['count']==len(catalog['graphs'])==len(cfg['graphs'])==64
assert catalog['totals']==cfg['totals']
for graph,want in zip(catalog['graphs'],cfg['graphs']):
 for k in ['id','nodes','edges','methods','callSites']:assert graph[k]==want[k],(graph['id'],k)
# Independent type-sensitive JSON comparison; object whitespace/order is not semantic.
# It compares every value, preserves array order/null and applies no dynamic masks.
def canonical(value):
 if isinstance(value,dict):return ('object',tuple(sorted((k,canonical(v))for k,v in value.items())))
 if isinstance(value,list):return ('array',tuple(canonical(v)for v in value))
 if isinstance(value,bool):return ('bool',value)
 if isinstance(value,Decimal):return ('number',value)
 if value is None:return ('null',)
 assert isinstance(value,str)
 return ('string',value)
checks=[]
for r,want in zip(actual,main):
 assert r['case']==want['case'] and r['baseline']==want['baseline']
 assert not r['case'].get('dynamicPointers')
 assert r['candidate']['status']==want['baseline']['status']==200
 assert r['equal'] is True and not r['headerDifferences']
 a=r['candidate'];b=want['baseline']
 assert canonical(json.loads(a['body'],parse_float=Decimal,parse_int=Decimal))==canonical(json.loads(b['body'],parse_float=Decimal,parse_int=Decimal)),r['case']['name']
 for k in ['Content-Type','Retry-After']:assert a['headers'].get(k)==b['headers'].get(k),(r['case']['name'],k)
 if 'Content-Length'in a['headers']:assert int(a['headers']['Content-Length'])==len(a['body'].encode())
 checks.append({'case':r['case']['name'],'status':200,'completeBodyEqual':True,'protocolHeadersEqual':True,'candidateBodySHA256':hashlib.sha256(a['body'].encode()).hexdigest(),'baselineBodySHA256':hashlib.sha256(b['body'].encode()).hexdigest()})
assert sha(here/'graphite-server')==source['binarySHA256']==identity['binarySHA256']
assert sha(here/'source-identity.json')==identity['sourceIdentitySHA256']
assert sha(Path(source['runner']))==source['runnerSHA256']
assert sha(Path(source['runner']).parent/'config.json')==source['configSHA256']
assert sha(Path(identity['frozenMainResponses']))==identity['frozenMainResponsesSHA256']
for name,digest in source['nativeSourcesAndEmbeds'].items():assert sha(Path(source['worktree'])/name)==digest,name
assert source['profilingEnvironment']=={'GRAPHITE_NATIVE_CPU_PROFILE':None,'GRAPHITE_PROFILE':None}
for name,digest in read(here/'inputs-hashes.json')['files'].items():assert sha(here/'inputs'/name)==digest,name
assert sha(here/'inputs/replay-http.py')==source['originalRunnerSHA256']
assert sha(Path(source['originalRunner']))==source['originalRunnerSHA256']
assert sha(here/'inputs/observations.json')==identity['frozenMainResponsesSHA256']
root=Path(source['originalRunner']).parents[3]
assert sha(root/'graphite-server/scripts/http_parity.py')==sha(here/'inputs/http_parity.py')
originalConfig=read(here/'inputs/config.json')
for current,original in zip(cfg['graphs'],originalConfig['graphs']):
 assert current['path']==str(Path(source['cloneFixtureRoot'])/current['id'])
 assert {k:v for k,v in current.items() if k!='path'}=={k:v for k,v in original.items() if k!='path'}
assert {k:v for k,v in cfg.items() if k!='graphs'}=={k:v for k,v in originalConfig.items() if k!='graphs'}
assert identity['command'][identity['command'].index('--data')+1]==source['cloneFixtureRoot']
assert source['noProfileQueryOrExport'] is True and source['noGenericSync'] is True and source['noExactTupleImplementation'] is True
assert not (Path(source['worktree'])/'graphite-server/cmd/profile-query').exists()
assert not list((Path(source['worktree'])/'graphite-server').rglob('profile_export*'))
assert not (Path(source['worktree'])/'graphite-server/internal/query/generic_distinct.go').exists()
assert read(here/'replay-command.json')['exitCode']==0
exitRecord=read(here/'http/server-exit.json')
assert exitRecord['forcedKill'] is False,exitRecord
before=read(here/'fixtures-before.json');after=read(here/'fixtures-after.json')
assert sha(here/'fixtures-before.json')==source['fixtureBeforeSHA256']
assert before['requiredGraphFiles']==after['requiredGraphFiles']==1152
assert before['allOriginalFrozenHashesMatch'] is True and after['allOriginalFrozenHashesMatch'] is True
assert after['changes']['original']=={}
assert read(here/'root-production-after.json')['allEqual'] is True
# The runner's finally block has terminated its server, not just completed requests.
during=read(here/'server-process-during.json');pids=[int(x[1:])for x in during['output'].splitlines()if x.startswith('p')]
assert len(pids)==1 and pids[0]==exitRecord['pid']
for pid in pids:
 try:os.kill(pid,0)
 except ProcessLookupError:pass
 else:raise AssertionError(('server PID still alive',pid))
s=socket.socket();s.bind(('127.0.0.1',18862));s.close()
result={'passed':True,'cases':42,'allHTTP200':True,'allCompleteBodiesEqual':True,'allProtocolHeadersEqual':True,'catalogGraphs':64,'noDynamicMasks':True,'serverPIDsExited':pids,'port18862Free':True,'checks':checks,'purpose':'Correctness only; no performance or P95 claim'}
(here/'verification.json').write_text(json.dumps(result,indent=2)+'\n')
print('PASS: 42/42 HTTP 200; complete bodies and protocol headers equal; all 64 catalog graphs exact; server exited')
