from pathlib import Path
import hashlib,json,tarfile
P=Path(__file__).resolve().parent;sha=lambda b:hashlib.sha256(b).hexdigest();a=json.loads((P/'archive-verification.json').read_text())
for r in a['artifacts']:
 b=(P/r['file']).read_bytes();assert(len(b),sha(b))==(r['bytes'],r['sha256']);assert b==Path(r['source']).read_bytes()
for group in ['failed-v1','baseline-v2']:
 p=P/group;inputs=json.loads((p/'module-with-adapter-inputs.json').read_text());original=json.loads((p/'baseline-module-inputs.json').read_text());assert len(original)==2568 and len(inputs)==2571
 assert all(inputs[f]==h for f,h in original.items())
 with tarfile.open(p/'source-with-adapter.tar.gz')as t:
  members=[m for m in t if m.isfile()];assert len(members)==2571
  for m in members:assert sha(t.extractfile(m).read())==inputs[m.name]
  context=t.extractfile('internal/query/execution_context.go').read().decode()
 overlay=(p/'execution_context.instrumented.go').read_text();receipt=json.loads((p/'instrumentation.json').read_text());needle='func (c *ExecutionContext) consume(units int64) {\n';assert overlay.replace(receipt['insertedDeclaration']+needle+receipt['insertedObserver'],needle)==context
p=P/'baseline-v2';receipt=json.loads((p/'receipt.json').read_text());assert receipt['terminal'] and receipt['baselineAndModuleUnchanged'] and receipt['oracleInputsUnchanged'] and receipt['observerComparisonEqual'];assert [x['exitCode']for x in receipt['commands']]==[1,1]
comparison=json.loads((p/'observer-comparison.json').read_text());assert len(comparison['unchangedNoActionCases'])==7 and comparison['changedNoActionCases']==[]
plain=json.loads((p/'plain.json').read_text());instrumented=json.loads((p/'instrumented.json').read_text());assert len(plain['cases'])==7 and len(instrumented['cases'])==10
assert plain['differences']==54 and instrumented['differences']==70
print(json.dumps({'verifiedArtifacts':len(a['artifacts']),'plainCases':7,'instrumentedCases':10,'plainDifferences':54,'instrumentedDifferences':70,'allSevenNoActionCasesObserverEqual':True,'actualMainMatches':[c['name']for c in instrumented['cases']if not c['differences']],'performanceMeasurements':0}))
