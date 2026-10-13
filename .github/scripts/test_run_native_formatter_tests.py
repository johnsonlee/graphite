"""Tiny real files/ZIP/XML and owned-phase fixtures; never starts Gradle or Java."""
import copy
from pathlib import Path
import shutil
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile
import run_native_formatter_tests as r
import test_audit_native_pressure_artifacts as fixtures


class ProductionFormatterTests(unittest.TestCase):
    def setUp(self):
        f=self.f=fixtures.ArtifactAuditTests();f.setUp();self.addCleanup(f.doCleanups)
        contract=r.common.read(r.CONTRACT);source=r.common.read(f.out/'source-manifest.json')
        repo=Path(__file__).resolve().parents[2]
        for row in contract['classes']:
            path=f.checkout/row['source'];path.parent.mkdir(parents=True,exist_ok=True)
            shutil.copyfile(repo/row['source'],path);source['files'][str(path)]=r.common.sha(path)
        self.formatters=r.common.read(r.formatter_binding.CONTRACT)['formatters']
        self.folding=f.checkout/r.formatter_binding.FOLDING;self.folding.parent.mkdir(parents=True,exist_ok=True)
        self.folding.write_text(self.formatters['B']);source['files'][str(self.folding)]=r.common.sha(self.folding)
        f.write(f.out/'source-manifest.json',source);f.source=source['files']
        f.runtime['sourceFiles']=f.source
        f.runtime['sourceManifestSha256']=f.fixture['sourceManifestSha256']=r.common.sha(f.out/'source-manifest.json')
        self.classes={name:b'class bytes '+name.encode() for name in (*r.CORE_CLASSES,*r.PERSISTENCE_CLASSES)}
        self.classes['io/johnsonlee/graphite/graph/Extra.class']=b'extra production class'
        jar=f.out/'runtime/writer.jar'
        with zipfile.ZipFile(jar,'w') as z:
            for name,data in self.classes.items():z.writestr(name,data)
        f.runtime['files'][str(jar)]=f.fixture['writerJarSha256']=r.common.sha(jar)
        for original in f.runtime['originalArtifacts']:
            if Path(original).name=='writer-jmh.jar':
                Path(original).write_bytes(jar.read_bytes());f.runtime['originalArtifacts'][original]=r.common.sha(original)
        f.packet['runtimeManifest']=f.runtime
        for name,value in [('runtime-manifest.json',f.runtime),('fixture-manifest.json',f.fixture),('packet.json',f.packet)]:f.write(f.out/name,value)
        self.artifact=f.out/'artifact-audit.json';f.write(self.artifact,f.run_audit())
        p=patch.object(r.artifacts,'source_inventory',return_value=f.source);p.start();self.addCleanup(p.stop)
        p=patch('subprocess.Popen',side_effect=AssertionError('no actual child'));p.start();self.addCleanup(p.stop)
        self.out=f.root/'tests';self.plan=r.bind(self.artifact,f.inputs_path,self.out);self.calls=[];self.fail=False

    def phase(self,name,argv,cwd,env,out,timeout):
        self.calls.append((name,argv,env));phase=out/name;phase.mkdir()
        (phase/'stdout.log').write_text('owned correctness');(phase/'stderr.log').write_text('')
        self.f.write(phase/'owner.json',{'group':12345,'runnerPid':1234,'argv':argv})
        record={'name':name,'argv':argv,'cwd':str(cwd),'timeoutSeconds':timeout,'status':'PASS','exit':0,'errors':[],
            'cleanup':{'group':12345,'after':[],'errors':[],'exit':0},
            'logs':{str(phase/n):r.common.sha(phase/n) for n in ('stdout.log','stderr.log')}}
        if self.fail:
            record.update(status='FAIL',exit=17,errors=['intentional test failure']);self.f.write(phase/'record.json',record)
            raise ValueError('intentional test failure')
        self.f.write(phase/'record.json',record)
        contract=r.common.read(r.CONTRACT)
        for row in contract['classes']:
            xml=ET.Element('testsuite',name=row['className'],tests=str(len(row['testCases'])),failures='0',errors='0',skipped='0')
            for case in row['testCases']:ET.SubElement(xml,'testcase',name=case+'()',classname=row['className'])
            file=out/'results/xml'/row['module']/('TEST-'+row['className']+'.xml');file.parent.mkdir(parents=True,exist_ok=True)
            file.write_bytes(ET.tostring(xml))
        classes=self.f.root/'actual-compiled-main';classes.mkdir()
        for name,data in self.classes.items():
            path=classes/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
        for module in ('sootup','webgraph'):
            value={'schema':'graphite.formatter-test-classpath.v1','java':self.plan['java'],'maxHeapSize':'4g','maxParallelForks':1,
                   'jvmArgs':['-XX:ActiveProcessorCount=4'],'classpath':[{'path':str(classes),'kind':'directory',
                       'files':{str(Path(path).relative_to(classes)):h for path,h in r.artifacts.inventory(classes).items()}},
                       {'path':str(self.f.root/'not-yet-present'),'kind':'absent'}]}
            for stage in ('before','after'):self.f.write(out/f'results/classpath-{module}-{stage}.json',value)
        return record

    def execute(self,plan=None):
        with patch.object(r.producer,'phase',side_effect=self.phase):return r.execute(plan or self.plan)

    def test_fresh_cli_keeps_raw_audit_and_replays_each_producer_once_before_execution(self):
        argv=['helper','--output',str(self.out),'--source-inputs',str(self.f.inputs_path)]
        argv+=['--artifact-audit',str(self.artifact)]
        with patch.object(r.sys,'argv',argv), patch.object(r.producer,'phase',side_effect=self.phase), \
                patch.object(r.artifacts,'audit',wraps=r.artifacts.audit) as full:
            self.assertEqual(0,r.main())
            self.assertEqual(3,full.call_count)
        report=r.common.read(self.out/'audit.json')
        self.assertEqual(r.AUDIT_PASS,report['status'])
        self.assertEqual(report,r.audit(self.out))

    def test_fresh_boundary_rejects_same_size_byte_drift_and_added_graph_file_before_output(self):
        import os
        original=r.bind
        graph=Path(self.f.fixture['graphs'][0]['path'])
        target=graph/'graph.nodes';before=target.read_bytes();times=target.stat()
        extra=graph/'unexpected-file'
        authority=self.artifact;authority_before=authority.read_bytes()
        for mutation in ('bytes','membership','authority'):
            with self.subTest(mutation=mutation):
                def changed(*args,**kwargs):
                    plan=original(*args,**kwargs)
                    if mutation=='bytes':
                        target.write_bytes(bytes([before[0]^1])+before[1:])
                        os.utime(target,ns=(times.st_atime_ns,times.st_mtime_ns))
                    elif mutation=='membership':extra.write_bytes(b'not declared')
                    else:authority.write_bytes(b'{}')
                    return plan
                try:
                    with patch.object(r,'bind',side_effect=changed), \
                            patch.object(r.producer,'phase',side_effect=AssertionError('no child before valid boundary')):
                        with self.assertRaises(ValueError):r._execute_fresh(self.artifact,self.f.inputs_path,self.out)
                    self.assertFalse(self.out.exists())
                    self.assertEqual([],self.calls)
                finally:
                    target.write_bytes(before)
                    os.utime(target,ns=(times.st_atime_ns,times.st_mtime_ns))
                    extra.unlink(missing_ok=True)
                    authority.write_bytes(authority_before)

    def test_fresh_execution_retains_child_failure_and_never_becomes_audit_pass(self):
        self.fail=True
        with patch.object(r.producer,'phase',side_effect=self.phase):
            result=r._execute_fresh(self.artifact,self.f.inputs_path,self.out)
        self.assertEqual('FAIL',result['status'])
        self.assertTrue(result['errors'])
        self.assertTrue((self.out/'record.json').is_file())
        with self.assertRaises(ValueError):r.audit(self.out)
        self.assertFalse((self.out/'audit.json').exists())

    def test_nested_audit_context_reuses_only_producer_replay(self):
        self.execute()
        expected=r.audit(self.out)
        with patch.object(r.artifacts,'audit',wraps=r.artifacts.audit) as full:
            with r.artifacts._ProducerReplayContext() as context:
                self.assertEqual(expected,r.audit(self.out,_artifact_context=context))
                self.assertEqual(expected,r.audit(self.out,_artifact_context=context))
            self.assertEqual(1,full.call_count)
        with self.assertRaises((KeyError,ValueError)):
            with r.artifacts._ProducerReplayContext() as context:
                r.audit(self.out,_artifact_context=context)
                (self.out/'record.json').write_text('{}')
                r.audit(self.out,_artifact_context=context)

    def test_all21_tests_bind_actual_writer_classes_and_reuse_original_cache(self):
        self.assertEqual(r.PASS,self.execute()['status']);audit=r.audit(self.out)
        self.assertEqual(r.AUDIT_PASS,audit['status']);self.assertEqual(21,sum(x['tests'] for x in audit['tests']))
        self.assertEqual(self.plan['revision'],audit['revision']);self.assertTrue(audit['productionFormatterTestsVerified'])
        self.assertFalse(audit['syntheticLocalInferenceOracleClaim']);self.assertFalse(audit['completeSemanticEquivalence'])
        self.assertEqual(self.f.packet['buildEnvironment']['GRADLE_USER_HOME'],self.calls[0][2]['GRADLE_USER_HOME'])
        self.assertEqual(self.f.packet['buildEnvironment']['HOME'],self.calls[0][2]['HOME'])
        self.assertIn('--rerun-tasks',self.calls[0][1]);self.assertIn('--no-daemon',self.calls[0][1])
        self.assertEqual(set(self.classes),set(audit['classpaths'][1]['comparedProductionClasses']))

    def test_failed_owned_test_phase_stays_failure_with_raw_evidence(self):
        self.fail=True;record=self.execute();self.assertEqual('FAIL',record['status'])
        self.assertEqual(17,r.common.read(self.out/'production-formatter-tests/record.json')['exit'])
        with self.assertRaisesRegex(ValueError,'completed actual production'):r.audit(self.out)

    def test_existing_output_and_mutated_plan_are_rejected(self):
        plan=copy.deepcopy(self.plan);plan['java']='/foreign/java'
        with self.assertRaisesRegex(ValueError,'execution plan differs'):self.execute(plan)
        self.assertFalse(self.out.exists());self.assertEqual([],self.calls)
        self.execute()
        with self.assertRaises(FileExistsError):self.execute()

    def test_skipped_missing_failed_and_unrelated_cases_rejected(self):
        self.execute();path=next((self.out/'results/xml/sootup').glob('*ArrayTypeFormatter*'))
        raw=path.read_bytes()
        for mode in ('skipped','failure','missing','unrelated'):
            xml=ET.fromstring(raw);case=xml.find('testcase')
            if mode in ('skipped','failure'):ET.SubElement(case,mode)
            elif mode=='missing':xml.remove(case)
            else:case.attrib['name']='unrelated'
            path.write_bytes(ET.tostring(xml))
            with self.subTest(mode=mode),self.assertRaises(ValueError):r.audit(self.out)

    def test_wrong_actual_class_bytes_rejected_even_after_snapshot_repin(self):
        self.execute();path=self.f.root/'actual-compiled-main'/r.CORE_CLASSES[0];path.write_bytes(b'wrong production class')
        for module in ('sootup','webgraph'):
            for stage in ('before','after'):
                file=self.out/f'results/classpath-{module}-{stage}.json';value=r.common.read(file)
                value['classpath'][0]['files'][r.CORE_CLASSES[0]]=r.common.sha(path);self.f.write(file,value)
        with self.assertRaisesRegex(ValueError,'required actual tested writer classes'):r.audit(self.out)

    def test_extra_matching_namespace_class_mismatch_rejected(self):
        self.execute();name='io/johnsonlee/graphite/graph/Extra.class';path=self.f.root/'actual-compiled-main'/name;path.write_bytes(b'changed')
        for module in ('sootup','webgraph'):
            for stage in ('before','after'):
                file=self.out/f'results/classpath-{module}-{stage}.json';value=r.common.read(file);value['classpath'][0]['files'][name]=r.common.sha(path);self.f.write(file,value)
        with self.assertRaisesRegex(ValueError,'production class bytes differ'):r.audit(self.out)

    def test_classpath_change_heap_override_or_absent_root_appearance_rejected(self):
        self.execute();file=self.out/'results/classpath-sootup-after.json';original=r.common.read(file)
        changed=copy.deepcopy(original);changed['maxHeapSize']='16g';self.f.write(file,changed)
        with self.assertRaisesRegex(ValueError,'bounded direct JVM'):r.audit(self.out)
        self.f.write(file,original);(self.f.root/'not-yet-present').mkdir()
        with self.assertRaisesRegex(ValueError,'absent classpath root appeared'):r.audit(self.out)

    def test_source_test_mutation_and_false_final_claim_rejected(self):
        self.execute();path=self.out/'record.json';value=r.common.read(path);value['syntheticLocalInferenceOracleClaim']=True;self.f.write(path,value)
        with self.assertRaisesRegex(ValueError,'completed actual production'):r.audit(self.out)
        value['syntheticLocalInferenceOracleClaim']=False;self.f.write(path,value)
        row=r.common.read(r.CONTRACT)['classes'][0];(self.f.checkout/row['source']).write_text('changed test assertion')
        with self.assertRaises(ValueError):r.audit(self.out)

    def test_actual_classpath_jar_and_first_resolution_are_checked(self):
        self.execute();jar=self.f.root/'compiled-model.jar'
        with zipfile.ZipFile(jar,'w') as z:
            for name,data in self.classes.items():z.writestr(name,data)
        for module in ('sootup','webgraph'):
            for stage in ('before','after'):
                file=self.out/f'results/classpath-{module}-{stage}.json';value=r.common.read(file)
                value['classpath']=[{'path':str(jar),'kind':'file','sha256':r.common.sha(jar)}]
                self.f.write(file,value)
            self.assertEqual(set(self.classes),set(r.classpath(self.plan,module)['comparedProductionClasses']))
        # A preceding test class that shadows a writer production class must fail.
        shadow=self.f.root/'shadow';path=shadow/r.CORE_CLASSES[0];path.parent.mkdir(parents=True);path.write_bytes(b'shadow')
        for stage in ('before','after'):
            file=self.out/f'results/classpath-sootup-{stage}.json';value=r.common.read(file)
            value['classpath'].insert(0,{'path':str(shadow),'kind':'directory','files':{r.CORE_CLASSES[0]:r.common.sha(path)}})
            self.f.write(file,value)
        with self.assertRaisesRegex(ValueError,'required actual tested writer classes'):r.classpath(self.plan,'sootup')

    def test_parent_with_existing_exact_tests_is_supported(self):
        self.f.packet['role']=self.f.role='parent';self.f.write(self.f.out/'packet.json',self.f.packet)
        self.f.write(self.artifact,self.f.run_audit());self.plan=r.bind(self.artifact,self.f.inputs_path,self.out)
        self.assertEqual(r.PASS,self.execute()['status']);self.assertEqual('parent',r.common.read(self.out/'plan.json')['role'])

    def repin_sources(self,role=None):
        if role:self.f.packet['role']=self.f.role=role
        source=r.common.read(self.f.out/'source-manifest.json');source['files'][str(self.folding)]=r.common.sha(self.folding)
        source['files']={path:digest for path,digest in source['files'].items() if Path(path).exists()}
        self.f.write(self.f.out/'source-manifest.json',source);self.f.source.clear();self.f.source.update(source['files'])
        self.f.runtime['sourceFiles']=self.f.source
        self.f.runtime['sourceManifestSha256']=self.f.fixture['sourceManifestSha256']=r.common.sha(self.f.out/'source-manifest.json')
        for name,value in [('runtime-manifest.json',self.f.runtime),('fixture-manifest.json',self.f.fixture),('packet.json',self.f.packet)]:self.f.write(self.f.out/name,value)
        self.f.write(self.artifact,self.f.run_audit())

    def test_candidate_audit_records_the_rank_preserving_renderer_as_tested(self):
        self.execute();audit=r.audit(self.out)
        self.assertEqual(('rank-preserving',True,True),(audit['formatterKind'],audit['productionFormatterTestsApplicable'],audit['productionFormatterTestsVerified']))
        self.assertTrue(r.common.read(self.out/'plan.json')['applicable'])

    def test_flat_parent_runs_no_migration_tests_and_says_so(self):
        # A rebased PR's parent arm is main, whose renderer is the accepted baseline's flat one and
        # whose checkout has no migration test sources: nothing runs, and the audit claims nothing.
        for row in r.common.read(r.CONTRACT)['classes']:(self.f.checkout/row['source']).unlink()
        self.folding.write_text(self.formatters['C']);self.repin_sources('parent')
        plan=r.bind(self.artifact,self.f.inputs_path,self.out)
        self.assertEqual(('legacy-flat',False,'parent'),(plan['formatterKind'],plan['applicable'],plan['role']))
        with patch.object(r.producer,'phase',side_effect=AssertionError('no gradle for the accepted renderer')):
            record=r.execute(plan)
        self.assertEqual(r.PASS,record['status']);self.assertEqual([],record['errors'])
        self.assertEqual(([],[],None,r.NOT_APPLICABLE),(record['tests'],record['classpaths'],record['phase'],record['notApplicable']))
        audit=r.audit(self.out)
        self.assertEqual((r.AUDIT_PASS,'legacy-flat',False,False),(audit['status'],audit['formatterKind'],
            audit['productionFormatterTestsApplicable'],audit['productionFormatterTestsVerified']))
        self.assertEqual(([],[]),(audit['tests'],audit['classpaths']))
        value=r.common.read(self.out/'record.json');value['tests']=[{'className':'forged'}];self.f.write(self.out/'record.json',value)
        with self.assertRaisesRegex(ValueError,'legacy renderer runs no'):r.audit(self.out)

    def test_candidate_must_hold_the_rank_preserving_renderer(self):
        self.folding.write_text(self.formatters['C']);self.repin_sources()
        with self.assertRaisesRegex(ValueError,'rank-preserving renderer'):r.bind(self.artifact,self.f.inputs_path,self.out)

    def test_unreviewed_formatter_source_rejected(self):
        self.folding.write_text(self.formatters['B'].replace('type.toString()','"$type"'));self.repin_sources()
        with self.assertRaisesRegex(ValueError,'exact reviewed formatter source'):r.bind(self.artifact,self.f.inputs_path,self.out)

    def test_baseline_cannot_be_claimed_tested_using_new_candidate_tests(self):
        value=r.common.read(self.artifact);value['role']='accepted-baseline';self.f.write(self.artifact,value)
        with self.assertRaisesRegex(ValueError,'non-baseline writer'):r.bind(self.artifact,self.f.inputs_path,self.out)


if __name__=='__main__':unittest.main()
