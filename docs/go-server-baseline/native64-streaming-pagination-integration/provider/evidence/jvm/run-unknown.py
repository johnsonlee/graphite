import pathlib,subprocess,json
root=pathlib.Path(__file__).resolve().parents[2];ev=root/'streaming-pagination-review/jvm';td=root/'graphite-server/internal/query/testdata/streaming-pagination';java='/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home/bin/java';jar='/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar'
specs=[dict(name=name,query='MATCH (n:AbsentType) WHERE true RETURN 1 AS x ORDER BY x '+suffix,params=params) for name,suffix,params in [('skip-expression',"SKIP substring('x','bad') LIMIT 1",{}),('skip-parameter-negative','SKIP $s LIMIT 1',{'s':-1}),('skip-expression-negative','SKIP -1 LIMIT 1',{}),('skip-string',"SKIP 'bad' LIMIT 1",{}),('skip-parameter-valid','SKIP $s LIMIT 1',{'s':1}),('limit-capacity','SKIP 0 LIMIT 2147483647',{})]]
(td/'unknown-cases.json').write_text(json.dumps(specs,indent=2)+'\n');records=[]
for fixture in ['clean','bad-matched']:
 cmd=[java,'-Xmx256m','-cp',str(ev/'classes')+':'+jar,'StreamingPaginationOracle',str(ev/'fixtures'/fixture),str(td/'unknown-cases.json'),str(td/(fixture+'-unknown-main.json')),'MAPPED']
 with (ev/(fixture+'-unknown.log')).open('w') as f:r=subprocess.run(cmd,cwd=root,stdout=f,stderr=subprocess.STDOUT)
 records.append(dict(argv=cmd,cwd=str(root),exitCode=r.returncode));(ev/'unknown-commands.json').write_text(json.dumps(records,indent=2)+'\n');r.check_returncode()
