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
    def execute(self, root, drift=False):
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
            role='candidate',fixture_manifest='fixture.json',java=str(jdk/'bin/java'),cargo=str(tools/'cargo'),rustc=str(tools/'rustc'))
        calls=[]
        def git(path,*args):
            if args[0]=='rev-parse':return 'b'*40
            if args[0]=='status':return ''
            if args[0]=='ls-files':return 'source'
            self.fail('unexpected git operation')
        def phase(name,argv,cwd,env,directory,timeout):
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
        with patch.object(producer,'git',side_effect=git),patch.object(producer,'verify_inputs',return_value=(inputs,{})),patch.object(producer,'phase',side_effect=phase),patch('subprocess.Popen',side_effect=AssertionError('no child')):
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

    def test_changed_original_binary_prevents_artifact_success(self):
        with tempfile.TemporaryDirectory() as d:
            result,_,_=self.execute(Path(d).resolve(),drift=True)
            self.assertEqual('FAIL',result['status']);self.assertEqual('FAIL',result['finalIdentity']['originalArtifacts'])
            self.assertFalse(result['acceptanceEligible'])


if __name__ == '__main__':
    with patch('subprocess.Popen', side_effect=AssertionError('no child processes')):
        unittest.main()
