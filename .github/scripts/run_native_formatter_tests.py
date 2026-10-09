#!/usr/bin/env python3
"""Run selected real production correctness tests on the actual candidate writer.

Uses the producer's existing isolated Gradle cache. No tests are copied into old
sources and no correctness duration contributes to performance acceptance.
"""
import argparse
import hashlib
import os
from pathlib import Path
import signal
import sys
import xml.etree.ElementTree as ET
import zipfile
sys.dont_write_bytecode=True
import audit_native_pressure_artifacts as artifacts
import multigraph_pressure as common
import produce_native_pressure_artifacts as producer
from export_native_core_strings import environment as clean_environment
from prepare_native_pressure_plan import preparation_control_pins

PACKAGE=Path(__file__).resolve().parent/'native_core_proof'
CONTRACT=PACKAGE/'formatter_test_contract.json'
INIT=PACKAGE/'formatter-test.init.gradle'
PASS='PASS_ACTUAL_WRITER_PRODUCTION_FORMATTER_TESTS'
AUDIT_PASS='PASS_INDEPENDENT_ACTUAL_WRITER_FORMATTER_TEST_AUDIT'
CORE_CLASSES=('io/johnsonlee/graphite/sootup/SootUpAdapter.class',
              'io/johnsonlee/graphite/sootup/ConstantFoldingKt.class',
              'io/johnsonlee/graphite/core/LocalVariable.class',
              'io/johnsonlee/graphite/core/TypeDescriptor.class',
              'io/johnsonlee/graphite/graph/DefaultGraph.class',
              'sootup/core/types/ArrayType.class')
PERSISTENCE_CLASSES=('io/johnsonlee/graphite/webgraph/GraphStore.class',
                     'io/johnsonlee/graphite/webgraph/NodeSerializer.class')
require=common.require


def bind(artifact_audit,source_inputs,output):
    path,inputs,out=(Path(p).resolve() for p in (artifact_audit,source_inputs,output))
    value=common.read(path);require(value['schema']=='graphite.native-independent-artifact-audit.v1' and
        value['status']==artifacts.STATUS and value['role'] in ('parent','candidate'),'actual non-baseline writer audit')
    source=common.pinned_authority_metadata(value,value['sourceManifest'])
    runtime=common.pinned_authority_metadata(value,value['runtimeManifest'])
    tools={key:runtime['toolchainIdentity'][key] for key in ('java','cargo','rustc')}
    actual=artifacts.audit(value['producerPacket']['path'],source['root'],value['revision'],value['role'],inputs,tools)
    require(common.typed(actual)==common.typed(value),'actual test writer artifact audit replay')
    root=Path(value['producerPacket']['path']).parent;checkout=Path(source['root'])
    require(not any(out.is_relative_to(p) for p in (checkout,root/'runtime',root/'graphs')),
            'correctness output outside measured source/runtime/graph roots')
    controls=preparation_control_pins()
    require(all(str(p) in controls for p in (Path(__file__).resolve(),CONTRACT,INIT)), 'reviewed production test controls')
    contract=common.read(CONTRACT);require(contract['schema']=='graphite.production-formatter-test-contract.v1' and
        contract['syntheticLocalInferenceOracleClaim'] is False,'bounded reviewed test contract')
    for row in contract['classes']:
        member=checkout/row['source']
        require(member.is_file() and not member.is_symlink() and common.sha(member)==row['sourceSha256'] and
                source['files'].get(str(member),source['files'].get(row['source']))==row['sourceSha256'],
                'actual writer lacks exact reviewed production test source: '+row['className'])
    packet=common.pinned_authority_metadata(value,value['producerPacket']);env=packet['buildEnvironment']
    require(env['JAVA_HOME']==str(Path(tools['java']).parent.parent) and
            env['JAVA_TOOL_OPTIONS']=='-Xmx4g -XX:ActiveProcessorCount=4','same bounded actual writer JDK')
    pins={**value['pins'],**controls,str(path):common.sha(path),str(inputs):common.sha(inputs)}
    return {'schema':'graphite.production-formatter-test-plan.v1','artifactAudit':artifacts.ref(path),
            'sourceInputs':artifacts.ref(inputs),'sourceManifest':value['sourceManifest'],
            'revision':value['revision'],'role':value['role'],'checkout':str(checkout),'writerJar':str(root/'runtime/writer.jar'),
            'java':tools['java'],'buildEnvironment':env,'output':str(out),'contract':artifacts.ref(CONTRACT),
            'initScript':artifacts.ref(INIT),'pins':pins,'performanceAcceptance':False,
            'syntheticLocalInferenceOracleClaim':False,'completeSemanticEquivalence':False}


def rebind(plan):return bind(plan['artifactAudit']['path'],plan['sourceInputs']['path'],plan['output'])


