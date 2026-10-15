#!/usr/bin/env python3
"""Single-arm original-ID topology exports for JVM oracles, never core proof."""
import argparse
from contextlib import contextmanager
import os
from pathlib import Path
import signal
import sys
import threading
import audit_native_pressure_artifacts as artifacts
import export_native_core_strings as strings
import jvm_pressure_inputs as inputs
import jvm_pressure_oracles as model
import multigraph_pressure as common
import produce_native_pressure_artifacts as producer
from assemble_native_pressure_producers import merge_pins

SOURCE=Path(__file__).resolve().with_name('ExportJvmRawEdges.java')
PASS='PASS_ALL64_OWN_WRITER_RAW_EDGES_NOT_CORE_EQUIVALENCE'
AUDIT='PASS_ALL64_RAW_EDGE_OWNED_EXECUTION_AUDIT'
require=common.require


def bind(producer_root, output):
    root,out=Path(producer_root).resolve(),Path(output).resolve()
    exports=strings.audit(root/'core-string-exports')
    require(common.typed(common.read(root/'core-string-exports/audit.json'))==common.typed(exports),'actual string export audit')
    pins=dict(exports['pins']); ref=artifacts.ref(root/'core-string-exports/audit.json');merge_pins(pins,{ref['path']:ref['sha256']})
    artifact=common.pinned_authority_metadata({'pins':pins},exports['artifactAudit'])
    source=common.pinned_authority_metadata({'pins':pins},artifact['sourceManifest'])
    fixture=common.pinned_authority_metadata({'pins':pins},artifact['fixtureManifest'])
    original=common.pinned_authority_metadata({'pins':pins},exports['plan'])
    require(exports['artifactAudit']==artifacts.ref(root/'artifact-audit.json'),'same own-writer producer')
    require([g['id'] for g in fixture['graphs']]==list(model.FIXTURE_GRAPH_IDS),'complete ordered64 export scope')
    require(not any(out.is_relative_to(Path(p)) for p in (source['root'],root/'graphs',root/'runtime',root/'core-string-exports')),'raw exports outside immutable inputs')
    graphs=[]
    for graph in fixture['graphs']:
        spec=inputs.graph_inputs(fixture,exports,graph['id'])
        refs={name:{'path':str(Path(spec['root'])/name),'sha256':pins[str(Path(spec['root'])/name)]} for name in inputs.TOPOLOGY_FILES}
        graphs.append({'id':graph['id'],'root':spec['root'],'sourceInputs':refs})
    for p in (Path(__file__).resolve(),SOURCE):merge_pins(pins,{str(p):common.sha(p)})
    return {'schema':'graphite.jvm-raw-edge-export-plan.v1','producerRoot':str(root),'output':str(out),
            'stringAudit':ref,'artifactAudit':exports['artifactAudit'],'revision':artifact['revision'],'role':artifact['role'],
            **{k:artifact[k] for k in ('sourceManifest','runtimeManifest','fixtureManifest')},
            **{k:original[k] for k in ('java','javac','writerJar')},'helperSource':artifacts.ref(SOURCE),
            'graphs':graphs,'pins':pins,'performanceAcceptance':False,'completeCoreTopologyIndexComparison':False}


def commands(plan):
    out=Path(plan['output'])
    return [('compile-raw-edges',[plan['javac'],'-J-Xmx4g','-J-XX:ActiveProcessorCount=4','-proc:none','-cp',plan['writerJar'],'-d',str(out/'classes'),plan['helperSource']['path']],180),
            ('export-raw-edges',[plan['java'],'-Xmx4g','-XX:ActiveProcessorCount=4','-cp',str(out/'classes')+os.pathsep+plan['writerJar'],'ExportJvmRawEdges',str(out/'plan.json')],1800)]


def rows(plan):
    out=Path(plan['output']); clazz=out/'classes/ExportJvmRawEdges.class'
    require({p.name for p in clazz.parent.iterdir()}=={clazz.name},'exact exporter class closure')
    require({p.name for p in (out/'exports').iterdir()}=={g['id'] for g in plan['graphs']},'exact64 raw output directories')
    pins=dict(plan['pins']);merge_pins(pins,{str(clazz):common.sha(clazz)})
    result=[]
    for graph in plan['graphs']:
        directory=out/'exports'/graph['id'];receipt=common.read(directory/'receipt.json')
        require({p.name for p in directory.iterdir()}=={'edges.bin','receipt.json'} and all(p.is_file() and not p.is_symlink() for p in directory.iterdir()),'exact raw output closure')
        require(receipt['output']==artifacts.ref(directory/'edges.bin') and receipt['helperSource']==plan['helperSource'] and receipt['helperClass']==artifacts.ref(clazz),'owned output/helper bindings')
        raw=inputs.edge_export_input(receipt,graph['root'],pins,pins)
        # Decode every original edge, including ordering/end-of-stream checks.
        list_count=sum(1 for _ in inputs.edge_records(raw,range(receipt['nodeSlots']),receipt['nodeSlots'],receipt['labeledEdges']))
        require(list_count==receipt['labeledEdges'],'complete raw edge count')
        result.append({'id':graph['id'],'edges':receipt,'receipt':artifacts.ref(directory/'receipt.json')})
    return result


