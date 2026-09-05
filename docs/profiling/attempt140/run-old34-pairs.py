import hashlib,json,pathlib,shutil,subprocess
root=pathlib.Path(__file__).parent
out=root/'old34-pairs';out.mkdir()
prior=pathlib.Path('/private/tmp/graphite-mapped-tuple-evidence.t2461mo1')
shutil.copyfile(prior/'oracle.correctness',out/'oracle.correctness')
template=json.loads((prior/'oracle-command.json').read_text())
jars={'base':'/private/tmp/graphite-next-baseline.T2FTs9/graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar','candidate':str(root/'candidate-jmh.jar')}
initial={side:hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest() for side,p in jars.items()}
for pair in range(1,4):
 for side in (['candidate','base'] if pair%2 else ['base','candidate']):
  prefix=out/f'{side}-global-wide-{pair}'
  cmd=template.copy();cmd[2]=jars[side];cmd[cmd.index('-rff')+1]=str(prefix)+'.json'
  cmd[-1]=cmd[-1].replace('correctness.mode=record','correctness.mode=verify').replace('pressure.output='+str(prior/'oracle.correctness'),'pressure.correctness.oracle='+str(out/'oracle.correctness')).replace(str(prior/'oracle.tsv'),str(prefix)+'.tsv')
  pathlib.Path(str(prefix)+'-command.json').write_text(json.dumps(cmd,indent=2))
  print('START old34',pair,side,flush=True)
  with pathlib.Path(str(prefix)+'.log').open('w') as log:subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,check=True)
  print('DONE old34',pair,side,flush=True)
cmd=[arg.replace(str(prior),str(out)) for arg in json.loads((prior/'comparison-command.json').read_text())]
(out/'comparison-command.json').write_text(json.dumps(cmd,indent=2))
result=subprocess.run(cmd)
status=json.loads((out/'global-wide-status.json').read_text())
assert initial=={side:hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest() for side,p in jars.items()}
progress=all(r['p95LatencyNanos']<r['baseP95LatencyNanos'] for r in status['runs'])
(out/'local-progress.json').write_text(json.dumps({'jarHashes':initial,'comparisonExit':result.returncode,'regressionPassed':status['regressionPassed'],'strictProgressEveryPair':progress,'targetAchieved':status['targetAchieved'],'accepted':False,'reason':'CI acceptance not established'},indent=2)+'\n')
print('COMPARISON',result.returncode,'regressionPassed',status['regressionPassed'],'strictProgressEveryPair',progress,'targetAchieved',status['targetAchieved'],flush=True)
