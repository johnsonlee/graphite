"""Tiny files and fake phases test the construction contract, never performance."""
import copy
import json
from pathlib import Path
import tempfile
import signal
import unittest
from unittest.mock import patch

import run_real64_construction as runner
import audit_real64_construction as auditor
import test_produce_native_pressure_artifacts as fixtures

p = runner.common


def write(path, value):
    Path(path).write_text(json.dumps(value))


class ConstructionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.calls = []
        self.no_child = patch('subprocess.Popen', side_effect=AssertionError('no real child allowed'))
        self.no_child.start(); self.addCleanup(self.no_child.stop)
        self.jars = []
        for name in runner.producer.CORPORA:
            f = self.root/(name+'.jar'); f.write_bytes(name.encode())
            self.jars.append({'corpus': name, 'path': str(f), 'sha256': p.sha(f)})
        java = self.root/'java'; java.write_bytes(b'fake java')
        source = self.root/'source.kt'; source.write_bytes(b'test source')
        self.source = {str(source): p.sha(source)}
        reference = self.root/'reference'; reference.mkdir()
        self.write_graphs(reference)
        self.inputs = self.root/'inputs.json'
        write(self.inputs, {'schema': 'graphite.fixture64-source-inputs.v1', 'jars': self.jars,
                            'referenceProvenance': runner.artifacts.ref(reference/'fixture-provenance.tsv')})
        self.packet = {'status': runner.bundle.CORRECTED_STATUS, 'missingAuthority': [],
                       'sourceInputs': runner.artifacts.ref(self.inputs), 'arms': {}, 'pins': {}}
        pins = self.packet['pins']
        pins.update(runner.producer.inventory(reference))
        pins.update({str(java): p.sha(java), '/usr/bin/time': p.sha('/usr/bin/time')})
        for label, revision, role in [('C',runner.artifacts.ACCEPTED,'accepted-baseline'),('A','a'*40,'parent'),('B','b'*40,'candidate')]:
            root = self.root/label; (root/'runtime').mkdir(parents=True)
            files = {}
            for name in ('writer.jar','graphite.jar','graphite'):
                f = root/'runtime'/name; f.write_bytes((name+label).encode()); files[str(f)] = p.sha(f)
            sm = root/'source.json'; write(sm, {'root':str(self.root),'revision':revision,'files':self.source})
            runtime = {'revision':revision,'sourceFiles':self.source,'sourceManifestSha256':p.sha(sm),
                       'files':files,'originalArtifacts':files,'toolchainIdentity':{'java':str(java)}}
            rm = root/'runtime-manifest.json'; write(rm,runtime)
            art = root/'artifact.json'; write(art, {'sourceBefore':self.source,'sourceAfter':self.source,
                                                    'constructionResources':{'heapMaxBytes':4*1024**3}})
            self.packet['arms'][label] = {'revision':revision,'role':role,'sourceManifest':runner.artifacts.ref(sm),
                                         'runtimeManifest':runner.artifacts.ref(rm),'artifactAudit':runner.artifacts.ref(art),
                                         'graphs':[{'path':str(d)} for d in reference.iterdir() if d.is_dir()]}
            pins.update(files); pins.update(self.source)
            pins.update({str(f):p.sha(f) for f in (sm,rm,art)})
        self.packet_file = self.root/'packet.json'; write(self.packet_file,self.packet)
        self.plan = self.prepare()
        self.plan_file = self.root/'plan.json'; write(self.plan_file,self.plan)

    def prepare(self):
        with patch.object(runner.bundle,'verify_bundle',side_effect=lambda doc,*args:doc), \
                patch.object(runner.bundle,'corrected_fixture_bindings',return_value={}), \
                patch.object(runner,'preparation_control_pins',return_value={}), \
                patch.object(runner,'native_build_identity',side_effect=lambda r:r['toolchainIdentity']):
            return runner.prepare(self.packet_file,'a'*40,'b'*40)

    def write_graphs(self, root):
        rows, manifest = [], []
        for jar in self.jars:
            for i in range(16):
                gid = f'fixture-{jar["corpus"]}-{i:02}'
                d = root/gid; d.mkdir(parents=True)
                index = d/'graph.callsite-string-index'; index.write_bytes(gid.encode())
                h = p.digest_bytes(gid.encode())
                row = [gid,jar['corpus'],str(i),Path(jar['path']).name,jar['sha256'],h,'2','10','3',
                       'miss','target','dense',h,'1',h,h,str(index.stat().st_size),p.sha(index),str(d)]
                rows.append('\t'.join(row)); manifest.append('\t'.join([gid,str(d),'10','3','target',h]))
        (root/'fixture-provenance.tsv').write_text(runner.artifacts.PROVENANCE_HEADER+'\n'+'\n'.join(rows)+'\n')
        (root/'graphs.tsv').write_text('\n'.join(manifest)+'\n')

    def phase(self,name,argv,cwd,env,out,timeout,construction_time=None):
        self.calls.append((name,argv,env,construction_time))
        directory = out/name; directory.mkdir()
        if name == 'prepare-real64':
            self.write_graphs(out/'graphs')
        (directory/'stdout.log').write_text('valid saved64\n')
        (directory/'stderr.log').write_text('')
        group = 10000+len(self.calls)
        record = {'name':name,'argv':argv,'cwd':str(cwd),'timeoutSeconds':timeout,'status':'PASS','errors':[],
                  'exit':0,'cleanup':{'group':group,'after':[],'errors':[],'exit':0}}
        actual = argv
        if construction_time:
            clock = directory/'time-v.log'; clock.write_text(fixtures.ConstructionResourceTests.RAW)
            actual = [construction_time,'-v','-o',str(clock),'--',*argv]
            record.update(launchArgv=actual,environmentOverrides={'LC_ALL':'C'},
                          constructionResources=runner.producer.construction_resources(clock,0))
        record['logs'] = {str(f):p.sha(f) for f in directory.glob('*.log')}
        write(directory/'owner.json',{'runnerPid':9000,'group':group,'argv':actual})
        write(directory/'record.json',record)
        return record

    def execute(self, phase=None):
        with patch.object(runner,'validate',side_effect=lambda x:x), \
                patch.object(runner.sys,'platform','linux'), \
                patch.object(runner.artifacts,'source_inventory',side_effect=lambda *args:{f:p.sha(f) for f in self.source}), \
                patch.object(runner.producer,'phase',side_effect=phase or self.phase):
            return runner.run(self.plan_file,self.root/'cells')

    def audit(self):
        with patch.object(runner,'validate',side_effect=lambda x:x):
            return auditor.audit(self.plan_file,self.root/'cells')

    def test_six_fresh64_and_second_readbacks_are_audited_with_raw_units(self):
        result = self.execute(); audit = self.audit()
        self.assertEqual('PASS_ALL_SIX_USABLE_SAVED64',result['status'])
        self.assertEqual([],result['unissued']); self.assertEqual(12,len(self.calls))
        self.assertEqual(list('CABBAC'),[r['cell']['arm'] for r in result['cells']])
        self.assertEqual(['prepare-real64','verify-real64']*6,[c[0] for c in self.calls])
        self.assertEqual(6,len({c[1][-1] for c in self.calls[::2]}))
        self.assertTrue(all(c[3]=='/usr/bin/time' for c in self.calls[::2]))
        self.assertTrue(all(c[3] is None for c in self.calls[1::2]))
        self.assertEqual('PASS_RAW_USABLE_SAVE_AUDIT',audit['status'])
        self.assertEqual((63.75,63.5,2097152),tuple(audit['rows'][0]['resources'][k]
                         for k in ('realSeconds','totalCpuSeconds','peakRssBytes')))
        self.assertTrue(all(audit[k] is False for k in runner.FALSE))

    def test_failed_second_verify_stops_preserves_cell_and_unissued(self):
        def fail(name,*args,**kwargs):
            if name=='verify-real64': raise ValueError('actual readback failure')
            return self.phase(name,*args,**kwargs)
        result=self.execute(fail)
        self.assertEqual('FAIL',result['status']);self.assertEqual(5,len(result['unissued']))
        self.assertIn('actual readback failure',result['cells'][0]['errors'][0])
        self.assertEqual(result['cells'][0],p.read(self.root/'cells/01-C/cell.json'))
        with self.assertRaisesRegex(ValueError,'complete six'):self.audit()

    def test_changed_writer_and_partial_authority_reject_before_measurement(self):
        self.packet['status']=runner.bundle.STATUS;write(self.packet_file,self.packet)
        with self.assertRaisesRegex(ValueError,'corrected writer comparability'):self.prepare()
        self.packet['status']=runner.bundle.CORRECTED_STATUS
        arm=self.packet['arms']['B'];runtime=p.read(arm['runtimeManifest']['path'])
        runtime['sourceManifestSha256']='0'*64;write(arm['runtimeManifest']['path'],runtime)
        arm['runtimeManifest']=runner.artifacts.ref(arm['runtimeManifest']['path'])
        self.packet['pins'][arm['runtimeManifest']['path']]=arm['runtimeManifest']['sha256']
        write(self.packet_file,self.packet)
        with self.assertRaisesRegex(ValueError,'same actual writer source'):self.prepare()
        self.assertEqual([],self.calls)

    def test_false_resource_summary_and_incomplete_group_cleanup_reject(self):
        self.execute()
        file=self.root/'cells/01-C/prepare-real64/time-v.log'
        original=file.read_text();file.write_text(original.replace('61.20','62.20'))
        with self.assertRaisesRegex(ValueError,'raw resources differ'):self.audit()
        file.write_text(original)
        record=self.root/'cells/01-C/verify-real64/record.json';value=p.read(record)
        value['cleanup']['after']=[123];write(record,value)
        with self.assertRaisesRegex(ValueError,'owned terminal cleanup'):self.audit()

    def test_added_output_file_or_changed_index_cannot_be_hidden(self):
        self.execute()
        extra=self.root/'cells/01-C/graphs/extra';extra.write_bytes(b'extra')
        with self.assertRaisesRegex(ValueError,'closed complete fixture'):self.audit()
        extra.unlink()
        index=self.root/'cells/01-C/graphs/fixture-android-00/graph.callsite-string-index'
        index.write_bytes(b'bad index')
        with self.assertRaisesRegex(ValueError,'closed complete fixture'):self.audit()

    def test_plan_and_source_drift_are_retained_failures(self):
        first=True
        def drift(*args,**kwargs):
            nonlocal first
            value=self.phase(*args,**kwargs)
            if first:
                Path(next(iter(self.source))).write_bytes(b'changed source');first=False
            return value
        result=self.execute(drift)
        self.assertEqual('FAIL',result['status'])
        self.assertIn('final identity',result['errors'][-1])
        self.assertEqual(5,len(result['unissued']))

    def test_interrupt_handlers_fail_current_cell_keep_unissued_and_restore(self):
        original = {sig:signal.getsignal(sig) for sig in (signal.SIGINT,signal.SIGTERM)}
        def interrupt(*args,**kwargs):
            for sig in original:
                self.assertTrue(callable(signal.getsignal(sig)))
                self.assertIsNot(original[sig],signal.getsignal(sig))
            signal.getsignal(signal.SIGTERM)(signal.SIGTERM,None)
        result=self.execute(interrupt)
        self.assertEqual('FAIL',result['status'])
        self.assertIn('InterruptedError',result['cells'][0]['errors'][0])
        self.assertEqual(['02-A','03-B','04-B','05-A','06-C'],result['unissued'])
        self.assertEqual(original,{sig:signal.getsignal(sig) for sig in original})

    def test_retention_preserves_paths_and_bytes_after_second_readback(self):
        result = self.execute()
        self.assertEqual('PASS_ALL_SIX_USABLE_SAVED64', result['status'])
        self.assertEqual('PASS_RAW_USABLE_SAVE_AUDIT', self.audit()['status'])
        links = result['cells'][0]['retention']['links']
        self.assertEqual(64, len(links))
        for link in links:
            self.assertTrue(Path(link['path']).samefile(link['source']))
            self.assertEqual(link['sha256'], p.sha(link['path']))
        self.assertFalse(list((self.root/'cells').rglob('*.retention-link')))

    def test_retention_source_mutation_fails_without_replacing_output(self):
        source, dest = self.root/'old', self.root/'new'
        source.write_bytes(b'unchanged'); dest.write_bytes(b'unchanged')
        digest = p.sha(source); pool = runner.retention_pool({str(source):digest})
        source.write_bytes(b'corrupted')
        with self.assertRaisesRegex(ValueError, 'retention bytes changed'):
            runner.retain_outputs({str(dest):digest}, pool)
        self.assertEqual(b'unchanged', dest.read_bytes())
        self.assertFalse(source.samefile(dest))

    def test_retention_replace_failure_cleans_only_its_temporary_link(self):
        source, dest = self.root/'old', self.root/'new'
        source.write_bytes(b'same'); dest.write_bytes(b'same')
        digest = p.sha(source); pool = runner.retention_pool({str(source):digest})
        with patch.object(runner.os, 'replace', side_effect=OSError('replace failed')):
            with self.assertRaisesRegex(OSError, 'replace failed'):
                runner.retain_outputs({str(dest):digest}, pool)
        self.assertEqual(b'same', dest.read_bytes())
        self.assertFalse(source.samefile(dest))
        self.assertFalse(list(self.root.glob('*.retention-link')))

    def test_retention_signal_after_link_keeps_valid_output_and_cleans_temporary(self):
        source, dest = self.root/'old', self.root/'new'
        source.write_bytes(b'same'); dest.write_bytes(b'same')
        digest = p.sha(source); pool = runner.retention_pool({str(source):digest})
        link = runner.os.link
        original = {sig:signal.getsignal(sig) for sig in (signal.SIGINT,signal.SIGTERM)}
        def interrupted_link(*args, **kwargs):
            link(*args, **kwargs)
            signal.raise_signal(signal.SIGTERM)
        with patch.object(runner.os, 'link', side_effect=interrupted_link):
            with self.assertRaises(InterruptedError):
                runner.retain_outputs({str(dest):digest}, pool)
        self.assertEqual(b'same', dest.read_bytes())
        self.assertFalse(list(self.root.glob('*.retention-link')))
        self.assertEqual(original,{sig:signal.getsignal(sig) for sig in original})


if __name__ == '__main__':unittest.main()
