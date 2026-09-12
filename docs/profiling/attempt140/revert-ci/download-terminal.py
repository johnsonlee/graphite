from pathlib import Path
import json,subprocess,concurrent.futures,re
r=Path(__file__).parent;head='b94b8caa8dea10d1d2ddb74a0a0c39a3ab5351f0';run=33995836230
for s,i in [('unit',33995836241),('benchmark',run)]:
 b=(r/f'terminal-{s}.json').read_bytes();d=json.loads(b);assert d['headSha']==head and d['status']=='completed';(r/f'run-{i}.json').write_bytes(b)
jobs=json.loads((r/'terminal-benchmark.json').read_text())['jobs'];methodJobs=[{k:x[k] for k in ['name','status','conclusion','databaseId','url']} for x in jobs if x['name'].startswith('method-compatibility')]
(r/'method-job-results.json').write_text(json.dumps({'head':head,'run':run,'jobs':methodJobs,'conclusionScope':'Terminal job status; any failed Method shard additionally audited from downloaded original evidence.'},indent=2)+'\n')
c=['gh','api',f'repos/johnsonlee/graphite/actions/runs/{run}/artifacts','--paginate'];p=subprocess.run(c,text=True,capture_output=True);assert p.returncode==0,p.stderr;(r/'artifacts.json').write_text(p.stdout);arts=json.loads(p.stdout)['artifacts']
pairs=[('benchmark-global-wide-116-1','global-wide'),('benchmark-graph-routing-116-1','routing')]
for job in methodJobs:
 m=re.fullmatch(r'method-compatibility-(\d+)-(\w+)',job['name'])
 if m and job['conclusion']=='failure':pairs.append((f'benchmark-method-compatibility-shard-{m[1]}-{m[2]}-116-1',f'method{m[1]}-{m[2]}'))
def download(pair):
 name,folder=pair;a=next(x for x in arts if x['name']==name);assert a['workflow_run']['id']==run and a['workflow_run']['head_sha']==head
 c=['gh','run','download',str(run),'--name',name,'--dir',str(r/folder)];p=subprocess.run(c,text=True,capture_output=True);assert p.returncode==0,p.stderr
 return {'metadata':a,'command':c,'exitCode':p.returncode,'folder':folder}
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:receipts=list(pool.map(download,pairs))
(r/'download-receipt.json').write_text(json.dumps({'head':head,'run':run,'readOnly':True,'artifacts':receipts},indent=2)+'\n')
print(json.dumps({'downloaded':[(x['metadata']['name'],x['metadata']['size_in_bytes']) for x in receipts]},indent=2))
