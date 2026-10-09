"""Synthetic metadata correctness only: no child, service or performance workload."""
import copy
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import prepare_native_pressure_plan as b


class PreparationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.addCleanup(patch.stopall)
        patch('subprocess.Popen', side_effect=AssertionError('child forbidden')).start()

    def put(self, name, value):
        p = self.root / name
        p.write_text(json.dumps(value))
        return {'path': str(p), 'sha256': b.pressure.sha(p)}

    def args(self):
        return SimpleNamespace(output=self.root/'out', provenance=self.root/'missing-provenance',
                               fixture=self.root/'missing-fixture', base_sha='a'*40,
                               candidate_sha='b'*40, producers=None)

    def test_missing_accepted_inputs_never_become_pass(self):
        result = b.prepare(self.args())
        self.assertEqual('UNAVAILABLE', result['status'])
        self.assertFalse(result['passed'])
        self.assertFalse((self.root/'out/plan.json').exists())
        self.assertIn('accepted4f compatible real64 saved graphs', result['missingProducers'])
        self.assertEqual(['construction', 'loading'], result['unavailableOperations'])
        self.assertEqual([], result['unavailableFamilies'])
        self.assertTrue(result['missingProducers'])  # Complete catalog is not completed evidence.

    def test_actual_wrong_candidate_fixture_is_failure_not_absence(self):
        args = self.args()
        args.fixture = Path(self.put('fixture.json', {'schema':'graphite-shared-fixture64-v1',
                          'complete':True, 'candidateSha':'c'*40})['path'])
        result = b.prepare(args)
        self.assertEqual('FAIL', result['status'])
        self.assertIn('actual candidate fixture identity', result['errors'][0])

    def test_source_relabel_rejected_from_recorded_git_output(self):
        record = {'commands':[{'command':['git','-C','base','rev-parse','HEAD','HEAD:backend','HEAD:cli'],
                               'output':'c'*40+'\n'}], 'files':{}}
        provenance = Path(self.put('provenance.json', record)['path'])
        with self.assertRaisesRegex(ValueError, 'revision mismatch'):
            b.inspect_available(provenance, self.root/'absent', 'a'*40, 'b'*40)

    def test_candidate_fixture_receipt_links_complete_actual_files(self):
        (self.root/'graphs').mkdir()
        values = {}
        for field, name in [('manifestSha256','graphs/graphs.tsv'),('provenanceSha256','graphs/fixture-provenance.tsv'),
                            ('receiptSha256','fixture-reproducibility.json')]:
            p = self.root/name;p.write_text('fixture metadata '+name);values[field]=b.pressure.sha(p)
        fixture = Path(self.put('fixture.json', {'schema':'graphite-shared-fixture64-v1', 'complete':True,
                          'candidateSha':'b'*40, **values})['path'])
        result = b.inspect_available(self.root/'absent', fixture, 'a'*40, 'b'*40)
        self.assertEqual(['B-fixture64-production'], result['available'])
        (self.root/'graphs/graphs.tsv').write_text('tampered')
        with self.assertRaisesRegex(ValueError, 'provenance changed'):
            b.inspect_available(self.root/'absent', fixture, 'a'*40, 'b'*40)

    def runtime(self):
        source = self.put('source.json', {'revision':b.ACCEPTED, 'root':str(self.root), 'files':{'cli/src/main.rs':'1'*64}})
        runtime = self.put('runtime.json', {'revision':b.ACCEPTED,'sourceFiles':{'cli/src/main.rs':'1'*64},
                       'sourceManifestSha256':source['sha256'],'binary':'/synthetic/graphite','sha256':'2'*64})
        audit = self.put('audit.json', {'status':'PASS','revision':b.ACCEPTED,
                         'sourceManifestSha256':source['sha256'],'runtimeManifestSha256':runtime['sha256'],
                         'binarySha256':'2'*64,'sourceBefore':{'cli/src/main.rs':'1'*64},'sourceAfter':{'cli/src/main.rs':'1'*64}})
        arm = {'revision':b.ACCEPTED,'patchSha256':None,'sourceManifest':source,'runtimeManifest':runtime,
               'runtimeFiles':{'/synthetic/graphite':'2'*64},'serverArgv':['/synthetic/graphite','serve']}
        return {'arms':{'C':arm},'buildAudits':{'C':audit},'pins':{str(self.root/'cli/src/main.rs'):'1'*64, **{r['path']:r['sha256'] for r in [source,runtime,audit]}},
                'proofs':[{'role':'source-runtime','arm':'C','upstream':{audit['path']:audit['sha256']}}]}

    def test_completed_build_links_and_c_revision_are_required(self):
        packet = self.runtime()
        self.assertEqual(b.ACCEPTED, b.runtime_binding(packet, 'C', b.ACCEPTED)['revision'])
        packet['arms']['C']['revision'] = 'b'*40
        with self.assertRaisesRegex(ValueError, 'CI source revision'):
            b.runtime_binding(packet, 'C', b.ACCEPTED)

    def test_source_closure_missing_pin_rejected(self):
        packet = self.runtime()
        del packet['pins'][str(self.root/'cli/src/main.rs')]
        with self.assertRaisesRegex(ValueError, 'source closure pin'):
            b.runtime_binding(packet, 'C', b.ACCEPTED)

    def test_short_revision_is_failure_even_without_producers(self):
        args = self.args(); args.base_sha = 'abc123'
        self.assertEqual('FAIL', b.prepare(args)['status'])

    def test_unknown_or_missing_build_authority_fails(self):
        packet = self.runtime();packet['proofs'][0]['upstream'] = {}
        with self.assertRaisesRegex(ValueError, 'actual build audit'):
            b.runtime_binding(packet, 'C', b.ACCEPTED)
        packet = self.runtime();Path(packet['buildAudits']['C']['path']).write_text('{}')
        with self.assertRaisesRegex(ValueError, 'audit changed'):
            b.runtime_binding(packet, 'C', b.ACCEPTED)

    def test_fresh_output_preserves_failure_history(self):
        args = self.args();b.prepare(args)
        original=(args.output/'preparation-status.json').read_bytes()
        with self.assertRaises(FileExistsError):b.prepare(args)
        self.assertEqual(original,(args.output/'preparation-status.json').read_bytes())

    def test_unknown_complete_packet_cannot_trigger_hash_or_server(self):
        with self.assertRaisesRegex(ValueError, 'producer packet schema'):
            b.assemble({'schema':'untrusted-pass'}, 'a'*40, 'b'*40, {})