@contextmanager
def progress_log(path):
    """Forward the helper's bounded progress records; stdout remains original evidence."""
    stop=threading.Event()
    def follow():
        offset=0
        while True:
            try:
                with path.open() as stream:
                    stream.seek(offset)
                    while line:=stream.readline():
                        if line.startswith('raw-edge-export '):print(line.rstrip(),flush=True)
                    offset=stream.tell()
            except (OSError,ValueError):pass
            if stop.wait(.2):break
        try:
            with path.open() as stream:
                stream.seek(offset)
                for line in stream:
                    if line.startswith('raw-edge-export '):print(line.rstrip(),flush=True)
        except (OSError,ValueError):pass
    worker=threading.Thread(target=follow,daemon=True);worker.start()
    try:yield
    finally:stop.set();worker.join(timeout=2)


def run(producer_root,output):
    plan=bind(producer_root,output);out=Path(plan['output']);out.mkdir(parents=True,exist_ok=False)
    (out/'classes').mkdir();(out/'exports').mkdir();common.save(out/'plan.json',plan)
    record={'schema':'graphite.jvm-raw-edge-export-record.v1','status':'FAIL','plan':artifacts.ref(out/'plan.json'),
            'phases':[],'errors':[],'performanceAcceptance':False,'completeCoreTopologyIndexComparison':False}
    previous={}
    def interrupted(signum,frame):raise InterruptedError('signal '+str(signum))
    try:
        for sig in (signal.SIGINT,signal.SIGTERM):previous[sig]=signal.signal(sig,interrupted)
        artifacts.verify_pins(plan['pins'])
        for name,argv,timeout in commands(plan):
            with progress_log(out/name/'stdout.log'):
                producer.phase(name,argv,out,strings.environment(plan['java']),out,timeout)
            record['phases'].append(artifacts.ref(out/name/'record.json'))
        record['graphs']=rows(plan);record['status']=PASS
    except BaseException as error:record['errors'].append(repr(error))
    finally:
        for sig in previous:signal.signal(sig,signal.SIG_IGN)
        try:artifacts.verify_pins(plan['pins']);record['finalIdentity']='PASS'
        except BaseException as error:record['errors'].append(repr(error));record['finalIdentity']='FAIL'
        if record['errors']:record['status']='FAIL'
        common.save(out/'record.json',record)
        for sig,handler in previous.items():signal.signal(sig,handler)
    return record


def audit(output):
    out=Path(output).resolve();plan=common.read(out/'plan.json');record=common.read(out/'record.json')
    require(plan['output']==str(out) and common.typed(plan)==common.typed(bind(plan['producerRoot'],out)),'raw export rebind')
    require(record['schema']=='graphite.jvm-raw-edge-export-record.v1' and record['status']==PASS and record['errors']==[] and record['finalIdentity']=='PASS' and record['plan']==artifacts.ref(out/'plan.json'),'complete owned raw export')
    require(record['performanceAcceptance'] is False and record['completeCoreTopologyIndexComparison'] is False,'raw scope cannot authorize core')
    require(len(record['phases'])==2,'exact two owned phases')
    for ref,(name,argv,timeout) in zip(record['phases'],commands(plan)):
        require(ref==artifacts.ref(out/name/'record.json'),'owned phase reference')
        phase=artifacts.check_phase(Path(ref['path']),argv,out)
        require(phase['name']==name and phase['timeoutSeconds']==timeout,'owned phase contract')
    graphs=rows(plan);require(common.typed(record['graphs'])==common.typed(graphs),'raw output records')
    pins=dict(plan['pins']); inventory=producer.inventory(out); inventory.pop(str(out/'audit.json'),None)
    merge_pins(pins,inventory);artifacts.verify_pins(pins)
    return {'schema':'graphite.jvm-raw-edge-export-audit.v1','status':AUDIT,'plan':artifacts.ref(out/'plan.json'),
            'record':artifacts.ref(out/'record.json'),'graphs':graphs,'pins':pins,
            **{k:plan[k] for k in ('revision','role','sourceManifest','runtimeManifest','fixtureManifest','artifactAudit','stringAudit')},
            'performanceAcceptance':False,'completeCoreTopologyIndexComparison':False}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--producer-root',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();result=run(args.producer_root,args.output)
    if result['status']!=PASS:print(result);sys.exit(1)
    common.save(args.output/'audit.json',audit(args.output))
