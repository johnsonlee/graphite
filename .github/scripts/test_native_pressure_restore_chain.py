"""Fixed core-chain byte transport; tiny files never establish performance evidence."""
import copy
import json
import contextlib
import io
import sys
import tarfile
from pathlib import Path
import unittest
from unittest.mock import patch

import native_pressure_checkpoint as checkpoint
import test_native_pressure_checkpoint as fixtures


class ClosedDirectoryIndexTests(unittest.TestCase):
    @staticmethod
    def old_accepts(path, inventories):
        return all(str(path.relative_to(closed)) in members
                   for closed, members in inventories.items()
                   if path != Path(closed) and path.is_relative_to(Path(closed)))

    def test_index_matches_original_predicate_for_aliases_nested_and_relative_roots(self):
        cases = [
            {'/proof': ['nested', 'nested/a', 'nested/b', 'top'],
             '/proof/nested': ['a', 'b'], '/proof/./nested/': ['a']},
            {'/proof': ['nested/a'], '/proof/nested': ['a']},
            {'/proof/nested': [], '/proof/./nested/': ['a']},
            {'proof': ['nested', 'nested/a'], 'proof/./nested': ['a']},
            {'/': ['proof', 'proof/nested', 'proof/nested/a']},
            {'/proof/../other': ['a'], '.': ['proof', 'proof/nested']},
        ]
        paths = [Path(p) for p in ('/', '/proof', '/proof/nested', '/proof/nested/a',
            '/proof/nested/b', '/proof/top', '/proof/other', '/other/a',
            'proof', 'proof/nested', 'proof/nested/a', 'proof/nested/b', '.')]
        for inventories in cases:
            original = copy.deepcopy(inventories)
            index = checkpoint._ClosedDirectoryIndex()
            # Separate extensions mirror distinct preceding checkpoints. Alias
            # constraints must survive even when inserted in separate calls.
            for root, members in inventories.items(): index.extend({root: members})
            self.assertEqual(original, inventories)
            for path in paths:
                with self.subTest(inventories=inventories, path=path):
                    if self.old_accepts(path, inventories): index.check(path)
                    else:
                        with self.assertRaisesRegex(ValueError, 'chain extends frozen closed directory'):
                            index.check(path)
        index = checkpoint._ClosedDirectoryIndex()
        index.extend(cases[0]); index.check(Path('/proof/nested/a'))
        index.check(Path('/proof/nested'))  # Self excluded, outer root still checked.
        with self.assertRaises(ValueError): index.check(Path('/proof/nested/b'))
        reversed_index = checkpoint._ClosedDirectoryIndex()
        reversed_index.extend(dict(reversed(list(cases[0].items()))))
        with self.assertRaises(ValueError): reversed_index.check(Path('/proof/nested/b'))

    def test_lookup_count_depends_on_path_depth_not_unrelated_root_count(self):
        class Counted(dict):
            def __init__(self, values): super().__init__(values); self.lookups = 0
            def get(self, key): self.lookups += 1; return super().get(key)
        index = checkpoint._ClosedDirectoryIndex()
        index.extend({f'/other-{n}': ['member'] for n in range(100)})
        index.extend({'/proof': ['nested', 'nested/a'], '/proof/nested': ['a']})
        index.members = Counted(index.members)
        path = Path('/proof/nested/a'); index.check(path)
        self.assertEqual(len(path.parents), index.members.lookups)
        self.assertEqual(3, index.members.lookups)


