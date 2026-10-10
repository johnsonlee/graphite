#!/usr/bin/env python3
"""Execute the retained complete core/index/topology proof for a fresh own-writer64 pair.

Correctness only. Each per-graph subprocess uses the original explicit migration
policies; no new normalization or unsupported bytecode-inference claim is made.
"""
import argparse
import copy
import csv
import json
import os
from pathlib import Path
import signal
import struct
import sys
sys.dont_write_bytecode=True
import audit_native_pressure_artifacts as artifacts
import bind_native_core_formatter as formatter
import export_native_core_marker as marker
import export_native_core_strings as strings
import run_native_formatter_tests as tests
import produce_native_pressure_artifacts as producer
import multigraph_pressure as common
from assemble_native_pressure_producers import merge_pins
from prepare_native_pressure_plan import preparation_control_pins
from native_core_proof import formatter_binding,source_bindings,raw_local_export

PACKAGE=Path(__file__).resolve().parent/'native_core_proof'
CORE_PASS='PASS_CORE_WITH_TYPE_OVERLOAD_SYNTHETIC_AND_INHERITED_FIELD_CORRECTIONS_REQUIRES_TOPOLOGY'
TOPOLOGY_PASS='PASS_TOPOLOGY_WITH_EXPLICIT_TYPE_OVERLOAD_SYNTHETIC_AND_INHERITED_FIELD_CORRECTIONS'
PASS='PASS_ALL64_CORE_WITH_EXPLICIT_TYPE_OVERLOAD_SYNTHETIC_INHERITED_FIELD_CORRECTIONS_TOPOLOGY_INDICES_ADDITIVE_DECLARATION_VALIDITY'
AUDIT_PASS='PASS_INDEPENDENT_ALL64_CORE_TOPOLOGY_EXECUTION_AUDIT'
COUNTS=('fieldCorrectionCount','methodCorrectionCount','localArrayCorrectionCount','parameterArrayCorrectionCount',
        'legacyOrdinalGroupCount','legacyMetadataGroupCount','legacyReturnOmittedGroupCount','recoveredMetadataMethodCount',
        'legacyOrdinalBindingCount','syntheticMethodKeyCorrectionCount','syntheticConstructorCollisionGroupCount',
        'recoveredSyntheticIdentityCount','inheritedFieldCorrectionCount')
FALSE_CLAIMS=('completeSemanticEquivalence','strictEquivalence','syntheticLocalInferenceOracleClaim','sourceToDeclarationCompletenessClaim','performanceAcceptance')
require=common.require


def replay(path,function):
    path=Path(path).resolve();value=common.read(path)
    require(common.typed(value)==common.typed(function(path.parent)),'stored upstream differs from independent raw replay: '+str(path))
    return value


def _source_evidence_with_marker(path,expected_marker_ref=None):
    """Reconstruct the saved source report through its exact original producer chain."""
    path=Path(path).resolve();stored=common.read(path)
    if expected_marker_ref is not None:
        require(stored['upstream']['markerAudit']==expected_marker_ref,'same actual formatter marker pair')
    bindings,marked=formatter._bind_with_marker(stored['upstream']['markerAudit']['path'],path.parent)
    sources,fixtures,controls,upstream=bindings
    rule,report=formatter_binding.source_rule(sources,fixtures,controls)
    ref=artifacts.ref(path.parent/'local-array-source-rule.json')
    require(common.read(ref['path'])==rule,'saved formatter rule differs from actual source')
    _,linked=source_bindings.load_rule(ref,{'schema':'graphite.classfile-field-authority.v1',
        'arms':{arm:{'fixtureManifest':fixtures[arm]} for arm in ('C','B')}})
    report['rule']=ref;report['pins'].update(linked);report['upstream']=upstream
    report['pins'].update({str(Path(formatter.__file__).resolve()):common.sha(formatter.__file__),
                          upstream['markerAudit']['path']:upstream['markerAudit']['sha256']})
    require(common.typed(stored)==common.typed(report),'source-only formatter report differs from actual source')
    artifacts.verify_pins(report['pins']);return copy.deepcopy(report),copy.deepcopy(marked)


