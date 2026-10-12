"""Small applicability metadata/phase tests; not upstream execution authority."""
import copy
import json
import subprocess
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import jvm_pressure_source_rules as p


class SourceRuleTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve(); self.checkout = self.root/'checkout'; self.checkout.mkdir()
        self.producer = self.root/'producer'; self.producer.mkdir()
        blocked = patch('subprocess.Popen', side_effect=AssertionError('no process or core replay in applicability'))
        blocked.start(); self.addCleanup(blocked.stop)

    def write(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, indent=2)+'\n'); return p.artifacts.ref(path)

    def fixture(self, profile='declared5a', role='candidate', revision=None):
        revision = revision or p.REVIEWED_AT[profile]
        pins = {str(self.checkout/path): digest for path, digest in p.profiles()[profile].items()}
        # The source file pins stand for previously replayed upstream evidence.
        # No source bytes or owned build execution are invented by this test.
        source = {'revision': revision, 'root': str(self.checkout), 'files': dict(pins)}
        source_ref = self.write(self.producer/'source.json', source)
        home = self.root/'jdk'; jdk = {'home': str(home), 'files': {}}
        for name in ('bin/java', 'release', 'lib/modules'):
            path = home/name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(('tiny '+name).encode())
            ref = p.artifacts.ref(path); jdk['files'][name] = ref; pins[ref['path']] = ref['sha256']
        files = {}; originals = {}
        for name, original in (('graphite.jar', self.checkout/'frontend/jvm/query/build/libs/graphite.jar'),
                               ('writer.jar', self.checkout/'frontend/jvm/webgraph/build/libs/webgraph-tiny-jmh.jar')):
            path = self.producer/'runtime'/name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(('tiny '+name).encode())
            ref = p.artifacts.ref(path); files[ref['path']] = ref['sha256']; originals[str(original)] = ref['sha256']
        pins.update(files); pins.update(originals)
        runtime = {'revision': revision, 'sourceManifestSha256': source_ref['sha256'], 'sourceFiles': source['files'],
                   'files': files, 'originalArtifacts': originals, 'toolchainIdentity': {'java': str(home/'bin/java')}}
        runtime_ref = self.write(self.producer/'runtime.json', runtime)
        artifact_ref = self.write(self.producer/'artifact-audit.json', {'revision': revision, 'role': role,
            'sourceManifest': source_ref, 'runtimeManifest': runtime_ref})
        phase_dir = self.producer/'build-jvm'; phase_dir.mkdir(exist_ok=True)
        argv = [str(self.checkout/'gradlew'), '--no-daemon', '--max-workers=2', '-Dorg.gradle.jvmargs=-Xmx4g -XX:ActiveProcessorCount=4',
                '-Pkotlin.compiler.execution.strategy=in-process', ':webgraph:jmhJar', ':query:shadowJar']
        self.write(phase_dir/'owner.json', {'runnerPid': 123, 'group': 456, 'argv': argv})
        for leaf in ('stdout.log', 'stderr.log'): (phase_dir/leaf).write_bytes(b'')
        phase = {'name': 'build-jvm', 'argv': argv, 'cwd': str(self.checkout), 'timeoutSeconds': 7200,
                 'status': 'PASS', 'errors': [], 'exit': 0,
                 'cleanup': {'group': 456, 'after': [], 'errors': [], 'exit': 0},
                 'logs': {str(phase_dir/n): p.common.sha(phase_dir/n) for n in ('stdout.log', 'stderr.log')}}
        phase_ref = self.write(phase_dir/'record.json', phase)
        for path in phase_dir.iterdir(): pins[str(path)] = p.common.sha(path)
        for ref in (source_ref, runtime_ref, artifact_ref): pins[ref['path']] = ref['sha256']
        controls = Path(p.__file__).parent
        pins.update({str(controls/name): digest for name, digest in p.REVIEWED_HELPERS.items()})
        return {'schema': 'graphite.jvm-raw-oracle-plan.v1', 'revision': revision, 'role': role,
                'sourceManifest': source_ref, 'runtimeManifest': runtime_ref, 'producerRoot': str(self.producer),
                'java': str(home/'bin/java'), 'jdkImage': jdk, 'writerJar': str(self.producer/'runtime/writer.jar'), 'pins': pins}

    def test_candidate_all_rules_and_exact35_scope_keep_execution_and_http_claims_false(self):
        plan = self.fixture(); result = p.audit(plan)
        self.assertEqual('declared5a', result['profile']); self.assertEqual(plan['revision'], result['revision'])
        self.assertEqual(set(p.RULES)|{'closed-runtime-source-and-build-dependencies'}, {r['id'] for r in result['rules']})
        self.assertEqual(35, len(result['requestContracts'])); self.assertTrue(result['sourceRuleApplicabilityVerified'])
        self.assertTrue(result['upstreamExecutionReplayRequired'])
        for key in ('oracleAuthorityVerified', 'fresh64Acceptance', 'performanceAcceptance'): self.assertIs(False, result[key])
        self.assertEqual(str(self.producer/'runtime/graphite.jar'), result['packagedRuntime']['graphiteJar']['path'])
        for case in result['requestContracts']:
            self.assertEqual(64, len(case['requestedGraphIds']))
            self.assertEqual(2 if case['id'].startswith('routing-') or case['id'].endswith(('-full', '-properties')) else 64, len(case['targetGraphIds']))

    def test_optimized_profile_keeps_review_identity_and_all35_rules(self):
        plan = self.fixture('optimized-declared24af', revision='b'*40)
        result = p.audit(plan)
        self.assertEqual('optimized-declared24af', result['profile'])
        self.assertEqual('24af8a59c27ddadc76c4759bfd0d5a1688b7e988', result['reviewedAt'])
        self.assertEqual('b'*40, result['revision'])
        self.assertEqual(35, len(result['requestContracts']))
        self.assertEqual(set(p.RULES)|{'closed-runtime-source-and-build-dependencies'},
                         {rule['id'] for rule in result['rules']})
        self.assertFalse(result['oracleAuthorityVerified'])
        self.assertFalse(result['fresh64Acceptance'])
        self.assertFalse(result['performanceAcceptance'])
        old = p.profiles()['declared5a']; optimized = p.profiles()['optimized-declared24af']
        self.assertEqual(set(old), set(optimized))
        self.assertEqual(set(p.OPTIMIZED_DECLARED_OVERRIDES),
                         {name for name in old if old[name] != optimized[name]})
        optimized.clear()
        self.assertEqual(old, p.profiles()['declared5a'])
        self.assertTrue(p.profiles()['optimized-declared24af'])

    def test_accepted4f_requires_explicit_absent_declared_files(self):
        plan = self.fixture('accepted4f', 'accepted-baseline'); result = p.audit(plan)
        self.assertEqual('accepted4f', result['profile'])
        rule = next(r for r in result['rules'] if r['id'] == 'declared-property-presence-and-rendering')
        self.assertEqual(8, len(rule['absentSources'])); self.assertEqual([], rule['sources'])
        self.assertEqual(p.REVIEWED_AT['accepted4f'], result['reviewedAt'])

    def test_control_only_revision_is_distinguished_from_reviewed_product_revision(self):
        plan = self.fixture(revision='b'*40); result = p.audit(plan)
        self.assertEqual('b'*40, result['revision']); self.assertEqual(p.REVIEWED_AT['declared5a'], result['reviewedAt'])

    def test_unknown_changed_removed_and_added_runtime_source_fail_even_if_repinned(self):
        original = p.profiles()['declared5a']; important = p.kt('cypher', 'cypher', 'QueryPipeline')
        for change in ('changed', 'removed', 'added', 'new-build-plugin'):
            sources = dict(original)
            if change == 'changed': sources[important] = 'f'*64
            elif change == 'removed': sources.pop(important)
            elif change == 'added': sources['frontend/jvm/cypher/src/main/kotlin/UnknownOverride.kt'] = 'f'*64
            else: sources['buildSrc/src/main/kotlin/UnknownPlugin.kt'] = 'f'*64
            pins = {str(self.checkout/n): digest for n, digest in sources.items()}
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, 'unrecognized closed'):
                p.recognized({'root': str(self.checkout), 'files': pins}, pins)

    def test_arbitrary_valid_hashes_and_missing_input_pin_are_not_authority(self):
        plan = self.fixture(); source = p.common.read(plan['sourceManifest']['path'])
        pins = dict(plan['pins']); pins.pop(next(iter(source['files'])))
        with self.assertRaisesRegex(ValueError, 'source file binding'): p.recognized(source, pins)
        with self.assertRaisesRegex(ValueError, 'unrecognized closed'):
            p.recognized({'root': str(self.checkout), 'files': {}}, {})

    def test_source_and_runtime_metadata_byte_change_rejected(self):
        plan = self.fixture(); Path(plan['runtimeManifest']['path']).write_text('{}')
        with self.assertRaisesRegex(ValueError, 'metadata changed'): p.audit(plan)

    def test_cross_revision_source_runtime_or_other_writer_rejected(self):
        plan = self.fixture()
        for mutation in ('revision', 'writerJar', 'java', 'role'):
            bad = copy.deepcopy(plan)
            bad[mutation] = 'c'*40 if mutation == 'revision' else str(self.root/'foreign'/mutation)
            with self.subTest(mutation=mutation), self.assertRaises(ValueError): p.audit(bad)

    def test_changed_gson_or_heap_build_command_and_unclosed_group_rejected(self):
        for key in ('argv', 'cleanup'):
            plan = self.fixture(); path = self.producer/'build-jvm/record.json'; phase = p.common.read(path)
            if key == 'argv': phase['argv'][3] = '-Dorg.gradle.jvmargs=-Xmx16g'
            else: phase['cleanup']['after'] = [456]
            ref = self.write(path, phase); plan['pins'][ref['path']] = ref['sha256']
            with self.subTest(key=key), self.assertRaises(ValueError): p.audit(plan)
        source = p.profiles()['declared5a'].copy(); source['gradle/libs.versions.toml'] = 'c'*64
        pins = {str(self.checkout/n): digest for n, digest in source.items()}
        with self.assertRaisesRegex(ValueError, 'unrecognized closed'): p.recognized({'root': str(self.checkout), 'files': pins}, pins)

    def test_missing_packaged_jar_pin_or_changed_rule_implementation_rejected(self):
        plan = self.fixture(); plan['pins'].pop(str(self.producer/'runtime/graphite.jar'))
        with self.assertRaisesRegex(ValueError, 'required actual evidence pin'): p.audit(plan)
        plan = self.fixture(); plan['pins'][str(Path(p.__file__).parent/'jvm_pressure_distinct.py')] = 'e'*64
        with self.assertRaisesRegex(ValueError, 'oracle rule implementation'): p.audit(plan)

    def test_baseline_role_cannot_claim_new_revision_or_candidate_source(self):
        for profile, revision in (('accepted4f', 'b'*40), ('declared5a', p.REVIEWED_AT['accepted4f']),
                                  ('optimized-declared24af', p.REVIEWED_AT['accepted4f']),
                                  ('lazy-declared-a9d4', p.REVIEWED_AT['accepted4f']),
                                  ('streamed-digest-16c1', p.REVIEWED_AT['accepted4f'])):
            plan = self.fixture(profile, 'accepted-baseline', revision)
            with self.subTest(profile=profile), self.assertRaisesRegex(ValueError, 'accepted baseline'): p.audit(plan)


