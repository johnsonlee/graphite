import {fixture,joinedFixture} from './native-pressure-join-test-fixture.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {comparePressure,pressureProgress,resourceBound,withinLatencyTolerance,withinWallTolerance,coldStatistics,CPU_TOLERANCE_RATIO,RSS_TOLERANCE_RATIO} from './benchmark-multigraph-pressure.mjs';
// Past both tolerances of the fixture's 100ns base: more than 100ms and more than 5%.
const SLOW=150e6;
const digest=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const put=(p,v)=>{fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,JSON.stringify(v));};
test('fixed per-case repeated latency and independent CPU/RSS intervals',t=>{
 const f=fixture(t),r=comparePressure(f.file,f.root);assert.equal(r.passed,true);assert.equal(r.comparisons.length,4);assert.equal(r.comparisons[2].cases[0].deltaP95Ms,-.00002);
 assert.deepEqual(r.latencyTolerance,{absoluteMs:100,ratio:0.05});assert.deepEqual(r.resourceTolerance,{cpuRatio:0.10,rssRatio:0.10});
 // Within 100ms or 5% of the base passes; past both fails, whichever is the larger allowance.
 for(const [candidate,base,expected] of [[150,100,true],[200e6,100e6,true],[200e6+1,100e6,false],[4.2e9+1,4e9,false],[4.2e9,4e9+1,true],[4.15e9,4e9,true],[100,100,true]])
  assert.equal(withinLatencyTolerance(candidate,base),expected,`${candidate} vs ${base}`);
 // A regression inside the tolerance keeps the verdict.
 f.rewrite(3,r=>{r.stages.pressure.requests.forEach(x=>{x.latencyNs=150;x.validationCompleteNs=x.startNs+150;});r.stages.pressure.caseStatistics.slow.p50Ns=150;r.stages.pressure.caseStatistics.slow.p95Ns=150;});
 assert.equal(comparePressure(f.file,f.root).passed,true);
 // CPU and RSS both have 10% (user-approved 2026-10-12); each ratio is explicit at the call.
 assert.equal(resourceBound(100,120,112,115),'INDETERMINATE');assert.equal(resourceBound(100,100,111,111),'FAIL');
 assert.equal(resourceBound(100,100,106,106,0.05),'FAIL');assert.equal(resourceBound(100,100,106,106),'PASS');
 assert.equal(resourceBound(100,100,110,110,CPU_TOLERANCE_RATIO),'PASS');assert.equal(resourceBound(100,100,111,111,CPU_TOLERANCE_RATIO),'FAIL');
 assert.equal(resourceBound(100,100,110,110,RSS_TOLERANCE_RATIO),'PASS');assert.equal(resourceBound(100,100,111,111,RSS_TOLERANCE_RATIO),'FAIL');
 assert.equal(resourceBound(100,120,125,130,RSS_TOLERANCE_RATIO),'INDETERMINATE');
 assert.throws(()=>resourceBound(100,100,100,100,-0.1),/Invalid resource interval/);
 for(const [candidate,base,expected] of [[105,100,true],[105.1,100,false],[1000,1000,true],[0,1000,true]])assert.equal(withinWallTolerance(candidate,base),expected,`wall ${candidate} vs ${base}`);
 f.rewrite(2,r=>{r.stages.pressure.resources.rss.upperBoundBytes=1200;});assert.equal(comparePressure(f.file,f.root).passed,false);
});
test('early feedback reports only completed original pairs and cannot satisfy final acceptance',t=>{
 const f=fixture(t),final=comparePressure(f.file,f.root);
 const five=pressureProgress(f.file,f.root,5);
 assert.deepEqual(five.comparisons,final.comparisons.slice(0,3));
 fs.rmSync(path.join(f.root,'c5'),{recursive:true});
 fs.rmSync(path.join(f.root,'c4'),{recursive:true});
 fs.rmSync(path.join(f.root,'c3'),{recursive:true});
 const three=pressureProgress(f.file,f.root,3);
 assert.deepEqual(three.completedCells,['c0','c1','c2']);
 assert.deepEqual(three.comparisons,[final.comparisons[0],final.comparisons[2]]);
 assert.equal(three.schema,'graphite.multigraph-pressure.progress.v1');
 for(const key of ['passed','performanceAcceptance','queryEvidenceComplete','otherOperationsEligible']) assert.equal(three[key],false);
 assert.throws(()=>comparePressure(f.file,f.root),/ENOENT/);
 for(const count of [0,1,2,4,6,NaN]) assert.throws(()=>pressureProgress(f.file,f.root,count),/completed fixed/);
});
test('early feedback retains regressions and rejects failed or changed audited samples',t=>{
 const f=fixture(t);
 f.rewrite(2,r=>{r.stages.pressure.resources.cpuLowerBoundSeconds=2;r.stages.pressure.resources.cpuUpperBoundSeconds=2;});
 assert.ok(pressureProgress(f.file,f.root,3).comparisons.every(c=>c.cpu.status==='FAIL'));
 f.rewrite(2,r=>{r.stages.pressure.requests[0].completeBody=false;});
 assert.throws(()=>pressureProgress(f.file,f.root,3),/boundary/);
});
test('progress CLI publishes an explicitly incomplete report without requiring future cells',t=>{
 const f=fixture(t),prefix=path.join(f.root,'early');
 for(const id of ['c3','c4','c5']) fs.rmSync(path.join(f.root,id),{recursive:true});
 const command=[fileURLToPath(new URL('./benchmark-multigraph-pressure.mjs',import.meta.url)),f.file,f.root,prefix];
 assert.equal(spawnSync(process.execPath,[...command,'--progress','3']).status,0);
 const result=JSON.parse(fs.readFileSync(prefix+'-status.json'));
 assert.equal(result.status,'PROVISIONAL');assert.equal(result.passed,false);
 assert.match(fs.readFileSync(prefix+'-report.md','utf8'),/cannot establish acceptance/);
 assert.equal(spawnSync(process.execPath,command).status,1);
});
test('one slow candidate cell is INDETERMINATE and reported; both slow cells fail (user-approved 2026-10-14 intervals)',t=>{
 const slow=(f,i)=>f.rewrite(i,r=>{r.stages.pressure.requests.forEach(x=>{x.latencyNs=SLOW;x.validationCompleteNs=x.startNs+SLOW;});r.stages.pressure.caseStatistics.slow.p50Ns=SLOW;r.stages.pressure.caseStatistics.slow.p95Ns=SLOW;r.stages.isolated.requests=[];r.stages.isolated.caseStatistics={};});
 const f=fixture(t);slow(f,3);
 let r=comparePressure(f.file,f.root);assert.equal(r.passed,true);
 assert.equal(r.comparisons[3].cases[0].p95Passed,false);assert.equal(r.comparisons[3].cases[0].p50Passed,false);
 assert.equal(r.p50.slow.status,'INDETERMINATE');assert.equal(r.tails.slow.status,'INDETERMINATE');
 slow(f,2);
 r=comparePressure(f.file,f.root);assert.equal(r.passed,false);assert.equal(r.status,'FAIL');
 assert.equal(r.p50.slow.status,'FAIL');assert.equal(r.tails.slow.status,'FAIL');
});
test('altered evidence, wrong typed boundary, stale quantiles, failures and singleton scope reject',t=>{
 const f=fixture(t);f.rewrite(0,r=>{r.stages.pressure.requests[0].validationCompleteNs++;});assert.throws(()=>comparePressure(f.file,f.root),/boundary/);
 f.rewrite(0,r=>{r.stages.pressure.requests[0].validationCompleteNs--;r.stages.pressure.caseStatistics.slow.p95Ns++;});assert.throws(()=>comparePressure(f.file,f.root),/quantile|statistics/);
 f.plan.cases[0].targetGraphIds=['one'];put(f.file,f.plan);assert.throws(()=>comparePressure(f.file,f.root),/multi-graph/);
});
test('missing required family remains unavailable despite every measured constraint passing',t=>{
 const f=fixture(t);f.plan.coverage.requiredFamilies.push('feature');f.plan.coverage.unavailableFamilies.push('feature');put(f.file,f.plan);
 for(let i=0;i<6;i++) {f.rewrite(i,r=>{r.coverage=f.plan.coverage;r.planSha256=digest(f.file);});const p=path.join(f.root,`c${i}`,'audit.json');const a=JSON.parse(fs.readFileSync(p));a.coverage=f.plan.coverage;a.planSha256=digest(f.file);put(p,a);}
 const r=comparePressure(f.file,f.root);assert.equal(r.measuredConstraintsPassed,true);assert.equal(r.status,'UNAVAILABLE');assert.equal(r.passed,false);
});

