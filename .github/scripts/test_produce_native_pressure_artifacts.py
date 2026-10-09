import contextlib
import copy
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import produce_native_pressure_artifacts as producer
from types import SimpleNamespace


class ProducerTests(unittest.TestCase):
    def graph_rows(self, root):
        return [{'id': f'fixture-{kind}-{i:02d}', 'path': str(root / f'fixture-{kind}-{i:02d}'),
                 'nodes': 10 + i, 'callSites': 3} for kind in producer.CORPORA for i in range(16)]

    def ready(self, root):
        graphs = self.graph_rows(root)
        records = [{**g, 'loadMode': 'MAPPED', 'loadedAt': '2026-10-09T00:00:00Z', 'edges': 5, 'methods': 2}
                   for g in graphs]
        return graphs, {'data': str(root / 'data'), 'loadMode': 'MAPPED', 'count': 64, 'graphs': records,
                        'totals': {key: sum(g[key] for g in records) for key in ('nodes', 'edges', 'methods', 'callSites')}}

    def test_provenance_requires_exact_ordered_64(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'provenance.tsv'
            rows = ['header'] + ['\t'.join([g['id']] + ['x'] * 18) for g in self.graph_rows(Path(directory))]
            path.write_text('\n'.join(rows) + '\n')
            self.assertEqual(64, len(producer.read_provenance(path)))
            rows[4], rows[5] = rows[5], rows[4]
            path.write_text('\n'.join(rows) + '\n')
            with self.assertRaises(ValueError): producer.read_provenance(path)

    def test_same_jar_proof_rejects_different_bytes_and_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jars = []
            for corpus in producer.CORPORA:
                jar = root / (corpus + '.jar')
                jar.write_bytes(corpus.encode())
                jars.append({'corpus': corpus, 'path': str(jar), 'sha256': producer.common.sha(jar)})
            lines = ['header']
            for item in jars:
                for i in range(16):
                    row = [f'fixture-{item["corpus"]}-{i:02d}', item['corpus'], str(i), Path(item['path']).name,
                           item['sha256']] + ['x'] * 14
                    lines.append('\t'.join(row))
            provenance = root / 'provenance.tsv'
            provenance.write_text('\n'.join(lines) + '\n')
            plan = {'schema': 'graphite.fixture64-source-inputs.v1', 'jars': jars,
                    'referenceProvenance': {'path': str(provenance), 'sha256': producer.common.sha(provenance)}}
            path = root / 'input.json'
            path.write_text(json.dumps(plan))
            self.assertEqual(4, len(producer.verify_inputs(path)[0]['jars']))
            Path(jars[0]['path']).write_bytes(b'changed')
            with self.assertRaises(ValueError): producer.verify_inputs(path)

    def test_inventory_rejects_symlinks_and_detects_added_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'one').write_bytes(b'a')
            initial = producer.inventory(root)
            (root / 'two').write_bytes(b'b')
            self.assertNotEqual(initial, producer.inventory(root))
            (root / 'link').symlink_to(root / 'one')
            with self.assertRaises(ValueError): producer.inventory(root)

    def run_phase(self, code=0, failure=None, cleanup=None):
        class Process:
            pid = 999999
            returncode = code
            def wait(self, timeout):
                if failure: raise failure
                return code
            def poll(self): return None if failure else code
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        with patch.object(producer.subprocess, 'Popen', return_value=Process()), \
             patch.object(producer.common, 'deferred_signals', contextlib.nullcontext), \
             patch.object(producer, 'cleanup_process', return_value=cleanup or {'after': [], 'errors': []}):
            if code or failure or cleanup:
                with self.assertRaises(ValueError): producer.phase('test', ['fake'], root, {}, root, 1)
            else:
                self.assertEqual('PASS', producer.phase('test', ['fake'], root, {}, root, 1)['status'])
        return json.loads((root / 'test/record.json').read_text()), root

    def test_owned_success_journals_actual_command(self):
        record, root = self.run_phase()
        self.assertEqual('PASS', record['status'])
        self.assertEqual(['fake'], json.loads((root / 'test/owner.json').read_text())['argv'])

    def test_failed_command_remains_failed(self):
        record, _ = self.run_phase(code=17)
        self.assertEqual(17, record['exit'])
        self.assertEqual('FAIL', record['status'])

    def test_timeout_and_interrupt_are_retained(self):
        for error in [subprocess.TimeoutExpired('fake', 1), InterruptedError('SIGTERM')]:
            with self.subTest(error=repr(error)):
                record, _ = self.run_phase(failure=error)
                self.assertEqual('FAIL', record['status'])
                self.assertIn(type(error).__name__, record['errors'][0])

    def test_cleanup_failure_cannot_be_success(self):
        record, _ = self.run_phase(cleanup={'after': [1], 'errors': ['remaining']})
        self.assertEqual('FAIL', record['status'])

    def test_failed_group_probe_still_cleans_and_retains_failure(self):
        class Process:
            pid = 999999
            returncode = 0
            def poll(self): return 0
        for error in (OSError('ps failed'), subprocess.TimeoutExpired('ps', 5)):
            with self.subTest(error=repr(error)), \
                 patch.object(producer.common, 'group_members', side_effect=error), \
                 patch.object(producer.common, 'stop_owned', return_value={'after': [], 'errors': []}) as stop:
                result = producer.cleanup_process(Process())
                stop.assert_called_once()
                self.assertEqual([], result['after'])
                self.assertIn('pre-cleanup probe failed', result['errors'][0])

    def test_environment_isolates_user_configs_and_toolchain_overrides(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            toolbin = root / 'tools'; toolbin.mkdir()
            for name in ('cargo', 'rustc', 'java'): (toolbin / name).write_bytes(b'tool')
            tools = {name: str(toolbin / name) for name in ('cargo', 'rustc', 'java')}
            injected = {'HOME': '/foreign', 'CARGO_HOME': '/foreign/cargo', 'RUSTUP_TOOLCHAIN': 'nightly',
                        'RUSTUP_HOME': '/foreign/rustup', 'ORG_GRADLE_PROJECT_x': 'injected',
                        'GRADLE_USER_HOME': '/foreign/gradle', 'GRADLE_OPTS': '-Xmx99g',
                        'LD_PRELOAD': '/foreign/library', 'CARGO_BUILD_JOBS': '99', 'JAVA_TOOL_OPTIONS': '-Xmx99g'}
            out = root / 'out'; out.mkdir()
            with patch.dict(producer.os.environ, injected):
                env = producer.build_environment(root, out, tools)
            for key in ('RUSTUP_TOOLCHAIN', 'RUSTUP_HOME', 'ORG_GRADLE_PROJECT_x', 'GRADLE_OPTS', 'LD_PRELOAD', 'CARGO_BUILD_JOBS'):
                self.assertNotIn(key, env)
            self.assertEqual(str(out / 'user-home'), env['HOME'])
            self.assertEqual(str(out / 'cargo-home'), env['CARGO_HOME'])
            self.assertEqual(str(out / 'gradle-home'), env['GRADLE_USER_HOME'])
            self.assertEqual('-Xmx4g -XX:ActiveProcessorCount=4', env['JAVA_TOOL_OPTIONS'])
            proxy = toolbin / 'rustup'; proxy.write_bytes(b'proxy')
            wrong = {**tools, 'rustc': str(proxy)}
            with self.assertRaises(ValueError): producer.build_environment(root, root / 'unused', wrong)

    def test_configuration_closure_tracks_absence_and_rejects_external_injection(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            checkout = root / 'checkout'; checkout.mkdir()
            cargo = root / 'cargo'; cargo.mkdir()
            gradle = root / 'gradle'; gradle.mkdir()
            env = {'CARGO_HOME': str(cargo), 'GRADLE_USER_HOME': str(gradle), 'HOME': str(root / 'home')}
            before = producer.configuration_inventory(checkout, env)
            self.assertIsNone(before[str(root / '.cargo/config.toml')])
            (checkout / '.cargo').mkdir()
            config = checkout / '.cargo/config.toml'; config.write_text('[build]\njobs=2\n')
            self.assertNotEqual(before, producer.configuration_inventory(checkout, env))
            for external in (cargo / 'config.toml', gradle / 'gradle.properties', gradle / 'init.gradle', root / 'rust-toolchain'):
                external.write_text('injected')
                with self.subTest(path=external), self.assertRaises(ValueError):
                    producer.configuration_inventory(checkout, env)
                external.unlink()
            init = gradle / 'init.d'; init.mkdir(); (init / 'foreign.gradle').write_text('injected')
            with self.assertRaises(ValueError): producer.configuration_inventory(checkout, env)
            (init / 'foreign.gradle').unlink(); init.rmdir()
            config.unlink(); config.symlink_to(cargo / 'missing')
            with self.assertRaises(ValueError): producer.configuration_inventory(checkout, env)

    def test_source_revision_clean_tree_and_ordinary_checkout_are_required(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            (root/'.git').write_text('gitdir: /another/worktree')
            with self.assertRaisesRegex(ValueError,'ordinary Git checkout'):
                producer.source_inventory(root,producer.ACCEPTED)
            (root/'.git').unlink();(root/'.git').mkdir()
            with patch.object(producer,'git',return_value='wrong'):
                with self.assertRaisesRegex(ValueError,'revision mismatch'):
                    producer.source_inventory(root,producer.ACCEPTED)
            with patch.object(producer,'git',side_effect=[producer.ACCEPTED,' M source']):
                with self.assertRaisesRegex(ValueError,'clean'):
                    producer.source_inventory(root,producer.ACCEPTED)
            (root/'source').write_text('tracked source')
            with patch.object(producer,'git',side_effect=[producer.ACCEPTED,'','source']):
                self.assertEqual({str((root/'source').resolve()):producer.common.sha(root/'source')},
                                 producer.source_inventory(root,producer.ACCEPTED))

    def test_accepted_role_cannot_relabel_candidate_revision_or_overwrite(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);checkout=root/'source';checkout.mkdir();out=root/'out'
            args=SimpleNamespace(checkout=str(checkout),output=str(out),revision='b'*40,role='accepted-baseline')
            with self.assertRaisesRegex(ValueError,'fixed accepted baseline'):producer.produce(args)
            self.assertFalse(out.exists())
            args.role='candidate';out.mkdir()
            with self.assertRaisesRegex(ValueError,'fresh output'):producer.produce(args)
            args.output=str(checkout/'nested')
            with self.assertRaisesRegex(ValueError,'outside source'):producer.produce(args)

    def test_actual_early_failure_retains_receipt_without_acceptance(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);checkout=root/'source';checkout.mkdir();out=root/'out'
            args=SimpleNamespace(checkout=str(checkout),output=str(out),revision='b'*40,
                                 role='candidate',fixture_manifest='missing')
            with patch.object(producer,'verify_inputs',side_effect=ValueError('wrong source JAR')):
                result=producer.produce(args)
            self.assertEqual('FAIL',result['status'])
            self.assertFalse(result['acceptanceEligible']);self.assertFalse(result['performanceMeasurement'])
            self.assertIn('wrong source JAR',result['errors'][0])
            self.assertEqual(result,json.loads((out/'packet.json').read_text()))
            self.assertTrue(all(v=='NOT_REACHED' for v in result['finalIdentity'].values()))


class ArtifactCompositionTests(unittest.TestCase):
    def execute(self, root, drift=False, construction=False):
        checkout=root/'checkout';checkout.mkdir();(checkout/'.git').mkdir();(checkout/'source').write_text('source')
        out=root/'out';tools=root/'tools';tools.mkdir()
        for name in ('cargo','rustc'):(tools/name).write_text(name)
        jdk=root/'jdk';(jdk/'bin').mkdir(parents=True);(jdk/'lib').mkdir()
        for name in ('bin/java','bin/javac','release','lib/modules'):(jdk/name).write_text(name)
        jars=[]
        for corpus in producer.CORPORA:
            jar=root/(corpus+'.jar');jar.write_text(corpus)
            jars.append({'corpus':corpus,'path':str(jar),'sha256':producer.common.sha(jar)})
        inputs={'jars':jars};args=SimpleNamespace(checkout=str(checkout),output=str(out),revision='b'*40,
            role='candidate',fixture_manifest='fixture.json',java=str(jdk/'bin/java'),cargo=str(tools/'cargo'),rustc=str(tools/'rustc'),construction_metrics=construction)
        calls=[];self.capture_options={}
        def git(path,*args):
            if args[0]=='rev-parse':return 'b'*40
            if args[0]=='status':return ''
            if args[0]=='ls-files':return 'source'
            self.fail('unexpected git operation')
        def phase(name,argv,cwd,env,directory,timeout,**options):
            self.capture_options[name]=options
            calls.append((name,argv,dict(env)));phase_dir=out/name;phase_dir.mkdir()
            (phase_dir/'stdout.log').write_text('host: x86_64-unknown-linux-gnu\n' if name=='rustc-version' else name+'\n')
            if name=='build-jvm':
                for relative in ['frontend/jvm/webgraph/build/libs/writer-jmh.jar','frontend/jvm/query/build/libs/graphite.jar']:
                    file=checkout/relative;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(relative)
            if name=='build-native':
                file=out/'native-target/x86_64-unknown-linux-gnu/release/graphite';file.parent.mkdir(parents=True);file.write_text('native')
            if name=='prepare-real64':
                graphs=out/'graphs';graphs.mkdir();manifest=[];provenance=['header']
                for item in jars:
                    for i in range(16):
                        gid=f'fixture-{item["corpus"]}-{i:02d}';graph=graphs/gid;graph.mkdir();(graph/'graph.nodes').write_text(gid)
                        row=['x']*19;row[0]=gid;row[1]=item['corpus'];row[4]=item['sha256'];row[6]='1';row[7]='10';row[8]='3';row[12]=gid+'-semantic';row[15]=gid+'-workload';row[18]=str(graph)
                        provenance.append('\t'.join(row));manifest.append('\t'.join([gid,str(graph),'10','3','x',row[15]]))
                (graphs/'graphs.tsv').write_text('\n'.join(manifest)+'\n');(graphs/'fixture-provenance.tsv').write_text('\n'.join(provenance)+'\n')
            if name=='verify-real64' and drift:
                (out/'native-target/x86_64-unknown-linux-gnu/release/graphite').write_text('changed after copy')
            return {'name':name,'status':'PASS','argv':argv,'errors':[],'cleanup':{'after':[],'errors':[]}}
        with patch.object(producer,'git',side_effect=git),patch.object(producer,'verify_inputs',return_value=(inputs,{})),patch.object(producer,'phase',side_effect=phase),patch.object(producer.sys,'platform','linux'),patch.object(producer.subprocess,'check_output',return_value=b'time (GNU Time) test-only\n'),patch('subprocess.Popen',side_effect=AssertionError('no child')):
            result=producer.produce(args)
        return result,calls,out

    def test_success_preserves_own_writer_and_runtime_but_never_claims_acceptance(self):
        with tempfile.TemporaryDirectory() as d:
            result,calls,out=self.execute(Path(d).resolve())
            self.assertEqual(producer.ARTIFACTS_READY,result['status']);self.assertFalse(result['acceptanceEligible'])
            self.assertFalse(result['independentlyAudited']);self.assertTrue(result['unavailable'])
            self.assertEqual(64,len(result['graphs']));self.assertTrue(all(v=='PASS' for v in result['finalIdentity'].values()))
            names=[name for name,_,_ in calls]
            self.assertEqual(['java-version','rustc-version','cargo-version','build-jvm','build-native','prepare-real64','verify-real64'],names)
            commands={name:argv for name,argv,_ in calls}
            self.assertIn('-Dorg.gradle.jvmargs=-Xmx4g -XX:ActiveProcessorCount=4',commands['build-jvm'])
            self.assertIn('-Xmx4g',commands['prepare-real64']);self.assertIn('-Xmx4g',commands['verify-real64'])
            self.assertIn(str(out/'runtime/writer.jar'),commands['prepare-real64'])
            self.assertIn('--verify',commands['verify-real64'])
            self.assertTrue(all(env['JAVA_TOOL_OPTIONS']=='-Xmx4g -XX:ActiveProcessorCount=4' for _,_,env in calls))
            manifest=json.loads((out/'fixture-manifest.json').read_text())
            self.assertEqual('b'*40,manifest['writerRevision']);self.assertEqual(4,len(manifest['inputJars']))
            self.assertEqual(result,json.loads((out/'packet.json').read_text()))

    def test_optional_construction_capture_only_targets_own_64_writer(self):
        with tempfile.TemporaryDirectory() as d:
            result,calls,out=self.execute(Path(d).resolve(),construction=True)
            self.assertEqual(producer.ARTIFACTS_READY,result['status']);self.assertTrue(result['performanceMeasurement'])
            self.assertFalse(result['acceptanceEligible'])
            self.assertEqual({'prepare-real64':{'construction_time':'/usr/bin/time'}}, {k:v for k,v in self.capture_options.items() if v})
            pins=json.loads((out/'inputs-before.json').read_text())
            self.assertEqual(producer.common.sha('/usr/bin/time'),pins['/usr/bin/time'])
            self.assertEqual(producer.common.sha(out/'construction-time-version.txt'),pins[str(out/'construction-time-version.txt')])
            self.assertEqual(producer.CONSTRUCTION_SCOPE,result['constructionMeasurement']['scope'])

    def test_changed_original_binary_prevents_artifact_success(self):
        with tempfile.TemporaryDirectory() as d:
            result,_,_=self.execute(Path(d).resolve(),drift=True)
            self.assertEqual('FAIL',result['status']);self.assertEqual('FAIL',result['finalIdentity']['originalArtifacts'])
            self.assertFalse(result['acceptanceEligible'])


class ConstructionResourceTests(unittest.TestCase):
    RAW = ('User time (seconds): 61.20\nSystem time (seconds): 2.30\n'
           'Elapsed (wall clock) time (h:mm:ss or m:ss): 1:03.75\n'
           'Maximum resident set size (kbytes): 2048\nExit status: 0\n')

    def test_resource_units_total_and_boundary(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'time-v.log';path.write_text(self.RAW)
            value=producer.construction_resources(path,0)
            self.assertEqual((63.75,61.2,2.3,63.5,2097152),(value['realSeconds'],value['userSeconds'],value['systemSeconds'],value['totalCpuSeconds'],value['peakRssBytes']))
            self.assertEqual(64,value['graphCount']);self.assertEqual(4*1024**3,value['heapMaxBytes'])
            self.assertIn('embedded readback validation',value['scope']);self.assertFalse(value['performanceAcceptance'])

    def test_missing_duplicate_malformed_exit_and_rss_rejected(self):
        cases=[self.RAW.replace('Exit status: 0','Exit status: 1'),self.RAW.replace('User time (seconds): 61.20\n',''),
               self.RAW+'System time (seconds): 0.1\n',self.RAW.replace('2048','0'),self.RAW.replace('2048','1.5'),
               self.RAW.replace('61.20','NaN'),self.RAW.replace('1:03.75','garbage')]
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'time-v.log'
            for raw in cases:
                with self.subTest(raw=raw):
                    path.write_text(raw)
                    with self.assertRaises(ValueError):producer.construction_resources(path,0)

    def resource_phase(self,raw=None,exit_code=0,timeout=False):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);root=Path(temp.name)
        argv=['java','-Xmx4g','-XX:ActiveProcessorCount=4','Writer',str(root/'graphs')]
        calls=[]
        class Process:
            pid=987654
            returncode=exit_code
            def wait(self,timeout):
                if raw is not None:(root/'prepare-real64/time-v.log').write_text(raw)
                if should_timeout:raise subprocess.TimeoutExpired('writer',1)
                return exit_code
            def poll(self):return exit_code
        should_timeout=timeout
        def launch(actual,**kwargs):calls.append((actual,kwargs));return Process()
        cleanup={'group':987654,'after':[],'errors':[],'exit':exit_code}
        with patch.object(producer.subprocess,'Popen',side_effect=launch),patch.object(producer,'cleanup_process',return_value=cleanup):
            try:producer.phase('prepare-real64',argv,root,{'LANG':'foreign'},root,1,construction_time='/usr/bin/time')
            except ValueError:pass
        return producer.common.read(root/'prepare-real64/record.json'),root,argv,calls

    def test_only_actual_writer_is_wrapped_and_receipts_bind_raw_time(self):
        record,root,argv,calls=self.resource_phase(self.RAW)
        expected=['/usr/bin/time','-v','-o',str(root/'prepare-real64/time-v.log'),'--',*argv]
        self.assertEqual('PASS',record['status']);self.assertEqual(expected,calls[0][0])
        self.assertEqual('C',calls[0][1]['env']['LC_ALL']);self.assertTrue(calls[0][1]['start_new_session'])
        self.assertEqual(argv,record['argv']);self.assertEqual(expected,record['launchArgv'])
        self.assertEqual(expected,producer.common.read(root/'prepare-real64/owner.json')['argv'])
        self.assertEqual(record['constructionResources']['rawSha256'],record['logs'][str(root/'prepare-real64/time-v.log')])

    def test_failed_missing_or_timed_out_measurement_never_passes(self):
        for raw,code,timeout in [(None,0,False),(self.RAW.replace('status: 0','status: 7'),7,False),(self.RAW,0,True)]:
            with self.subTest(raw=raw,code=code,timeout=timeout):
                record,_,_,_=self.resource_phase(raw,code,timeout)
                self.assertEqual('FAIL',record['status']);self.assertTrue(record['errors']);self.assertEqual([],record['cleanup']['after'])

    def test_capture_cannot_wrap_build_or_verification(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            for name in ('build-jvm','verify-real64'):
                with self.assertRaisesRegex(ValueError,'capture only'):
                    producer.phase(name,['java','-Xmx4g','-XX:ActiveProcessorCount=4'],root,{},root,1,construction_time='/usr/bin/time')


if __name__ == '__main__':
    with patch('subprocess.Popen', side_effect=AssertionError('no child processes')):
        unittest.main()
