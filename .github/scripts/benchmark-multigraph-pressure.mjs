#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
const require = (yes, message) => {if (!yes) throw new Error(message);};
const sha = p => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const read = p => JSON.parse(fs.readFileSync(p,'utf8'));
const same = (a,b) => JSON.stringify(a) === JSON.stringify(b);
const finite = n => typeof n === 'number' && Number.isFinite(n) && n >= 0;
const sorted = value => Array.isArray(value) ? value.map(sorted) : value && typeof value === 'object' ?
    Object.fromEntries(Object.keys(value).sort().map(key => [key, sorted(value[key])])) : value;
const equal = (a,b) => JSON.stringify(sorted(a)) === JSON.stringify(sorted(b));
// The production formatter tests prove the rank-preserving renderer this feature introduced: a
// pair whose actual writer holds the accepted flat renderer (a rebased PR's parent arm) has none
// to verify, and its audit must say so rather than claim them.
const formatterTestsConsistent = audit => {
    const kinds=audit.formatterKinds, applicable=kinds?.B==='rank-preserving';
    return kinds?.C==='legacy-flat' && ['legacy-flat','rank-preserving'].includes(kinds?.B) &&
        audit.productionFormatterTestsApplicable===applicable && audit.productionFormatterTestsVerified===applicable;
};

