import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {comparePressure,pressureProgress,resourceBound} from './benchmark-multigraph-pressure.mjs';
const digest=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const put=(p,v)=>{fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,JSON.stringify(v));};
function fixture(t) {
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'multigraph-comparison-'));t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
 const plan={schema:'graphite.multigraph-pressure.plan.v1',operation:'query',engine:'native',cells:['C','A','B','B','A','C'].map((arm,i)=>({id:`c${i}`,arm,port:10000+i})),
 arms:Object.fromEntries(['C','A','B'].map(x=>[x,{revision:x}])),cases:[{id:'slow',targetGraphIds:['one','two']}],
 comparisonPairs:{parent:[[1,2],[4,3]],acceptedBaseline:[[0,2],[5,3]]},schedule:{concurrency:4,warmupPerCase:2,measuredPerCase:20},
 coverage:{requiredFamilies:['slow'],coveredFamilies:['slow'],unavailableFamilies:[]}};
 const file=path.join(root,'plan.json');put(file,plan);
 const rewrite=(i,change)=>{
   const p=path.join(root,`c${i}`,'result.json');let r=JSON.parse(fs.readFileSync(p));change(r);put(p,r);
   const a=path.join(root,`c${i}`,'audit.json');let audit=JSON.parse(fs.readFileSync(a));audit.resultSha256=digest(p);put(a,audit);
 };
 plan.cells.forEach((cell,i)=>{
   const lat=cell.arm==='B'?80:100;
   const requests=Array.from({length:20},(_,sequence)=>({sequence,caseId:'slow',status:'PASS',httpStatus:200,completeBody:true,deadlineExpired:false,
   startNs:sequence*1000,wireCompleteNs:sequence*1000+lat-1,validationCompleteNs:sequence*1000+lat,latencyNs:lat}));
   const result={schema:'graphite.multigraph-pressure.result.v1',status:'PASS',errors:[],cell,arm:plan.arms[cell.arm],engine:plan.engine,operation:'query',planSha256:digest(file),coverage:plan.coverage,
   cleanup:{after:[],errors:[],signals:[]},stages:{pressure:{status:'PASS',allWorkersStopped:true,unissued:[],journalErrors:[],requests,wallNs:cell.arm==='B'?19080:19100,
   caseStatistics:{slow:{n:20,sampleIds:Array.from({length:20},(_,x)=>x),p50Ns:lat,p95Ns:lat}},
   resources:{cpuLowerBoundSeconds:1,cpuUpperBoundSeconds:1.01,rss:{lowerBoundBytes:1000,upperBoundBytes:1010}}}}};
   const p=path.join(root,cell.id,'result.json');put(p,result);
   put(path.join(root,cell.id,'audit.json'),{schema:'graphite.multigraph-pressure.audit.v1',status:'PASS',cell:cell.id,resultSha256:digest(p),planSha256:digest(file),queryEvidenceComplete:true,completeBodies:23,otherOperationsEligible:false,coverage:plan.coverage});
 });
 return {root,file,rewrite,plan};
}
test('fixed per-case repeated latency and independent CPU/RSS intervals',t=>{
 const f=fixture(t),r=comparePressure(f.file,f.root);assert.equal(r.passed,true);assert.equal(r.comparisons.length,4);assert.equal(r.comparisons[2].cases[0].deltaP95Ms,-.00002);
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
 const f=fixture(t);f.rewrite(3,r=>{r.stages.pressure.requests.forEach(x=>{x.latencyNs=150;x.validationCompleteNs=x.startNs+150;});r.stages.pressure.caseStatistics.slow.p50Ns=150;r.stages.pressure.caseStatistics.slow.p95Ns=150;});
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
function joinedFixture(t, options={}) {
 const f=fixture(t), joinRoot=path.join(f.root,'join'), metadata=[], pins={};
 const hosted='/original-run';
 const ref=(file,original)=>{const r={path:original,sha256:digest(file)};pins[r.path]=r.sha256;return r;};
 const meta=(name,value)=>{
  const file=path.join(joinRoot,'metadata',name+'.json');put(file,value);
  const r=ref(file,`${hosted}/proof/${name}.json`);metadata.push({...r,relativePath:`metadata/${name}.json`});return r;
 };
 for(const arm of ['C','A','B']) {
  const identity=arm==='A'&&options.alias!==false?'C':arm;
  f.plan.arms[arm]={revision:identity,sourceManifest:{path:`/${identity}/source`,sha256:identity},
   runtimeManifest:{path:`/${identity}/runtime`,sha256:identity},fixtureManifest:{path:`/${identity}/fixture`,sha256:identity},
   graphs:Array.from({length:64},(_,i)=>({id:`g${i}`,path:`/${identity}/g${i}`})),
   serverArgv:[`/${identity}/graphite`,'serve'],runtimeFiles:{[`/${identity}/graphite`]:identity}};
 }
 const original={measurementMode:'native39-before-core-v1',status:'ARTIFACTS_AND39_QUERIES_AUDITED_EQUIVALENCE_PENDING',
  arms:structuredClone(f.plan.arms),sourceInputs:{path:'/source-inputs.json',sha256:'source'},armDirectories:{C:'/C',A:'/C',B:'/B'}};
 const originalRef=meta('original',original);
 f.plan.pendingComparabilityAuthority=originalRef;f.plan.comparabilityPending=true;put(f.file,f.plan);
 for(const [i,cell] of f.plan.cells.entries()) {
  f.rewrite(i,r=>{r.arm=f.plan.arms[cell.arm];r.planSha256=digest(f.file);options.changeCell?.(r,i);});
  const p=path.join(f.root,cell.id,'audit.json'),a=JSON.parse(fs.readFileSync(p));a.planSha256=digest(f.file);put(p,a);
 }
 const planRef=ref(f.file,hosted+'/measurement/plan.json'), comparisons={};
 for(const arm of options.alias===false?['A','B']:['B']) {
  const revisions={C:f.plan.arms.C.revision,B:f.plan.arms[arm].revision};
  const fixtures={C:f.plan.arms.C.fixtureManifest,B:f.plan.arms[arm].fixtureManifest};
  const corePlan={revisions,fixtureManifests:fixtures,maxOwnedPhases:139,graphs:Array.from({length:64},(_,i)=>({id:`g${i}`}))};
  options.changeCorePlan?.(corePlan,arm);
  const corePlanRef=meta(`core-${arm}-plan`,corePlan);
  const phaseRefs=Array.from({length:139},(_,i)=>{
   const phase={status:'PASS',exit:0,errors:[]};options.changePhase?.(phase,i,arm);return meta(`core-${arm}-phase-${i}`,phase);
  });
  const record={plan:corePlanRef,phases:phaseRefs,graphs:corePlan.graphs,errors:[],finalIdentity:'PASS',
   status:'PASS_ALL64_CORE_WITH_EXPLICIT_TYPE_OVERLOAD_SYNTHETIC_INHERITED_FIELD_CORRECTIONS_TOPOLOGY_INDICES_ADDITIVE_DECLARATION_VALIDITY'};
  options.changeCoreRecord?.(record,arm);
  const recordRef=meta(`core-${arm}-record`,record);
  const audit={status:'PASS_INDEPENDENT_ALL64_CORE_TOPOLOGY_EXECUTION_AUDIT',completeCoreTopologyIndexComparison:true,
   productionFormatterTestsVerified:true,phases:139,graphs:corePlan.graphs,revisions,fixtureManifests:fixtures,record:recordRef,
   pins:{[recordRef.path]:recordRef.sha256,[corePlanRef.path]:corePlanRef.sha256,...Object.fromEntries(phaseRefs.map(r=>[r.path,r.sha256]))}};
  options.changeCoreAudit?.(audit,arm);
  comparisons[arm]={audit:meta(`core-${arm}-audit`,audit),revisions,fixtureManifests:fixtures};
 }
 const corrected={...structuredClone(original),status:'ARTIFACTS_QUERIES_AND_CORRECTED_CORE_COMPARABILITY_AUDITED',
  missingAuthority:[],correctedComparisons:comparisons};
 options.changeCorrected?.(corrected);
 const correctedRef=meta('corrected',corrected);
 const execution={status:'MEASURED_CORE_COMPARABILITY_PENDING',performanceAcceptance:false,plan:planRef,
  errors:[],unissued:[],cells:f.plan.cells.map(c=>({id:c.id,status:'PASS'}))};
 options.changeExecution?.(execution);put(path.join(f.root,'execution.json'),execution);
 const executionRef=ref(path.join(f.root,'execution.json'),hosted+'/measurement/execution.json');
 const cells=f.plan.cells.map(cell=>({id:cell.id,...Object.fromEntries(['result','audit'].map(kind=>
  [kind,ref(path.join(f.root,cell.id,kind+'.json'),`${hosted}/measurement/${cell.id}/${kind}.json`)]))}));
 const receipt={schema:'graphite.native-pressure.core-comparability-join.v1',status:'PASS_SAME_INPUT_COMPLETE_CORRECTED_CORE_JOIN',
  coreComparabilityVerified:true,completeSemanticEquivalence:false,strictEquivalence:false,sourceToDeclarationCompletenessClaim:false,
  performanceAcceptance:false,otherOperationsEligible:false,plan:planRef,measurementProducer:originalRef,correctedProducer:correctedRef,
  measurementExecution:executionRef,measurementInputsSha256:originalRef.sha256,coreComparisons:comparisons,cells,metadata,pins};
 options.changeReceipt?.(receipt);
 const receiptFile=path.join(joinRoot,'comparability.json');put(receiptFile,receipt);
 return {...f,receipt,receiptFile,joinRoot,metadata};
}

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
   if(metric==='latency') {s.requests.forEach(x=>{x.latencyNs=150;x.validationCompleteNs=x.startNs+150;});s.caseStatistics.slow.p50Ns=150;s.caseStatistics.slow.p95Ns=150;}
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