def source_evidence(path):
    report,_=_source_evidence_with_marker(path)
    return report


def field_authority(graph_id,graphs,roots,fixtures,refs,marker_ref,pins):
    """Original source16 authority policy, with explicit actual roots instead of /tmp."""
    arms={};selected=[];corpora=[]
    for label in ('C','B'):
        root=roots[label];graph=graphs[label];fixture=fixtures[label]
        path=root/'graphs/fixture-provenance.tsv';digest=fixture['files'][str(path)]
        require(common.sha(path)==digest,'producer provenance bytes')
        with path.open() as stream:rows=[r for r in csv.DictReader(stream,delimiter='\t') if r['graphId']==graph_id]
        require(len(rows)==1 and Path(rows[0]['graphPath']).resolve()==Path(graph['path']).resolve(),'graph provenance identity')
        row=rows[0];jars=[j for j in fixture['inputJars'] if j['corpus']==row['corpus']]
        require(len(jars)==1 and jars[0]['sha256']==row['sourceJarSha256'] and
                Path(jars[0]['path']).name==row['sourceJar'],'exact graph corpus JAR')
        selected.append(jars[0]);corpora.append(row['corpus'])
        arms[label]={'fixtureManifest':refs[label],'provenance':{'path':str(path),'sha256':digest}}
        merge_pins(pins,{str(path):digest})
    require(selected[0]==selected[1] and corpora[0]==corpora[1],'matched corpus/JAR authority')
    merge_pins(pins,{selected[0]['path']:selected[0]['sha256']})
    return {'schema':'graphite.classfile-field-authority.v1','graphId':graph_id,'corpus':corpora[0],
            'jar':selected[0],'arms':arms,'platformMarker':marker_ref}