class ActualProducerShapeTests(unittest.TestCase):
    def test_structured_toolchain_and_escaped_versions_preserve_raw_identity(self):
        import copy
        identity = {'java':'/jdk/bin/java','cargo':'/rust/bin/cargo','rustc':'/rust/bin/rustc',
                    'target':'aarch64-apple-darwin','rustcVersion':'rustc 1.93.0\\nhost: aarch64-apple-darwin\\n',
                    'cargoVersion':'cargo 1.93.0\\n'}
        value={'toolchainIdentity':identity,'buildArgv':['/rust/bin/cargo','build','--release','--locked',
               '--jobs','2','-p','graphite-cli','--target','aarch64-apple-darwin']}
        original=copy.deepcopy(value)
        self.assertEqual(identity,b.native_build_identity(value));self.assertEqual(original,value)
        for index, replacement in [(0,'/foreign/cargo'),(5,'8'),(9,'foreign-target')]:
            wrong=copy.deepcopy(value);wrong['buildArgv'][index]=replacement
            with self.assertRaises(ValueError):b.native_build_identity(wrong)

    def test_absolute_source_names_normalize_without_rewriting_or_alias_collisions(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve();source={'root':str(root),'files':{str(root/'a.rs'):'a'*64}}
            original=copy.deepcopy(source)
            self.assertEqual({'a.rs':'a'*64},b.normalize_source_files(source));self.assertEqual(original,source)
            for files in [{str(root/'a.rs'):'a'*64,'a.rs':'a'*64},{'../escape':'a'*64}]:
                with self.assertRaises(ValueError):b.normalize_source_files({'root':str(root),'files':files})

    def candidate_fixture(self):
        p=b.pressure; digest='a'*64
        refs={key:{'path':'/fixture/'+key+'.json','sha256':digest} for key in
              ['producer','artifactAudit','schemaAudit','artifactAuditor','schemaAuditor','source','runtime','fixture','plan','derived']}
        graphs=[{'id':f'g{i}','path':f'/graphs/g{i}'} for i in range(64)]
        arm={'revision':'b'*40,'graphs':graphs,'sourceManifest':refs['source'],'runtimeManifest':refs['runtime']}
        runtime={'revision':'b'*40,'rawBuildArgv':['/rust/bin/cargo','build']}
        final={k:'PASS' for k in ['source','inputs','runtime','fixtures','originalArtifacts','configuration']}
        cases=[{'id':f'other{i}','status':'PASS'} for i in range(5)]+[{'id':'schema-key-histogram',
                'status':'CAPTURED_UNVERIFIED','bodySha256':digest,
                'validation':{'capturedCanonicalSha256':'b'*64,'capturedRows':28}}]
        producer={'schema':'graphite.current-b-producer.v1','status':'ARTIFACTS_COMPLETE_ORACLE_AUTHORITY_PENDING',
                  'revision':arm['revision'],'acceptanceEligible':False,'runtimeManifest':runtime,
                  'correctness':{'responses':cases},'finalIdentity':final}
        artifact={'schema':'graphite.current-b-independent-artifact-audit.v1','status':'VERIFIED_ARTIFACTS_SCHEMA_ORACLE_PENDING',
                  'revision':arm['revision'],'acceptanceEligible':False,'producerPacket':refs['producer'],
                  'sourceManifest':refs['source'],'runtimeManifest':refs['runtime'],'fixtureManifest':refs['fixture'],
                  'graphs':graphs,'completeAssertions':5,'pendingSchemaOracle':True,'allCorrectnessPassed':False,
                  'correctness':producer['correctness'],'finalIdentity':final,'auditor':refs['artifactAuditor'],'caseEvidence':cases}
        schema={'status':'PASS_INDEPENDENT_COMPLETED_SCHEMA_OUTPUT_AUDIT','graphs':64,
                'completeOrderedResponseAndProvenanceEqual':True,'metadataSourceBeforeAfterStable':True,
                'producerOriginalPartialStatusPreserved':True,'auditor':refs['schemaAuditor'],
                'plan':refs['plan'],'actualDerivationAudit':refs['derived'],'expectedCanonicalSha256':'b'*64,
                'completeRows':28,'checkedMetadataAndSourcePins':{v['path']:v['sha256'] for v in refs.values()}}
        plan={'revision':arm['revision'],'performanceClaim':False,'graphs':graphs,'pins':{'/graphs/g0/graph.types':digest},
              'query':'MATCH (n) UNWIND keys(n) AS k RETURN k, count(*) AS c ORDER BY c DESC LIMIT 50',
              'capturedBody':{'path':'/fixture/native-correctness/schema-key-histogram.body','sha256':digest}}
        derived={'status':'PASS_INDEPENDENT_PERSISTED_NODE_SCHEMA_ORACLE','planSha256':digest,'errors':[],
                 'graphs':64,'expectedCanonicalSha256':'b'*64}
        docs={refs[key]['path']:value for key,value in [('producer',producer),('artifactAudit',artifact),
              ('schemaAudit',schema),('runtime',runtime),('plan',plan),('derived',derived)]}
        packet={'candidateAuthority':{k:refs[k] for k in ['producer','artifactAudit','schemaAudit','artifactAuditor','schemaAuditor']},
                'arms':{'B':arm},'buildAudits':{'B':refs['artifactAudit']},'fixtureAudits':{'B':refs['artifactAudit']},
                'pins':{**{v['path']:v['sha256'] for v in refs.values()},**plan['pins']}}
        return packet,docs,refs

    def test_partial_b_requires_independent_artifact_and_schema_authorities(self):
        packet,docs,refs=self.candidate_fixture();original=copy.deepcopy(docs)
        with patch.object(b.pressure,'sha',side_effect=lambda path:packet['pins'][str(path)]), \
             patch.object(b.pressure,'read',side_effect=lambda path:docs[str(path)]), \
             patch('subprocess.Popen',side_effect=AssertionError('no child')):
            authority=b.pressure.candidate_authority(packet)
        self.assertEqual({'digest':'b'*64,'rows':28,'proof':refs['schemaAudit'],
                          'query':docs[refs['plan']['path']]['query']},authority)
        self.assertEqual(original,docs)
        self.assertEqual('ARTIFACTS_COMPLETE_ORACLE_AUTHORITY_PENDING',docs[refs['producer']['path']]['status'])

    def test_partial_b_rejects_missing_wrong_or_unrelated_completion(self):
        mutations=[('schemaAudit','status','PASS'),('derived','status','FAIL'),('derived','graphs',63),
                   ('derived','expectedCanonicalSha256','c'*64),('plan','revision','c'*40),
                   ('artifactAudit','allCorrectnessPassed',True),('producer','status','PASS'),
                   ('schemaAudit','checkedMetadataAndSourcePins',{}),('schemaAudit','auditor',{'path':'/unreviewed','sha256':'a'*64})]
        for key,field,value in mutations:
            packet,docs,refs=self.candidate_fixture();docs[refs[key]['path']][field]=value
            with self.subTest(key=key,field=field),patch.object(b.pressure,'sha',side_effect=lambda path:packet['pins'][str(path)]), \
                 patch.object(b.pressure,'read',side_effect=lambda path:docs[str(path)]),self.assertRaises((ValueError,KeyError)):
                b.pressure.candidate_authority(packet)

    def test_explicit_missing_authority_never_becomes_plan_or_performance_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);producer=root/'packet.json';producer.write_text('{"pins":{}}')
            args=SimpleNamespace(output=root/'out',provenance=root/'absent',fixture=root/'absent',
                                 base_sha=b.ACCEPTED,candidate_sha='b'*40,producers=producer)
            with patch.object(b,'assemble',side_effect=b.MissingAuthority('Complete core proof remains pending')):
                result=b.prepare(args)
            self.assertEqual('UNAVAILABLE',result['status']);self.assertFalse(result['passed'])
            self.assertFalse(result['performanceAcceptance']);self.assertFalse((root/'out/plan.json').exists())

