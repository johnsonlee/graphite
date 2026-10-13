import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {joinedFixture} from './native-pressure-join-test-fixture.mjs';
import {compareNativeLoading,loadingDeclaration,writeNativeLoading} from './benchmark-native-loading.mjs';
import {comparePressure,requestStatistics,isolatedStatistics} from './benchmark-multigraph-pressure.mjs';
import {loadingResources} from './benchmark-loading.mjs';
import {aggregateReports} from './benchmark-gate.mjs';
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const put=(p,v)=>{fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,JSON.stringify(v));};
function sample(start,end,ticks=100,pages=256,pid=2002) {
 const tail=Array(22).fill('0');tail[0]='S';for(const [i,v] of [[11,ticks],[12,0],[19,3000],[21,pages]])tail[i]=String(v);
 return {backend:'linux-proc',pid,startTicks:3000,ticksPerSecond:100,pageSize:4096,userTicks:ticks,systemTicks:0,
 cpuSeconds:ticks/100,rssBytes:pages*4096,readStartNs:start,readEndNs:end,raw:`${pid} (native) ${tail.join(' ')}`};
}
function nativeFixture(t,options={}) {
 return joinedFixture(t,{alias:false,
 prepare(f) {
  f.plan.loadingMeasurement=structuredClone(loadingDeclaration);f.plan.limits={requestSeconds:240};
  f.plan.cases=Array.from({length:39},(_,i)=>({id:`case${i}`,targetGraphIds:['g0','g63']}));
  for(const [key,arm] of Object.entries(f.plan.arms)) {
   arm.revision=key==='C'?'4f2ccf33b969e684972e56b5e810034e6e67c1b3':key.toLowerCase().repeat(40);
   arm.readiness={expected:{count:64,graphs:arm.graphs.map(g=>({...g,nodeCount:2}))}};
  }
  options.prepare?.(f);
 },
 changeCell(r,i,f) {
  const root=path.join(f.root,r.cell.id),pid=2002+i,group=1001+i;
  const loading={schema:'graphite.native64-loading.cell.v1',graphCount:64,queriesIssuedDuringLoading:0,
   startNs:1e9,readyNs:2e9,initialSample:sample(1100000000,1100000010,25,256,pid),
   cpuAfterReady:sample(2000000010,2000000020,100,256,pid),
   readyWatermark:{pid,startTicks:3000,readStartNs:2000000030,readEndNs:2000000040,peakRssBytes:1048576,
    raw:`Pid:\t${pid}\nVmHWM:\t1024 kB\n`,identityAfter:sample(2000000050,2000000060,101,256,pid)}};
  const samples=[loading.initialSample,sample(1500000000,1500000010,100,256,pid),sample(4e9,4e9+10,500,900000,pid)];
  loading.resources=loadingResources(loading,samples);r.loading=loading;r.readyNs=2000000100;
  r.cleanup={group,after:[],errors:[],signals:[{signal:'SIGTERM'}],exit:143};
  const body={...structuredClone(r.arm.readiness.expected),data:path.join(root,'data')};
  for(const g of body.graphs)g.loadedAt='2026-10-11T00:00:00Z';put(path.join(root,'readiness.body'),body);
  fs.writeFileSync(path.join(root,'resources.jsonl'),samples.map(JSON.stringify).join('\n'));
  fs.writeFileSync(path.join(root,'time-v.log'),'Maximum resident set size (kbytes): 5000000\n');
  r.lifecycle={peakRssBytes:5000000*1024,rawSha256:sha(path.join(root,'time-v.log'))};
  fs.writeFileSync(path.join(root,'server.pid'),String(pid));put(path.join(root,'launch-owner.json'),{group,runnerPid:900});
  put(path.join(root,'command.json'),['/usr/bin/time','-v','-o',path.join(root,'time-v.log'),'/python','/scripts/multigraph_pressure.py','_exec-server','--pid-file',path.join(root,'server.pid'),'--',
   ...r.arm.serverArgv,'--data',path.join(root,'data'),'--port',String(r.cell.port),'--load-mode','MAPPED','--max-concurrent-cypher','4','--cypher-max-timeout-ms','240000','--metrics',
   ...r.arm.graphs.flatMap(g=>['--graph',`${g.id}:${g.path}`])]);
  for(const [name,repeats] of [['oracle',1],['warmup',2],['pressure',20],['isolated',40]]) {
   const latency=r.cell.arm==='B'?80:100,caseMajor=name==='isolated';
   const requests=Array.from({length:39*repeats},(_,sequence)=>({sequence,caseId:f.plan.cases[caseMajor?Math.floor(sequence/40):sequence%39].id,status:'PASS',httpStatus:200,completeBody:true,deadlineExpired:false,
    startNs:3e9+sequence*1000,wireCompleteNs:3e9+sequence*1000+latency-1,validationCompleteNs:3e9+sequence*1000+latency,latencyNs:latency}));
   const stage={status:'PASS',allWorkersStopped:true,unissued:[],journalErrors:[],concurrency:caseMajor?1:4,requests,wallNs:requests.at(-1).validationCompleteNs-requests[0].startNs,
    cpuStart:sample(2900000000,2900000010,110,256,pid),resources:{cpuLowerBoundSeconds:1,cpuUpperBoundSeconds:1.01,rss:{lowerBoundBytes:1000,upperBoundBytes:1010}}};
   if(name==='pressure')stage.caseStatistics=requestStatistics(stage,f.plan.cases);
   if(name==='isolated')stage.caseStatistics=isolatedStatistics(stage,f.plan,r.stages.pressure.caseStatistics);
   r.stages[name]=stage;
  }
  options.changeCell?.(r,i,f);
 },
 changeAudit(a,i,f) {
  const root=path.join(f.root,f.plan.cells[i].id),r=JSON.parse(fs.readFileSync(path.join(root,'result.json')));
  a.completeBodies=39*63;a.loading={schema:'graphite.native64-loading.audit.v1',status:'PASS_RAW_NATIVE64_LOADING_AUDIT',engine:'native',operation:'loading',graphCount:64,
   queriesIssuedDuringLoading:0,resources:r.loading.resources,performanceAcceptance:false,otherOperationsEligible:false,
   rawPins:Object.fromEntries(['command.json','launch-owner.json','server.pid','readiness.body','resources.jsonl','time-v.log'].map(n=>[n,sha(path.join(root,n))]))};
 },changeReceipt:options.changeReceipt,changePhase:options.changePhase});
}
test('same six Native39 processes give independent ready bounds and untouched query verdict',t=>{
 const f=nativeFixture(t),loading=compareNativeLoading(f.file,f.root,f.receiptFile),query=comparePressure(f.file,f.root,f.receiptFile);
 assert.equal(loading.passed,true);assert.equal(query.passed,true);assert.equal(loading.comparisons.length,4);
 assert.equal(loading.comparisons[2].baseWallMs,1000);assert.deepEqual(loading.comparisons[2].rss.base,[1048576,1048576]);
 assert.deepEqual(loading.comparisons[2].cpu.base,[.98,1.02]);
});
test('later query RSS and latency failures do not contaminate ready loading or hide its report',t=>{
 const f=nativeFixture(t,{changeCell(r){if(r.cell.arm==='B')r.stages.pressure.resources.rss.upperBoundBytes=999999999;}});
 assert.equal(comparePressure(f.file,f.root,f.receiptFile).passed,false);
 assert.equal(compareNativeLoading(f.file,f.root,f.receiptFile).passed,true);
 const query=comparePressure(f.file,f.root,f.receiptFile),load=writeNativeLoading(f.file,f.root,path.join(f.root,'multigraph-native-loading'),f.receiptFile);
 query.evidence={plan:'plan.json',directory:'.',comparabilityReceipt:path.relative(f.root,f.receiptFile)};
 // Aggregation expects an artifact-contained subdirectory, as the real workflow uses.
 assert.equal(load.passed,true);assert.equal(query.passed,false);
});
test('loading overrun cannot borrow query gains or the historical time/CPU allowance',t=>{
 const f=nativeFixture(t,{changeCell(r){if(r.cell.arm==='B') {r.loading.startNs-=1e9;r.loading.resources.wallMs+=1000;}}});
 assert.equal(comparePressure(f.file,f.root,f.receiptFile).passed,true);
 const loading=compareNativeLoading(f.file,f.root,f.receiptFile);assert.equal(loading.passed,false);assert.equal(loading.comparisons[2].deltaWallMs,1000);
});
test('pending core retains loading numbers but cannot pass CLI, missing/failed core cannot authorize',t=>{
 const f=nativeFixture(t),pending=compareNativeLoading(f.file,f.root);
 assert.equal(pending.measuredConstraintsPassed,true);assert.equal(pending.passed,false);assert.equal(pending.status,'MEASURED_CORE_COMPARABILITY_PENDING');
 const cli=spawnSync(process.execPath,[fs.realpathSync(new URL('./benchmark-native-loading.mjs',import.meta.url)),f.file,f.root,path.join(f.root,'pending')],{encoding:'utf8'});
 assert.equal(cli.status,1);assert.equal(JSON.parse(fs.readFileSync(path.join(f.root,'pending-status.json'))).passed,false);
 const noJoin=nativeFixture(t);delete noJoin.plan.pendingComparabilityAuthority;delete noJoin.plan.comparabilityPending;put(noJoin.file,noJoin.plan);
 assert.throws(()=>compareNativeLoading(noJoin.file,noJoin.root),/original pending producer and explicit core join/);
 const bad=nativeFixture(t,{changePhase:(p,i)=>{if(i===138)p.status='FAIL';}});
 assert.throws(()=>compareNativeLoading(bad.file,bad.root,bad.receiptFile),/Every recorded core phase/);
});
test('old or wrong declaration and wrong PID, VmHWM or early requests fail closed',t=>{
 for(const options of [
  {prepare:f=>{delete f.plan.loadingMeasurement;}},
  {prepare:f=>{f.plan.loadingMeasurement.engine='jvm';}},
  {changeCell:r=>{r.loading.initialSample.pid++;}},
  {changeCell:r=>{r.loading.readyWatermark.raw+='VmHWM:\t1024 kB\n';}},
  {changeCell:r=>{r.loading.readyWatermark.identityAfter.startTicks++;}},
  {changeCell:r=>{r.stages.oracle.requests[38].startNs=1900000000;}},
  {changeCell:r=>{r.stages.warmup.requests[77].startNs=2000000055;}},
  {changeCell:r=>{r.stages.pressure.requests[779].startNs=2000000055;}},
 ]) {const f=nativeFixture(t,options);assert.throws(()=>compareNativeLoading(f.file,f.root,f.receiptFile));}
});
test('aggregate dispatch requires Native loading separately from JVM loading and query',t=>{
 const f=nativeFixture(t),directory=path.join(f.root,'artifact');fs.mkdirSync(directory);
 // Portable report may reference the original measurement beneath its artifact root.
 const reportRoot=path.dirname(f.root),prefix=path.join(reportRoot,path.basename(f.root)+'-report','multigraph-native-loading');
 const outer=path.dirname(prefix);fs.mkdirSync(outer,{recursive:true});t.after(()=>fs.rmSync(outer,{recursive:true,force:true}));
 fs.cpSync(f.root,path.join(outer,'cells'),{recursive:true});
 const value=writeNativeLoading(path.join(outer,'cells/plan.json'),path.join(outer,'cells'),prefix,path.join(outer,'cells/join/comparability.json'));
 assert.equal(value.passed,true);
 const result=aggregateReports(outer,{baseSha:'a'.repeat(40),candidateSha:'b'.repeat(40)});
 assert.equal(result.operationEvidence.find(x=>x.name==='native-loading').status,'PASS');
 assert.equal(result.operationEvidence.find(x=>x.name==='loading').status,'UNAVAILABLE');
 assert.equal(result.operationEvidence.find(x=>x.name==='native-query').status,'UNAVAILABLE');
 const wrong=structuredClone(value);wrong.engine='jvm';put(prefix+'-status.json',wrong);
 assert.equal(aggregateReports(outer,{baseSha:'a'.repeat(40),candidateSha:'b'.repeat(40)}).operationEvidence.find(x=>x.name==='native-loading').status,'UNAVAILABLE');
});

