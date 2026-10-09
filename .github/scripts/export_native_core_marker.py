#!/usr/bin/env python3
"""Export the exact producer JDK Serializable marker for the existing field oracle.

This is a narrow bootstrap resource authority, never a missing-class waiver or
an independent proof of SootUp type inference. Actual artifact audits are replayed.
"""
import argparse
import os
from pathlib import Path
import signal
import sys

sys.dont_write_bytecode = True
import audit_native_pressure_artifacts as artifacts
import multigraph_pressure as common
import produce_native_pressure_artifacts as producer
from prepare_native_pressure_plan import preparation_control_pins
from export_native_core_strings import environment
from native_core_proof import marker_authority

SOURCE = Path(__file__).resolve().parent/'native_core_proof/ExportSerializable.java'
SOURCE_SHA = 'c322dd8096d437eb435ac89df099ce2e535ceccbba9f309bc65d89da7d4a5a87'
PASS = 'PASS_EXACT_PRODUCER_BOOTSTRAP_MARKER_EXPORT'
AUDIT_PASS = 'PASS_EXACT_PRODUCER_BOOTSTRAP_MARKER_INDEPENDENT_AUDIT'
require = common.require


def bind(reference_audit, actual_audit, source_inputs, output):
    inputs, out = Path(source_inputs).resolve(), Path(output).resolve()
    pins = dict(preparation_control_pins()); arms = {}; java = None; jar = None; scope = None
    require(str(Path(__file__).resolve()) in pins and pins.get(str(SOURCE)) == SOURCE_SHA,
            'reviewed marker exporter and helper controls')
    for arm, path in [('C', reference_audit), ('B', actual_audit)]:
        path = Path(path).resolve(); value = common.read(path)
        require(value['schema'] == 'graphite.native-independent-artifact-audit.v1' and
                value['status'] == artifacts.STATUS, 'completed actual artifact audit')
        source = common.pinned_authority_metadata(value,value['sourceManifest'])
        runtime = common.pinned_authority_metadata(value,value['runtimeManifest'])
        tools = {key: runtime['toolchainIdentity'][key] for key in ('java','cargo','rustc')}
        check = artifacts.audit(value['producerPacket']['path'],source['root'],value['revision'],
                                value['role'],inputs,tools)
        require(common.typed(check) == common.typed(value), 'stored artifact audit differs from actual producer')
        require(value['role'] == 'accepted-baseline' if arm == 'C' else
                value['role'] in ('accepted-baseline','parent','candidate'), 'actual comparison arm role')
        root = Path(value['producerPacket']['path']).parent
        require(not any(out.is_relative_to(Path(p)) for p in (source['root'],root/'runtime',root/'graphs')),
                'marker output outside actual source/runtime/graph roots')
        ids = [row['id'] for row in value['graphs']]
        require(len(ids) == len(set(ids)) == 64 and (scope is None or ids == scope), 'matched ordered64 producer scope')
        scope = ids
        require(java is None or java == tools['java'], 'same actual producer JDK path')
        java = tools['java']; jar = root/'runtime/writer.jar'
        for key,digest in {**value['pins'],str(path):common.sha(path)}.items():
            require(key not in pins or pins[key] == digest,'conflicting producer input pin')
            pins[key] = digest
        arms[arm] = {'artifactAudit':artifacts.ref(path),'role':value['role'],'revision':value['revision'],
                     'producerPacket':value['producerPacket']}
    home = Path(java).parent.parent; modules = str(home/'lib/modules'); javac = str(home/'bin/javac')
    require(all(p in pins for p in (java,javac,modules,str(jar))), 'actual JDK/module/writer pins')
    require(common.sha(SOURCE) == SOURCE_SHA,'exact retained bootstrap export helper')
    pins.update({str(SOURCE):SOURCE_SHA,str(inputs):common.sha(inputs)})
    return {'schema':'graphite.native-core-marker-export-plan.v1','arms':arms,
            'sourceInputs':artifacts.ref(inputs),'output':str(out),'java':java,'javac':javac,
            'javaHome':str(home),'modules':{'path':modules,'sha256':pins[modules]},'writerJar':str(jar),
            'helperSource':artifacts.ref(SOURCE),'pins':pins,'graphIds':scope,
            'performanceAcceptance':False,'completeSemanticEquivalence':False,
            'syntheticLocalInferenceOracleClaim':False}


def rebind(plan):
    return bind(plan['arms']['C']['artifactAudit']['path'],plan['arms']['B']['artifactAudit']['path'],
                plan['sourceInputs']['path'],plan['output'])


def commands(plan):
    out=Path(plan['output']); cp=str(out/'classes')+os.pathsep+plan['writerJar']
    return [('compile-bootstrap-marker',[plan['javac'],'-J-Xmx4g','-J-XX:ActiveProcessorCount=4',
             '-proc:none','-cp',plan['writerJar'],'-d',str(out/'classes'),plan['helperSource']['path']],180),
            ('export-bootstrap-marker',[plan['java'],'-Xmx4g','-XX:ActiveProcessorCount=4','-cp',cp,
             'ExportSerializable',plan['javaHome'],plan['modules']['sha256'],str(out/'Serializable.class'),
             str(out/'receipt.json')],180)]


def compiled(plan):
    root=Path(plan['output'])/'classes'; files=artifacts.inventory(root)
    require(set(files)=={str(root/'ExportSerializable.class')},'exact compiled marker helper')
    return files


