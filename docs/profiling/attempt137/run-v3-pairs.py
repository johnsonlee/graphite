import hashlib,json,pathlib,subprocess,sys
root=pathlib.Path(__file__).parent
worktree=pathlib.Path('/Users/johnsonlee/.codex/worktrees/ac7b5da2-2450-48c5-894c-5fd84ab6cb7d/graphite')
profile=pathlib.Path('/private/tmp/graphite-main-profiling-n50joikp')
base=pathlib.Path('/private/tmp/graphite-next-baseline.T2FTs9/graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar')
manifest=pathlib.Path('/private/tmp/pr113-attempt131-ascii.JqgmHw/fixture64/graphs.tsv')
java='/opt/homebrew/opt/openjdk@17/bin/java'
jars={'base':base,'candidate':root/'candidate-jmh.jar'}
initial={s:hashlib.sha256(p.read_bytes()).hexdigest() for s,p in jars.items()}
for pair in range(1,4):
 for side in (['candidate','base'] if pair%2 else ['base','candidate']):
  output=root/f'v3-pair-{pair}-{side}'
  cmd=['python3',str(worktree/'.github/scripts/wide-query-profile/run.py'),'--java',java,'--trusted-jar',str(base),'--jar',str(jars[side]),'--manifest',str(manifest),'--catalog-dir',str(profile/'oracle-v3'),'--output',str(output),'--forks','1']
  (root/f'v3-pair-{pair}-{side}-command.json').write_text(json.dumps(cmd,indent=2))
  print('START',pair,side,flush=True)
  subprocess.run(cmd,check=True)
  receipt=json.loads((output/'run.json').read_text())
  assert receipt['status']=='complete' and receipt['queryCount']==36 and receipt['completedVerifiedForks']==1
  print('VERIFIED',pair,side,'36',flush=True)
assert initial=={s:hashlib.sha256(p.read_bytes()).hexdigest() for s,p in jars.items()}
(root/'v3-pairs-receipt.json').write_text(json.dumps({'status':'complete','pairs':3,'queriesPerRun':36,'verifiedObservations':216,'jarHashes':initial,'notP95':True},indent=2)+'\n')
