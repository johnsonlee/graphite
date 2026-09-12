"""Read-only audit of existing146 Method control; no Java, capture or large hashes."""
from pathlib import Path
from collections import Counter
import csv,hashlib,json,re,zipfile,shlex
R=Path(__file__).resolve().parent;C=R/'candidate'
load=lambda p:json.loads(Path(p).read_text());sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
receipt=load(R/'method-control-receipt.json');build=load(R/'final-build-receipt.json')
assert load(R/'method-control-exit.json')['exitCode']==0
before=load(R/'method-input-before.json');after=load(R/'method-input-after.json');assert before==after
assert len(before)==receipt['fixtureFiles']==64
assert sum(x['bytes'] for x in before)==receipt['fixtureBytes']==1641948871
assert receipt['inputsAndJarUnchanged'] and receipt['jarSha256']==build['jars']['explore']['sha256']
corpora=['android','tika','hive','kotlin-compiler'];counts=Counter(x['path'].split('/')[0] for x in before);assert set(counts)==set(corpora)
for corpus in corpora:
 names={x['path'].split('/',1)[1] for x in before if x['path'].startswith(corpus+'/')}
 assert {'graph.metadata','graph.nodedata','graph.nodeindex','graph.nodeoffsets','graph.strings'}<=names,(corpus,names)
scenarios=['zero','early','middle','late','prefix','suffix','contains','regex','or','count','order'];expected={(str(g),s) for g in [4,17,36] for s in scenarios}
results=load(R/'method-control.json');assert len(results)==33 and {(x['params']['graphCount'],x['params']['scenario']) for x in results}==expected
manifest=(R/'method-control.manifest.txt').read_text().splitlines();assert len(manifest)==33
manifestRecords={}
for line in manifest:
 fields=line.split('|');g,scenario=fields[:2];assert (g,scenario) not in manifestRecords and len(fields)==2+int(g)+4
 for i,field in enumerate(fields[2:2+int(g)]):assert field.startswith(f'service-{i}:{corpora[i%4]}:') and re.search(r'=[0-9a-f]{64}$',field)
 for corpus,field in zip(corpora,fields[2+int(g):]):assert field.startswith(f'root:{corpus}:root:{corpus}:{scenario}=') and re.search(r'=[0-9a-f]{64}$',field)
 manifestRecords[g,scenario]=fields[2:]
assert set(manifestRecords)==expected
records=[]
for x in results:
 p=x['params'];m=x['secondaryMetrics'];key=p['graphCount'],p['scenario']
 assert x['benchmark']=='io.johnsonlee.graphite.cli.MethodDiscoveryCompatibilityBenchmark.methodScenarioGate'
 assert x['mode']=='ss' and x['threads']==x['forks']==x['measurementIterations']==x['measurementBatchSize']==1 and x['warmupIterations']==0
 assert x['jdkVersion']=='17.0.18' and '-XX:ActiveProcessorCount=4' in x['jvmArgs'] and '-Xmx8g' in x['jvmArgs']
 assert m['requestsSucceeded']['score']==1 and m['requestsSucceeded']['rawData']==[[1.0]]
 assert m['graphCount']['score']==int(p['graphCount']) and m['responseBytes']['score']>0
 records.append({'graphCount':int(p['graphCount']),'scenario':p['scenario'],'requestsSucceeded':1,'responseBytes':m['responseBytes']['score'],'expectedDigestEntries':len(manifestRecords[key]),'manifestEntry':manifestRecords[key]})
log=(R/'method-control.log').read_text();assert len(re.findall(r'^# Benchmark: .*methodScenarioGate$',log,re.M))==33 and '# Run complete.' in log
assert len(re.findall(r'^\s+requestsSucceeded:\s+1\.000 #$',log,re.M))==33
assert '<failure>' not in log and '<forked VM failed' not in log
source=C/'graphite-explore/src/jmh/kotlin/io/johnsonlee/graphite/cli/ExplorerMemoryBenchmark.kt'
checkInputs={x['path']:x['sha256'] for x in load(R/'checks-inputs.json')}
assert checkInputs[str(source.relative_to(C))]==sha(source)

command=load(R/'method-control-command.json')
assert command[2]==str(R/'candidate-final-explore-jmh.jar')
expected_args=shlex.split(command[-1])
for result in results:
 for arg in expected_args:assert arg in result['jvmArgs']