class IndependentCaseAuthorityTests(unittest.TestCase):
    def fixture(self, root):
        from test_native_legal_response import LegalResponseTest
        case, value, body = LegalResponseTest().fixture()
        case.update(sourceKind='independent-persisted-graph-oracle-v1', oracleKind='native-complete-legal-limit-multiset-v1')
        expected={'digest':b.pressure.digest_bytes(b.pressure.canonical(value)), 'rows':50}
        pins={'/native':'a'*64}
        def put(name, value):
            path=root/name;path.write_text(json.dumps(value));ref={'path':str(path),'sha256':b.pressure.sha(path)}
            pins[str(path)]=ref['sha256'];return ref
        want=put('expected.json',value);actual=put('body.json',body)
        graphs=[{'id':name,'path':'/graphs/'+name} for name in case['targetGraphIds']]
        source_ref=put('source.json',{'revision':'c'*40})
        runtime_ref=put('runtime.json',{'revision':'c'*40})
        arm={'revision':'c'*40,'serverArgv':['/native','serve'],'graphs':graphs,'runtimeFiles':{'/native':'a'*64},
             'sourceManifest':source_ref,'runtimeManifest':runtime_ref,'readiness':{'expected':{'count':2}}}
        executed=copy.deepcopy(case);executed['expected']={'C':want}
        plan={'arms':{'C':{**arm,'binary':'/native'}},'pins':dict(pins),'graphIds':case['targetGraphIds'],'cases':{case['id']:executed}}
        plan_ref=put('plan.json',plan)
        audit={'status':'PASS_EIGHT_COMPLETE_NATIVE_DATAFLOW_LEGAL_RESPONSES_INDEPENDENT_RAW_AUDIT','performanceClaim':False,
               'pins':{r['path']:r['sha256'] for r in [want,actual,plan_ref]},
               'proofs':[{'arm':'C','case':case['id'],'expectedSha256':want['sha256'],'bodySha256':actual['sha256'],'rows':50}]}
        audit_ref=put('audit.json',audit)
        refs={'plan':plan_ref,'audit':audit_ref,'expected':want,'body':actual}
        packet={'arms':{'C':arm,'A':copy.deepcopy(arm)},'pins':pins,'independentCaseOracles':{'C':{case['id']:refs}}}
        return packet,case,expected,put

    def test_actual_scope_and_exact_accepted_alias_bind_without_launch(self):
        with tempfile.TemporaryDirectory() as d,patch('subprocess.Popen',side_effect=AssertionError('no child')):
            packet,case,expected,_=self.fixture(Path(d))
            for arm in ('C','A'):
                result=b.independent_case_oracle(packet,case,arm,expected)
                self.assertEqual(expected['digest'],result['digest']);self.assertEqual(50,result['rows'])
                self.assertEqual('native-complete-legal-limit-multiset-v1',result['kind'])
                self.assertIn('valueProof',result)

    def test_missing_source_identity_never_authorizes_parent_alias(self):
        with tempfile.TemporaryDirectory() as d:
            packet,case,expected,_=self.fixture(Path(d))
            for arm in ('C','A'):packet['arms'][arm].pop('sourceManifest')
            self.assertFalse(b.pressure.same_accepted_artifacts(packet,'A'))
            with self.assertRaises(b.MissingAuthority):b.independent_case_oracle(packet,case,'A',expected)

    def test_parent_revision_cannot_reuse_accepted_oracle(self):
        with tempfile.TemporaryDirectory() as d:
            packet,case,expected,_=self.fixture(Path(d));packet['arms']['A']['revision']='d'*40
            with self.assertRaises(b.MissingAuthority):b.independent_case_oracle(packet,case,'A',expected)

    def test_runtime_fixture_query_and_input_closure_mismatch_rejected(self):
        mutations=[lambda p,c:p['arms']['C'].update(revision='d'*40),
                   lambda p,c:p['arms']['C']['graphs'][0].update(path='/other'),
                   lambda p,c:p['arms']['C']['runtimeFiles'].update({'/native':'b'*64}),
                   lambda p,c:p['pins'].pop('/native'),
                   lambda p,c:c['request']['body'].update(query='RETURN 1'),
                   lambda p,c:c.update(targetGraphIds=['g1','g3'])]
        for i,mutation in enumerate(mutations):
            with self.subTest(mutation=i),tempfile.TemporaryDirectory() as d:
                packet,case,expected,_=self.fixture(Path(d));mutation(packet,case)
                with self.assertRaises(ValueError):b.independent_case_oracle(packet,case,'C',expected)

    def test_receipt_status_or_broken_payload_link_rejected_even_when_repinned(self):
        for field,value in [('status','PASS'),('proofs',[]),('performanceClaim',True)]:
            with self.subTest(field=field),tempfile.TemporaryDirectory() as d:
                packet,case,expected,put=self.fixture(Path(d));refs=packet['independentCaseOracles']['C'][case['id']]
                audit=b.pressure.read(refs['audit']['path']);audit[field]=value;refs['audit']=put('audit.json',audit)
                with self.assertRaises(ValueError):b.independent_case_oracle(packet,case,'C',expected)

    def test_catalog_expected_cannot_be_replaced_by_observed_body_digest(self):
        with tempfile.TemporaryDirectory() as d:
            packet,case,expected,_=self.fixture(Path(d));refs=packet['independentCaseOracles']['C'][case['id']]
            expected['digest']=b.pressure.digest_bytes(b.pressure.canonical(b.pressure.read(refs['body']['path'])))
            with self.assertRaisesRegex(ValueError,'expected payload'):
                b.independent_case_oracle(packet,case,'C',expected)