def raw_marker(plan):
    out=Path(plan['output']); receipt=common.read(out/'receipt.json'); path=out/'Serializable.class'
    require(path.is_file() and not path.is_symlink() and not (out/'receipt.json').is_symlink(),
            'regular actual bootstrap export outputs')
    require(receipt == {'status':'PASS_EXACT_BOOTSTRAP_SERIALIZABLE_EXPORT',
        'className':'java.io.Serializable','module':'java.base','resource':'java/io/Serializable.class',
        'javaHome':plan['javaHome'],'modulesPath':plan['modules']['path'],
        'modulesSha256':plan['modules']['sha256'],'rawClassFile':str(path),
        'classBytesSha256':common.sha(path),'bootstrapClassLoader':True,'performanceClaim':False},
        'exact actual bootstrap module receipt')
    marker_authority.marker_class(path.read_bytes())
    return {'rawClass':artifacts.ref(path),'receipt':artifacts.ref(out/'receipt.json')}


def execute(plan):
    require(common.typed(plan)==common.typed(rebind(plan)),'execution plan differs from actual producer inputs')
    out=Path(plan['output']);out.mkdir(parents=True,exist_ok=False);(out/'classes').mkdir()
    common.save(out/'plan.json',plan)
    record={'schema':'graphite.native-core-marker-export-record.v1','status':'FAIL',
            'plan':artifacts.ref(out/'plan.json'),'phases':[],'errors':[],
            'performanceAcceptance':False,'completeSemanticEquivalence':False,
            'syntheticLocalInferenceOracleClaim':False}
    previous={}; helper={}; payload={}
    def interrupted(signum,frame):raise InterruptedError('signal '+str(signum))
    try:
        for sig in (signal.SIGINT,signal.SIGTERM):previous[sig]=signal.signal(sig,interrupted)
        artifacts.verify_pins(plan['pins'])
        for index,(name,argv,timeout) in enumerate(commands(plan)):
            producer.phase(name,argv,out,environment(plan['java']),out,timeout)
            record['phases'].append(artifacts.ref(out/name/'record.json'))
            if index==0:helper=compiled(plan)
            else:payload=raw_marker(plan)
        record['status']=PASS
    except BaseException as error:record['errors'].append(repr(error))
    finally:
        for sig in previous:signal.signal(sig,signal.SIG_IGN)
        try:
            artifacts.verify_pins(plan['pins'])
            if helper:require(compiled(plan)==helper,'compiled helper changed')
            if payload:require(raw_marker(plan)==payload,'marker output changed')
            record['finalIdentity']='PASS'
        except BaseException as error:
            record['errors'].append('final verification: '+repr(error));record['finalIdentity']='FAIL'
        if record['errors']:record['status']='FAIL'
        record.update(compiledHelper=helper,**payload);common.save(out/'record.json',record)
        for sig,handler in previous.items():signal.signal(sig,handler)
    return record


def audit(output):
    out=Path(output).resolve();plan=common.read(out/'plan.json');record=common.read(out/'record.json')
    require(plan['output']==str(out) and record['schema']=='graphite.native-core-marker-export-record.v1' and
            record['status']==PASS and record['errors']==[] and record['finalIdentity']=='PASS' and
            record['plan']==artifacts.ref(out/'plan.json') and all(record[k] is False for k in
            ('performanceAcceptance','completeSemanticEquivalence','syntheticLocalInferenceOracleClaim')),
            'completed actual bootstrap export')
    require(common.typed(plan)==common.typed(rebind(plan)),'marker plan differs from actual producer inputs')
    helper=compiled(plan);payload=raw_marker(plan)
    require(record['compiledHelper']==helper and all(record[k]==v for k,v in payload.items()),'actual marker output binding')
    require(len(record['phases'])==2,'exact two owned marker phases')
    pins={**plan['pins'],**helper}
    for ref,(name,argv,timeout) in zip(record['phases'],commands(plan)):
        path=out/name/'record.json';require(ref==artifacts.ref(path),'actual marker phase receipt')
        phase=artifacts.check_phase(path,argv,out)
        require(phase['name']==name and phase['timeoutSeconds']==timeout,'exact bounded marker phase')
        for leaf in ('record.json','owner.json','stdout.log','stderr.log'):
            file=path.parent/leaf;pins[str(file)]=common.sha(file)
    for path in (out/'plan.json',out/'record.json',out/'receipt.json',out/'Serializable.class'):
        pins[str(path)]=common.sha(path)
    artifacts.verify_pins(pins)
    return {'schema':'graphite.native-core-marker-export-audit.v1','status':AUDIT_PASS,
            'plan':artifacts.ref(out/'plan.json'),'record':artifacts.ref(out/'record.json'),
            'arms':plan['arms'],'modules':plan['modules'],**payload,'pins':pins,
            'performanceAcceptance':False,'completeSemanticEquivalence':False,
            'syntheticLocalInferenceOracleClaim':False}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference-audit',type=Path);parser.add_argument('--actual-audit',type=Path)
    parser.add_argument('--source-inputs',type=Path);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--audit-only',action='store_true');args=parser.parse_args()
    if not args.audit_only:
        require(all((args.reference_audit,args.actual_audit,args.source_inputs)),'actual producer pair and inputs required')
        if execute(bind(args.reference_audit,args.actual_audit,args.source_inputs,args.output))['status']!=PASS:return 1
    common.save(args.output/'audit.json',audit(args.output));return 0


if __name__=='__main__':sys.exit(main())
