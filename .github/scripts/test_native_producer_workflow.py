"""Tiny workflow dispatch contracts; no runtime build or performance measurement."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import shutil
import zipfile

import native_pressure_checkpoint as checkpoint
import test_native_producer_merge as merge_fixtures
import unittest

ACCEPTED = '4f2ccf33b969e684972e56b5e810034e6e67c1b3'
SUFFIX = '${{ github.run_id }}-${{ github.run_attempt }}'


class ProducerWorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = (Path(__file__).parents[1] / 'workflows/benchmark.yml').read_text()

    def job(self, name):
        return self.workflow.split('\n  '+name+':\n', 1)[1].split('\n  rust-', 1)[0]

    def test_common_inputs_arm_matrix_and_legacy_output_dependencies(self):
        common = self.job('rust-producer-common')
        arm = self.job('rust-producer-arm')
        merge = self.job('rust-producer')
        self.assertIn('needs: [candidate-gate-tests, prepare-fixture64]', common)
        self.assertIn('needs: [rust-producer-common]', arm)
        self.assertIn('needs: [rust-producer-common, rust-producer-arm]', merge)
        self.assertIn("&& '[\"C\",\"B\"]' || '[\"C\",\"A\",\"B\"]'", arm)
        self.assertIn('fail-fast: false', arm)
        self.assertEqual(1, common.count('native_portable_oracles.py'))
        self.assertNotIn('native_portable_oracles.py', arm)
        self.assertNotIn('produce_native_pressure_artifacts.py', common)
        self.assertNotIn('produce_native_pressure_artifacts.py', merge)
        self.assertIn('merge-producer', merge)
        self.assertIn('name: native-checkpoint-producer-producer-'+SUFFIX, merge)
        self.assertIn('path: pressure-checkpoints/producer-producer/', merge)
        self.assertIn('--upstream pressure-checkpoints/producer-common-common/manifest.json', arm)
        for consumer in ('rust-core-prepare', 'rust-query-native-measurement', 'rust-loading-measurement',
                         'rust-construction-measurement'):
            needs = re.search(r'^    needs: \[(.+)\]$', self.job(consumer), re.M).group(1)
            self.assertIn('rust-producer', [item.strip() for item in needs.split(',')])

    def test_named_transport_paths_and_optional_parent_match_merge_protocol(self):
        arm = self.job('rust-producer-arm')
        merge = self.job('rust-producer')
        for body in (arm, merge):
            self.assertIn('name: native-checkpoint-producer-common-common-'+SUFFIX, body)
            self.assertIn('path: pressure-checkpoints/producer-common-common', body)
        self.assertIn('name: native-checkpoint-producer-arm-${{ matrix.arm }}-'+SUFFIX, arm)
        for label in ('C', 'A', 'B'):
            step = merge.split('    - name: Download producer arm '+label+'\n')[1].split('\n    - name:')[0]
            self.assertIn('name: native-checkpoint-producer-arm-'+label+'-'+SUFFIX, step)
            self.assertIn('path: pressure-checkpoints/producer-arm-'+label, step)
            if label == 'A':
                self.assertIn("if: github.event.pull_request.base.sha != '"+ACCEPTED+"'", step)
            else:
                self.assertNotIn('      if:', step)
        for body in (self.job('rust-producer-common'), arm, merge):
            self.assertIn('sha256sum -c .github/scripts/multigraph-pressure-preparation-controls.sha256', body)
            for flag in ('--head-sha', '--base-sha', '--run-id', '--run-attempt', '--workspace'):
                self.assertIn(flag, body)

    def test_actual_workflow_download_layout_roundtrips_real_checkpoint_bytes(self):
        for separate in (False, True):
            with self.subTest(separate=separate):
                case = merge_fixtures.ProducerMergeSmokeTests()
                case.setUp()
                try:
                    def transfer(args):
                        stage, arm = args.stage, args.arm
                        if stage == 'producer-common':
                            job, label = 'rust-producer', 'common producer inputs'
                        elif stage == 'producer-arm':
                            job, label = 'rust-producer', 'producer arm '+arm
                        else:
                            job, label = 'rust-core-prepare', 'producer checkpoint'
                        step = self.job(job).split('    - name: Download '+label+'\n')[1].split('\n    - name:')[0]
                        name = re.search(r'^        name: (.+)$', step, re.M).group(1)
                        relative = re.search(r'^        path: (.+)$', step, re.M).group(1)
                        self.assertEqual('native-checkpoint-'+stage+'-'+arm+'-'+SUFFIX, name)
                        destination = case.f.workspace/relative
                        directory = Path(args.directory)
                        manifest = checkpoint.read(directory/'manifest.json')
                        archive = case.f.workspace.parent/(directory.name+'.zip')
                        with zipfile.ZipFile(archive, 'w') as outgoing:
                            for leaf in ('manifest.json', 'payload.tar.gz'):
                                outgoing.write(directory/leaf, leaf)
                        shutil.rmtree(directory)
                        with zipfile.ZipFile(archive) as incoming:
                            incoming.extractall(destination)
                        self.assertEqual(manifest, checkpoint.read(destination/'manifest.json'))
                        return manifest
                    case.transfer = transfer
                    case.roundtrip(separate)
                finally:
                    case.doCleanups()

    def test_actual_shell_dispatch_keeps_exact_writer_roles_and_all_audits(self):
        body = self.job('rust-producer-arm')
        run = body.split('    - name: Build one exact-revision runtime and its own real64 graphs\n')[1]
        run = run.split('    - name: Seal exact producer arm checkpoint')[0]
        script = run[run.index('        ACCEPTED_SHA='):]
        script = '\n'.join(line[8:] if line.startswith('        ') else line for line in script.splitlines())
        for base in (ACCEPTED, 'a'*40):
            for arm in ('C', 'B', *(() if base == ACCEPTED else ('A',))):
                with self.subTest(base=base, arm=arm), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory); commands = root/'commands.jsonl'
                    stub = root/'python3'
                    stub.write_text('#!'+sys.executable+'\nimport json,os,sys\nwith open(os.environ["COMMANDS"],"a") as f: f.write(json.dumps(sys.argv[1:])+"\\n")\n')
                    stub.chmod(0o755)
                    env = {**os.environ, 'PATH': str(root)+os.pathsep+os.environ['PATH'], 'COMMANDS': str(commands),
                           'BASE_SHA': base, 'CANDIDATE_SHA': 'b'*40, 'CHECKPOINT_ARM': arm,
                           'JAVA_EXE': '/exact/java', 'CARGO_EXE': '/exact/cargo', 'RUSTC_EXE': '/exact/rustc'}
                    subprocess.run(['bash', '-euo', 'pipefail', '-c', script], env=env, check=True, timeout=10)
                    calls = [json.loads(line) for line in commands.read_text().splitlines()]
                    expected = ['produce_native_pressure_artifacts.py', 'audit_native_pressure_artifacts.py',
                                'run_native_query_correctness.py', 'audit_native_query_correctness.py']
                    if arm == 'C': expected.append('export_native_core_strings.py')
                    self.assertEqual(expected, [Path(call[0]).name for call in calls])
                    revision = ACCEPTED if arm == 'C' else base if arm == 'A' else 'b'*40
                    role = {'C': 'accepted-baseline', 'A': 'parent', 'B': 'candidate'}[arm]
                    checkout = ('base' if base == ACCEPTED else 'accepted') if arm == 'C' else 'base' if arm == 'A' else 'candidate'
                    for call in calls[:2]:
                        for flag, value in (('--checkout', checkout), ('--revision', revision), ('--role', role)):
                            self.assertEqual(value, call[call.index(flag)+1])
                    self.assertIn('--construction-metrics', calls[0])
                    self.assertIn('--require-construction-metrics', calls[1])
                    self.assertEqual(role, calls[2][calls[2].index('--role')+1])
                    self.assertEqual(revision, calls[2][calls[2].index('--revision')+1])
                    self.assertTrue(all('native-pressure-artifacts/'+arm+'/' in ' '.join(call) or
                                        'native-pressure-artifacts/'+arm in call for call in calls))


    def test_baseline_and_parent_products_are_cached_by_name_version_and_ref_never_the_candidate(self):
        arm = self.job('rust-producer-arm')
        self.assertIn("      actions: write\n", arm)
        self.assertIn("FIXTURE64_ARM_CACHE_VERSION: '2'", arm)
        self.assertIn('echo "key=fixture64-producer-arm-${CHECKPOINT_ARM}-v${FIXTURE64_ARM_CACHE_VERSION}-${REF}"', arm)
        self.assertIn('C) REF="${ACCEPTED_SHA:0:7}"', arm)
        self.assertIn('A) REF="${BASE_REF}"', arm)
        self.assertIn('BASE_REF: ${{ github.event.pull_request.base.ref }}', arm)
        for step in ('Resolve the cached products key', 'Restore the cached products', 'Validate the restored products',
                     'Record the identity of the products', 'Save the baseline or parent arm products'):
            self.assertIn(step, arm)
        restore = arm.split('    - name: Restore the cached products of the baseline or parent arm\n')[1].split('\n    - name:')[0]
        save = arm.split('    - name: Save the baseline or parent arm products for later runs\n')[1].split('\n    - name:')[0]
        self.assertIn("if: matrix.arm != 'B'\n", restore)
        self.assertIn("if: matrix.arm != 'B' && steps.arm-cache-check.outputs.valid != 'true'\n", save)
        self.assertIn('continue-on-error: true', save)
        paths = lambda body: [line.strip() for line in body.split('path: |\n')[1].splitlines() if line.startswith('          ')]
        self.assertEqual(paths(restore), paths(save))
        self.assertIn('native-pressure-artifacts/${{ matrix.arm }}/graphs', paths(restore))
        self.assertIn('${{ steps.arm-cache-key.outputs.checkout }}/frontend/jvm/query/build/libs/graphite.jar', paths(restore))
        build = arm.split('    - name: Build one exact-revision runtime and its own real64 graphs\n')[1].split('\n      run:')[0]
        self.assertIn("if: matrix.arm == 'B' || steps.arm-cache-check.outputs.valid != 'true'", build)
        self.assertIn('gh cache delete "${CACHE_KEY}"', arm)
        self.assertIn('fixture64_arm_cache.py check', arm)
        self.assertIn('fixture64_arm_cache.py identity', arm)


if __name__ == '__main__':
    unittest.main()
