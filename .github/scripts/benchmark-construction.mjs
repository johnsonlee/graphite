#!/usr/bin/env node
// Recompute the six usable-save observations. Never accepts a producer's single CAB run.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
const require=(ok,message)=>{if(!ok)throw new Error(message);};
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const same=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
const flags=['completeSemanticEquivalence','strictEquivalence','sourceToDeclarationCompletenessClaim','performanceAcceptance','otherOperationsEligible'];
const pairs={parent:[[1,2],[4,3]],acceptedBaseline:[[0,2],[5,3]]};
const scope='fresh JVM through 64 saved graphs, persisted indexes, manifests, embedded readback validation and clean writer exit';
const model='complete-core-topology-index-with-source-corrections-and-additive-declarations-v1';
const contained=(root,name)=>{
    require(typeof name==='string'&&!path.isAbsolute(name),'artifact-relative raw evidence');
    const p=path.resolve(root,name);require(p.startsWith(path.resolve(root)+path.sep),'raw evidence outside artifact root');return p;
};
export function constructionResources(file) {
    const raw=fs.readFileSync(file,'utf8');
    const field=name=>{const values=raw.split('\n').filter(x=>x.trim().startsWith(name+':'));
        require(values.length===1,'unique GNU resource field '+name);return values[0].trim().slice(name.length+1).trim();};
    require(field('Exit status')==='0','GNU time child exit');
    const cpu=[field('User time (seconds)'),field('System time (seconds)')];
    require(cpu.every(x=>/^\d+(\.\d+)?$/.test(x)),'GNU CPU values');
    const [user,system]=cpu.map(Number);
    const clock=field('Elapsed (wall clock) time (h:mm:ss or m:ss)').split(':');
    require(clock.length===2||clock.length===3,'GNU elapsed format');
    require(clock.every(x=>/^\d+(\.\d+)?$/.test(x)),'GNU elapsed components');
    const wall=clock.map(Number).reduce((a,b)=>a*60+b,0);
    const rss=field('Maximum resident set size (kbytes)');require(/^\d+$/.test(rss),'GNU RSS unit');
    require([user,system,wall,user+system].every(x=>Number.isFinite(x)&&x>=0)&&
        Number.isSafeInteger(Number(rss))&&Number(rss)>0&&Number.isSafeInteger(Number(rss)*1024),'finite raw construction resources');
    return {realSeconds:wall,userSeconds:user,systemSeconds:system,totalCpuSeconds:user+system,
        peakRssBytes:Number(rss)*1024,rawSha256:sha(file)};
}
export function compareConstruction(planFile, directory) {
    const plan=read(planFile),digest=sha(planFile),root=path.join(directory,'cells');
    require(plan.schema==='graphite.real64-construction.plan.v1'&&plan.engine==='jvm'&&plan.operation==='construction'&&
        plan.graphCount===64&&plan.scope===scope&&plan.comparisonModel===model,'construction plan scope');
    require(flags.every(k=>plan[k]===false),'construction semantic and other-operation claims');
    require(same(plan.cells.map(c=>c.arm),[...'CABBAC'])&&new Set(plan.cells.map(c=>c.id)).size===6&&same(plan.comparisonPairs,pairs),'fixed construction CABBAC');
    require(plan.maxHeapBytes===4*1024**3&&plan.activeProcessorCount===4,'matched 4g/APC4 construction');
    require(plan.arms.C.revision==='4f2ccf33b969e684972e56b5e810034e6e67c1b3'&&Object.values(plan.arms).every(a=>/^[a-f0-9]{40}$/.test(a.revision)),'exact construction revisions');
    require(Object.values(plan.arms).every(a=>a.argv.length===10&&a.argv[7]==='-cp'&&
        same(a.argv.slice(0,7),plan.arms.C.argv.slice(0,7))&&/^[a-f0-9]{64}$/.test(a.writer.sha256)),'matched direct JDK flags and four input JARs');
    const audit=read(path.join(directory,'audit.json')),executionFile=path.join(root,'execution.json'),execution=read(executionFile);
    require(audit.schema==='graphite.real64-construction.audit.v1'&&audit.status==='PASS_RAW_USABLE_SAVE_AUDIT'&&audit.planSha256===digest&&
        audit.executionSha256===sha(executionFile)&&audit.scope===scope&&audit.comparisonModel===model&&same(audit.correctedComparability,plan.producers)&&
        flags.every(k=>audit[k]===false),'bound independent construction audit');
    require(execution.schema==='graphite.real64-construction.execution.v1'&&execution.status==='PASS_ALL_SIX_USABLE_SAVED64'&&
        same(execution.errors,[])&&same(execution.unissued,[])&&execution.planSha256===digest&&flags.every(k=>execution[k]===false)&&
        same(execution.cells.map(c=>c.cell),plan.cells)&&audit.rows.length===6,'all six actual construction cells');
    const groups=[];
    const rows=plan.cells.map((cell,i)=>{
        const result=execution.cells[i],a=audit.rows[i],arm=plan.arms[cell.arm];
        require(same(a.cell,cell)&&a.graphCount===64&&a.revision===arm.revision&&same(a.writer,arm.writer)&&
            result.status==='PASS_USABLE_SAVED64'&&same(result.errors,[])&&same(result.writer,arm.writer)&&result.revision===arm.revision&&
            flags.every(k=>result[k]===false),'actual source writer and complete output binding');
        const rawNames=[`${cell.id}/cell.json`,`${cell.id}/fixture-manifest.json`,`${cell.id}/prepare-real64/time-v.log`,
            ...['prepare-real64','verify-real64'].flatMap(n=>['record.json','owner.json','stdout.log','stderr.log'].map(x=>`${cell.id}/${n}/${x}`))];
        require(same(Object.keys(a.rawPins).sort(),rawNames.sort()),'complete raw construction evidence');
        for(const [name,h] of Object.entries(a.rawPins))require(sha(contained(root,name))===h,'raw construction digest '+name);
        require(same(read(contained(root,`${cell.id}/cell.json`)),result),'original cell record');
        for(const [name,key] of [['prepare-real64','construction'],['verify-real64','verify']]) {
            const p=read(contained(root,`${cell.id}/${name}/record.json`)),owner=read(contained(root,`${cell.id}/${name}/owner.json`));
            require(same(p,result[key])&&p.status==='PASS'&&p.exit===0&&p.cleanup.exit===0&&same(p.errors,[])&&
                same(p.cleanup.after,[])&&same(p.cleanup.errors,[])&&p.cleanup.group===owner.group,'normal owned writer/readback exit');
            groups.push(owner.group);
            const suffix=name==='prepare-real64'?[`${p.cwd}/graphs`]:['--verify',`${p.cwd}/graphs/graphs.tsv`,`${p.cwd}/graphs/fixture-provenance.tsv`];
            require(same(p.argv,[...arm.argv,...suffix])&&p.timeoutSeconds===plan.timeouts[name],'actual timed and untimed argv');
            const actual=name==='prepare-real64'?['/usr/bin/time','-v','-o',`${p.cwd}/${name}/time-v.log`,'--',...p.argv]:p.argv;
            require(same(owner.argv,actual)&&(name!=='prepare-real64'||same(p.launchArgv,actual)),'direct measured wrapper');
        }
        require(arm.argv[1]==='-Xmx4g'&&arm.argv[2]==='-XX:ActiveProcessorCount=4'&&arm.argv.at(-2)===arm.writer.path&&
            arm.argv.at(-1)==='io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation','bounded actual writer');
        const fixture=read(contained(root,`${cell.id}/fixture-manifest.json`));
        require(fixture.writerRevision===arm.revision&&fixture.writerJarSha256===arm.writer.sha256&&fixture.graphs.length===64&&
            new Set(fixture.graphs.map(g=>g.id)).size===64&&fixture.inputJars.length===4&&flags.every(k=>fixture[k]===false),'saved64 validity receipt');
        const resources=constructionResources(contained(root,`${cell.id}/prepare-real64/time-v.log`));
        require(Object.entries(resources).every(([k,v])=>v===a.resources[k]&&v===result.construction.constructionResources[k]),'independently recomputed construction resources');
        return resources;
    });
    require(new Set(groups).size===12,'twelve distinct owned phases');
    const comparisons=Object.entries(pairs).flatMap(([kind,ps])=>ps.map(([b,c],direction)=>{
        const base=rows[b],candidate=rows[c];return {kind,direction,base:plan.cells[b].id,candidate:plan.cells[c].id,
            baseWallMs:base.realSeconds*1000,candidateWallMs:candidate.realSeconds*1000,deltaWallMs:(candidate.realSeconds-base.realSeconds)*1000,
            baseCpuSeconds:base.totalCpuSeconds,candidateCpuSeconds:candidate.totalCpuSeconds,deltaCpuSeconds:candidate.totalCpuSeconds-base.totalCpuSeconds,
            baseRssBytes:base.peakRssBytes,candidateRssBytes:candidate.peakRssBytes,deltaRssBytes:candidate.peakRssBytes-base.peakRssBytes,
            wallPassed:candidate.realSeconds<=base.realSeconds,cpuPassed:candidate.totalCpuSeconds<=base.totalCpuSeconds*1.05,
            rssPassed:candidate.peakRssBytes<=base.peakRssBytes*1.05};}));
    const passed=comparisons.filter(x=>x.kind==='acceptedBaseline').every(x=>x.wallPassed&&x.cpuPassed&&x.rssPassed);
    return {schema:'graphite.real64-construction.comparison.v1',engine:'jvm',operation:'construction',scope:'multi-graph-usable-save',
        passed,status:passed?'PASS':'FAIL',planSha256:digest,comparisons,acceptedBaselineRevision:plan.arms.C.revision,
        parentRevision:plan.arms.A.revision,candidateRevision:plan.arms.B.revision,completeSemanticEquivalence:false,
        strictEquivalence:false,sourceToDeclarationCompletenessClaim:false,otherOperationsEligible:false,
        limitation:'Two fixed directions; source-corrected comparability and fresh-output validity, not complete semantic equality. No query/loading acceptance.'};
}
function main(argv) {
    const [plan,dir,prefix]=argv;require(plan&&dir&&prefix,'plan directory prefix');
    const value=compareConstruction(plan,dir);
    value.evidence={plan:path.relative(path.dirname(prefix),plan),directory:path.relative(path.dirname(prefix),dir)};
    fs.mkdirSync(path.dirname(prefix),{recursive:true});
    fs.writeFileSync(prefix+'-status.json',JSON.stringify(value,null,2)+'\n');
    const lines=['### JVM real64 construction','',value.status,'',
        '| Pair | Base wall ms | Candidate wall ms | Δ ms | Base CPU s | Candidate CPU s | Base RSS bytes | Candidate RSS bytes |',
        '|---|---:|---:|---:|---:|---:|---:|---:|'];
    for(const c of value.comparisons)lines.push(`| ${c.kind} ${c.base} → ${c.candidate} | ${c.baseWallMs} | ${c.candidateWallMs} | ${c.deltaWallMs} | ${c.baseCpuSeconds} | ${c.candidateCpuSeconds} | ${c.baseRssBytes} | ${c.candidateRssBytes} |`);
    lines.push('',value.limitation);fs.writeFileSync(prefix+'-report.md',lines.join('\n')+'\n');
    if(!value.passed)process.exitCode=1;
}
if(process.argv[1]&&fs.realpathSync(process.argv[1])===fs.realpathSync(fileURLToPath(import.meta.url)))main(process.argv.slice(2));