test('lower exit RSS stays separately bound and leaves Native ready comparisons unchanged',t=>{
 const before=nativeFixture(t),f=nativeFixture(t,{changeCell(r,i,f) {
  const file=path.join(f.root,r.cell.id,'time-v.log');
  fs.writeFileSync(file,'Maximum resident set size (kbytes): 988\n');
  r.lifecycle={peakRssBytes:988*1024,rawSha256:sha(file)};
 }});
 const result=compareNativeLoading(f.file,f.root,f.receiptFile);
 assert.equal(result.passed,true);
 assert.deepEqual(result.comparisons,compareNativeLoading(before.file,before.root,before.receiptFile).comparisons);
 for(const kind of ['zero','forged','hash','duplicate']) {
  const bad=nativeFixture(t,{changeCell(r,i,f) {
   const file=path.join(f.root,r.cell.id,'time-v.log');
   if(kind==='zero') {fs.writeFileSync(file,'Maximum resident set size (kbytes): 0\n');r.lifecycle.peakRssBytes=0;r.lifecycle.rawSha256=sha(file);}
   if(kind==='forged')r.lifecycle.peakRssBytes+=1024;
   if(kind==='hash')r.lifecycle.rawSha256='0'.repeat(64);
   if(kind==='duplicate') {fs.appendFileSync(file,'Maximum resident set size (kbytes): 5000000\n');r.lifecycle.rawSha256=sha(file);}
  }});
  assert.throws(()=>compareNativeLoading(bad.file,bad.root,bad.receiptFile),/Positive original lifetime RSS/,kind);
 }
});
