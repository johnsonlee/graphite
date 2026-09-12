from pathlib import Path
import hashlib,json,struct,warnings,zipfile
P=Path(__file__).resolve().parent
BASE=Path('/private/tmp/graphite-next-baseline.T2FTs9/graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar')
EXPECTED='a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe'
TARGET='io/johnsonlee/graphite/webgraph/LargeBroadQueryPressureBenchmark.class'
HELPER='io/johnsonlee/graphite/diagnostic/QueryExecutionMarker'
NAME='replay$lambda$33$lambda$29'
DESC='(Ljava/util/List;Lio/johnsonlee/graphite/cypher/CypherExecutionContext;Lio/johnsonlee/graphite/webgraph/BroadQueryCase;)Lio/johnsonlee/graphite/cypher/CypherResult;'
def sha(x):return hashlib.sha256(x).hexdigest()
assert sha(BASE.read_bytes())==EXPECTED
class R:
 def __init__(self,b):self.b=b;self.p=0
 def n(self,n):v=self.b[self.p:self.p+n];assert len(v)==n;self.p+=n;return v
 def u1(self):return self.n(1)[0]
 def u2(self):return int.from_bytes(self.n(2),'big')
 def u4(self):return int.from_bytes(self.n(4),'big')
def parse(b):
 r=R(b);assert r.u4()==0xcafebabe;r.n(4);cp=[None]*r.u2();i=1
 while i<len(cp):
  tag=r.u1()
  if tag==1:cp[i]=(tag,r.n(r.u2()).decode('utf8',errors='replace'))
  elif tag in (3,4):cp[i]=(tag,r.n(4))
  elif tag in (5,6):cp[i]=(tag,r.n(8));i+=1
  elif tag in (7,8,16,19,20):cp[i]=(tag,r.u2())
  elif tag in (9,10,11,12,17,18):cp[i]=(tag,r.u2(),r.u2())
  elif tag==15:cp[i]=(tag,r.u1(),r.u2())
  else:raise ValueError(tag)
  i+=1
 cp_end=r.p
 def attrs():
  out={}
  for _ in range(r.u2()):
   name=cp[r.u2()][1];size=r.u4();offset=r.p;out[name]=(offset,r.n(size))
  return out
 r.n(6);r.n(r.u2()*2)
 for _ in range(r.u2()):r.n(6);attrs()
 methods={}
 for _ in range(r.u2()):
  access=r.u2();name=cp[r.u2()][1];desc=cp[r.u2()][1];a=attrs();methods[(name,desc)]=a
 attrs();assert r.p==len(b)
 return cp,cp_end,methods
with zipfile.ZipFile(BASE) as z: original=z.read(TARGET)
cp,cp_end,methods=parse(original)
code_attr_offset,code_attr=methods[(NAME,DESC)]['Code']
code_length=int.from_bytes(code_attr[4:8],'big');assert code_length==40
code_offset=code_attr_offset+8
assert original[code_offset+36:code_offset+39]==bytes([182,8,77])
old_index=int.from_bytes(original[code_offset+37:code_offset+39],'big')
old=cp[old_index];assert old[0]==10
old_owner=cp[cp[old[1]][1]][1];nt=cp[old[2]]
old_name=cp[nt[1]][1];old_desc=cp[nt[2]][1]
assert old_owner=='io/johnsonlee/graphite/cypher/CrossGraphCypherExecutor' and old_name=='execute'
assert old_desc=='(Ljava/lang/String;Ljava/util/Map;)Lio/johnsonlee/graphite/cypher/CypherResult;'
helper_desc='(L'+old_owner+';'+old_desc[1:]
n=len(cp)
def utf(s):b=s.encode('utf8');return b'\x01'+struct.pack('>H',len(b))+b
extra=utf(HELPER)+b'\x07'+struct.pack('>H',n)+utf(helper_desc)+b'\x0c'+struct.pack('>HH',nt[1],n+2)+b'\x0a'+struct.pack('>HH',n+1,n+3)
patched=bytearray(original);patched[code_offset+36]=184;patched[code_offset+37:code_offset+39]=struct.pack('>H',n+4)
patched=bytes(patched[:8])+struct.pack('>H',n+5)+bytes(patched[10:cp_end])+extra+bytes(patched[cp_end:])
cp2,end2,methods2=parse(patched)
assert cp2[:n]==cp and len(cp2)==n+5 and end2==cp_end+len(extra)
assert methods.keys()==methods2.keys()
for key in methods:
 for attr,(offset,value) in methods[key].items():
  new=methods2[key][attr][1]
  if key==(NAME,DESC) and attr=='Code':
   expected=bytearray(value);expected[8+36]=184;expected[8+37:8+39]=struct.pack('>H',n+4);assert new==expected
  else:assert new==value
