from pathlib import Path
import subprocess,json,hashlib,xml.etree.ElementTree as E,zipfile
R=Path(__file__).parent; repo=R/'repo';receipt=json.loads((R/'build-receipt.json').read_text());pin='4e328b0109e13c896b74004823fb049fcb19251a'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
files=subprocess.check_output(['git','ls-files'],cwd=repo,text=True).splitlines();files=[f for f in files if '/src/main/' in f or '/src/jmh/' in f]
changed=[]
for f in files:
 original=subprocess.check_output(['git','show',pin+':'+f],cwd=repo)
 if original!=(repo/f).read_bytes():changed.append({'file':f,'sourceSha256':sha(repo/f)})
assert len(files)==receipt['comparedMainAndJmhFiles']==130
assert changed==receipt['changedFiles']
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()==receipt['parent']
expected=['mapped selected tuples preserve earliest source order and reject recombined strings','mapped selected tuples preserve duplicate and null columns with partial projection fallback','mapped selected tuple validates the whole chosen posting before limit one','mapped selected tuple work rejection and cancellation preserve retry correctness','sparse initial posting projection preserves reordered duplicate and null columns']
retained=['four keyword OR keeps every exclusive term and deduplicates overlap in stored order','selected tuples filter the OR result without changing its physical order','raw and lowercase predicates do not share a match state for the same string','different lowercase terms keep separate match states and preserve a complete miss']
suites=[];totals={k:0 for k in ['tests','failures','errors','skipped']};seen={}
for p in sorted((repo/'graphite-webgraph/build/test-results/test').glob('TEST-*.xml')):
 root=E.parse(p).getroot();cs=root.findall('testcase');assert len(cs)==int(root.attrib['tests'])
 for k in totals:totals[k]+=int(root.attrib[k])
 for case in cs:
  assert not any(case.find(k) is not None for k in ['failure','error','skipped'])
  if case.attrib['name'] in expected+retained:seen[case.attrib['name']]={'suite':root.attrib['name'],'timeSeconds':case.attrib['time'],'passed':True}
 suites.append({'file':str(p.relative_to(R)),'sha256':sha(p),'attributes':root.attrib})
assert totals==receipt['testResults'] and len(suites)==receipt['testSuites']==6
assert set(seen)==set(expected+retained)
log=(R/'build.log').read_text();tasklines=[x for x in log.splitlines() if x.startswith('> Task ')]
for check in receipt['checks']:assert '> Task '+check in tasklines
assert 'BUILD SUCCESSFUL' in log and 'BUILD FAILED' not in log
assert receipt['buildExit']==0 and receipt['buildSuccessful']
testhashes={}
for name,expectedhash in [('GraphStoreTest.kt','2189a077f3d5e41fd3d7627d0ea28064b8fca3f9d7b8d147552a0a206b4ade80'),('ParallelDistinctDisjunctionTest.kt','521db43f2599e71cf85a4ad0422f2ee22c692bf2ce9cf40cc92e0f26fad8e1b1')]:
 p=repo/'graphite-webgraph/src/test/kotlin/io/johnsonlee/graphite/webgraph'/name;assert sha(p)==expectedhash;testhashes[name]=sha(p)
entries=set()
for sub in ['classes/kotlin/test','classes/java/test','resources/test']:
 base=repo/'graphite-webgraph/build'/sub
 if base.exists():entries.update(str(p.relative_to(base)) for p in base.rglob('*') if p.is_file())
with zipfile.ZipFile(R/'candidate-jmh.jar') as z:
 jarentries=set(z.namelist());leaked=sorted(entries&jarentries)
assert entries and not leaked
out={'passed':True,'baseRevision':pin,'candidateParent':receipt['parent'],'comparedMainAndJmhFiles':len(files),'changedFiles':changed,'testResults':totals,'suites':suites,'newFiveTests':{k:seen[k] for k in expected},'retainedFourOrTests':{k:seen[k] for k in retained},'testSourceHashes':testhashes,'buildCommand':json.loads((R/'build-command.json').read_text()),'taskLines':[x for x in tasklines if any(x=='> Task '+c for c in receipt['checks'])],'evidenceHashes':{p.name:sha(p) for p in [R/'build.log',R/'build-command.json',R/'build-receipt.json',R/'prebuild-source-receipt.json']},'jarExclusion':{'testOutputEntryCount':len(entries),'jarEntryCount':len(jarentries),'leakedEntries':leaked,'checkedCentralDirectoryOnly':True},'recordedFrozenJarSha256':receipt['jmhJarSha256'],'limitations':['No Java/build/benchmark was started.','Recorded process exit 0 agrees with successful terminal log; the auditor did not observe the original process directly.','Only the JAR central directory was read for an independent test-output exclusion check; the full frozen JAR hash was not recomputed while timed work may run.','Synthetic tests establish correctness/path coverage only, not performance or final 10x acceptance.']}
(R/'independent-build-audit.json').write_text(json.dumps(out,indent=2)+'\n')
print(json.dumps({'passed':True,'totals':totals,'newTestCount':len(expected),'retainedTestCount':len(retained),'jarExclusion':out['jarExclusion']},indent=2))
