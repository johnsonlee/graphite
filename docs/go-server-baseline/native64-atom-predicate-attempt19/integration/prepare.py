from pathlib import Path
import json,hashlib,tarfile,subprocess,io,shutil
r=Path(__file__).resolve().parent;a=Path('/tmp/graphite-atom-attempt19-evidence');H=lambda b:hashlib.sha256(b).hexdigest()
assert H((a/'manifest.json').read_bytes())=='46e5e952f4323e7a31e766636b0c7015837f8ce1b1f916ac2f82237e4a863ad7'
m=json.loads((a/'manifest.json').read_text())
for n,v in m['files'].items():assert H((a/n).read_bytes())==v['sha256'],n
shutil.copy2(a/'manifest.json',r/'author-manifest.json');shutil.copy2(a/'attempt19.patch',r/'attempt19.patch');shutil.copy2(a/'candidate-inputs.json',r/'author-candidate-inputs.json')
(r/'author-verification.json').write_text(json.dumps({'filesVerified':len(m['files']),'manifestSHA':H((a/'manifest.json').read_bytes())},indent=2)+'\n')
base=subprocess.check_output(['git','rev-parse','39eedb33']).decode().strip();(r/'base.txt').write_text(base+'\n')
b=subprocess.check_output(['git','archive',base,'graphite-server','graphite-explore','CONVENTIONS.md']);(r/'base-source.tar').write_bytes(b)
for name in ['base','candidate']:
 d=r/'combined'/name;d.mkdir(parents=True)
 with tarfile.open(fileobj=io.BytesIO(b)) as t:t.extractall(d,filter='data')
def inv(d):return {str(p.relative_to(d)):{'sha256':H(p.read_bytes()),'bytes':p.stat().st_size} for p in sorted(d.rglob('*')) if p.is_file()}
before=inv(r/'combined/base');(r/'base-inputs.json').write_text(json.dumps(before,indent=2)+'\n')
assert sum(n.startswith('graphite-server/') for n in before)==2437
for cmd,log in [(['git','apply','--check',str(r/'attempt19.patch')],'apply-check.log'),(['git','apply',str(r/'attempt19.patch')],'apply.log')]:
 p=subprocess.run(cmd,cwd=r/'combined/candidate',text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT);(r/log).write_text(p.stdout);assert p.returncode==0
post=inv(r/'combined/candidate');(r/'candidate-inputs.json').write_text(json.dumps(post,indent=2)+'\n')
assert post==json.loads((a/'candidate-inputs.json').read_text())
changes=[dict(path=n,base=before.get(n),candidate=v) for n,v in post.items() if before.get(n)!=v];assert len(changes)==4;(r/'changed-files.json').write_text(json.dumps(changes,indent=2)+'\n')
(r/'original-eval-overlay.json').write_text(json.dumps({'Replace':{str((r/'combined/candidate/graphite-server/internal/query/eval.go').resolve()):str((r/'combined/base/graphite-server/internal/query/eval.go').resolve())}},indent=2)+'\n')
for n in ['verify_b595.py','numeric-spelling-audit.py','summarize_original.py']:
 s=(a/n).read_text().replace('/tmp/graphite-go-atom-attempt19-39eedb33/graphite-server',str(r/'combined/candidate/graphite-server')).replace("r/'candidate-output'","r/'output'").replace("r/'candidate-output/", "r/'output/")
 (r/n).write_text(s)
(r/'output/original-corpus').mkdir(parents=True);(r/'output/history').mkdir()
print(base,len(post),'inputs; exactauthor')
