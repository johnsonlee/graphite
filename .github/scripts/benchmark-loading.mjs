#!/usr/bin/env node
// The ready VmHWM bound, never the later lifetime peak, determines loading RSS.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {resourceBound} from './benchmark-multigraph-pressure.mjs';
const require=(ok,message)=>{if(!ok)throw new Error(message);};
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const normalized=value=>Array.isArray(value)?value.map(normalized):value&&typeof value==='object'?Object.fromEntries(Object.keys(value).sort().map(k=>[k,normalized(value[k])])):value;
const same=(a,b)=>JSON.stringify(normalized(a))===JSON.stringify(normalized(b));
const flags=['completeSemanticEquivalence','strictEquivalence','sourceToDeclarationCompletenessClaim','performanceAcceptance','otherOperationsEligible'];
const model='complete-core-topology-index-with-source-corrections-and-additive-declarations-v1';
const scope={
    boundary:'Immediately before wrapper spawn through complete /api/graphs consumption and exact64 validation',
    readiness:'All64 MAPPED registrations and saved counts; includes required startup topology work',
    cpu:'Process-zero cumulative user+system CPU; in-boundary sample lower, first post-ready sample upper, with two ticks uncertainty',
    rss:'In-boundary sampled peak lower; first post-ready Linux VmHWM upper; GNU time lifetime RSS is only corroboration',
    cache:'Matched full closed-input reads before every process; uncontrolled OS cache; no disk-cold claim',
    deferredWork:'Registry-ready only; deferred first-query work is not counted as already paid',
    aggregation:'Fixed CABBAC and two directions; no pooling, retry selection, quiet-host or saturation claim',
};
const pairs={parent:[[1,2],[4,3]],acceptedBaseline:[[0,2],[5,3]]};
const safe=x=>Number.isSafeInteger(x)&&x>=0;
function sample(r) {
    require(r.backend==='linux-proc'&&safe(r.readStartNs)&&safe(r.readEndNs)&&r.readStartNs<=r.readEndNs&&
        safe(r.ticksPerSecond)&&r.ticksPerSecond>0&&safe(r.pageSize)&&r.pageSize>0,'raw Linux sample units and timing');
    const tail=r.raw.slice(r.raw.lastIndexOf(')')+2).trim().split(/\s+/);
    require(Number(r.raw.split(' ',1)[0])===r.pid&&Number(tail[19])===r.startTicks&&
        Number(tail[11])===r.userTicks&&Number(tail[12])===r.systemTicks&&safe(r.userTicks)&&safe(r.systemTicks)&&
        r.cpuSeconds===(r.userTicks+r.systemTicks)/r.ticksPerSecond&&r.rssBytes===Number(tail[21])*r.pageSize&&safe(r.rssBytes),'raw /proc sample identity and counters');
}
export function loadingResources(record,samples) {
    const {startNs:start,readyNs:ready,cpuAfterReady:after,readyWatermark:high}=record;
    require(safe(start)&&safe(ready)&&start<=ready&&ready<=after.readStartNs&&after.readEndNs<=high.readStartNs&&
        safe(high.readStartNs)&&safe(high.readEndNs)&&high.readStartNs<=high.readEndNs&&high.readEndNs<=high.identityAfter.readStartNs,'loading ready read boundaries');
    for(const r of [after,...samples,high.identityAfter]) {
        sample(r);require(['pid','startTicks','ticksPerSecond','pageSize','backend'].every(k=>r[k]===after[k]),'same process and units');
    }
    const ids=[...high.raw.matchAll(/^Pid:\s+(\d+)\s*$/gm)],peaks=[...high.raw.matchAll(/^VmHWM:\s+(\d+)\s+kB\s*$/gm)];
    require(ids.length===1&&peaks.length===1&&[...high.raw.matchAll(/^Pid:/gm)].length===1&&
        [...high.raw.matchAll(/^VmHWM:/gm)].length===1&&Number(ids[0][1])===high.pid&&high.pid===after.pid&&high.startTicks===after.startTicks&&
        Number(peaks[0][1])*1024===high.peakRssBytes&&safe(high.peakRssBytes)&&high.peakRssBytes>0,'raw ready VmHWM identity and units');
    const inside=samples.filter(r=>start<=r.readStartNs&&r.readEndNs<=ready);require(inside.length>0,'whole sample inside loading');
    const uncertainty=2/after.ticksPerSecond;
    const cpu=[Math.max(0,Math.max(...inside.map(r=>r.cpuSeconds))-uncertainty),after.cpuSeconds+uncertainty];
    const rss=[Math.max(...inside.map(r=>r.rssBytes)),high.peakRssBytes];
    require(cpu.every(Number.isFinite)&&cpu[0]<=cpu[1]&&rss[0]>0&&rss[0]<=rss[1],'loading resource intervals');
    return {wallMs:(ready-start)/1e6,cpuBoundsSeconds:cpu,rssBoundsBytes:rss,cpuQuantizationSeconds:uncertainty,
        insideSamples:inside.length,excludedSamples:samples.length-inside.length,cpuReadAfterReadyNs:after.readEndNs-ready,rssReadAfterReadyNs:high.readEndNs-ready};
}
function contained(root,name) {
    require(typeof name==='string'&&!path.isAbsolute(name),'artifact-relative loading evidence');
    const p=path.resolve(root,name);require(p.startsWith(path.resolve(root)+path.sep),'loading evidence outside artifact root');return p;
}
export function compareLoading(planFile,directory) {
    const plan=read(planFile),digest=sha(planFile),root=path.join(directory,'cells');
    require(plan.schema==='graphite.jvm64-loading.plan.v1'&&plan.engine==='jvm'&&plan.operation==='loading'&&plan.graphCount===64&&
        plan.queriesIssued===0&&plan.comparisonModel===model&&same(plan.scope,scope)&&flags.every(k=>plan[k]===false),'JVM zero-query loading plan');
    require(same(plan.cells.map(c=>c.arm),[...'CABBAC'])&&new Set(plan.cells.map(c=>c.id)).size===6&&same(plan.comparisonPairs,pairs)&&
        plan.maxHeapBytes===4*1024**3&&plan.activeProcessorCount===4,'fixed bounded loading protocol');
    require(plan.arms.C.revision==='4f2ccf33b969e684972e56b5e810034e6e67c1b3'&&
        Object.values(plan.arms).every(a=>/^[a-f0-9]{40}$/.test(a.revision)&&a.serverArgv.length===7&&
            same(a.serverArgv.slice(0,4),[plan.arms.C.serverArgv[0],'-Xmx4g','-XX:ActiveProcessorCount=4','-cp'])&&
            a.serverArgv[4]===a.serverJar.path&&/^[a-f0-9]{64}$/.test(a.serverJar.sha256)&&same(a.serverArgv.slice(5),['io.johnsonlee.graphite.cli.MainKt','serve'])&&
            a.graphs.length===64&&new Set(a.graphs.map(g=>g.id)).size===64),'exact JVM runtime, heap and graph scope');
    const audit=read(path.join(directory,'audit.json')),executionFile=path.join(root,'execution.json'),execution=read(executionFile);
    require(audit.schema==='graphite.jvm64-loading.audit.v1'&&audit.status==='PASS_RAW_ZERO_QUERY_LOADING_AUDIT'&&audit.planSha256===digest&&
        audit.executionSha256===sha(executionFile)&&same(audit.correctedComparability,plan.producers)&&same(audit.scope,scope)&&audit.comparisonModel===model&&
        flags.every(k=>audit[k]===false)&&audit.rows.length===6,'bound independent loading audit');
    require(execution.schema==='graphite.jvm64-loading.execution.v1'&&execution.status==='PASS_ALL_SIX_ZERO_QUERY_READY64'&&
        execution.planSha256===digest&&same(execution.errors,[])&&same(execution.unissued,[])&&flags.every(k=>execution[k]===false)&&
        same(execution.cells.map(r=>r.cell),plan.cells),'all actual loading attempts');
    const groups=[];
    const rows=plan.cells.map((cell,i)=>{
        const arm=plan.arms[cell.arm],a=audit.rows[i],dir=contained(root,cell.id),r=read(path.join(dir,'result.json'));
        require(same(a.cell,cell)&&a.revision===arm.revision&&same(a.serverJar,arm.serverJar)&&r.schema==='graphite.jvm64-loading.cell.v1'&&
            r.status===execution.cells[i].status&&r.status==='PASS_ZERO_QUERY_READY64'&&r.engine==='jvm'&&r.operation==='loading'&&
            r.graphCount===64&&r.queriesIssued===0&&same(r.cell,cell)&&r.planSha256===digest&&same(r.errors,[])&&flags.every(k=>r[k]===false)&&
            execution.cells[i].resultSha256===sha(path.join(dir,'result.json')),'actual zero-query cell identity');
        const required=['result.json','command.json','owner.json','process-identity.json','server.pid','readiness.body','resources.jsonl','time-v.log','stdout.log','stderr.log','identities-before.json','identities-after.json'];
        require(required.every(n=>`${cell.id}/${n}` in a.rawPins),'complete raw loading evidence');
        for(const [name,h] of Object.entries(a.rawPins))require(sha(contained(root,name))===h,'raw loading digest '+name);
        require(['oracle','warmup','pressure','bodies','requests.jsonl'].every(n=>!fs.existsSync(path.join(dir,n))),'loading cannot include query stages');
        const command=read(path.join(dir,'command.json')),owner=read(path.join(dir,'owner.json')),published=read(path.join(dir,'process-identity.json'));
        const at=command.indexOf('--'),server=command.slice(at+1),data=server[server.indexOf('--data')+1],original=path.dirname(data);
        const graphs=arm.graphs.flatMap(g=>['--graph',`${g.id}:${g.path}`]);
        require(at===9&&same(command.slice(0,5),['/usr/bin/time','-v','-o',`${original}/time-v.log`,plan.python])&&
            command[5].endsWith('/multigraph_pressure.py')&&command[6]==='_exec-server'&&command[7]==='--pid-file'&&command[8]===`${original}/server.pid`&&
            same(server,[...arm.serverArgv,'--data',data,'--port',String(cell.port),'--load-mode','MAPPED','--max-concurrent-cypher','4',
                '--cypher-max-timeout-ms','240000','--metrics',...graphs]),'exact direct zero-query JVM command');
        const ready=read(path.join(dir,'readiness.body'));require(ready.data===data,'readiness data root');delete ready.data;
        require(Array.isArray(ready.graphs)&&ready.graphs.every(g=>typeof g.loadedAt==='string'&&Number.isFinite(Date.parse(g.loadedAt))),'complete readiness timestamps');
        for(const g of ready.graphs)delete g.loadedAt;
        require(same(ready,arm.readiness.expected),'complete persisted readiness oracle');
        require(same(read(path.join(dir,'identities-before.json')),read(path.join(dir,'identities-after.json')))&&
            same(read(path.join(dir,'identities-before.json')),{pins:plan.pins}),'unchanged loading inputs');
        const pid=Number(fs.readFileSync(path.join(dir,'server.pid'),'utf8'));sample(published.initialSample);
        require(pid===published.pid&&pid===r.cpuAfterReady.pid&&pid===published.initialSample.pid&&published.startTicks===r.cpuAfterReady.startTicks&&
            published.initialSample.startTicks===published.startTicks&&r.startNs<=published.initialSample.readStartNs&&published.initialSample.readEndNs<=r.readyNs&&
            owner.group===published.group&&owner.group===r.cleanup.group&&safe(owner.group)&&owner.group>1&&owner.group!==owner.runnerPid&&
            same(r.cleanup.after,[])&&same(r.cleanup.errors,[])&&[0,143,-15].includes(r.cleanup.exit)&&r.cleanup.signals.every(s=>!s.signal.includes('KILL')),'live process identity and owned cleanup');
        groups.push(owner.group);
        const samples=fs.readFileSync(path.join(dir,'resources.jsonl'),'utf8').trim().split('\n').map(JSON.parse),resources=loadingResources(r,samples);
        require(same(resources,r.resources)&&same(resources,a.resources),'independently reconstructed loading resources');
        const life=fs.readFileSync(path.join(dir,'time-v.log'),'utf8'),peaks=[...life.matchAll(/^\s*Maximum resident set size \(kbytes\):\s*(\d+)\s*$/gm)];
        require(peaks.length===1&&safe(Number(peaks[0][1])*1024)&&Number(peaks[0][1])*1024===r.lifecycle.peakRssBytes&&
            r.lifecycle.rawSha256===sha(path.join(dir,'time-v.log'))&&r.lifecycle.peakRssBytes>=r.readyWatermark.peakRssBytes,'lifetime peak only corroborates ready watermark');
        return resources;
    });
    require(new Set(groups).size===6,'six independent JVM processes');
    const comparisons=Object.entries(pairs).flatMap(([kind,ps])=>ps.map(([b,c],direction)=>{
        const base=rows[b],candidate=rows[c];
        const resource=key=>({base:base[key],candidate:candidate[key],absoluteDeltaBounds:[candidate[key][0]-base[key][1],candidate[key][1]-base[key][0]],
            status:resourceBound(...base[key],...candidate[key])});
        return {kind,direction,base:plan.cells[b].id,candidate:plan.cells[c].id,baseWallMs:base.wallMs,candidateWallMs:candidate.wallMs,
            deltaWallMs:candidate.wallMs-base.wallMs,cpu:resource('cpuBoundsSeconds'),rss:resource('rssBoundsBytes')};}));
    const passed=comparisons.filter(c=>c.kind==='acceptedBaseline').every(c=>c.deltaWallMs<=0&&c.cpu.status==='PASS'&&c.rss.status==='PASS');
    return {schema:'graphite.jvm64-loading.comparison.v1',engine:'jvm',operation:'loading',passed,status:passed?'PASS':'FAIL',planSha256:digest,comparisons,
        acceptedBaselineRevision:plan.arms.C.revision,parentRevision:plan.arms.A.revision,candidateRevision:plan.arms.B.revision,
        completeSemanticEquivalence:false,strictEquivalence:false,sourceToDeclarationCompletenessClaim:false,otherOperationsEligible:false,
        limitation:'Registry-ready, zero queries; ready VmHWM is a conservative post-ready bound. No Native loading exception, first-query, cold-cache or saturation claim.'};
}
function main(argv) {
    const [plan,dir,prefix]=argv;require(plan&&dir&&prefix,'plan directory prefix');const value=compareLoading(plan,dir);
    value.evidence={plan:path.relative(path.dirname(prefix),plan),directory:path.relative(path.dirname(prefix),dir)};
    fs.mkdirSync(path.dirname(prefix),{recursive:true});fs.writeFileSync(prefix+'-status.json',JSON.stringify(value,null,2)+'\n');
    const lines=['### JVM zero-query64 loading','',value.status,'','| Pair | Base wall ms | Candidate wall ms | Δ ms | CPU bounds s | RSS bounds bytes |','|---|---:|---:|---:|---|---|'];
    for(const c of value.comparisons)lines.push(`| ${c.kind} ${c.base} → ${c.candidate} | ${c.baseWallMs} | ${c.candidateWallMs} | ${c.deltaWallMs} | ${c.cpu.base.join('–')} → ${c.cpu.candidate.join('–')} (${c.cpu.status}) | ${c.rss.base.join('–')} → ${c.rss.candidate.join('–')} (${c.rss.status}) |`);
    lines.push('',value.limitation);fs.writeFileSync(prefix+'-report.md',lines.join('\n')+'\n');if(!value.passed)process.exitCode=1;
}
if(process.argv[1]&&fs.realpathSync(process.argv[1])===fs.realpathSync(fileURLToPath(import.meta.url)))main(process.argv.slice(2));
