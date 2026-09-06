#!/usr/bin/env python3
from pathlib import Path
import subprocess,json
out=Path(__file__).resolve().parent
base=Path('/tmp/graphite-go-main-baseline-clone-4e328b0')
jar=base/'graphite-explore/build/libs/graphite-explore.jar'
java=['java','-Dfile.encoding=UTF-8','-Xmx128m','-XX:ActiveProcessorCount=2']
commands={'explore':java+['-jar',str(jar)],'serve':java+['-cp',str(base/'graphite-query/build/libs/query-1.0.0-SNAPSHOT-slim.jar')+':'+str(jar),'io.johnsonlee.graphite.cli.MainKt','serve']}
cases=[[],['--help'],['-h'],['--version'],['-V'],['--unknown'],['--unknown','--help'],['--unknown','--bogus'],['-hV'],['--help=false'],['--help=true'],['--version=false'],['--metrics=false'],['--metrics','false'],['--metrics=invalid'],['--data'],['--port'],['--load-mode'],['--port','abc'],['-p','2147483648'],['--cypher-max-timeout-ms','9223372036854775808'],['--port','--metrics'],['--load-mode','mapped'],['--load-mode','bogus'],['/graph'],['a','b'],['--data','/tmp','a','b'],['--port','1','--port','2','--help'],['--metrics','--metrics','--help'],['--graph','a:dir','--graph','b:dir','--help'],['-p80','--help'],['-p=80','--help'],['-p80h'],['--','-h'],['--data','/tmp','--max-concurrent-cypher','0'],['--data','/tmp','--cypher-max-timeout-ms','0'],['--data','/tmp','--port','-1','--max-concurrent-cypher','0'],['--data','/tmp','--load-mode','AUTO','--max-concurrent-cypher','0'],['--data','space path','--help'],['--metrics=true','--help'],['--id=app','--help'],['-help'],['--loa','MAPPED'],['--topology'],['--id'],['--graph'],['--max-concurrent-cypher'],['--cypher-work-budget'],['--cypher-max-timeout-ms']]
cases += [['-p',v,'--help'] for v in ['0x50','080','+80','1_000','１２','١٢',' 12','1e3']]
cases += [[v] for v in ['--metrcs','--cy','--m','---help','--p','--h']]
cases += [['@testdata/cli/'+name+'.args'] for name in ['help','nested','quoted','separate','unclosed','bad-number']]
cases += [['@testdata/cli/help.args','@testdata/cli/help.args'],['@@--help'],['@missing-graphite-file'],['@']]
records=[]
for mode,command in commands.items():
 for args in cases:
  r=subprocess.run(command+args,capture_output=True,text=True,timeout=15,cwd=out.parents[1])
  records.append({'mode':mode,'args':args,'exitCode':r.returncode,'stdout':r.stdout,'stderr':r.stderr})
(out/'main.json').write_text(json.dumps(records,indent=2)+'\n')
for r in records:
 if r['mode']=='serve':print(r['args'],r['exitCode'],repr((r['stderr'] or r['stdout']).split('\n')[0]))
