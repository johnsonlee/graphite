#!/usr/bin/env python3
"""Small CLI correctness observations; no server or graph runtime is started."""
from pathlib import Path
import subprocess,json
out=Path(__file__).resolve().parent
base=Path('/tmp/graphite-go-main-baseline-clone-4e328b0')
jar=base/'graphite-explore/build/libs/graphite-explore.jar'
java=['java','-Dfile.encoding=UTF-8','-Xmx128m','-XX:ActiveProcessorCount=2']
commands={'main-explore':java+['-jar',str(jar)],'main-serve':java+['-cp',str(base/'graphite-query/build/libs/query-1.0.0-SNAPSHOT-slim.jar')+':'+str(jar),'io.johnsonlee.graphite.cli.MainKt','serve'],'native':['/tmp/graphite-packaging-audit-server']}
records=[]
for name,command in commands.items():
 for args in [['--help'],['-h'],['--version'],['--unknown'],[]]:
  result=subprocess.run(command+args,capture_output=True,text=True,timeout=15)
  records.append({'target':name,'command':command+args,'exitCode':result.returncode,'stdout':result.stdout,'stderr':result.stderr})
(out/'cli-observations.json').write_text(json.dumps(records,indent=2)+'\n')
