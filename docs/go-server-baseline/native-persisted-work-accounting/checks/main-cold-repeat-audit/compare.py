"""Compare independently captured original main; preserve all existing references."""
from pathlib import Path
import collections,csv,gzip,hashlib,json
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[4]
OLD=ROOT/'docs/go-server-baseline/native64-fullcase-replay/main-cold-complete'
BASE=Path('/Users/johnsonlee/.codex/benchmarks/graphite')
NEW=BASE/'persisted-work-f0838dda-main-cold-repeat-v1'
NATIVES=[BASE/'persisted-work-f0838dda-real64-v1',BASE/'persisted-work-f0838dda-real64-cold-repeat-v1']
def sha(p):
 h=hashlib.sha256()
 with p.open('rb')as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
def read(p):return json.loads(p.read_text())
def records(directory):
 p=directory/'responses.jsonl';p=p if p.exists()else Path(str(p)+'.gz')
 with(gzip.open(p,'rb')if p.suffix=='.gz'else p.open('rb'))as f:return[json.loads(line)for line in f]
def dump(p,x):p.write_text(json.dumps(x,indent=2)+'\n')
fields=['id','retained','mappedView','trigrams','loadedFromPersistence','mappedRangeCount','rawMatchCount','rawProjectionCount']
public=['columns','rows','canonical','error','message']
def compare(m,n):
 assert len(m)==len(n)
 diffs=[];outputs=[];observers=[];count=0;cases=0
 def states(a,b,at):
  nonlocal count
  assert len(a)==len(b)==64
  for x,y in zip(a,b):
   count+=1;assert x['id']==y['id']
   for k in fields:
    if x[k]!=y[k]:diffs.append(dict(observation=at,graph=x['id'],field=k,main=x[k],other=y[k]))
 for a,b in zip(m,n):
  assert a['kind']==b['kind']
  if a['kind']=='header':states(a['loaded'],b['loaded'],'loaded')
  elif a['kind']=='prepared':states(a['sources'],b['sources'],'prepared')
  elif a['kind']=='case':
   assert (a['phase'],a['index'],a['id'])==(b['phase'],b['index'],b['id']);cases+=1
   changed=[k for k in public if(k in a,a.get(k))!=(k in b,b.get(k))]
   if changed:outputs.append(dict(index=a['index'],id=a['id'],fields=changed))
   if a.get('errorClass')!=b.get('errorClass'):observers.append(dict(index=a['index'],field='errorClass',main=a.get('errorClass'),other=b.get('errorClass')))
   for when in ['before','after']:states(a[when],b[when],f"{a['phase']}/{a['index']}/{when}")
 return dict(cases=cases,graphStateObservations=count,publicDifferences=outputs,stateDifferenceCounts=dict(collections.Counter(d['field']for d in diffs)),firstStateDifference=diffs[0]if diffs else None,observerDifferences=observers),diffs
old=records(OLD);new=records(NEW/'capture');native=[records(n/'native-cold')for n in NATIVES]
main_result,main_diffs=compare(old,new);main_result['allParsedRecordsExactlyEqual']=old==new
reports=[]
for i,n in enumerate(native):
 r,ds=compare(new,n);r['nativeDirectory']=str(NATIVES[i]);reports.append(r)
 with gzip.open(NEW/f'native-{i+1}-state-differences.json.gz','wt')as f:json.dump(ds,f,indent=2)
workload=read(Path(read(NATIVES[0]/'module-source.json')['module'])/'internal/benchmarkcase/testdata/main64.json')
assert read(NEW/'capture/actual-cases.json')==read(OLD/'actual-cases.json')==workload['cases']
assert [r['id']for r in new[0]['loaded']]==workload['sourceOrder']
obs=list(csv.DictReader((NEW/'capture/main-observations.tsv').open(),delimiter='\t'));corr=[l.split('|')for l in(NEW/'capture/main-correctness.tsv').read_text().splitlines()];cs=[r for r in new if r['kind']=='case'];assert len(cs)==len(obs)==len(corr)==1267
verified=0;errors=[]
for c,o,r in zip(cs,obs,corr):
 assert c['id']==o['id']==r[0]
 if 'canonical'in c:
  b=c['canonical'].encode();assert o['digest']==r[13]==hashlib.sha256(b).hexdigest();assert int(o['rowCount'])==int(r[11])==len(c['rows']);assert int(o['responseBytes'])==int(r[12])==len(b);verified+=1
 else:errors.append({k:c[k]for k in ['index','id','error','errorClass','message']});assert o['outcome']==r[10]=='failed'
mods=[read(n/'module-source.json')for n in NATIVES];assert mods[0]['files']==mods[1]['files']
for m,n in zip(mods,NATIVES):
 for file,h in m['files'].items():assert sha(Path(m['module'])/file)==h,file
 rec=read(n/'native-cold-process.json');assert rec['status']=='exited'and rec['exitCode']==1 and rec['inputsUnchanged']and rec['binaryUnchanged']
ni=[]
for m,n in zip(mods,NATIVES):
 ni.append({str(Path(k).relative_to(m['module'])):v for k,v in read(n/'native-cold-inputs.json').items()})
assert ni[0]==ni[1]
receipt=read(NEW/'process.json');assert receipt['status']=='exited'and receipt['exitCode']==1 and receipt['classpathUnchanged']and receipt['inputsUnchanged']
report=dict(originalMainComparison=main_result,newMainToNativeComparisons=reports,nativeRepeatsAllParsedRecordsExactlyEqual=native[0]==native[1],nativeFrozenModuleFilesEqual=len(mods[0]['files']),nativeCompiledInputsEqual=len(ni[0]),originalCasesAndSourceOrderExactlyEqual=True,verifiedSuccessfulOriginalManifestDigests=verified,originalErrorsRetained=errors,mainRuntimeExitCode=receipt['exitCode'],originalReferenceReplaced=False,existingComparisonsModified=False,performanceMeasurement=False,interpretation='This independently repeated main capture does not justify relaxing mappedRangeCount comparison; report raw differences and fix semantics before claiming parity.'if not main_diffs and old==new else 'Main repeated observations differ; preserve exact differences without redefining reference or acceptance.')
dump(NEW/'comparison.json',report)
print(json.dumps(report,indent=2))
