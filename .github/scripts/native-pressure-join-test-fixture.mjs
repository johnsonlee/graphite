import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
const digest=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const put=(p,v)=>{fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,JSON.stringify(v));};
export function fixture(t) {
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
export function joinedFixture(t, options={}) {
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
 options.prepare?.(f);
 const original={measurementMode:'native39-before-core-v1',status:'ARTIFACTS_AND39_QUERIES_AUDITED_EQUIVALENCE_PENDING',
  arms:structuredClone(f.plan.arms),sourceInputs:{path:'/source-inputs.json',sha256:'source'},armDirectories:{C:'/C',A:'/C',B:'/B'}};
 const originalRef=meta('original',original);
 f.plan.pendingComparabilityAuthority=originalRef;f.plan.comparabilityPending=true;put(f.file,f.plan);
 for(const [i,cell] of f.plan.cells.entries()) {
  f.rewrite(i,r=>{r.arm=f.plan.arms[cell.arm];r.planSha256=digest(f.file);options.changeCell?.(r,i,f);});
  const p=path.join(f.root,cell.id,'audit.json'),a=JSON.parse(fs.readFileSync(p));a.planSha256=digest(f.file);options.changeAudit?.(a,i,f);put(p,a);
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

