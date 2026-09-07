from pathlib import Path
import hashlib,json,re,subprocess,collections
here=Path(__file__).resolve().parent;root=here.parents[2];revision='4e328b0109e13c896b74004823fb049fcb19251a';sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
identity=json.loads((here/'source-identity.json').read_text())
clone=Path('/tmp/graphite-go-main-baseline-clone-4e328b0')
assert subprocess.check_output(['git','-C',str(clone),'rev-parse','HEAD'],text=True).strip()==revision
assert not subprocess.check_output(['git','-C',str(clone),'status','--short','--untracked-files=no'],text=True).strip()
for path,h in identity['sources'].items():assert sha(here/Path(path).name)==sha(clone/path)==h
original=Path('/Users/johnsonlee/.codex/fixtures/graphite-real64-1152-20260908/graphs.tsv').read_text().splitlines();relocated=(here/'graphs-relocated.tsv').read_text().splitlines();assert len(original)==len(relocated)
changed=0
for a,b in zip(original,relocated):
 if a and not a.startswith('#'):
  x,y=a.split('\t'),b.split('\t');assert len(x)==len(y)==6 and x[:1]+x[2:]==y[:1]+y[2:];assert Path(y[1]).is_dir();changed+=x[1]!=y[1]
 else:assert a==b
cp=(here/'jvm-classpath.txt').read_text();command=['java','-cp',str(here/'exporter-classes')+':'+cp,'ExportBenchmarkCases',str(here/'graphs-relocated.tsv'),str(here/'main-cases-repeat.json')]
result=subprocess.run(command,capture_output=True,text=True);(here/'repeat-export.log').write_text(result.stdout+result.stderr);assert result.returncode==0
assert (here/'main-cases.json').read_bytes()==(here/'main-cases-repeat.json').read_bytes()
main=json.loads((here/'main-cases.json').read_text());cases=main['cases'];assert len(cases)==len({c['id'] for c in cases})==1267
coverage=json.loads((here/'http-coverage.json').read_text());mapped=coverage['mappedMainCases'];assert len(mapped)==34 and len(coverage['omittedMainCases'])==1233 and len(coverage['extraHttpCases'])==8
assert sum(c['queryEqualIgnoringWhitespace'] for c in mapped)==31
assert {c['id'] for c in cases}=={c['id'] for c in mapped}|{c['id'] for c in coverage['omittedMainCases']}
assert not {c['id'] for c in mapped}&{c['id'] for c in coverage['omittedMainCases']}
assert coverage['firstMainCase']=='request-selected-set-wrapped-contains-k64-group-00-zero'
assert coverage['sameSourceOrder'] is False
classes=clone/'graphite-webgraph/build/classes/kotlin/jmh/io/johnsonlee/graphite/webgraph'
compiled={str(p.relative_to(classes)):sha(p) for p in sorted(classes.glob('*BroadQuery*.class'))}
assert 'LargeBroadQueryPressureBenchmarkKt.class' in compiled
receipt={'passed':True,'mainRevision':revision,'actualMainWorkloadCaseCount':len(cases),'repeatActualJVMExportByteExact':True,'uniqueIDs':True,'mappedHTTPCaseCount':len(mapped),'omittedMainCaseCount':1233,'extraWrappedHTTPCaseCount':8,'queriesEqualIgnoringWhitespace':31,'parameterToLiteralAdaptations':3,'sourceOrderDiffers':True,'relocatedPathColumnsOnly':changed,'compiledBenchmarkClasses':compiled,'mainCasesSHA256':sha(here/'main-cases.json'),'httpManifestSHA256':sha(root/'graphite-server/scripts/real64-workload.json'),'exporterSourceSHA256':sha(here/'ExportBenchmarkCases.java'),'repeatCommand':command,'graphLoadingQueryExecutionOrPerformanceMeasurement':False,'testcaseReplicationComplete':False}
(here/'verification.json').write_text(json.dumps(receipt,indent=2)+'\n')
print('Verified actual JVM1267 export twice; HTTP34 mapped/1233missing/8extras,3parameter adaptations; testcase replication INCOMPLETE')