test('operation belongs to plan; lifecycle cannot masquerade as query',t=>{
 const f=fixture(t);assert.equal(comparePressure(f.file,f.root).passed,true);
 f.plan.operation='loading';put(f.file,f.plan);assert.throws(()=>comparePressure(f.file,f.root),/Plan schema/);
});


test('pressure CLI through a directory symlink writes the verdict and rejects invalid evidence', t => {
 const f=fixture(t),alias=path.join(f.root,'scripts'),prefix=path.join(f.root,'comparison');
 fs.symlinkSync(fileURLToPath(new URL('.',import.meta.url)),alias,'dir');
 const invoke=()=>spawnSync(process.execPath,[path.join(alias,'benchmark-multigraph-pressure.mjs'),f.file,f.root,prefix],{encoding:'utf8'});
 assert.equal(invoke().status,0);
 const passed=JSON.parse(fs.readFileSync(prefix+'-status.json'));
 assert.equal(passed.passed,true);assert.equal(passed.status,'PASS');
 assert.equal(passed.comparisons.length,4);
 assert.deepEqual(passed.comparisons[2].cases[0],comparePressure(f.file,f.root).comparisons[2].cases[0]);
 assert.match(fs.readFileSync(prefix+'-report.md','utf8'),/Base p95 ms/);
 f.rewrite(0,r=>{r.stages.pressure.requests[0].completeBody=false;});
 assert.equal(invoke().status,1);
 const failed=JSON.parse(fs.readFileSync(prefix+'-status.json'));
 assert.equal(failed.passed,false);assert.equal(failed.status,'FAIL');
 assert.match(failed.errors.join(),/complete request boundary/);
 assert.match(fs.readFileSync(prefix+'-report.md','utf8'),/FAIL/);
});

