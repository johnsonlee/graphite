#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

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
export function verifyLifecycleJoin(planFile,directory,plan,receiptFile) {
    const inputs={};
    const pending='pendingComparabilityAuthority' in plan || plan.comparabilityPending===true;
    if (!pending) {require(!receiptFile,'Unexpected comparability receipt');return true;}
    require(plan.engine==='jvm' && ['loading','construction'].includes(plan.operation) && plan.comparabilityPending===true && plan.pendingComparabilityAuthority &&
        equal(plan.pendingComparabilityAuthority,plan.producers),'Explicit pending lifecycle comparability');
    if (!receiptFile) return false;
    const receipt=read(receiptFile), base=path.dirname(path.resolve(receiptFile));
    inputs[path.resolve(receiptFile)]=sha(receiptFile);
    require(receipt.schema==='graphite.real64-lifecycle.core-comparability-join.v1' && receipt.engine==='jvm' && receipt.operation===plan.operation &&
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
    require(equal(plan.sourceInputs,original.sourceInputs),'Measured source JAR inputs differ from joined producer');
    for (const arm of ['C','A','B']) {
        const measured=plan.arms[arm], source=original.arms[arm];
        require(['revision','sourceManifest','runtimeManifest','artifactAudit'].every(key=>equal(source[key],measured[key])),
            'Measured writer source differs from joined producer');
        require(source.runtimeFiles[measured.writer.path]===measured.writer.sha256 &&
            original.pins[measured.writer.path]===measured.writer.sha256,'Measured own-writer bytes differ');
        if(plan.operation==='loading') require(equal(source.graphs,measured.graphs) &&
            equal(source.fixtureManifest,measured.fixtureManifest) &&
            source.runtimeFiles[measured.serverJar.path]===measured.serverJar.sha256 &&
            original.pins[measured.serverJar.path]===measured.serverJar.sha256,'Measured loading runtime or graphs differ');
    }
    for(const [p,h] of Object.entries(original.pins)) require(corrected.pins[p]===h && plan.pins[p]===h,
        'Joined core must retain every original producer pin');
    const execution=pinned(receipt.measurementExecution,path.join(directory,'cells/execution.json'));
    const audit=pinned(receipt.measurementAudit,path.join(directory,'audit.json'));
    require(execution.performanceAcceptance===false && equal(execution.errors,[]) && equal(execution.unissued,[]) &&
        equal(execution.cells.map(c=>c.cell),plan.cells) && execution.planSha256===sha(planFile) &&
        audit.planSha256===sha(planFile) && audit.executionSha256===receipt.measurementExecution.sha256 &&
        equal(audit.correctedComparability,plan.producers),'Complete original immutable measurement execution and raw audit');
    const alias=['revision','sourceManifest','runtimeManifest','fixtureManifest','graphs'].every(
        key=>equal(original.arms.A[key],original.arms.C[key]));
    require(equal(Object.keys(receipt.coreComparisons).sort(),alias?['B']:['A','B']),'Every nonaccepted arm needs core proof');
    for (const [arm,comparison] of Object.entries(receipt.coreComparisons)) {
        const audit=metadata(comparison.audit), record=metadata(audit.record), corePlan=metadata(record.plan);
        for(const name of ['C',arm]) for(const field of ['artifactAudit','fixtureManifest','sourceManifest','runtimeManifest']) {
            const ref=original.arms[name][field];
            require(audit.pins?.[ref.path]===ref.sha256,'Core comparison producer upstream mismatch');
        }
        const revisions={C:plan.arms.C.revision,B:plan.arms[arm].revision};
        const fixtures={C:original.arms.C.fixtureManifest,B:original.arms[arm].fixtureManifest};
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
            [audit.graphs,record.graphs,corePlan.graphs].every(rows=>equal(rows?.map(g=>g.id),original.arms.C.graphs.map(g=>g.id))),
            'Complete core graph inventory and clean final identity');
        require(audit.pins?.[audit.record.path]===audit.record.sha256 &&
            audit.pins?.[record.plan.path]===record.plan.sha256,'Core metadata closure');
        for (const phaseRef of record.phases) {
            const phase=metadata(phaseRef);
            require(audit.pins?.[phaseRef.path]===phaseRef.sha256 && phase.status==='PASS' && phase.exit===0 &&
                equal(phase.errors,[]),'Every recorded core phase succeeded');
        }
    }
    for(const [file,digest] of Object.entries(inputs)) require(sha(file)===digest,'Lifecycle joined evidence drift');
    return true;
}
