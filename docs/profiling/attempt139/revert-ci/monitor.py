from pathlib import Path
import json,subprocess,time,datetime,concurrent.futures
root=Path(__file__).parent; expected='aede4c82f66a925ba9df3fc8588c6e1399c17f61'; runs={'unit':33992947613,'benchmark':33992947567}; previous=None; sequence=0
(root/'monitor-command.json').write_text(json.dumps({'readOnly':True,'head':expected,'runs':runs,'pollIntervalSeconds':55,'actions':['gh run view <id> --json databaseId,headSha,status,conclusion,url,jobs'],'neverRerunOrPush':True},indent=2)+'\n')
def fetch(item):
 side,rid=item; cmd=['gh','run','view',str(rid),'--json','databaseId,headSha,status,conclusion,url,jobs']; result=subprocess.run(cmd,text=True,capture_output=True)
 if result.returncode: raise RuntimeError(f'{side}: {result.stderr.strip()}')
 d=json.loads(result.stdout); assert d['headSha']==expected and d['databaseId']==rid
 return side,d
# The caller just observed both run handles; wait one requested interval before polling.
time.sleep(55)
while True:
 sequence+=1; now=datetime.datetime.now(datetime.timezone.utc).isoformat()
 try:
  with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool: data=dict(pool.map(fetch,runs.items()))
  for side,d in data.items():
   (root/f'current-{side}.json').write_text(json.dumps(d,indent=2)+'\n')
   (root/f'poll-{sequence:03d}-{side}.json').write_text(json.dumps(d,indent=2)+'\n')
  summary={s:{'status':d['status'],'conclusion':d['conclusion'],'jobs':[(j['name'],j['status'],j['conclusion']) for j in d['jobs']]} for s,d in data.items()}
  (root/'latest-poll.json').write_text(json.dumps({'timestamp':now,'sequence':sequence,'head':expected,'summary':summary},indent=2)+'\n')
  if summary!=previous:
   print(json.dumps({'timestamp':now,'sequence':sequence,'runs':{s:{'status':x['status'],'conclusion':x['conclusion'],'active':[j[0] for j in x['jobs'] if j[1]!='completed'],'failed':[j[0] for j in x['jobs'] if j[2]=='failure']} for s,x in summary.items()}}),flush=True);previous=summary
  if all(d['status']=='completed' for d in data.values()):
   for side,d in data.items():(root/f'terminal-{side}.json').write_text(json.dumps(d,indent=2)+'\n')
   (root/'terminal-receipt.json').write_text(json.dumps({'head':expected,'observedAt':now,'status':'complete','runs':{s:{'id':d['databaseId'],'conclusion':d['conclusion'],'url':d['url']} for s,d in data.items()},'readOnly':True,'candidate139RejectionUnchanged':True},indent=2)+'\n'); print('BOTH_RUNS_TERMINAL',flush=True);break
 except Exception as e:
  (root/f'poll-{sequence:03d}-error.txt').write_text(repr(e)+'\n');print(json.dumps({'timestamp':now,'sequence':sequence,'observationError':str(e),'notTerminal':True}),flush=True)
 time.sleep(55)