test('JVM35 retains full projections and raw zero-hit coverage with complete bodies', t=>{
 const f=fixture(t);
 const catalog=JSON.parse(fs.readFileSync(new URL('./fixtures/jvm-multigraph-pressure-cases.json',import.meta.url))).engines.jvm;
 f.plan.engine='jvm'; f.plan.cases=catalog.cases;
 f.plan.coverage={requiredFamilies:catalog.requiredFamilies,coveredFamilies:catalog.coveredFamilies,unavailableFamilies:[]};
 assert.equal(f.plan.cases.length,35);
 assert.equal(f.plan.cases.filter(c=>c.family==='full-projection').length,8);
 assert.equal(f.plan.cases.at(-1).id,'global-wide-four-properties-zero');
 assert.equal(f.plan.cases.at(-1).targetGraphIds.length,64);
 put(f.file,f.plan);
 for(const [i,cell] of f.plan.cells.entries()) {
  f.rewrite(i,r=>{
   r.engine='jvm';r.planSha256=digest(f.file);r.coverage=f.plan.coverage;
   const stage=r.stages.pressure,lat=cell.arm==='B'?80:100;
   stage.requests=Array.from({length:35*20},(_,sequence)=>({sequence,caseId:f.plan.cases[sequence%35].id,
    status:'PASS',httpStatus:200,completeBody:true,deadlineExpired:false,startNs:sequence*1000,
    wireCompleteNs:sequence*1000+lat-1,validationCompleteNs:sequence*1000+lat,latencyNs:lat}));
   stage.wallNs=(35*20-1)*1000+lat;
   stage.caseStatistics=Object.fromEntries(f.plan.cases.map((c,j)=>[c.id,{n:20,
    sampleIds:Array.from({length:20},(_,k)=>k*35+j),p50Ns:lat,p95Ns:lat}]));
   const oracle=r.stages.oracle;
   oracle.requests=Array.from({length:35},(_,sequence)=>({sequence,caseId:f.plan.cases[sequence].id,
    status:'PASS',httpStatus:200,completeBody:true,deadlineExpired:false,startNs:sequence*1000,
    wireCompleteNs:sequence*1000+lat-1,validationCompleteNs:sequence*1000+lat,latencyNs:lat}));
   oracle.wallNs=34*1000+lat;
   const isolated=r.stages.isolated;
   isolated.requests=Array.from({length:35*40},(_,sequence)=>({sequence,caseId:f.plan.cases[Math.floor(sequence/40)].id,
    status:'PASS',httpStatus:200,completeBody:true,deadlineExpired:false,startNs:1e6+sequence*1000,
    wireCompleteNs:1e6+sequence*1000+lat-1,validationCompleteNs:1e6+sequence*1000+lat,latencyNs:lat}));
   isolated.wallNs=(35*40-1)*1000+lat;
   isolated.caseStatistics=Object.fromEntries(f.plan.cases.map((c,j)=>[c.id,{n:40,
    sampleIds:Array.from({length:40},(_,k)=>j*40+k),p50Ns:lat,p95Ns:lat}]));
  });
  const file=path.join(f.root,cell.id,'audit.json'),audit=JSON.parse(fs.readFileSync(file));
  audit.planSha256=digest(f.file);audit.coverage=f.plan.coverage;audit.completeBodies=35*63;put(file,audit);
 }
 const verdict=comparePressure(f.file,f.root);
 assert.equal(verdict.passed,true);
 assert.ok(verdict.comparisons.every(pair=>pair.cases.length===35));
 const complete=f.plan.cells.reduce((sum,cell)=>sum+JSON.parse(fs.readFileSync(path.join(f.root,cell.id,'audit.json'))).completeBodies,0);
 const measured=f.plan.cells.reduce((sum,cell)=>sum+JSON.parse(fs.readFileSync(path.join(f.root,cell.id,'result.json'))).stages.pressure.requests.length,0);
 assert.equal(complete,6*35*63);assert.equal(measured,4200);
 const file=path.join(f.root,'c2','audit.json'),audit=JSON.parse(fs.readFileSync(file));
 for (const oldCount of [26,34]) {
  audit.completeBodies=oldCount*23;put(file,audit);
  assert.throws(()=>comparePressure(f.file,f.root),/raw audit/);
 }
});

