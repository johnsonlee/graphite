import hashlib
import json
import os
import pathlib
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).parent
PRIOR = pathlib.Path('/private/tmp/graphite-attempt140._5jztd0a')
MANIFEST = pathlib.Path('/private/tmp/pr113-attempt131-ascii.JqgmHw/fixture64/graphs.tsv')
BASE = pathlib.Path('/private/tmp/graphite-next-baseline.T2FTs9/graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar')
JAR = pathlib.Path('/private/tmp/graphite-query-marker-diagnostic/diagnostic-jmh.jar')
EXPECTED_BASE = 'a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe'
EXPECTED_JAR = '2728888d2820fc9721149302612c94b1001bfd38ee5a8c69244fed59fc9b6ef9'

def sha(path):
    h=hashlib.sha256()
    with pathlib.Path(path).open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
    return h.hexdigest()

def write(name,data): (ROOT/name).write_text(json.dumps(data,indent=2)+'\n')

def inventory():
    result=[]
    for line in MANIFEST.read_text().splitlines():
        if not line or line.startswith('#'):continue
        fields=line.split('\t');directory=pathlib.Path(fields[1])
        result.append({'id':fields[0],'files':[{'path':str(p.relative_to(directory)),'size':p.stat().st_size,'sha256':sha(p)} for p in sorted(directory.rglob('*')) if p.is_file()]})
    return result

mode=sys.argv[1]
assert mode in ('control','profile')
assert not any(os.environ.get(key) for key in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS'])
assert sha(BASE)==EXPECTED_BASE and sha(JAR)==EXPECTED_JAR
audit=json.loads((ROOT/'root-overlay-audit.json').read_text())
assert audit['overlayJarSha256']==EXPECTED_JAR and audit['allProductionEntriesIdentical']
if mode=='profile':
    verified=json.loads((ROOT/'control-verification.json').read_text())
    assert verified['passed'] and verified['markers']==34 and verified['oracleSignatures']==34
before=inventory()
assert before==json.loads((PRIOR/'v3-control/graph-content-after.json').read_text())
write(mode+'-graph-content-before.json',before)
template=json.loads((PRIOR/'old34-pairs/base-global-wide-1-command.json').read_text())
names=['control'] if mode=='control' else ['profile-1','profile-2','profile-3']
runs=[]
for name in names:
    prefix=ROOT/name
    assert not prefix.with_suffix('.jfr').exists() and not prefix.with_suffix('.log').exists()
    command=list(template);command[2]=str(JAR)
    command[command.index('-rff')+1]=str(prefix)+'.jmh.json'
    command[-1]=command[-1].replace(str(PRIOR/'old34-pairs/base-global-wide-1.tsv'),str(prefix)+'.tsv')
    command[-1]+=f' -XX:StartFlightRecording=settings={ROOT/(mode+".jfc")},filename={prefix}.jfr,dumponexit=true -XX:FlightRecorderOptions=stackdepth=256'
    if mode=='profile':command[-1]+=f' -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile={prefix}.compilation.xml'
    write(name+'-command.json',command)
    before_jar=sha(JAR);assert before_jar==EXPECTED_JAR
    started=time.time()
    print('START',name,flush=True)
    with pathlib.Path(str(prefix)+'.log').open('w') as log:
        result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
    row={'name':name,'startedEpoch':started,'endedEpoch':time.time(),'exitCode':result.returncode,'diagnosticJarSha256Before':before_jar,'diagnosticJarSha256After':sha(JAR),'baseJarSha256After':sha(BASE)}
    runs.append(row);write(mode+'-runs.json',runs)
    assert result.returncode==0 and row['diagnosticJarSha256After']==EXPECTED_JAR and row['baseJarSha256After']==EXPECTED_BASE
    assert pathlib.Path(str(prefix)+'.jfr').stat().st_size>0
    print('DONE',name,flush=True)
after=inventory();write(mode+'-graph-content-after.json',after)
assert before==after
write(mode+'-completion.json',{'processesTerminal':True,'forks':len(names),'graphFiles':sum(len(g['files']) for g in after),'graphsUnchanged':True,'jarSha256':sha(JAR),'baseSha256':sha(BASE),'newProductionCandidate':False})
print('COMPLETE',mode,'all processes terminal, graph and JAR hashes unchanged',flush=True)
