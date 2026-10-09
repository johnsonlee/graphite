#!/usr/bin/env python3
"""Independently inspect exact-revision producer artifacts, without importing the producer.

This checks build and file identity only. HTTP readiness, query correctness,
cross-arm semantic equivalence and performance acceptance are separate work.
"""
import argparse
import math
import re
import os
from pathlib import Path
import subprocess
import sys

sys.dont_write_bytecode = True
import multigraph_pressure as common
from prepare_native_pressure_plan import native_build_identity, preparation_control_pins

ACCEPTED = '4f2ccf33b969e684972e56b5e810034e6e67c1b3'
STATUS = 'PASS_ARTIFACTS_READINESS_AND_QUERY_PROOFS_PENDING'
SCRIPTS = Path(__file__).resolve().parent
CONSTRUCTION_SCOPE = 'fresh JVM through 64 saved graphs, persisted indexes, manifests, embedded readback validation and clean writer exit'
PROVENANCE_HEADER = ('graphId\tcorpus\tshard\tsourceJar\tsourceJarSha256\tshardBytecodeSha256\tclassCount\tnodeCount'
                     '\tcallSiteCount\tzeroTerm\ttargetedTerm\tdenseTerm\tquerySemanticSha256\tresourceCount'
                     '\tresourceSemanticSha256\tworkloadIdentity\tcallSiteIndexBytes\tcallSiteIndexSha256\tgraphPath')

def require(value, message):
    if not value: raise ValueError(message)


def ref(path):
    path=Path(path).resolve()
    return {'path':str(path),'sha256':common.sha(path)}


def inventory(root):
    root=Path(root)
    require(root.is_dir() and not root.is_symlink(),'closed inventory root')
    result={}
    for p in sorted(root.rglob('*')):
        require(not p.is_symlink(),'closed inventory symlink')
        if p.is_file():result[str(p.resolve())]=common.sha(p)
    require(result,'closed inventory empty')
    return result


def verify_pins(pins):
    for name,digest in pins.items():
        p=Path(name)
        require(p.is_absolute() and p.is_file() and not p.is_symlink() and
                common.valid_digest(digest) and common.sha(p)==digest,'actual pinned input changed: '+name)


def configs(checkout, env):
    paths=set()
    for root in (checkout,*checkout.parents):
        paths.update(root/'.cargo'/n for n in ('config','config.toml'))
        paths.update(root/n for n in ('rust-toolchain','rust-toolchain.toml'))
    paths.update(Path(env['CARGO_HOME'])/n for n in ('config','config.toml'))
    homes={Path(env['GRADLE_USER_HOME']),Path(env['HOME'])/'.gradle'}
    for root in homes:paths.update(root/n for n in ('gradle.properties','init.gradle','init.gradle.kts'))
    result={}
    for p in sorted(paths):
        require(not p.is_symlink() and (not p.exists() or p.is_file()),'configuration type')
        require(not p.exists() or p.is_relative_to(checkout),'foreign build configuration')
        result[str(p)]=common.sha(p) if p.exists() else None
    for root in homes:
        p=root/'init.d'
        require(not p.is_symlink() and (not p.exists() or p.is_dir() and not list(p.iterdir())),'Gradle init closure')
        result[str(p)]='EMPTY_DIRECTORY' if p.exists() else None
    return result


def source_inventory(checkout, revision):
    # Bounded read-only Git commands; disable fsmonitor/pager and external user config.
    env={**{k:v for k,v in common.clean_env().items() if not k.startswith('GIT_')},
         'GIT_CONFIG_NOSYSTEM':'1','GIT_CONFIG_GLOBAL':os.devnull,'GIT_TERMINAL_PROMPT':'0'}
    def git(*args):
        return subprocess.check_output(['git','--no-pager','-c','core.fsmonitor=false','-C',str(checkout),*args],
                                       env=env,timeout=30).decode().strip()
    require(git('rev-parse','HEAD')==revision and not git('status','--porcelain','--untracked-files=normal'),
            'actual clean source revision')
    result={}
    for name in git('ls-files').splitlines():
        p=checkout/name
        require(p.is_file() and not p.is_symlink() and p.resolve().is_relative_to(checkout),'tracked source type')
        result[str(p.resolve())]=common.sha(p)
    require(result,'empty tracked source')
    return result


