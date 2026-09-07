import pathlib,subprocess,json,shutil,struct
root=pathlib.Path(__file__).resolve().parents[2]; ev=root/'streaming-pagination-review/jvm'; td=root/'graphite-server/internal/query/testdata/streaming-pagination'
q="MATCH (n) WHERE n.caller_name CONTAINS 'other' RETURN "
specs=[]
for count in [2,9,40]:
 for kind,tail in [('skip',"n.id AS x SKIP 1 LIMIT 1"),('distinct',"DISTINCT n.id AS x SKIP 0 LIMIT 1"),('order',"n.id AS x ORDER BY x SKIP 0 LIMIT 1")]:
  specs.append(dict(name=f'{kind}-late-{count}',count=count,badAt=count-1,fixture='bad-matched',query=q+tail))
 for mode in ['MAPPED','EAGER']:
  specs.append(dict(name=f'order-ties-{count}-{mode}',count=count,mode=mode,reverseIDs=True,repeat=2,query=q+"n.id AS x ORDER BY 0 SKIP 1 LIMIT 3"))
for count in [2,40]:
 for order in [False,True]:
  specs.append(dict(name=f'route-offset-{count}-{order}',count=count,badAt=0,fixture='offset-negative-90',repeat=2,query="MATCH (n) WHERE n.caller_name CONTAINS 'other' AND n.graphId = 'g0' RETURN n.id AS x "+('ORDER BY x ' if order else '')+'SKIP 0 LIMIT 1'))
for kind,tail in [('skip',"n.id AS x SKIP 1 LIMIT 1"),('distinct',"DISTINCT n.id AS x SKIP 0 LIMIT 1"),('order',"n.id AS x ORDER BY x SKIP 0 LIMIT 1")]:
 specs.append(dict(name=f'{kind}-missing-sidecar',count=9,badAt=8,fixture='bad-matched',missingIndex=True,query=q+tail))
for name,condition in [
 ('route-in',"n.caller_name CONTAINS 'other' AND n.graphId IN ['g0']"),
 ('route-or-residual',"(n.graphId = 'g0' AND n.caller_name CONTAINS 'other') OR (n.graphId = 'g0' AND n.caller_name STARTS WITH 'oth')"),
 ('route-conflict',"n.caller_name CONTAINS 'other' AND n.graphId='g0' AND graphId(n)='g1'"),
 ('route-empty',"n.caller_name CONTAINS 'other' AND n.graphId IN []"),
 ('route-unknown-or',"n.caller_name CONTAINS 'other' AND (n.graphId='g0' OR true)")]:
 specs.append(dict(name=name,count=2,badAt=1,fixture='bad-matched',query='MATCH (n) WHERE '+condition+' RETURN n.id AS x ORDER BY x SKIP 0 LIMIT 1'))
specs.append(dict(name='route-inline',count=2,badAt=1,fixture='bad-matched',query="MATCH (n:CallSiteNode {graphId:'g0'}) WHERE n.caller_name CONTAINS 'other' RETURN n.id AS x ORDER BY x SKIP 0 LIMIT 1"))
(td/'source-cases.json').write_text(json.dumps(specs,indent=2)+'\n')
fixtures=ev/'fixtures'; mut=fixtures/'offset-negative-90';shutil.copytree(fixtures/'clean',mut,dirs_exist_ok=True);p=mut/'graph.nodeoffsets';b=bytearray(p.read_bytes());struct.pack_into('>Q',b,8+90*8,2**64-1);p.write_bytes(b)
java='/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home/bin/java';jar='/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar';cp=str(ev/'classes')+':'+jar
cmds=[[java[:-4]+'javac','-cp',jar,'-d',str(ev/'classes'),str(td/'StreamingSourcesOracle.java')],[java,'-Xmx256m','-cp',cp,'StreamingSourcesOracle',str(fixtures),str(ev),str(td/'source-cases.json'),str(td/'sources-main.json')]]
records=[]
for i,cmd in enumerate(cmds):
 with (ev/f'sources-command-{i}.log').open('w') as f: p=subprocess.run(cmd,cwd=root,stdout=f,stderr=subprocess.STDOUT)
 records.append(dict(argv=cmd,cwd=str(root),exitCode=p.returncode));(ev/'sources-commands.json').write_text(json.dumps(records,indent=2)+'\n');p.check_returncode()
