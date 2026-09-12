from pathlib import Path
import gzip,hashlib,json,tarfile,re
P=Path(__file__).resolve().parent
EXCLUDE={'evidence.tar.gz','archive-manifest.json','archive-validation.json','pr-before.json','pr-body-template.md'}
DIRS={'source-snapshot','tool-source','checks-test-results','old34-pairs','v3-control-final'}
def selected():
 out=[]
 for f in P.iterdir():
  if f.is_file() and f.name not in EXCLUDE and f.suffix not in ('.jar','.class') and not f.name.endswith('.part'):out.append(f)
  if f.is_dir() and f.name in DIRS:
   out.extend(x for x in f.rglob('*') if x.is_file() and x.suffix not in ('.jar','.class') and 'classes' not in x.relative_to(f).parts and '__pycache__' not in x.parts)
 return sorted(out,key=lambda f:f.relative_to(P).as_posix())
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
assert not (P/'evidence.tar.gz').exists()
for required in ['method-control-audit.md','method-control-audit.json','control-and-gate-audit.md','control-and-gate-audit.json']:assert (P/required).is_file()
files=selected()
direct=[f.relative_to(P).as_posix() for f in files if f.parent==P and (f.suffix in ('.md','.py') or f.suffix=='.json' and f.name not in {'method-control.json'})]
direct+=['old34-pairs/local-progress.json','old34-pairs/global-wide-status.json','old34-pairs/global-wide-report.md','v3-control-final/run.json','evidence.tar.gz','archive-manifest.json','archive-inventory.md','direct-copy-list.json','archive-validation.json']
direct=sorted(set(direct));(P/'direct-copy-list.json').write_text(json.dumps({'destinationSuggestion':'candidate/docs/profiling/attempt146/','executedCopy':False,'preserveRelativePaths':True,'files':direct,'rawData':'other raw logs/JMH/TSV/oracle/XML/source/tool snapshots remain in evidence.tar.gz','history':'README and chronology snippet describe precommit state; future commit/CI addenda must not rewrite raw evidence'},indent=2)+'\n')
(P/'archive-inventory.md').write_text('# 本地证据归档范围\n\n纳入顶层实验计划、源码/构建/控制/门槛审计、命令与日志；source-snapshot、tool-source、checks-test-results、old34-pairs、v3-control-final。完整原值与失败边界均保留，未执行新采集。\n\n排除 candidate clone、全部 JAR/.class/classes、publish/、pr-before.json、pr-body-template.md、生成的压缩包和外部manifest本身。PR操作草稿不是测量证据。v3 runner class不入包，但既有run/审计保留其身份；Java源码另有快照。\n\n[完整逐文件manifest](archive-manifest.json)含SHA与大小，[直接复制建议](direct-copy-list.json)保留相对路径。尚未复制到candidate或root，也未commit/推送。\n')
files=selected();assert all(not f.is_symlink() for f in files)
manifest=[{'path':f.relative_to(P).as_posix(),'bytes':f.stat().st_size,'sha256':sha(f)} for f in files]
with (P/'evidence.tar.gz.part').open('wb') as raw:
 with gzip.GzipFile(filename='',mode='wb',mtime=0,fileobj=raw) as gz:
  with tarfile.open(fileobj=gz,mode='w|') as tf:
   for f,item in zip(files,manifest):
    info=tarfile.TarInfo(item['path']);info.size=item['bytes'];info.mode=0o644;info.mtime=0
    with f.open('rb') as stream:tf.addfile(info,stream)
(P/'evidence.tar.gz.part').rename(P/'evidence.tar.gz')
actual=[]
with tarfile.open(P/'evidence.tar.gz','r:gz') as tf:
 for member in tf:
  assert member.isfile();data=tf.extractfile(member).read();actual.append({'path':member.name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
assert actual==manifest
assert all(f.stat().st_size==r['bytes'] and sha(f)==r['sha256'] for f,r in zip(files,manifest))
record={'scope':'146 local evidence, precommit/unpushed/CI planned only','source':str(P),'memberCount':len(manifest),'uncompressedBytes':sum(r['bytes'] for r in manifest),'archive':{'path':'evidence.tar.gz','bytes':(P/'evidence.tar.gz').stat().st_size,'sha256':sha(P/'evidence.tar.gz')},'files':manifest,'readback':{'exactOrder':True,'allMemberSizeAndSha256':True,'sourceUnchangedAfterArchive':True},'exclusions':sorted(EXCLUDE|{'candidate/','publish/','*.jar','*.class','classes/'}),'accepted':False,'ciRun':False}
(P/'archive-manifest.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
links=[]
for f in (P/'README.md',P/'archive-inventory.md'):
 for target in re.findall(r'\]\(([^)]+)\)',f.read_text()):
  path=f.parent/target.split('#',1)[0];links.append({'from':f.name,'target':target,'exists':path.exists(),'includedInDirectCopyPlan':target in direct})
assert all(x['exists'] and x['includedInDirectCopyPlan'] for x in links)
assert all((P/x).exists() for x in direct if x!='archive-validation.json')
(P/'archive-validation.json').write_text(json.dumps({'readback':record['readback'],'readmeLinks':links,'allReadmeRelativeLinksValid':True,'directCopyPlanFiles':len(direct),'actualCopyToCandidateOrRoot':False},ensure_ascii=False,indent=2)+'\n')
print(json.dumps({k:record[k] for k in ('memberCount','uncompressedBytes','archive')},indent=2));print('links',len(links),'direct',len(direct))
