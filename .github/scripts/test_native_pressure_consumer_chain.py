"""Fixed consumer transport correctness; tiny files provide no performance evidence."""
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

import native_pressure_checkpoint as checkpoint
import test_native_pressure_checkpoint as fixtures


class ConsumerChainTests(unittest.TestCase):
    def setUp(self):
        self.f = fixtures.CheckpointTests(methodName='runTest')
        self.f.setUp(); self.addCleanup(self.f.doCleanups)
        self.graph = self.f.root/'B/graphs/graph.nodes'
        self.entries = []

    def prepare(self, stage='jvm', distinct_base=False):
        if distinct_base:
            shutil.copytree(self.f.workspace/'base', self.f.workspace/'accepted')
            self.f.write(self.f.workspace/'base/tracked', 'distinct parent source')
            subprocess.run(['git', '-C', str(self.f.workspace/'base'), 'add', '.'], check=True)
            subprocess.run(['git', '-C', str(self.f.workspace/'base'), '-c', 'user.name=Fixture',
                            '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'distinct parent'],
                           check=True, env={**os.environ, 'GIT_AUTHOR_DATE': '2020-01-02T00:00:00Z',
                                            'GIT_COMMITTER_DATE': '2020-01-02T00:00:00Z'})
            self.f.base_revision = subprocess.check_output(
                ['git', '-C', str(self.f.workspace/'base'), 'rev-parse', 'HEAD'], text=True).strip()
        producer = self.f.args()
        self.entries.append((producer, checkpoint.seal(producer)))
        cores = []
        for arm in (('A', 'B') if distinct_base else ('B',)):
            self.f.json(self.f.root/arm/'core-equivalence/plan.json',
                        {'pins': {str(self.graph): checkpoint.sha(self.graph)}})
            directory = self.f.workspace/f'pressure-checkpoints/native-checkpoint-core-{arm}-1234-2'
            args = self.f.args('core', arm, directory, [self.f.directory/'manifest.json'])
            self.entries.append((args, checkpoint.seal(args)))
            cores.append(directory/'manifest.json')
        if stage == 'jvm':
            for arm in (('C', 'A', 'B') if distinct_base else ('C', 'B')):
                for name in ('jvm-raw-derivation', 'jvm-query-correctness'):
                    self.f.json(self.f.root/arm/name/'plan.json',
                                {'pins': {str(self.graph): checkpoint.sha(self.graph)}})
                directory = self.f.workspace/f'pressure-checkpoints/native-checkpoint-jvm-{arm}-1234-2'
                args = self.f.args('jvm', arm, directory, [self.f.directory/'manifest.json', *cores])
                self.entries.append((args, checkpoint.seal(args)))
        for _, value in reversed(self.entries): self.f.remove_payload(value)
        self.args = self.f.args(stage, 'B', self.f.workspace/'pressure-checkpoints')
        return self.args

    def mutate_last(self, change):
        path = Path(self.entries[-1][0].directory)/'manifest.json'
        value = checkpoint.read(path); change(value); checkpoint.save(path, value)

    def roundtrip(self, stage, distinct_base):
        self.prepare(stage, distinct_base)
        hashes = []; original = checkpoint.sha
        def digest(path):
            hashes.append(str(path)); return original(path)
        with patch.object(checkpoint, 'sha', digest):
            result = checkpoint.restore_consumer_chain(self.args)
        self.assertEqual(stage+'-consumer-chain', result['identity']['stage'])
        self.assertFalse(result['proofAuthority']); self.assertFalse(result['performanceAcceptance'])
        self.assertEqual('tiny saved graph', self.graph.read_text())
        # One initial disk verification, one independent final read, regardless
        # of how many later core/JVM manifests pin this same graph file.
        self.assertEqual(2, hashes.count(str(self.graph)))
        self.assertEqual(2, hashes.count(str(self.f.binary)))
        self.assertEqual((6 if distinct_base else 4) if stage == 'jvm' else (3 if distinct_base else 2),
                         len(self.entries))
        for _, manifest in self.entries: checkpoint.check_files(manifest)

    def test_core_accepted_base_omits_a(self): self.roundtrip('core', False)
    def test_core_distinct_base_includes_a(self): self.roundtrip('core', True)
    def test_jvm_accepted_base_omits_a(self): self.roundtrip('jvm', False)
    def test_jvm_distinct_base_includes_a(self): self.roundtrip('jvm', True)

    def test_public_cli_has_fixed_profile_and_transport_only_result(self):
        self.prepare()
        command = [checkpoint.__file__, 'restore-consumer-chain']
        for key, value in vars(self.args).items():
            if key == 'upstream': continue
            command.extend(['--'+key.replace('_', '-'), str(value)])
        output = io.StringIO(); diagnostic = io.StringIO()
        with patch.object(sys, 'argv', command), contextlib.redirect_stdout(output), contextlib.redirect_stderr(diagnostic):
            checkpoint.main()
        lines = output.getvalue().splitlines(); self.assertEqual(1, len(lines))
        value = json.loads(lines[0])
        self.assertEqual('restore-consumer-chain', value['checkpointTransport'])
        self.assertEqual('jvm-consumer-chain', value['identity']['stage'])
        self.assertFalse(value['proofAuthority']); self.assertFalse(value['performanceAcceptance'])
        progress = [json.loads(line.removeprefix('CHECKPOINT_PROGRESS ')) for line in diagnostic.getvalue().splitlines()]
        self.assertTrue(all(row['diagnosticOnly'] is True for row in progress))
        self.assertEqual(('final-verification', 'complete'), (progress[-1]['phase'], progress[-1]['event']))

    def test_custom_upstreams_shards_and_noncoordinator_profiles_rejected(self):
        self.prepare()
        for changes in ({'upstream': ['arbitrary.json']}, {'shard_count': 8}, {'shard_index': 0},
                        {'arm': 'A'}, {'arm': 'C'}, {'stage': 'producer'}, {'stage': 'core-shard'}):
            args = copy.deepcopy(self.args); vars(args).update(changes)
            with self.subTest(changes=changes), self.assertRaisesRegex(ValueError, 'fixed consumer chain arguments'):
                checkpoint.restore_consumer_chain(args)
            self.assertFalse(self.graph.exists())

    def test_missing_required_a_checkpoint_rejected_before_writes(self):
        self.prepare(distinct_base=True)
        path = self.f.workspace/'pressure-checkpoints/native-checkpoint-core-A-1234-2/manifest.json'
        path.unlink()
        with self.assertRaises(FileNotFoundError): checkpoint.restore_consumer_chain(self.args)
        self.assertFalse(self.graph.exists())

    def test_missing_required_jvm_checkpoint_rejected_before_writes(self):
        self.prepare()
        (Path(self.entries[-1][0].directory)/'manifest.json').unlink()
        with self.assertRaises(FileNotFoundError): checkpoint.restore_consumer_chain(self.args)
        self.assertFalse(self.graph.exists())

    def test_identity_source_interpreter_runner_and_upstream_mismatch_rejected_before_writes(self):
        self.prepare()
        path = Path(self.entries[-1][0].directory)/'manifest.json'; original = checkpoint.read(path)
        changes = [lambda m, key=key: m['identity'].update({key: 'wrong'})
                   for key in ('head', 'base', 'accepted', 'runId', 'runAttempt', 'workspace', 'stage', 'arm')]
        changes += [lambda m: m.update(python='/wrong/python'), lambda m: m.update(runnerImage={}),
                    lambda m: m.update(sources={}), lambda m: m.update(upstream=[]),
                    lambda m: m['upstream'][0].update(sha256='0'*64)]
        for index, change in enumerate(changes):
            value = copy.deepcopy(original); change(value); checkpoint.save(path, value)
            with self.subTest(index=index), self.assertRaises(ValueError):
                checkpoint.restore_consumer_chain(self.args)
            self.assertFalse(self.graph.exists())
        checkpoint.save(path, original)

    def test_conflicting_file_payload_and_closed_inventory_rejected_before_writes(self):
        self.prepare()
        path = Path(self.entries[-1][0].directory)/'manifest.json'; original = checkpoint.read(path)
        def extra(value):
            name = str(self.graph.parent/'extra')
            value['files'][name] = {'sha256': '0'*64, 'mode': 0o644, 'size': 0}
            value['payload'].append(name)
        for change in (lambda m: m['payload'].append(str(self.graph)),
                       lambda m: m['files'][str(self.graph)].update(sha256='0'*64),
                       lambda m: m['files'][str(self.graph)].update(mode=0o600), extra):
            value = copy.deepcopy(original); change(value); checkpoint.save(path, value)
            with self.assertRaises(ValueError): checkpoint.restore_consumer_chain(self.args)
            self.assertFalse(self.graph.exists())
        checkpoint.save(path, original)

    def test_archive_digest_failure_cannot_return_success(self):
        self.prepare()
        (Path(self.entries[-1][0].directory)/'payload.tar.gz').write_bytes(b'bad archive')
        with self.assertRaisesRegex(ValueError, 'archive digest'): checkpoint.restore_consumer_chain(self.args)

    def test_archive_incomplete_even_with_matching_digest_is_rejected(self):
        self.prepare()
        archive = Path(self.entries[-1][0].directory)/'payload.tar.gz'
        with tarfile.open(archive, 'r:gz') as stream:
            members = [(member, stream.extractfile(member).read()) for member in stream.getmembers()]
        with tarfile.open(archive, 'w:gz') as stream:
            for member, data in members[:-1]: stream.addfile(member, io.BytesIO(data))
        self.mutate_last(lambda m: m.update(archiveSha256=checkpoint.sha(archive)))
        with self.assertRaisesRegex(ValueError, 'unsafe or incomplete archive members'):
            checkpoint.restore_consumer_chain(self.args)

    def test_existing_wrong_payload_never_overwritten(self):
        self.prepare()
        self.f.write(self.graph, 'preexisting wrong bytes')
        with self.assertRaisesRegex(ValueError, 'refuse overwriting different file'):
            checkpoint.restore_consumer_chain(self.args)
        self.assertEqual('preexisting wrong bytes', self.graph.read_text())

    def test_late_same_size_mutation_rejected_by_independent_final_read(self):
        self.prepare(); original = checkpoint.restore
        def mutate(args, authority=None):
            value = original(args, authority)
            if args.stage == 'jvm' and args.arm == 'B': self.graph.write_bytes(b'x'*self.graph.stat().st_size)
            return value
        with patch.object(checkpoint, 'restore', mutate), self.assertRaisesRegex(ValueError, 'chain file digest changed'):
            checkpoint.restore_consumer_chain(self.args)

    def test_late_closed_directory_extra_rejected(self):
        self.prepare(); original = checkpoint.restore
        def mutate(args, authority=None):
            value = original(args, authority)
            if args.stage == 'jvm' and args.arm == 'B': self.f.write(self.graph.parent/'extra', 'unexpected')
            return value
        with patch.object(checkpoint, 'restore', mutate), self.assertRaisesRegex(ValueError, 'closed directory membership'):
            checkpoint.restore_consumer_chain(self.args)

    def test_late_manifest_change_rejected(self):
        self.prepare(); original = checkpoint.restore
        def mutate(args, authority=None):
            value = original(args, authority)
            if args.stage == 'jvm' and args.arm == 'B': self.mutate_last(lambda m: m.update(proofAuthority=True))
            return value
        with patch.object(checkpoint, 'restore', mutate), self.assertRaisesRegex(ValueError, 'preflight checkpoint manifest changed'):
            checkpoint.restore_consumer_chain(self.args)

    def test_late_source_change_rejected(self):
        self.prepare(); original = checkpoint.restore
        def mutate(args, authority=None):
            value = original(args, authority)
            if args.stage == 'jvm' and args.arm == 'B': self.f.write(self.f.workspace/'candidate/tracked', 'changed source')
            return value
        with patch.object(checkpoint, 'restore', mutate), self.assertRaisesRegex(ValueError, 'exact clean checkout'):
            checkpoint.restore_consumer_chain(self.args)

    def test_standalone_restore_still_rechecks_shared_graph(self):
        self.prepare(); checkpoint.restore_consumer_chain(self.args)
        self.graph.write_bytes(b'x'*self.graph.stat().st_size)
        with self.assertRaises(ValueError): checkpoint.restore(self.entries[-1][0])
