#!/usr/bin/env python3
"""Invoke the unchanged pinned-main matcher, preserving bytecode and execution evidence."""
from pathlib import Path
import hashlib,json,subprocess,zipfile,datetime
ROOT=Path(__file__).resolve().parent
MAIN=Path('/tmp/graphite-go-main-baseline-clone-4e328b0')
JAR=MAIN/'graphite-explore/build/libs/graphite-explore.jar'
REV='4e328b0109e13c896b74004823fb049fcb19251a'
EXPECTED='91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
commands=[]
def run(args,output):
    r=subprocess.run(args,cwd=ROOT,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    (ROOT/output).write_text(r.stdout)
    (ROOT/(output+'.stderr')).write_text(r.stderr)
    commands.append({'argv':list(map(str,args)),'exitCode':r.returncode,'stdout':output,'stderr':output+'.stderr'})
    r.check_returncode();return r.stdout
assert sha(JAR)==EXPECTED
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=MAIN,text=True).strip()==REV
(ROOT/'classes').mkdir(exist_ok=True)
start=datetime.datetime.now(datetime.timezone.utc).isoformat()
run(['java','-version'],'java-version.txt')
run(['javac','-cp',str(JAR),'-d',str(ROOT/'classes'),str(ROOT/'BoundedStringMatcherOracle.java')],'compile.log')
result=run(['java','-cp',str(ROOT/'classes')+':'+str(JAR),'BoundedStringMatcherOracle'],'responses.jsonl')
assert json.loads(result.splitlines()[-1])['verified'] is True
classes=['io.johnsonlee.graphite.webgraph.BoundedStringMatcher','io.johnsonlee.graphite.webgraph.StringPredicateKey','io.johnsonlee.graphite.webgraph.StringTable','io.johnsonlee.graphite.webgraph.MappedWebGraphBackedGraph','io.johnsonlee.graphite.webgraph.MappedWebGraphBackedGraphKt','io.johnsonlee.graphite.cypher.QueryPipelineKt','it.unimi.dsi.util.FrontCodedStringList']
for cls in classes:
    run(['javap','-c','-p','-classpath',str(JAR),cls],cls.rsplit('.',1)[-1]+'.javap.txt')
source=MAIN/'graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedWebGraphBackedGraph.kt'
text=source.read_text();startline=text[:text.index('    private fun rawCallSiteStringProjection(')].count('\n')+1
excerpt=text.splitlines()[startline-1:startline+29]
assert 'StringPredicateKey(predicate.transform, predicate.mode, predicate.expected)' in '\n'.join(excerpt)
(ROOT/'predicate-identity-source-audit.json').write_text(json.dumps({'mainRevision':REV,'source':str(source),'sourceSha256':sha(source),'startLine':startline,'excerpt':excerpt,'finding':'Raw projection creates one per-query matcher per transform/mode/expected, excluding property; capacity is 4096. Persistent raw projection result key separately retains ordered properties and limit.'},indent=2)+'\n')
serial_start=text[:text.index('    private fun <T : Node> serialRawCallSiteStringDisjunction(')].count('\n')+1
serial_excerpt=text.splitlines()[serial_start-1:serial_start+22]
assert 'if (type != CallSiteNode::class.java || limit == Int.MAX_VALUE) return null' in '\n'.join(serial_excerpt)
assert 'if (workConsumer !is SerialGraphWorkBatchConsumer) return null' in '\n'.join(serial_excerpt)
(ROOT/'serial-raw-source-audit.json').write_text(json.dumps({'mainRevision':REV,'source':str(source),'sourceSha256':sha(source),'startLine':serial_start,'excerpt':serial_excerpt,'finding':'Bounded serial raw disjunction requires CallSite, finite limit, SerialGraphWorkBatchConsumer; matcher identity excludes property and uses default capacity65536.'},indent=2)+'\n')
with zipfile.ZipFile(JAR) as z:
    classhash={cls:hashlib.sha256(z.read(cls.replace('.','/')+'.class')).hexdigest() for cls in classes}
assert sha(JAR)==EXPECTED
(ROOT/'receipt.json').write_text(json.dumps({'mainRevision':REV,'startedUtc':start,'endedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'jar':str(JAR),'jarSha256BeforeAndAfter':EXPECTED,'classByteSha256':classhash,'oracleSourceSha256':{f:sha(ROOT/f) for f in ['BoundedStringMatcherOracle.java','run.py']},'commands':commands,'verified':True,'performanceMeasurement':False},indent=2)+'\n')
print('Actual pinned-main BoundedStringMatcher oracle passed: '+str(json.loads(result.splitlines()[-1])['assertions'])+' assertions.')
