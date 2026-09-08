"""Archive two complete captures and one audited pre-query fixture per mutation."""
from pathlib import Path
import gzip,hashlib,io,json,shutil,tarfile
HERE=Path(__file__).resolve().parent
runs=[Path('/tmp/graphite-generic-disjunction-oracle-v2'),Path('/tmp/graphite-generic-disjunction-oracle-v3')]
def sha(b):return hashlib.sha256(b).hexdigest()
def gz(p,b):p.write_bytes(gzip.compress(b,mtime=0))
for i,run in enumerate(runs):
 tag='main' if i==0 else 'repeat'
 for name in ['receipt.json','fixture-audit.json','compile.stdout','compile.stderr','prepare.stdout','prepare.stderr','run.stdout','run.stderr']:
  shutil.copyfile(run/name,HERE/(tag+'-'+name))
 for name in ['fixture-before.json','fixture-after.json']:gz(HERE/(tag+'-'+name+'.gz'),(run/name).read_bytes())
 if i==0:shutil.copyfile(run/'main.json',HERE/'main.json');shutil.copyfile(run/'source-fixture.json',HERE/'source-fixture.json')
 else:gz(HERE/'repeat-main.json.gz',(run/'main.json').read_bytes())
# Use recorded pre-query manifests: later optional persisted indexes are omitted exactly.
specs=json.loads((HERE/'cases.json').read_text());before={f['file']:f for f in json.loads((runs[0]/'fixture-before.json').read_text())}
variants={}
for spec in specs:
 for i,variant in enumerate(spec['fixtures']):variants.setdefault(variant,spec['name']+'/store'+str(i))
buf=io.BytesIO();files=[]
with tarfile.open(fileobj=buf,mode='w') as tar:
 for variant,prefix in sorted(variants.items()):
  for original,meta in sorted(before.items()):
   if not original.startswith(prefix+'/'):continue
   data=(runs[0]/'fixtures'/original).read_bytes();assert sha(data)==meta['sha256'] and len(data)==meta['bytes']
   name=variant+'/'+original[len(prefix)+1:];info=tarfile.TarInfo(name);info.size=len(data);info.mode=0o644;tar.addfile(info,io.BytesIO(data));files.append(dict(file=name,bytes=len(data),sha256=sha(data)))
gz(HERE/'fixtures.tar.gz',buf.getvalue());(HERE/'fixture-variants.json').write_text(json.dumps(dict(variants=variants,files=files),indent=2)+'\n')
# Preserve the earlier assertion failure and its unfiltered JVM output. This run
# was exploratory: final spec fixes Annotation positive needle and Enum provider needle.
failed=HERE/'initial-audit-failure';failed.mkdir(exist_ok=True)
for name in ['main.json','fixture-audit.json']:gz(failed/(name+'.gz'),(Path('/tmp/graphite-generic-disjunction-oracle-v1')/name).read_bytes())
(failed/'README.md').write_text('Initial JVM compile/prepare/query execution completed, but the Python controller exited 1 because it forbade newly generated graph.callsite-string-index files. No original fixture file changed. The final controller explicitly permits only this optional sidecar, and two fresh captures passed. The initial matrix also used a nonmatching Annotation needle (deprecated); final cases use annotation and fix the Enum provider positive needle to red. Both initial output and audit remain here without removing any cases.\n')
print('Archived',len(files),'files across',len(variants),'fixture variants')
