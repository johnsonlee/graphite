from pathlib import Path
import subprocess,json,hashlib
R=Path('/Users/johnsonlee/.codex/worktrees/ac7b5da2-2450-48c5-894c-5fd84ab6cb7d/graphite')
O=Path(__file__).resolve().parent
FROZEN='4e328b0109e13c896b74004823fb049fcb19251a';PARENT='aede4c82f66a925ba9df3fc8588c6e1399c17f61';CANDIDATE='4215b66e462675baeb3e1b1f2013cf7e6de01812';EXPECTED='b94b8caa8dea10d1d2ddb74a0a0c39a3ab5351f0'
TARGET='graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedWebGraphBackedGraph.kt'
def git(*args):return subprocess.check_output(['git','-C',str(R),*args])
def blob(ref,p):return git('show',ref+':'+p)
def paths(ref):return set(git('ls-tree','-r','--name-only',ref).decode().splitlines())
def sha(b):return hashlib.sha256(b).hexdigest()
def filesha(p):return sha(p.read_bytes())
head=git('rev-parse','HEAD').decode().strip();assert head==EXPECTED
assert git('rev-parse',head+'^').decode().strip()==CANDIDATE
assert git('rev-parse',CANDIDATE+'^').decode().strip()==PARENT
sets={ref:paths(ref) for ref in [FROZEN,PARENT,CANDIDATE,head]}
prod=lambda ps:{p for p in ps if '/src/main/' in p or '/src/jmh/' in p}
tests=lambda ps:{p for p in ps if '/src/test/' in p}
assert prod(sets[head])==prod(sets[FROZEN])==prod(sets[PARENT])==prod(sets[CANDIDATE])
assert tests(sets[head])==tests(sets[PARENT])==tests(sets[CANDIDATE])
assert len(prod(sets[head]))==130 and len(tests(sets[head]))==168
production=[];testfiles=[]
for p in sorted(prod(sets[head])):
 current=blob(head,p);assert current==blob(FROZEN,p)==(R/p).read_bytes();production.append({'path':p,'sha256':sha(current)})
for p in sorted(tests(sets[head])):
 current=blob(head,p);assert current==blob(PARENT,p)==blob(CANDIDATE,p)==(R/p).read_bytes();testfiles.append({'path':p,'sha256':sha(current)})
pre=json.loads((O/'prebuild-source-receipt.json').read_text());base=blob(FROZEN,TARGET);candidate=blob(CANDIDATE,TARGET);reverted=blob(head,TARGET)
assert sha(candidate)==pre['sourceAfterSha256'] and sha(reverted)==pre['sourceBeforeSha256']
old=pre['exactReplacement']['old'].encode();new=pre['exactReplacement']['new'].encode()
start=base.index(b'    private fun parallelRawDistinctCallSiteStringProjection(');end=base.index(b'\n    private ',start+1)
section=base[start:end];assert section.count(old)==1
assert candidate==base[:start]+section.replace(old,new,1)+base[end:] and reverted==base
candidate_to_revert=git('diff','--name-only',CANDIDATE,head).decode().splitlines();non_docs=[p for p in candidate_to_revert if not p.startswith('docs/')];assert non_docs==[TARGET]
parent_to_revert=git('diff','--name-only',PARENT,head).decode().splitlines();assert parent_to_revert and all(p.startswith('docs/') for p in parent_to_revert)
assert git('diff','--name-only').decode().strip()=='' and git('diff','--cached','--name-only').decode().strip()==''
untracked=git('ls-files','--others','--exclude-standard','-z').decode().split('\0');untracked=[p for p in untracked if p]
assert set(untracked)==set(pre['unrelatedUntrackedSnapshot'])
u=[]
for p,snapshot in pre['unrelatedUntrackedSnapshot'].items():
 f=R/p;current={'sha256':filesha(f),'size':f.stat().st_size,'mtimeNs':f.stat().st_mtime_ns};assert current==snapshot;u.append({'path':p,**current})
