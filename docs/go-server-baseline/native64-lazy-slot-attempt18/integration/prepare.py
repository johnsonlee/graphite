import hashlib,json,subprocess,tarfile,io,shutil
from pathlib import Path
r=Path(__file__).resolve().parent; w=r.parent;a=Path('/tmp/graphite-lazy-slot-attempt18-evidence');H=lambda b:hashlib.sha256(b).hexdigest()
base=subprocess.check_output(['git','rev-parse','fb4434df']).decode().strip(); (r/'base.txt').write_text(base+'\n')
m=json.loads((a/'manifest.json').read_text());assert H((a/'manifest.json').read_bytes())=='5565f5e27c2fac6367ef1054fc34372110769c3671d80a5d341230893580db0b'
for f in m['files']: assert H((a/f['path']).read_bytes())==f['sha256'],f['path']
shutil.copy2(a/'manifest.json',r/'author-manifest.json');shutil.copy2(a/'attempt18.patch',r/'attempt18.patch');assert H((r/'attempt18.patch').read_bytes())=='259d1be1cef97757ec5a012f20d24c597c862e9c6b29d3840b34a268e973a26d'
(r/'author-verification.json').write_text(json.dumps({'verifiedFiles':len(m['files']),'manifest':H((a/'manifest.json').read_bytes())},indent=2)+'\n')
b=subprocess.check_output(['git','archive',base,'graphite-server','graphite-explore','CONVENTIONS.md']);(r/'base-source.tar').write_bytes(b)
for name in ['base','candidate']:
 d=w/name;d.mkdir()
 with tarfile.open(fileobj=io.BytesIO(b)) as t:t.extractall(d,filter='data')
def inv(d):return {str(p.relative_to(d)):{'sha256':H(p.read_bytes()),'bytes':p.stat().st_size} for p in sorted(d.rglob('*')) if p.is_file()}
before=inv(w/'base');(r/'base-inputs.json').write_text(json.dumps(before,indent=2)+'\n');assert sum(n.startswith('graphite-server/') for n in before)==2436
for args,log in [(['git','apply','--check',str(r/'attempt18.patch')],'apply-check.log'),(['git','apply',str(r/'attempt18.patch')],'apply.log')]:
 p=subprocess.run(args,cwd=w/'candidate',text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT);(r/log).write_text(p.stdout);assert p.returncode==0
post=inv(w/'candidate');(r/'candidate-inputs.json').write_text(json.dumps(post,indent=2)+'\n');assert sum(n.startswith('graphite-server/') for n in post)==2437
changed=[n for n in post if before.get(n)!=post[n]];assert len(changed)==3,changed
proof=[]
for n in changed:
 expected=Path('/tmp/graphite-go-lazy-slot-attempt18-b7bb15a2')/n
 assert H(expected.read_bytes())==post[n]['sha256'],n
 proof.append({'path':n,'base':before.get(n),'candidate':post[n],'exactAuthor':True})
(r/'changed-files.json').write_text(json.dumps(proof,indent=2)+'\n')
old=json.loads(Path('/tmp/graphite-go-a17-on-a16-b/integration/candidate-inputs.json').read_text());assert before==old
(r/'prior-a17-identity.json').write_text(json.dumps({'allInputsIdentical':len(old),'moduleInputs':2436,'base':base},indent=2)+'\n')
for n in ['summarize_original.py','verify_b595.py','numeric-spelling-audit.py']:
 p=Path('/tmp/graphite-go-a17-on-a16-b/integration')/n;s=p.read_text().replace('/tmp/graphite-go-a17-on-a16-b','/tmp/graphite-go-a18-on-fb4434df');(r/n).write_text(s)
(r/'output/original-corpus').mkdir(parents=True);(r/'output/history').mkdir()
print(base,len(before),len(post),changed)
