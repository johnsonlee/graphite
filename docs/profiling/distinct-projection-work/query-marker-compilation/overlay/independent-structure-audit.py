"""Second check from ZIP content and javap output; does not execute/import patch.py."""
from pathlib import Path
from collections import Counter
import difflib,hashlib,json,re,struct,zipfile
P=Path(__file__).resolve().parent
BASE=Path('/private/tmp/graphite-next-baseline.T2FTs9/graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar')
OUT=P/'diagnostic-jmh.jar'
def sha(b):return hashlib.sha256(b).hexdigest()
assert sha(BASE.read_bytes())=='a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe'
changes=[];names=Counter();same=0
with zipfile.ZipFile(BASE) as a,zipfile.ZipFile(OUT) as b:
 aa,bb=a.infolist(),b.infolist();assert len(bb)==len(aa)+2
 for i,left in enumerate(aa):
  right=bb[i];assert right.filename==left.filename
  names[left.filename]+=1
  before,after=a.read(left),b.read(right)
  if before!=after:changes.append({'ordinal':i,'name':left.filename,'oldSha256':sha(before),'newSha256':sha(after)})
  else:same+=1
 assert [x['name'] for x in changes]==['io/johnsonlee/graphite/webgraph/LargeBroadQueryPressureBenchmark.class']
 added=[x.filename for x in bb[len(aa):]]
 assert set(added)=={'io/johnsonlee/graphite/diagnostic/QueryExecutionMarker.class','io/johnsonlee/graphite/diagnostic/QueryExecutionMarker$QueryWindow.class'}
 for name in added:assert name not in names
 old=a.read(changes[0]['name']);new=b.read(changes[0]['name'])
def cp_end(data):
 count=struct.unpack_from('>H',data,8)[0];p=10;i=1
 while i<count:
  tag=data[p];p+=1
  if tag==1:n=struct.unpack_from('>H',data,p)[0];p+=2+n
  elif tag in (3,4,9,10,11,12,17,18):p+=4
  elif tag in (5,6):p+=8;i+=1
  elif tag in (7,8,16,19,20):p+=2
  elif tag==15:p+=3
  else:raise AssertionError(tag)
  i+=1
 return count,p
n1,p1=cp_end(old);n2,p2=cp_end(new)
assert n2==n1+5 and p2-p1==208
assert old[:8]==new[:8] and old[10:p1]==new[10:p1]
suffix1,suffix2=old[p1:],new[p2:];assert len(suffix1)==len(suffix2)
diffs=[{'offsetFromClassBody':i,'before':a,'after':b} for i,(a,b) in enumerate(zip(suffix1,suffix2)) if a!=b]
assert len(diffs)==2 and [(x['before'],x['after']) for x in diffs]==[(182,184),(77,240)] and diffs[1]['offsetFromClassBody']==diffs[0]['offsetFromClassBody']+2
oldtext=(P/'baseline-benchmark.javap.txt').read_text();newtext=(P/'diagnostic-benchmark.javap.txt').read_text()
removed=[x[2:] for x in difflib.ndiff(oldtext.splitlines(),newtext.splitlines()) if x.startswith('- ')]
inserted=[x[2:] for x in difflib.ndiff(oldtext.splitlines(),newtext.splitlines()) if x.startswith('+ ')]
assert len(removed)==len(inserted)==1
assert re.search(r'36: invokevirtual #2125 .*CrossGraphCypherExecutor.execute:',removed[0])
assert re.search(r'36: invokestatic\s+#2288 .*QueryExecutionMarker.execute:',inserted[0])
(P/'benchmark-bytecode.diff').write_text(''.join(difflib.unified_diff(oldtext.splitlines(True),newtext.splitlines(True),fromfile='frozen benchmark',tofile='diagnostic benchmark')))
helper=(P/'marker-helper.javap.txt').read_text();event=(P/'marker-event.javap.txt').read_text()
method=helper.split('public static io.johnsonlee.graphite.cypher.CypherResult execute(',1)[1].split('private static ',1)[0]
assert method.count('CrossGraphCypherExecutor.execute:')==1
assert '26: astore        5' in method and '28: aload         5' in method and '30: astore        4' in method and '32: aload         5' in method and '34: athrow' in method
assert '9    17    26   Class java/lang/Throwable' in method
assert 'finish:' in method and '45: athrow' in method
assert 'graphite.diagnostic.QueryExecution' in event and '0 ns' in event
catalog_path=Path('/private/tmp/graphite-query-marker-capture/original-catalog.json')
catalog=json.loads(catalog_path.read_text());assert len(catalog)==34
assert [x['ordinal'] for x in catalog]==list(range(1,35))
assert all(isinstance(k,str) and isinstance(v,str) for q in catalog for k,v in q['parameters'].items())
params=[{'ordinal':q['ordinal'],'id':q['id'],'parametersJson':json.dumps(q['parameters'],sort_keys=True,ensure_ascii=True,separators=(',',':'))} for q in catalog]
assert sum(bool(q['parameters']) for q in catalog)==3
receipt={'passed':True,'ranPatchScriptForVerification':False,'diagnosticJarSha256':sha(OUT.read_bytes()),'jarMode':oct(OUT.stat().st_mode&0o777),'originalEntries':len(aa),'unchangedEntries':same,'changedOriginalEntries':changes,'addedEntries':added,'duplicateOriginalEntryNames':{n:c for n,c in names.items() if c>1},'allOriginalNamesAndEntryOrderPreserved':True,'constantPoolCounts':[n1,n2],'appendedConstantPoolBytes':208,'allOtherClassBytesIdenticalExcept':diffs,'javapDifferenceOnlyOneInvocation':{'before':removed,'after':inserted},'actualHelperBytecodePreservesOriginalThrowableReference':True,'workloadCatalogSha256':sha(catalog_path.read_bytes()),'expectedParameters':params,'realQueriesExecuted':0,'jfrRecordingsStarted':0}
(P/'independent-structure-audit.json').write_text(json.dumps(receipt,indent=2)+'\n')
print('PASS',same,'original entries unchanged;',len(changes),'modified;',len(added),'added; only one benchmark invocation changed')
