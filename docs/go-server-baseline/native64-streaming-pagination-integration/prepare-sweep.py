from pathlib import Path
import hashlib,json,datetime
here=Path(__file__).resolve().parent
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
base=here/'base';completion=json.loads((base/'completion.json').read_text());assert completion['exitCode']==2
identity=json.loads((base/'identity.json').read_text());assert sha(Path(identity['command'][0]))==identity['binarySHA256']
source=json.loads((base/'source-manifest.json').read_text());module=Path('/tmp/graphite-go-pagination-base-62b92d20/graphite-server')
for p in source:assert sha(module/p['path'])==p['sha256'],p['path']
fixture=json.loads((here.parent/'native64-profile-a7de0bec/fixture-files.json').read_text());cfg=json.loads((here/'config.json').read_text());paths={g['id']:Path(g['path']) for g in cfg['graphs']}
def digest(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
 return h.hexdigest()
for p in fixture:assert digest(paths[p['graphId']]/p['file'])==p['sha256'],p
actual={str(p.relative_to(next(iter(paths.values())).parent)) for g in paths.values() for p in g.rglob('*') if p.is_file()}
assert actual=={p['graphId']+'/'+p['file'] for p in fixture}
(here/'initial-base-stop-verification.json').write_text(json.dumps({'exitCode':2,'allInitialSourcesAndBinaryUnchangedBeforeHarnessChange':True,'all1152CloneHashesMatch':True,'noExtraGraphFiles':True,'originalRunDidNotReachClose':True,'failure':'Complete successful response differs at b-distinct-order-eviction-candidate; raw output and strict fail retained','action':'Fresh collecting sweeps keep every mismatch as failure, continue original query history, close Stores, exit2 if any mismatch. No production/query/oracle change.'},indent=2)+'\n')
s=(here/'profile-query.go').read_text()
s=s.replace('for _, q := range cfg.Queries {\n\t\tprofile(*out, q, sources)\n\t}', 'allMatch := true\n\tfor _, q := range cfg.Queries {\n\t\tif !profile(*out, q, sources) { allMatch = false }\n\t}')
s=s.replace('fmt.Println("COMPLETE")','fmt.Println("COMPLETE", "allOutputsMatch", allMatch)\n\tif !allMatch { os.Exit(2) }')
s=s.replace('func profile(out string, q querySpec, sources []query.Graph) {','func profile(out string, q querySpec, sources []query.Graph) bool {')
s=s.replace('if queryErr != nil || !equal {\n\t\tpanic("query error or complete output mismatch")\n\t}', 'return queryErr == nil && equal')
assert 'allMatch = false' in s and 'return queryErr == nil && equal' in s
(here/'profile-query-sweep.go').write_text(s)
(module/'cmd/profile-query/main.go').write_text(s)
script=(here/'run-profile.py').read_text().replace("here / 'profile-query.go'", "here / 'profile-query-sweep.go'")
(here/'run-profile-sweep.py').write_text(script)
print('Strict failure retained; collecting harness prepared, no production/query/oracle changes',flush=True)