def bind(reference,actual,output,raw_locals=False,raw_edges=False):
    roots={'C':Path(reference).resolve(),'B':Path(actual).resolve()};out=Path(output).resolve()
    exports={};audits={};fixtures={};fixture_refs={};pins={};upstream={};source_inputs=[];toolchains={}
    controls=preparation_control_pins()
    require(all(str(p) in controls for p in (Path(__file__).resolve(),PACKAGE/'check_graph.py',PACKAGE/'VerifyTopology.java')),
            'reviewed complete proof helper controls')
    merge_pins(pins,controls)
    for arm,root in roots.items():
        ref=artifacts.ref(root/'core-string-exports/audit.json');export=replay(ref['path'],strings.audit)
        require(export['artifactAudit']==artifacts.ref(root/'artifact-audit.json'),'export actual producer root')
        artifact=common.pinned_authority_metadata(export,export['artifactAudit'])
        require((arm=='C' and artifact['role']=='accepted-baseline' and artifact['revision']==artifacts.ACCEPTED) or
                (arm=='B' and artifact['role'] in ('parent','candidate')),
                'accepted prefeature reference and actual feature writer')
        fixture=common.pinned_authority_metadata(artifact,artifact['fixtureManifest'])
        source=common.pinned_authority_metadata(artifact,artifact['sourceManifest'])
        require(not any(out.is_relative_to(Path(p)) for p in (source['root'],root/'graphs',root/'runtime')),
                'proof output outside measured source/runtime/graph roots')
        plan=common.read(root/'core-string-exports/plan.json');source_inputs.append(plan['sourceInputs'])
        if raw_locals:toolchains[arm]=(plan['java'],plan['javac'])
        exports[arm]=export;audits[arm]=artifact;fixtures[arm]=fixture;fixture_refs[arm]=artifact['fixtureManifest']
        merge_pins(pins,export['pins']);merge_pins(pins,{ref['path']:ref['sha256']})
        upstream[arm+'Strings']=ref
    require(source_inputs[0]==source_inputs[1] and fixtures['C']['inputJars']==fixtures['B']['inputJars'],
            'same exact input corpus and manifest')
    marker_ref=artifacts.ref(roots['B']/'core-marker/audit.json')
    test_ref=artifacts.ref(roots['B']/'formatter-tests/audit.json');tested=replay(test_ref['path'],tests.audit)
    source_ref=artifacts.ref(roots['B']/'core-formatter-source/source-binding.json')
    bound,marked=_source_evidence_with_marker(source_ref['path'],marker_ref)
    require(tested['artifactAudit']==artifacts.ref(roots['B']/'artifact-audit.json') and tested['productionFormatterTestsVerified'] is True,
            'actual candidate compiled formatter test authority')
    require(bound['upstream']['markerAudit']==marker_ref and bound['fixtureManifests']==fixture_refs,
            'same actual formatter source pair')
    for arm in ('C','B'):
        require(marked['arms'][arm]['artifactAudit']==artifacts.ref(roots[arm]/'artifact-audit.json'), 'same actual bootstrap marker pair')
        require(bound['sourceManifests'][arm]==audits[arm]['sourceManifest'],'same actual source authority')
    for ref,value in ((marker_ref,marked),(test_ref,tested),(source_ref,bound)):
        merge_pins(pins,value['pins']);merge_pins(pins,{ref['path']:ref['sha256']})
    upstream.update(marker=marker_ref,formatterTests=test_ref,formatterSource=source_ref)
    marker_plan=common.read(roots['B']/'core-marker/plan.json')
    python=str(Path(sys.executable).resolve());merge_pins(pins,{python:common.sha(python)})
    graphs=[]
    ids=[r['id'] for r in audits['C']['graphs']]
    require(len(ids)==len(set(ids))==64 and all([r['id'] for r in audits[arm]['graphs']]==ids==
                [r['id'] for r in exports[arm]['graphs']] for arm in ('C','B')),'complete ordered matched64 graph scope')
    for index,graph_id in enumerate(ids):
        pair={arm:audits[arm]['graphs'][index] for arm in ('C','B')}
        graphs.append({'id':graph_id,'C':pair['C']['path'],'B':pair['B']['path'],
            'CExport':exports['C']['graphs'][index],'BExport':exports['B']['graphs'][index],
            'fieldAuthority':field_authority(graph_id,pair,roots,fixtures,fixture_refs,marker_ref,pins)})
    artifacts.verify_pins(pins)
    result={'schema':'graphite.native-core-equivalence-plan.v1','referenceRoot':str(roots['C']),
        'actualRoot':str(roots['B']),'output':str(out),'revisions':{a:audits[a]['revision'] for a in ('C','B')},
        'fixtureManifests':fixture_refs,'upstream':upstream,'sourceRule':bound['rule'],'graphs':graphs,'pins':pins,
        'python':python,'java':marker_plan['java'],'javac':marker_plan['javac'],'writerJar':marker_plan['writerJar'],
        'maxOwnedPhases':129,'stopAtFirstFailure':True,**{k:False for k in FALSE_CLAIMS}}
    if raw_edges:result['rawEdgeExports']=True
    return raw_local_export.configure(result,fixtures,roots,toolchains) if raw_locals else result


def rebind(plan):return bind(plan['referenceRoot'],plan['actualRoot'],plan['output'],'rawLocalExports' in plan,plan.get('rawEdgeExports',False))


def commands(plan):
    out=Path(plan['output']);classes=out/'classes'
    yield 'compile-topology',[plan['javac'],'-J-Xmx4g','-J-XX:ActiveProcessorCount=4','-proc:none',
          '-cp',plan['writerJar'],'-d',str(classes),str(PACKAGE/'VerifyTopology.java')],180
    if 'rawLocalExports' in plan:yield from raw_local_export.commands(plan)
    for row in plan['graphs']:
        proof=out/'graphs'/row['id']
        yield row['id']+'-core',[plan['python'],'-B','-m','native_core_proof.check_graph',
              str(out/'plan.json'),common.sha(out/'plan.json'),row['id']],3600
        yield row['id']+'-topology',[plan['java'],'-Xmx4g','-XX:ActiveProcessorCount=4','-cp',
              str(classes)+os.pathsep+plan['writerJar'],'VerifyTopology',row['B'],row['C'],
              str(proof/'core/field-bijection.tsv'),str(proof/'topology.json'),str(PACKAGE),
              *([str(proof/'actual-edges.bin'),str(proof/'reference-edges.bin')] if plan.get('rawEdgeExports') else [])],1800


