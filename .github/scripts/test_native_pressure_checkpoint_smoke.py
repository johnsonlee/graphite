"""Fast synthetic transport smoke; no graph execution or performance evidence.

Exercise the public CLI dispatch with real seal/archive/restore code and the
workflow's named-download directories. Only fixture identities/system paths are
patched, using the existing transport unit-test fixture.
"""
import contextlib
import fnmatch
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import unittest
from unittest.mock import patch
import zipfile

import native_pressure_checkpoint as checkpoint
import test_native_pressure_checkpoint as fixtures


class CheckpointChainSmoke(unittest.TestCase):
    def setUp(self):
        self.f = fixtures.CheckpointTests()
        self.f.setUp()
        self.addCleanup(self.f.doCleanups)
        self.workflow = (Path(__file__).resolve().parents[1]/'workflows/benchmark.yml').read_text()
        self.jdk, self.certificate, self.link = self.f.system_jdk_fixture('adoptium')
        self.certificate_before = self.certificate.read_bytes()
        self.jdk_before = {str(p): (p.lstat().st_mode, p.readlink().as_posix() if p.is_symlink()
                                  else p.read_bytes() if p.is_file() else None)
                           for p in self.jdk.rglob('*')}

    def cli(self, action, args):
        argv = ['native_pressure_checkpoint.py', action]
        for name, value in vars(args).items():
            if name == 'upstream':
                for path in value:
                    argv.extend(['--upstream', path])
            else:
                argv.extend(['--'+name.replace('_', '-'), str(value)])
        output = io.StringIO()
        with patch.object(sys, 'argv', argv), contextlib.redirect_stdout(output):
            checkpoint.main()
        receipt = json.loads(output.getvalue())
        self.assertEqual(action, receipt['checkpointTransport'])
        self.assertIs(receipt['proofAuthority'], False)
        self.assertIs(receipt['performanceAcceptance'], False)
        return checkpoint.read(Path(args.directory)/'manifest.json')

    def download_directory(self, stage, arm, job):
        """Resolve the actual named-download destination, even for one artifact."""
        job_body = self.workflow.split('\n  '+job+':\n', 1)[1].split('\n  rust-', 1)[0]
        label = 'producer' if stage == 'producer' else stage+' '+arm
        step = job_body.split('    - name: Download '+label+' checkpoint\n', 1)[1].split('\n    - name:', 1)[0]
        self.assertIn('uses: actions/download-artifact@v8', step)
        self.assertNotIn('pattern:', step)
        name = re.search(r'^        name: (.+)$', step, re.M).group(1)
        directory = re.search(r'^        path: (.+)$', step, re.M).group(1)
        suffix = '${{ github.run_id }}-${{ github.run_attempt }}'
        self.assertEqual('native-checkpoint-'+stage+'-'+arm+'-'+suffix, name)
        expected = 'pressure-checkpoints/producer-producer' if stage == 'producer' else 'pressure-checkpoints/'+name
        self.assertEqual(expected, directory)
        if stage != 'producer':
            self.assertIn('DIRECTORY="pressure-checkpoints/native-checkpoint-'+stage+'-${ARM}-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"', job_body)
        return self.f.workspace/directory.replace('${{ github.run_id }}', '1234').replace('${{ github.run_attempt }}', '2')

    def transfer(self, sealed, destination):
        # upload-artifact publishes the two files at the root of its ZIP;
        # download-artifact(name=...) extracts directly into the given path.
        archive = self.f.workspace.parent/(sealed.name+'.zip')
        with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as out:
            for name in ('manifest.json', 'payload.tar.gz'):
                out.write(sealed/name, name)
        expected = {name: checkpoint.sha(sealed/name) for name in ('manifest.json', 'payload.tar.gz')}
        shutil.rmtree(sealed)
        destination.mkdir(parents=True)
        with zipfile.ZipFile(archive) as incoming:
            self.assertEqual(['manifest.json', 'payload.tar.gz'], incoming.namelist())
            incoming.extractall(destination)
        self.assertEqual(expected, {name: checkpoint.sha(destination/name) for name in expected})
        return destination/'manifest.json'

    def chain(self, separate_parent):
        f = self.f
        if separate_parent:
            subprocess.run(['git', 'clone', '-q', str(f.workspace/'base'), str(f.workspace/'accepted')], check=True)
            f.write(f.workspace/'base/tracked', 'distinct parent fixture')
            subprocess.run(['git', '-C', str(f.workspace/'base'), '-c', 'user.name=Fixture',
                            '-c', 'user.email=fixture@example.invalid', 'commit', '-qam', 'parent'], check=True)
            f.base_revision = subprocess.check_output(['git', '-C', str(f.workspace/'base'), 'rev-parse', 'HEAD'], text=True).strip()
        producer_args = f.args(directory=f.workspace/'sealed/producer')
        producer = self.cli('seal', producer_args)
        producer_ref = self.transfer(Path(producer_args.directory), self.download_directory('producer', 'producer', 'rust-jvm-correctness'))
        f.remove_payload(producer)
        self.assertFalse(f.binary.exists())
        self.cli('restore', f.args(directory=producer_ref.parent))
        self.assertEqual('tiny binary', f.binary.read_text())
        self.assertEqual('tiny saved graph', (f.root/'B/graphs/graph.nodes').read_text())
        refs = [producer_ref]
        core_arms = ('A', 'B') if separate_parent else ('B',)
        pins = {str(f.binary): checkpoint.sha(f.binary), str(self.jdk/'bin/javac'): checkpoint.sha(self.jdk/'bin/javac')}
        for arm in core_arms:
            f.json(f.root/arm/'core-equivalence/plan.json', {'pins': pins, 'fixtureResult': 'core-'+arm})
            args = f.args('core', arm, f.workspace/('sealed/core-'+arm), [producer_ref])
            manifest = self.cli('seal', args)
            ref = self.transfer(Path(args.directory), self.download_directory('core', arm, 'rust-jvm-correctness'))
            f.remove_payload(manifest)
            self.cli('restore', f.args('core', arm, ref.parent, [producer_ref]))
            self.assertEqual('core-'+arm, checkpoint.read(f.root/arm/'core-equivalence/plan.json')['fixtureResult'])
            refs.append(ref)
        for arm in (('C', 'A', 'B') if separate_parent else ('C', 'B')):
            for stage in ('jvm-raw-derivation', 'jvm-query-correctness'):
                f.json(f.root/arm/stage/'plan.json', {'pins': pins, 'fixtureResult': stage+'-'+arm})
            args = f.args('jvm', arm, f.workspace/('sealed/jvm-'+arm), refs)
            manifest = self.cli('seal', args)
            destination = self.download_directory('jvm', arm, 'rust-query-jvm')
            ref = self.transfer(Path(args.directory), destination)
            f.remove_payload(manifest)
            target = f.root/arm/'jvm-query-correctness/plan.json'
            # Missing or changed transferred upstream must fail before writing
            # the consumer's JVM payload, even though all producer bytes exist.
            with self.assertRaisesRegex(ValueError, 'exact upstream stages'):
                self.cli('restore', f.args('jvm', arm, destination, refs[:-1]))
            self.assertFalse(target.exists())
            old = refs[-1].read_bytes()
            refs[-1].write_bytes(old+b'\n')
            with self.assertRaisesRegex(ValueError, 'upstream checkpoint binding'):
                self.cli('restore', f.args('jvm', arm, destination, refs))
            self.assertFalse(target.exists())
            refs[-1].write_bytes(old)
            self.cli('restore', f.args('jvm', arm, destination, refs))
            checkpoint.check_files(manifest)
            self.assertEqual('jvm-query-correctness-'+arm, checkpoint.read(target)['fixtureResult'])
            self.assertEqual('jvm-raw-derivation-'+arm, checkpoint.read(f.root/arm/'jvm-raw-derivation/plan.json')['fixtureResult'])
            self.assertEqual(producer['systemJdkLinks'], manifest['systemJdkLinks'])
            self.assertIn(str(self.certificate), manifest['verificationOnly'])
            self.assertNotIn(str(self.certificate), manifest['payload'])
        self.assertEqual(self.certificate_before, self.certificate.read_bytes())
        self.assertEqual(self.jdk_before, {str(p): (p.lstat().st_mode, p.readlink().as_posix() if p.is_symlink()
                                                 else p.read_bytes() if p.is_file() else None)
                                          for p in self.jdk.rglob('*')})

    def test_alias_single_core_named_download_to_jvm_consumers(self):
        self.chain(False)

    def test_distinct_parent_two_core_named_downloads_to_all_jvm_consumers(self):
        self.chain(True)

    def test_shared_preparation_eight_shard_transfers_and_self_contained_core(self):
        f = self.f
        core, out, plan, prep_args, prep = f.sharded_core_fixture()
        producer_ref = self.transfer(f.directory, self.download_directory('producer', 'producer', 'rust-core-prepare'))
        # Exercise the public CLI for the newly introduced transport stages too.
        shutil.rmtree(prep_args.directory)
        prep = self.cli('seal', prep_args)
        job = self.workflow.split('\n  rust-core:\n', 1)[1].split('\n  rust-', 1)[0]
        steps = job.split('    - name: ')
        prep_step = next(s for s in steps if 'uses: actions/download-artifact@' in s and 'name: native-checkpoint-core-prepare-' in s)
        shard_step = next(s for s in steps if 'uses: actions/download-artifact@' in s and 'pattern: native-checkpoint-core-shard-' in s)
        self.assertIn('merge-multiple: false', shard_step)

        def expand(value):
            for key, replacement in (('matrix.arm', 'B'), ('github.run_id', '1234'), ('github.run_attempt', '2')):
                value = value.replace('${{ '+key+' }}', replacement)
            self.assertNotIn('${{', value)
            return value

        prep_destination = f.workspace/expand(re.search(r'^        path: (.+)$', prep_step, re.M).group(1))
        prep_ref = self.transfer(Path(prep_args.directory), prep_destination)
        prep_args.directory = str(prep_destination)
        f.remove_payload(prep)
        self.cli('restore', prep_args)
        pattern = expand(re.search(r'^        pattern: (.+)$', shard_step, re.M).group(1))
        shard_destination = f.workspace/expand(re.search(r'^        path: (.+)$', shard_step, re.M).group(1))
        transfers = []
        for index in range(8):
            args = f.write_core_shard(core, out, plan, prep_args, index)
            manifest = self.cli('seal', args)
            artifact = f'native-checkpoint-core-shard-B-{index}-1234-2'
            self.assertTrue(fnmatch.fnmatchcase(artifact, pattern))
            ref = self.transfer(Path(args.directory), shard_destination/artifact)
            args.directory = str(ref.parent)
            transfers.append((args, manifest))
            # Each worker starts with preparation only; sibling outputs are
            # combined on the join runner after every independent seal.
            f.remove_payload(manifest)
        for args, manifest in reversed(transfers):
            self.cli('restore', args)
            checkpoint.check_files(manifest)
        self.assertEqual(64, len(list((out/'graphs').iterdir())))
        self.assertTrue(all((out/name/'record.json').is_file() for name, _, _ in core.commands(plan)))
        self.assertEqual(139, len(list(core.commands(plan))))
        checkpoint.check_files(prep)
        # A tiny consumer verifies complete transport, without claiming that its
        # placeholder proof bytes execute any real graph correctness checks.
        f.json(out/'record.json', {'fixtureResult': 'all64-transferred'})
        args = f.args('core', 'B', f.workspace/'sealed/final-core', [producer_ref])
        complete = self.cli('seal', args)
        final_ref = self.transfer(Path(args.directory), self.download_directory('core', 'B', 'rust-jvm-correctness'))
        f.remove_payload(complete)
        self.cli('restore', f.args('core', 'B', final_ref.parent, [producer_ref]))
        self.assertEqual('all64-transferred', checkpoint.read(out/'record.json')['fixtureResult'])
        self.assertEqual(64, len(list((out/'graphs').iterdir())))
        self.assertTrue(all((out/'shards'/str(i)/'record.json').is_file() for i in range(8)))
        self.assertEqual(self.certificate_before, self.certificate.read_bytes())


if __name__ == '__main__':
    unittest.main()
