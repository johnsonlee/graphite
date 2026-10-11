"""Fixed worker byte transport correctness; tiny fixtures are not performance evidence."""
import contextlib
import copy
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import unittest
from unittest.mock import patch
import zipfile

import native_pressure_checkpoint as checkpoint
import test_native_pressure_checkpoint as fixtures


class WorkerChainTests(unittest.TestCase):
    def setUp(self):
        self.f = fixtures.CheckpointTests(methodName='runTest')
        self.f.setUp(); self.addCleanup(self.f.doCleanups)
        self.graph = self.f.root/'B/graphs/graph.nodes'

    def prepare(self, arm='B'):
        if arm == 'A':
            shutil.copytree(self.f.workspace/'base', self.f.workspace/'accepted')
            self.f.write(self.f.workspace/'base/tracked', 'distinct base source')
            subprocess.run(['git', '-C', str(self.f.workspace/'base'), 'add', '.'], check=True)
            subprocess.run(['git', '-C', str(self.f.workspace/'base'), '-c', 'user.name=Fixture',
                            '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'distinct base'],
                           check=True, env={**os.environ, 'GIT_AUTHOR_DATE': '2020-01-02T00:00:00Z',
                                            'GIT_COMMITTER_DATE': '2020-01-02T00:00:00Z'})
            self.f.base_revision = subprocess.check_output(
                ['git', '-C', str(self.f.workspace/'base'), 'rev-parse', 'HEAD'], text=True).strip()
        self.core, self.out, self.plan, self.prep_args, self.prep = self.f.sharded_core_fixture()
        self.producer = checkpoint.read(self.f.directory/'manifest.json')
        # Preparation must actually share producer dependencies for read-count
        # assertions to exercise deduplication across the two manifests.
        self.plan['pins'].update({str(path): checkpoint.sha(path) for path in (self.graph, self.f.writer)})
        self.f.json(self.out/'plan.json', self.plan)
        prepared = checkpoint.read(self.out/'preparation.json')
        prepared['plan']['sha256'] = checkpoint.sha(self.out/'plan.json')
        self.f.json(self.out/'preparation.json', prepared)
        shutil.rmtree(self.prep_args.directory)
        self.prep = checkpoint.seal(self.prep_args)
        if arm == 'A':
            old = self.out; self.out = self.f.root/'A/core-equivalence'
            shutil.copytree(old, self.out)
            self.plan['output'] = str(self.out)
            self.f.json(self.out/'plan.json', self.plan)
            self.f.json(self.out/'preparation.json', {
                'plan': {'path': str(self.out/'plan.json'), 'sha256': checkpoint.sha(self.out/'plan.json')},
                'files': {str(p): checkpoint.sha(p) for directory in (self.out/'classes', self.out/'raw-locals')
                          for p in directory.rglob('*') if p.is_file()}})
            self.prep_args = self.f.args('core-prepare', 'A', self.f.workspace/'pressure-checkpoints/core-prepare-A',
                                         [self.f.directory/'manifest.json'])
            self.prep_args.shard_count = 8
            self.prep = checkpoint.seal(self.prep_args)
        # Simulate named upload/download: each artifact ZIP has its two files at
        # the root, extracted directly into producer-producer/core-prepare-arm.
        for directory in (self.f.directory, Path(self.prep_args.directory)):
            archive = self.f.workspace.parent/(directory.name+'.zip')
            expected = {name: checkpoint.sha(directory/name) for name in ('manifest.json', 'payload.tar.gz')}
            with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as stream:
                for name in expected: stream.write(directory/name, name)
            shutil.rmtree(directory)
            with zipfile.ZipFile(archive) as stream: stream.extractall(directory)
            self.assertEqual(expected, {name: checkpoint.sha(directory/name) for name in expected})
        self.f.remove_payload(self.prep); self.f.remove_payload(self.producer)
        self.args = self.f.args('core-prepare', arm, self.f.workspace/'pressure-checkpoints')
        self.args.shard_count = 8; self.args.shard_index = None

    def mutate(self, change):
        path = Path(self.prep_args.directory)/'manifest.json'
        value = checkpoint.read(path); change(value); checkpoint.save(path, value)

    def consume(self):
        # Consume the restored preparation's real declared partition and bytes;
        # this fixture does not pretend to provide semantic graph acceptance.
        plan = checkpoint.read(self.out/'plan.json')
        self.assertEqual(str(self.out), plan['output'])
        self.assertEqual([row['id'] for row in self.core.shard_rows(self.plan, 2)],
                         [row['id'] for row in self.core.shard_rows(plan, 2)])
        self.assertEqual(8, len(self.core.shard_rows(plan, 2)))
        self.assertEqual('tiny compiled helper', (self.out/'classes/VerifyTopology.class').read_text())
        self.assertEqual('tiny local export', (self.out/'raw-locals/input').read_text())
        self.assertEqual('tiny saved graph', self.graph.read_text())
        for manifest in (self.producer, self.prep): checkpoint.check_files(manifest)

    def roundtrip(self, arm):
        self.prepare(arm)
        reads = []; original = checkpoint.sha
        def digest(path):
            reads.append(str(path)); return original(path)
        with patch.object(checkpoint, 'sha', digest):
            result = checkpoint.restore_worker_chain(self.args)
        self.assertEqual('worker-chain', result['identity']['stage'])
        self.assertEqual(arm, result['identity']['arm']); self.assertEqual(8, result['identity']['shardCount'])
        self.assertFalse(result['proofAuthority']); self.assertFalse(result['performanceAcceptance'])
        # Initial verification plus an independent final disk read, even when
        # the producer and preparation refer to the same runtime dependency.
        self.assertEqual(2, reads.count(str(self.f.writer)))
        self.assertEqual(2, reads.count(str(self.graph)))
        self.assertIn(str(self.graph), self.prep['files'])
        self.assertIn(str(self.f.writer), self.prep['files'])
        self.consume()

    def test_b_roundtrip_and_unique_independent_reads(self): self.roundtrip('B')
    def test_distinct_base_a_roundtrip_and_unique_independent_reads(self): self.roundtrip('A')

    def test_public_cli_only_reports_success_after_final_verification(self):
        self.prepare()
        command = [checkpoint.__file__, 'restore-worker-chain']
        for name, value in vars(self.args).items():
            if value is None or name == 'upstream': continue
            command.extend(['--'+name.replace('_', '-'), str(value)])
        output = io.StringIO(); diagnostic = io.StringIO()
        with patch.object(sys, 'argv', command), contextlib.redirect_stdout(output), contextlib.redirect_stderr(diagnostic):
            checkpoint.main()
        value = json.loads(output.getvalue())
        self.assertEqual('restore-worker-chain', value['checkpointTransport'])
        self.assertEqual('worker-chain', value['identity']['stage'])
        self.assertFalse(value['proofAuthority']); self.assertFalse(value['performanceAcceptance'])
        rows = [json.loads(line.removeprefix('CHECKPOINT_PROGRESS ')) for line in diagnostic.getvalue().splitlines()]
        self.assertTrue(all(row['diagnosticOnly'] for row in rows))
        self.assertEqual(('final-verification', 'complete'), (rows[-1]['phase'], rows[-1]['event']))
        self.consume()

    def test_invalid_profile_rejected_before_writes(self):
        self.prepare()
        for changes in ({'stage': 'core-shard'}, {'stage': 'producer'}, {'arm': 'C'},
                        {'shard_count': 4}, {'shard_count': None}, {'shard_count': True},
                        {'shard_index': 0}, {'upstream': ['arbitrary.json']}):
            args = copy.deepcopy(self.args); vars(args).update(changes)
            with self.subTest(changes=changes), self.assertRaisesRegex(ValueError, 'fixed worker chain arguments'):
                checkpoint.restore_worker_chain(args)
            self.assertFalse(self.graph.exists())

    def test_aliased_a_cannot_claim_checkpoint(self):
        self.prepare(); self.args.arm = 'A'
        with self.assertRaisesRegex(ValueError, 'aliased arm'): checkpoint.restore_worker_chain(self.args)
        self.assertFalse(self.graph.exists())

    def test_missing_checkpoint_rejected_before_writes(self):
        self.prepare(); (Path(self.prep_args.directory)/'manifest.json').unlink()
        with self.assertRaises(FileNotFoundError): checkpoint.restore_worker_chain(self.args)
        self.assertFalse(self.graph.exists())

    def test_wrong_identity_and_upstream_rejected_before_writes(self):
        self.prepare(); path = Path(self.prep_args.directory)/'manifest.json'; original = checkpoint.read(path)
        changes = [lambda m, key=key: m['identity'].update({key: 'wrong'})
                   for key in ('head', 'base', 'arm', 'stage', 'runId', 'runAttempt', 'shardCount')]
        changes += [lambda m: m.update(upstream=[]), lambda m: m['upstream'][0].update(sha256='0'*64),
                    lambda m: m.update(sources={}), lambda m: m.update(runnerImage={})]
        for index, change in enumerate(changes):
            value = copy.deepcopy(original); change(value); checkpoint.save(path, value)
            with self.subTest(index=index), self.assertRaises(ValueError): checkpoint.restore_worker_chain(self.args)
            self.assertFalse(self.graph.exists())

    def test_conflicting_payload_rejected_before_writes(self):
        self.prepare(); self.mutate(lambda m: m['payload'].append(str(self.f.writer)))
        with self.assertRaisesRegex(ValueError, 'overwrites frozen input'): checkpoint.restore_worker_chain(self.args)
        self.assertFalse(self.graph.exists())

    def test_corrupt_archive_cannot_succeed(self):
        self.prepare(); (Path(self.prep_args.directory)/'payload.tar.gz').write_bytes(b'bad archive')
        with self.assertRaisesRegex(ValueError, 'archive digest'): checkpoint.restore_worker_chain(self.args)

    def test_incomplete_archive_even_with_matching_digest_rejected(self):
        self.prepare(); archive = Path(self.prep_args.directory)/'payload.tar.gz'
        with tarfile.open(archive, 'r:gz') as stream:
            members = [(member, stream.extractfile(member).read()) for member in stream.getmembers()]
        with tarfile.open(archive, 'w:gz') as stream:
            for member, data in members[:-1]: stream.addfile(member, io.BytesIO(data))
        self.mutate(lambda m: m.update(archiveSha256=checkpoint.sha(archive)))
        with self.assertRaisesRegex(ValueError, 'unsafe or incomplete archive members'):
            checkpoint.restore_worker_chain(self.args)

    def test_late_same_size_same_mtime_mutation_rejected_by_fresh_final_read(self):
        self.prepare(); original = checkpoint.restore
        def mutate(args, authority=None):
            value = original(args, authority)
            if args.stage == 'core-prepare':
                before = self.graph.stat()
                self.graph.write_bytes(b'x'*before.st_size)
                os.utime(self.graph, ns=(before.st_atime_ns, before.st_mtime_ns))
            return value
        with patch.object(checkpoint, 'restore', mutate), self.assertRaisesRegex(ValueError, 'chain file digest changed'):
            checkpoint.restore_worker_chain(self.args)

    def test_late_extra_closed_file_rejected(self):
        self.prepare(); original = checkpoint.restore
        def mutate(args, authority=None):
            value = original(args, authority)
            if args.stage == 'core-prepare': self.f.write(self.graph.parent/'extra', 'unexpected')
            return value
        with patch.object(checkpoint, 'restore', mutate), self.assertRaisesRegex(ValueError, 'closed directory membership'):
            checkpoint.restore_worker_chain(self.args)

    def test_late_manifest_change_rejected(self):
        self.prepare(); original = checkpoint.restore
        def mutate(args, authority=None):
            value = original(args, authority)
            if args.stage == 'core-prepare': self.mutate(lambda m: m.update(proofAuthority=True))
            return value
        with patch.object(checkpoint, 'restore', mutate), self.assertRaisesRegex(ValueError, 'preflight checkpoint manifest changed'):
            checkpoint.restore_worker_chain(self.args)

    def test_standalone_restore_cannot_reuse_worker_authority(self):
        self.prepare(); checkpoint.restore_worker_chain(self.args)
        self.f.writer.write_bytes(b'x'*self.f.writer.stat().st_size)
        with self.assertRaises(ValueError): checkpoint.restore(self.prep_args)
