"""Full response comparison; no timing/allocation assertions."""
import pathlib,json,collections,hashlib,subprocess
root=pathlib.Path(__file__).resolve().parents[1];ev=root/'streaming-pagination-review';v=ev/'final-comparator';base=ev/'baseline/cde-corpus';read=lambda p:json.loads(p.read_text())
# The preserved original corpus categorizer only reads JSON and retains all1048.
script=pathlib.Path('/tmp/graphite-go-lazy-cde-evidence/summarize_original.py')
subprocess.run(['python3',str(script),'--verification',str(v)],check=True)
a=read(v/'original-summary.json');b=read(ev/'baseline/cde-original/original-summary.json');changes=[dict(before=x,after=y)for x,y in zip(b['pairs'],a['pairs'])if x['comparison']!=y['comparison']]
assert len(changes)==28 and all(x['after']['comparison']=='equal'for x in changes)
(v/'b28-repaired.json').write_text(json.dumps(changes,indent=2)+'\n')
assert a['comparison']=={'equal':1044,'both-success-difference':4}
checks={};differences={}
for p in sorted(base.glob('*.json')):
 q=v/'original-corpus'/p.name
 if not q.is_file():continue
 x,y=read(p),read(q);checks[p.name]=x==y
 if x!=y:differences[p.name]=[dict(before=a,after=b)for a,b in zip(x,y)if a!=b]
assert set(differences)<= {'primary-native.json','supplementary-native.json','generic-fault-audit.json','main-string-source-main.json-native.json'}
# Lazy source successful/error public responses stay fixed; cancelled task
# interleavings may change only before/after mapped-view initialization receipts.
def without_state(x):
 if isinstance(x,dict):return{k:without_state(v)for k,v in x.items()if k not in ['before','after']}
 if isinstance(x,list):return[without_state(v)for v in x]
 return x
source='main-string-source-main.json-native.json'
assert without_state(read(base/source))==without_state(read(v/'original-corpus'/source))
fault='generic-fault-audit.json';old,new=read(base/fault),read(v/'original-corpus'/fault)
assert len(old)==len(new)==432 and sum(x['mainEqual']for x in old)==408 and all(x['mainEqual']for x in new)
assert sum(x['candidate']!=y['candidate']for x,y in zip(old,new))==24
for name,records in differences.items():(v/(name+'.changes.json')).write_text(json.dumps(records,indent=2)+'\n')
(v/'corpus-comparison.json').write_text(json.dumps(dict(exactFiles=checks,changedFiles=list(differences),original=a['comparison'],genericFault=dict(total=432,beforeEqual=408,afterEqual=432)),indent=2)+'\n')
# The original retained/raw source histories are unaffected by B.
history_baseline=ev/'baseline/history';history_diffs=[]
for p in sorted(history_baseline.glob('*.json')):
 q=v/'history'/p.name
 if q.is_file() and read(p)!=read(q):history_diffs.append(p.name)
(v/'history-file-comparison.json').write_text(json.dumps(dict(compared=[p.name for p in history_baseline.glob('*.json')if(v/'history'/p.name).is_file()],changed=history_diffs),indent=2)+'\n')
print('B28 repaired; 1044/1048; 432/432 fault; history differences:',history_diffs)
