"""Regenerate the bounded actual-main oracle in a NEW directory; never benchmark."""
import argparse, hashlib, json, pathlib, shutil, subprocess
p=argparse.ArgumentParser()
p.add_argument('--jar',type=pathlib.Path,required=True)
p.add_argument('--java-home',type=pathlib.Path,required=True)
p.add_argument('--output',type=pathlib.Path,required=True)
a=p.parse_args(); here=pathlib.Path(__file__).resolve().parent
if a.output.exists(): raise SystemExit('output must not already exist')
a.output.mkdir(parents=True); a.output=a.output.resolve(); a.jar=a.jar.resolve()
commands=[]
def run(args,out=None):
    result=subprocess.run([str(x)for x in args],capture_output=True)
    commands.append({'command':[str(x)for x in args],'exitCode':result.returncode,'stderr':result.stderr.decode('utf-8','replace')})
    (a.output/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
    if result.returncode: raise SystemExit(result.stderr.decode('utf-8','replace'))
    if out: (a.output/out).write_bytes(result.stdout)
for name in ['GenerateGeneric.java','GenericOracle.java','GenericFaultOracle.java','queries.json','edge-queries.json','fault-queries.json','wire_oracles.py']:
    shutil.copy2(here/name,a.output/name)
for name in ['callsites','bad-first','bad-last']: shutil.copytree(here/name,a.output/name)
run([a.java_home/'bin/javac','-encoding','UTF-8','-cp',a.jar,'-d',a.output,*[a.output/n for n in ['GenerateGeneric.java','GenericOracle.java','GenericFaultOracle.java']]])
java=[a.java_home/'bin/java','-Dfile.encoding=UTF-8','-Xmx256m','-XX:ActiveProcessorCount=2','-cp',str(a.output)+':'+str(a.jar)]
run(java+['GenerateGeneric',a.output/'store'])
run(java+['GenericOracle',a.output/'store',a.output/'queries.json'],'main.jsonl')
run(java+['GenericOracle',a.output/'store',a.output/'edge-queries.json'],'edge-main.jsonl')
for name in ['callsites','bad-first','bad-last']:
    run(java+['GenericFaultOracle',a.output/name,a.output/'fault-queries.json'],name+'-main.jsonl')
run(['python3',a.output/'wire_oracles.py'])
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
(a.output/'manifest.json').write_text(json.dumps({'jarSHA256':sha(a.jar),'files':{str(f.relative_to(a.output)):sha(f)for f in sorted(a.output.rglob('*'))if f.is_file()}},indent=2)+'\n')