// Tiny synthetic report protocol fixtures: never performance observations or
// substitutes for the Python finalizer's full raw-core replay.
test('pending fast measurement remains non-PASS until exact complete core join is supplied',t=>{
 const f=joinedFixture(t),pending=comparePressure(f.file,f.root);
 assert.equal(pending.measuredConstraintsPassed,true);assert.equal(pending.queryEvidenceComplete,true);
 assert.equal(pending.passed,false);assert.equal(pending.status,'MEASURED_CORE_COMPARABILITY_PENDING');
 const joined=comparePressure(f.file,f.root,f.receiptFile);
 assert.equal(joined.passed,true);assert.equal(joined.status,'PASS');
 assert.deepEqual(joined.comparisons,pending.comparisons);assert.equal(joined.otherOperationsEligible,false);
 assert.equal(joined.inputs[f.receiptFile],digest(f.receiptFile));
});

test('distinct parent needs both A and B complete core comparisons',t=>{
 const valid=joinedFixture(t,{alias:false});assert.equal(comparePressure(valid.file,valid.root,valid.receiptFile).passed,true);
 const missing=joinedFixture(t,{alias:false,changeCorrected:c=>{delete c.correctedComparisons.A;}});
 assert.throws(()=>comparePressure(missing.file,missing.root,missing.receiptFile),/Every nonaccepted arm/);
});

test('a flat parent pair needs no production formatter tests and must say so',t=>{
 const flat=a=>{a.formatterKinds={C:'legacy-flat',B:'legacy-flat'};a.productionFormatterTestsApplicable=false;a.productionFormatterTestsVerified=false;};
 const f=joinedFixture(t,{alias:false,changeCoreAudit:(a,arm)=>{if(arm==='A')flat(a);}});
 assert.equal(comparePressure(f.file,f.root,f.receiptFile).passed,true);
 for(const change of [a=>{a.productionFormatterTestsApplicable=false;},a=>{flat(a);a.productionFormatterTestsVerified=true;},
                      a=>{flat(a);a.formatterKinds.C='rank-preserving';},a=>{delete a.formatterKinds;}]) {
  const g=joinedFixture(t,{changeCoreAudit:change});
  assert.throws(()=>comparePressure(g.file,g.root,g.receiptFile),/complete core execution/);
 }
});

test('missing or failed core authority cannot upgrade a completed fast measurement',t=>{
 for(const options of [
  {changeCorrected:c=>{c.missingAuthority=['core B unavailable'];}},
  {changeCoreAudit:a=>{a.status='FAIL';}},
  {changeCoreAudit:a=>{a.productionFormatterTestsVerified=false;}},
  {changeCoreRecord:r=>{r.finalIdentity='FAIL';}},
  {changeCoreRecord:r=>{r.graphs=r.graphs.slice(0,63);}},
  {changePhase:(p,i)=>{if(i===138){p.status='FAIL';p.exit=1;p.errors=['wrong result'];}}},
  {changeCorePlan:p=>{p.graphs.pop();}},
 ]) {
  const f=joinedFixture(t,options);
  assert.throws(()=>comparePressure(f.file,f.root,f.receiptFile),/complete corrected core|complete core execution|core phase succeeded|core graph inventory/);
 }
 const f=joinedFixture(t);fs.unlinkSync(path.join(f.joinRoot,f.metadata.find(x=>x.path.endsWith('core-B-audit.json')).relativePath));
 assert.throws(()=>comparePressure(f.file,f.root,f.receiptFile),/ENOENT/);
});

test('join cannot substitute producer inputs, measured arm, plan, execution or cells',t=>{
 for(const [options,message] of [
  [{changeReceipt:r=>{r.measurementProducer={...r.measurementProducer,sha256:'other'};}},/original measured plan and producer/],
  [{changeCorrected:c=>{c.sourceInputs.sha256='different source';}},/Same producer inputs/],
  [{changeCorrected:c=>{c.arms.B.runtimeManifest.sha256='different binary';}},/Same producer inputs/],
  [{changeReceipt:r=>{r.plan.sha256='other plan';}},/original measured plan/],
  [{changeReceipt:r=>{r.measurementInputsSha256='other inputs';}},/original measured plan/],
  [{changeReceipt:r=>{r.completeSemanticEquivalence=true;}},/Complete scoped core join/],
  [{changeExecution:e=>{e.cells[3].status='FAIL';}},/original pending measurement/],
  [{changeReceipt:r=>{r.cells.pop();}},/all six/],
  [{changeReceipt:r=>{[r.cells[2],r.cells[3]]=[r.cells[3],r.cells[2]];}},/cell order/],
  [{changeReceipt:r=>{r.cells[2].result.path='/another-cell/result.json';}},/cell evidence path/],
 ]) {
  const f=joinedFixture(t,options);assert.throws(()=>comparePressure(f.file,f.root,f.receiptFile),message);
 }
});

