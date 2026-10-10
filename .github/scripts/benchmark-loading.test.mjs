import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import {compareLoading,loadingResources} from './benchmark-loading.mjs';
import {aggregateReports} from './benchmark-gate.mjs';
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const write=(p,v)=>{fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,JSON.stringify(v));};
const model='complete-core-topology-index-with-source-corrections-and-additive-declarations-v1';
const scope={
 boundary:'Immediately before wrapper spawn through complete /api/graphs consumption and exact64 validation',
 readiness:'All64 MAPPED registrations and saved counts; includes required startup topology work',
 cpu:'Process-zero cumulative user+system CPU; in-boundary sample lower, first post-ready sample upper, with two ticks uncertainty',
 rss:'In-boundary sampled peak lower; first post-ready Linux VmHWM upper; GNU time lifetime RSS is only corroboration',
 cache:'Matched full closed-input reads before every process; uncontrolled OS cache; no disk-cold claim',
 deferredWork:'Registry-ready only; deferred first-query work is not counted as already paid',
 aggregation:'Fixed CABBAC and two directions; no pooling, retry selection, quiet-host or saturation claim',
};
const flags={completeSemanticEquivalence:false,strictEquivalence:false,sourceToDeclarationCompletenessClaim:false,performanceAcceptance:false,otherOperationsEligible:false};
function sample(start,end,ticks=100,pages=256,pid=2002) {
 const tail=Array(22).fill('0');tail[0]='S';for(const [i,v] of [[11,ticks],[12,0],[19,3000],[21,pages]])tail[i]=String(v);
 return {backend:'linux-proc',pid,startTicks:3000,ticksPerSecond:100,pageSize:4096,userTicks:ticks,systemTicks:0,
   cpuSeconds:ticks/100,rssBytes:pages*4096,readStartNs:start,readEndNs:end,raw:`${pid} (java worker) ${tail.join(' ')}`};
}
function raw(pid=2002) {
 const record={startNs:1000000000,readyNs:2000000000,cpuAfterReady:sample(2000000010,2000000020,100,256,pid),
   readyWatermark:{pid,startTicks:3000,readStartNs:2000000030,readEndNs:2000000040,peakRssBytes:1048576,
    raw:`Name:\tjava\nPid:\t${pid}\nVmHWM:\t1024 kB\n`,identityAfter:sample(2000000050,2000000060,102,256,pid)}};
 const samples=[sample(1100000000,1100000010,25,256,pid),sample(1500000000,1500000010,100,256,pid),sample(1999999999,2000000001,101,256,pid)];
 return {record,samples};
}
function fixture(t) {
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'jvm-loading-contract-'));t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
 const directory=path.join(root,'loading'),file=path.join(directory,'plan.json'),cells=path.join(directory,'cells');
 const graphs=Array.from({length:64},(_,i)=>({id:`g${i}`,path:`/saved/g${i}`}));
 const arms=Object.fromEntries([...'CAB'].map(name=>[name,{revision:name==='C'?'4f2ccf33b969e684972e56b5e810034e6e67c1b3':name.toLowerCase().repeat(40),
   serverJar:{path:`/sealed/${name}/graphite.jar`,sha256:name.toLowerCase().repeat(64)},
   serverArgv:['/jdk/java','-Xmx4g','-XX:ActiveProcessorCount=4','-cp',`/sealed/${name}/graphite.jar`,'io.johnsonlee.graphite.cli.MainKt','serve'],
   graphs,readiness:{path:'/api/graphs',expected:{graphs:graphs.map(g=>({id:g.id})),count:64}}}]));
 const plan={schema:'graphite.jvm64-loading.plan.v1',engine:'jvm',operation:'loading',graphCount:64,queriesIssued:0,arms,
   cells:[...'CABBAC'].map((arm,i)=>({id:`0${i+1}-${arm}`,arm,port:22960+i})),comparisonPairs:{parent:[[1,2],[4,3]],acceptedBaseline:[[0,2],[5,3]]},
   maxHeapBytes:4*1024**3,activeProcessorCount:4,python:'/python',pins:{},scope,comparisonModel:model,producers:{path:'/packet',sha256:'f'.repeat(64)},...flags};
 write(file,plan);
 const execution={schema:'graphite.jvm64-loading.execution.v1',status:'PASS_ALL_SIX_ZERO_QUERY_READY64',planSha256:hash(file),cells:[],unissued:[],errors:[],...flags};
 const audit={schema:'graphite.jvm64-loading.audit.v1',status:'PASS_RAW_ZERO_QUERY_LOADING_AUDIT',planSha256:hash(file),scope:plan.scope,comparisonModel:model,correctedComparability:plan.producers,rows:[],...flags};
 const records=[];
 for(const [i,cell] of plan.cells.entries()) {
   const arm=arms[cell.arm],dir=path.join(cells,cell.id),original=`/original/${cell.id}`,pid=2002+i*10,group=pid-1;
   const {record:r,samples}=raw(pid);records.push(r);
   Object.assign(r,{schema:'graphite.jvm64-loading.cell.v1',status:'PASS_ZERO_QUERY_READY64',engine:'jvm',operation:'loading',graphCount:64,queriesIssued:0,
     cell,planSha256:hash(file),errors:[],cleanup:{group,exit:143,after:[],errors:[],signals:[{signal:'SIGTERM'}]},...flags});
   const command=['/usr/bin/time','-v','-o',`${original}/time-v.log`,plan.python,'/scripts/multigraph_pressure.py','_exec-server','--pid-file',`${original}/server.pid`,'--',
     ...arm.serverArgv,'--data',`${original}/data`,'--port',String(cell.port),'--load-mode','MAPPED','--max-concurrent-cypher','4','--cypher-max-timeout-ms','240000','--metrics',
     ...graphs.flatMap(g=>['--graph',`${g.id}:${g.path}`])];
   write(path.join(dir,'command.json'),command);write(path.join(dir,'owner.json'),{runnerPid:900,group});
   write(path.join(dir,'process-identity.json'),{group,pid,startTicks:3000,initialSample:samples[0]});
   fs.writeFileSync(path.join(dir,'server.pid'),String(pid));
   write(path.join(dir,'readiness.body'),{data:`${original}/data`,count:64,graphs:graphs.map(g=>({id:g.id,loadedAt:'2026-10-10T00:00:00Z'}))});
   fs.writeFileSync(path.join(dir,'resources.jsonl'),samples.map(JSON.stringify).join('\n')+'\n');
   fs.writeFileSync(path.join(dir,'time-v.log'),'User time (seconds): 2\nSystem time (seconds): 0.5\nElapsed (wall clock) time (h:mm:ss or m:ss): 0:03.00\nMaximum resident set size (kbytes): 4096\n');
   r.lifecycle={peakRssBytes:4194304,rawSha256:hash(path.join(dir,'time-v.log'))};r.resources=loadingResources(r,samples);
   for(const name of ['stdout.log','stderr.log'])fs.writeFileSync(path.join(dir,name),'');
   for(const name of ['identities-before.json','identities-after.json'])write(path.join(dir,name),{pins:plan.pins});
   write(path.join(dir,'result.json'),r);execution.cells.push({cell,status:r.status,resultSha256:hash(path.join(dir,'result.json'))});
   const names=['result.json','command.json','owner.json','process-identity.json','server.pid','readiness.body','resources.jsonl','time-v.log','stdout.log','stderr.log','identities-before.json','identities-after.json'];
   audit.rows.push({cell,revision:arm.revision,serverJar:arm.serverJar,resources:r.resources,rawPins:Object.fromEntries(names.map(n=>[`${cell.id}/${n}`,hash(path.join(dir,n))]))});
 }
 function seal() {write(path.join(cells,'execution.json'),execution);audit.executionSha256=hash(path.join(cells,'execution.json'));write(path.join(directory,'audit.json'),audit);}
 function reseal(i) {const dir=path.join(cells,plan.cells[i].id);write(path.join(dir,'result.json'),records[i]);execution.cells[i].resultSha256=hash(path.join(dir,'result.json'));for(const name of Object.keys(audit.rows[i].rawPins))audit.rows[i].rawPins[name]=hash(path.join(cells,name));seal();}
 seal();return {root,directory,file,plan,cells,audit,execution,records,seal,reseal};
}
test('raw CPU includes early startup, excludes straddling reads and does not use lifetime RSS',()=>{
 const {record,samples}=raw();record.lifecycle={peakRssBytes:999999999};const r=loadingResources(record,samples);
 assert.deepEqual(r.cpuBoundsSeconds,[.98,1.02]);assert.deepEqual(r.rssBoundsBytes,[1048576,1048576]);
 assert.equal(r.insideSamples,2);assert.equal(r.excludedSamples,1);assert.equal(r.cpuQuantizationSeconds,.02);
});
test('forged VmHWM, duplicate fields, PID reuse, malformed units and nonfinite counters reject',()=>{
 for(const change of ['forged','duplicate','malformedDuplicate','pid','reuse','units','huge','counter','boundary']) {
  const {record:r,samples:s}=raw(),h=r.readyWatermark;
  if(change==='forged')h.peakRssBytes+=1024;
  if(change==='duplicate')h.raw+='VmHWM:\t1024 kB\n';
  if(change==='malformedDuplicate')h.raw+='VmHWM:\tbroken\n';
  if(change==='pid')h.raw=h.raw.replace('2002','2003');
  if(change==='reuse')h.identityAfter.startTicks++;
  if(change==='units')h.raw=h.raw.replace('kB','MB');
  if(change==='huge'){h.raw=h.raw.replace('1024','9'.repeat(400));h.peakRssBytes=Infinity;}
  if(change==='counter')s[1].cpuSeconds=.01;
  if(change==='boundary')r.cpuAfterReady.readStartNs=1999999999;
  assert.throws(()=>loadingResources(r,s),undefined,change);
 }
});
test('six zero-query loads have two absolute resource comparisons and keep semantic claims false',t=>{
 const f=fixture(t),r=compareLoading(f.file,f.directory);assert.equal(r.passed,true);assert.equal(r.comparisons.length,4);
 assert.equal(r.comparisons[2].baseWallMs,1000);assert.deepEqual(r.comparisons[2].cpu.base,[.98,1.02]);
 assert.deepEqual(r.comparisons[2].rss.base,[1048576,1048576]);assert.equal(r.otherOperationsEligible,false);assert.equal(r.completeSemanticEquivalence,false);
});
test('changed ready body, query stage, missing reverse cell and killed cleanup block acceptance',t=>{
 const a=fixture(t),file=path.join(a.cells,'01-C/readiness.body'),body=JSON.parse(fs.readFileSync(file));body.graphs.pop();write(file,body);a.reseal(0);
 assert.throws(()=>compareLoading(a.file,a.directory),/readiness oracle/);
 const b=fixture(t);fs.mkdirSync(path.join(b.cells,'03-B/pressure'));assert.throws(()=>compareLoading(b.file,b.directory),/query stages/);
 const c=fixture(t);c.execution.cells.pop();c.seal();assert.throws(()=>compareLoading(c.file,c.directory),/all actual loading/);
 const d=fixture(t);d.records[2].cleanup.signals.push({signal:'SIGKILL'});d.reseal(2);assert.throws(()=>compareLoading(d.file,d.directory),/owned cleanup/);
});
test('RSS excess and native-only loading allowance cannot silently pass JVM loading',t=>{
 const f=fixture(t),r=f.records[3],dir=path.join(f.cells,'04-B');r.readyWatermark.raw=r.readyWatermark.raw.replace('1024','2048');r.readyWatermark.peakRssBytes=2097152;
 const samples=fs.readFileSync(path.join(dir,'resources.jsonl'),'utf8').trim().split('\n').map(JSON.parse);
 r.resources=loadingResources(r,samples);f.audit.rows[3].resources=r.resources;f.reseal(3);
 const verdict=compareLoading(f.file,f.directory);assert.equal(verdict.passed,false);assert.equal(verdict.comparisons[3].rss.status,'INDETERMINATE');
 assert.match(verdict.limitation,/No Native loading exception/);
});
test('aggregate recomputes loading while construction and JVM query remain unavailable and stale revisions fail',t=>{
 const f=fixture(t),r=compareLoading(f.file,f.directory);r.evidence={plan:path.relative(f.root,f.file),directory:path.relative(f.root,f.directory)};
 write(path.join(f.root,'multigraph-loading-status.json'),r);
 const metadata={baseSha:'a'.repeat(40),candidateSha:'b'.repeat(40),runner:'tiny-contract',runUrl:'https://example.invalid'};
 const a=aggregateReports(f.root,metadata);assert.equal(a.passed,false);assert.equal(a.operationEvidence.find(x=>x.name==='loading').status,'PASS');
 assert.equal(a.operationEvidence.find(x=>x.name==='construction').status,'UNAVAILABLE');assert.equal(a.operationEvidence.find(x=>x.name==='jvm-query').status,'UNAVAILABLE');
 assert.equal(aggregateReports(f.root,{...metadata,baseSha:'d'.repeat(40)}).operationEvidence.find(x=>x.name==='loading').status,'UNAVAILABLE');
 r.evidence.directory='../escape';write(path.join(f.root,'multigraph-loading-status.json'),r);assert.ok(aggregateReports(f.root,metadata).errors.some(x=>x.includes('escaped artifact root')));
});
