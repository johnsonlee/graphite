"""Regenerate direct Java load/read/query lifecycle evidence, only tiny graphs."""
import argparse,json,pathlib,subprocess,tempfile
p=argparse.ArgumentParser();p.add_argument('--jar',required=True);p.add_argument('--java-home',required=True);p.add_argument('--out',required=True);a=p.parse_args()
here=pathlib.Path(__file__).resolve().parent;module=here.parents[3];out=pathlib.Path(a.out).resolve();out.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory(prefix='graphite-node-tag-classes-') as classes:
 compile=[str(pathlib.Path(a.java_home)/'bin/javac'),'-cp',a.jar,'-d',classes,str(here/'NodeTagOracle.java')]
 subprocess.run(compile,check=True)
 run=[str(pathlib.Path(a.java_home)/'bin/java'),'-Xmx128m','-cp',classes+':'+a.jar,'NodeTagOracle',str(module/'internal/query/testdata/traversal'),str(here/'prepared.nodeindex'),str(out/'main-lifecycle.json')]
 with (out/'main-lifecycle.log').open('w') as log:result=subprocess.run(run,stdout=log,stderr=log)
 (out/'main-lifecycle-command.json').write_text(json.dumps({'compile':compile,'command':run,'exitCode':result.returncode},indent=2)+'\n')
 result.check_returncode()
 assert len(json.loads((out/'main-lifecycle.json').read_text()))==72
