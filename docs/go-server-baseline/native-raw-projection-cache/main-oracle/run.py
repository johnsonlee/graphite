#!/usr/bin/env python3
"""Reproduce the actual pinned-main cache oracle and its source/bytecode audit."""
from pathlib import Path
import hashlib,json,subprocess,zipfile,datetime,shutil,tempfile
ROOT=Path(__file__).resolve().parent
MAIN=Path('/tmp/graphite-go-main-baseline-clone-4e328b0')
JAR=MAIN/'graphite-explore/build/libs/graphite-explore.jar'
REV='4e328b0109e13c896b74004823fb049fcb19251a'
EXPECTED='91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def run(args,output):
    r=subprocess.run(args,cwd=ROOT,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    (ROOT/output).write_text(r.stdout)
    if r.stderr:(ROOT/(output+'.stderr')).write_text(r.stderr)
    else:(ROOT/(output+'.stderr')).unlink(missing_ok=True)
    commands.append({'argv':list(map(str,args)),'exitCode':r.returncode,'stdout':output,'stderr':r.stderr})
    r.check_returncode();return r.stdout
commands=[]
assert sha(JAR)==EXPECTED
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=MAIN,text=True).strip()==REV
(ROOT/'classes').mkdir(exist_ok=True)
start=datetime.datetime.now(datetime.timezone.utc).isoformat()
run(['java','-version'],'java-version.txt')
run(['javac','-cp',str(JAR),'-d',str(ROOT/'classes'),str(ROOT/'RawProjectionCacheOracle.java'),str(ROOT/'RawProjectionGraphOracle.java')],'compile.log')
result=run(['java','-cp',str(ROOT/'classes')+':'+str(JAR),'RawProjectionCacheOracle'],'responses.jsonl')
assert json.loads(result.splitlines()[-1])['verified'] is True
repo=ROOT.parents[3]
fixtures={'clean':repo/'graphite-server/internal/query/testdata/candidate-index/clean','split-clean':repo/'graphite-server/internal/query/testdata/indexed-distinct/split-clean'}
def inventory(directory):return {str(p.relative_to(directory)):sha(p) for p in sorted(directory.rglob('*')) if p.is_file()}
fixture_evidence={}
with tempfile.TemporaryDirectory(prefix='graphite-raw-projection-oracle-') as temp:
    temp=Path(temp)
    for name,origin in fixtures.items():
        shutil.copytree(origin,temp/name); fixture_evidence[name]={'source':str(origin),'before':inventory(temp/name)}
    graph_result=run(['java','-cp',str(ROOT/'classes')+':'+str(JAR),'RawProjectionGraphOracle',str(temp/'clean'),str(temp/'split-clean')],'graph-responses.jsonl')
    assert json.loads(graph_result.splitlines()[-1])['verified'] is True
    for name,origin in fixtures.items():
        fixture_evidence[name]['after']=inventory(temp/name)
        assert fixture_evidence[name]['before']==fixture_evidence[name]['after']==inventory(origin)
(ROOT/'graph-fixture-hashes.json').write_text(json.dumps(fixture_evidence,indent=2)+'\n')
classes=['io.johnsonlee.graphite.webgraph.RawProjectionMatches','io.johnsonlee.graphite.webgraph.RawProjectionMatchKey','io.johnsonlee.graphite.webgraph.MappedWebGraphBackedGraph','io.johnsonlee.graphite.webgraph.RawStringMatchStates']
bytecode={}
for cls in classes:
    bytecode[cls]=run(['javap','-c','-p','-classpath',str(JAR),cls],cls.rsplit('.',1)[-1]+'.javap.txt')
assert 'RawStringMatchStates.stateFor:' not in bytecode[classes[2]]
# Hash every production Kotlin/Java source enumerated by rg, so the search scope is reproducible.
allpaths=subprocess.check_output(['rg','--files','-g','*.kt','-g','*.java'],cwd=MAIN,text=True).splitlines()
paths=sorted(p for p in allpaths if '/src/main/' in p)
refs=[];inventory={}
for p in paths:
    f=MAIN/p;inventory[p]=sha(f)
    for n,line in enumerate(f.read_text().splitlines(),1):
        if 'rawStringMatchStates' in line or 'stateFor(' in line or 'RawStringMatchStates' in line:
            refs.append({'path':p,'line':n,'text':line.strip()})
state_for=[r for r in refs if 'stateFor(' in r['text']]
assert len(state_for)==1 and state_for[0]['text'].startswith('fun stateFor(')
assert len([r for r in refs if 'rawStringMatchStates' in r['text']])==4
(ROOT/'production-source-inventory.json').write_text(json.dumps(inventory,indent=2)+'\n')
(ROOT/'raw-string-state-source-audit.json').write_text(json.dumps({'mainRevision':REV,'productionSourceFiles':len(paths),'references':refs,'stateForDefinitionCount':1,'stateForDirectCallSites':0,'mappedGraphBytecodeCallsStateFor':False,'scope':'Direct Kotlin/Java production references plus disassembly of mapped graph; no claim about arbitrary external reflection.'},indent=2)+'\n')
with zipfile.ZipFile(JAR) as z:
    classhash={cls:hashlib.sha256(z.read(cls.replace('.','/')+'.class')).hexdigest() for cls in classes}
assert sha(JAR)==EXPECTED
(ROOT/'receipt.json').write_text(json.dumps({'mainRevision':REV,'startedUtc':start,'endedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'jar':str(JAR),'jarSha256BeforeAndAfter':EXPECTED,'classByteSha256':classhash,'oracleSourceSha256':{f:sha(ROOT/f) for f in ['RawProjectionCacheOracle.java','RawProjectionGraphOracle.java','run.py']},'commands':commands,'verified':True,'performanceMeasurement':False},indent=2)+'\n')
print('Actual pinned-main cache oracle passed; '+str(json.loads(result.splitlines()[-1])['assertions'])+' assertions; '+str(len(paths))+' production source files audited.')
