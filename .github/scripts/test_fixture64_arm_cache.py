import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import fixture64_arm_cache as cache

SCRIPTS = Path(__file__).resolve().parent
ACCEPTED = '4f2ccf33b969e684972e56b5e810034e6e67c1b3'


class ArmCacheTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.workspace = Path(self.tmp.name).resolve()
        self.tools = self.workspace.parent/'tools'; self.tools.mkdir(exist_ok=True)
        for name, text in (('java', 'openjdk version "17.0.12"'), ('cargo', 'cargo 1.90.0'), ('rustc', 'rustc 1.90.0\nhost: x86_64')):
            self.tool(name, text, stream=2 if name == 'java' else 1)
        artifacts = self.workspace/'native-pressure-artifacts'; artifacts.mkdir()
        self.write(artifacts/'source-inputs.json', {'jars': [{'corpus': 'android', 'sha256': 'a'*64}]})
        self.write(artifacts/'portable-expectations.json', {'cases': 39})
        self.write(artifacts/'producer-common.json', {'toolchainIdentity': {name: str(self.tools/name) for name in ('java', 'cargo', 'rustc')}})
        self.candidate_code()
        self.arm(self.workspace, 'C', ACCEPTED, 'accepted-baseline', 'accepted')

    def candidate_code(self, helper='def sha(path): return path\n', job='      - run: python3 candidate/.github/scripts/produce.py\n'):
        """A candidate checkout: the producer job names one script, which imports a helper and a package."""
        workflow = ('name: bench\njobs:\n  earlier:\n    steps: []\n  ' + cache.JOB + ':\n    steps:\n' + job +
                    '      - run: python3 candidate/.github/scripts/audit.py\n  later:\n    steps:\n      - run: python3 candidate/.github/scripts/gate.py\n')
        self.write(self.workspace/'candidate'/cache.WORKFLOW, workflow)
        scripts = self.workspace/'candidate'/cache.SCRIPTS
        self.write(scripts/'produce.py', 'import helper\nfrom proof import rows\nimport json, os\n')
        self.write(scripts/'audit.py', 'import helper as h\n')
        self.write(scripts/'helper.py', helper)
        self.write(scripts/'proof/__init__.py', '')
        self.write(scripts/'proof/rows.py', 'import helper\n')
        self.write(scripts/'proof/rows_test.py', 'import rows\n')
        self.write(scripts/'gate.py', 'import helper\n')
        self.write(scripts/'test_produce.py', 'import produce\n')

    def tool(self, name, text, stream=1):
        tool = self.tools/name
        tool.write_text("#!/bin/sh\nprintf '" + text.replace('\n', '\\n') + "\\n' >&" + str(stream) + '\n')
        tool.chmod(0o755)

    def write(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value if isinstance(value, str) else json.dumps(value))

    def arm(self, workspace, arm, revision, role, checkout):
        root = workspace/'native-pressure-artifacts'/arm
        self.write(root/'packet.json', {'revision': revision, 'role': role})
        self.write(root/'runtime-manifest.json', {'revision': revision})
        for name in ('artifact-audit.json', 'query-correctness-audit.json', 'fixture-manifest.json'):
            self.write(root/name, {})
        for tree in ('runtime', 'graphs', 'query-correctness'):
            (root/tree).mkdir(parents=True, exist_ok=True)
        self.write(workspace/checkout/'frontend/jvm/webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar', 'jar')
        self.write(workspace/checkout/'frontend/jvm/query/build/libs/graphite.jar', 'jar')

    def test_identity_names_every_input_and_the_toolchain_versions(self):
        value = cache.identity(self.workspace, 'C', ACCEPTED, 'accepted-baseline', 'accepted')
        self.assertEqual(value['schema'], cache.SCHEMA)
        self.assertEqual(set(value['commonInputs']), set(cache.COMMON_INPUTS))
        self.assertEqual(sorted(value['producerCode']['scripts']), ['audit.py', 'helper.py', 'produce.py', 'proof/__init__.py', 'proof/rows.py'],
                         'the job\'s scripts and what they import, never the gate or the tests')
        job = cache.job_definition((self.workspace/'candidate'/cache.WORKFLOW).read_text())
        self.assertTrue(job.startswith('  ' + cache.JOB + ':\n') and 'audit.py' in job and 'gate.py' not in job)
        self.assertEqual(value['toolchainVersions'], {'java': 'openjdk version "17.0.12"', 'cargo': 'cargo 1.90.0', 'rustc': 'rustc 1.90.0\nhost: x86_64'})
        self.assertEqual(value['workspace'], str(self.workspace))
        with self.assertRaisesRegex(ValueError, 'only the accepted baseline and parent'):
            cache.identity(self.workspace, 'B', 'b'*40, 'candidate', 'candidate')
        with self.assertRaisesRegex(ValueError, 'only the accepted baseline and parent'):
            cache.identity(self.workspace, 'C', ACCEPTED, 'parent', 'accepted')

    def test_check_accepts_unchanged_inputs_and_names_each_change(self):
        identity = self.workspace/'identity.json'
        identity.write_text(json.dumps(cache.identity(self.workspace, 'C', ACCEPTED, 'accepted-baseline', 'accepted')))
        args = (identity, self.workspace, 'C', ACCEPTED, 'accepted-baseline', 'accepted')
        self.assertEqual([], cache.check(*args))
        self.candidate_code(helper='def sha(path): return None\n')
        self.assertEqual(['identity.producerCode.scripts.helper.py'], cache.check(*args))
        self.candidate_code(job='      - run: python3 candidate/.github/scripts/produce.py --jobs 2\n')
        self.assertEqual(['identity.producerCode.jobDefinitionSha256'], cache.check(*args))
        self.candidate_code()
        self.write(self.workspace/'candidate'/cache.SCRIPTS/'gate.py', 'import helper\nRULE = 5\n')
        self.write(self.workspace/'candidate'/cache.SCRIPTS/'test_produce.py', 'import produce\nX = 1\n')
        self.assertEqual([], cache.check(*args), 'a gate or a test cannot change the products')
        self.write(self.workspace/'native-pressure-artifacts/portable-expectations.json', {'cases': 40})
        self.assertEqual(['identity.commonInputs.portable-expectations.json'], cache.check(*args))
        self.write(self.workspace/'native-pressure-artifacts/portable-expectations.json', {'cases': 39})
        self.tool('rustc', 'rustc 1.91.0')
        self.assertEqual(['identity.toolchainVersions.rustc'], cache.check(*args))
        self.tool('rustc', 'rustc 1.90.0\nhost: x86_64')
        self.assertEqual([], cache.check(*args))
        # A parent that moved: the packet still names the old revision.
        self.assertEqual(['identity.revision', 'packet names revision ' + ACCEPTED + ' role accepted-baseline', 'runtime manifest names another revision'],
                         cache.check(identity, self.workspace, 'C', 'c'*40, 'accepted-baseline', 'accepted'))
        self.assertEqual(['no recorded identity'], cache.check(self.workspace/'absent.json', *args[1:]))

    def test_check_requires_the_products_and_the_checkout_jars(self):
        identity = self.workspace/'identity.json'
        identity.write_text(json.dumps(cache.identity(self.workspace, 'C', ACCEPTED, 'accepted-baseline', 'accepted')))
        args = (identity, self.workspace, 'C', ACCEPTED, 'accepted-baseline', 'accepted')
        (self.workspace/'accepted/frontend/jvm/query/build/libs/graphite.jar').unlink()
        self.assertEqual(['missing accepted graphite.jar'], cache.check(*args))
        self.write(self.workspace/'accepted/frontend/jvm/query/build/libs/graphite.jar', 'jar')
        (self.workspace/'native-pressure-artifacts/C/packet.json').unlink()
        self.assertEqual(['missing packet.json'], cache.check(*args))

    def test_cli_writes_and_checks_the_identity(self):
        identity = self.workspace/'cli-identity.json'
        env = {**os.environ, 'PATH': os.environ['PATH']}
        common = ['--workspace', str(self.workspace), '--arm', 'C', '--revision', ACCEPTED, '--role', 'accepted-baseline', '--checkout', 'accepted']
        run = lambda *argv: subprocess.run([sys.executable, '-B', str(SCRIPTS/'fixture64_arm_cache.py'), *argv], cwd=SCRIPTS, env=env, capture_output=True, text=True)
        written = run('identity', *common, '--output', str(identity))
        self.assertEqual(0, written.returncode, written.stderr)
        self.assertEqual(json.loads(identity.read_text())['revision'], ACCEPTED)
        checked = run('check', *common, '--identity', str(identity))
        self.assertEqual(0, checked.returncode, checked.stderr)
        self.assertEqual(True, json.loads(checked.stdout)['valid'])
        self.write(self.workspace/'native-pressure-artifacts/source-inputs.json', {'jars': []})
        stale = run('check', *common, '--identity', str(identity))
        self.assertEqual(1, stale.returncode)
        self.assertEqual({'valid': False, 'problems': ['identity.commonInputs.source-inputs.json']},
                         {k: v for k, v in json.loads(stale.stdout).items() if k in ('valid', 'problems')})


if __name__ == '__main__':
    unittest.main()
