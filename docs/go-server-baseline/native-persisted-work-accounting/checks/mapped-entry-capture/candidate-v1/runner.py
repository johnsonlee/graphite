from pathlib import Path
import argparse,datetime,hashlib,json,os,shutil,subprocess,tarfile
P=Path(__file__).resolve().parent;ROOT=P.parents[4];GO=Path('/opt/homebrew/Cellar/go/1.22.0/libexec/bin/go')
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def dump(p,v):p.write_text(json.dumps(v,indent=2)+'\n')
def inventory(p):return {str(f.relative_to(p)):sha(f)for f in sorted(p.rglob('*'))if f.is_file()}
a=argparse.ArgumentParser();a.add_argument('--source',type=Path,required=True);a.add_argument('--output',type=Path,required=True);args=a.parse_args();SOURCE=args.source.resolve();assert SOURCE.is_dir();out=args.output.resolve();out.mkdir(parents=True,exist_ok=False);tool_sha=sha(GO);runner_sha=sha(Path(__file__));dump(out/'source-root.json',dict(source=str(SOURCE),go=str(GO),goSHA256=tool_sha,runnerSHA256=runner_sha))
base=inventory(SOURCE);dump(out/'baseline-module-inputs.json',base);module=out/'module';shutil.copytree(SOURCE,module);assert inventory(module)==base
adapters={};added=[];already=[];expected_adapters=json.loads((P/'baseline-v2/adapter-inputs.json').read_text())
for f in sorted((ROOT/'graphite-server/internal/query').glob('mapped_entry*_test.go')):
 adapters[f.name]=sha(f);assert adapters[f.name]==expected_adapters[f.name],('adapter changed from frozen baseline',f.name);target=module/'internal/query'/f.name
 if target.exists():assert sha(target)==adapters[f.name],('candidate adapter conflict',f.name);already.append(f.name)
 else:shutil.copy2(f,target);added.append(f.name)
assert adapters==expected_adapters;dump(out/'adapter-inputs.json',adapters);dump(out/'adapter-installation.json',dict(alreadyPresentAndEqual=already,added=added));shutil.copy2(__file__,out/'runner.py')
oracle=ROOT/'docs/go-server-baseline/native-persisted-work-accounting/mapped-entry-oracle';oracles={}
for relative in ['', 'warm-range-supplement']:
 for name in ['main.json','repeat-capture/main.json','fixtures.tar.gz']:
  f=oracle/relative/name;d=out/'docs/go-server-baseline/native-persisted-work-accounting/mapped-entry-oracle'/relative/name;d.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,d);oracles[str(f)]=sha(f)
dump(out/'oracle-inputs.json',oracles)
context=module/'internal/query/execution_context.go';original=context.read_text();needle='func (c *ExecutionContext) consume(units int64) {\n';assert original.count(needle)==1
prefix='// Test-only observer declaration: exists exclusively in the frozen-copy overlay.\nvar mappedEntryConsumeObserver func(*ExecutionContext, int64, string, any)\n\n'
insert='''\t// Test-only observation: preserve actual algorithm and panic instance below.
\tif observer := mappedEntryConsumeObserver; observer != nil {
\t\tobserver(c, units, "before", nil)
\t\tdefer func() {
\t\t\tif failure := recover(); failure != nil {
\t\t\t\tobserver(c, units, "after", failure)
\t\t\t\tpanic(failure)
\t\t\t}
\t\t\tobserver(c, units, "after", nil)
\t\t}()
\t}
'''
modified=original.replace(needle,prefix+needle+insert);assert modified.replace(prefix+needle+insert,needle)==original
replacement=out/'execution_context.instrumented.go';replacement.write_text(modified);dump(out/'overlay.json',{'Replace':{str(context):str(replacement)}})
dump(out/'instrumentation.json',dict(originalSHA256=sha(context),instrumentedSHA256=sha(replacement),removedObserverExactlyRestoresOriginal=True,originalConsumeBodyUnmodified=True,insertedDeclaration=prefix,insertedObserver=insert,scope='actual consume calls; no injected work; original panics rethrown with identity'))
frozen=inventory(module);dump(out/'module-with-adapter-inputs.json',frozen)
with tarfile.open(out/'source-with-adapter.tar.gz','w:gz')as t:
 for name in frozen:t.add(module/name,arcname=name,recursive=False)
commands=[]
for label in ['plain','instrumented']:
 cmd=[str(GO),'test','-race','-count=1','-v','-run','^TestMappedEntryCapture$']
 if label=='instrumented':cmd+=['-vet=off','-overlay',str(out/'overlay.json'),'-tags','mapped_entry_instrumented']
 cmd+=['./internal/query'];env=dict(os.environ,GOTOOLCHAIN='local',GRAPHITE_MAPPED_ENTRY_OUTPUT=str(out/(label+'.json')),PATH=str(GO.parent)+os.pathsep+os.environ['PATH']);started=datetime.datetime.now(datetime.timezone.utc).isoformat()
 with (out/(label+'.log')).open('x')as log:
  p=subprocess.Popen(cmd,cwd=module,env=env,stdout=log,stderr=subprocess.STDOUT);dump(out/'live-process.json',dict(phase=label,pid=p.pid,command=cmd));print(label,'PID',p.pid,flush=True);rc=p.wait()
 commands.append(dict(phase=label,command=cmd,exitCode=rc,startUTC=started,endUTC=datetime.datetime.now(datetime.timezone.utc).isoformat()));dump(out/'commands.json',commands)
 if not (out/(label+'.json')).is_file():raise RuntimeError(label+' did not write full capture; attempt retained')
plain=json.loads((out/'plain.json').read_text());instrumented=json.loads((out/'instrumented.json').read_text())
def comparable(v):
 if isinstance(v,dict):return {k:comparable(x)for k,x in v.items()if k not in ['callbacks','sameAsCallbackFailure','differences','fixtureDirectory']}
 if isinstance(v,list):return [comparable(x)for x in v]
 return v
other={c['name']:c for c in instrumented['cases']};unchanged=[];changed=[]
for c in plain['cases']:
 (unchanged if comparable(c)==comparable(other[c['name']])else changed).append(c['name'])
assert len(plain['cases'])==7 and len(instrumented['cases'])==10
comparison=dict(plainCases=7,instrumentedCases=10,unchangedNoActionCases=unchanged,changedNoActionCases=changed,excludedComparisonFields=['actual callback events and their callback-failure identity (unavailable uninstrumented)','temporary fixture path','derived JVM differences'],plainMainDifferences=plain['differences'],instrumentedMainDifferences=instrumented['differences']);dump(out/'observer-comparison.json',comparison)
assert inventory(module)==frozen and inventory(SOURCE)==base and sha(GO)==tool_sha and sha(Path(__file__))==runner_sha
receipt=dict(terminal=True,commands=commands,baselineOriginalFiles=len(base),adapterFiles=len(adapters),moduleFiles=len(frozen),baselineAndModuleUnchanged=True,sourceRoot=str(SOURCE),goSHA256=tool_sha,runnerSHA256=runner_sha,adapterFilesAdded=len(added),adapterFilesAlreadyPresent=len(already),observerComparisonEqual=not changed,oracleInputsUnchanged=all(sha(Path(p))==h for p,h in oracles.items()),performanceMeasurements=0);dump(out/'receipt.json',receipt);assert not changed,comparison
print(json.dumps(receipt),flush=True)
raise SystemExit(1 if any(c['exitCode']!=0 for c in commands)else 0)
