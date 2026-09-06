from pathlib import Path
import json,hashlib,subprocess
p=Path(__file__).resolve().parent
fixture=Path('/private/tmp/pr113-method-fixtures');corpora=['android','tika','hive','kotlin-compiler']
def identity():
 out=[]
 for corpus in corpora:
  for f in sorted((fixture/corpus).rglob('*')):
   if f.is_file():out.append({'path':str(f.relative_to(fixture)),'bytes':f.stat().st_size,'sha256':hashlib.sha256(f.read_bytes()).hexdigest()})
 assert out and {x['path'].split('/')[0] for x in out}==set(corpora)
 return out
before=identity();(p/'method-input-before.json').write_text(json.dumps(before,indent=2)+'\n')
jar=p/'candidate-final-explore-jmh.jar';expected=json.loads((p/'final-build-receipt.json').read_text())['jars']['explore']['sha256'];assert hashlib.sha256(jar.read_bytes()).hexdigest()==expected
cmd=json.loads((p/'method-control-command.json').read_text());assert not (p/'method-control.json').exists() and not (p/'method-control.manifest.txt').exists()
try:
 with (p/'method-control.log').open('w') as log:r=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT)
 (p/'method-control-exit.json').write_text(json.dumps({'exitCode':r.returncode})+'\n')
 assert r.returncode==0
 results=json.loads((p/'method-control.json').read_text());assert len(results)==33
 assert {(x['params']['graphCount'],x['params']['scenario']) for x in results}=={(g,s) for g in ['4','17','36'] for s in ['zero','early','middle','late','prefix','suffix','contains','regex','or','count','order']}
 assert all(x['secondaryMetrics']['requestsSucceeded']['score']==1 for x in results)
 assert len((p/'method-control.manifest.txt').read_text().splitlines())==33
 print('33 real Method scenario/count combinations complete')
finally:
 after=identity();(p/'method-input-after.json').write_text(json.dumps(after,indent=2)+'\n');same=before==after and hashlib.sha256(jar.read_bytes()).hexdigest()==expected
 (p/'method-control-receipt.json').write_text(json.dumps({'inputsAndJarUnchanged':same,'fixtureFiles':len(before),'fixtureBytes':sum(x['bytes'] for x in before),'jarSha256':expected,'scope':'unpaired built-in correctness control; Method root order is normalized and unknown graph IDs filtered, so raw root ordering and extra IDs are not independently proven; no performance comparison','performanceAccepted':False},indent=2)+'\n');assert same
