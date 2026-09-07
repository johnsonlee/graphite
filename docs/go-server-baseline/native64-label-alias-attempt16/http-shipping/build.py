import hashlib,json,os,pathlib,subprocess
here=pathlib.Path(__file__).resolve().parent
root=here.parents[3]
worktree=pathlib.Path('/tmp/graphite-go-label-http-4e94017f').resolve()
module=worktree/'graphite-server'
read=lambda p:json.loads(p.read_text())
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
tested=read(here.parent/'tested-source.json')
actual={str(p.relative_to(module)):sha(p) for p in module.rglob('*') if p.is_file() and '__pycache__' not in p.parts}
assert actual==tested
assert not (module/'cmd/profile-query').exists()
assert not (module/'internal/server/profile_export.go').exists()
assert not (module/'internal/store/profile_state.go').exists()
binary=pathlib.Path('/tmp/graphite-label-http-binary-4e94017f')
command=['go','build','-o',str(binary),'./cmd/graphite-server']
subprocess.run(command,cwd=module,check=True)
(here/'binary-build-info.txt').write_bytes(subprocess.check_output(['go','version','-m',str(binary)]))
raw=subprocess.check_output(['go','list','-json','./...'],cwd=module,text=True)
(here/'go-list.json').write_text(raw)
decoder=json.JSONDecoder();remaining=raw;inputs={}
while remaining.strip():
 obj,end=decoder.raw_decode(remaining.lstrip());remaining=remaining.lstrip()[end:]
 for key in ['GoFiles','CgoFiles','CFiles','SFiles','EmbedFiles']:
  for name in obj.get(key,[]):
   path=pathlib.Path(obj['Dir'])/name;inputs[str(path.relative_to(worktree))]=sha(path)
for name in ['go.mod','go.sum']:inputs['graphite-server/'+name]=sha(module/name)
for name,h in inputs.items():assert sha(worktree/name)==h
identity={'base':'4e94017f','worktree':str(worktree),'nativeSourcesAndEmbeds':inputs,'inputScope':'Active current-platform go list compiler/embed inputs plus module manifests; entire module identity recorded separately.','binary':str(binary),'binarySHA256':sha(binary),'compiler':subprocess.check_output(['go','version'],text=True).strip(),'buildCommand':command,'runner':str(here/'replay-http.py'),'runnerSHA256':sha(here/'replay-http.py'),'configSHA256':sha(here/'config.json'),'profilingEnvironment':{k:None for k in ['GRAPHITE_NATIVE_CPU_PROFILE','GRAPHITE_PROFILE']},'executionEnvironment':{k:None for k in ['GOGC','GODEBUG','GOMEMLIMIT','GOMAXPROCS']},'noProfileHelpers':True,'phases':'Two fresh sequential server processes,21+42 complete responses.'}
(here/'source-identity.json').write_text(json.dumps(identity,indent=2)+'\n')
(here/'source-inputs.json').write_text(json.dumps({'base':'4e94017f','clone':'/tmp/graphite-label-http-real64-4e94017f','testedFiles':tested,'original42SHA256':sha(root/'docs/go-server-baseline/native64-preflight/queries/observations.json'),'allSourceInputsMatchFullModuleTestedCandidate':True,'sourceInputCount':len(tested)},indent=2)+'\n')
print('Shipping build verified:',len(tested),'module inputs;',len(inputs),'compiler/embed inputs;',identity['binarySHA256'])
