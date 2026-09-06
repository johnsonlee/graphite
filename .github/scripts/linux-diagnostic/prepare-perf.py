"""Fixed ephemeral-runner perf permission preparation. Never a sampling-mode fallback."""
from pathlib import Path
import datetime,json,os,platform,resource,subprocess,time,traceback

OUTPUT=Path('diagnostic-output/perf-permissions')
COMMAND=['sudo','-n','sysctl','-w','kernel.perf_event_paranoid=1','kernel.kptr_restrict=0']
KEYS=['perf_event_paranoid','kptr_restrict','perf_event_mlock_kb','perf_event_max_stack','perf_event_max_sample_rate']

def snapshot():
 values={}
 for key in KEYS:
  p=Path('/proc/sys/kernel')/key
  try:values[key]={'path':str(p),'value':p.read_text().strip()}
  except OSError as e:values[key]={'path':str(p),'readError':str(e)}
 # Only selected capability/sandbox fields, never the environment or process arguments.
 status={}
 try:
  for line in Path('/proc/self/status').read_text().splitlines():
   key,_,value=line.partition(':')
   if key in {'CapEff','CapPrm','NoNewPrivs','Seccomp','Seccomp_filters','Cpus_allowed_list'}:status[key]=value.strip()
 except OSError as e:status['readError']=str(e)
 return {'sysctls':values,'processStatus':status,'memlockLimitsBytes':list(resource.getrlimit(resource.RLIMIT_MEMLOCK)),'cpuCount':os.cpu_count(),'cpuAffinity':sorted(os.sched_getaffinity(0)) if hasattr(os,'sched_getaffinity') else None}

def main():
 OUTPUT.mkdir(parents=True,exist_ok=False)
 receipt={'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'diagnosticOnly':True,'accepted':False,'command':COMMAND,'commandExitCode':None,'passed':False,'before':None,'after':None,'scope':'Only the ephemeral runner of codex/attempt146-linux-profile; no persistent sysctl config file is written.'}
 start=time.monotonic();exitcode=1
 try:
  if not (platform.system()=='Linux' and os.getenv('GITHUB_ACTIONS')=='true' and os.getenv('GITHUB_REPOSITORY')=='johnsonlee/graphite' and os.getenv('GITHUB_REF')=='refs/heads/codex/attempt146-linux-profile'):
   raise RuntimeError('Perf preparation requires the exact authorized Linux Actions diagnostic branch')
  receipt['before']=snapshot()
  (OUTPUT/'before.json').write_text(json.dumps(receipt['before'],indent=2)+'\n')
  (OUTPUT/'command.json').write_text(json.dumps(COMMAND,indent=2)+'\n')
  with (OUTPUT/'command.log').open('w') as log:
   proc=subprocess.run(COMMAND,stdout=log,stderr=subprocess.STDOUT,timeout=30)
  receipt['commandExitCode']=proc.returncode
  if proc.returncode:raise RuntimeError('Fixed perf permission command failed with exit '+str(proc.returncode))
  receipt['after']=snapshot()
  for key,expected in [('perf_event_paranoid','1'),('kptr_restrict','0')]:
   if receipt['after']['sysctls'][key].get('value')!=expected:raise RuntimeError('Readback differs for '+key)
  receipt['passed']=True;exitcode=0
 except BaseException as error:
  receipt['error']=repr(error);receipt['traceback']=traceback.format_exc()
 finally:
  if receipt['before'] is not None and receipt['after'] is None:receipt['after']=snapshot()
  if receipt['after'] is not None:(OUTPUT/'after.json').write_text(json.dumps(receipt['after'],indent=2)+'\n')
  receipt['elapsedSeconds']=time.monotonic()-start;receipt['scriptExitCode']=exitcode
  receipt['completedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
  (OUTPUT/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
  print(json.dumps({'perfPreparationPassed':receipt['passed'],'commandExitCode':receipt['commandExitCode'],'scriptExitCode':exitcode,'receipt':str(OUTPUT/'receipt.json')}))
 return exitcode

if __name__=='__main__':raise SystemExit(main())