class RestoreChainTests(unittest.TestCase):
    def setUp(self):
        self.f = fixtures.CheckpointTests(methodName='runTest'); self.f.setUp(); self.addCleanup(self.f.doCleanups)
        self.core, self.out, self.plan, self.prep_args, self.prep = self.f.sharded_core_fixture()
        self.producer = checkpoint.read(self.f.directory/'manifest.json')
        self.shards = []
        for index in range(8):
            args = self.f.write_core_shard(self.core, self.out, self.plan, self.prep_args, index)
            value = checkpoint.seal(args)
            directory = self.f.workspace/f'pressure-checkpoints/native-checkpoint-core-shard-B-{index}-1234-2'
            Path(args.directory).rename(directory)
            self.shards.append((directory, value))
            self.f.remove_payload(value)
        self.f.remove_payload(self.prep); self.f.remove_payload(self.producer)
        self.args = self.f.args('core-shard', 'B', self.f.workspace/'pressure-checkpoints')
        self.args.shard_count = 8; self.args.shard_index = None

    def mutate(self, index, function):
        path = self.shards[index][0]/'manifest.json'; value = checkpoint.read(path); function(value); checkpoint.save(path, value)

    def test_roundtrip_all_eight_and_unique_final_hashes(self):
        hashes = []; original = checkpoint.sha
        def digest(path):
            hashes.append(str(path)); return original(path)
        with patch.object(checkpoint, 'sha', digest): result = checkpoint.restore_core_chain(self.args)
        self.assertEqual('core-chain', result['identity']['stage']); self.assertFalse(result['proofAuthority'])
        self.assertEqual(64, len(list((self.out/'graphs').iterdir())))
        # One initial on-disk verification and one independent final read; no per-shard rehash.
        self.assertEqual(2, hashes.count(str(self.f.binary)))
        self.assertEqual(2, hashes.count(str(self.out/'raw-locals/input')))
        for value in [self.producer, self.prep, *[value for _, value in self.shards]]: checkpoint.check_files(value)

    def test_public_cli_emits_only_complete_chain_success(self):
        command = [checkpoint.__file__, 'restore-core-chain']
        for key, value in vars(self.args).items():
            if value is None or key == 'upstream': continue
            command.extend(['--'+key.replace('_', '-'), str(value)])
        output = io.StringIO(); diagnostic = io.StringIO()
        with patch.object(sys, 'argv', command), contextlib.redirect_stdout(output), contextlib.redirect_stderr(diagnostic):
            checkpoint.main()
        progress = [json.loads(line.removeprefix('CHECKPOINT_PROGRESS ')) for line in diagnostic.getvalue().splitlines()]
        self.assertTrue(all(row['diagnosticOnly'] is True for row in progress))
        self.assertEqual(('preflight', 'start'), (progress[0]['phase'], progress[0]['event']))
        self.assertEqual(('final-verification', 'complete'), (progress[-1]['phase'], progress[-1]['event']))
        self.assertNotIn('PASS', diagnostic.getvalue())
        lines = output.getvalue().splitlines(); self.assertEqual(1, len(lines))
        value = json.loads(lines[0]); self.assertEqual('core-chain', value['identity']['stage'])
        self.assertFalse(value['proofAuthority']); self.assertFalse(value['performanceAcceptance'])

    def test_conflicting_file_and_payload_overwrite_rejected_before_writes(self):
        path = self.shards[1][0]/'manifest.json'; original = checkpoint.read(path)
        for change in [lambda m: m['payload'].append(str(self.f.binary)),
                       lambda m: m['files'][str(self.f.workspace/'candidate/.github/scripts/run_native_core_equivalence.py')].update(sha256='0'*64),
                       lambda m: m['files'][str(self.f.workspace/'candidate/.github/scripts/run_native_core_equivalence.py')].update(mode=0o600)]:
            value = copy.deepcopy(original); change(value); checkpoint.save(path, value)
            with self.assertRaises(ValueError): checkpoint.restore_core_chain(self.args)
            self.assertFalse(self.f.binary.exists())
        checkpoint.save(path, original)

    def test_extending_upstream_closed_root_rejected_before_writes(self):
        def add(value):
            name = str(self.f.root/'B/graphs/extra')
            value['files'][name] = {'sha256': '0'*64, 'mode': 0o644, 'size': 0}; value['payload'].append(name)
        self.mutate(1, add)
        with self.assertRaisesRegex(ValueError, 'closed directory'): checkpoint.restore_core_chain(self.args)
        self.assertFalse(self.f.binary.exists())

    def test_original_closed_inventory_order_is_still_compared_exactly(self):
        root, members = next((root, members) for root, members in self.producer['closedDirectories'].items()
                             if len(members) > 1)
        self.mutate(1, lambda value: value['closedDirectories'].update({root: list(reversed(members))}))
        with self.assertRaisesRegex(ValueError, 'chain conflicting closed inventory'):
            checkpoint.restore_core_chain(self.args)
        self.assertFalse(self.f.binary.exists())

    def test_missing_manifest_and_wrong_identity_rejected_before_writes(self):
        self.mutate(7, lambda value: value['identity'].update(runId='9999'))
        with self.assertRaisesRegex(ValueError, 'checkpoint identity'): checkpoint.restore_core_chain(self.args)
        self.assertFalse(self.f.binary.exists())
        (self.shards[7][0]/'manifest.json').unlink()
        with self.assertRaises(FileNotFoundError): checkpoint.restore_core_chain(self.args)
        self.assertFalse(self.f.binary.exists())

    def test_archive_failure_does_not_return_success(self):
        (self.shards[7][0]/'payload.tar.gz').write_bytes(b'bad archive')
        with self.assertRaisesRegex(ValueError, 'archive digest'): checkpoint.restore_core_chain(self.args)

    def test_missing_archive_member_rejected_even_with_matching_archive_digest(self):
        directory = self.shards[7][0]; archive = directory/'payload.tar.gz'
        with tarfile.open(archive, 'r:gz') as stream:
            members = [(member, stream.extractfile(member).read()) for member in stream.getmembers()]
        with tarfile.open(archive, 'w:gz') as stream:
            for member, content in members[:-1]: stream.addfile(member, io.BytesIO(content))
        self.mutate(7, lambda value: value.update(archiveSha256=checkpoint.sha(archive)))
        with self.assertRaisesRegex(ValueError, 'unsafe or incomplete archive members'):
            checkpoint.restore_core_chain(self.args)

    def test_late_missing_input_fails_final_validation(self):
        original = checkpoint.restore
        def mutate(args, authority=None):
            result = original(args, authority)
            if args.stage == 'core-shard' and args.shard_index == 7: self.f.binary.unlink()
            return result
        with patch.object(checkpoint, 'restore', mutate):
            with self.assertRaises(ValueError): checkpoint.restore_core_chain(self.args)

    def test_late_input_mutation_and_extra_file_rejected_at_final_boundary(self):
        original = checkpoint.restore
        def mutate(args, authority=None):
            result = original(args, authority)
            if args.stage == 'core-shard' and args.shard_index == 7:
                self.f.binary.write_bytes(b'x'*self.f.binary.stat().st_size)
            return result
        with patch.object(checkpoint, 'restore', mutate):
            with self.assertRaisesRegex(ValueError, 'chain file digest changed'): checkpoint.restore_core_chain(self.args)

    def test_late_extra_closed_file_rejected(self):
        original = checkpoint.restore
        def mutate(args, authority=None):
            result = original(args, authority)
            if args.stage == 'core-shard' and args.shard_index == 7:
                self.f.write(self.f.root/'B/graphs/extra', 'unexpected')
            return result
        with patch.object(checkpoint, 'restore', mutate):
            with self.assertRaisesRegex(ValueError, 'closed directory membership'): checkpoint.restore_core_chain(self.args)

    def test_standalone_restore_does_not_reuse_chain_authority(self):
        checkpoint.restore_core_chain(self.args)
        self.f.binary.write_bytes(b'x'*self.f.binary.stat().st_size)
        with self.assertRaises(ValueError): checkpoint.restore(self.f.args())
