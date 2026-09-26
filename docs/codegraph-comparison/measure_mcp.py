import subprocess,json,time,os,selectors,statistics,re
from pathlib import Path
import argparse
parser=argparse.ArgumentParser()
parser.add_argument('corpus',type=Path,help='Commons Lang directory containing source/ and graph/')
args=parser.parse_args()
root=args.corpus.resolve()
class Client:
 def __init__(self,name,cmd):
  self.log=(root/(name+'-mcp.log')).open('w');self.p=subprocess.Popen(cmd,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=self.log,text=True,bufsize=1,env={**os.environ,'CODEGRAPH_TELEMETRY':'0','CODEGRAPH_NO_DAEMON':'1'});self.i=0
  self.call('initialize',{'protocolVersion':'2024-11-05','capabilities':{},'clientInfo':{'name':'comparison','version':'1'}})
  self.p.stdin.write(json.dumps({'jsonrpc':'2.0','method':'notifications/initialized'})+'\n');self.p.stdin.flush()
 def call(self,method,params):
  self.i+=1;self.p.stdin.write(json.dumps({'jsonrpc':'2.0','id':self.i,'method':method,'params':params})+'\n');self.p.stdin.flush()
  while True:
   sel=selectors.DefaultSelector();sel.register(self.p.stdout,selectors.EVENT_READ);ready=sel.select(45);sel.close()
   if not ready:raise TimeoutError(method)
   line=self.p.stdout.readline()
   if not line:raise RuntimeError('server exited')
   try:r=json.loads(line)
   except ValueError:continue
   if r.get('id')==self.i:
    if 'error' in r:raise RuntimeError(r)
    return r['result']
 def close(self):
  self.p.terminate()
  try:self.p.wait(timeout=5)
  except subprocess.TimeoutExpired:self.p.kill();self.p.wait()
  self.log.close()
clients={}
try:
 clients['graphite']=Client('graphite',[os.environ.get('GRAPHITE_BIN','graphite'),'mcp','--id','test',str(root/'graph')])
 clients['codegraph']=Client('codegraph',[os.environ.get('CODEGRAPH_BIN','codegraph'),'serve','--mcp','--no-watch','-p',str(root/'source')])
 params={'graphite':{'name':'cypher','arguments':{'graph_id':'test','query':"MATCH (c:CallSiteNode) WHERE c.callee_class = 'org.apache.commons.lang3.StringUtils' AND c.callee_name = 'isBlank' RETURN DISTINCT c.caller_signature AS caller"}},'codegraph':{'name':'codegraph_callers','arguments':{'symbol':'isBlank','file':'org/apache/commons/lang3/StringUtils.java','limit':100}}}
 results={};samples={x:[] for x in clients}
 for i in range(35):
  for name in (list(clients) if i%2 else list(reversed(clients))):
   t=time.perf_counter_ns();r=clients[name].call('tools/call',params[name]);ms=(time.perf_counter_ns()-t)/1e6
   assert not r.get('isError'),r
   payload=r['content'][0]['text']
   expected={'defaultIfBlank','getIfBlank','isAnyBlank','isNotBlank','notBlank','createBigDecimal','createNumber','containsAllWords','wrap'}
   if name=='graphite':
    rows=json.loads(payload)['rows']
    actual={x['caller'].split('(')[0].split('.')[-1] for x in rows}
    assert len(rows)==9 and actual==expected, payload
   else:
    actual=set(re.findall(r'^- (\w+) \(method\)',payload,re.M))
    assert '(9 found)' in payload and actual==expected, payload
   results[name]=r
   if i>=5:samples[name].append(ms)
 rss={n:int(subprocess.check_output(['ps','-o','rss=','-p',str(c.p.pid)],text=True).strip()) for n,c in clients.items()}
 out={'rss_kib_after_queries':rss,'scope':'persistent MCP stdio request/response; five warmups; thirty alternating samples; no startup timing','params':params,'results':results,'samples_ms':samples,'summary':{n:{'median_ms':statistics.median(t),'p95_ms':sorted(t)[28]} for n,t in samples.items()}}
 (root/'mcp-comparison.json').write_text(json.dumps(out,indent=2)+'\n');print(json.dumps(out['summary']));
finally:
 for c in clients.values():c.close()