test('changed metadata or measured raw result bytes invalidate an otherwise valid join',t=>{
 const f=joinedFixture(t);assert.equal(comparePressure(f.file,f.root,f.receiptFile).passed,true);
 const metadataFile=path.join(f.joinRoot,f.metadata[0].relativePath),old=fs.readFileSync(metadataFile);
 fs.appendFileSync(metadataFile,'\n');assert.throws(()=>comparePressure(f.file,f.root,f.receiptFile),/hash or pin changed/);
 fs.writeFileSync(metadataFile,old);
 f.rewrite(2,r=>{r.stages.pressure.requests[0].completeBody=false;});
 assert.throws(()=>comparePressure(f.file,f.root,f.receiptFile),/hash or pin changed/);
});

test('receipt placement and metadata paths cannot escape their portable artifact',t=>{
 const f=joinedFixture(t),moved=path.join(f.root,'misplaced','comparability.json');put(moved,f.receipt);
 assert.throws(()=>comparePressure(f.file,f.root,moved),/ENOENT/);
 for(const relativePath of ['../outside.json',path.join(f.root,'outside.json')]) {
  const bad=structuredClone(f.receipt);bad.metadata[0].relativePath=relativePath;put(f.receiptFile,bad);
  assert.throws(()=>comparePressure(f.file,f.root,f.receiptFile),/escaped artifact root|Missing portable join metadata/);
 }
});

test('valid complete join cannot mask latency, CPU or RSS regression',t=>{
 for(const metric of ['latency','cpu','rss']) {
  const f=joinedFixture(t,{changeCell:(r,i)=>{
   if(i!==2&&!(metric==='latency'&&i===3))return;const s=r.stages.pressure;
   // Latency intervals need both candidate cells slow to fail; CPU and RSS keep the per-pair PASS requirement.
   if(metric==='latency') {s.requests.forEach(x=>{x.latencyNs=SLOW;x.validationCompleteNs=x.startNs+SLOW;});s.caseStatistics.slow.p50Ns=SLOW;s.caseStatistics.slow.p95Ns=SLOW;r.stages.isolated.requests=[];r.stages.isolated.caseStatistics={};}
   if(metric==='cpu') {s.resources.cpuLowerBoundSeconds=2;s.resources.cpuUpperBoundSeconds=2;}
   if(metric==='rss') {s.resources.rss.lowerBoundBytes=2000;s.resources.rss.upperBoundBytes=2000;}
  }});
  const r=comparePressure(f.file,f.root,f.receiptFile);assert.equal(r.passed,false);assert.equal(r.status,'FAIL');
  const pair=r.comparisons.find(x=>x.kind==='acceptedBaseline'&&x.candidate==='c2');
  if(metric==='latency'){assert.equal(pair.cases[0].p95Passed,false);assert.equal(r.p50.slow.status,'FAIL');assert.equal(r.tails.slow.status,'FAIL');}else assert.equal(pair[metric].status,'FAIL');
 }
});

test('CLI pending and joined verdicts preserve portable relative receipt evidence',t=>{
 const f=joinedFixture(t),script=fileURLToPath(new URL('./benchmark-multigraph-pressure.mjs',import.meta.url));
 const prefix=path.join(f.root,'summary','pressure');
 const invoke=(receipt)=>spawnSync(process.execPath,[script,f.file,f.root,prefix,...(receipt?['--comparability-receipt',receipt]:[])],{encoding:'utf8'});
 assert.equal(invoke().status,1);assert.equal(JSON.parse(fs.readFileSync(prefix+'-status.json')).status,'MEASURED_CORE_COMPARABILITY_PENDING');
 assert.equal(invoke(f.receiptFile).status,0);const value=JSON.parse(fs.readFileSync(prefix+'-status.json'));
 assert.equal(value.passed,true);assert.deepEqual(value.evidence,{plan:'../plan.json',directory:'..',comparabilityReceipt:'../join/comparability.json'});
 const moved=path.join(f.root,'copied-artifact');fs.mkdirSync(moved);
 for(const name of ['plan.json','execution.json','join','c0','c1','c2','c3','c4','c5'])fs.cpSync(path.join(f.root,name),path.join(moved,name),{recursive:true});
 assert.equal(comparePressure(path.join(moved,'plan.json'),moved,path.join(moved,'join','comparability.json')).passed,true);
});