def environment(plan):
    # The original producer homes/cache are already independently audited. No
    # clean cache is created and no old checkout receives copied test sources.
    return {**clean_environment(plan['java']),**plan['buildEnvironment']}


def command(plan):
    home=str(Path(plan['java']).parent.parent)
    return [str(Path(plan['checkout'])/'gradlew'),'--no-daemon','--max-workers=2',
            '-Dorg.gradle.jvmargs=-Xmx4g -XX:ActiveProcessorCount=4',
            '-Pkotlin.compiler.execution.strategy=in-process',
            '-Dorg.gradle.java.installations.auto-detect=false',
            '-Dorg.gradle.java.installations.auto-download=false',
            '-Dorg.gradle.java.installations.paths='+home,
            '-Dgraphite.formatter.expectedJava='+plan['java'],
            '-Dgraphite.formatter.outputRoot='+str(Path(plan['output'])/'results'),
            '--init-script',plan['initScript']['path'],':sootup:test',':webgraph:test','--rerun-tasks']


def xml_results(plan):
    out=Path(plan['output']);expected=common.read(plan['contract']['path'])['classes'];rows=[]
    actual=set((out/'results/xml').rglob('*.xml'))
    require(actual=={out/'results/xml'/r['module']/('TEST-'+r['className']+'.xml') for r in expected},
            'exact selected production test XML closure')
    for row in expected:
        path=out/'results/xml'/row['module']/('TEST-'+row['className']+'.xml')
        require(path.is_file() and not path.is_symlink(),'regular raw JUnit XML')
        root=ET.fromstring(path.read_bytes());cases=root.findall('testcase')
        require(root.tag=='testsuite' and root.attrib['name']==row['className'] and
            int(root.attrib['tests'])==len(row['testCases'])==len(cases) and
            all(int(root.attrib.get(k,'0'))==0 for k in ('failures','errors','skipped')) and
            not root.findall('.//failure') and not root.findall('.//error') and not root.findall('.//skipped'),
            'all selected actual production tests completed without failure/skip')
        names=[case.attrib['name'].removesuffix('()') for case in cases]
        require(len(names)==len(set(names)) and set(names)==set(row['testCases']) and
            all(case.attrib.get('classname')==row['className'] for case in cases),'complete exact production test identities')
        rows.append({'className':row['className'],'tests':len(cases),'cases':names,'xml':artifacts.ref(path)})
    return rows


def classpath(plan,module):
    out=Path(plan['output']);before=out/f'results/classpath-{module}-before.json';after=out/f'results/classpath-{module}-after.json'
    value=common.read(before)
    require(value==common.read(after) and value['schema']=='graphite.formatter-test-classpath.v1' and
        value['java']==plan['java'] and value['maxHeapSize']=='4g' and value['maxParallelForks']==1 and
        value['jvmArgs'].count('-XX:ActiveProcessorCount=4')==1 and not any(x.startswith('-Xmx') for x in value['jvmArgs']),
        'stable actual test classpath and bounded direct JVM')
    pins={str(before):common.sha(before),str(after):common.sha(after)};ordered={};roots=[]
    with zipfile.ZipFile(plan['writerJar']) as jar:
        names=jar.namelist();writer={}
        for name in names:
            if name.endswith('.class') and name.startswith(('io/johnsonlee/graphite/','sootup/')):
                require(name not in writer,'duplicate actual writer class entry')
                writer[name]=hashlib.sha256(jar.read(name)).hexdigest()
    require(value['classpath'],'nonempty actual test classpath')
    for item in value['classpath']:
        path=Path(item['path']);require(path.is_absolute() and not path.is_symlink(),'absolute regular actual test classpath')
        require(str(path) not in roots,'duplicate actual classpath root');roots.append(str(path))
        if item['kind']=='absent':
            require(set(item)=={'path','kind'} and not path.exists(),'recorded absent classpath root appeared');continue
        if item['kind']=='directory':
            files=artifacts.inventory(path);expected={str(path/n):h for n,h in item['files'].items()}
            require(files==expected,'actual classpath directory drift');pins.update(files)
            for name,digest in item['files'].items():
                if name in writer:ordered.setdefault(name,digest)
        else:
            require(item['kind']=='file' and path.is_file() and common.sha(path)==item['sha256'],'actual classpath file drift')
            pins[str(path)]=item['sha256']
            with zipfile.ZipFile(path) as jar:
                seen=set()
                for name in jar.namelist():
                    if name in writer:
                        require(name not in seen,'duplicate test classpath class entry');seen.add(name)
                        ordered.setdefault(name,hashlib.sha256(jar.read(name)).hexdigest())
    require(all(ordered.get(n)==writer.get(n) and n in writer for n in
                CORE_CLASSES+(PERSISTENCE_CLASSES if module=='webgraph' else ())), 'required actual tested writer classes')
    require(all(writer[name]==digest for name,digest in ordered.items()),'tested production class bytes differ from writer.jar')
    return {'module':module,'comparedProductionClasses':ordered,'before':artifacts.ref(before),
            'after':artifacts.ref(after),'pins':pins}


