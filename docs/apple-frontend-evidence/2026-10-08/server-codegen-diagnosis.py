from pathlib import Path
import hashlib,struct,json,subprocess
V=Path('/Users/johnsonlee/.codex/benchmarks/apple-validation'); R=Path('/Users/johnsonlee/.codex/worktrees/19db/graphite')
sha=lambda b:hashlib.sha256(b).hexdigest()
def sections(b):
 if b[:4]!=b'\xcf\xfa\xed\xfe': return {}
 n=struct.unpack_from('<I',b,16)[0]; p=32; out={}
 for _ in range(n):
  cmd,size=struct.unpack_from('<II',b,p)
  if cmd==0x19:
   ns=struct.unpack_from('<I',b,p+64)[0]
   for i in range(ns):
    s=p+72+i*80; name=b[s:s+16].split(b'\0')[0].decode(); seg=b[s+16:s+32].split(b'\0')[0].decode(); sz,off=struct.unpack_from('<QI',b,s+40); flags=struct.unpack_from('<I',b,s+64)[0]
    if off and sz and (flags&0xff) not in [1,12]: out[seg+','+name]={'size':sz,'sha256':sha(b[off:off+sz])}
  p+=size
 return out
def archive(b):
 out={}; pos=8
 while pos+60<=len(b):
  h=b[pos:pos+60]; name=h[:16].decode().strip(); size=int(h[48:58]); data=b[pos+60:pos+60+size];pos+=60+size+(size%2)
  if name.startswith('#1/'):
   k=int(name[3:]); name=data[:k].rstrip(b'\0').decode();data=data[k:]
  if name.endswith('.o'): out[name]=sections(data)
 return out
result={'kind':'read-only binary/build evidence; no benchmark execution','variants':{},'rlibComparisons':{}}
for variant,root in [('baseline',V/'main'),('candidate',R)]:
 b=root/'target/aarch64-apple-darwin/release/graphite'; target=root/'target/aarch64-apple-darwin/release'
 fps={}
 for glob in ['graphite-cli-*','graphite-cypher-*','graphite-storage-*','graphite-explore-*','tikv-jemallocator-*','tikv-jemalloc-sys-*']:
  for d in (target/'.fingerprint').glob(glob):
   for f in d.glob('*.json'):
    if f.name.startswith(('lib-','bin-','run-build')): fps[f.name]=json.loads(f.read_text())
 result['variants'][variant]={'binary':str(b),'sha256':sha(b.read_bytes()),'sections':sections(b.read_bytes()),'fingerprints':fps,'rustc':json.loads((root/'target/.rustc_info.json').read_text())}
for crate in ['graphite_cypher','graphite_storage','graphite_explore','tikv_jemallocator','tikv_jemalloc_sys']:
 paths=[next((r/'target/aarch64-apple-darwin/release/deps').glob('lib'+crate+'-*.rlib')) for r in [V/'main',R]]
 objs=[archive(p.read_bytes()) for p in paths]; common=sorted(objs[0].keys()&objs[1].keys()); mismatches=[]
 for name in common:
  sec=sorted(objs[0][name].keys()|objs[1][name].keys())
  diff={s:{'baseline':objs[0][name].get(s),'candidate':objs[1][name].get(s)} for s in sec if objs[0][name].get(s)!=objs[1][name].get(s)}
  if diff: mismatches.append({'object':name,'sections':diff})
 result['rlibComparisons'][crate]={'sha256':[sha(p.read_bytes()) for p in paths],'objectCounts':[len(o) for o in objs],'sameObjectNames':objs[0].keys()==objs[1].keys(),'objectsWithChangedSections':len(mismatches),'changedTextObjects':sum('__TEXT,__text' in x['sections'] for x in mismatches),'mismatches':mismatches}
(V/'server-codegen-diagnosis.json').write_text(json.dumps(result,indent=2)+'\n')
print('Fingerprints identical:',result['variants']['baseline']['fingerprints']==result['variants']['candidate']['fingerprints'])
for k,x in result['rlibComparisons'].items(): print(k,{a:b for a,b in x.items() if a!='mismatches'});print('Sections changed',sorted({s for m in x['mismatches'] for s in m['sections']}))