# Reversing exactly the appended constants and the 3 hook bytes restores every original class byte.
reversed_class=bytearray(patched[:8]+struct.pack('>H',n)+patched[10:cp_end]+patched[end2:])
reversed_class[code_offset+36:code_offset+39]=original[code_offset+36:code_offset+39]
assert bytes(reversed_class)==original
(P/'baseline-benchmark.class').write_bytes(original)
(P/'diagnostic-benchmark.class').write_bytes(patched)
helper_files=sorted((P/'classes').rglob('*.class'))
assert {f.relative_to(P/'classes').as_posix() for f in helper_files}=={HELPER+'.class',HELPER+'$QueryWindow.class'}
out=P/'diagnostic-jmh.jar';assert not out.exists()
with zipfile.ZipFile(BASE) as src,zipfile.ZipFile(out,'w') as dst:
 with warnings.catch_warnings():
  warnings.simplefilter('ignore',UserWarning)
  for info in src.infolist():dst.writestr(info,patched if info.filename==TARGET else src.read(info))
 for f in helper_files:dst.write(f,f.relative_to(P/'classes').as_posix(),compress_type=zipfile.ZIP_DEFLATED)
entries=[]
with zipfile.ZipFile(BASE) as src,zipfile.ZipFile(out) as dst:
 a,b=src.infolist(),dst.infolist();assert len(b)==len(a)+2
 for i,(left,right) in enumerate(zip(a,b)):
  assert left.filename==right.filename
  old,new=src.read(left),dst.read(right)
  assert old==new or left.filename==TARGET
  entries.append({'entryOrdinal':i,'name':left.filename,'beforeSha256':sha(old),'afterSha256':sha(new),'changed':old!=new})
 for info in b[len(a):]:entries.append({'entryOrdinal':len(entries),'name':info.filename,'beforeSha256':None,'afterSha256':sha(dst.read(info)),'changed':True})
assert sum(e['changed'] for e in entries)==3
assert sha(BASE.read_bytes())==EXPECTED
out.chmod(0o444)
(P/'entry-comparison.json').write_text(json.dumps(entries,indent=2)+'\n')
receipt={'baseJar':str(BASE),'baseJarSha256Before':EXPECTED,'baseJarSha256After':sha(BASE.read_bytes()),'diagnosticJar':str(out),'diagnosticJarSha256':sha(out.read_bytes()),'originalEntryCount':len(entries)-2,'uniqueOriginalNames':len({e['name'] for e in entries[:-2]}),'unchangedOriginalEntries':len(entries)-3,'modifiedOriginalEntries':[e for e in entries[:-2] if e['changed']],'addedEntries':entries[-2:],'modifiedClass':TARGET,'method':NAME+DESC,'bci':36,'oldInstruction':{'opcode':'invokevirtual','cpIndex':old_index,'owner':old_owner,'name':old_name,'descriptor':old_desc},'newInstruction':{'opcode':'invokestatic','cpIndex':n+4,'owner':HELPER,'name':'execute','descriptor':helper_desc},'methodCodeBytesBefore':40,'methodCodeBytesAfter':40,'constantPoolCountBefore':n,'constantPoolCountAfter':n+5,'appendedConstantPoolBytes':len(extra),'allOtherClassBytesReversibleExactly':True,'methodCount':len(methods),'allOtherMethodAttributesUnchanged':True,'productionClassesModified':[],'jarEntriesComparedByOrdinalIncludingDuplicateNames':True,'realQueriesExecuted':0,'jfrCapturesStarted':0,'entryComparisonSha256':sha((P/'entry-comparison.json').read_bytes())}
(P/'patch-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(receipt,indent=2))
