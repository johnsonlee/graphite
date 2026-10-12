#!/usr/bin/env node
// Native64 readiness from the original six query processes; no query metric substitution.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {verifyComparabilityJoin,resourceBound,withinWallTolerance,RESOURCE_TOLERANCE,CPU_TOLERANCE_RATIO,RSS_TOLERANCE_RATIO} from './benchmark-multigraph-pressure.mjs';
import {loadingResources} from './benchmark-loading.mjs';
const require=(ok,message)=>{if(!ok)throw new Error(message);};
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const normalized=v=>Array.isArray(v)?v.map(normalized):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,normalized(v[k])])):v;
const same=(a,b)=>JSON.stringify(normalized(a))===JSON.stringify(normalized(b));
export const loadingDeclaration={
    schema:'graphite.native64-loading.measurement.v1',engine:'native',operation:'loading',graphCount:64,
    boundary:'Immediately before wrapper spawn through complete /api/graphs consumption and exact64 validation',
    readiness:'All64 MAPPED registrations and saved counts; includes required startup topology work',
    cpu:'Process-zero cumulative CPU; in-boundary sample lower and first post-ready sample upper with two ticks uncertainty',
    rss:'In-boundary sampled peak lower and first post-ready Linux VmHWM upper; later query RSS is excluded',
    cache:'Matched full closed-input reads before every process; uncontrolled OS cache; no disk-cold claim',
    queriesIssuedDuringLoading:0,deferredWork:'Registry-ready only; first-query work remains in query evidence',
    aggregation:'Fixed six CABBAC cells and two directions; no pooling or retry selection',
};
const rawFiles=['command.json','launch-owner.json','server.pid','readiness.body','resources.jsonl','time-v.log'];
const pairs={parent:[[1,2],[4,3]],acceptedBaseline:[[0,2],[5,3]]};
export function compareNativeLoading(planFile,directory,comparabilityReceipt=null) {
    const plan=read(planFile),digest=sha(planFile),inputs={[path.resolve(planFile)]:digest};
    require(plan.schema==='graphite.multigraph-pressure.plan.v1' && plan.engine==='native' && plan.operation==='query' &&
        same(plan.loadingMeasurement,loadingDeclaration),'Prospective versioned Native64 loading declaration required');
    require(plan.comparabilityPending===true && plan.pendingComparabilityAuthority &&
        !plan.correctedProducerAuthority && !plan.producerAuthority,'Native loading requires its original pending producer and explicit core join');
    require(same(plan.cells.map(c=>c.arm),[...'CABBAC']) && new Set(plan.cells.map(c=>c.id)).size===6 &&
        same(plan.comparisonPairs,pairs) && plan.cases.length===39 &&
        same(plan.schedule,{concurrency:4,warmupPerCase:2,measuredPerCase:20}),'Original six Native39 query processes required');
    require(plan.arms.C.revision==='4f2ccf33b969e684972e56b5e810034e6e67c1b3' && Object.values(plan.arms).every(arm=>
        /^[a-f0-9]{40}$/.test(arm.revision) && arm.graphs.length===64 && new Set(arm.graphs.map(g=>g.id)).size===64 &&
        arm.readiness.expected.count===64),'Own-writer exact Native64 inputs');
    const comparabilityVerified=verifyComparabilityJoin(planFile,directory,plan,comparabilityReceipt,inputs);
    const groups=[];
    const rows=plan.cells.map(cell=>{
        const root=path.join(directory,cell.id),resultFile=path.join(root,'result.json'),auditFile=path.join(root,'audit.json');
        const result=read(resultFile),audit=read(auditFile),arm=plan.arms[cell.arm],loading=result.loading;
        inputs[resultFile]=sha(resultFile);inputs[auditFile]=sha(auditFile);
        require(audit.schema==='graphite.multigraph-pressure.audit.v1' && audit.status==='PASS' && audit.cell===cell.id &&
            audit.planSha256===digest && audit.resultSha256===sha(resultFile) && audit.queryEvidenceComplete===true &&
            audit.completeBodies===39*23 && result.schema==='graphite.multigraph-pressure.result.v1' &&
            result.engine==='native' && result.operation==='query' && result.planSha256===digest &&
            result.status==='PASS' && same(result.errors,[]) && same(result.cell,cell) && same(result.arm,arm),'Original fully audited Native cell');
        require(audit.loading?.schema==='graphite.native64-loading.audit.v1' && audit.loading.status==='PASS_RAW_NATIVE64_LOADING_AUDIT' &&
            audit.loading.engine==='native' && audit.loading.operation==='loading' && audit.loading.graphCount===64 &&
            audit.loading.queriesIssuedDuringLoading===0 && audit.loading.performanceAcceptance===false &&
            audit.loading.otherOperationsEligible===false && loading?.schema==='graphite.native64-loading.cell.v1' &&
            loading.graphCount===64 && loading.queriesIssuedDuringLoading===0 && loading.readyNs<=result.readyNs,'Bound zero-query loading audit');
        require(same(Object.keys(audit.loading.rawPins).sort(),[...rawFiles].sort()),'Complete Native loading raw metadata');
        for(const name of rawFiles) {const file=path.join(root,name);inputs[file]=sha(file);require(inputs[file]===audit.loading.rawPins[name],'Native loading raw bytes changed');}
        const samples=fs.readFileSync(path.join(root,'resources.jsonl'),'utf8').trim().split('\n').map(JSON.parse);
        const resources=loadingResources(loading,samples);
        require(same(resources,loading.resources) && same(resources,audit.loading.resources),'Reconstructed Native loading resources');
        const initial=loading.initialSample,pid=Number(fs.readFileSync(path.join(root,'server.pid'),'utf8'));
        // Including initial in a second formula call validates its original /proc fields/units.
        loadingResources(loading,[initial,...samples]);
        require(Number.isSafeInteger(pid) && pid>1 && initial.pid===pid && loading.cpuAfterReady.pid===pid &&
            loading.startNs<=initial.readStartNs && initial.readEndNs<=loading.readyNs,'Loading process identity and launch boundary');
        require(same(Object.keys(result.stages).sort(),['oracle','pressure','warmup']),'All original query stages retained');
        const loadingEnd=loading.readyWatermark.identityAfter.readEndNs;
        for(const [name,repeats] of [['oracle',1],['warmup',2],['pressure',20]]) {
            const stage=result.stages[name];
            require(stage.requests.length===39*repeats && stage.cpuStart.readStartNs>=loadingEnd &&
                stage.requests.every(r=>r.startNs>=loadingEnd),'Query cannot precede complete loading resource boundary');
        }
        const owner=read(path.join(root,'launch-owner.json')),cleanup=result.cleanup;
        require(owner.group===cleanup.group && owner.group!==owner.runnerPid && Number.isSafeInteger(owner.group) && owner.group>1 &&
            same(cleanup.after,[]) && same(cleanup.errors,[]) && [0,143,-15].includes(cleanup.exit) &&
            cleanup.signals.every(s=>!s.signal.includes('KILL')),'Owned normal Native cleanup');
        groups.push(owner.group);
        const command=read(path.join(root,'command.json')),marker=command.indexOf('--');
        const original=path.dirname(command[3]),data=path.join(original,'data');
        require(command[0]==='/usr/bin/time' && command[1]==='-v' && command[2]==='-o' && marker===9 &&
            same(command.slice(marker+1),[...arm.serverArgv,'--data',data,'--port',String(cell.port),'--load-mode','MAPPED',
                '--max-concurrent-cypher','4','--cypher-max-timeout-ms',String(plan.limits.requestSeconds*1000),'--metrics',
                ...arm.graphs.flatMap(g=>['--graph',`${g.id}:${g.path}`])]),'Exact original Native64 launch');
        const ready=read(path.join(root,'readiness.body'));require(ready.data===data,'Native readiness data');delete ready.data;
        require(ready.graphs.every(g=>typeof g.loadedAt==='string' && Number.isFinite(Date.parse(g.loadedAt))),'Complete readiness timestamps');
        for(const g of ready.graphs) delete g.loadedAt;
        require(same(ready,arm.readiness.expected),'Complete Native64 persisted readiness');
        const raw=fs.readFileSync(path.join(root,'time-v.log'),'utf8'),peaks=[...raw.matchAll(/^\s*Maximum resident set size \(kbytes\):\s*(\d+)\s*$/gm)];
        // Keep both Linux observations; exit rusage need not exceed /proc VmHWM.
        require(peaks.length===1 && Number.isSafeInteger(result.lifecycle.peakRssBytes) &&
            Number(peaks[0][1])*1024===result.lifecycle.peakRssBytes &&
            result.lifecycle.rawSha256===sha(path.join(root,'time-v.log')) &&
            result.lifecycle.peakRssBytes>0,'Positive original lifetime RSS; separate from ready watermark');
        return resources;
    });
    require(new Set(groups).size===6,'Six independent Native processes');
    const comparisons=Object.entries(pairs).flatMap(([kind,ps])=>ps.map(([b,c],direction)=>{
        const base=rows[b],candidate=rows[c];
        const resource=(key,ratio)=>({base:base[key],candidate:candidate[key],absoluteDeltaBounds:[candidate[key][0]-base[key][1],candidate[key][1]-base[key][0]],status:resourceBound(...base[key],...candidate[key],ratio)});
        return {kind,direction,base:plan.cells[b].id,candidate:plan.cells[c].id,baseWallMs:base.wallMs,candidateWallMs:candidate.wallMs,
            deltaWallMs:candidate.wallMs-base.wallMs,wallPassed:withinWallTolerance(candidate.wallMs,base.wallMs),
            cpu:resource('cpuBoundsSeconds',CPU_TOLERANCE_RATIO),rss:resource('rssBoundsBytes',RSS_TOLERANCE_RATIO)};
    }));
    const measuredConstraintsPassed=comparisons.filter(c=>c.kind==='acceptedBaseline').every(c=>c.wallPassed && c.cpu.status==='PASS' && c.rss.status==='PASS');
    for(const [file,digest] of Object.entries(inputs))require(sha(file)===digest,'Native loading evidence drift');
    const passed=comparabilityVerified&&measuredConstraintsPassed;
    return {schema:'graphite.native64-loading.comparison.v1',engine:'native',operation:'loading',scope:'multi-graph-native64-registry-ready',
        planSha256:digest,passed,status:!comparabilityVerified?'MEASURED_CORE_COMPARABILITY_PENDING':passed?'PASS':'FAIL',
        comparabilityPending:!comparabilityVerified,coreComparabilityVerified:comparabilityVerified,measuredConstraintsPassed,resourceTolerance:RESOURCE_TOLERANCE,comparisons,
        acceptedBaselineRevision:plan.arms.C.revision,parentRevision:plan.arms.A.revision,candidateRevision:plan.arms.B.revision,
        performanceAcceptance:passed,otherOperationsEligible:false,
        limitation:'Prospective zero-query registry-ready interval within six fresh Native39 processes. Later query RSS excluded. Wall within 5%, CPU within 10%, RSS within 10% of the base (user-approved 2026-10-12); the historical loading allowance is not automatically applied. No query acceptance.'};
}
export function writeNativeLoading(plan,directory,prefix,receipt=null) {
    let value;
    try {value=compareNativeLoading(plan,directory,receipt);value.evidence={plan:path.relative(path.dirname(prefix),plan),directory:path.relative(path.dirname(prefix),directory),
        ...(receipt?{comparabilityReceipt:path.relative(path.dirname(prefix),receipt)}:{})};}
    catch(error) {value={schema:'graphite.native64-loading.comparison.v1',engine:'native',operation:'loading',passed:false,status:'UNAVAILABLE',errors:[error.message],performanceAcceptance:false,otherOperationsEligible:false};}
    fs.mkdirSync(path.dirname(prefix),{recursive:true});fs.writeFileSync(prefix+'-status.json',JSON.stringify(value,null,2)+'\n');
    const lines=['### Native64 registry-ready loading','',value.status,'','| Pair | Base wall ms | Candidate wall ms | Δ ms | CPU bounds s | RSS bounds bytes |','|---|---:|---:|---:|---|---|'];
    for(const c of value.comparisons??[])lines.push(`| ${c.kind} ${c.base} → ${c.candidate} | ${c.baseWallMs} | ${c.candidateWallMs} | ${c.deltaWallMs} | ${c.cpu.base} → ${c.cpu.candidate} (${c.cpu.status}) | ${c.rss.base} → ${c.rss.candidate} (${c.rss.status}) |`);
    lines.push('',...(value.errors??[]),value.limitation??'');fs.writeFileSync(prefix+'-report.md',lines.join('\n')+'\n');return value;
}
if(process.argv[1] && fs.realpathSync(process.argv[1])===fs.realpathSync(fileURLToPath(import.meta.url))) {
    const [plan,directory,prefix,flag,receipt]=process.argv.slice(2);
    require(plan&&directory&&prefix && (process.argv.length===5 || (process.argv.length===7&&flag==='--comparability-receipt'&&receipt)),'Native loading plan directory prefix [--comparability-receipt file]');
    if(!writeNativeLoading(plan,directory,prefix,receipt).passed)process.exitCode=1;
}
