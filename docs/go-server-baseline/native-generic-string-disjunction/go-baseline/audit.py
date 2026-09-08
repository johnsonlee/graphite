#!/usr/bin/env python3
import hashlib,json,pathlib,subprocess,tarfile,gzip
HERE=pathlib.Path(__file__).resolve().parent
ROOT=HERE.parents[3]
BASE=pathlib.Path('/Users/johnsonlee/.codex/benchmarks/graphite/generic-string-go-baseline-513e2b96-v1')
CAPTURE=BASE/'capture';MODULE=BASE/'module';HEAD='513e2b96d4cd5136560461d06f3b804e6aa67c41'
GO=pathlib.Path('/opt/homebrew/Cellar/go/1.22.0/libexec/bin/go')
def sha(b):return hashlib.sha256(b).hexdigest()
def dump(p,x):p.write_text(json.dumps(x,indent=2)+'\n')
def entry(p):return {'path':str(p),'size':p.stat().st_size,'sha256':sha(p.read_bytes())}
# Git objects independently prove all tracked files in the original full module.
original=json.loads((HERE/'frozen-source.json').read_text());byname={x['path']:x for x in original['files']}
tracked=subprocess.check_output(['git','ls-tree','-r','--name-only',HEAD,'graphite-server'],cwd=ROOT,text=True).splitlines()
for name in tracked:
 raw=subprocess.check_output(['git','show',HEAD+':'+name],cwd=ROOT);assert sha(raw)==byname[name.removeprefix('graphite-server/')]['sha256'],name
for x in json.loads((CAPTURE/'inputs.json').read_text()):assert entry(pathlib.Path(x['path']))==x,x['path']
# Record exact compiler/linker and all Go dependency package inputs after build.
version=subprocess.check_output([str(GO),'version'],text=True).strip()
env=json.loads(subprocess.check_output([str(GO),'env','-json'],cwd=MODULE))
raw=subprocess.check_output([str(GO),'list','-deps','-test','-json','./internal/query'],cwd=MODULE);(CAPTURE/'go-list-deps.json').write_bytes(raw)
s=raw.decode();decoder=json.JSONDecoder();packages=[];position=0
while position<len(s):
 while position<len(s)and s[position].isspace():position+=1
 if position==len(s):break
 p,n=decoder.raw_decode(s,position);position=n;packages.append(p)
paths=set()
for p in packages:
 directory=pathlib.Path(p.get('Dir','/nonexistent'))
 for key in ('GoFiles','CgoFiles','CFiles','CXXFiles','MFiles','HFiles','FFiles','SFiles','SwigFiles','SwigCXXFiles','SysoFiles','EmbedFiles','TestGoFiles','XTestGoFiles','TestEmbedFiles','XTestEmbedFiles'):
  for name in p.get(key,[]):
   f=directory/name
   if f.is_file():paths.add(f)
 for key in ('GoMod',):
  name=p.get('Module',{}).get(key)
  if name and pathlib.Path(name).is_file():paths.add(pathlib.Path(name))
for name in ('compile','link','asm','buildid'):paths.add(pathlib.Path(env['GOTOOLDIR'])/name)
dump(CAPTURE/'dependency-inputs.json',{'recordedAfterSuccessfulBuild':True,'goVersion':version,'goEnvironment':env,'packages':len(packages),'files':[entry(p)for p in sorted(paths)]})
# The fixture tar is byte-exact to the original audited variant manifest.
manifest=json.loads((HERE.parent/'fixture-variants.json').read_text())
with tarfile.open(HERE.parent/'fixtures.tar.gz') as archive:
 expected={x['file']:x for x in manifest['files']}
 assert set(archive.getnames())==set(expected)
 for member in archive.getmembers():
  content=archive.extractfile(member).read();assert len(content)==expected[member.name]['bytes'] and sha(content)==expected[member.name]['sha256']
fixtures=json.loads((CAPTURE/'fixtures-before.json').read_text());assert len(fixtures)==3036
for f in fixtures:assert sha((CAPTURE/'fixtures'/f['path']).read_bytes())==f['sha256']
comparison=json.loads((CAPTURE/'comparison.json').read_text());assert comparison['cases']==201
for c in comparison['comparisons']:assert c['matches']=={'public':157,'providerWrapper':19} and len(c['differences'])==25
assert not comparison['fixtureOriginalChanged'];assert all(x['path'].endswith('/graph.callsite-string-index')for x in comparison['fixtureAdded'])
dump(CAPTURE/'independent-audit.json',{'head':HEAD,'trackedModuleFilesVerified':len(tracked),'frozenModuleFiles':len(byname),'inputHashesVerified':True,'dependencyPackages':len(packages),'dependencyFiles':len(paths),'fixtureOriginalFilesVerified':len(fixtures),'fixtureOnlyAddedCallsiteSidecars':len(comparison['fixtureAdded']),'captureExitCode':0,'comparisonExitCode':1,'publicMatchesEachReference':157,'providerWrapperMatchesEachReference':19,'differencesEachReference':25,'performanceMeasurements':0})
# Archive all compact capture artifacts, source, and exact executable. Fixtures are reproducible from parent archive.
for name in ('source-before.json','inputs.json','input.json','compiled-source.json','source.tar.gz','compile.stdout','compile.stderr','capture.stdout','capture.stderr','commands.json','go.json','comparison.json','source-after.json','dependency-inputs.json','independent-audit.json'):
 (HERE/name).write_bytes((CAPTURE/name).read_bytes())
for name in ('fixtures-before.json','fixtures-after.json','go-list-deps.json','diagnostic.test'):
 (HERE/(name+'.gz')).write_bytes(gzip.compress((CAPTURE/name).read_bytes(),mtime=0))
# Preserve initial capture manifest; later-added independent audit is a distinct step.
(HERE/'capture-artifact-manifest.json').write_bytes((CAPTURE/'artifact-manifest.json').read_bytes())
print(json.dumps(json.loads((CAPTURE/'independent-audit.json').read_text()),indent=2))
