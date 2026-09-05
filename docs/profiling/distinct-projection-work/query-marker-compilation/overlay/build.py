from pathlib import Path
import subprocess,time,json,hashlib
P=Path(__file__).resolve().parent
J='/opt/homebrew/opt/openjdk@17/bin/'
BASE='/private/tmp/graphite-next-baseline.T2FTs9/graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar'
commands=[
 [J+'javac','--release','17','-cp',BASE,'-d',str(P/'classes'),str(P/'src/QueryExecutionMarker.java')],
 ['python3',str(P/'patch.py')],
 [J+'javap','-c','-p','-s','-classpath',str(P/'diagnostic-jmh.jar'),'io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark'],
 [J+'javap','-c','-p','-s','-v','-classpath',str(P/'diagnostic-jmh.jar'),'io.johnsonlee.graphite.diagnostic.QueryExecutionMarker'],
 [J+'javap','-c','-p','-s','-v','-classpath',str(P/'diagnostic-jmh.jar'),'io.johnsonlee.graphite.diagnostic.QueryExecutionMarker$QueryWindow'],
]
outputs=['javac.log','patch.log','diagnostic-benchmark.javap.txt','marker-helper.javap.txt','marker-event.javap.txt']
receipts=[]
for cmd,name in zip(commands,outputs):
 print('START',name,flush=True);started=time.time()
 with (P/name).open('w') as stream:r=subprocess.run(cmd,stdout=stream,stderr=subprocess.STDOUT)
 receipts.append({'command':cmd,'output':name,'exitCode':r.returncode,'startedEpoch':started,'endedEpoch':time.time(),'outputSha256':hashlib.sha256((P/name).read_bytes()).hexdigest()})
 (P/'commands.json').write_text(json.dumps(receipts,indent=2)+'\n')
 print('END',name,r.returncode,flush=True)
 if r.returncode:raise SystemExit(r.returncode)
print('ALL COMPILER/PATCH/JAVAP PROCESSES TERMINAL; NO QUERIES OR JFR CAPTURES',flush=True)