def check_phase(path, expected_argv, checkout, construction=False):
    record=common.read(path);owner=common.read(path.parent/'owner.json')
    require(record['status']=='PASS' and not record['errors'],'raw phase failed')
    require(record['argv']==expected_argv,'raw actual command')
    expected_logs = ['stdout.log','stderr.log']
    if construction:
        launch = ['/usr/bin/time','-v','-o',str(path.parent/'time-v.log'),'--',*expected_argv]
        require(path.parent.name == 'prepare-real64' and record['launchArgv'] == owner['argv'] == launch and
                record['environmentOverrides'] == {'LC_ALL':'C'}, 'actual GNU time construction wrapper')
        raw = (path.parent/'time-v.log').read_text()
        require(re.findall(r'^\s*Exit status:\s*([0-9]+)\s*$',raw,re.M) == ['0'], 'resource child exit status')
        for label in ('User time (seconds)','System time (seconds)',
                      'Elapsed (wall clock) time (h:mm:ss or m:ss)','Maximum resident set size (kbytes)'):
            require(sum(line.strip().startswith(label+':') for line in raw.splitlines()) == 1,'unique GNU construction resource field')
        require(re.search(r'^\s*Maximum resident set size \(kbytes\):\s*[0-9]+\s*$',raw,re.M), 'integer GNU time peak RSS')
        resources = common.lifecycle_time(path.parent/'time-v.log')
        require(all(math.isfinite(resources[k]) and resources[k] >= 0 for k in ('realSeconds','userSeconds','systemSeconds')) and resources['peakRssBytes'] > 0,'valid construction resources')
        resources.update(scope=CONSTRUCTION_SCOPE,totalCpuSeconds=resources['userSeconds']+resources['systemSeconds'],
                         graphCount=64,heapMaxBytes=4*1024**3,activeProcessorCount=4,
                         cpuAccounting='GNU time waited JVM and its waited-for descendants; all JVM threads included',
                         rssAccounting='GNU time maximum process RSS; not a sum of concurrent process RSS',
                         performanceAcceptance=False)
        require(record['constructionResources'] == resources,'construction raw resources differ from summary')
        expected_logs.append('time-v.log')
    else:
        require(owner['argv']==expected_argv and not any(k in record for k in ('launchArgv','constructionResources','environmentOverrides')), 'unmeasured phase must remain unwrapped')
    require(type(owner['group']) is int and owner['group']>1 and type(owner['runnerPid']) is int and owner['runnerPid']>1,'owned PID identity')
    cleanup=record['cleanup']
    require(cleanup['group']==owner['group'] and cleanup['after']==[] and cleanup['errors']==[] and
            cleanup['exit'] ==0,'owned terminal cleanup')
    require(record['exit']==0 and record['cwd']==str(checkout) and record['timeoutSeconds']>0,'actual phase completion')
    expected_logs={str(path.parent/n):common.sha(path.parent/n) for n in expected_logs}
    require(record['logs']==expected_logs,'raw phase logs closed')
    return record


def expected_commands(root, checkout, runtime, inputs):
    ti=native_build_identity(runtime);java=ti['java']
    props=['-D'+key+'='+str(Path(item['path']).resolve()) for key,item in zip(
        ['android.jar.path','tika.jar.path','hive.jar.path','kotlin.compiler.jar.path'],inputs['jars'])]
    writer=[java,'-Xmx4g','-XX:ActiveProcessorCount=4',*props,'-cp',str(root/'runtime/writer.jar'),
            'io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation']
    return {'java-version':[java,'-Xmx512m','-XX:ActiveProcessorCount=4','-version'],
            'rustc-version':[ti['rustc'],'--version','--verbose'],'cargo-version':[ti['cargo'],'--version','--verbose'],
            'build-jvm':[str(checkout/'gradlew'),'--no-daemon','--max-workers=2',
                         '-Dorg.gradle.jvmargs=-Xmx4g -XX:ActiveProcessorCount=4',
                         '-Pkotlin.compiler.execution.strategy=in-process',':webgraph:jmhJar',':query:shadowJar'],
            'build-native':runtime['buildArgv'],'prepare-real64':[*writer,str(root/'graphs')],
            'verify-real64':[*writer,'--verify',str(root/'graphs/graphs.tsv'),str(root/'graphs/fixture-provenance.tsv')]}


