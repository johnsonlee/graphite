import pathlib,subprocess,os,json,datetime
p=pathlib.Path(__file__).resolve().parent
env=os.environ.copy()
keys=['GRAPHITE_NATIVE_CPU_PROFILE','GRAPHITE_PROFILE','GOGC','GODEBUG','GOMEMLIMIT','GOMAXPROCS']
removed={k:env.pop(k,None) for k in keys}
command=['python3',str(p/'replay-http.py'),'--binary',str(p/'graphite-server'),'--out',str(p/'http'),'--source-identity',str(p/'source-identity.json'),'--port','18863']
record={'command':command,'removedEnvironment':removed,'startedUTC':datetime.datetime.now(datetime.timezone.utc).isoformat()}
(p/'replay-command.json').write_text(json.dumps(record,indent=2)+'\n')
with (p/'replay.log').open('w') as log:r=subprocess.run(command,stdout=log,stderr=log,env=env)
record['exitCode']=r.returncode;record['finishedUTC']=datetime.datetime.now(datetime.timezone.utc).isoformat();(p/'replay-command.json').write_text(json.dumps(record,indent=2)+'\n');print('runner terminal',r.returncode)
raise SystemExit(r.returncode)