def compiled(plan):
    root=Path(plan['output'])/'classes';value=artifacts.inventory(root)
    expected={str(root/'VerifyTopology.class')}
    if 'rawLocalExports' in plan:expected.update(str(root/('raw-'+arm)/'ExportRawLocals.class') for arm in ('C','B'))
    require(set(value)==expected,'exact topology/raw Local helper closure')
    return value


EDGE_INPUTS=('forward.graph','forward.offsets','forward.properties','graph.labels','graph.labelprefix')


def raw_edge_exports(plan,row,top,root):
    """Bind optional single-pass exports without another BVGraph traversal."""
    require(plan.get('rawEdgeExports') is True, 'explicit raw edge export mode')
    exports=top.get('rawEdgeExports')
    require(type(exports) is dict and set(exports)=={'actual','reference'}, 'both raw edge exports required')
    fields={'schema','format','graphRoot','sourceInputs','output','nodeSlots','labeledEdges','helperSource','helperClass',
            'allSequentialOffsetsChecked','allRandomAccessOffsetsChecked','allLabelPrefixOffsetsChecked',
            'rawNodeIdsPreserved','mappingApplied','fullScanConsumed'}
    helper_source=artifacts.ref(PACKAGE/'VerifyTopology.java')
    helper_class=artifacts.ref(Path(plan['output'])/'classes/VerifyTopology.class')
    require(plan['pins'].get(helper_source['path'])==helper_source['sha256'], 'raw edge helper source pin')
    for name,arm in (('actual','B'),('reference','C')):
        value=exports[name];graph=Path(row[arm]);output=root/(name+'-edges.bin')
        require(type(value) is dict and set(value)==fields and value['schema']=='graphite.raw-labeled-edge-export.v1'
                and value['format']=='GSE01' and value['graphRoot']==str(graph), 'raw edge export identity')
        require(value['helperSource']==helper_source and value['helperClass']==helper_class, 'actual compiled raw edge helper')
        require(all(value[key] is True for key in ('allSequentialOffsetsChecked','allRandomAccessOffsetsChecked',
                    'allLabelPrefixOffsetsChecked','rawNodeIdsPreserved','fullScanConsumed'))
                and value['mappingApplied'] is False, 'complete original unremapped edge scope')
        expected={name:{'path':str(graph/name),'sha256':plan['pins'].get(str(graph/name))} for name in EDGE_INPUTS}
        require(all(common.valid_digest(ref['sha256']) and top['inputPins'].get(ref['path'])==ref['sha256']
                    for ref in expected.values()) and value['sourceInputs']==expected, 'five original topology input pins')
        require(not output.is_symlink() and value['output']==artifacts.ref(output), 'complete raw edge output pin')
        with output.open('rb') as stream:header=stream.read(16)
        require(len(header)==16, 'complete GSE01 header')
        magic,nodes,edges=struct.unpack('>iiq',header)
        require(magic==0x47534501 and type(value['nodeSlots']) is int and type(value['labeledEdges']) is int
                and 0<=nodes==value['nodeSlots']==top['nodeSlots'] and 0<=edges==value['labeledEdges']==top['labeledEdges']
                and output.stat().st_size==16+9*edges, 'GSE01 exact counts and length')