class PreparationControlClosureTests(unittest.TestCase):
    def test_manifest_pins_legal_compiler_and_rejects_unreviewed_edit(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);scripts=root/'.github/scripts';scripts.mkdir(parents=True)
            compiler=scripts/'native_legal_response.py';compiler.write_text('reviewed source')
            manifest=scripts/'multigraph-pressure-preparation-controls.sha256'
            manifest.write_text(b.pressure.sha(compiler)+'  .github/scripts/native_legal_response.py\n')
            with patch.object(b,'SCRIPTS',scripts):
                pins=b.preparation_control_pins()
                self.assertEqual({str(manifest.resolve()),str(compiler.resolve())},set(pins))
                compiler.write_text('changed after review')
                with self.assertRaisesRegex(ValueError,'control changed'):b.preparation_control_pins()

    def test_manifest_rejects_duplicate_and_escaping_entries(self):
        for name in ('../outside.py','.github/scripts/compiler.py'):
            with self.subTest(name=name),tempfile.TemporaryDirectory() as d:
                root=Path(d);scripts=root/'.github/scripts';scripts.mkdir(parents=True)
                compiler=scripts/'compiler.py';compiler.write_text('source')
                valid=b.pressure.sha(compiler)+'  .github/scripts/compiler.py\n'
                (scripts/'multigraph-pressure-preparation-controls.sha256').write_text(valid+b.pressure.sha(compiler)+'  '+name+'\n')
                with patch.object(b,'SCRIPTS',scripts),self.assertRaises(ValueError):b.preparation_control_pins()


if __name__ == '__main__':
    unittest.main()