def verify_fixture(root, fixture, runtime, inputs, catalog, revision):
    require(fixture['writerRevision']==revision and fixture['writerJarSha256']==runtime['files'][str(root/'runtime/writer.jar')], 'own revision writer')
    require(inventory(root/'graphs')==fixture['files'],'closed complete fixture output')
    lines = (root/'graphs/fixture-provenance.tsv').read_text().splitlines()
    require(lines and lines[0] == PROVENANCE_HEADER, 'fixture provenance header')
    rows=[line.split('\t') for line in lines[1:]]
    source=common.read(inputs)
    require(source['schema']=='graphite.fixture64-source-inputs.v1' and
            [j['corpus'] for j in source['jars']]==['android','tika','hive','kotlin-compiler'],'same four input JARs')
    reference=source['referenceProvenance'];verify_pins({reference['path']:reference['sha256']})
    reference_lines = Path(reference['path']).read_text().splitlines()
    require(reference_lines and reference_lines[0] == PROVENANCE_HEADER, 'reference provenance header')
    reference_rows=[line.split('\t') for line in reference_lines[1:]]
    ids=catalog['engines']['native']['graphIds']
    require(len(rows)==len(reference_rows)==64 and all(len(x)==19 for x in rows+reference_rows) and
            [x[0] for x in rows]==[x[0] for x in reference_rows]==ids,'full ordered source/provenance scope')
    jarhash={j['corpus']:j['sha256'] for j in source['jars']}
    verify_pins({j['path']:j['sha256'] for j in source['jars']})
    manifest=[line.split('\t') for line in (root/'graphs/graphs.tsv').read_text().splitlines() if not line.startswith('#')]
    require(len(manifest)==64 and all(len(x)==6 for x in manifest),'full graph manifest')
    require(len(fixture['graphs']) == 64, 'complete fixture graph records')
    graphs=[]
    for row,other,line,record in zip(rows,reference_rows,manifest,fixture['graphs']):
        directory=root/'graphs'/row[0]
        require(directory.is_dir() and not directory.is_symlink() and
                int(row[6]) > 0 and int(row[7]) > 0 and int(row[8]) > 0,
                'nonempty actual graph directory')
        require(row[1]==other[1] and row[4]==other[4]==jarhash[row[1]],'same corpus source bytes')
        require(all(common.valid_digest(row[i]) for i in (4, 5, 12, 14, 15, 17)),
                'complete graph fingerprint fields')
        expected={'id':row[0],'path':str(directory),'nodes':int(row[7]),'callSites':int(row[8]),
                  'workloadIdentitySha256':row[15],'querySemanticSha256':row[12]}
        require(record==expected and Path(line[1]).resolve()==Path(row[18]).resolve()==directory and
                line[0]==row[0] and line[5]==row[15],'actual own graph/provenance identity')
        graphs.append({'id':row[0],'path':str(directory)})
    require(len(fixture['graphs'])==64 and fixture['inputJars']==source['jars'],'complete fixture input binding')
    require(len({row[12] for row in rows}) == 64, 'distinct real graph workloads')
    return graphs,source