assert len(expected_args)==6
prior=Path('/private/tmp/graphite-attempt145.lwdc61sb')
assert sha(source)==sha(prior/'candidate'/source.relative_to(C))
# Read only relevant class payloads; do not hash or decompress either whole JAR.
class_evidence={}
with zipfile.ZipFile(R/'candidate-final-explore-jmh.jar') as current, zipfile.ZipFile(prior/'candidate-final-explore-jmh.jar') as previous:
 names=[i.filename for i in current.infolist() if i.filename.startswith('io/johnsonlee/graphite/cli/Method') and i.filename.endswith('.class')]
 assert names and len(names)==len(set(names))
 for name in names:
  a=current.read(name);b=previous.read(name);assert a==b,name
  class_evidence[name]=hashlib.sha256(a).hexdigest()
assert len(manifestRecords)==33 and sum(len(v) for v in manifestRecords.values())==759
out={
 'attempt':146,'auditPassed':True,'acceptance':False,
 'scope':'Unpaired original Method correctness control. No timing/resource comparison, raw HTTP body capture or CI verdict.',
 'combinationCount':33,'combinations':records,'scenarioCompletionCounters':33,
 'expectedManifestEntries':759,'manifestMeaning':'Expected signatures constructed during setup, not actual response bodies.',
 'requestCountBoundary':'Code-derived 759 measured HTTP request helpers across33 scenario invocations; requestsSucceeded increments once per successful scenario action. Additional setup route probe is outside this count. No observed network log census.',
 'inputIdentity':{'records':len(before),'bytes':sum(x['bytes'] for x in before),'corpusFileCounts':dict(counts),'beforeAfterEqual':True,'exploreJarReceiptSha256':receipt['jarSha256'],'sameAsBuildReceipt':True,'wholeJarOrGraphRehashedByAudit':False},
 'codeEvidence':{'sourceSha256':sha(source),'sourceEquals145':True,'relevantMethodClassPayloadsEqual145':class_evidence,
 'anchors':{'setup':'ExplorerMemoryBenchmark.kt:399-433: four persisted corpora; services repeat index%4; expected derives from graph.methods.',
 'scenario':'ExplorerMemoryBenchmark.kt:445-453: graphCount scoped + four corpus root requests per action.',
 'rootOrder':'ExplorerMemoryBenchmark.kt:624-629: sorts returned root signatures descending and takes limit before digest; raw order not proved.',
 'unknownGraphIds':'ExplorerMemoryBenchmark.kt:638-641: rebuilds results from known services, dropping unexpected IDs before equality.',
 'counter':'ExplorerMemoryBenchmark.kt:787-799: requestsSucceeded increments once after entire action.',
 'manifest':'ExplorerMemoryBenchmark.kt:801-829: serializes expected scoped and root digests during setup.'}},
 'validationScope':['Known scoped/root results pass existing schema/digest comparisons when action completes. Normal non-order method results are canonicalized before hashing.',
 'Root ORDER normalizes actual signatures before hashing; no claim of raw response order preservation.',
 'Known-graph map projection can filter extra graph IDs; cannot claim validation rejects all unexpected IDs.',
 'Expected manifest alone cannot independently recompute raw HTTP response digests; successful action counter and checked code bind the existing validation.',
 '4/17/36 service graphs reuse four real corpora, not57 independent graph datasets.',
 'No Java/build/new measurement. Recorded CPU/resident metrics are retained in original JSON; no benefit/regression inference from unpaired results.'],
 'hashes':{str(q.relative_to(R)) if q.is_relative_to(R) else str(q):sha(q) for q in [R/'method-control.json',R/'method-control.log',R/'method-control.manifest.txt',R/'method-control-command.json',R/'method-control-exit.json',R/'method-control-receipt.json',R/'method-input-before.json',R/'method-input-after.json',R/'run-method-control.py',R/'final-build-receipt.json',source]}}
(R/'method-control-audit.json').write_text(json.dumps(out,indent=2)+'\n')
print(json.dumps({'combos':33,'manifestEntries':759,'inputFiles':64,'bytes':receipt['fixtureBytes'],'MethodClassPayloadsIdentical':len(class_evidence)},indent=2))