# Check retained source+working copy+committed copy against every original copy receipt entry.
receiptpath='docs/profiling/attempt140/copy-receipt.json';copy=json.loads(blob(head,receiptpath));copies=[]
assert blob(CANDIDATE,receiptpath)==blob(head,receiptpath)==(R/receiptpath).read_bytes()
for item in copy['files']:
 src=Path(item['source']);dest=item['destination'];expected=item['sha256']
 assert filesha(src)==filesha(R/dest)==sha(blob(head,dest))==expected,dest
 assert blob(head,dest)==blob(CANDIDATE,dest),dest
 copies.append({'source':str(src),'destination':dest,'sha256':expected,'sourceWorkingCommittedAndCandidateEqual':True})
finalpath='docs/profiling/attempt140/independent-final-audits-receipt.json';final=json.loads(blob(head,finalpath));finalchecks=[]
for path,expected in final['filesSha256'].items():
 dest='docs/profiling/attempt140/'+path
 assert filesha(O/path)==filesha(R/dest)==sha(blob(head,dest))==expected;finalchecks.append({'path':path,'sha256':expected})
runnerpath='docs/profiling/attempt140/runner-copy-receipt.json';runner=json.loads(blob(head,runnerpath))
for item in runner:assert filesha(Path(item['source']))==filesha(Path(item['destination']))==item['sha256']
assert blob(CANDIDATE,finalpath)==blob(head,finalpath) and blob(CANDIDATE,runnerpath)==blob(head,runnerpath)
revert_receipt=json.loads(blob(head,'docs/profiling/attempt140/revert-source-receipt.json'))
assert revert_receipt['candidateCommit']==CANDIDATE and revert_receipt['candidateParent']==PARENT and revert_receipt['mainAndJmhCompared']==130 and revert_receipt['testFilesComparedToParent']==168 and not revert_receipt['accepted']
# Four previously retained pure-four OR correctness tests survive byte-for-byte.
test='graphite-webgraph/src/test/kotlin/io/johnsonlee/graphite/webgraph/ParallelDistinctDisjunctionTest.kt';test_text=blob(head,test).decode();assert test_text.count('@Test')==4
assert git('rev-parse','HEAD').decode().strip()==head
output={'result':'pass','scope':'Read-only git objects, working-tree files and retained copy-receipt hashes. No build, Java, measurement, commit or acceptance rerun.','head':head,'candidate':CANDIDATE,'parent':PARENT,'frozenMain':FROZEN,'candidateAndRevertAncestryVerified':True,'productionPathSetAndContentEqualFrozenMain':len(production),'testPathSetAndContentEqualParentAndCandidate':len(testfiles),'candidateToRevertFiles':candidate_to_revert,'candidateToRevertOnlyNonDocsFile':TARGET,'targetChangeIsExactReverse':True,'candidateSourceSha256':sha(candidate),'revertedSourceSha256':sha(reverted),'parentToRevertChangedFiles':parent_to_revert,'parentToRevertOnlyDocs':True,'trackedWorkspaceClean':True,'unrelatedUntrackedUnchanged':u,'pureFourOrTestsRetained':{'path':test,'tests':4,'sha256':sha(blob(head,test))},'copyReceiptSha256':sha(blob(head,receiptpath)),'copyReceiptEntriesVerified':len(copies),'copyReceiptFiles':copies,'finalIndependentAuditHashesVerified':finalchecks,'runnerCopyHashesVerified':runner,'production':production,'tests':testfiles,'decision':'Attempt140 remains rejected for repeated v3 mixed-four-few-rows regression. This audit verifies rollback/evidence integrity only; it does not reopen timing, prove CI pass, remove pools, or establish10x.'}
(O/'independent-revert-audit.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n')
print({'head':head,'production':len(production),'tests':len(testfiles),'copiedFiles':len(copies),'finalAuditHashes':len(finalchecks),'runnerHashes':len(runner),'parentDocsChanged':len(parent_to_revert),'untracked':len(u),'result':'pass'})