class CurrentTrackedSourceTests(unittest.TestCase):
    """Fail early on actual checkout/profile drift, without building or reading graphs."""

    @classmethod
    def setUpClass(cls):
        cls.checkout = Path(__file__).resolve().parents[2]
        tracked = subprocess.check_output(['git', '-C', str(cls.checkout), 'ls-files', '-z']).decode().split('\0')
        cls.sources = {str(cls.checkout/name): p.common.sha(cls.checkout/name)
                       for name in tracked if name and p.relevant_source(name)}
        if not cls.sources:
            raise AssertionError('actual tracked JVM source inventory is empty')

    def test_current_tracked_source_matches_reviewed_complete_profile(self):
        profile, sources = p.recognized({'root': str(self.checkout), 'files': self.sources}, self.sources)
        self.assertEqual('streamed-digest-16c1', profile)
        self.assertEqual(166, len(sources))

    def test_actual_inventory_changes_removal_addition_and_dependency_change_fail(self):
        for change in ('changed', 'removed', 'added', 'dependency'):
            pins = dict(self.sources)
            existing = str(self.checkout/p.kt('webgraph', 'webgraph', 'DeclaredTypeStore'))
            if change == 'removed': pins.pop(existing)
            elif change == 'changed': pins[existing] = 'f'*64
            elif change == 'added':
                pins[str(self.checkout/'frontend/jvm/query/src/main/kotlin/UnreviewedRuntime.kt')] = 'f'*64
            else: pins[str(self.checkout/'gradle/libs.versions.toml')] = 'f'*64
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, 'unrecognized closed'):
                p.recognized({'root': str(self.checkout), 'files': pins}, pins)


if __name__ == '__main__': unittest.main()
