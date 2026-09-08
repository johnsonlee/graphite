"""Preserve every capture, including the initial fixture-design miss."""
from pathlib import Path
import hashlib,io,json,shutil,tarfile
HERE=Path(__file__).resolve().parent
BASE=Path('/Users/johnsonlee/.codex/benchmarks/graphite')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def dump(p,v):p.write_text(json.dumps(v,indent=2)+'\n')
def main():
 copied=[]
 for version,label in [('v1','initial-missing-callsite-control'),('v2','main-capture'),('v3','repeat-capture')]:
  src=BASE/('generic-prefix-main-'+version);dest=HERE/label;dest.mkdir(exist_ok=False)
  for f in sorted(src.rglob('*')):
   if not f.is_file() or f.relative_to(src).parts[0] in ('fixtures','variants'):continue
   target=dest/f.relative_to(src);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,target)
   assert sha(f)==sha(target);copied.append(dict(source=str(f),file=str(target.relative_to(HERE)),sha256=sha(f)))
  inputs=json.loads((src/'inputs.json').read_text())
  # Freeze source bytes behind external-path identities. The initial design is
  # reconstructed exactly and checked against its pre-execution SHA256.
  with tarfile.open(dest/'input-sources.tar.gz','w:gz') as t:
   for path,expected in inputs.items():
    p=Path(path)
    if p.suffix not in ('.java','.kt','.py','.json'):continue
    data=p.read_bytes()
    if version=='v1':
     if p.name=='prepare.py':data=data.replace(b"variants[name+'-first']=dict(call=True,matchingCall=first_call,locals=first_names)",b"variants[name+'-first']=dict(call=first_call,locals=first_names)")
     if p.name=='PrefixFixture.java':
      text=data.decode();text=text.replace('   var callMethod=spec.has("matchingCall")&&!spec.get("matchingCall").getAsBoolean()?new MethodDescriptor(type,"neutral-call",List.of(),type):method;\n','').replace('newInstance(1,callMethod,callMethod,','newInstance(1,method,method,');data=text.encode()
     if p.name=='fixture-specs.json':
      specs=json.loads(data)
      for spec in specs.values():
       if 'matchingCall' in spec:spec['call']=spec.pop('matchingCall')
      data=(json.dumps(specs,indent=2)+'\n').encode()
    assert hashlib.sha256(data).hexdigest()==expected,(version,path)
    info=tarfile.TarInfo(str(p).lstrip('/'));info.size=len(data);t.addfile(info,io.BytesIO(data))
 a=json.loads((HERE/'main-capture/main.json').read_text());b=json.loads((HERE/'repeat-capture/main.json').read_text())
 assert a==b
 a_files=json.loads((HERE/'main-capture/fixture-variants.json').read_text());b_files=json.loads((HERE/'repeat-capture/fixture-variants.json').read_text())
 different=[]
 for x,y in zip(a_files,b_files):
  assert x['file']==y['file']
  if x!=y:
   p=x['file'];left=(BASE/'generic-prefix-main-v2/variants'/p).read_bytes();right=(BASE/'generic-prefix-main-v3/variants'/p).read_bytes()
   assert Path(p).name=='forward.properties'
   assert b'\n'.join(left.splitlines()[:1]+left.splitlines()[2:])==b'\n'.join(right.splitlines()[:1]+right.splitlines()[2:])
   different.append(dict(file=p,mainWriterComment=left.splitlines()[1].decode(),repeatWriterComment=right.splitlines()[1].decode()))
 dump(HERE/'repeat-audit.json',dict(caseCount=len(a['cases']),completeOutputExact=True,fixtureFilesPerCapture=len(a_files),byteIdenticalFixtureFiles=len(a_files)-len(different),writerTimestampOnlyDifferences=different,performanceMeasurements=0))
 dump(HERE/'archive-copy-verification.json',dict(copied=copied,allCopiedBytesVerified=True))
 print('archived',len(copied),'files; full output exact')
if __name__=='__main__':main()
