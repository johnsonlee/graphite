"""Tiny protocol artifacts only; mocked Java phases, no real JVM/graphs/network."""
import hashlib
import json
from pathlib import Path
import struct
import unittest
from unittest.mock import patch
import zipfile

import export_native_core_strings as export
import test_audit_native_pressure_artifacts as fixtures


class ExportTests(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.ArtifactAuditTests()
        self.fixture.revision = getattr(self, 'revision', 'b'*40)
        self.fixture.role = getattr(self, 'role', 'candidate')
        self.fixture.setUp(); self.addCleanup(self.fixture.doCleanups)
        f = self.fixture
        jar = f.out/'runtime/writer.jar'
        with zipfile.ZipFile(jar, 'w') as archive: archive.writestr(export.DEPENDENCY, b'actual fixture dependency')
        f.runtime['files'][str(jar)] = export.common.sha(jar)
        for original in f.runtime['originalArtifacts']:
            if Path(original).name == 'writer-jmh.jar':
                Path(original).write_bytes(jar.read_bytes())
                f.runtime['originalArtifacts'][original] = export.common.sha(original)
        f.fixture['writerJarSha256'] = export.common.sha(jar)
        for graph in f.fixture['graphs']:
            Path(graph['path'], 'graph.strings').write_bytes(('serialized fixture '+graph['id']).encode())
        f.fixture['files'] = export.artifacts.inventory(f.out/'graphs')
        f.packet['runtimeManifest'] = f.runtime
        for name, value in [('runtime-manifest.json',f.runtime),('fixture-manifest.json',f.fixture),('packet.json',f.packet)]:
            f.write(f.out/name,value)
        self.artifact_path = f.out/'artifact-audit.json'; f.write(self.artifact_path,f.run_audit())
        self.output = f.root/'exports'
        inventory = patch.object(export.artifacts,'source_inventory',return_value=f.source)
        inventory.start(); self.addCleanup(inventory.stop)
        no_child = patch('subprocess.Popen', side_effect=AssertionError('no child process allowed'))
        no_child.start(); self.addCleanup(no_child.stop)
        self.plan = export.bind(self.artifact_path,f.inputs_path,self.output)
        self.calls=[]; self.fail_name=None; self.bad_export=False

    def fake_phase(self,name,argv,cwd,env,out,timeout):
        self.calls.append((name,argv,env));directory=out/name;directory.mkdir()
        (directory/'stdout.log').write_text('protocol phase');(directory/'stderr.log').write_text('')
        owner={'group':12345,'runnerPid':1234,'argv':argv};self.fixture.write(directory/'owner.json',owner)
        record={'name':name,'argv':argv,'cwd':str(cwd),'timeoutSeconds':timeout,'errors':[], 'status':'PASS',
                'exit':0,'cleanup':{'group':12345,'after':[],'errors':[],'exit':0},
                'logs':{str(directory/n):export.common.sha(directory/n) for n in ('stdout.log','stderr.log')}}
        if name == self.fail_name:
            record.update(status='FAIL',errors=['intentional child failure'],exit=17)
        elif name == 'compile-exporter':
            for leaf in export.CLASSES: (out/'classes'/leaf).write_bytes(leaf.encode())
        else:
            destination=Path(argv[-2]);destination.mkdir()
            words=[b'A',b'B'];i=lambda n:struct.pack('>i',n)
            raw=i(0x47534f01)+i(len(words))+b''.join(i(len(w))+w for w in words)
            values, semantic = export.wire.legacy.strings_export(raw)
            self.assertEqual(['A','B'],list(values))
            (destination/'strings.bin').write_bytes(raw)
            self.fixture.write(destination/'receipt.json',{'format':'GSO01','inputSha256':argv[-3],
                'outputSha256':export.common.sha(destination/'strings.bin'),'entryCount':len(words),
                'semanticSha256':semantic,'helperSourceSha256':export.SOURCE_SHA,
                'helperClassSha256':export.common.sha(out/'classes/ExportStrings.class'),
                'frontCodedStringListClassSha256':self.plan['dependencyClassSha256']})
            if self.bad_export:(destination/'strings.bin').write_bytes(raw+b'bad trailing bytes')
        self.fixture.write(directory/'record.json',record)
        if record['status'] != 'PASS':raise ValueError('intentional child failure')
        return record

    def execute(self):
        with patch.object(export.producer,'phase',side_effect=self.fake_phase):
            return export.execute(self.plan)

    def test_all64_bind_actual_writer_inputs_and_full_raw_outputs(self):
        record=self.execute();self.assertEqual(export.PASS,record['status'])
        audit=export.audit(self.output);self.assertEqual(export.AUDIT_PASS,audit['status'])
        self.assertEqual(65,len(self.calls));self.assertEqual(64,len(audit['graphs']))
        self.assertEqual([g['id'] for g in self.plan['graphs']],[g['id'] for g in audit['graphs']])
        self.assertEqual(self.fixture.revision,audit['revision']);self.assertEqual(self.fixture.role,audit['role'])
        self.assertEqual(export.artifacts.ref(self.artifact_path),audit['artifactAudit'])
        for expected,row in zip(self.plan['graphs'],audit['graphs']):
            self.assertEqual(expected['input'],row['input'])
            self.assertEqual(export.artifacts.ref(self.output/'exports'/row['id']/'strings.bin'),row['stringsExport'])
        self.assertEqual('-J-Xmx4g',self.calls[0][1][1])
        for name,argv,env in self.calls:
            self.assertEqual('-Xmx4g -XX:ActiveProcessorCount=4',env['JAVA_TOOL_OPTIONS'])
            self.assertIn(self.plan['writerJar'],argv if name=='compile-exporter' else argv[4].split(':'))
        self.assertFalse(audit['completeSemanticEquivalence']);self.assertFalse(audit['performanceAcceptance'])
        self.assertFalse(audit['sourceToDeclarationCompletenessClaim'])

    def test_failure_stops_and_preserves_partial_raw_evidence(self):
        self.fail_name=self.plan['graphs'][1]['id']+'-export';record=self.execute()
        self.assertEqual('FAIL',record['status']);self.assertEqual(3,len(self.calls));self.assertEqual(1,len(record['exports']))
        self.assertTrue((self.output/self.fail_name/'record.json').exists())
        self.assertEqual(17,export.common.read(self.output/self.fail_name/'record.json')['exit'])
        with self.assertRaisesRegex(ValueError,'completed actual export'):export.audit(self.output)

    def test_invalid_export_never_becomes_completed(self):
        self.bad_export=True;record=self.execute()
        self.assertEqual('FAIL',record['status']);self.assertEqual(2,len(self.calls));self.assertEqual([],record['exports'])
        self.assertTrue((self.output/'exports'/self.plan['graphs'][0]['id']/'strings.bin').exists())

    def test_modified_plan_rejected_before_creating_output_or_phase(self):
        self.plan['java']='/foreign/java'
        with self.assertRaisesRegex(ValueError,'execution plan differs'):
            self.execute()
        self.assertEqual([],self.calls);self.assertFalse(self.output.exists())

    def test_existing_output_cannot_be_overwritten(self):
        self.execute()
        with self.assertRaises(FileExistsError):self.execute()

    def test_extra_compiled_class_is_rejected(self):
        self.execute();(self.output/'classes/Injected.class').write_bytes(b'injected')
        with self.assertRaisesRegex(ValueError,'compiled helper closure'):export.audit(self.output)

    def test_changed_actual_input_dictionary_rejected(self):
        self.execute();Path(self.plan['graphs'][0]['input']['path']).write_bytes(b'other')
        with self.assertRaises(ValueError):export.audit(self.output)

    def test_wrong_raw_command_cannot_be_repaired_by_repinning_receipt(self):
        self.execute();path=self.output/'compile-exporter/record.json';raw=export.common.read(path)
        raw['argv'][1]='-J-Xmx16g';self.fixture.write(path,raw)
        record=export.common.read(self.output/'record.json');record['phases'][0]=export.artifacts.ref(path)
        self.fixture.write(self.output/'record.json',record)
        with self.assertRaisesRegex(ValueError,'raw actual command'):export.audit(self.output)

    def test_missing_graph_output_is_rejected(self):
        self.execute();(self.output/'exports'/self.plan['graphs'][0]['id']/'strings.bin').unlink()
        with self.assertRaisesRegex(ValueError,'export output closure'):export.audit(self.output)

    def test_receipt_cannot_rebind_another_dictionary_or_dependency(self):
        self.execute();path=self.output/'exports'/self.plan['graphs'][0]['id']/'receipt.json';original=export.common.read(path)
        for key in ('inputSha256','frontCodedStringListClassSha256'):
            value=dict(original);value[key]='0'*64;self.fixture.write(path,value)
            with self.assertRaises(ValueError):export.audit(self.output)

    def test_changed_source_input_or_premature_semantic_pass_rejected(self):
        self.execute();path=self.output/'record.json';value=export.common.read(path)
        value['completeSemanticEquivalence']=True;self.fixture.write(path,value)
        with self.assertRaisesRegex(ValueError,'completed actual export'):export.audit(self.output)

    def test_output_inside_measured_artifact_root_rejected(self):
        for parent in (self.fixture.checkout,self.fixture.out/'graphs',self.fixture.out/'runtime'):
            with self.assertRaisesRegex(ValueError,'outside source/graph/runtime'):
                export.bind(self.artifact_path,self.fixture.inputs_path,parent/'exports')

    def test_environment_removes_java_and_loader_injection(self):
        with patch.dict(export.os.environ,{'JAVA_TOOL_OPTIONS':'-Xmx99g','JDK_JAVAC_OPTIONS':'@bad',
             '_JAVA_OPTIONS':'-javaagent:bad','CLASSPATH':'bad','LD_PRELOAD':'bad','GRAPHITE_X':'bad'}):
            env=export.environment(self.plan['java'])
        self.assertEqual('-Xmx4g -XX:ActiveProcessorCount=4',env['JAVA_TOOL_OPTIONS'])
        for key in ('JDK_JAVAC_OPTIONS','_JAVA_OPTIONS','CLASSPATH','LD_PRELOAD','GRAPHITE_X'):
            self.assertNotIn(key,env)


class OtherRoleExportTests(unittest.TestCase):
    def check_role(self, revision, role):
        case=ExportTests();case.revision=revision;case.role=role
        case.setUp();self.addCleanup(case.doCleanups)
        case.test_all64_bind_actual_writer_inputs_and_full_raw_outputs()

    def test_accepted_writer_keeps_actual_revision_and_all64_bindings(self):
        self.check_role(export.artifacts.ACCEPTED,'accepted-baseline')

    def test_parent_writer_keeps_actual_revision_and_all64_bindings(self):
        self.check_role('a'*40,'parent')


if __name__=='__main__':unittest.main()
