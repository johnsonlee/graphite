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

// The Python finalizer replays the full raw proof. The portable report retains
// its original metadata bytes so the aggregate can check the exact join again
// without downloading graph/runtime payloads or changing the measured plan.
export function verifyComparabilityJoin(planFile,directory,plan,receiptFile,inputs) {
    const pending='pendingComparabilityAuthority' in plan || plan.comparabilityPending===true;
    if (!pending) {require(!receiptFile,'Unexpected comparability receipt');return true;}
    require(plan.engine==='native' && plan.comparabilityPending===true && plan.pendingComparabilityAuthority &&
        !plan.correctedProducerAuthority && !plan.jvmProducerAuthority,'Explicit pending native comparability');
    if (!receiptFile) return false;
    const receipt=read(receiptFile), base=path.dirname(path.resolve(receiptFile));
    inputs[path.resolve(receiptFile)]=sha(receiptFile);
    require(receipt.schema==='graphite.native-pressure.core-comparability-join.v1' &&
        receipt.status==='PASS_SAME_INPUT_COMPLETE_CORRECTED_CORE_JOIN' && receipt.coreComparabilityVerified===true &&
        ['completeSemanticEquivalence','strictEquivalence','sourceToDeclarationCompletenessClaim',
            'performanceAcceptance','otherOperationsEligible'].every(k=>receipt[k]===false),'Complete scoped core join');
    require(receipt.plan?.sha256===sha(planFile) && equal(receipt.measurementProducer,plan.pendingComparabilityAuthority) &&
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
    require(original.measurementMode==='native39-before-core-v1' &&
        original.status==='ARTIFACTS_AND39_QUERIES_AUDITED_EQUIVALENCE_PENDING' &&
        corrected.status==='ARTIFACTS_QUERIES_AND_CORRECTED_CORE_COMPARABILITY_AUDITED' &&
        equal(original.arms,corrected.arms) && equal(original.sourceInputs,corrected.sourceInputs) &&
        equal(original.armDirectories,corrected.armDirectories) && equal(corrected.missingAuthority,[]) &&
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
            audit.completeCoreTopologyIndexComparison===true && audit.productionFormatterTestsVerified===true &&
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
    return true;
}
export function resourceBound(baseLower,baseUpper,candidateLower,candidateUpper) {
    require([baseLower,baseUpper,candidateLower,candidateUpper].every(finite) &&
        baseLower <= baseUpper && candidateLower <= candidateUpper,'Invalid resource interval');
    return candidateUpper <= baseLower*1.05 ? 'PASS' : candidateLower > baseUpper*1.05 ? 'FAIL' : 'INDETERMINATE';
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
    require(plan.schedule.concurrency===4 && plan.schedule.warmupPerCase===2 && plan.schedule.measuredPerCase===20,'Fixed sampling');
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
            audit.completeBodies===plan.cases.length*23 && audit.otherOperationsEligible===false,'Bound full raw audit');
        require(result.schema==='graphite.multigraph-pressure.result.v1' && result.status==='PASS' && result.errors.length===0 &&
            same(result.cell,cell) && same(result.arm,plan.arms[cell.arm]) && result.engine===plan.engine &&
            result.operation==='query' && result.planSha256===planSha256 && same(result.coverage,plan.coverage) &&
            same(audit.coverage,plan.coverage),'Result identity');
        const stats=requestStatistics(result.stages.pressure,plan.cases);
        require(same(stats,result.stages.pressure.caseStatistics),'Recomputed nearest-rank per-case statistics');
        require(same(result.cleanup.after,[]) && same(result.cleanup.errors,[]) &&
            result.cleanup.signals.every(s=>!s.signal.includes('KILL')),'Owned cleanup');
        return {cell,stats,stage:result.stages.pressure,queryEvidenceComplete:true,
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
                p50Passed:z.p50Ns<=a.p50Ns,p95Passed:z.p95Ns<=a.p95Ns};
        });
        return {kind,direction,base:base.cell.id,candidate:candidate.cell.id,cases,
            baseWallMs:base.stage.wallNs/1e6,candidateWallMs:candidate.stage.wallNs/1e6,
            deltaWallMs:(candidate.stage.wallNs-base.stage.wallNs)/1e6,
            cpu:{baseLower:br.cpuLowerBoundSeconds,baseUpper:br.cpuUpperBoundSeconds,candidateLower:cr.cpuLowerBoundSeconds,candidateUpper:cr.cpuUpperBoundSeconds,
                status:resourceBound(br.cpuLowerBoundSeconds,br.cpuUpperBoundSeconds,cr.cpuLowerBoundSeconds,cr.cpuUpperBoundSeconds)},
            rss:{measurementSemantics:base.rssSemantics,baseLower:br.rss.lowerBoundBytes,baseUpper:br.rss.upperBoundBytes,candidateLower:cr.rss.lowerBoundBytes,candidateUpper:cr.rss.upperBoundBytes,
                status:resourceBound(br.rss.lowerBoundBytes,br.rss.upperBoundBytes,cr.rss.lowerBoundBytes,cr.rss.upperBoundBytes)}};
    }).filter(Boolean));
    if (provisional) return {
        schema:'graphite.multigraph-pressure.progress.v1',status:'PROVISIONAL',
        scope:'multi-graph-pressure',engine:plan.engine,operation:'query',planSha256,
        completedCells:cells.map(c=>c.cell.id),comparisons,inputs,
        passed:false,performanceAcceptance:false,queryEvidenceComplete:false,otherOperationsEligible:false,
        limitation:'Completed fixed pairs only. All six cells and complete correctness evidence remain required; this report cannot establish acceptance.'
    };
    const coverage=plan.coverage;
    require(Array.isArray(coverage.unavailableFamilies),'Query coverage');
    const coverageComplete=coverage.unavailableFamilies.length===0 && coverage.requiredFamilies.length>0 &&
        coverage.requiredFamilies.every(f=>coverage.coveredFamilies.includes(f));
    const accepted= comparisons.filter(c=>c.kind==='acceptedBaseline');
    const measuredConstraintsPassed=accepted.every(c=>c.cases.every(k=>k.p50Passed&&k.p95Passed)&&c.cpu.status==='PASS'&&c.rss.status==='PASS'&&c.deltaWallMs<=0);
    return {schema:'graphite.multigraph-pressure.comparison.v1',scope:'multi-graph-pressure',engine:plan.engine,operation:'query',
        planSha256,passed:comparabilityVerified&&coverageComplete&&measuredConstraintsPassed,
        status:!comparabilityVerified?'MEASURED_CORE_COMPARABILITY_PENDING':!coverageComplete?'UNAVAILABLE':measuredConstraintsPassed?'PASS':'FAIL',
        queryEvidenceComplete:true,coverageComplete,coverage,measuredConstraintsPassed,comparisons,inputs,
        ...('pendingComparabilityAuthority' in plan?{comparabilityPending:!comparabilityVerified,coreComparabilityVerified:comparabilityVerified}:{}),
        acceptedBaselineRevision:plan.arms.C.revision,parentRevision:plan.arms.A.revision,candidateRevision:plan.arms.B.revision,
        otherOperationsEligible:false,limitation:'Two fixed directions; no pooling, quiet-host or saturation claim. RSS observation envelopes may leave query RSS indeterminate; Linux kernel counters are approximate, not mathematical physical-memory bounds.'};
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
    lines.push(...(result.errors??[]),result.limitation??'');
    fs.writeFileSync(prefix+'-report.md',lines.join('\n')+'\n');
    if(!result.passed && !(provisional&&result.status==='PROVISIONAL'))process.exitCode=1;
}
if(process.argv[1]&&fs.realpathSync(process.argv[1])===fs.realpathSync(fileURLToPath(import.meta.url)))main(process.argv.slice(2));
