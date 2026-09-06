#!/usr/bin/env python3
"""Correctness only: one Store at a time, no Cypher/HTTP workload."""
import hashlib,json,os,pathlib,subprocess,sys
repo=pathlib.Path(sys.argv[1]).resolve()
manifest=pathlib.Path(sys.argv[2]).resolve()
out=pathlib.Path(__file__).resolve().parent
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
module=repo/'graphite-server'
command=['go','test','./internal/store','-run','^TestRealCallSiteIndexes$','-count=1','-v','-timeout=30m']
env=os.environ.copy();env.update(GRAPHITE_TEST_CALLSITE_MANIFEST=str(manifest),GOMAXPROCS='2')
identity={'baseRevision':subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip(),'repository':str(repo),'command':command,'cwd':str(module),'environment':{'GRAPHITE_TEST_CALLSITE_MANIFEST':str(manifest),'GOMAXPROCS':'2'},'go':subprocess.check_output(['go','version'],text=True).strip(),'fixtureManifestSHA256':sha(manifest),'sourceSHA256':{str(p.relative_to(repo)):sha(p) for p in sorted(module.rglob('*')) if p.is_file() and (p.suffix=='.go' or p.name in ['go.mod','go.sum'])},'purpose':'full CRC/CSR correctness; no timing or performance conclusion'}
(out/'run-identity.json').write_text(json.dumps(identity,indent=2)+'\n')
with (out/'run.log').open('w') as log:
 result=subprocess.run(command,cwd=module,env=env,stdout=log,stderr=subprocess.STDOUT)
(out/'exit-code.txt').write_text(str(result.returncode)+'\n')
print('exit_code='+str(result.returncode),flush=True)
if result.returncode: sys.exit(result.returncode)
lines=(out/'run.log').read_text().splitlines()
records=json.loads(next(line.split('validated_indexes=',1)[1] for line in lines if 'validated_indexes=' in line))
(out/'validated-indexes.json').write_text(json.dumps(records,indent=2)+'\n')
summary={'graphs':len(records),'nodes':sum(r['nodes'] for r in records),'methods':sum(r['methods'] for r in records),'edges':sum(r['edges'] for r in records),'annotations':sum(r['annotationNodes'] for r in records),'graphsWithZeroAnnotations':sum(r['annotationNodes']==0 for r in records),'indexBytes':sum(r['indexBytes'] for r in records),'callSites':sum(r['info']['CallSiteCount'] for r in records),'strings':sum(r['info']['StringCount'] for r in records),'trigramPostings':sum(r['info']['TrigramPostingCount'] for r in records),'uniqueStringCounts':[sum(r['info']['UniqueStringCounts'][p] for r in records) for p in range(4)],'exitCode':result.returncode}
(out/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary),flush=True)
