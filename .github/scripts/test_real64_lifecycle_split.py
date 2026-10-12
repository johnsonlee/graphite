#!/usr/bin/env python3
"""Correctness-only tiny transport/join contract; no performance measurements."""
import copy
import io
import importlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import real64_lifecycle_transport as transport
import finalize_real64_lifecycle as join


class LifecycleTransport(unittest.TestCase):
    operation = 'construction'
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.graph = self.root/'producer/graphs/g/graph.nodes'; self.graph.parent.mkdir(parents=True)
        self.graph.write_bytes(b'actual graph bytes')
        self.packet = self.root/'packet.json'
        self.packet.write_text(json.dumps({'arms': {'C': {'graphs': [{'path': str(self.graph.parent)}]}},
                                         'pins': {str(self.graph): transport.sha(self.graph)}}))
        self.selected = transport.roots(self.root, self.operation)
        for p in self.selected: p.mkdir(parents=True)
        (self.selected[1]/'packet.json').write_bytes(self.packet.read_bytes())
        for cell in ('01-C','02-A','03-B','04-B','05-A','06-C'):
            p = self.selected[0]/'cells'/cell/'graphs/g/graph.nodes'; p.parent.mkdir(parents=True)
            os.link(self.graph, p)
            (p.parent/'new').write_bytes(b'new common output')
        self.out = self.root/'transport'
        self.ident = transport.identity(self.root, self.operation, 'b'*40, 'a'*40, '123', '1')

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
            self.assertNotIn('assemble_native_pressure_producers.py', final)
            self.assertNotIn('real64_lifecycle_transport.py restore', final)
            for flag in ('--artifacts native-pressure-artifacts', '--transport-directory lifecycle-transport-'+operation,
                         '--workspace', '--base-sha', '--candidate-sha', '--run-id', '--run-attempt'):
                self.assertIn(flag, final)
            self.assertIn('finalize_real64_lifecycle.py --operation '+operation,final)
            self.assertIn("jq -e '.passed == true'",final)
            self.assertNotIn('run_real64_'+operation+'.py',final)
        final = workflow.split('\n  rust-construction:\n',1)[1].split('\n  rust-',1)[0]
        self.assertIn('!benchmark-results/real64-construction/cells/**/graphs/**', final)
        gate = workflow.split('\n  rust-latency:\n',1)[1].split('\n  slow-query',1)[0]
        self.assertIn("'rust-loading-measurement'",gate); self.assertIn("'rust-construction-measurement'",gate)


