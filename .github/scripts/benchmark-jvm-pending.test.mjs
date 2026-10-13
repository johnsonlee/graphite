import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {spawnSync} from 'node:child_process';
import {comparePressure} from './benchmark-multigraph-pressure.mjs';
import {joinedFixture} from './native-pressure-join-test-fixture.mjs';

function fixture(t,mutation={}) {
 const exports={},joins={},rawAudits={};
 return joinedFixture(t,{
  jvm:true,
  prepare(f) {
   f.plan.engine='jvm';f.plan.cases=Array.from({length:35},(_,i)=>({id:'q'+i,targetGraphIds:['g0','g1']}));
   for(const arm of ['C','A','B'])f.plan.arms[arm].artifactAudit={path:`/${arm==='A'?'C':arm}/artifact`,sha256:arm==='A'?'C':arm};
  },
  changeOriginal(original,f,meta) {
   original.schema='graphite.jvm-pressure.producers.v1';original.measurementMode='jvm35-before-core-v1';original.status='JVM35_RESPONSES_AUDITED_CORE_COMPARABILITY_PENDING';original.correctnessRoots={C:'/C/query',A:'/C/query',B:'/B/query'};
   for(const arm of ['C','A','B']) {
    const producer=f.plan.arms[arm],pins={};
    exports[arm]=producer.graphs.map((g,i)=>{
     const edges={graphRoot:g.path,sourceInputs:{forward:{path:g.path+'/forward',sha256:'same-'+i}},nodeSlots:44,labeledEdges:2,mappingApplied:false,output:{path:`/${arm}/raw/${i}`,sha256:'edges-'+i}};
     const receipt=meta(`raw-${arm}-${i}`,edges);pins[receipt.path]=receipt.sha256;pins[edges.output.path]=edges.output.sha256;
     return {id:g.id,edges,receipt};
    });
    const audit={schema:'graphite.jvm-raw-edge-export-audit.v1',status:'PASS_ALL64_RAW_EDGE_OWNED_EXECUTION_AUDIT',completeCoreTopologyIndexComparison:false,performanceAcceptance:false,
      ...Object.fromEntries(['sourceManifest','runtimeManifest','fixtureManifest','artifactAudit'].map(k=>[k,producer[k]])),graphs:exports[arm],pins};
    rawAudits[arm]=meta('raw-audit-'+arm,audit);producer.rawExportAudit=rawAudits[arm];original.arms[arm]=structuredClone(producer);
   }
  },
  changeCell(r,i,f) {
   r.engine='jvm';const latency=r.cell.arm==='B'?80:100;
   r.stages.pressure.requests=Array.from({length:20},(_,n)=>f.plan.cases.map((c,k)=>{
    const sequence=n*35+k;return {sequence,caseId:c.id,status:'PASS',httpStatus:200,completeBody:true,deadlineExpired:false,startNs:sequence*1000,wireCompleteNs:sequence*1000+latency-1,validationCompleteNs:sequence*1000+latency,latencyNs:latency};
   })).flat();
   r.stages.pressure.caseStatistics=Object.fromEntries(f.plan.cases.map((c,k)=>[c.id,{n:20,sampleIds:Array.from({length:20},(_,n)=>n*35+k),p50Ns:latency,p95Ns:latency}]));
   r.stages.pressure.wallNs=700000;
   // Every case is cheap here, so the isolated stage repeats all 35 of them 40 times, case-major.
   r.stages.isolated.requests=Array.from({length:35*40},(_,sequence)=>({sequence,caseId:f.plan.cases[Math.floor(sequence/40)].id,status:'PASS',httpStatus:200,completeBody:true,deadlineExpired:false,
    startNs:1e6+sequence*1000,wireCompleteNs:1e6+sequence*1000+latency-1,validationCompleteNs:1e6+sequence*1000+latency,latencyNs:latency}));
   r.stages.isolated.caseStatistics=Object.fromEntries(f.plan.cases.map((c,k)=>[c.id,{n:40,sampleIds:Array.from({length:40},(_,n)=>k*40+n),p50Ns:latency,p95Ns:latency}]));
   r.stages.isolated.wallNs=(35*40-1)*1000+latency;
  },
  changeAudit(a){a.completeBodies=35*63;},
  changeCoreAudit(a,arm,meta) {
   for(const side of ['C','B'])for(const key of ['sourceManifest','runtimeManifest','fixtureManifest','artifactAudit']){
    const identity=side==='C'?'C':'B',ref={path:`/${identity}/${key==='sourceManifest'?'source':key==='runtimeManifest'?'runtime':key==='fixtureManifest'?'fixture':'artifact'}`,sha256:identity};a.pins[ref.path]=ref.sha256;
   }
   a.graphs=a.graphs.map((g,i)=>{
    const reference={...exports.C[i].edges,output:{path:`/core/reference/${i}`,sha256:'edges-'+i}},actual={...exports.B[i].edges,output:{path:`/core/actual/${i}`,sha256:'edges-'+i}};
    mutation.edge?.(actual,i);
    const topology=meta('topology-'+i,{rawEdgeExports:{reference,actual}});a.pins[topology.path]=topology.sha256;a.pins[reference.output.path]=reference.output.sha256;a.pins[actual.output.path]=actual.output.sha256;
    for(const name of ['C','A','B']){joins[name]??={rawAudit:rawAudits[name],graphs:[]};joins[name].graphs.push({id:g.id,rawReceipt:exports[name][i].receipt,topology,coreArm:'B',coreSide:name==='B'?'actual':'reference',outputSha256:'edges-'+i});}
    return {...g,topology};
   });
   mutation.audit?.(a);
  },
  changeCorrected(p){p.rawEdgeJoins=joins;mutation.corrected?.(p);},
  changePhase:mutation.phase,
 });
}