def graph_result(plan,row):
    root=Path(plan['output'])/'graphs'/row['id'];core=common.read(root/'record.json');top=common.read(root/'topology.json')
    require(core['status']==CORE_PASS and top['status']==TOPOLOGY_PASS and
            core['strictEquivalence'] is top['strictEquivalence'] is False,'complete scoped graph receipts')
    receipt=common.read(root/'core/receipt.json')
    require(core['core']==receipt and top['coreReceiptSha256']==common.sha(root/'core/receipt.json') and
            top['mappingSha256']==receipt['mappingSha256']==common.sha(root/'core/field-bijection.tsv') and
            top['inputPins']==receipt['inputs'] and top['actual']==receipt['actual']==row['B'] and
            top['reference']==receipt['reference']==row['C'], 'raw core topology proof identity')
    require(all(core[k]==receipt[k] for k in COUNTS),'core correction counts binding')
    require(all(type(core[k]) is type(top[k]) is int and core[k]>=0 and core[k]==top[k] for k in COUNTS),'complete matching correction counts')
    require(core['declarations']==common.read(root/'declarations.json') and
            core['properties']==common.read(root/'properties.json'),'outer raw declaration/property proof linkage')
    require(core['declarations']['status']=='PASS_ADDITIVE_DECLARATION_WIRE_VALIDITY' and
            core['declarations']['sourceToDeclarationCompletenessClaim'] is False and
            core['declarations']['queryExpectedAuthority'] is False and
            core['properties']['status']=='PASS_REQUIRES_COMPLETE_TOPOLOGY','declaration/property scope')
    if 'rawLocalExports' in plan:
        expected=raw_local_export.binding(plan,row)
        report=common.read(root/'core/raw-local-type-proof.json')
        require(report['status']=='PASS_ALL_PERSISTED_ARRAY_LOCALS_RAW_TYPE' and report['completeNodeInventory'] is True and report['unprovedCount']==0,'all persisted raw Local proof')
        require(report['inputs']=={r['path']:r['sha256'] for r in expected['exports'].values()} and
                all(receipt['inputs'].get(p)==h for p,h in report['inputs'].items()),'actual raw Local export linkage')
    if plan.get('rawEdgeExports'):raw_edge_exports(plan,row,top,root)
    else:require('rawEdgeExports' not in top, 'undeclared raw edge export mode')
    return {'id':row['id'],'core':artifacts.ref(root/'record.json'),'topology':artifacts.ref(root/'topology.json'),
            **{k:top[k] for k in COUNTS},'strictEquivalence':False,'files':artifacts.inventory(root)}


def execute(plan):
    require(common.typed(plan)==common.typed(rebind(plan)),'execution plan differs from actual pair')
    out=Path(plan['output']);out.mkdir(parents=True,exist_ok=False);(out/'classes').mkdir();(out/'graphs').mkdir()
    common.save(out/'plan.json',plan)
    record={'schema':'graphite.native-core-equivalence-record.v1','status':'FAIL','plan':artifacts.ref(out/'plan.json'),
            'errors':[],'phases':[],'graphs':[],**{k:False for k in FALSE_CLAIMS}}
    previous={};helper={}
    def interrupted(signum,frame):raise InterruptedError('signal '+str(signum))
    try:
        for sig in (signal.SIGINT,signal.SIGTERM):previous[sig]=signal.signal(sig,interrupted)
        artifacts.verify_pins(plan['pins'])
        for index,(name,argv,timeout) in enumerate(commands(plan)):
            producer.phase(name,argv,PACKAGE.parent,strings.environment(raw_local_export.phase_java(plan,name)),out,timeout)
            record['phases'].append(artifacts.ref(out/name/'record.json'))
            if name==('compile-raw-B' if 'rawLocalExports' in plan else 'compile-topology'):helper=compiled(plan)
            elif name.endswith('-topology') and name!='compile-topology':
                row=next(r for r in plan['graphs'] if r['id']==name[:-len('-topology')])
                record['graphs'].append(graph_result(plan,row))
            (out/'record.json').write_text(json.dumps(record,indent=2)+'\n')
        require(len(record['graphs'])==64 and len(record['phases'])==plan['maxOwnedPhases'],'all64 complete proofs required')
        record.update({k:sum(g[k] for g in record['graphs']) for k in COUNTS});record['status']=PASS
    except BaseException as error:record['errors'].append(repr(error))
    finally:
        for sig in previous:signal.signal(sig,signal.SIG_IGN)
        try:
            require(common.typed(plan)==common.typed(rebind(plan)),'final actual source/runtime/fixture identity drift')
            if helper:require(compiled(plan)==helper,'topology helper changed')
            require([graph_result(plan,r) for r in plan['graphs'][:len(record['graphs'])]]==record['graphs'],
                    'completed proof outputs changed')
            record['finalIdentity']='PASS'
        except BaseException as error:record['errors'].append('final verification: '+repr(error));record['finalIdentity']='FAIL'
        if record['errors']:record['status']='FAIL'
        record['compiledHelper']=helper;(out/'record.json').write_text(json.dumps(record,indent=2)+'\n')
        for sig,handler in previous.items():signal.signal(sig,handler)
    return record