def execute(plan):
    require(common.typed(plan)==common.typed(rebind(plan)),'test execution plan differs from actual writer')
    out=Path(plan['output']);out.mkdir(parents=True,exist_ok=False);(out/'results').mkdir()
    common.save(out/'plan.json',plan)
    record={'schema':'graphite.production-formatter-test-record.v1','status':'FAIL','plan':artifacts.ref(out/'plan.json'),
            'errors':[],'performanceAcceptance':False,'syntheticLocalInferenceOracleClaim':False,'completeSemanticEquivalence':False}
    previous={}
    def interrupted(signum,frame):raise InterruptedError('signal '+str(signum))
    try:
        for sig in (signal.SIGINT,signal.SIGTERM):previous[sig]=signal.signal(sig,interrupted)
        artifacts.verify_pins(plan['pins'])
        producer.phase('production-formatter-tests',command(plan),Path(plan['checkout']),environment(plan),out,3600)
        record['phase']=artifacts.ref(out/'production-formatter-tests/record.json')
        record['tests']=xml_results(plan);record['classpaths']=[classpath(plan,m) for m in ('sootup','webgraph')]
        record['status']=PASS
    except BaseException as error:record['errors'].append(repr(error))
    finally:
        for sig in previous:signal.signal(sig,signal.SIG_IGN)
        try:
            require(common.typed(plan)==common.typed(rebind(plan)),'actual source/runtime/fixtures changed during correctness tests')
            record['finalIdentity']='PASS'
        except BaseException as error:
            record['errors'].append('final verification: '+repr(error));record['finalIdentity']='FAIL'
        if record['errors']:record['status']='FAIL'
        common.save(out/'record.json',record)
        for sig,handler in previous.items():signal.signal(sig,handler)
    return record


def audit(output):
    out=Path(output).resolve();plan=common.read(out/'plan.json');record=common.read(out/'record.json')
    require(plan['output']==str(out) and record['schema']=='graphite.production-formatter-test-record.v1' and
        record['status']==PASS and record['errors']==[] and record['finalIdentity']=='PASS' and
        record['plan']==artifacts.ref(out/'plan.json') and all(record[k] is False for k in
        ('performanceAcceptance','syntheticLocalInferenceOracleClaim','completeSemanticEquivalence')),
        'completed actual production formatter tests')
    require(common.typed(plan)==common.typed(rebind(plan)),'production test plan differs from actual writer')
    phase=out/'production-formatter-tests/record.json';require(record['phase']==artifacts.ref(phase),'actual production test phase')
    checked=artifacts.check_phase(phase,command(plan),Path(plan['checkout']))
    require(checked['name']=='production-formatter-tests' and checked['timeoutSeconds']==3600,'exact bounded correctness phase')
    tests=xml_results(plan);paths=[classpath(plan,m) for m in ('sootup','webgraph')]
    require(record['tests']==tests and record['classpaths']==paths,'raw test bodies/classpaths differ from record')
    pins=dict(plan['pins'])
    for row in tests:pins[row['xml']['path']]=row['xml']['sha256']
    for row in paths:pins.update(row['pins'])
    for path in [out/'plan.json',out/'record.json',*(phase.parent/n for n in ('record.json','owner.json','stdout.log','stderr.log'))]:pins[str(path)]=common.sha(path)
    artifacts.verify_pins(pins)
    return {'schema':'graphite.production-formatter-test-audit.v1','status':AUDIT_PASS,
            'artifactAudit':plan['artifactAudit'],'sourceManifest':plan['sourceManifest'],'revision':plan['revision'],
            'writerJar':{'path':plan['writerJar'],'sha256':pins[plan['writerJar']]},'record':artifacts.ref(out/'record.json'),
            'tests':tests,'classpaths':paths,'pins':pins,'productionFormatterTestsVerified':True,
            'syntheticLocalInferenceOracleClaim':False,'completeSemanticEquivalence':False,'performanceAcceptance':False}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifact-audit',type=Path);parser.add_argument('--source-inputs',type=Path)
    parser.add_argument('--output',type=Path,required=True);parser.add_argument('--audit-only',action='store_true')
    args=parser.parse_args()
    if not args.audit_only:
        require(args.artifact_audit and args.source_inputs,'actual writer audit and source inputs required')
        if execute(bind(args.artifact_audit,args.source_inputs,args.output))['status']!=PASS:return 1
    common.save(args.output/'audit.json',audit(args.output));return 0


if __name__=='__main__':sys.exit(main())
