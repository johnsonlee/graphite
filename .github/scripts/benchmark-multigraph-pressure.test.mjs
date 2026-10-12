import {fixture,joinedFixture} from './native-pressure-join-test-fixture.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {comparePressure,pressureProgress,resourceBound,withinLatencyTolerance} from './benchmark-multigraph-pressure.mjs';
// Past both tolerances of the fixture's 100ns base: more than 100ms and more than 5%.
const SLOW=150e6;
const digest=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const put=(p,v)=>{fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,JSON.stringify(v));};
test('fixed per-case repeated latency and independent CPU/RSS intervals',t=>{
 const f=fixture(t),r=comparePressure(f.file,f.root);assert.equal(r.passed,true);assert.equal(r.comparisons.length,4);assert.equal(r.comparisons[2].cases[0].deltaP95Ms,-.00002);
 assert.deepEqual(r.latencyTolerance,{absoluteMs:100,ratio:0.05});
 // Within 100ms or 5% of the base passes; past both fails, whichever is the larger allowance.
 for(const [candidate,base,expected] of [[150,100,true],[200e6,100e6,true],[200e6+1,100e6,false],[4.2e9+1,4e9,false],[4.2e9,4e9+1,true],[4.15e9,4e9,true],[100,100,true]])
  assert.equal(withinLatencyTolerance(candidate,base),expected,`${candidate} vs ${base}`);
 // A regression inside the tolerance keeps the verdict.
 f.rewrite(3,r=>{r.stages.pressure.requests.forEach(x=>{x.latencyNs=150;x.validationCompleteNs=x.startNs+150;});r.stages.pressure.caseStatistics.slow.p50Ns=150;r.stages.pressure.caseStatistics.slow.p95Ns=150;});
 assert.equal(comparePressure(f.file,f.root).passed,true);
 assert.equal(resourceBound(100,120,110,115),'INDETERMINATE');assert.equal(resourceBound(100,100,106,106),'FAIL');
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
test('each fixed direction is required; a slower reverse cannot be pooled away',t=>{
 const f=fixture(t);f.rewrite(3,r=>{r.stages.pressure.requests.forEach(x=>{x.latencyNs=SLOW;x.validationCompleteNs=x.startNs+SLOW;});r.stages.pressure.caseStatistics.slow.p50Ns=SLOW;r.stages.pressure.caseStatistics.slow.p95Ns=SLOW;});
 const r=comparePressure(f.file,f.root);assert.equal(r.passed,false);assert.equal(r.comparisons[3].cases[0].p95Passed,false);
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
  });
  const file=path.join(f.root,cell.id,'audit.json'),audit=JSON.parse(fs.readFileSync(file));
  audit.planSha256=digest(f.file);audit.coverage=f.plan.coverage;audit.completeBodies=35*23;put(file,audit);
 }
 const verdict=comparePressure(f.file,f.root);
 assert.equal(verdict.passed,true);
 assert.ok(verdict.comparisons.every(pair=>pair.cases.length===35));
 const complete=f.plan.cells.reduce((sum,cell)=>sum+JSON.parse(fs.readFileSync(path.join(f.root,cell.id,'audit.json'))).completeBodies,0);
 const measured=f.plan.cells.reduce((sum,cell)=>sum+JSON.parse(fs.readFileSync(path.join(f.root,cell.id,'result.json'))).stages.pressure.requests.length,0);
 assert.equal(complete,4830);assert.equal(measured,4200);
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
   if(i!==2)return;const s=r.stages.pressure;
   if(metric==='latency') {s.requests.forEach(x=>{x.latencyNs=SLOW;x.validationCompleteNs=x.startNs+SLOW;});s.caseStatistics.slow.p50Ns=SLOW;s.caseStatistics.slow.p95Ns=SLOW;}
   if(metric==='cpu') {s.resources.cpuLowerBoundSeconds=2;s.resources.cpuUpperBoundSeconds=2;}
   if(metric==='rss') {s.resources.rss.lowerBoundBytes=2000;s.resources.rss.upperBoundBytes=2000;}
  }});
  const r=comparePressure(f.file,f.root,f.receiptFile);assert.equal(r.passed,false);assert.equal(r.status,'FAIL');
  const pair=r.comparisons.find(x=>x.kind==='acceptedBaseline'&&x.candidate==='c2');
  if(metric==='latency')assert.equal(pair.cases[0].p95Passed,false);else assert.equal(pair[metric].status,'FAIL');
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
test('Linux observed RSS envelopes retain the conservative five percent gate',t=>{
 const f=fixture(t);for(let i=0;i<6;i++)f.rewrite(i,r=>linuxEnvelope(r));
 let result=comparePressure(f.file,f.root);assert.equal(result.passed,true);
 assert.equal(result.comparisons[0].rss.measurementSemantics,'linux-rss-observed-envelope-v1');
 f.rewrite(2,r=>linuxEnvelope(r,1010,1100));
 result=comparePressure(f.file,f.root);assert.equal(result.passed,false);
 assert.equal(result.comparisons[0].rss.status,'INDETERMINATE');
 f.rewrite(2,r=>{r.stages.pressure.resources.rss.lowerBoundBytes=1100;});
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
