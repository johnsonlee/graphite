import pathlib,subprocess,json,hashlib,shutil,os,gzip
root=pathlib.Path(__file__).resolve().parents[1];ev=root/'streaming-pagination-review';out=pathlib.Path('/tmp/graphite-go-streaming-pagination-freeze');out.mkdir(exist_ok=False)
sha=lambda b:hashlib.sha256(b).hexdigest()
owned=['graphite-server/internal/query/'+s for s in ['engine.go','streaming_pagination.go','streaming_order.go','streaming_pagination_test.go','streaming_sources_test.go','streaming_lifecycle_test.go']]+[str(p.relative_to(root))for p in sorted((root/'graphite-server/internal/query/testdata/streaming-pagination').rglob('*'))if p.is_file()]
index=ev/'freeze.index';env=dict(os.environ,GIT_INDEX_FILE=str(index))
def git(*args):return subprocess.check_output(['git',*args],cwd=root,env=env)
git('read-tree','faf2bee8fe4fc845442cf775f212cd70b0a96bbd');git('add','--',*owned);tree=git('write-tree').decode().strip()
for name,base in [('incremental-from-cde.patch','faf2bee8fe4fc845442cf775f212cd70b0a96bbd'),('complete-from-main-source.patch','baf9ae489cae2477bc52c7a83561cfd0ff449560'),('complete-from-87aaf0ad.patch','87aaf0ad')]:
 (out/name).write_bytes(git('diff','--binary',base,tree,'--'))
(out/'module-source.tar.gz').write_bytes(gzip.compress(git('archive',tree,'graphite-server'),mtime=0))
(out/'tree.txt').write_text(tree+'\n')
modulefiles=[p for p in git('ls-tree','-r','--name-only',tree,'graphite-server').decode().splitlines()]
(out/'module-files.json').write_text(json.dumps({p:sha((root/p).read_bytes())for p in modulefiles},indent=2)+'\n')
(out/'delta-files.json').write_text(json.dumps({p:sha((root/p).read_bytes())for p in owned},indent=2)+'\n')
shutil.copyfile(ev/'production-files.json',out/'production-files.json');shutil.copyfile(root/'graphite-server/internal/query/testdata/streaming-pagination/README.md',out/'README.md')
# Preserve original failure files and exact commands, not thousands of disposable
# tiny source copies. Fixture receipt and capture helpers reproduce those inputs.
for p in ev.rglob('*'):
 if not p.is_file() or p.name=='freeze.index':continue
 relative=p.relative_to(ev)
 if relative.parts[0]=='jvm' and len(relative.parts)>1 and (relative.parts[1].startswith('case-') or relative.parts[1].startswith('source-case-') or relative.parts[1] in ['classes','fixtures']):continue
 if p.suffix not in ['.json','.jsonl','.log','.txt','.py','.java','.md','.patch']:continue
 target=out/'evidence'/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,target)
meta=dict(baseCommit=git('rev-parse','HEAD').decode().strip(),mainSourceTree='baf9ae489cae2477bc52c7a83561cfd0ff449560',requiredCDETree='faf2bee8fe4fc845442cf775f212cd70b0a96bbd',candidateTree=tree,worktree=str(root),productionManifestSHA256=sha((out/'production-files.json').read_bytes()),original=dict(total=1048,baseEqual=1016,equal=1044,remaining=4,bRepaired=28),genericFault=dict(total=432,baseEqual=408,equal=432),newMain=dict(designResponses=53,newDirectResponses=480,unknownLabelResponses=24,sourceHistories=28,sourceExecutions=38,uniqueResponses=595),comparison='Full JSON-value responses; numeric spelling differences are not byte parity',checks=dict(moduleRaceExit=0,moduleVetExit=0,targetedRace10Exit=0),performanceRuns=0,commits=0)
(out/'manifest.json').write_text(json.dumps(meta,indent=2)+'\n')
print(out,tree,len(owned),len(modulefiles))
