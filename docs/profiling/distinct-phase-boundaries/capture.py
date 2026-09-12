import json,pathlib,subprocess,hashlib,sys
root=pathlib.Path(__file__).parent
repo=pathlib.Path('/Users/johnsonlee/.codex/worktrees/ac7b5da2-2450-48c5-894c-5fd84ab6cb7d/graphite')
sys.path.insert(0,str(repo/'.github/scripts/wide-query-profile'))
from run import graph_identity
manifest=pathlib.Path('/private/tmp/pr113-attempt131-ascii.JqgmHw/fixture64/graphs.tsv')
prior=pathlib.Path('/private/tmp/graphite-main-profiling-n50joikp')
template=json.loads((prior/'cpu-3-command.json').read_text())
jar=pathlib.Path(template[2]);before=graph_identity(manifest)
jarhash=hashlib.sha256(jar.read_bytes()).hexdigest()
(root/'input-receipt.json').write_text(json.dumps({'graphFiles':before,'jar':str(jar),'jarSha256':jarhash,'manifestSha256':hashlib.sha256(manifest.read_bytes()).hexdigest(),'profilerDocumentation':'https://github.com/async-profiler/async-profiler/discussions/1497','purpose':'Frozen-main phase diagnostic only; no production edit or acceptance comparison'},indent=2)+'\n')
for i in range(1,4):
 name=f'phase-{i}';cmd=[s.replace(str(prior/'cpu-3'),str(root/name)).replace(str(prior/'jvm-cpu-3'),str(root/('jvm-'+name))) for s in template]
 extra=',trace=io.johnsonlee.graphite.cypher.QueryPipeline.executeIndexedDistinctStringProjection$projectSource,trace=io.johnsonlee.graphite.cypher.QueryPipeline.executeIndexedDistinctStringProjection$lambda$155$lambda$154'
 cmd[-1]=cmd[-1].replace(',file='+str(root/name)+'.jfr',extra+',file='+str(root/name)+'.jfr')
 assert cmd[-1].count('trace=')==3
 (root/(name+'-command.json')).write_text(json.dumps(cmd,indent=2)+'\n')
 print('START',name,flush=True)
 with (root/(name+'.log')).open('w') as out:subprocess.run(cmd,stdout=out,stderr=subprocess.STDOUT,check=True)
 print('DONE',name,flush=True)
 after=graph_identity(manifest)
 assert before==after and hashlib.sha256(jar.read_bytes()).hexdigest()==jarhash
(root/'input-after-receipt.json').write_text(json.dumps({'graphContentUnchanged':True,'jarUnchanged':True,'completedRecordings':3,'candidate':False},indent=2)+'\n')
