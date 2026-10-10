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
export function resourceBound(baseLower,baseUpper,candidateLower,candidateUpper) {
    require([baseLower,baseUpper,candidateLower,candidateUpper].every(finite) &&
        baseLower <= baseUpper && candidateLower <= candidateUpper,'Invalid resource interval');
    return candidateUpper <= baseLower*1.05 ? 'PASS' : candidateLower > baseUpper*1.05 ? 'FAIL' : 'INDETERMINATE';
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
export function comparePressure(planFile,directory) {
    const plan=read(planFile), planSha256=sha(planFile);
    require(plan.schema==='graphite.multigraph-pressure.plan.v1' && plan.operation==='query' && ['native','jvm'].includes(plan.engine),'Plan schema');
    require(same(plan.cells.map(c=>c.arm),['C','A','B','B','A','C']) && new Set(plan.cells.map(c=>c.id)).size===6,'Fixed CABBAC');
    require(same(plan.comparisonPairs,{parent:[[1,2],[4,3]],acceptedBaseline:[[0,2],[5,3]]}),'Fixed pairs');
    require(plan.schedule.concurrency===4 && plan.schedule.warmupPerCase===2 && plan.schedule.measuredPerCase===20,'Fixed sampling');
    require(plan.cases.length>0 && plan.cases.every(c=>new Set(c.targetGraphIds).size>1),'Actual multi-graph request scopes');
    const inputs={[path.resolve(planFile)]:planSha256};
    const cells=plan.cells.map(cell=>{
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
        return {cell,stats,stage:result.stages.pressure,queryEvidenceComplete:true};
    });
    const comparisons=Object.entries(plan.comparisonPairs).flatMap(([kind,pairs])=>pairs.map(([b,c],direction)=>{
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
            rss:{baseLower:br.rss.lowerBoundBytes,baseUpper:br.rss.upperBoundBytes,candidateLower:cr.rss.lowerBoundBytes,candidateUpper:cr.rss.upperBoundBytes,
                status:resourceBound(br.rss.lowerBoundBytes,br.rss.upperBoundBytes,cr.rss.lowerBoundBytes,cr.rss.upperBoundBytes)}};
    }));
    const coverage=plan.coverage;
    require(Array.isArray(coverage.unavailableFamilies),'Query coverage');
    const coverageComplete=coverage.unavailableFamilies.length===0 && coverage.requiredFamilies.length>0 &&
        coverage.requiredFamilies.every(f=>coverage.coveredFamilies.includes(f));
    const accepted= comparisons.filter(c=>c.kind==='acceptedBaseline');
    const measuredConstraintsPassed=accepted.every(c=>c.cases.every(k=>k.p50Passed&&k.p95Passed)&&c.cpu.status==='PASS'&&c.rss.status==='PASS'&&c.deltaWallMs<=0);
    return {schema:'graphite.multigraph-pressure.comparison.v1',scope:'multi-graph-pressure',engine:plan.engine,operation:'query',
        planSha256,passed:coverageComplete&&measuredConstraintsPassed,status:!coverageComplete?'UNAVAILABLE':measuredConstraintsPassed?'PASS':'FAIL',
        queryEvidenceComplete:true,coverageComplete,coverage,measuredConstraintsPassed,comparisons,inputs,
        acceptedBaselineRevision:plan.arms.C.revision,parentRevision:plan.arms.A.revision,candidateRevision:plan.arms.B.revision,
        otherOperationsEligible:false,limitation:'Two fixed directions; no pooling, quiet-host or saturation claim. Lifecycle RSS bounds may leave query RSS indeterminate.'};
}
function main(argv) {
    const [plan,dir,prefix]=argv;
    require(plan&&dir&&prefix,'plan.json cell-directory output-prefix');
    let result;
    try {result=comparePressure(plan,dir);result.evidence={plan:path.relative(path.dirname(prefix),plan),directory:path.relative(path.dirname(prefix),dir)};} catch(e) {result={schema:'graphite.multigraph-pressure.comparison.v1',passed:false,status:'FAIL',errors:[e.message]};}
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
    if(!result.passed)process.exitCode=1;
}
if(process.argv[1]&&fs.realpathSync(process.argv[1])===fs.realpathSync(fileURLToPath(import.meta.url)))main(process.argv.slice(2));