// The Python finalizer replays the full raw proof. The portable report retains
// its original metadata bytes so the aggregate can check the exact join again
// without downloading graph/runtime payloads or changing the measured plan.
export function verifyComparabilityJoin(planFile,directory,plan,receiptFile,inputs) {
    const jvm='pendingJvmComparabilityAuthority' in plan;
    const pending=jvm || 'pendingComparabilityAuthority' in plan || plan.comparabilityPending===true;
    const producerRef=jvm?plan.pendingJvmComparabilityAuthority:plan.pendingComparabilityAuthority;
    if (!pending) {require(!receiptFile,'Unexpected comparability receipt');return true;}
    require(plan.engine===(jvm?'jvm':'native') && plan.comparabilityPending===true && producerRef &&
        !plan.correctedProducerAuthority && !plan.jvmProducerAuthority &&
        (!jvm || !plan.pendingComparabilityAuthority),'Explicit pending engine comparability');
    if (!receiptFile) return false;
    const receipt=read(receiptFile), base=path.dirname(path.resolve(receiptFile));
    inputs[path.resolve(receiptFile)]=sha(receiptFile);
    require(receipt.schema===(jvm?'graphite.jvm-pressure.core-comparability-join.v1':'graphite.native-pressure.core-comparability-join.v1') &&
        receipt.status==='PASS_SAME_INPUT_COMPLETE_CORRECTED_CORE_JOIN' && receipt.coreComparabilityVerified===true &&
        ['completeSemanticEquivalence','strictEquivalence','sourceToDeclarationCompletenessClaim',
            'performanceAcceptance','otherOperationsEligible'].every(k=>receipt[k]===false),'Complete scoped core join');
    require(receipt.plan?.sha256===sha(planFile) && equal(receipt.measurementProducer,producerRef) &&
        receipt.measurementInputsSha256===receipt.measurementProducer.sha256,
        'Join must bind the original measured plan and producer');
    const pinned=(ref,actual)=>{
        require(ref && typeof ref.path==='string' && receipt.pins?.[ref.path]===ref.sha256 &&
            sha(actual)===ref.sha256,'Join evidence hash or pin changed');
        inputs[path.resolve(actual)]=ref.sha256;return read(actual);
    };
    pinned(receipt.plan,planFile);
    require(Array.isArray(receipt.metadata) && new Set(receipt.metadata.map(r=>r.path)).size===receipt.metadata.length,
        'Unique portable join metadata');
    const metadata=ref=>{
        const row=receipt.metadata.find(r=>r.path===ref?.path && r.sha256===ref.sha256);
        require(row && typeof row.relativePath==='string' && !path.isAbsolute(row.relativePath),'Missing portable join metadata');
        const target=path.resolve(base,row.relativePath);
        require(target.startsWith(base+path.sep),'Join metadata escaped artifact root');
        return pinned(ref,target);
    };
    const original=metadata(receipt.measurementProducer), corrected=metadata(receipt.correctedProducer);
    require(original.measurementMode===(jvm?'jvm35-before-core-v1':'native39-before-core-v1') &&
        original.status===(jvm?'JVM35_RESPONSES_AUDITED_CORE_COMPARABILITY_PENDING':'ARTIFACTS_AND39_QUERIES_AUDITED_EQUIVALENCE_PENDING') &&
        corrected.status==='ARTIFACTS_QUERIES_AND_CORRECTED_CORE_COMPARABILITY_AUDITED' &&
        equal(original.arms,corrected.arms) && equal(original.sourceInputs,corrected.sourceInputs) &&
        equal(original.armDirectories,corrected.armDirectories) &&
        (!jvm || (original.schema==='graphite.jvm-pressure.producers.v1' && corrected.schema===original.schema &&
          equal(original.correctnessRoots,corrected.correctnessRoots) && plan.cases.length===35)) && equal(corrected.missingAuthority,[]) &&
        equal(receipt.coreComparisons,corrected.correctedComparisons),'Same producer inputs and complete corrected core');
    for (const arm of ['C','A','B']) {
        require(['revision','sourceManifest','runtimeManifest','fixtureManifest','graphs','serverArgv','runtimeFiles'].every(
            key=>equal(original.arms[arm][key],plan.arms[arm][key])),'Measured arm differs from joined producer');
    }
    const execution=pinned(receipt.measurementExecution,path.join(directory,'execution.json'));
    require(execution.status==='MEASURED_CORE_COMPARABILITY_PENDING' && execution.performanceAcceptance===false &&
        equal(execution.plan,receipt.plan) && equal(execution.errors,[]) && equal(execution.unissued,[]) &&
        execution.cells.length===6 && execution.cells.every((c,i)=>c.id===plan.cells[i].id && c.status==='PASS'),
        'Complete original pending measurement execution');
    require(receipt.cells?.length===6,'Join requires all six original cells');
    for (const [i,cell] of plan.cells.entries()) {
        const row=receipt.cells[i];require(row.id===cell.id,'Join cell order');
        for (const kind of ['result','audit']) {
            require(row[kind].path===path.join(path.dirname(receipt.measurementExecution.path),cell.id,kind+'.json'),
                'Original cell evidence path');
            pinned(row[kind],path.join(directory,cell.id,kind+'.json'));
        }
    }
    const alias=['revision','sourceManifest','runtimeManifest','fixtureManifest','graphs'].every(
        key=>equal(original.arms.A[key],original.arms.C[key]));
    require(equal(Object.keys(receipt.coreComparisons).sort(),alias?['B']:['A','B']),'Every nonaccepted arm needs core proof');
    for (const [arm,comparison] of Object.entries(receipt.coreComparisons)) {
        const audit=metadata(comparison.audit), record=metadata(audit.record), corePlan=metadata(record.plan);
        const revisions={C:plan.arms.C.revision,B:plan.arms[arm].revision};
        const fixtures={C:plan.arms.C.fixtureManifest,B:plan.arms[arm].fixtureManifest};
        require(audit.status==='PASS_INDEPENDENT_ALL64_CORE_TOPOLOGY_EXECUTION_AUDIT' &&
            audit.completeCoreTopologyIndexComparison===true && formatterTestsConsistent(audit) &&
            audit.phases===139 && audit.graphs?.length===64 &&
            equal(audit.revisions,revisions) && equal(audit.fixtureManifests,fixtures) &&
            equal(comparison.revisions,revisions) && equal(comparison.fixtureManifests,fixtures) &&
            equal(corePlan.revisions,revisions) && equal(corePlan.fixtureManifests,fixtures) &&
            corePlan.maxOwnedPhases===139 && corePlan.graphs?.length===64 && record.phases?.length===139,
            'Matching successful complete core execution');
        require(record.status==='PASS_ALL64_CORE_WITH_EXPLICIT_TYPE_OVERLOAD_SYNTHETIC_INHERITED_FIELD_CORRECTIONS_TOPOLOGY_INDICES_ADDITIVE_DECLARATION_VALIDITY' &&
            equal(record.errors,[]) && record.finalIdentity==='PASS' &&
            new Set(record.phases.map(p=>p.path)).size===139 &&
            [audit.graphs,record.graphs,corePlan.graphs].every(rows=>equal(rows?.map(g=>g.id),plan.arms.C.graphs.map(g=>g.id))),
            'Complete core graph inventory and clean final identity');
        require(audit.pins?.[audit.record.path]===audit.record.sha256 &&
            audit.pins?.[record.plan.path]===record.plan.sha256,'Core metadata closure');
        for (const phaseRef of record.phases) {
            const phase=metadata(phaseRef);
            require(audit.pins?.[phaseRef.path]===phaseRef.sha256 && phase.status==='PASS' && phase.exit===0 &&
                equal(phase.errors,[]),'Every recorded core phase succeeded');
        }
    }
    if(jvm) {
        require(equal(Object.keys(corrected.rawEdgeJoins??{}).sort(),['A','B','C']),'Complete JVM raw edge joins');
        for(const arm of ['C','A','B']) {
            const join=corrected.rawEdgeJoins[arm],producer=original.arms[arm],raw=metadata(join.rawAudit);
            require(equal(join.rawAudit,producer.rawExportAudit) && raw.schema==='graphite.jvm-raw-edge-export-audit.v1' &&
                raw.status==='PASS_ALL64_RAW_EDGE_OWNED_EXECUTION_AUDIT' && raw.completeCoreTopologyIndexComparison===false &&
                raw.performanceAcceptance===false && raw.graphs?.length===64 && join.graphs?.length===64,
                'Complete independently owned JVM raw export');
            for(const key of ['sourceManifest','runtimeManifest','fixtureManifest','artifactAudit'])
                require(equal(raw[key],producer[key]),'Raw export actual producer binding');
            const coreArm=arm==='C'||(arm==='A'&&alias)?'B':arm,side=arm==='C'||(arm==='A'&&alias)?'reference':'actual';
            const coreAudit=metadata(corrected.correctedComparisons[coreArm].audit);
            for(const name of ['C',coreArm])for(const key of ['sourceManifest','runtimeManifest','fixtureManifest','artifactAudit']) {
                const ref=original.arms[name][key];require(coreAudit.pins?.[ref.path]===ref.sha256,'JVM core producer identity pin');
            }
            for(let i=0;i<64;i++) {
                const row=join.graphs[i],exported=raw.graphs[i],coreGraph=coreAudit.graphs[i];
                require(row.id===plan.arms[arm].graphs[i].id && row.id===exported.id && row.id===coreGraph.id &&
                    row.coreArm===coreArm && row.coreSide===side && equal(row.rawReceipt,exported.receipt) &&
                    equal(row.topology,coreGraph.topology),'Same ordered64 raw/core edge receipts');
                const rawEdge=metadata(row.rawReceipt),topology=metadata(row.topology),edge=topology.rawEdgeExports?.[side];
                require(equal(rawEdge,exported.edges) && raw.pins?.[row.rawReceipt.path]===row.rawReceipt.sha256 &&
                    coreAudit.pins?.[row.topology.path]===row.topology.sha256 && edge &&
                    ['graphRoot','sourceInputs','nodeSlots','labeledEdges','mappingApplied'].every(k=>equal(edge[k],rawEdge[k])) &&
                    rawEdge.mappingApplied===false && edge.output.sha256===rawEdge.output.sha256 &&
                    row.outputSha256===rawEdge.output.sha256 && raw.pins?.[rawEdge.output.path]===rawEdge.output.sha256 &&
                    coreAudit.pins?.[edge.output.path]===edge.output.sha256,'Full original-ID raw edges bound to complete core');
            }
        }
    }
    return true;
}
// User-approved query latency tolerance (2026-10-12): a case's P50 or P95 is accepted when the
// candidate is within 100ms or within 5% of the base, whichever is larger. Repeated-request
// quantiles of a 50ms case move by that much between runs; a slower case must regress by both.
export const LATENCY_TOLERANCE_NS=100e6;
export const LATENCY_TOLERANCE_RATIO=0.05;
export function withinLatencyTolerance(candidateNs,baseNs){
    return candidateNs<=baseNs+Math.max(LATENCY_TOLERANCE_NS,baseNs*LATENCY_TOLERANCE_RATIO);
}
// User-approved tail protocol (2026-10-13). A 20-sample pressure p95 is the second-slowest sample,
// and a cheap case's pressure tail is the queue behind whichever heavy requests it collided with.
// So: (1) p50 keeps the per-pair rule; (2) p95 is judged across the run's two base cells and two
// candidate cells as intervals: FAIL only when every candidate cell is slower than every base cell
// by more than the latency tolerance, PASS when none is, INDETERMINATE between (reported, not red);
// (3) a cheap case, base pressure p50 under CHEAP_P50_NS, takes its tail from the isolated stage
// (one worker, isolatedPerCase samples) and keeps the pressure p95 only as a collision bound: at
// most twice the base's, or 250ms more.
export const ISOLATED_SCHEDULE={isolatedConcurrency:1,isolatedPerCase:40,isolatedThresholdNs:100e6};
export const CHEAP_P50_NS=50e6;
export const COLLISION_TAIL_FACTOR=2;
export const COLLISION_TAIL_NS=250e6;
export function tailLimit(baseNs){return baseNs+Math.max(LATENCY_TOLERANCE_NS,baseNs*LATENCY_TOLERANCE_RATIO);}
export function tailBound(baseLower,baseUpper,candidateLower,candidateUpper){
    require([baseLower,baseUpper,candidateLower,candidateUpper].every(finite) && baseLower<=baseUpper && candidateLower<=candidateUpper,'Invalid tail interval');
    return candidateUpper<=tailLimit(baseLower)?'PASS':candidateLower>tailLimit(baseUpper)?'FAIL':'INDETERMINATE';
}
export function nearestRank(sorted,fraction){return sorted[Math.ceil(sorted.length*fraction)-1];}
export function isolatedCases(plan,pressureStats){
    return plan.cases.filter(c=>pressureStats[c.id].p50Ns<plan.schedule.isolatedThresholdNs);
}
export function isolatedStatistics(stage,plan,pressureStats){
    const cases=isolatedCases(plan,pressureStats),per=plan.schedule.isolatedPerCase;
    require(stage.status==='PASS' && stage.allWorkersStopped && stage.unissued.length===0 && stage.journalErrors.length===0 &&
        stage.concurrency===plan.schedule.isolatedConcurrency && stage.requests.length===cases.length*per,'Incomplete isolated stage');
    const stats={};
    for(const [i,r] of stage.requests.entries()){
        require(r.sequence===i && r.caseId===cases[Math.floor(i/per)].id && r.status==='PASS' && r.httpStatus===200 &&
            r.completeBody===true && r.deadlineExpired===false && Number.isSafeInteger(r.latencyNs) && r.latencyNs>0 &&
            r.latencyNs===r.validationCompleteNs-r.startNs && r.startNs<=r.wireCompleteNs && r.wireCompleteNs<=r.validationCompleteNs,
            'Invalid complete isolated request boundary');
        (stats[r.caseId]??=[]).push(r);
    }
    return Object.fromEntries(cases.map(c=>{
        const rows=stats[c.id],sorted=rows.map(r=>r.latencyNs).sort((a,b)=>a-b);
        require(rows.length===per,'Missing isolated samples');
        return [c.id,{n:per,sampleIds:rows.map(r=>r.sequence),p50Ns:nearestRank(sorted,.5),p95Ns:nearestRank(sorted,.95)}];
    }));
}
export function tailVerdicts(plan,cells){
    const base=cells.filter(c=>c.cell.arm==='C'),candidate=cells.filter(c=>c.cell.arm==='B');
    if(!base.length||!candidate.length) return null;
    const range=(group,pick)=>{const v=group.map(pick);return [Math.min(...v),Math.max(...v)];};
    return Object.fromEntries(plan.cases.map(k=>{
        const id=k.id,[,basePressureP50Hi]=range(base,c=>c.stats[id].p50Ns),cheap=basePressureP50Hi<CHEAP_P50_NS;
        const isolated=cheap&&[...base,...candidate].every(c=>c.isolated[id]);
        const pick=c=>(isolated?c.isolated:c.stats)[id].p95Ns;
        const [baseLo,baseHi]=range(base,pick),[candLo,candHi]=range(candidate,pick);
        const verdict={caseId:id,cheap,source:isolated?'isolated':'pressure',baseP95Ms:[baseLo/1e6,baseHi/1e6],
            candidateP95Ms:[candLo/1e6,candHi/1e6],status:tailBound(baseLo,baseHi,candLo,candHi)};
        if(cheap){
            const [,pressureBaseHi]=range(base,c=>c.stats[id].p95Ns),[,pressureCandHi]=range(candidate,c=>c.stats[id].p95Ns);
            const limit=Math.max(COLLISION_TAIL_FACTOR*pressureBaseHi,pressureBaseHi+COLLISION_TAIL_NS);
            verdict.collisionBound={basePressureP95Ms:pressureBaseHi/1e6,candidatePressureP95Ms:pressureCandHi/1e6,limitMs:limit/1e6,
                status:pressureCandHi<=limit?'PASS':'FAIL'};
            if(verdict.collisionBound.status==='FAIL') verdict.status='FAIL';
        }
        return [id,verdict];
    }));
}
export function tailSummary(tails){
    const summary={PASS:0,INDETERMINATE:0,FAIL:0};
    for(const t of Object.values(tails??{})) summary[t.status]++;
    return summary;
}
// Cold acceptance. The first request of every case (the oracle stage, concurrency 4) is the only
// cold measurement the run makes: work a reader defers from loading to first use (a declared type
// table read on its first access) lands here and nowhere else, since the loading gate ends at
// registry-ready and warmup and pressure run after it. The cold latency of every case and the
// oracle stage's wall are judged like the tails, as intervals across the run's two base cells and
// two candidate cells with the latency tolerance: FAIL blocks, INDETERMINATE is reported.
export function coldStatistics(stage,plan){
    require(stage!==undefined && stage!==null,'Missing oracle stage');
    require(stage.status==='PASS' && stage.allWorkersStopped && stage.unissued.length===0 && stage.journalErrors.length===0 &&
        stage.requests.length===plan.cases.length && Number.isSafeInteger(stage.wallNs) && stage.wallNs>0,'Incomplete oracle stage');
    const latencyNs={};
    for(const [i,r] of stage.requests.entries()){
        require(r.sequence===i && r.caseId===plan.cases[i].id && r.status==='PASS' && r.httpStatus===200 && r.completeBody===true &&
            r.deadlineExpired===false && Number.isSafeInteger(r.latencyNs) && r.latencyNs>0 && r.latencyNs===r.validationCompleteNs-r.startNs &&
            r.startNs<=r.wireCompleteNs && r.wireCompleteNs<=r.validationCompleteNs,'Invalid complete cold request boundary');
        latencyNs[r.caseId]=r.latencyNs;
    }
    return {latencyNs,wallNs:stage.wallNs};
}
export function coldVerdicts(plan,cells){
    const base=cells.filter(c=>c.cell.arm==='C'),candidate=cells.filter(c=>c.cell.arm==='B');
    if(!base.length||!candidate.length) return null;
    const range=(group,pick)=>{const v=group.map(pick);return [Math.min(...v),Math.max(...v)];};
    const interval=(pick)=>{
        const [baseLo,baseHi]=range(base,pick),[candLo,candHi]=range(candidate,pick);
        return {baseMs:[baseLo/1e6,baseHi/1e6],candidateMs:[candLo/1e6,candHi/1e6],status:tailBound(baseLo,baseHi,candLo,candHi)};
    };
    const cases=Object.fromEntries(plan.cases.map(k=>[k.id,{caseId:k.id,source:'oracle',...interval(c=>c.cold.latencyNs[k.id])}]));
    return {cases,stageWall:{source:'oracle',...interval(c=>c.cold.wallNs)}};
}
export function coldSummary(cold){
    const summary={PASS:0,INDETERMINATE:0,FAIL:0};
    for(const c of Object.values(cold?.cases??{})) summary[c.status]++;
    return summary;
}
// User-approved resource tolerances (2026-10-12): construction and loading wall time may exceed
// the base by 5%, total CPU time and peak RSS by 10%, in every fixture64 gate. The pressure
// stage's own wall time stays strict. Each limit is independent of the others.
export const WALL_TOLERANCE_RATIO=0.05;
export const CPU_TOLERANCE_RATIO=0.10;
export const RSS_TOLERANCE_RATIO=0.10;
export const RESOURCE_TOLERANCE={wallRatio:WALL_TOLERANCE_RATIO,cpuRatio:CPU_TOLERANCE_RATIO,rssRatio:RSS_TOLERANCE_RATIO};
export function withinWallTolerance(candidateMs,baseMs){
    return candidateMs<=baseMs*(1+WALL_TOLERANCE_RATIO);
}
export function resourceBound(baseLower,baseUpper,candidateLower,candidateUpper,ratio=CPU_TOLERANCE_RATIO) {
    require([baseLower,baseUpper,candidateLower,candidateUpper,ratio].every(finite) && ratio>=0 &&
        baseLower <= baseUpper && candidateLower <= candidateUpper,'Invalid resource interval');
    const limit=1+ratio;
    return candidateUpper <= baseLower*limit ? 'PASS' : candidateLower > baseUpper*limit ? 'FAIL' : 'INDETERMINATE';
}
function rssSemantics(rss) {
    if (!('measurementSemantics' in rss)) return 'legacy-rss-interval';
    require(rss.measurementSemantics==='linux-rss-observed-envelope-v1','Unknown RSS measurement semantics');
    const {lifecyclePeakBytes:time,lifecycleSampledPeakBytes:sampled,sampledMinusLifecycleBytes:delta}=rss;
    require([time,sampled,rss.lowerBoundBytes,rss.upperBoundBytes].every(n=>Number.isSafeInteger(n)&&n>=0) &&
        time>0 && sampled>0 && Number.isSafeInteger(delta) && delta===sampled-time &&
        rss.lowerBoundBytes<=sampled && rss.upperBoundBytes===Math.max(time,sampled),'Invalid Linux RSS observed envelope');
    return rss.measurementSemantics;
}
export function requestStatistics(stage,cases) {
    require(stage.status === 'PASS' && stage.allWorkersStopped && stage.unissued.length === 0 &&
        stage.journalErrors.length === 0 && stage.requests.length === cases.length*20,'Incomplete pressure stage');
    const stats = {};
    for (const [i,r] of stage.requests.entries()) {
        require(r.sequence===i && r.caseId===cases[i%cases.length].id && r.status==='PASS' &&
            r.httpStatus===200 && r.completeBody===true && r.deadlineExpired===false &&
            Number.isSafeInteger(r.latencyNs) && r.latencyNs>0 && r.latencyNs===r.validationCompleteNs-r.startNs &&
            r.startNs<=r.wireCompleteNs && r.wireCompleteNs<=r.validationCompleteNs,'Invalid complete request boundary');
        (stats[r.caseId]??=[]).push(r);
    }
    return Object.fromEntries(cases.map(c=>{
        const rows=stats[c.id]; const sorted=rows.map(r=>r.latencyNs).sort((a,b)=>a-b);
        require(rows.length===20,'Missing repeated samples');
        return [c.id,{n:20,sampleIds:rows.map(r=>r.sequence),p50Ns:sorted[9],p95Ns:sorted[18]}];
    }));
}
export function comparePressure(planFile,directory,comparabilityReceipt=null) {
    return compareCompletedCells(planFile,directory,comparabilityReceipt,null);
}
export function pressureProgress(planFile,directory,completedCells) {
    require(completedCells===3 || completedCells===5,'Progress requires a completed fixed comparison pair');
    return compareCompletedCells(planFile,directory,null,completedCells);
}
function compareCompletedCells(planFile,directory,comparabilityReceipt,completedCells) {
    const plan=read(planFile), planSha256=sha(planFile);
    require(plan.schema==='graphite.multigraph-pressure.plan.v1' && plan.operation==='query' && ['native','jvm'].includes(plan.engine),'Plan schema');
    require(same(plan.cells.map(c=>c.arm),['C','A','B','B','A','C']) && new Set(plan.cells.map(c=>c.id)).size===6,'Fixed CABBAC');
    require(same(plan.comparisonPairs,{parent:[[1,2],[4,3]],acceptedBaseline:[[0,2],[5,3]]}),'Fixed pairs');
    require(plan.schedule.concurrency===4 && plan.schedule.warmupPerCase===2 && plan.schedule.measuredPerCase===20 &&
        Object.entries(ISOLATED_SCHEDULE).every(([k,v])=>plan.schedule[k]===v),'Fixed sampling');
    require(plan.cases.length>0 && plan.cases.every(c=>new Set(c.targetGraphIds).size>1),'Actual multi-graph request scopes');
    const inputs={[path.resolve(planFile)]:planSha256};
    const provisional=completedCells!==null;
    const comparabilityVerified=provisional?false:verifyComparabilityJoin(planFile,directory,plan,comparabilityReceipt,inputs);
    const cells=plan.cells.slice(0,completedCells??6).map(cell=>{
        const root=path.join(directory,cell.id), resultFile=path.join(root,'result.json'), auditFile=path.join(root,'audit.json');
        const result=read(resultFile),audit=read(auditFile);
        inputs[path.resolve(resultFile)]=sha(resultFile);inputs[path.resolve(auditFile)]=sha(auditFile);
        require(audit.schema==='graphite.multigraph-pressure.audit.v1' && audit.status==='PASS' && audit.cell===cell.id &&
            audit.resultSha256===sha(resultFile) && audit.planSha256===planSha256 && audit.queryEvidenceComplete===true &&
            audit.otherOperationsEligible===false,'Bound full raw audit');
        require(result.schema==='graphite.multigraph-pressure.result.v1' && result.status==='PASS' && result.errors.length===0 &&
            same(result.cell,cell) && same(result.arm,plan.arms[cell.arm]) && result.engine===plan.engine &&
            result.operation==='query' && result.planSha256===planSha256 && same(result.coverage,plan.coverage) &&
            same(audit.coverage,plan.coverage),'Result identity');
        const stats=requestStatistics(result.stages.pressure,plan.cases);
        require(same(stats,result.stages.pressure.caseStatistics),'Recomputed nearest-rank per-case statistics');
        const isolated=isolatedStatistics(result.stages.isolated,plan,stats);
        require(same(isolated,result.stages.isolated.caseStatistics),'Recomputed isolated per-case statistics');
        const cold=coldStatistics(result.stages.oracle,plan);
        require(audit.completeBodies===plan.cases.length*23+isolatedCases(plan,stats).length*plan.schedule.isolatedPerCase,'Bound full raw audit body count');
        require(same(result.cleanup.after,[]) && same(result.cleanup.errors,[]) &&
            result.cleanup.signals.every(s=>!s.signal.includes('KILL')),'Owned cleanup');
        return {cell,stats,isolated,cold,stage:result.stages.pressure,queryEvidenceComplete:true,
            rssSemantics:rssSemantics(result.stages.pressure.resources.rss)};
    });
    require(new Set(cells.map(c=>c.rssSemantics)).size===1,'Mixed RSS measurement semantics');
    const comparisons=Object.entries(plan.comparisonPairs).flatMap(([kind,pairs])=>pairs.map(([b,c],direction)=>{
        if (b>=cells.length || c>=cells.length) return null;
        const base=cells[b],candidate=cells[c],br=base.stage.resources,cr=candidate.stage.resources;
        const cases=plan.cases.map(k=>{
            const a=base.stats[k.id],z=candidate.stats[k.id];
            return {caseId:k.id,baseP50Ms:a.p50Ns/1e6,candidateP50Ms:z.p50Ns/1e6,deltaP50Ms:(z.p50Ns-a.p50Ns)/1e6,
                baseP95Ms:a.p95Ns/1e6,candidateP95Ms:z.p95Ns/1e6,deltaP95Ms:(z.p95Ns-a.p95Ns)/1e6,
                p50Passed:withinLatencyTolerance(z.p50Ns,a.p50Ns),p95Passed:withinLatencyTolerance(z.p95Ns,a.p95Ns)};
        });
        return {kind,direction,base:base.cell.id,candidate:candidate.cell.id,cases,
            baseWallMs:base.stage.wallNs/1e6,candidateWallMs:candidate.stage.wallNs/1e6,
            deltaWallMs:(candidate.stage.wallNs-base.stage.wallNs)/1e6,
            cpu:{baseLower:br.cpuLowerBoundSeconds,baseUpper:br.cpuUpperBoundSeconds,candidateLower:cr.cpuLowerBoundSeconds,candidateUpper:cr.cpuUpperBoundSeconds,
                status:resourceBound(br.cpuLowerBoundSeconds,br.cpuUpperBoundSeconds,cr.cpuLowerBoundSeconds,cr.cpuUpperBoundSeconds,CPU_TOLERANCE_RATIO)},
            rss:{measurementSemantics:base.rssSemantics,baseLower:br.rss.lowerBoundBytes,baseUpper:br.rss.upperBoundBytes,candidateLower:cr.rss.lowerBoundBytes,candidateUpper:cr.rss.upperBoundBytes,
                status:resourceBound(br.rss.lowerBoundBytes,br.rss.upperBoundBytes,cr.rss.lowerBoundBytes,cr.rss.upperBoundBytes,RSS_TOLERANCE_RATIO)}};
    }).filter(Boolean));
    const tails=tailVerdicts(plan,cells),cold=coldVerdicts(plan,cells);
    const tailRule={cheapP50Ms:CHEAP_P50_NS/1e6,isolated:ISOLATED_SCHEDULE,collisionFactor:COLLISION_TAIL_FACTOR,collisionMs:COLLISION_TAIL_NS/1e6,
        interval:'p95 FAIL only when every candidate cell exceeds every base cell by more than the latency tolerance; INDETERMINATE is reported, not red',
        cold:'the first request of every case (oracle stage) and that stage\'s wall, as the same base/candidate cell intervals with the latency tolerance: work deferred from loading to first use is measured here; FAIL blocks, INDETERMINATE is reported'};
    if (provisional) return {
        schema:'graphite.multigraph-pressure.progress.v1',status:'PROVISIONAL',
        scope:'multi-graph-pressure',engine:plan.engine,operation:'query',planSha256,
        completedCells:cells.map(c=>c.cell.id),comparisons,tailRule,tails,tailSummary:tailSummary(tails),cold,coldSummary:coldSummary(cold),inputs,
        passed:false,performanceAcceptance:false,queryEvidenceComplete:false,otherOperationsEligible:false,
        limitation:'Completed fixed pairs only. All six cells and complete correctness evidence remain required; this report cannot establish acceptance.'
    };
    const coverage=plan.coverage;
    require(Array.isArray(coverage.unavailableFamilies),'Query coverage');
    const coverageComplete=coverage.unavailableFamilies.length===0 && coverage.requiredFamilies.length>0 &&
        coverage.requiredFamilies.every(f=>coverage.coveredFamilies.includes(f));
    const accepted= comparisons.filter(c=>c.kind==='acceptedBaseline');
    require(tails!==null && cold!==null,'Tail and cold verdicts need both base cells and both candidate cells');
    const measuredConstraintsPassed=accepted.every(c=>c.cases.every(k=>k.p50Passed)&&c.cpu.status==='PASS'&&c.rss.status==='PASS'&&c.deltaWallMs<=0) &&
        Object.values(tails).every(t=>t.status!=='FAIL') &&
        Object.values(cold.cases).every(c=>c.status!=='FAIL') && cold.stageWall.status!=='FAIL';
    return {schema:'graphite.multigraph-pressure.comparison.v1',scope:'multi-graph-pressure',engine:plan.engine,operation:'query',
        latencyTolerance:{absoluteMs:LATENCY_TOLERANCE_NS/1e6,ratio:LATENCY_TOLERANCE_RATIO},resourceTolerance:{cpuRatio:CPU_TOLERANCE_RATIO,rssRatio:RSS_TOLERANCE_RATIO},
        tailRule,tails,tailSummary:tailSummary(tails),cold,coldSummary:coldSummary(cold),
        planSha256,passed:comparabilityVerified&&coverageComplete&&measuredConstraintsPassed,
        status:!comparabilityVerified?'MEASURED_CORE_COMPARABILITY_PENDING':!coverageComplete?'UNAVAILABLE':measuredConstraintsPassed?'PASS':'FAIL',
        queryEvidenceComplete:true,coverageComplete,coverage,measuredConstraintsPassed,comparisons,inputs,
        ...(('pendingComparabilityAuthority' in plan || 'pendingJvmComparabilityAuthority' in plan)?{comparabilityPending:!comparabilityVerified,coreComparabilityVerified:comparabilityVerified}:{}),
        acceptedBaselineRevision:plan.arms.C.revision,parentRevision:plan.arms.A.revision,candidateRevision:plan.arms.B.revision,
        otherOperationsEligible:false,limitation:'Two fixed directions; p50 per pair, p95 as base/candidate cell intervals with cheap cases measured in isolation (user-approved 2026-10-13), the first request of every case and the oracle stage wall as the cold intervals; no pooling, quiet-host or saturation claim. RSS observation envelopes may leave query RSS indeterminate; Linux kernel counters are approximate, not mathematical physical-memory bounds.'};
}
function main(argv) {
    const [plan,dir,prefix,flag,receipt]=argv;
    require(plan&&dir&&prefix,'plan.json cell-directory output-prefix');
    require(argv.length===3 || (argv.length===5 && ['--comparability-receipt','--progress'].includes(flag) && receipt),
        'Optional --comparability-receipt file or --progress completed-cell-count');
    const provisional=flag==='--progress';
    let result;
    try {result=provisional?pressureProgress(plan,dir,Number(receipt)):comparePressure(plan,dir,receipt);
        result.evidence={plan:path.relative(path.dirname(prefix),plan),directory:path.relative(path.dirname(prefix),dir),
        ...(!provisional&&receipt?{comparabilityReceipt:path.relative(path.dirname(prefix),receipt)}:{})};
    } catch(e) {result={schema:'graphite.multigraph-pressure.comparison.v1',passed:false,status:'FAIL',errors:[e.message]};}
    fs.mkdirSync(path.dirname(prefix),{recursive:true});
    fs.writeFileSync(prefix+'-status.json',JSON.stringify(result,null,2)+'\n');
    const lines=['### Multi-graph continuous c4 pressure','',result.status,''];
    for(const c of result.comparisons??[]) {
        lines.push(`${c.kind} ${c.base} → ${c.candidate}: CPU ${c.cpu.status}; RSS ${c.rss.status}.`,'',
            '| Case | Base p50 ms | Candidate p50 ms | Δ ms | Base p95 ms | Candidate p95 ms | Δ ms |',
            '|---|---:|---:|---:|---:|---:|---:|');
        for(const k of c.cases) lines.push(`| ${k.caseId} | ${k.baseP50Ms.toFixed(3)} | ${k.candidateP50Ms.toFixed(3)} | ${k.deltaP50Ms.toFixed(3)} | ${k.baseP95Ms.toFixed(3)} | ${k.candidateP95Ms.toFixed(3)} | ${k.deltaP95Ms.toFixed(3)} |`);
        lines.push('');
    }
    if(result.tails){
        const s=result.tailSummary;lines.push(`Tails: ${s.PASS} PASS, ${s.INDETERMINATE} INDETERMINATE, ${s.FAIL} FAIL (cheap cases from the isolated stage).`,'');
        const shown=Object.values(result.tails).filter(t=>t.status!=='PASS');
        if(shown.length){
            lines.push('| Case | Source | Base p95 ms | Candidate p95 ms | Tail | Collision bound |','|---|---|---|---|---|---|');
            for(const t of shown) lines.push(`| ${t.caseId} | ${t.source} | ${t.baseP95Ms.map(v=>v.toFixed(3)).join('–')} | ${t.candidateP95Ms.map(v=>v.toFixed(3)).join('–')} | ${t.status} | ${t.collisionBound?`${t.collisionBound.status} (≤ ${t.collisionBound.limitMs.toFixed(0)} ms)`:'n/a'} |`);
            lines.push('');
        }
    }
    if(result.cold){
        const s=result.coldSummary,w=result.cold.stageWall,ms=v=>v.map(x=>x.toFixed(3)).join('–');
        lines.push(`Cold (first request per case, oracle stage): ${s.PASS} PASS, ${s.INDETERMINATE} INDETERMINATE, ${s.FAIL} FAIL; stage wall base ${ms(w.baseMs)} ms, candidate ${ms(w.candidateMs)} ms: ${w.status}.`,'');
        const shown=Object.values(result.cold.cases).filter(c=>c.status!=='PASS');
        if(shown.length){
            lines.push('| Case | Source | Base cold ms | Candidate cold ms | Cold |','|---|---|---|---|---|');
            for(const c of shown) lines.push(`| ${c.caseId} | ${c.source} | ${ms(c.baseMs)} | ${ms(c.candidateMs)} | ${c.status} |`);
            lines.push('');
        }
    }
    lines.push(...(result.errors??[]),result.limitation??'');
    fs.writeFileSync(prefix+'-report.md',lines.join('\n')+'\n');
    if(!result.passed && !(provisional&&result.status==='PROVISIONAL'))process.exitCode=1;
}
if(process.argv[1]&&fs.realpathSync(process.argv[1])===fs.realpathSync(fileURLToPath(import.meta.url)))main(process.argv.slice(2));