def audit(output):
    out=Path(output).resolve();plan=common.read(out/'plan.json');record=common.read(out/'record.json')
    require(plan['output']==str(out) and record['schema']=='graphite.native-core-equivalence-record.v1' and
            record['status']==PASS and record['errors']==[] and record['finalIdentity']=='PASS' and
            record['plan']==artifacts.ref(out/'plan.json') and all(record[k] is False for k in FALSE_CLAIMS),
            'completed owned all64 comparison')
    require(common.typed(plan)==common.typed(rebind(plan)),'audited plan differs from actual pair')
    expected=list(commands(plan));require(len(record['phases'])==len(expected)==plan['maxOwnedPhases'],'all declared owned phases')
    helper=compiled(plan);require(helper==record['compiledHelper'],'actual topology helper')
    pins=dict(plan['pins']);merge_pins(pins,helper)
    for ref,(name,argv,timeout) in zip(record['phases'],expected):
        path=out/name/'record.json';require(ref==artifacts.ref(path),'exact owned proof phase receipt')
        phase=artifacts.check_phase(path,argv,PACKAGE.parent)
        require(phase['name']==name and phase['timeoutSeconds']==timeout,'exact bounded proof phase')
        for leaf in ('record.json','owner.json','stdout.log','stderr.log'):
            file=path.parent/leaf;merge_pins(pins,{str(file):common.sha(file)})
    require({p.name for p in (out/'graphs').iterdir()}=={r['id'] for r in plan['graphs']},'closed all64 proof inventory')
    rows=[graph_result(plan,row) for row in plan['graphs']]
    require(rows==record['graphs'] and all(record[k]==sum(r[k] for r in rows) for k in COUNTS),'all raw completed graph proofs')
    for row in rows:merge_pins(pins,row['files'])
    for leaf in ('plan.json','record.json'):merge_pins(pins,{str(out/leaf):common.sha(out/leaf)})
    artifacts.verify_pins(pins)
    return {'schema':'graphite.native-core-equivalence-audit.v1','status':AUDIT_PASS,'record':artifacts.ref(out/'record.json'),
        'revisions':plan['revisions'],'fixtureManifests':plan['fixtureManifests'],'upstream':plan['upstream'],
        'graphs':rows,'phases':plan['maxOwnedPhases'],'pins':pins,**{k:record[k] for k in COUNTS},**{k:False for k in FALSE_CLAIMS},
        'completeCoreTopologyIndexComparison':True,'productionFormatterTestsVerified':True,
        'proofModel':'EXPLICIT_CLASSFILE_AND_FORMATTER_SOURCE_CORRECTIONS_WITH_ADDITIVE_DECLARATION_VALIDITY',
        'missingAuthority':['Independent actual SootUp Type rank and local creation/cache/identity linkage for every persisted local; formatter-model projection is insufficient.'],
        'historicalUnsupportedSyntheticLocalCount':10410,'historicalCountIsFreshMeasurement':False}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference',type=Path);parser.add_argument('--actual',type=Path)
    parser.add_argument('--raw-local-types',action='store_true',help='Require both actual-writer raw no-fold Local.type inventories')
    parser.add_argument('--raw-edges',action='store_true',help='Export both original labeled edge streams during the existing topology proof')
    parser.add_argument('--output',type=Path,required=True);parser.add_argument('--audit-only',action='store_true')
    args=parser.parse_args()
    if not args.audit_only:
        require(args.reference and args.actual,'actual producer pair required')
        if execute(bind(args.reference,args.actual,args.output,args.raw_local_types,args.raw_edges))['status']!=PASS:return 1
    common.save(args.output/'audit.json',audit(args.output));return 0

if __name__=='__main__':sys.exit(main())