function linuxEnvelope(r, time=1010, sampled=1000) {
 const rss=r.stages.pressure.resources.rss;
 Object.assign(rss,{measurementSemantics:'linux-rss-observed-envelope-v1',lifecyclePeakBytes:time,
   lifecycleSampledPeakBytes:sampled,sampledMinusLifecycleBytes:sampled-time,upperBoundBytes:Math.max(time,sampled)});
}
test('Linux observed RSS envelopes keep the ten percent RSS gate',t=>{
 const f=fixture(t);for(let i=0;i<6;i++)f.rewrite(i,r=>linuxEnvelope(r));
 let result=comparePressure(f.file,f.root);assert.equal(result.passed,true);
 assert.equal(result.comparisons[0].rss.measurementSemantics,'linux-rss-observed-envelope-v1');
 // Exactly 10% above the base's lower bound passes; past it the envelope is INDETERMINATE, then FAIL.
 f.rewrite(2,r=>linuxEnvelope(r,1010,1100));
 result=comparePressure(f.file,f.root);assert.equal(result.passed,true);assert.equal(result.comparisons[0].rss.status,'PASS');
 f.rewrite(2,r=>linuxEnvelope(r,1010,1120));
 result=comparePressure(f.file,f.root);assert.equal(result.passed,false);
 assert.equal(result.comparisons[0].rss.status,'INDETERMINATE');
 f.rewrite(2,r=>{r.stages.pressure.resources.rss.lowerBoundBytes=1112;});
 assert.equal(comparePressure(f.file,f.root).comparisons[0].rss.status,'FAIL');
});
test('Linux RSS envelopes reject mixed policies and altered component arithmetic',t=>{
 const f=fixture(t);f.rewrite(0,r=>linuxEnvelope(r));
 assert.throws(()=>comparePressure(f.file,f.root),/Mixed RSS/);
 for(let i=1;i<6;i++)f.rewrite(i,r=>linuxEnvelope(r));
 f.rewrite(0,r=>{r.stages.pressure.resources.rss.sampledMinusLifecycleBytes++;});
 assert.throws(()=>comparePressure(f.file,f.root),/Invalid Linux RSS/);
 f.rewrite(0,r=>{linuxEnvelope(r);r.stages.pressure.resources.rss.upperBoundBytes--;});
 assert.throws(()=>comparePressure(f.file,f.root),/Invalid Linux RSS/);
});

