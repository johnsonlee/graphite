import pathlib,subprocess,os,shutil,json,hashlib,tarfile,datetime
root=pathlib.Path(__file__).resolve().parent.parent;out=root/'main-candidate-review'
def git(*args,env=None):return subprocess.check_output(['git',*args],cwd=root,env=env)
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
new=git('ls-files','--others','--exclude-standard','--','graphite-server/internal/query/main_string*.go','graphite-server/internal/query/testdata/main-string-source','graphite-server/internal/store/main_*.go','graphite-server/internal/store/projection_property*.go').decode().splitlines()
tracked=git('diff','--name-only').decode().splitlines()
newpatch=b''
for p in new:
 x=subprocess.run(['git','diff','--no-index','--binary','--full-index','--','/dev/null',p],cwd=root,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 assert x.returncode==1,(p,x.stderr);newpatch+=x.stdout
(out/'incremental-from-ordinary-sync.patch').write_bytes(git('diff','--binary','--full-index')+newpatch)
(out/'complete-from-87aaf0ad.patch').write_bytes(git('diff','--binary','--full-index','HEAD')+newpatch)
baseindex=pathlib.Path(git('rev-parse','--git-path','index').decode().strip())
if not baseindex.is_absolute():baseindex=root/baseindex
index=out/'check-incremental.index';shutil.copyfile(baseindex,index)
env={**os.environ,'GIT_INDEX_FILE':str(index)}
baseTree=git('write-tree',env=env).decode().strip()
git('apply','--cached','--check',str(out/'incremental-from-ordinary-sync.patch'),env=env)
git('apply','--cached',str(out/'incremental-from-ordinary-sync.patch'),env=env)
finalTree=git('write-tree',env=env).decode().strip()
index2=out/'check-complete.index';index2.unlink(missing_ok=True);env2={**os.environ,'GIT_INDEX_FILE':str(index2)}
git('read-tree','HEAD',env=env2);git('apply','--cached','--check',str(out/'complete-from-87aaf0ad.patch'),env=env2);git('apply','--cached',str(out/'complete-from-87aaf0ad.patch'),env=env2)
assert git('write-tree',env=env2).decode().strip()==finalTree
files=sorted(set(git('ls-files').decode().splitlines())|set(new))
source={p:sha(root/p) for p in files};(out/'source-manifest.json').write_text(json.dumps(source,indent=2)+'\n')
with tarfile.open(out/'source.tar.gz','w:gz') as tar:
 for p in files:tar.add(root/p,arcname=p,recursive=False)
for f in (index,index2):f.unlink()
misplaced=root/'graphite-server/internal/main-candidate-review'
if misplaced.exists():shutil.move(str(misplaced),out/'mislocated-final-v2')
manifest=dict(baseCommit=git('rev-parse','HEAD').decode().strip(),composedBaselineTree=baseTree,finalTree=finalTree,createdUTC=datetime.datetime.now(datetime.timezone.utc).isoformat(),sourceFiles=len(files),incrementalFiles=sorted(set(tracked)|set(new)),production=json.loads((out/'production-files.json').read_text()),artifacts={name:sha(out/name) for name in ['baseline/combined.patch','baseline/source-manifest.json','baseline/source.tar.gz','incremental-from-ordinary-sync.patch','complete-from-87aaf0ad.patch','source-manifest.json','source.tar.gz','production-files.json','results.json']},checks=[dict(command='INDEXED_DISTINCT_OUTPUT=/tmp/graphite-go-main-candidate-87aaf0ad/main-candidate-review/final-v4/original-corpus ORDINARY_HISTORY_OUTPUT=/tmp/graphite-go-main-candidate-87aaf0ad/main-candidate-review/final-v4/history go test -race ./...',cwd=str(root/'graphite-server'),exitCode=0,log='module-race-v4.log'),dict(command='go vet ./...',cwd=str(root/'graphite-server'),exitCode=0,log='module-vet-final.log'),dict(command='python3 graphite-server/internal/query/testdata/ordinary-projection/summarize.py --verification main-candidate-review/final-v4',cwd=str(root),exitCode=0),dict(command='python3 main-candidate-review/verify-results.py',cwd=str(root),exitCode=0)],mainJar=dict(path='/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar',sha256=sha(pathlib.Path('/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar'))),notes=['No commit, root/provider edit, 64 runtime or performance measurement.','Temporary index applications of both patches produced the identical final Git tree.','All 16 production hashes independently verified by contract receipt 9b210b8f8d9b5244a1701c52195c7f69a12d05fbcddae9555804ac66c17333fb.'])
manifest['evidenceFiles']={str(p.relative_to(out)):sha(p) for p in sorted(out.rglob('*')) if p.is_file() and p.name!='manifest.json'}
(out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps({k:manifest[k]for k in ['baseCommit','composedBaselineTree','finalTree','sourceFiles','artifacts']},indent=2));print('manifest',sha(out/'manifest.json'))
