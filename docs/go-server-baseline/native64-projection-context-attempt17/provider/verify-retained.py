import pathlib,json,hashlib,shutil
r=pathlib.Path(__file__).resolve().parent
w=pathlib.Path('/tmp/graphite-go-projection-context-attempt17-62b92d20/graphite-server')
(r/'verify-corpus-before-retained.py').write_bytes((r/'verify-corpus.py').read_bytes());(r/'verify-corpus-before-retained.log').write_bytes((r/'verify-corpus.log').read_bytes())
observations=[]
paths=[r/'base-ordinary/rolling-native.json',r/'candidate-ordinary/rolling-native.json']+[r/f'original-rolling-repeat-{i}/rolling-native.json' for i in range(4)]
(r/'existing-main').mkdir(exist_ok=True)
for i in range(3):
 p=w/f'internal/query/testdata/ordinary-projection/rolling-main-{i}.json';dst=r/'existing-main'/p.name;shutil.copyfile(p,dst);paths.append(dst)
def response(x):return {k:v for k,v in x.items() if k not in ['before','after']}
reference=json.load(open(paths[0]))
for p in paths:
 data=json.load(open(p));assert len(data)==len(reference)
 for a,b in zip(reference,data): assert [response(x) for x in a['targets']]==[response(x) for x in b['targets']]
 case=data[8];assert case['count']==40 and case['scenario']=='early-match-later-error'
 observations.append({'path':str(p.relative_to(r)),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'targets':[{phase:[x['retained'] for x in t[phase][:9]] for phase in ['before','after']} for t in case['targets']]})
proof={'case':8,'sources':40,'scenario':'early-match-later-error','projection':'n.caller_name','changedSources':['g5','g7'],'changedLocations':['targets[0].after','targets[1].before'],'completeRollingResponsesEqualAllNineCaptures':True,'caveat':'Original initial full capture is non-race; four original-production-only repeats use race instrumentation, as does candidate. This establishes reachable old states, not a timing distribution or exact schedule equivalence. Existing three JVM oracles also vary these retained states.','observations':observations}
(r/'retained-state-proof.json').write_text(json.dumps(proof,indent=2)+'\n')
s=(r/'verify-corpus.py').read_text()
s=s.replace("assert re.fullmatch(r'\\$\\[\\d+\\]\\.targets\\[\\d+\\]\\.(before|after)\\[\\d+\\]\\.mappedView',d['path']),d", "assert re.fullmatch(r'\\$\\[\\d+\\]\\.targets\\[\\d+\\]\\.(before|after)\\[\\d+\\]\\.mappedView',d['path']) or (item['base']=='base-ordinary/rolling-native.json' and re.fullmatch(r'\\$\\[8\\]\\.targets(?:\\[0\\]\\.after|\\[1\\]\\.before)\\[(5|7)\\]\\.retained',d['path'])),d")
s=s.replace("'40-source speculative mappedView state only; full raw artifacts retained, not normalized or called exact'", "'91 mappedView leaves plus exactly four retained leaves in rolling case8/sourceg5,g7: independent old-production/JVM variation recorded in retained-state-proof.json. No other states ignored. Full raw artifacts retained, not normalized or called exact'")
(r/'verify-corpus.py').write_text(s)