class OwnedConstructionJoin(unittest.TestCase):
    """Actual sealed six-output restore through final receipt; semantic core is stubbed."""
    operation = 'construction'

    def setUp(self):
        LifecycleTransport.setUp(self)
        self.artifacts = self.root/'producer'
        self.metadata = self.root/'inputs.json'; transport.save(self.metadata, {'inputs':'same'})
        self.producer_audit = self.root/'producer-audit.json'
        transport.save(self.producer_audit, {'pins': {str(self.graph):transport.sha(self.graph)}})
        pins = {str(self.graph):transport.sha(self.graph), str(self.metadata):transport.sha(self.metadata),
                str(self.producer_audit):transport.sha(self.producer_audit)}
        arm = {'revision':'b'*40,'graphs':[{'path':str(self.graph.parent)}],
               'artifactAudit':join.artifacts.ref(self.producer_audit)}
        self.pending = {'arms':{k:dict(arm,revision=('b' if k=='B' else 'a')*40) for k in 'CAB'},
                        'sourceInputs':join.artifacts.ref(self.metadata), 'pins':pins,
                        'artifactsRoot':str(self.artifacts),'baseRevision':'a'*40,'candidateRevision':'b'*40}
        pending_path = self.selected[1]/'packet.json'; pending_path.write_text(json.dumps(self.pending))
        self.corrected = copy.deepcopy(self.pending); self.corrected['correctedComparisons'] = {}
        self.plan = {'engine':'jvm','operation':self.operation,'comparabilityPending':True,
                     'producers':join.artifacts.ref(pending_path),'pendingComparabilityAuthority':join.artifacts.ref(pending_path),
                     'arms':self.pending['arms'],'sourceInputs':self.pending['sourceInputs'],
                     'pins':{**pins,str(pending_path):transport.sha(pending_path)}}
        transport.save(self.selected[0]/'plan.json',self.plan)
        transport.save(self.selected[0]/'cells/execution.json',{'sixOriginalCells':True})
        self.cell_payload = 'graphs/g/graph.nodes'
        if self.operation == 'loading':
            # Loading retains only raw process/readiness evidence, not six written graph copies.
            self.cell_payload = 'loading.json'
            for cell in ('01-C','02-A','03-B','04-B','05-A','06-C'):
                directory = self.selected[0]/'cells'/cell
                shutil.rmtree(directory/'graphs')
                transport.save(directory/self.cell_payload, {'cell':cell,'queriesIssuedDuringLoading':0,
                               'readyNs':200,'startNs':100,'readyVmHWM':4096})
        self.expected_audit = {'sixOriginalCellDigests':{cell:transport.sha(self.selected[0]/'cells'/cell/self.cell_payload)
            for cell in ('01-C','02-A','03-B','04-B','05-A','06-C')}}
        transport.save(self.selected[0]/'audit.json',self.expected_audit)
        self.originals = {str(p):transport.sha(p) for p in self.selected[0].rglob('*') if p.is_file()}
        transport.seal(self.root,self.operation,self.packet,self.out,self.ident)
        LifecycleTransport.clear(self)

    def invoke(self, *, mutate=None, run_id='123', core_error=None):
        audit_module = importlib.import_module('audit_real64_'+self.operation)
        runner = importlib.import_module('run_real64_'+self.operation)
        self.audited_cells = []; self.closure_checks = 0
        def audit(plan_path,cells):
            for cell,digest in self.expected_audit['sixOriginalCellDigests'].items():
                self.assertEqual(digest,transport.sha(cells/cell/self.cell_payload))
                self.audited_cells.append(cell)
            if mutate: mutate()
            return self.expected_audit
        def inventory(report): return sorted(str(p) for p in self.graph.parent.rglob('*'))
        def closure(report,before):
            self.closure_checks += 1
            join.artifacts.verify_pins(report['pins'])
            join.require(inventory(report)==before,'closed producer membership drift')
        def compare(command,**kwargs):
            receipt = json.loads(Path(command[-1]).read_text())
            self.assertEqual(self.plan['producers'],receipt['measurementProducer'])
            self.assertEqual(self.corrected,json.loads(Path(receipt['correctedProducer']['path']).read_text()))
            for ref in receipt['metadata']:
                self.assertEqual(ref['sha256'],transport.sha(Path(command[4]).parent/ref['relativePath']))
            transport.save(str(self.root/'final')+'-status.json',{
                'schema':('graphite.real64-construction.comparison.v1' if self.operation == 'construction' else
                          'graphite.jvm64-loading.comparison.v1'),'engine':'jvm','operation':self.operation,
                'planSha256':transport.sha(self.selected[0]/'plan.json'),'passed':True,'status':'PASS',
                'coreComparabilityVerified':True,'comparabilityPending':False})
            return subprocess.CompletedProcess(command,0,'','')
        with patch.object(join.bundle,'assemble_finalization_pair',return_value=(self.corrected,self.pending),
                          side_effect=core_error) as assemble, \
             patch.object(join.bundle,'corrected_fixture_bindings'), \
             patch.object(join.artifacts._ProducerReplayContext,'_inventories',side_effect=inventory), \
             patch.object(join.artifacts._ProducerReplayContext,'_check',side_effect=closure), \
             patch.object(runner,'validate',side_effect=lambda plan:plan), \
             patch.object(audit_module,'audit',side_effect=audit), \
             patch.object(join.subprocess,'run',side_effect=compare), \
             patch.object(join.shutil,'which',return_value='/node'):
            result = join.finalize(self.selected[0],self.root/'final',None,self.operation,
                artifacts_root=self.artifacts,workspace=self.root,transport_directory=self.out,
                base_sha='a'*40,candidate_sha='b'*40,run_id=run_id,run_attempt='1')
        self.assertEqual(1,assemble.call_count)
        return result

    def test_actual_sealed_six_outputs_join_once_and_keep_original_metadata(self):
        result = self.invoke()
        self.assertEqual('PASS',result['status']); self.assertTrue(result['performanceAcceptance'])
        self.assertEqual(list(self.expected_audit['sixOriginalCellDigests']),self.audited_cells)
        self.assertEqual(2,self.closure_checks)
        for path,digest in self.originals.items(): self.assertEqual(digest,transport.sha(path))
        if self.operation == 'construction':
            for cell in self.audited_cells:
                self.assertTrue(os.path.samefile(self.graph,self.selected[0]/'cells'/cell/self.cell_payload))
        else:
            self.assertFalse(any((self.selected[0]/'cells').glob('*/graphs')))
        receipt = json.loads((self.root/'final-comparability.json').read_text())
        self.assertEqual(self.operation,receipt['operation'])
        self.assertEqual('jvm',receipt['engine'])
        self.assertEqual(self.plan['producers'],receipt['measurementProducer'])

    def test_same_size_producer_drift_during_raw_audit_fails(self):
        import os
        old = self.graph.stat()
        def drift():
            self.graph.write_bytes(b'x'*old.st_size);os.utime(self.graph,ns=(old.st_atime_ns,old.st_mtime_ns))
        result = self.invoke(mutate=drift)
        self.assertEqual('FAIL',result['status']);self.assertFalse(result['performanceAcceptance'])

    def test_new_closed_member_during_raw_audit_fails(self):
        result = self.invoke(mutate=lambda:(self.graph.parent/'added').mkdir())
        self.assertEqual('FAIL',result['status']);self.assertIn('membership drift',result['errors'][0])

    def test_wrong_transport_run_and_missing_core_never_restore_or_pass(self):
        result = self.invoke(run_id='124')
        self.assertEqual('FAIL',result['status']);self.assertFalse(self.selected[0].exists())
        self.assertIn('transport identity',result['errors'][0])

    def test_missing_core_fails_before_restore(self):
        result = self.invoke(core_error=ValueError('missing complete corrected core'))
        self.assertEqual('FAIL',result['status']);self.assertFalse(self.selected[0].exists())

    def test_fresh_output_symlink_rejected_without_assembling(self):
        target=self.root/'final-corrected-producer.json';target.symlink_to(self.metadata)
        with patch.object(join.bundle,'assemble_finalization_pair') as assemble:
            with self.assertRaisesRegex(ValueError,'fresh corrected'):
                join.finalize(self.selected[0],self.root/'final',None,self.operation,
                    artifacts_root=self.artifacts,workspace=self.root,transport_directory=self.out,
                    base_sha='a'*40,candidate_sha='b'*40,run_id='123',run_attempt='1')
            assemble.assert_not_called()

    def test_cli_cannot_supply_both_authority_modes(self):
        result = subprocess.run([sys.executable,'-B',join.__file__,'--operation',self.operation,
            '--output',str(self.selected[0]),'--prefix',str(self.root/'final'),
            '--producers',str(self.packet),'--artifacts',str(self.artifacts)],capture_output=True,text=True,timeout=10)
        self.assertEqual(2,result.returncode);self.assertIn('not allowed with argument',result.stderr)