test('JVM35 pending measurements retain all samples and direct CLI never passes',t=>{
 const f=fixture(t);const pending=comparePressure(f.file,f.root);
 assert.equal(pending.passed,false);assert.equal(pending.status,'MEASURED_CORE_COMPARABILITY_PENDING');assert.equal(pending.comparabilityPending,true);
 const cli=spawnSync(process.execPath,['.github/scripts/benchmark-multigraph-pressure.mjs',f.file,f.root,f.root+'/pending'],{encoding:'utf8'});assert.equal(cli.status,1);
 const completed=comparePressure(f.file,f.root,f.receiptFile);assert.equal(completed.passed,true);assert.equal(completed.coreComparabilityVerified,true);assert.equal(completed.comparabilityPending,false);
 assert.equal(completed.comparisons.length,4);assert.equal(completed.comparisons[0].cases.length,35);
});

test('JVM35 final join rejects raw edge changes, missing graphs, core source drift and phase failures',t=>{
 for(const mutation of [
  {edge:(e,i)=>{if(i===63)e.output.sha256='different';}},
  {corrected:p=>p.rawEdgeJoins.B.graphs.pop()},
  {audit:a=>delete a.pins['/B/runtime']},
  {phase:(p,i)=>{if(i===138)p.status='FAIL';}},
 ]){const f=fixture(t,mutation);assert.throws(()=>comparePressure(f.file,f.root,f.receiptFile));}
});

test('JVM35 cannot consume a Native receipt or a stripped pending marker',t=>{
 for(const kind of ['receipt','marker']){
  const f=fixture(t);
  if(kind==='receipt'){f.receipt.schema='graphite.native-pressure.core-comparability-join.v1';fs.writeFileSync(f.receiptFile,JSON.stringify(f.receipt));}
  else{delete f.plan.comparabilityPending;fs.writeFileSync(f.file,JSON.stringify(f.plan));}
  assert.throws(()=>comparePressure(f.file,f.root,kind==='receipt'?f.receiptFile:null));
 }
});