test('tails: p95 intervals across both base and both candidate cells, cheap cases from the isolated stage',t=>{
 // Fixture: one cheap case ('slow', pressure p50 100ns on C cells, 80ns on B cells); isolated stage carries 40 samples at the same latency.
 const f=fixture(t),r=comparePressure(f.file,f.root);
 assert.equal(r.tails.slow.cheap,true);assert.equal(r.tails.slow.source,'isolated');assert.equal(r.tails.slow.status,'PASS');
 assert.deepEqual(r.tailSummary,{PASS:1,INDETERMINATE:0,FAIL:0});
 assert.equal(r.tailRule.cheapP50Ms,50);assert.equal(r.tailRule.isolated.isolatedPerCase,40);
 const slowIsolated=(i,ns)=>f.rewrite(i,x=>{x.stages.isolated.requests.forEach(q=>{q.latencyNs=ns;q.validationCompleteNs=q.startNs+ns;});x.stages.isolated.caseStatistics.slow.p50Ns=ns;x.stages.isolated.caseStatistics.slow.p95Ns=ns;});
 // One candidate cell slow in isolation: INDETERMINATE, reported but not red.
 slowIsolated(2,300e6);
 let v=comparePressure(f.file,f.root);assert.equal(v.tails.slow.status,'INDETERMINATE');assert.equal(v.passed,true);
 assert.deepEqual(v.tailSummary,{PASS:0,INDETERMINATE:1,FAIL:0});
 // Both candidate cells slow in isolation beyond the tolerance: FAIL, and the gate is red although every pressure p50 passes.
 slowIsolated(3,300e6);
 v=comparePressure(f.file,f.root);assert.equal(v.tails.slow.status,'FAIL');assert.equal(v.passed,false);assert.equal(v.status,'FAIL');
 assert.ok(v.comparisons.every(c=>c.cases.every(k=>k.p50Passed)));
 // The same regression inside the tolerance (100ms absolute) passes.
 slowIsolated(2,90e6);slowIsolated(3,90e6);
 v=comparePressure(f.file,f.root);assert.equal(v.tails.slow.status,'PASS');assert.equal(v.passed,true);
});
test('p50: base/candidate cell intervals decide; the per-pair verdicts are reported',t=>{
 // Fixture p50: C cells 100ns, B cells 80ns. Move one candidate cell's p50 far past every base cell.
 const f=fixture(t);
 // A cell whose pressure p50 reaches the isolated threshold runs no isolated samples for the case.
 const p50=(i,ns)=>f.rewrite(i,x=>{x.stages.pressure.requests.forEach(q=>{q.latencyNs=ns;q.validationCompleteNs=q.startNs+ns;});x.stages.pressure.caseStatistics.slow.p50Ns=ns;x.stages.pressure.caseStatistics.slow.p95Ns=ns;
  if(ns>=100e6){x.stages.isolated.requests=[];x.stages.isolated.caseStatistics={};}});
 p50(2,300e6);
 let v=comparePressure(f.file,f.root);
 assert.equal(v.p50.slow.status,'INDETERMINATE');assert.deepEqual(v.p50Summary,{PASS:0,INDETERMINATE:1,FAIL:0});
 assert.deepEqual(v.p50.slow.candidateP50Ms,[0.00008,300]);assert.equal(v.tailRule.p50.includes('2026-10-14'),true);
 // The per-pair rule still says FAIL for the pairs that meet that cell, and the gate ignores it.
 assert.equal(v.comparisons.filter(c=>c.candidate==='2-B').every(c=>c.cases[0].p50Passed===false),true);
 assert.equal(v.passed,true);
 // Both candidate cells slower than both base cells by more than the tolerance: FAIL.
 p50(3,300e6);
 v=comparePressure(f.file,f.root);assert.equal(v.p50.slow.status,'FAIL');assert.equal(v.passed,false);assert.equal(v.status,'FAIL');
 // Inside the tolerance (100 ms absolute on a 100 ns base): PASS.
 p50(2,100e6);p50(3,100e6);
 v=comparePressure(f.file,f.root);assert.equal(v.p50.slow.status,'PASS');assert.equal(v.passed,true);
});
test('tails: the pressure p95 of a cheap case is only a collision bound; a heavy case keeps the pressure interval',t=>{
 const f=fixture(t);
 // Nearest-rank p95 of 20 samples is the second-slowest, so two samples move.
 const pressureP95=(i,ns)=>f.rewrite(i,x=>{for(const q of x.stages.pressure.requests.slice(-2)){q.latencyNs=ns;q.validationCompleteNs=q.startNs+ns;}x.stages.pressure.caseStatistics.slow.p95Ns=ns;});
 // Candidate pressure p95 at 200ms on a 100ns base: beyond 100ms/5% per pair, but within the collision bound (base + 250ms).
 pressureP95(2,200e6);pressureP95(3,200e6);
 let v=comparePressure(f.file,f.root);
 assert.ok(v.comparisons.filter(c=>c.kind==='acceptedBaseline').every(c=>c.cases[0].p95Passed===false));
 assert.equal(v.tails.slow.collisionBound.status,'PASS');assert.equal(v.tails.slow.status,'PASS');assert.equal(v.passed,true);
 // One candidate cell past the collision bound (twice the base or +250ms, whichever allows more) is one collision:
 // INDETERMINATE, reported, not red (user-approved 2026-10-14).
 pressureP95(2,400e6);
 v=comparePressure(f.file,f.root);assert.equal(v.tails.slow.collisionBound.status,'INDETERMINATE');assert.equal(v.tails.slow.status,'INDETERMINATE');
 assert.deepEqual(v.tails.slow.collisionBound.candidatePressureP95Ms,[200,400]);assert.equal(v.passed,true);
 assert.deepEqual(v.tailSummary,{PASS:0,INDETERMINATE:1,FAIL:0});
 // Both candidate cells past it: the cheap case fails even though isolation is clean.
 pressureP95(3,400e6);
 v=comparePressure(f.file,f.root);assert.equal(v.tails.slow.collisionBound.status,'FAIL');assert.equal(v.tails.slow.status,'FAIL');assert.equal(v.passed,false);
 // A heavy case (base pressure p50 at 2s) has no isolated samples and is judged on the pressure interval.
 const g=fixture(t);
 const heavy=(i,ns,tail)=>g.rewrite(i,x=>{x.stages.pressure.requests.forEach(q=>{q.latencyNs=ns;q.validationCompleteNs=q.startNs+ns;});for(const last of x.stages.pressure.requests.slice(-2)){last.latencyNs=tail;last.validationCompleteNs=last.startNs+tail;}
  x.stages.pressure.caseStatistics.slow.p50Ns=ns;x.stages.pressure.caseStatistics.slow.p95Ns=tail;x.stages.isolated.requests=[];x.stages.isolated.caseStatistics={};});
 for(const i of [0,5])heavy(i,2000e6,2100e6);
 heavy(2,2000e6,2150e6);heavy(3,2000e6,2300e6);
 v=comparePressure(g.file,g.root);
 assert.equal(v.tails.slow.cheap,false);assert.equal(v.tails.slow.source,'pressure');assert.equal(v.tails.slow.status,'INDETERMINATE');assert.equal(v.passed,true);
 heavy(2,2000e6,2300e6);
 v=comparePressure(g.file,g.root);assert.equal(v.tails.slow.status,'FAIL');assert.equal(v.passed,false);
});
test('tails: an isolated stage that disagrees with the pressure-derived task list is refused',t=>{
 const f=fixture(t);
 f.rewrite(2,x=>{x.stages.isolated.requests.pop();});
 assert.throws(()=>comparePressure(f.file,f.root),/Incomplete isolated stage/);
 const g=fixture(t);
 g.rewrite(2,x=>{x.stages.isolated.caseStatistics.slow.p95Ns+=1;});
 assert.throws(()=>comparePressure(g.file,g.root),/isolated per-case statistics/);
 const h=fixture(t);
 h.rewrite(2,x=>{x.stages.isolated.concurrency=4;});
 assert.throws(()=>comparePressure(h.file,h.root),/Incomplete isolated stage/);
});

