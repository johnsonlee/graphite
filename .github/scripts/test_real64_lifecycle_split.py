#!/usr/bin/env python3
"""Correctness-only tiny transport/join contract; no performance measurements."""
import copy
import io
import json
import os
from pathlib import Path
import shutil
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import real64_lifecycle_transport as transport
import finalize_real64_lifecycle as join


class LifecycleTransport(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.graph = self.root/'producer/graphs/g/graph.nodes'; self.graph.parent.mkdir(parents=True)
        self.graph.write_bytes(b'actual graph bytes')
        self.packet = self.root/'packet.json'
        self.packet.write_text(json.dumps({'arms': {'C': {'graphs': [{'path': str(self.graph.parent)}]}},
                                         'pins': {str(self.graph): transport.sha(self.graph)}}))
        self.selected = transport.roots(self.root, 'construction')
        for p in self.selected: p.mkdir(parents=True)
        (self.selected[1]/'packet.json').write_bytes(self.packet.read_bytes())
        for cell in ('01-C','02-A','03-B','04-B','05-A','06-C'):
            p = self.selected[0]/'cells'/cell/'graphs/g/graph.nodes'; p.parent.mkdir(parents=True)
            os.link(self.graph, p)
            (p.parent/'new').write_bytes(b'new common output')
        self.out = self.root/'transport'
        self.ident = transport.identity(self.root, 'construction', 'b'*40, 'a'*40, '123', '1')

    def seal(self):
        return transport.seal(self.root, 'construction', self.packet, self.out, self.ident)

    def clear(self):
        for p in self.selected: shutil.rmtree(p)

    def restore(self):
        return transport.restore(self.root, 'construction', self.packet, self.out, self.ident)

    def rewrite(self, value): transport.save(self.out/'manifest.json', value)

    def test_lossless_six_output_sharing_and_original_producer_reuse(self):
        value = self.seal(); self.clear(); self.restore()
        self.assertLess(value['uniquePayloadBytes'], value['logicalBytes'])
        first = self.selected[0]/'cells/01-C/graphs/g/new'
        for cell in ('01-C','02-A','03-B','04-B','05-A','06-C'):
            p = self.selected[0]/'cells'/cell/'graphs/g/graph.nodes'
            self.assertTrue(os.path.samefile(self.graph, p))
            self.assertTrue(os.path.samefile(first, p.parent/'new'))
        self.assertFalse(value['performanceAcceptance']); self.assertFalse(value['proofAuthority'])

    def test_hardlink_digest_reuse_is_boundary_local_and_directory_modes_survive(self):
        self.selected[0].chmod(0o750)
        real_sha = transport.sha
        with patch.object(transport, 'sha', wraps=real_sha) as digest:
            files, directories = transport.inventory(self.selected)
            # Six producer links share one inode; six new outputs do not.
            self.assertEqual(8, digest.call_count)
            transport.inventory(self.selected)
            self.assertEqual(16, digest.call_count)
        self.seal(); self.clear(); self.restore()
        self.assertEqual(0o750, self.selected[0].stat().st_mode & 0o777)

    def test_restore_hashes_shared_inodes_once_per_fresh_boundary(self):
        self.seal(); self.clear()
        with patch.object(transport, 'sha', wraps=transport.sha) as digest:
            self.restore()
            # Archive1 + original pool1 + two payloads2 + source boundary2 + final inventory3.
            self.assertEqual(9, digest.call_count)

    def test_mutation_after_linking_fails_independent_final_inventory(self):
        self.seal(); self.clear()
        real = transport.inventory
        def changed(roots):
            self.graph.write_bytes(b'different bytes after linking')
            return real(roots)
        with patch.object(transport, 'inventory', side_effect=changed):
            with self.assertRaisesRegex(ValueError, 'full byte inventory'): self.restore()

    def test_readonly_directory_with_empty_descendants_restores(self):
        parent = self.selected[0]/'readonly'; (parent/'empty').mkdir(parents=True)
        parent.chmod(0o555)
        self.seal()
        parent.chmod(0o755); self.clear(); self.restore()
        self.assertTrue((parent/'empty').is_dir()); self.assertEqual(0o555, parent.stat().st_mode & 0o777)
        parent.chmod(0o755)

    def test_unreadable_directory_cannot_silently_drop_output(self):
        directory = self.selected[0]/'blocked'; directory.mkdir()
        (directory/'hidden').write_bytes(b'must never disappear')
        real = transport.os.scandir
        def denied(path):
            if Path(path) == directory: raise PermissionError('unreadable output directory')
            return real(path)
        with patch.object(transport.os, 'scandir', side_effect=denied):
            with self.assertRaisesRegex(PermissionError, 'unreadable output'): self.seal()
        self.assertFalse((self.out/'manifest.json').exists())

    def test_wrong_revision_attempt_and_operation_reject(self):
        self.seal(); self.clear()
        for key in ('head','base','attempt','operation'):
            with self.subTest(key=key):
                with self.assertRaisesRegex(ValueError, 'identity'):
                    transport.restore(self.root, 'construction', self.packet, self.out, {**self.ident, key:'wrong'})

    def test_destination_escape_rejects_before_writing(self):
        value = self.seal(); self.clear(); value['directories'][str(self.root/'outside')] = 0o755
        self.rewrite(value)
        with self.assertRaisesRegex(ValueError, 'fixed roots'): self.restore()
        self.assertFalse((self.root/'outside').exists())
        self.assertFalse(self.selected[0].exists())

    def test_symlink_source_or_destination_rejected(self):
        self.seal(); self.clear()
        saved = self.graph.with_name('other'); self.graph.rename(saved); self.graph.symlink_to(saved)
        with self.assertRaisesRegex(ValueError, 'noncanonical'): self.restore()
        self.graph.unlink(); saved.rename(self.graph)
        self.selected[0].parent.mkdir(exist_ok=True)
        self.selected[0].symlink_to(self.root/'outside')
        with self.assertRaisesRegex(ValueError, 'fresh'): self.restore()

    def test_changed_producer_bytes_rejected(self):
        self.seal(); self.clear(); self.graph.write_bytes(b'changed graph bytes')
        with self.assertRaisesRegex(ValueError, 'producer graph identity drift'): self.restore()

    def test_unknown_or_changed_content_link_rejected(self):
        value = self.seal(); self.clear(); dest = next(iter(value['links']))
        value['links'][dest] = str(self.root/'outside'); self.rewrite(value)
        with self.assertRaisesRegex(ValueError, 'unknown or cyclic lifecycle content'): self.restore()

    def test_archive_traversal_and_symlink_members_reject(self):
        value = self.seal(); self.clear()
        for kind in ('traversal','symlink'):
            with tarfile.open(self.out/'payload.tar.gz','w:gz') as archive:
                info = tarfile.TarInfo('../escape' if kind == 'traversal' else 'files/00000000')
                if kind == 'symlink': info.type = tarfile.SYMTYPE; info.linkname = '/outside'
                archive.addfile(info, io.BytesIO(b''))
            value['archiveSha256'] = transport.sha(self.out/'payload.tar.gz'); self.rewrite(value)
            with self.assertRaisesRegex(ValueError, 'unsafe lifecycle archive'): self.restore()
            self.assertFalse((self.root/'escape').exists())

    def test_unsupported_modes_rejected_at_seal(self):
        for target in (self.selected[0], self.selected[1]/'packet.json'):
            old = target.stat().st_mode & 0o777
            target.chmod(old | 0o1000)
            try:
                with self.assertRaisesRegex(ValueError, 'safe lifecycle'): self.seal()
            finally: target.chmod(old)

    def test_drift_during_seal_rejected(self):
        real = transport.inventory; count = 0
        def drift(roots):
            nonlocal count
            count += 1
            if count == 2: (self.selected[1]/'packet.json').write_bytes(b'changed')
            return real(roots)
        with patch.object(transport, 'inventory', side_effect=drift):
            with self.assertRaisesRegex(ValueError, 'measurement drift'): self.seal()
        self.assertFalse((self.out/'manifest.json').exists())

    def test_cycle_in_content_links_rejected(self):
        value = self.seal(); self.clear()
        identical = [p for p in value['links'] if p.endswith('/graph.nodes')][:2]
        value['links'][identical[0]] = identical[1]; value['links'][identical[1]] = identical[0]
        self.rewrite(value)
        with self.assertRaisesRegex(ValueError, 'cyclic'): self.restore()


class LifecycleJoin(unittest.TestCase):
    def setUp(self):
        self.pending = {'arms': {'C':{},'A':{},'B':{}}, 'sourceInputs': {'path':'/inputs','sha256':'i'},
                        'pins': {'/writer':'w'}, 'artifactsRoot':'/actual', 'baseRevision':'a', 'candidateRevision':'b'}
        self.corrected = copy.deepcopy(self.pending)
        self.plan = {'engine':'jvm','operation':'construction','comparabilityPending':True,
                     'producers':{'path':'/pending','sha256':'p'}, 'pendingComparabilityAuthority':{'path':'/pending','sha256':'p'},
                     'arms':{'A':{'revision':'a'},'B':{'revision':'b'}},'sourceInputs':self.pending['sourceInputs']}

    def run_join(self, corrected=None, current=None):
        with patch.object(join.common,'pinned_authority_metadata',return_value=self.pending), \
             patch.object(join.common,'read',return_value={}), \
             patch.object(join.bundle,'verify_bundle',return_value=corrected or self.corrected), \
             patch.object(join.bundle,'corrected_fixture_bindings'), \
             patch.object(join.bundle,'assemble',return_value=current or self.pending):
            return join.join_inputs(self.plan, '/corrected')

    def test_original_inputs_join_without_rewriting_measurement(self):
        before = copy.deepcopy(self.plan); self.run_join(); self.assertEqual(before, self.plan)

    def test_changed_core_source_arm_input_or_pin_rejected(self):
        for change in ('arms','sourceInputs','pins'):
            value = copy.deepcopy(self.corrected); value[change] = {}
            with self.subTest(change=change), self.assertRaisesRegex(ValueError,'exactly the original'):
                self.run_join(corrected=value)
        value = copy.deepcopy(self.pending); value['extra'] = 'drift'
        with self.assertRaisesRegex(ValueError,'exactly the original'): self.run_join(current=value)

    def test_missing_core_fails_visible_without_authorizing_status(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); out = root/'measurement'; out.mkdir()
            (out/'plan.json').write_text(json.dumps(self.plan))
            with patch.object(join.construction,'validate',side_effect=ValueError('missing complete core')):
                result = join.finalize(out,root/'final',root/'absent','construction')
            self.assertFalse(result['performanceAcceptance'])
            self.assertEqual('FAIL', result['status'])
            status = json.loads((root/'final-status.json').read_text())
            self.assertFalse(status['passed']); self.assertIn('missing complete core',status['errors'][0])

    def test_workflow_measurements_precede_core_but_final_acceptance_joins_both(self):
        workflow = (Path(__file__).parents[1]/'workflows/benchmark.yml').read_text()
        for operation in ('construction','loading'):
            measured = workflow.split('\n  rust-'+operation+'-measurement:\n',1)[1].split('\n  rust-',1)[0]
            final = workflow.split('\n  rust-'+operation+':\n',1)[1].split('\n  rust-',1)[0]
            self.assertIn('needs: [rust-producer]',measured); self.assertNotIn('Download core', measured)
            self.assertIn('native_pressure_checkpoint.py restore',measured); self.assertIn('--stage producer --arm producer',measured)
            self.assertIn('run_real64_'+operation+'.py --measurement-only',measured)
            self.assertIn('real64_lifecycle_transport.py seal',measured)
            self.assertIn(f'needs: [rust-producer, rust-core, rust-{operation}-measurement]',final)
            self.assertIn('restore-consumer-chain',final); self.assertIn('--stage core --arm B',final)
            self.assertIn('real64_lifecycle_transport.py restore',final)
            self.assertIn('finalize_real64_lifecycle.py --operation '+operation,final)
            self.assertIn("jq -e '.passed == true'",final)
            self.assertNotIn('run_real64_'+operation+'.py',final)
        final = workflow.split('\n  rust-construction:\n',1)[1].split('\n  rust-',1)[0]
        self.assertIn('!benchmark-results/real64-construction/cells/**/graphs/**', final)
        gate = workflow.split('\n  rust-latency:\n',1)[1].split('\n  slow-query',1)[0]
        self.assertIn("'rust-loading-measurement'",gate); self.assertIn("'rust-construction-measurement'",gate)


if __name__ == '__main__': unittest.main()