class OwnedLoadingJoin(OwnedConstructionJoin):
    operation = 'loading'

    def test_wrong_operation_transport_cannot_restore_or_authorize(self):
        manifest = json.loads((self.out/'manifest.json').read_text())
        manifest['identity']['operation'] = 'construction'
        transport.save(self.out/'manifest.json',manifest)
        result = self.invoke()
        self.assertEqual('FAIL',result['status']);self.assertFalse(result['performanceAcceptance'])
        self.assertIn('transport identity',result['errors'][0])
        self.assertFalse(self.selected[0].exists());self.assertEqual([],self.audited_cells)

    def test_corrupt_original_loading_archive_cannot_authorize(self):
        archive = self.out/'payload.tar.gz';archive.write_bytes(archive.read_bytes()+b'changed')
        result = self.invoke()
        self.assertEqual('FAIL',result['status']);self.assertFalse(result['performanceAcceptance'])
        self.assertIn('archive digest',result['errors'][0]);self.assertEqual([],self.audited_cells)

    def test_original_plan_with_wrong_operation_cannot_authorize(self):
        transport.restore(self.root,self.operation,self.packet,self.out,self.ident)
        plan = json.loads((self.selected[0]/'plan.json').read_text());plan['operation']='construction'
        transport.save(self.selected[0]/'plan.json',plan)
        shutil.rmtree(self.out)
        transport.seal(self.root,self.operation,self.packet,self.out,self.ident)
        LifecycleTransport.clear(self)
        result = self.invoke()
        self.assertEqual('FAIL',result['status']);self.assertFalse(result['performanceAcceptance'])
        self.assertIn('operation-specific finalization',result['errors'][0]);self.assertEqual([],self.audited_cells)


if __name__ == '__main__': unittest.main()
