from pathlib import Path
import subprocess,json,hashlib
R=Path(__file__).parent;repo=Path('/Users/johnsonlee/.codex/worktrees/ac7b5da2-2450-48c5-894c-5fd84ab6cb7d/graphite')
HEAD='27de1f5ebd318fb5f60b24596712a5b3a6a3836e';PARENT='e6c932c5e1d0fb7b583ceb9e14c8ef88ec9d9694';MAIN='4e328b0109e13c896b74004823fb049fcb19251a';CANDIDATE='470df7cea888240b87380f1a4a650638ea713815'
def git(*args):return subprocess.check_output(['git',*args],cwd=repo)
def files(ref):return git('ls-tree','-r','--name-only',ref).decode().splitlines()
def blob(ref,f):return git('show',ref+':'+f)
def sha(data):return hashlib.sha256(data).hexdigest()
assert git('rev-parse','HEAD').decode().strip()==HEAD
hfiles=files(HEAD);sections={}
for section,ref,predicate in [('mainAndJmh',MAIN,lambda f:'/src/main/' in f or '/src/jmh/' in f),('tests',PARENT,lambda f:'/src/test/' in f)]:
 hf=[f for f in hfiles if predicate(f)];rf=[f for f in files(ref) if predicate(f)];assert hf==rf
 checked=[]
 for f in hf:
  h=blob(HEAD,f);assert h==blob(ref,f),f;assert h==(repo/f).read_bytes(),f
  checked.append({'file':f,'sha256':sha(h)})
 sections[section]={'reference':ref,'fileCount':len(checked),'committedAndWorkingBytesEqual':True,'files':checked}
assert sections['mainAndJmh']['fileCount']==130 and sections['tests']['fileCount']==168
changed=git('diff','--name-only',PARENT,HEAD).decode().splitlines();assert changed and all(p.startswith('docs/') for p in changed)
tracked=git('diff','--name-only','HEAD').decode().splitlines();assert not tracked
pure='graphite-webgraph/src/test/kotlin/io/johnsonlee/graphite/webgraph/ParallelDistinctDisjunctionTest.kt';text=blob(HEAD,pure).decode()
tests=['four keyword OR keeps every exclusive term and deduplicates overlap in stored order','selected tuples filter the OR result without changing its physical order','raw and lowercase predicates do not share a match state for the same string','different lowercase terms keep separate match states and preserve a complete miss']
for name in tests:assert f'fun `{name}`' in text
assert text.count('@Test')==4 and 'sparse initial posting projection' not in text
assert 'assertEquals(1L, graph.callSiteParallelScanCount()' in text
restored=git('diff','--name-only',CANDIDATE,HEAD,'--','*/src/main/*','*/src/test/*','*/src/jmh/*').decode().splitlines()
assert len(restored)==4
# Distinguish existing parent test changes from candidate remnants.
mainfs=set(files(MAIN));testvsMain=[]
for f in [f for f in hfiles if '/src/test/' in f]:
 if f not in mainfs or blob(HEAD,f)!=blob(MAIN,f):testvsMain.append(f)
untracked=git('ls-files','--others','--exclude-standard').decode().splitlines()
receiptpath=repo/'docs/profiling/attempt138/revert-source-receipt.json';receipt=json.loads(receiptpath.read_text());assert receipt['productionAndJmhFilesByteEqualFrozenMain']==130 and receipt['testFilesByteEqualParent']==168
out={'passed':True,'head':HEAD,'candidate':CANDIDATE,'restoreReference':PARENT,'frozenMain':MAIN,'sections':sections,'restoredFilesComparedWithCandidate':restored,'parentToRevertChangedFiles':changed,'parentToRevertDocsOnly':True,'retainedPureFourOrTests':tests,'retainedPureFourOrSourceSha256':sha(blob(HEAD,pure)),'existingParentTestDifferencesFromFrozenMain':testvsMain,'trackedWorkingDifferences':tracked,'untrackedAtAudit':untracked,'receiptSha256':sha(receiptpath.read_bytes()),'limitations':['Only file bytes and committed/working state were checked; no tests, builds, Java or performance runs were started.','All test files match e6c, not all match frozen main: existing parent correctness tests are retained.','Recorded untracked files are outside the committed revert diff; this audit does not alter or delete them.','Revert source equality does not waive failed performance or assert new CI success.']}
(R/'independent-revert-audit.json').write_text(json.dumps(out,indent=2)+'\n')
print(json.dumps({k:out[k] for k in ['passed','restoredFilesComparedWithCandidate','existingParentTestDifferencesFromFrozenMain','untrackedAtAudit']},indent=2))
