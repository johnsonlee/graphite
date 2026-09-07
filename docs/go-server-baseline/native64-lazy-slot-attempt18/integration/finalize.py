from pathlib import Path
import json,hashlib,tarfile,re,shutil,subprocess
r=Path(__file__).resolve().parent;w=r.parent;H=lambda b:hashlib.sha256(b).hexdigest()
inv=json.loads((r/'candidate-inputs.json').read_text())
for n,m in inv.items():assert H((w/'candidate'/n).read_bytes())==m['sha256'],n
before=json.loads((r/'base-inputs.json').read_text());changed=[n for n in inv if before.get(n)!=inv[n]];assert len(changed)==3
assert not any('lazy_edge' in n or 'filtered_relationship' in n for n in inv)
(r/'final-source-verification.json').write_text(json.dumps({'totalInputs':len(inv),'moduleInputs':2437,'allExact':True,'changed':changed,'preservedOriginalInputs':len(before)-2,'includes':'fb4434df A17+A16+B+A15+CDE+tuple+A13+A12','excludes':'lazyedge and filtered relationship'},indent=2)+'\n')
with tarfile.open(r/'combined-source.tar.gz','w:gz') as t:
 for n in sorted(inv):t.add(w/'candidate'/n,arcname=n,recursive=False)
p=Path('/tmp/graphite-go-a17-on-a16-b/integration/output');shutil.copytree(p,r/'baseline-output',dirs_exist_ok=True)
s=(r/'original-production-named.log').read_text();assert 'no tests to run' not in s and '\nPASS\n' in s
names=re.findall(r'^--- PASS: (\S+)',s,re.M);assert len(names)==5
s2=(r/'independent-boundaries.log').read_text();assert '\nPASS\n' in s2;names2=re.findall(r'^--- PASS: (\S+)',s2,re.M);assert len(names2)==18
(r/'named-execution-verification.json').write_text(json.dumps({'originalProductionOverlay':{'passedNames':names,'subtestPasses':len(re.findall(r'^    --- PASS:',s,re.M)),'actualExistingCanonicalFileOverlay':json.loads((r/'original-overlay.json').read_text())},'independentBoundaryPasses':names2,'repetitions':3},indent=2)+'\n')
(r/'commands.json').write_text(json.dumps({'base':(r/'base.txt').read_text().strip(),'go':subprocess.check_output(['go','version']).decode().strip(),'commands':[{'cwd':str(w/'candidate/graphite-server'),'argv':['go','test','-race','-count=1','./...'],'env':{'INDEXED_DISTINCT_OUTPUT':str(r/'output/original-corpus'),'ORDINARY_HISTORY_OUTPUT':str(r/'output/history')},'log':'module-race.log','exit':0},{'argv':['go','vet','./...'],'log':'module-vet.log','exit':0},{'argv':['go','test','-race','-v','-count=1','-overlay='+str(r/'original-overlay.json'),'./internal/query','-run','^TestLazySlot'],'log':'original-production-named.log','exit':0},{'cwd':str(w/'review-module'),'argv':['go','test','-race','-v','-count=3','./internal/query','-run','^(TestReviewLazySlotInlineBindingsAcrossDistinctStores|TestLazySlotRealCancellationAndIndependentRequests|TestLazySlotRegistryUnionAndSurrogateOutputFallback|TestGenericProjectionBindingCancellationJoinsAndClears|TestStreamingPaginationWaveFailureCancellationJoin|TestGenericSyncCurrentRequestCancelsBlockedAdvanceAndJoinsTask)$'],'log':'independent-boundaries.log','exit':0,'initialLog':'independent-boundaries-initial-compile.log','initialExit':1},{'cwd':str(w/'review-module'),'argv':['go','vet','./internal/query'],'log':'review-vet.log','exit':0}], 'comparisonCommands':['python3 compare_b_outputs.py','python3 verify_b595.py','python3 numeric-spelling-audit.py','python3 summarize_original.py --verification '+str(r/'output')],'coTenancy':'No 64/performance/JVM/HTTP launched by reviewer. Parent shipping correctness may overlap. All execution ends before parent A18 quiet pair.'},indent=2)+'\n')
(r/'README.md').write_text('''# Independent A18 integration on committed A17

Bounded verdict: no new correctness blocker found. Full module race/vet and all current complete-response comparisons pass with the explicitly enumerated preexisting state variation. No performance conclusion is made.

## Exact inputs

Clean base fb4434df4545c195fdee379d109266bbdaaab4d1 was extracted using git archive for graphite-server, graphite-explore and CONVENTIONS.md. Its 2436 module files plus 59 external reference files exactly equal the previous tested A17-on-A16+B input inventory. Thus A12, A13, tuple, CDE, A15, B, A16 and A17 remain present. No lazyedge or filtered relationship changes are included.

The A18 author manifest 5565f5e27c2fac6367ef1054fc34372110769c3671d80a5d341230893580db0b verified all 276 files. Patch 259d1be1cef97757ec5a012f20d24c597c862e9c6b29d3840b34a268e973a26d applies cleanly without conflicts. All three changed files match author bytes: lazy_filtered.go (one functional loop), candidate.go (comment only), lazy_filtered_slot_test.go (new test). Final candidate has 2437 module files plus the same 59 external files. No production, existing tests, root/provider files, or author freeze was edited during independent review. Source archive/inventories and exact patch are provided here.

## Mechanism and ownership review

The slot is allocated after the existing indexed candidate branch returns, only when rowOrders is nil. The generic loop fully reads the same owned Node through the same source Next before assigning graph, graphID and node. The slot is query-local and sequential; it is neither shared across workers nor stored in evaluator, Store or a cache. No task source, iterator, context check, reader or Close call changed. All A17 Store poll sites remain byte-identical to committed base.

The registry guard is necessary: e.bind registers the scratch binding map by reference when rowOrders is nonnil. That branch continues to construct the old owned nodeValue. Whole output values freeze in lazyProject; nested lists, maps, comprehensions and scalar list additions freeze at their existing construction boundaries. CASE/coalesce/unary transport a slot only synchronously. freezeCandidate is shallow; the container construction boundaries, not a nonexistent recursive freeze, establish ownership. Matching/WHERE, projection order (including overwritten aliases), Java equality, provenance merge, and qualified later-source consumption remain unchanged.

The author's guard-disabled negative-control artifact is verified within the author manifest and proves the escape check can fail. Independent execution also used the final new tests against an exact original-production overlay of the already-existing canonical lazy_filtered.go path: all five top-level tests and 152 subtests actually executed and passed. This is not a newly-added-file overlay with zero tests.

## Independent results

Full module go test -race -count=1 ./... and go vet ./... exit 0. All 76 current capture paths are retained: 73 exact; three differ only at 51 mappedView boolean leaves in count-40 source histories. Both source counts and precise paths/types are checked. There are no retained-state or public-response changes in this run. Original corpus remains 1044 equal out of 1048 with the same four F mismatches. All B595 complete JSON-value responses and 53 ordered design traces pass; the prior 166 numeric spelling differences remain explicitly listed (8 rows, 98 params, 60 spec), not called byte-equal.

Six actual named boundary tests ran three times under race in a separate review-module copy, including A15 binding cleanup/task join, generic request cancellation/join, B wave failure/join, A18 standard cancellation/independent requests and registry fallback. An additional independent test uses two distinct Stores with the same IDs, inline property matching, comprehension binding and nested DISTINCT whole-node output. It asserts the actual retained source Store, graph ID and node IDs for all four rows and materializes the owned rows after closing both Stores. All 18 top-level executions passed; test source and raw logs are retained. Review-module query vet also passed.

The independent test initially used lowercase names for qualifiedNode's exported fields and failed compilation. That exact test source and compile log are preserved; only the test field names were corrected, then all six named tests actually ran. No production fix or weakened expectation was needed. Author earlier sqrt-to-left test correction and intentional disabled-guard failure remain immutable in the verified author evidence.

No JVM, HTTP, real64 runtime or performance experiment was launched here. Correctness checks may have overlapped parent shipping HTTP, not a timing claim. All processes are terminal before handing the candidate to the parent for the separately controlled measurement. These finite corpora do not prove every language behavior or eliminate the four known F cases.
''')
files={str(p.relative_to(r)):{'sha256':H(p.read_bytes()),'bytes':p.stat().st_size} for p in sorted(r.rglob('*')) if p.is_file() and p.name!='manifest.json'}
(r/'manifest.json').write_text(json.dumps({'base':(r/'base.txt').read_text().strip(),'scope':'A18 on A17 independent integration; excludes edge/relationship','files':files},indent=2)+'\n')
for n in ['manifest.json','candidate-inputs.json','combined-source.tar.gz','attempt18.patch']:print(n,H((r/n).read_bytes()))
print('files',len(files))
