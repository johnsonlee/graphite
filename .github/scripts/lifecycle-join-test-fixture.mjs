// Tiny correctness fixture only; never a performance sample.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const write=(p,v)=>{fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,JSON.stringify(v));};
export function joinedLifecycle(f,options={}) {
 const joinRoot=path.join(f.root,'join'),metadata=[],pins={};
 const ref=(file,original)=>{const r={path:original,sha256:hash(file)};pins[r.path]=r.sha256;return r;};
 const meta=(name,value)=>{const file=path.join(joinRoot,'metadata',name+'.json');write(file,value);
  const r=ref(file,`/original/proof/${name}.json`);metadata.push({...r,relativePath:`metadata/${name}.json`});return r;};
 const original={measurementMode:'native39-before-core-v1',status:'ARTIFACTS_AND39_QUERIES_AUDITED_EQUIVALENCE_PENDING',
  arms:{},pins:{},sourceInputs:{path:'/jars/inputs',sha256:'d'.repeat(64)},armDirectories:{C:'/C',A:'/A',B:'/B'}};
 for(const [label,arm] of Object.entries(f.plan.arms)) {
  for(const field of ['sourceManifest','runtimeManifest','artifactAudit','fixtureManifest']) arm[field]={path:`/${label}/${field}`,sha256:label.toLowerCase().repeat(64)};
  arm.writer??={path:`/${label}/writer.jar`,sha256:label.toLowerCase().repeat(64)};
  const runtimeFiles={[arm.writer.path]:arm.writer.sha256,...(arm.serverJar?{[arm.serverJar.path]:arm.serverJar.sha256}:{})};
  original.arms[label]={...structuredClone(arm),graphs:arm.graphs??Array.from({length:64},(_,i)=>({id:`g${i}`,path:`/${label}/g${i}`})),runtimeFiles};
  Object.assign(original.pins,runtimeFiles);
 }
 f.plan.pins={...original.pins};f.plan.sourceInputs=original.sourceInputs;
 const originalRef=meta('original',original);
 Object.assign(f.plan,{comparabilityPending:true,pendingComparabilityAuthority:originalRef,producers:originalRef});
 write(f.file,f.plan);f.execution.planSha256=hash(f.file);f.audit.planSha256=hash(f.file);f.audit.correctedComparability=originalRef;
 if(f.records) for(const [i,cell] of f.plan.cells.entries()) {
  f.records[i].planSha256=hash(f.file);
  for(const name of ['identities-before.json','identities-after.json'])write(path.join(f.cells,cell.id,name),{pins:f.plan.pins});
  f.reseal(i);
 }
 f.seal();
 const comparisons={};
 for(const arm of ['A','B']) {
  const revisions={C:f.plan.arms.C.revision,B:f.plan.arms[arm].revision};
  const fixtures={C:original.arms.C.fixtureManifest,B:original.arms[arm].fixtureManifest};
  const corePlan={revisions,fixtureManifests:fixtures,maxOwnedPhases:139,graphs:Array.from({length:64},(_,i)=>({id:`g${i}`}))};
  options.changeCorePlan?.(corePlan,arm);const corePlanRef=meta(`core-${arm}-plan`,corePlan);
  const phaseRefs=Array.from({length:139},(_,i)=>{const p={status:'PASS',exit:0,errors:[]};options.changePhase?.(p,i,arm);return meta(`core-${arm}-phase-${i}`,p);});
  const record={plan:corePlanRef,phases:phaseRefs,graphs:corePlan.graphs,errors:[],finalIdentity:'PASS',
   status:'PASS_ALL64_CORE_WITH_EXPLICIT_TYPE_OVERLOAD_SYNTHETIC_INHERITED_FIELD_CORRECTIONS_TOPOLOGY_INDICES_ADDITIVE_DECLARATION_VALIDITY'};
  const recordRef=meta(`core-${arm}-record`,record);
  const audit={status:'PASS_INDEPENDENT_ALL64_CORE_TOPOLOGY_EXECUTION_AUDIT',completeCoreTopologyIndexComparison:true,
   formatterKinds:{C:'legacy-flat',B:'rank-preserving'},productionFormatterTestsApplicable:true,productionFormatterTestsVerified:true,phases:139,graphs:corePlan.graphs,revisions,fixtureManifests:fixtures,record:recordRef,
   pins:{[recordRef.path]:recordRef.sha256,[corePlanRef.path]:corePlanRef.sha256,...Object.fromEntries(phaseRefs.map(r=>[r.path,r.sha256]))}};
  for(const name of ['C',arm]) for(const field of ['artifactAudit','fixtureManifest','sourceManifest','runtimeManifest']) {const r=original.arms[name][field];audit.pins[r.path]=r.sha256;}
  options.changeCoreAudit?.(audit,arm);
  comparisons[arm]={audit:meta(`core-${arm}-audit`,audit),revisions,fixtureManifests:fixtures};
 }
 const corrected={...structuredClone(original),status:'ARTIFACTS_QUERIES_AND_CORRECTED_CORE_COMPARABILITY_AUDITED',missingAuthority:[],correctedComparisons:comparisons};
 options.changeCorrected?.(corrected);const correctedRef=meta('corrected',corrected);
 const receipt={schema:'graphite.real64-lifecycle.core-comparability-join.v1',engine:'jvm',operation:f.plan.operation,
  status:'PASS_SAME_INPUT_COMPLETE_CORRECTED_CORE_JOIN',coreComparabilityVerified:true,
  completeSemanticEquivalence:false,strictEquivalence:false,sourceToDeclarationCompletenessClaim:false,performanceAcceptance:false,otherOperationsEligible:false,
  plan:ref(f.file,'/original/measurement/plan.json'),measurementProducer:originalRef,correctedProducer:correctedRef,
  measurementExecution:ref(path.join(f.cells,'execution.json'),'/original/measurement/cells/execution.json'),
  measurementAudit:ref(path.join(f.directory,'audit.json'),'/original/measurement/audit.json'),
  measurementInputsSha256:originalRef.sha256,coreComparisons:comparisons,metadata,pins};
 options.changeReceipt?.(receipt);const receiptFile=path.join(joinRoot,'comparability.json');write(receiptFile,receipt);
 return {...f,receipt,receiptFile,joinRoot};
}
