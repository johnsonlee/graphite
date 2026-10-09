import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {comparePressure,resourceBound} from './benchmark-multigraph-pressure.mjs';
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
