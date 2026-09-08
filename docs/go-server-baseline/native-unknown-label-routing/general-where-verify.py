"""Verify actual-main captures and exact copied fixture mutations; no timing."""
from pathlib import Path
import gzip,hashlib,json,struct,tarfile
p=Path(__file__).resolve().parent
def read(name):return json.loads((p/name).read_text())
def gz(name):return json.loads(gzip.decompress((p/name).read_bytes()))
specs=read('general-where-cases.json');main=read('general-where-main.json');repeat=gz('general-where-repeat-main.json.gz')
assert len(specs)==len(main)==len(repeat)==8
fields=['name','spec','phase','outcome','columns','rows','types','error','qualifiedError','message','before','after','causes']
for spec,a,b in zip(specs,main,repeat):
 assert a['spec']==b['spec']==spec and a['phase']=='execute'
 for key in fields:assert a.get(key)==b.get(key),(a['name'],key)
 if spec['sources'][0]['mutationNode']==22:
  assert a['error']=='IllegalArgumentException' and a['message']=='Unknown node tag: -1'
 elif spec['name'].split('-')[2] in ['0','2']:
  assert a['error']=='CypherException' and a['message']=='Division by zero'
 else:assert a['outcome']=='SUCCESS' and a['rows']==[]
with tarfile.open(p/'general-where-initial6.tar.gz') as tar:
 assert json.load(tar.extractfile('general-where-cases.json'))==specs[:6]
 for old,new in zip(json.load(tar.extractfile('general-where-main.json')),main):
  for key in fields:assert old.get(key)==new.get(key)
source=read('general-where-source-fixture.json');original={e['file']:e for e in source['files']}
assert len(original)==20
for name,e in original.items():
 data=(Path(source['root'])/name).read_bytes()
 assert len(data)==e['bytes'] and hashlib.sha256(data).hexdigest()==e['sha256']
legacy=(Path(source['root'])/'graph.nodeindex').read_bytes()
entries=[struct.unpack_from('>IBQ',legacy,at) for at in range(8,len(legacy),13)]
assert [(n,o) for n,t,o in entries if t==0]==[(90,8),(41,75),(22,142)]
typed=(Path(source['root'])/'graph.typeindex').read_bytes()
count=struct.unpack_from('>I',typed,4)[0]
n,at=next((n,o) for pos in range(8,8+count*13,13) for t,n,o in [struct.unpack_from('>BIQ',typed,pos)] if t==0)
assert [struct.unpack_from('>I',typed,at+i*4)[0] for i in range(n)]==[90,41,22]
for index,audit in enumerate(read('general-where-fixture-audit.json'),1):
 cm={e['file']:e for e in gz(f'general-where-run{index}-fixture-copied.json.gz')}
 bm={e['file']:e for e in gz(f'general-where-run{index}-fixture-before.json.gz')}
 am={e['file']:e for e in gz(f'general-where-run{index}-fixture-after.json.gz')}
 mutations=gz(f'general-where-run{index}-mutations.json.gz')
 assert len(cm)==len(bm)==len(am)==160 and len(mutations)==4
 for name,e in cm.items():
  reference=original[Path(name).name]
  assert e['bytes']==reference['bytes'] and e['sha256']==reference['sha256']
 assert {name for name in cm if cm[name]!=bm[name]}=={e['file'] for e in mutations}
 for mutation in mutations:
  assert mutation['nodeID']==22 and mutation['nodeDataRecordOffset']==142 and mutation['tagByteOffset']==146
  data=bytearray((Path(source['root'])/'graph.nodedata').read_bytes())
  assert struct.unpack_from('>i',data,142)[0]==22 and data[146]==0
  data[146]=255
  assert mutation['originalSha256']==cm[mutation['file']]['sha256']
  assert mutation['mutatedSha256']==bm[mutation['file']]['sha256']==hashlib.sha256(data).hexdigest()
 assert bm==am and audit['changed']==audit['added']==audit['missing']==[]
summary=dict(cases=8,actualMainCalls=16,repeatPublicAndStateDifferences=0,originalSixPrefixPreserved=True,physicalGraphCopiesPerRun=8,originalFilesPerCopy=20,explicitMutationsPerRun=4,runtimeChangedFilesPerRun=0,performanceMeasurements=0)
(p/'general-where-verification.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary,indent=2))
