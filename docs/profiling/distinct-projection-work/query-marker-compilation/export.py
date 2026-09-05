import hashlib
import json
import pathlib
import subprocess
import sys

ROOT=pathlib.Path(__file__).parent
JAVA=pathlib.Path('/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin')
name=sys.argv[1]
assert name in ('control','profile-1','profile-2','profile-3')
source=ROOT/(name+'.jfr')
output=ROOT/(name+'.events.json')
metadata=ROOT/(name+'.event-types.json')
events='graphite.diagnostic.QueryExecution,jdk.Deoptimization,jdk.Compilation,jdk.CompilationFailure,jdk.ExecutionSample,jdk.JVMInformation,jdk.ActiveRecording,jdk.ActiveSetting,jdk.DataLoss,jdk.GarbageCollection,jdk.GCPhasePause'
commands=[]
command=[str(JAVA/'jfr'),'print','--json','--stack-depth','256','--events',events,str(source)]
before=hashlib.sha256(source.read_bytes()).hexdigest()
if not output.exists():
    with output.open('w') as stream:subprocess.run(command,stdout=stream,check=True)
commands.append(command)
command=[str(JAVA/'java'),'-cp',str(ROOT),'JfrEventTypes',str(source),str(metadata)]
subprocess.run(command,check=True);commands.append(command)
assert before==hashlib.sha256(source.read_bytes()).hexdigest()
receipt={'name':name,'commands':commands,'jfrSha256BeforeAfter':before,'eventsJsonSha256':hashlib.sha256(output.read_bytes()).hexdigest(),'eventTypesSha256':hashlib.sha256(metadata.read_bytes()).hexdigest(),'metadataSourceSha256':hashlib.sha256((ROOT/'JfrEventTypes.java').read_bytes()).hexdigest(),'metadataClassSha256':hashlib.sha256((ROOT/'JfrEventTypes.class').read_bytes()).hexdigest(),'eventTypesCompilerCommand':[str(JAVA/'javac'),'-cp',str(ROOT),'-d',str(ROOT),str(ROOT/'JfrEventTypes.java')],'captureProcessesAlreadyTerminal':True}
(ROOT/(name+'-export-receipt.json')).write_text(json.dumps(receipt,indent=2)+'\n')
print('Exported',name)