def audit(packet_path, checkout, revision, role, inputs_path, tools, require_construction_metrics=False):
    packet_path, checkout, inputs_path = (Path(path).resolve() for path in
                                         (packet_path, checkout, inputs_path))
    root = packet_path.parent
    require(len(revision) == 40 and all(c in '0123456789abcdef' for c in revision), 'full revision')
    require(role in ('accepted-baseline', 'parent', 'candidate') and
            (role != 'accepted-baseline' or revision == ACCEPTED), 'actual producer role/revision')
    require(not root.is_relative_to(checkout), 'output outside source checkout')
    control_pins = preparation_control_pins()
    require(str(Path(__file__).resolve()) in control_pins, 'auditor is a reviewed control')
    metadata = {str(path): common.sha(path) for path in
                (packet_path, root / 'source-manifest.json', root / 'runtime-manifest.json',
                 root / 'fixture-manifest.json', root / 'inputs-before.json', inputs_path)}
    packet = common.read(packet_path)
    require(packet['schema'] == 'graphite.native-artifact-producer.v1' and
            packet['status'] == 'ARTIFACTS_COMPLETE_INDEPENDENT_PROOFS_PENDING' and
            packet['revision'] == revision and packet['role'] == role and packet['errors'] == [] and
            all(packet[key] is False for key in
                ('independentlyAudited', 'acceptanceEligible')),
            'completed bounded artifact producer')
    construction = 'constructionMeasurement' in packet
    require(not require_construction_metrics or construction, 'required real64 construction resources missing')
    require(packet['performanceMeasurement'] is construction, 'explicit construction measurement scope')
    source = common.read(root / 'source-manifest.json')
    runtime = common.read(root / 'runtime-manifest.json')
    fixture = common.read(root / 'fixture-manifest.json')
    require(Path(source['root']).resolve() == checkout and source['revision'] == runtime['revision'] == revision,
            'requested checkout and revision')
    source_before = source_inventory(checkout, revision)
    source_digest = metadata[str(root / 'source-manifest.json')]
    require(source_before == source['files'] == runtime['sourceFiles'] and
            runtime == packet['runtimeManifest'] and
            runtime['sourceManifestSha256'] == fixture['sourceManifestSha256'] == source_digest,
            'complete source/runtime/writer manifest chain')
    pins = common.read(root / 'inputs-before.json')
    verify_pins(pins)
    require(pins.get(str(inputs_path)) == metadata[str(inputs_path)], 'actual source input manifest')
    for name in ('produce_native_pressure_artifacts.py', 'multigraph_pressure.py', 'native_legal_response.py'):
        path = str(SCRIPTS / name)
        require(pins.get(path) == control_pins[path], 'executed reviewed producer dependency')
    if construction:
        version = root/'construction-time-version.txt'
        require(packet['constructionMeasurement'] == {'tool':'/usr/bin/time','versionFile':str(version),
                'scope':CONSTRUCTION_SCOPE,'performanceAcceptance':False}, 'bounded GNU time construction scope')
        require('/usr/bin/time' in pins and str(version) in pins and version.read_text().startswith('time (GNU Time)'), 'pinned actual GNU time implementation/version')
    identity = native_build_identity(runtime)
    require({key: identity[key] for key in tools} == tools and set(tools) == {'java', 'cargo', 'rustc'},
            'requested direct toolchain')
    for path in [*tools.values(), *(str(Path(tools['java']).parent.parent / name)
                                   for name in ('bin/javac', 'release', 'lib/modules'))]:
        require(path in pins, 'full selected JDK/toolchain input identity')
    env = packet['buildEnvironment']
    require(env['JAVA_TOOL_OPTIONS'] == '-Xmx4g -XX:ActiveProcessorCount=4' and
            env['JAVA_HOME'] == str(Path(tools['java']).parent.parent) and env['RUSTC'] == tools['rustc'],
            'bounded actual build tools')
    for key, name in [('HOME', 'user-home'), ('CARGO_HOME', 'cargo-home'),
                      ('GRADLE_USER_HOME', 'gradle-home'), ('CARGO_TARGET_DIR', 'native-target')]:
        require(env[key] == str(root / name), 'isolated build home/target')
    require(configs(checkout, env) == packet['configurationBefore'] == packet['configurationAfter'],
            'complete build configuration closure')
    require(set(runtime['files']) == {str(root / 'runtime' / name)
                                     for name in ('writer.jar', 'graphite.jar', 'graphite')} and
            runtime['binary'] == str(root / 'runtime/graphite') and
            inventory(root / 'runtime') == runtime['files'] and
            runtime['files'][runtime['binary']] == runtime['sha256'], 'complete actual runtime bytes')
    writers = list((checkout / 'frontend/jvm/webgraph/build/libs').glob('*-jmh.jar'))
    require(len(writers) == 1, 'single source-built writer')
    originals = {str(writers[0]): str(root / 'runtime/writer.jar'),
                 str(checkout / 'frontend/jvm/query/build/libs/graphite.jar'): str(root / 'runtime/graphite.jar'),
                 str(root / 'native-target' / identity['target'] / 'release/graphite'): runtime['binary']}
    require(runtime['originalArtifacts'] == {path: runtime['files'][copy] for path, copy in originals.items()},
            'original build output locations and unchanged runtime copies')
    verify_pins(runtime['originalArtifacts'])
    catalog = common.read(SCRIPTS / 'fixtures/multigraph-pressure-cases.json')
    graphs, inputs = verify_fixture(root, fixture, runtime, inputs_path, catalog, revision)
    require(fixture['graphs'] == packet['graphs'], 'complete actual fixture receipt')
    for jar in inputs['jars']:
        require(pins.get(str(Path(jar['path']).resolve())) == jar['sha256'], 'build input JAR pin')
    reference = inputs['referenceProvenance']
    require(pins.get(str(Path(reference['path']).resolve())) == reference['sha256'], 'build reference provenance pin')
    commands = expected_commands(root, checkout, runtime, inputs)
    receipts = packet['phaseReceipts']
    require(set(receipts) == {str(root / name / 'record.json') for name in commands}, 'exact seven build/writer phases')
    verify_pins(receipts)
    phases = [check_phase(root / name / 'record.json', argv, checkout, construction and name == 'prepare-real64') for name, argv in commands.items()]
    require(packet['phases'] == phases, 'ordered full raw phase records')
    require((root / 'rustc-version/stdout.log').read_text() == identity['rustcVersion'] and
            (root / 'cargo-version/stdout.log').read_text() == identity['cargoVersion'], 'actual compiler version output')
    owners = [common.read(root / name / 'owner.json') for name in commands]
    require(len({owner['group'] for owner in owners}) == 7 and
            len({owner['runnerPid'] for owner in owners}) == 1, 'owned producer phase lineage')
    expected_final = {key: 'PASS' for key in
                      ('source', 'inputs', 'runtime', 'fixtures', 'originalArtifacts', 'configuration')}
    require(packet['finalIdentity'] == expected_final, 'producer final identities')
    evidence = {**control_pins, **metadata, **pins, **receipts, **source_before,
                **runtime['files'], **runtime['originalArtifacts'], **fixture['files']}
    for name in commands:
        for leaf in ('owner.json', 'stdout.log', 'stderr.log', *(['time-v.log'] if construction and name == 'prepare-real64' else [])):
            path = root / name / leaf
            evidence[str(path)] = common.sha(path)
    # A final complete recheck also detects mutation while the auditor is reading.
    verify_pins(evidence)
    require(source_inventory(checkout, revision) == source_before and
            inventory(root / 'runtime') == runtime['files'] and
            inventory(root / 'graphs') == fixture['files'] and
            configs(checkout, env) == packet['configurationAfter'], 'audit final closed inventories')
    result = {'schema': 'graphite.native-independent-artifact-audit.v1', 'status': STATUS,
            'revision': revision, 'role': role, 'producerPacket': ref(packet_path), 'auditor': ref(__file__),
            'sourceManifest': ref(root / 'source-manifest.json'),
            'runtimeManifest': ref(root / 'runtime-manifest.json'),
            'fixtureManifest': ref(root / 'fixture-manifest.json'),
            'sourceManifestSha256': source_digest,
            'runtimeManifestSha256': metadata[str(root / 'runtime-manifest.json')],
            'binarySha256': runtime['sha256'], 'sourceBefore': source_before, 'sourceAfter': source_before,
            'writerRevision': revision, 'graphs': graphs, 'phaseCount': len(phases), 'pins': evidence,
            'readinessVerified': False, 'queryCorrectnessVerified': False,
            'completeSemanticEquivalence': False, 'performanceAcceptance': False, 'acceptanceEligible': False}
    if construction:
        result['constructionResources'] = phases[5]['constructionResources']
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('packet', 'checkout', 'revision', 'role', 'source-inputs', 'java', 'cargo', 'rustc', 'output'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--require-construction-metrics', action='store_true')
    args = parser.parse_args()
    output = Path(args.output).resolve()
    require(not output.exists(), 'fresh audit output required')
    try:
        result = audit(args.packet, args.checkout, args.revision, args.role, args.source_inputs,
                       {key: str(Path(getattr(args, key)).resolve()) for key in ('java', 'cargo', 'rustc')},
                       args.require_construction_metrics)
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        common.save(output, {'schema': 'graphite.native-independent-artifact-audit.v1', 'status': 'FAIL',
                             'errors': [repr(error)], 'acceptanceEligible': False})
        return 1
    common.save(output, result)
    return 0


if __name__ == '__main__':
    sys.exit(main())
