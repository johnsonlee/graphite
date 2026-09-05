from pathlib import Path
import os,subprocess,json,hashlib,time,shutil
root=Path(__file__).parent; clone=root/'repo'; java='/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'; env=os.environ.copy(); env['JAVA_HOME']=java; env['JAVA_TOOL_OPTIONS']='-XX:ActiveProcessorCount=4'; env['PATH']=java+'/bin:'+env['PATH']
cmd=['./gradlew',':webgraph:jmhJar',':webgraph:verifyJmhJarExcludesTests','--no-daemon']
(root/'build-command.json').write_text(json.dumps({'cwd':str(clone),'argv':cmd,'environmentOverrides':{'JAVA_HOME':java,'JAVA_TOOL_OPTIONS':env['JAVA_TOOL_OPTIONS'],'PATHPrefix':java+'/bin'},'diagnosticOnly':True,'noTestOrBenchmarkRun':True},indent=2)+'\n')
with (root/'java-version.txt').open('w') as log: subprocess.run([java+'/bin/java','-version'],stdout=log,stderr=subprocess.STDOUT,env=env,check=True)
start=time.time()
with (root/'build.log').open('w') as log: result=subprocess.run(cmd,cwd=clone,env=env,stdout=log,stderr=subprocess.STDOUT)
receipt={'diagnosticOnly':True,'previousAttempt133RejectionUnchanged':True,'exitCode':result.returncode,'elapsedSeconds':time.time()-start,'noProfileOrBenchmarkStarted':True}
if result.returncode==0:
 src=clone/'graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar'; dst=root/'rejected133-diagnostic-jmh.jar'; shutil.copyfile(src,dst)
 receipt.update({'jar':str(dst),'jarSha256':hashlib.sha256(dst.read_bytes()).hexdigest(),'jarBytes':dst.stat().st_size,'cloneHead':subprocess.check_output(['git','rev-parse','HEAD'],cwd=clone,text=True).strip(),'cloneStatus':subprocess.check_output(['git','status','--porcelain'],cwd=clone,text=True)})
 source=json.loads((root/'source-receipt.json').read_text())
 for row in source['mainAndJmhFiles']: assert hashlib.sha256((clone/row['path']).read_bytes()).hexdigest()==row['sha256'],row['path']
 receipt['allSourceFilesMatchBeforeBuild']=True
(root/'build-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(receipt),flush=True)
raise SystemExit(result.returncode)