// Cold acceptance (reviewer P1): a table read on first use lands in the first request of a case,
// which the loading gate (registry-ready) and the pressure stage (after oracle and warmup) never see.
const coldSlow=(r,ns)=>{const q=r.stages.oracle.requests[0];q.latencyNs=ns;q.validationCompleteNs=q.startNs+ns;q.wireCompleteNs=q.startNs+ns-1;};
test('cold: the first request of every case is judged as base/candidate cell intervals and blocks only when every candidate cell is slower',t=>{
 const f=fixture(t);
 let v=comparePressure(f.file,f.root);
 assert.equal(v.cold.cases.slow.status,'PASS');assert.equal(v.cold.cases.slow.source,'oracle');assert.deepEqual(v.coldSummary,{PASS:1,INDETERMINATE:0,FAIL:0});
 assert.equal(v.cold.stageWall.status,'PASS');assert.deepEqual(v.cold.cases.slow.baseMs,[0.0001,0.0001]);assert.deepEqual(v.cold.cases.slow.candidateMs,[0.00008,0.00008]);
 assert.match(v.tailRule.cold,/first request of every case/);
 // One candidate cell cold-slow: indeterminate, reported, not red.
 f.rewrite(2,r=>coldSlow(r,SLOW));
 v=comparePressure(f.file,f.root);assert.equal(v.cold.cases.slow.status,'INDETERMINATE');assert.equal(v.passed,true);assert.equal(v.status,'PASS');
 assert.deepEqual(v.coldSummary,{PASS:0,INDETERMINATE:1,FAIL:0});
 // Both candidate cells cold-slow by more than the tolerance: the deferred work is a regression.
 f.rewrite(3,r=>coldSlow(r,SLOW));
 v=comparePressure(f.file,f.root);assert.equal(v.cold.cases.slow.status,'FAIL');assert.equal(v.passed,false);assert.equal(v.status,'FAIL');
 // The oracle stage's wall is judged the same way.
 const g=fixture(t);
 for(const i of [2,3]) g.rewrite(i,r=>{r.stages.oracle.wallNs=SLOW;});
 v=comparePressure(g.file,g.root);assert.equal(v.cold.stageWall.status,'FAIL');assert.equal(v.cold.cases.slow.status,'PASS');assert.equal(v.passed,false);
 g.rewrite(3,r=>{r.stages.oracle.wallNs=120;});
 v=comparePressure(g.file,g.root);assert.equal(v.cold.stageWall.status,'INDETERMINATE');assert.equal(v.passed,true);
 // The report carries the verdict, the measured intervals and their source.
 const prefix=path.join(g.root,'out','pressure');
 const run=spawnSync(process.execPath,[fileURLToPath(new URL('./benchmark-multigraph-pressure.mjs',import.meta.url)),g.file,g.root,prefix],{encoding:'utf8'});
 const report=fs.readFileSync(prefix+'-report.md','utf8');
 assert.match(report,/Cold \(first request per case, oracle stage\): 1 PASS, 0 INDETERMINATE, 0 FAIL; stage wall base 0\.000–0\.000 ms, candidate 0\.000–150\.000 ms: INDETERMINATE\./);
 assert.equal(JSON.parse(fs.readFileSync(prefix+'-status.json','utf8')).coldSummary.PASS,1);
 assert.equal(run.status,0);
});
test('cold: a missing, incomplete or inconsistent oracle stage is refused',t=>{
 const f=fixture(t);
 f.rewrite(0,r=>{delete r.stages.oracle;});
 assert.throws(()=>comparePressure(f.file,f.root),/Missing oracle stage/);
 const g=fixture(t);
 g.rewrite(0,r=>{r.stages.oracle.requests=[];});
 assert.throws(()=>comparePressure(g.file,g.root),/Incomplete oracle stage/);
 const h=fixture(t);
 h.rewrite(0,r=>{r.stages.oracle.requests[0].latencyNs=5;});
 assert.throws(()=>comparePressure(h.file,h.root),/Invalid complete cold request boundary/);
 assert.throws(()=>coldStatistics(undefined,{cases:[]}),/Missing oracle stage/);
 // Progress over the first pair reports cold intervals from one base and one candidate cell.
 const p=pressureProgress(fixture(t).file,fixture(t).root,3);
 assert.equal(p.cold.cases.slow.status,'PASS');assert.equal(p.status,'PROVISIONAL');
});
