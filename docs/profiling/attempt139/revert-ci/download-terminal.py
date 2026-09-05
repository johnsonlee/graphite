from pathlib import Path
import json,subprocess,concurrent.futures
r=Path(__file__).parent;head='aede4c82f66a925ba9df3fc8588c6e1399c17f61';run=33992947567
for s,i in [('unit',33992947613),('benchmark',run)]:
 b=(r/f'terminal-{s}.json').read_bytes();d=json.loads(b);assert d['headSha']==head and d['status']=='completed';(r/f'run-{i}.json').write_bytes(b)
jobs=json.loads((r/'terminal-benchmark.json').read_text())['jobs'];(r/'method-job-results.json').write_text(json.dumps({'head':head,'run':run,'jobs':[{k:x[k] for k in ['name','status','conclusion','databaseId','url']} for x in jobs if x['name'].startswith('method-compatibility')],'conclusionScope':'Terminal job status only, failed Method4 string additionally audited from raw JMH/signatures.'},indent=2)+'\n')
c=['gh','api',f'repos/johnsonlee/graphite/actions/runs/{run}/artifacts','--paginate'];p=subprocess.run(c,text=True,capture_output=True);assert p.returncode==0,p.stderr;(r/'artifacts.json').write_text(p.stdout);arts=json.loads(p.stdout)['artifacts']
def download(pair):
 name,folder=pair;a=next(x for x in arts if x['name']==name);assert a['workflow_run']['id']==run and a['workflow_run']['head_sha']==head
 c=['gh','run','download',str(run),'--name',name,'--dir',str(r/folder)];p=subprocess.run(c,text=True,capture_output=True);assert p.returncode==0,p.stderr
 return {'metadata':a,'command':c,'exitCode':p.returncode}
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:receipts=list(pool.map(download,[('benchmark-global-wide-116-1','global-wide'),('benchmark-graph-routing-116-1','routing')]))
(r/'download-receipt.json').write_text(json.dumps({'head':head,'run':run,'readOnly':True,'artifacts':receipts,'failedMethodDownloadReceipt':'download-method-receipt.json'},indent=2)+'\n')
print(json.dumps({'downloaded':[(x['metadata']['name'],x['metadata']['size_in_bytes']) for x in receipts]},indent=2))
