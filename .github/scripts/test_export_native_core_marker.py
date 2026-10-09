"""Tiny synthetic protocol bytes; no JVM, subprocess, graph corpus or timing."""
import json
from pathlib import Path
import shutil
import unittest
from unittest.mock import patch

import export_native_core_marker as m
import test_audit_native_pressure_artifacts as fixtures
from native_core_proof.marker_authority_test import marker


class MarkerExportTests(unittest.TestCase):
    def setUp(self):
        f=self.fixture=fixtures.ArtifactAuditTests();f.revision=m.producer.ACCEPTED;f.role='accepted-baseline'
        f.setUp();self.addCleanup(f.doCleanups)
        self.sources={str(f.checkout):f.source}
        p=patch.object(m.artifacts,'source_inventory',side_effect=lambda root,revision:self.sources[str(root)])
        p.start();self.addCleanup(p.stop)
        p=patch('subprocess.Popen',side_effect=AssertionError('no subprocess allowed'));p.start();self.addCleanup(p.stop)
        self.reference=f.out/'artifact-audit.json';f.write(self.reference,f.run_audit())
        self.actual=self.reference;self.output=f.root/'marker';self.calls=[];self.fail=None;self.bad=False

    def make_actual(self,role):
        f=self.fixture;out=f.root/'actualout';checkout=f.root/'actualcheckout'
        shutil.copytree(f.out,out);shutil.copytree(f.checkout,checkout)
        def changed(text):return text.replace(str(f.out),str(out)).replace(str(f.checkout),str(checkout)).replace(f.revision,'b'*40)
        for path in out.rglob('*'):
            if path.is_file() and path.suffix in ('.json','.tsv'):
                path.write_text(changed(path.read_text()))
        source=m.common.read(out/'source-manifest.json');self.sources[str(checkout)]=source['files']
        runtime=m.common.read(out/'runtime-manifest.json');fixture=m.common.read(out/'fixture-manifest.json')
        packet=m.common.read(out/'packet.json');packet['role']=role
        runtime['sourceManifestSha256']=fixture['sourceManifestSha256']=m.common.sha(out/'source-manifest.json')
        fixture['files']=m.artifacts.inventory(out/'graphs');f.write(out/'fixture-manifest.json',fixture)
        f.write(out/'runtime-manifest.json',runtime);packet['runtimeManifest']=runtime
        packet['phases']=[m.common.read(out/phase['name']/'record.json') for phase in packet['phases']]
        packet['phaseReceipts']={str(out/phase['name']/'record.json'):m.common.sha(out/phase['name']/'record.json') for phase in packet['phases']}
        f.write(out/'packet.json',packet)
        self.actual=out/'artifact-audit.json'
        f.write(self.actual,m.artifacts.audit(out/'packet.json',checkout,'b'*40,role,f.inputs_path,f.tools))

    def plan(self):return m.bind(self.reference,self.actual,self.fixture.inputs_path,self.output)

    def phase(self,name,argv,cwd,env,out,timeout):
        self.calls.append((name,argv,env));p=out/name;p.mkdir()
        (p/'stdout.log').write_text('marker protocol');(p/'stderr.log').write_text('')
        self.fixture.write(p/'owner.json',{'group':12345,'runnerPid':1234,'argv':argv})
        record={'name':name,'argv':argv,'cwd':str(cwd),'timeoutSeconds':timeout,'errors':[],
                'status':'PASS','exit':0,'cleanup':{'group':12345,'after':[],'errors':[],'exit':0},
                'logs':{str(p/n):m.common.sha(p/n) for n in ('stdout.log','stderr.log')}}
        if name==self.fail:record.update(status='FAIL',errors=['intentional failure'],exit=17)
        elif name=='compile-bootstrap-marker':(out/'classes/ExportSerializable.class').write_bytes(b'compiled helper')
        else:
            path=out/'Serializable.class';path.write_bytes(marker('java/lang/Runnable') if self.bad else marker())
            self.fixture.write(out/'receipt.json',{'status':'PASS_EXACT_BOOTSTRAP_SERIALIZABLE_EXPORT',
              'className':'java.io.Serializable','module':'java.base','resource':'java/io/Serializable.class',
              'javaHome':argv[-4],'modulesPath':str(Path(argv[-4])/'lib/modules'),'modulesSha256':argv[-3],
              'rawClassFile':str(path),'classBytesSha256':m.common.sha(path),'bootstrapClassLoader':True,
              'performanceClaim':False})
        self.fixture.write(p/'record.json',record)
        if record['status']!='PASS':raise ValueError('intentional phase failure')
        return record

    def execute(self,plan=None):
        with patch.object(m.producer,'phase',side_effect=self.phase):return m.execute(plan or self.plan())

    def check_success(self):
        self.assertEqual(m.PASS,self.execute()['status']);audit=m.audit(self.output)
        self.assertEqual(m.AUDIT_PASS,audit['status']);self.assertEqual(2,len(self.calls))
        self.assertEqual(m.producer.ACCEPTED,audit['arms']['C']['revision'])
        self.assertEqual(self.actual,m.Path(audit['arms']['B']['artifactAudit']['path']))
        self.assertEqual(marker(),Path(audit['rawClass']['path']).read_bytes())
        for name,argv,env in self.calls:
            self.assertIn('-J-Xmx4g' if name.startswith('compile') else '-Xmx4g',argv)
            self.assertEqual('-Xmx4g -XX:ActiveProcessorCount=4',env['JAVA_TOOL_OPTIONS'])
        self.assertFalse(audit['completeSemanticEquivalence']);self.assertFalse(audit['syntheticLocalInferenceOracleClaim'])
        self.fixture.write(self.output/'audit.json',audit)
        authority=m.marker_authority.Marker(m.artifacts.ref(self.output/'audit.json'))
        self.assertEqual('java.io.Serializable',authority.parsed['owner'])
        archive=self.fixture.root/'authority';archive.mkdir();row=authority.archive(archive)
        self.assertEqual(marker(),Path(row['rawClassFile']).read_bytes())
        self.assertEqual('EXACT_BOOTSTRAP_MARKER_NOT_CORPUS_JAR',row['sourceKind'])

    def test_accepted_alias_pair_with_existing_authority(self):self.check_success()
    def test_candidate_pair_with_distinct_producer_sources(self):self.make_actual('candidate');self.check_success()
    def test_parent_pair_with_distinct_producer_sources(self):self.make_actual('parent');self.check_success()

    def test_failure_preserves_raw_phase_and_stops(self):
        self.fail='compile-bootstrap-marker';record=self.execute()
        self.assertEqual('FAIL',record['status']);self.assertEqual(1,len(self.calls))
        self.assertEqual(17,m.common.read(self.output/self.fail/'record.json')['exit'])
        with self.assertRaisesRegex(ValueError,'completed actual bootstrap'):m.audit(self.output)

    def test_invalid_marker_class_rejected_even_with_matching_digest(self):
        self.bad=True;self.assertEqual('FAIL',self.execute()['status'])
        self.assertTrue((self.output/'Serializable.class').exists())
        with self.assertRaises(ValueError):m.audit(self.output)

    def test_modified_plan_and_existing_output_rejected(self):
        plan=self.plan();plan['modules']['sha256']='0'*64
        with self.assertRaisesRegex(ValueError,'execution plan differs'):self.execute(plan)
        self.assertEqual([],self.calls);self.assertFalse(self.output.exists())
        self.execute()
        with self.assertRaises(FileExistsError):self.execute()

    def test_wrong_receipt_module_or_bootstrap_identity_rejected(self):
        self.execute();path=self.output/'receipt.json';original=m.common.read(path)
        for key,value in [('modulesSha256','0'*64),('module','foreign'),('bootstrapClassLoader',False),('rawClassFile','/foreign')]:
            with self.subTest(key=key):
                self.fixture.write(path,{**original,key:value})
                with self.assertRaisesRegex(ValueError,'bootstrap module receipt'):m.audit(self.output)

    def test_wrong_command_repin_cannot_hide_heap_change(self):
        self.execute();path=self.output/'compile-bootstrap-marker/record.json';record=m.common.read(path)
        record['argv'][1]='-J-Xmx16g';self.fixture.write(path,record)
        record=m.common.read(self.output/'record.json');record['phases'][0]=m.artifacts.ref(path);self.fixture.write(self.output/'record.json',record)
        with self.assertRaisesRegex(ValueError,'raw actual command'):m.audit(self.output)

    def test_extra_helper_and_module_drift_rejected(self):
        self.execute();extra=self.output/'classes/Extra.class';extra.write_bytes(b'bad')
        with self.assertRaisesRegex(ValueError,'compiled marker helper'):m.audit(self.output)
        extra.unlink();Path(self.plan()['modules']['path']).write_bytes(b'changed')
        with self.assertRaises(ValueError):m.audit(self.output)

    def test_promoted_partial_claim_rejected(self):
        self.execute();path=self.output/'record.json';record=m.common.read(path);record['completeSemanticEquivalence']=True
        self.fixture.write(path,record)
        with self.assertRaisesRegex(ValueError,'completed actual bootstrap'):m.audit(self.output)

    def test_unbound_audit_and_missing_raw_phase_rejected(self):
        value=m.common.read(self.reference);value['revision']='f'*40
        self.fixture.write(self.reference,value)
        with self.assertRaises(ValueError):self.plan()
        self.fixture.write(self.reference,self.fixture.run_audit());self.execute()
        (self.output/'export-bootstrap-marker/owner.json').unlink()
        with self.assertRaises((ValueError,FileNotFoundError)):m.audit(self.output)

    def test_different_producer_jdk_path_rejected(self):
        self.make_actual('candidate')
        # The independently audited fixture itself stays untouched. This proves
        # a stored audit with foreign runtime metadata cannot stand in for it.
        value=m.common.read(self.actual);runtime=m.common.read(value['runtimeManifest']['path'])
        path=self.fixture.root/'foreign-runtime.json';runtime['toolchainIdentity']['java']='/foreign/bin/java'
        self.fixture.write(path,runtime);value['runtimeManifest']=m.artifacts.ref(path)
        value['pins'][str(path)]=m.common.sha(path);self.fixture.write(self.actual,value)
        with self.assertRaises(ValueError):self.plan()

    def test_output_in_measured_roots_rejected(self):
        for root in (self.fixture.checkout,self.fixture.out/'graphs',self.fixture.out/'runtime'):
            with self.assertRaisesRegex(ValueError,'outside actual source/runtime/graph'):
                m.bind(self.reference,self.actual,self.fixture.inputs_path,root/'marker')


if __name__=='__main__':unittest.main()
