import pathlib,json,hashlib,subprocess,shutil
r=pathlib.Path(__file__).resolve().parent
root=pathlib.Path('/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite/docs/go-server-baseline/native64-label-alias-attempt16')
w=pathlib.Path('/tmp/graphite-go-label-candidate-4e94017f/graphite-server')
records=[]
for phase in ['base','candidate']:
 i=json.load(open(root/phase/'identity.json'));binary=pathlib.Path(i['command'][0]);assert hashlib.sha256(binary.read_bytes()).hexdigest()==i['binarySHA256']
 manifest=json.load(open(root/phase/'source-manifest.json'))
 if phase=='candidate':
  for entry in manifest:assert hashlib.sha256((w/entry['path']).read_bytes()).hexdigest()==entry['sha256'],entry['path']
 dst=r/phase;dst.mkdir(exist_ok=True)
 for name in ['identity.json','source-manifest.json','completion.json','binary-build-info.txt']:
  shutil.copyfile(root/phase/name,dst/name)
 src=root/phase/'profiles/e-generic-distinct'
 for p in src.iterdir():shutil.copyfile(p,dst/p.name)
 for label,args in [('alloc-top',['-top','-nodecount=30']),('alloc-node-value',['-list','nodeValue']),('alloc-lazy-filtered',['-list','lazyFiltered$'])]:
  command=['go','tool','pprof','-alloc_space','-unit=bytes','-nodefraction=0','-edgefraction=0']+args+['-base',str(dst/'heap-before.pprof'),str(binary),str(dst/'heap-after.pprof')]
  q=subprocess.run(command,cwd=w,capture_output=True,text=True)
  (dst/(label+'.stdout')).write_text(q.stdout);(dst/(label+'.stderr')).write_text(q.stderr)
  records.append({'command':command,'cwd':str(w),'exit':q.returncode,'stdout':str((dst/(label+'.stdout')).relative_to(r)),'stderr':str((dst/(label+'.stderr')).relative_to(r))})
(r/'commands.json').write_text(json.dumps(records,indent=2)+'\n')
print('pprof exits',[x['exit'] for x in records])
