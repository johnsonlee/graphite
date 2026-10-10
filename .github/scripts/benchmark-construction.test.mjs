import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import {compareConstruction,constructionResources} from './benchmark-construction.mjs';
import {aggregateReports} from './benchmark-gate.mjs';
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const write=(p,v)=>{fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,JSON.stringify(v));};
const flags={completeSemanticEquivalence:false,strictEquivalence:false,sourceToDeclarationCompletenessClaim:false,performanceAcceptance:false,otherOperationsEligible:false};
const scope='fresh JVM through 64 saved graphs, persisted indexes, manifests, embedded readback validation and clean writer exit';
const model='complete-core-topology-index-with-source-corrections-and-additive-declarations-v1';
function fixture(t) {
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'construction-contract-'));t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
 const directory=path.join(root,'construction'),file=path.join(directory,'plan.json');
 const arms=Object.fromEntries(['C','A','B'].map((arm)=>[arm,{revision:arm==='C'?'4f2ccf33b969e684972e56b5e810034e6e67c1b3':arm.toLowerCase().repeat(40),
   writer:{path:`/original/${arm}/writer.jar`,sha256:arm.toLowerCase().repeat(64)},
   argv:['/jdk/java','-Xmx4g','-XX:ActiveProcessorCount=4','-Dandroid.jar.path=/jars/android.jar','-Dtika.jar.path=/jars/tika.jar',
     '-Dhive.jar.path=/jars/hive.jar','-Dkotlin.compiler.jar.path=/jars/kotlin.jar','-cp',`/original/${arm}/writer.jar`,'io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation']}])) ;
 const plan={schema:'graphite.real64-construction.plan.v1',engine:'jvm',operation:'construction',graphCount:64,scope,comparisonModel:model,
   cells:[...'CABBAC'].map((arm,i)=>({id:`0${i+1}-${arm}`,arm})),arms,comparisonPairs:{parent:[[1,2],[4,3]],acceptedBaseline:[[0,2],[5,3]]},
   maxHeapBytes:4*1024**3,activeProcessorCount:4,timeouts:{'prepare-real64':14400,'verify-real64':7200},
   producers:{path:'/original/packet.json',sha256:'f'.repeat(64)},...flags};
 write(file,plan);
 const execution={schema:'graphite.real64-construction.execution.v1',status:'PASS_ALL_SIX_USABLE_SAVED64',planSha256:hash(file),cells:[],errors:[],unissued:[],...flags};
 const audit={schema:'graphite.real64-construction.audit.v1',status:'PASS_RAW_USABLE_SAVE_AUDIT',planSha256:hash(file),scope,comparisonModel:model,correctedComparability:plan.producers,rows:[],...flags};
 const cells=path.join(directory,'cells');
 for(const [i,cell] of plan.cells.entries()) {
   const arm=arms[cell.arm],cwd=`/original/run/cells/${cell.id}`,dir=path.join(cells,cell.id);
   const result={cell,status:'PASS_USABLE_SAVED64',writer:arm.writer,revision:arm.revision,errors:[],...flags};
   for(const [name,key] of [['prepare-real64','construction'],['verify-real64','verify']]) {
     const measured=key==='construction';
     const argv=[...arm.argv,...(measured?[`${cwd}/graphs`]:['--verify',`${cwd}/graphs/graphs.tsv`,`${cwd}/graphs/fixture-provenance.tsv`])];
     const phase={name,status:'PASS',argv,cwd,timeoutSeconds:plan.timeouts[name],exit:0,errors:[],
       cleanup:{group:1000+i*2+(measured?0:1),exit:0,after:[],errors:[]}};
     const owner={group:phase.cleanup.group,runnerPid:900,argv};
     if(measured) {
       fs.mkdirSync(path.join(dir,name),{recursive:true});
       const clock=path.join(dir,name,'time-v.log');
       fs.writeFileSync(clock,'User time (seconds): 60.00\nSystem time (seconds): 3.50\nElapsed (wall clock) time (h:mm:ss or m:ss): 1:04.00\nMaximum resident set size (kbytes): 2048\nExit status: 0\n');
       phase.constructionResources=constructionResources(clock);
       phase.launchArgv=['/usr/bin/time','-v','-o',`${cwd}/${name}/time-v.log`,'--',...argv];owner.argv=phase.launchArgv;
     }
     result[key]=phase;
     write(path.join(dir,name,'record.json'),phase);write(path.join(dir,name,'owner.json'),owner);
     fs.writeFileSync(path.join(dir,name,'stdout.log'),'');fs.writeFileSync(path.join(dir,name,'stderr.log'),'');
   }
   write(path.join(dir,'fixture-manifest.json'),{writerRevision:arm.revision,writerJarSha256:arm.writer.sha256,
     graphs:Array.from({length:64},(_,n)=>({id:`g${n}`})),inputJars:['a','t','h','k'],...flags});
   write(path.join(dir,'cell.json'),result);execution.cells.push(result);
   const names=['cell.json','fixture-manifest.json','prepare-real64/time-v.log',...['prepare-real64','verify-real64'].flatMap(n=>['record.json','owner.json','stdout.log','stderr.log'].map(x=>`${n}/${x}`))];
   audit.rows.push({cell,revision:arm.revision,writer:arm.writer,graphCount:64,resources:result.construction.constructionResources,
     rawPins:Object.fromEntries(names.map(n=>[`${cell.id}/${n}`,hash(path.join(dir,n))]))});
 }
 function seal() {write(path.join(cells,'execution.json'),execution);audit.executionSha256=hash(path.join(cells,'execution.json'));write(path.join(directory,'audit.json'),audit);}
 seal();
 return {root,directory,file,plan,execution,audit,seal,cells};
}
test('six measured usable saves give resource absolutes without semantic/query/loading claims',t=>{
 const f=fixture(t),result=compareConstruction(f.file,f.directory);
 assert.equal(result.passed,true);assert.equal(result.comparisons.length,4);
 assert.equal(result.comparisons[2].baseWallMs,64000);assert.equal(result.comparisons[2].baseCpuSeconds,63.5);
 assert.equal(result.comparisons[2].baseRssBytes,2097152);assert.equal(result.otherOperationsEligible,false);
 assert.equal(result.completeSemanticEquivalence,false);
});
test('one changed raw time rejects even when the summary still claims success',t=>{
 const f=fixture(t),file=path.join(f.cells,'03-B/prepare-real64/time-v.log');
 fs.appendFileSync(file,'User time (seconds): 0\n');
 assert.throws(()=>compareConstruction(f.file,f.directory),/raw construction digest/);
 f.audit.rows[2].rawPins['03-B/prepare-real64/time-v.log']=hash(file);f.seal();
 assert.throws(()=>compareConstruction(f.file,f.directory),/unique GNU resource field/);
});
test('nonfinite or unsafe RSS and blank CPU fields are rejected before comparison',t=>{
 const f=fixture(t),file=path.join(f.cells,'01-C/prepare-real64/time-v.log'),raw=fs.readFileSync(file,'utf8');
 for(const bad of ['9'.repeat(400),String(Number.MAX_SAFE_INTEGER),'0']) {
   fs.writeFileSync(file,raw.replace('kbytes): 2048',`kbytes): ${bad}`));
   assert.throws(()=>constructionResources(file),/finite raw construction resources/);
 }
 fs.writeFileSync(file,raw.replace('User time (seconds): 60.00','User time (seconds): '));
 assert.throws(()=>constructionResources(file),/GNU CPU values/);
});
test('single CAB, missing readback, changed graph count and promoted semantic flags reject',t=>{
 const f=fixture(t);f.execution.cells.pop();f.seal();assert.throws(()=>compareConstruction(f.file,f.directory),/all six/);
 const g=fixture(t);fs.unlinkSync(path.join(g.cells,'04-B/verify-real64/record.json'));assert.throws(()=>compareConstruction(g.file,g.directory));
 const h=fixture(t);h.audit.rows[3].graphCount=63;h.seal();assert.throws(()=>compareConstruction(h.file,h.directory),/complete output/);
 const j=fixture(t);j.audit.completeSemanticEquivalence=true;j.seal();assert.throws(()=>compareConstruction(j.file,j.directory),/independent construction audit/);
});
test('a slower reverse or resource overrun cannot be pooled away',t=>{
 const f=fixture(t),i=3,row=f.audit.rows[i],cell=f.execution.cells[i],dir=path.join(f.cells,cell.cell.id);
 const clock=path.join(dir,'prepare-real64/time-v.log');
 fs.writeFileSync(clock,'User time (seconds): 70\nSystem time (seconds): 3.5\nElapsed (wall clock) time (h:mm:ss or m:ss): 1:05\nMaximum resident set size (kbytes): 4096\nExit status: 0\n');
 cell.construction.constructionResources=constructionResources(clock);row.resources=cell.construction.constructionResources;
 write(path.join(dir,'prepare-real64/record.json'),cell.construction);write(path.join(dir,'cell.json'),cell);
 for(const name of Object.keys(row.rawPins))row.rawPins[name]=hash(path.join(f.cells,name));f.seal();
 const result=compareConstruction(f.file,f.directory);assert.equal(result.passed,false);
 assert.equal(result.comparisons[3].deltaWallMs,1000);assert.equal(result.comparisons[3].cpuPassed,false);assert.equal(result.comparisons[3].rssPassed,false);
});
test('aggregate consumes real construction evidence while other missing operations remain unavailable',t=>{
 const f=fixture(t),result=compareConstruction(f.file,f.directory);
 result.evidence={plan:path.relative(f.root,f.file),directory:path.relative(f.root,f.directory)};
 const status=path.join(f.root,'multigraph-construction-status.json');write(status,result);
 const metadata={baseSha:'a'.repeat(40),candidateSha:'b'.repeat(40),runner:'tiny-contract',runUrl:'https://example.invalid'};
 const report=aggregateReports(f.root,metadata);
 assert.equal(report.passed,false);assert.equal(report.operationEvidence.find(x=>x.name==='construction').status,'PASS');
 assert.equal(report.operationEvidence.find(x=>x.name==='loading').status,'UNAVAILABLE');
 assert.equal(report.operationEvidence.find(x=>x.name==='jvm-query').status,'UNAVAILABLE');
 assert.equal(aggregateReports(f.root,{...metadata,candidateSha:'d'.repeat(40)}).operationEvidence.find(x=>x.name==='construction').status,'UNAVAILABLE');
 write(status,{...result,evidence:{...result.evidence,directory:'../escape'}});
 assert.ok(aggregateReports(f.root,metadata).errors.some(e=>e.includes('escaped artifact root')));
});
