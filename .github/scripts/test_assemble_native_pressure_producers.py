"""Evidence handoff correctness only; no real server, build or performance run."""
import copy
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import assemble_native_pressure_producers as bundle
import prepare_native_pressure_plan as preparation
import test_native_query_correctness as query_fixtures


class AuditedArmTests(unittest.TestCase):
    def setUp(self):
        self.fixture = query_fixtures.QueryCorrectnessTests()
        self.fixture.revision = getattr(self, 'revision', 'b' * 40)
        self.fixture.role = getattr(self, 'role', 'candidate')
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        q = self.fixture
        self.root = q.fixture.out
        if getattr(self, 'construction_capture', False):
            q.fixture.construction_capture()
        q.artifact_path = self.root / 'artifact-audit.json'
        q.fixture.write(q.artifact_path, q.fixture.run_audit())
        q.out = self.root / 'query-correctness'
        q.out.mkdir()
        q.plan = query_fixtures.runner.prepare(q.artifact_path, q.fixture.inputs_path,
                                              q.fixture.revision, q.fixture.role, q.out, 22840)
        q.run_queries()
        q.fixture.write(self.root / 'query-correctness-audit.json', bundle.queries.audit(q.out))
        self.source_inputs = bundle.artifacts.ref(q.fixture.inputs_path)
        self.no_child = patch('subprocess.Popen', side_effect=AssertionError('no child allowed'))
        self.no_child.start()
        self.addCleanup(self.no_child.stop)

    def read_arm(self):
        return bundle.audited_arm(self.root, self.fixture.fixture.revision,
                                  self.fixture.fixture.role, self.source_inputs)

    def test_full_current_arm_preserves_all39_requests_responses_and_scope(self):
        arm, pins, identity = self.read_arm()
        self.assertEqual(arm['revision'], 'b' * 40)
        self.assertEqual(len(arm['graphs']), 64)
        self.assertEqual(len(arm['cases']), 39)
        self.assertEqual([c['id'] for c in arm['cases']], [c['id'] for c in self.fixture.plan['cases']])
        self.assertEqual(arm['readiness'], self.fixture.plan['readiness'])
        self.assertEqual(identity, self.fixture.fixture.runtime['toolchainIdentity'])
        for actual, expected in zip(arm['cases'], self.fixture.plan['cases']):
            self.assertEqual(actual['request'], expected['request'])
            self.assertEqual(actual['targetGraphIds'], expected['targetGraphIds'])
            self.assertEqual(actual['oracle'], expected['oracleByArm']['B'])
            self.assertEqual(pins[actual['observed']['body']['path']], actual['observed']['body']['sha256'])

    def test_forged_stored_response_audit_fails_raw_recomputation(self):
        p = self.root / 'query-correctness-audit.json'
        value = bundle.common.read(p)
        value['cases'][0]['canonicalSha256'] = 'f' * 64
        self.fixture.fixture.write(p, value)
        with self.assertRaisesRegex(ValueError, 'recomputed raw query evidence'):
            self.read_arm()

    def test_query_success_cannot_be_promoted_to_semantic_equivalence(self):
        p = self.root / 'query-correctness-audit.json'
        value = bundle.common.read(p)
        value['completeSemanticEquivalence'] = True
        self.fixture.fixture.write(p, value)
        with self.assertRaisesRegex(ValueError, 'partial proof scope'):
            self.read_arm()

    def test_different_source_manifest_cannot_be_joined(self):
        self.source_inputs = dict(self.source_inputs, sha256='f' * 64)
        with self.assertRaisesRegex(ValueError, 'same source input manifest'):
            self.read_arm()


class CapturedConstructionArmTests(AuditedArmTests):
    construction_capture = True

    def test_resource_pins_survive_assembly_without_acceptance(self):
        arm,pins,_=self.read_arm()
        path=self.root/'prepare-real64/time-v.log'
        self.assertEqual(bundle.common.sha(path),pins[str(path)])
        artifact=bundle.common.read(self.root/'artifact-audit.json')
        self.assertEqual(63.5,artifact['constructionResources']['totalCpuSeconds'])
        self.assertFalse(artifact['performanceAcceptance']);self.assertFalse(artifact['acceptanceEligible'])
        self.assertEqual(39,len(arm['cases']));self.assertEqual(64,len(arm['graphs']))


class BundleAssemblyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.base = bundle.artifacts.ACCEPTED
        self.candidate = 'b' * 40
        self.put('source-inputs.json', {'test': 'metadata-only'})
        self.directories()
        no_child = patch('subprocess.Popen', side_effect=AssertionError('child forbidden'))
        no_child.start()
        self.addCleanup(no_child.stop)

    def put(self, name, value):
        path = self.root / name
        path.write_text(json.dumps(value))
        return path

    def directories(self, mapping=None):
        self.put('arm-directories.json', {
            'schema': 'graphite.native-artifact-arm-directories.v1',
            'directories': mapping or {'C': 'C', 'A': 'C', 'B': 'B'},
            'acceptedRevision': bundle.artifacts.ACCEPTED, 'baseRevision': self.base,
            'candidateRevision': self.candidate,
            'status': 'ARTIFACTS_ONLY_INDEPENDENT_PROOFS_REQUIRED', 'performanceAcceptance': False})

    @staticmethod
    def arm(root, revision, role, inputs):
        # Only directory composition is mocked here; AuditedArmTests exercises
        # the real artifact/query handoff with every versioned response payload.
        return {'revision': revision, 'artifactRole': role, 'cases': [{'id': str(i)} for i in range(39)]}, {}, {'compiler': 'same'}

    def assemble(self):
        with patch.object(bundle, 'audited_arm', side_effect=self.arm) as audit:
            value = bundle.assemble(self.root, self.base, self.candidate)
        return value, audit

    def test_exact_accepted_parent_reuses_audited_artifacts_without_relabelling_origin(self):
        value, audit = self.assemble()
        self.assertEqual([call.args[0].name for call in audit.call_args_list], ['C', 'B'])
        self.assertEqual(value['arms']['A']['revision'], bundle.artifacts.ACCEPTED)
        self.assertEqual(value['arms']['A']['artifactRole'], 'accepted-baseline')
        self.assertEqual(value['arms']['A']['role'], 'parent')
        self.assertEqual(value['status'], bundle.STATUS)
        for key in ('completeSemanticEquivalence', 'performanceAcceptance', 'acceptanceEligible'):
            self.assertIs(value[key], False)
        self.assertTrue(value['missingAuthority'])

    def test_corrected_mode_requires_every_distinct_arm_audit(self):
        self.base='a'*40;self.directories({'C':'C','A':'A','B':'B'})
        (self.root/'B/core-equivalence').mkdir(parents=True)
        (self.root/'B/core-equivalence/audit.json').write_text('{}')
        value,_=self.assemble()
        self.assertEqual(bundle.STATUS,value['status'])
        with patch.object(bundle,'corrected_comparison',return_value={'bound':'actual pair'}) as compare:
            (self.root/'A/core-equivalence').mkdir(parents=True)
            (self.root/'A/core-equivalence/audit.json').write_text('{}')
            value,_=self.assemble()
        self.assertEqual(['A','B'],[c.args[1] for c in compare.call_args_list])
        self.assertEqual(bundle.CORRECTED_STATUS,value['status'])
        self.assertEqual([],value['missingAuthority'])
        for key in ('completeSemanticEquivalence','strictEquivalence','sourceToDeclarationCompletenessClaim'):
            self.assertFalse(value[key])

    def test_cli_status_preserves_partial_or_corrected_packet_result(self):
        for index,status in enumerate((bundle.STATUS,bundle.CORRECTED_STATUS)):
            output=self.root/('cli-'+str(index))
            result={'status':status,'missingAuthority':['pending'] if status==bundle.STATUS else [],
                    'performanceAcceptance':False}
            argv=['assemble','--artifacts',str(self.root),'--base-sha',self.base,
                  '--candidate-sha',self.candidate,'--output',str(output)]
            with patch.object(bundle.sys,'argv',argv),patch.object(bundle,'assemble',return_value=result):
                self.assertEqual(0,bundle.main())
            saved=bundle.common.read(output/'assembly-status.json')
            self.assertEqual(status,saved['status']);self.assertEqual(result['missingAuthority'],saved['missingAuthority'])
            self.assertFalse(saved['performanceAcceptance'])
            self.assertEqual(bundle.artifacts.ref(output/'packet.json'),saved['packet'])

    def test_distinct_parent_requires_its_own_audit(self):
        self.base = 'a' * 40
        self.directories({'C': 'C', 'A': 'A', 'B': 'B'})
        value, audit = self.assemble()
        self.assertEqual([call.args[0].name for call in audit.call_args_list], ['C', 'A', 'B'])
        self.assertEqual(value['arms']['A']['revision'], self.base)
        self.assertEqual(value['arms']['A']['artifactRole'], 'parent')

    def test_wrong_parent_alias_is_rejected_before_any_audit(self):
        self.base = 'a' * 40
        self.directories()
        with patch.object(bundle, 'audited_arm', side_effect=AssertionError('must reject before audit')):
            with self.assertRaisesRegex(ValueError, 'permitted A/C alias'):
                bundle.assemble(self.root, self.base, self.candidate)

    def test_mismatched_toolchains_do_not_form_a_comparison(self):
        def different(root, revision, role, inputs):
            arm, pins, _ = self.arm(root, revision, role, inputs)
            return arm, pins, {'compiler': root.name}
        with patch.object(bundle, 'audited_arm', side_effect=different):
            with self.assertRaisesRegex(ValueError, 'matched native build toolchains'):
                bundle.assemble(self.root, self.base, self.candidate)

    def test_conflicting_pins_never_overwrite_earlier_authority(self):
        pins = {str(self.root / 'same'): 'a' * 64}
        with self.assertRaisesRegex(ValueError, 'conflicting evidence pin'):
            bundle.merge_pins(pins, {str(self.root / 'same'): 'b' * 64})
        self.assertEqual(pins[str(self.root / 'same')], 'a' * 64)

    def test_promoted_bundle_status_or_claim_cannot_pass_verification(self):
        value, _ = self.assemble()
        for key, replacement in [('status', 'PASS'), ('completeSemanticEquivalence', True),
                                 ('performanceAcceptance', True), ('missingAuthority', [])]:
            wrong = copy.deepcopy(value)
            wrong[key] = replacement
            with self.subTest(key=key), patch.object(bundle, 'assemble', return_value=value):
                with self.assertRaisesRegex(ValueError, 'current raw evidence'):
                    bundle.verify_bundle(wrong, self.base, self.candidate)

    def test_preparation_records_completed_inputs_but_does_not_create_a_pressure_plan(self):
        value, _ = self.assemble()
        args = SimpleNamespace(output=self.root / 'prepared', provenance=self.root / 'absent',
                               fixture=self.root / 'absent', base_sha=self.base, candidate_sha=self.candidate,
                               producers=self.put('bundle.json', value))
        with patch.object(bundle, 'verify_bundle', return_value=value):
            result = preparation.prepare(args)
        self.assertEqual(result['status'], 'UNAVAILABLE')
        self.assertFalse(result['passed'])
        self.assertFalse(result['performanceAcceptance'])
        self.assertEqual(result['availableInputs']['matchedNativeBundle']['auditedCasesByArm'],
                         {'C': 39, 'A': 39, 'B': 39})
        self.assertEqual(result['missingProducers'], value['missingAuthority'])
        self.assertFalse((args.output / 'plan.json').exists())


class CorrectedComparisonTests(unittest.TestCase):
    """Only the existing upstream execution auditor is stubbed; all new joins are real."""
    def setUp(self):
        import run_native_core_equivalence as core
        self.core = core
        self.temp = tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.pins = {}
        def put(relative, value):
            path = self.root / relative;path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(value));ref = bundle.artifacts.ref(path)
            self.pins[ref['path']] = ref['sha256'];return ref
        self.put = put
        arms = {}
        for arm, revision in [('C', bundle.artifacts.ACCEPTED), ('B', 'b'*40)]:
            refs = {key: put(arm+'/'+key+'.json', {'arm': arm, 'kind': key}) for key in
                    ('artifactAudit','sourceManifest','runtimeManifest','fixtureManifest')}
            arms[arm] = dict(refs, revision=revision, graphs=[{'id':f'g{i:02}', 'path':str(self.root/arm/f'g{i:02}')} for i in range(64)])
        rows = [];receipts = []
        for c,b in zip(arms['C']['graphs'],arms['B']['graphs']):
            rows.append({'id':c['id'],'C':c['path'],'B':b['path']})
            inputs = [put('raw/'+c['id']+'-'+arm+'.json', {'raw':arm}) for arm in ('C','B')]
            raw = put('proof/graphs/'+c['id']+'/core/raw-local-type-proof.json', {
                'status':'PASS_ALL_PERSISTED_ARRAY_LOCALS_RAW_TYPE','completeNodeInventory':True,'unprovedCount':0,
                'strictEquivalence':False,'completeSemanticEquivalence':False,'performanceAcceptance':False,
                'localCount':1,'arrayCount':1,'occurrences':[{'graphId':c['id'],'status':'PASS_ARRAY','issues':[]}],
                'inputs':{v['path']:v['sha256'] for v in inputs}})
            receipts.append({'id':c['id'],'files':{raw['path']:raw['sha256']}})
        self.plan = {'rawLocalExports':[{'stage':i} for i in range(8)],'maxOwnedPhases':139,'graphs':rows}
        plan = put('proof/plan.json', self.plan)
        record = put('proof/record.json', {'plan':plan})
        self.audit = {'schema':'graphite.native-core-equivalence-audit.v1','status':core.AUDIT_PASS,
            'completeCoreTopologyIndexComparison':True,'productionFormatterTestsVerified':True,
            'proofModel':'EXPLICIT_CLASSFILE_AND_FORMATTER_SOURCE_CORRECTIONS_WITH_ADDITIVE_DECLARATION_VALIDITY',
            **{k:False for k in core.FALSE_CLAIMS},'revisions':{k:a['revision'] for k,a in arms.items()},
            'fixtureManifests':{k:a['fixtureManifest'] for k,a in arms.items()},'record':record,
            'phases':139,'graphs':receipts,'pins':dict(self.pins),**{k:0 for k in core.COUNTS}}
        self.path = self.root/'proof/audit.json';self.path.write_text(json.dumps(self.audit))
        self.packet = {'arms':arms,'pins':{}}
        self.audit_mock = patch.object(core,'audit',side_effect=lambda _:copy.deepcopy(self.audit))
        self.replayed = self.audit_mock.start();self.addCleanup(self.audit_mock.stop)
        no_child = patch('subprocess.Popen',side_effect=AssertionError('no child'))
        no_child.start();self.addCleanup(no_child.stop)

    def check(self):
        self.path.write_text(json.dumps(self.audit))
        return bundle.corrected_comparison(self.packet,'B',self.path)

    def repin(self, path, value):
        path.write_text(json.dumps(value));self.audit['pins'][str(path)] = bundle.common.sha(path)

    def test_complete64_exact_pair_raw_proofs_and_corrections_are_bound_without_promotion(self):
        result = self.check()
        self.replayed.assert_called_once_with(self.path.parent)
        self.assertEqual(64,len(result['rawArrayProofs']))
        self.assertEqual(self.audit['fixtureManifests'],result['fixtureManifests'])
        self.assertEqual({k:0 for k in self.core.COUNTS},result['correctionCounts'])
        self.assertEqual(bundle.common.sha(self.path),self.packet['pins'][str(self.path)])
        self.assertTrue(all(self.audit[k] is False for k in self.core.FALSE_CLAIMS))

    def test_forged_audit_status_cannot_bypass_actual_replay(self):
        self.path.write_text(json.dumps(dict(self.audit,phases=129)))
        with self.assertRaisesRegex(ValueError,'raw complete comparison audit changed'):
            bundle.corrected_comparison(self.packet,'B',self.path)

    def test_audited_allocation_classification_is_bound_without_array_or_global_promotion(self):
        # This adapter test stubs upstream replay; the real creation proof and
        # source closure are exercised through graph_result in raw_local_types_test.
        path=self.root/'proof/graphs/g00/core/raw-local-type-proof.json';value=bundle.common.read(path)
        value.update(arrayCount=0,typedAllocationCount=1)
        value['occurrences'][0]['status']='PASS_TYPED_ALLOCATION'
        self.repin(path,value);self.audit['graphs'][0]['files'][str(path)]=bundle.common.sha(path)
        result=self.check()
        self.replayed.assert_called_once_with(self.path.parent)
        self.assertEqual(64,len(result['rawArrayProofs']))
        self.assertTrue(all(self.audit[k] is False for k in self.core.FALSE_CLAIMS))

    def test_wrong_revision_manifest_missing_graph_or_upstream_is_rejected(self):
        original=copy.deepcopy(self.audit)
        changes=[lambda a:a['revisions'].update(B='e'*40),
                 lambda a:a['fixtureManifests'].update(B=a['fixtureManifests']['C']),
                 lambda a:a['graphs'].pop(),
                 lambda a:a['pins'].pop(self.packet['arms']['B']['artifactAudit']['path']),
                 lambda a:a.update(completeSemanticEquivalence=True)]
        for change in changes:
            self.audit=copy.deepcopy(original);change(self.audit)
            with self.subTest(change=change),self.assertRaises(ValueError):self.check()

    def test_129_without_raw_exports_cannot_qualify(self):
        ref=self.audit['record'];record=bundle.common.read(ref['path']);plan_ref=record['plan']
        plan=copy.deepcopy(self.plan);plan.pop('rawLocalExports');plan['maxOwnedPhases']=129
        self.repin(Path(plan_ref['path']),plan);record['plan']=bundle.artifacts.ref(plan_ref['path'])
        self.repin(Path(ref['path']),record);self.audit['record']=bundle.artifacts.ref(ref['path']);self.audit['phases']=129
        with self.assertRaisesRegex(ValueError,'actual raw Local phases'):self.check()

    def test_raw_conflict_incomplete_inventory_or_rebound_export_never_qualifies(self):
        path=self.root/'proof/graphs/g00/core/raw-local-type-proof.json';original=bundle.common.read(path)
        original_audit=copy.deepcopy(self.audit)
        changes=[lambda r:r.update(unprovedCount=1),lambda r:r.update(completeNodeInventory=False),
                 lambda r:r.update(typedAllocationCount=1),
                 lambda r:r['occurrences'][0].update(status='PASS_TYPED_ALLOCATION'),
                 lambda r:r['occurrences'][0].update(issues=['typed allocation requires creation-order proof']),
                 lambda r:r['inputs'].clear(),lambda r:r['occurrences'][0].update(graphId='other')]
        for change in changes:
            self.audit=copy.deepcopy(original_audit);value=copy.deepcopy(original);change(value)
            self.repin(path,value)
            self.audit['graphs'][0]['files'][str(path)]=bundle.common.sha(path)
            with self.subTest(change=change),self.assertRaises(ValueError):self.check()


class CorrectedPlanTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name).resolve();arms={};pins={}
        for name,revision,role in [('C',bundle.artifacts.ACCEPTED,'accepted-baseline'),('B','b'*40,'candidate')]:
            fixture=AuditedArmTests();fixture.revision=revision;fixture.role=role;fixture.setUp()
            self.addCleanup(fixture.doCleanups)
            arm,evidence,_=fixture.read_arm();arms[name]=dict(arm,role=role)
            bundle.merge_pins(pins,evidence)
        arms['A']=dict(arms['C'],role='parent')
        # The raw comparison join is tested above; this tests its consumption with
        # actual tiny artifact/query receipts and all39 original response payloads.
        comparison=self.root/'comparison.json';comparison.write_text('{}')
        ref=bundle.artifacts.ref(comparison);pins[ref['path']]=ref['sha256']
        self.packet={'schema':bundle.SCHEMA,'status':bundle.CORRECTED_STATUS,'baseRevision':bundle.artifacts.ACCEPTED,
            'candidateRevision':'b'*40,'arms':arms,'pins':pins,'comparisonModel':bundle.CORRECTED_MODEL,
            'correctedComparisons':{'B':{'audit':ref}},'missingAuthority':[],
            'completeSemanticEquivalence':False,'strictEquivalence':False,'sourceToDeclarationCompletenessClaim':False,
            'performanceAcceptance':False,'acceptanceEligible':False}
        self.packet_path=self.root/'packet.json';self.packet_path.write_text(json.dumps(self.packet))
        self.ref=bundle.artifacts.ref(self.packet_path)
        self.catalog=bundle.common.read(preparation.CATALOG)
        self.output=self.root/'out';self.output.mkdir()

    def make(self):
        return preparation.corrected_plan(self.packet,self.ref,self.output,self.catalog)

    def validate(self,plan):
        with patch.object(bundle,'verify_bundle',return_value=self.packet):
            return bundle.common.validate_plan(plan,self.catalog)

    def test_full39_corrected_plan_preserves_scope_oracles_readiness_and_protocol(self):
        plan=self.make();self.validate(plan);bundle.common.verify_inputs(plan)
        self.assertEqual(39,len(plan['cases']));self.assertEqual(list('CABBAC'),[c['arm'] for c in plan['cells']])
        self.assertEqual({'concurrency':4,'warmupPerCase':2,'measuredPerCase':20},plan['schedule'])
        self.assertEqual(bundle.pressure_arms(self.packet),plan['arms'])
        for name,arm in self.packet['arms'].items():
            self.assertEqual(arm['readiness']['expected'],plan['arms'][name]['readiness']['expected'])
            self.assertEqual('/api/graphs',plan['arms'][name]['readiness']['path'])
        self.assertEqual({'requestSeconds':240,'stageSeconds':3600,'readinessSeconds':300,
                          'bodyBytes':67108864,'rssIntervalSeconds':.01},plan['limits'])
        claims=plan['proofs'][-1]['bindings']
        self.assertFalse(claims['completeSemanticEquivalence']);self.assertFalse(claims['strictEquivalence'])
        self.assertFalse(claims['sourceToDeclarationCompletenessClaim'])
        self.assertEqual(bundle.CORRECTED_MODEL,claims['comparisonModel'])
        for name,arm in self.packet['arms'].items():
            self.assertEqual([c['oracle'] for c in arm['cases']],[c['oracleByArm'][name] for c in plan['cases']])

    def test_forged_scope_oracle_proof_or_missing_pair_is_rejected(self):
        plan=self.make()
        for mutate in [lambda p:p.pop('correctedProducerAuthority'),
                       lambda p:p['proofs'][-1]['bindings'].update(completeSemanticEquivalence=True),
                       lambda p:p['proofs'][-1]['bindings']['correctedComparisons'].clear(),
                       lambda p:p['proofs'][-1]['upstream'].pop(self.ref['path']),
                       lambda p:p['cases'][0]['oracleByArm']['B'].update(proof=self.ref)]:
            value=copy.deepcopy(plan);mutate(value)
            with self.subTest(mutate=mutate),self.assertRaises(ValueError):self.validate(value)

    def test_actual_preparation_branch_produces_unmeasured_plan_without_changing_upstream(self):
        args=SimpleNamespace(output=self.root/'prepared',provenance=self.root/'absent',fixture=self.root/'absent',
            base_sha=self.packet['baseRevision'],candidate_sha=self.packet['candidateRevision'],producers=self.packet_path)
        before=self.packet_path.read_bytes()
        with patch.object(bundle,'verify_bundle',return_value=self.packet):
            result=preparation.prepare(args)
        self.assertEqual('PLAN_READY_NOT_MEASURED',result['status'],result)
        self.assertFalse(result['passed']);self.assertFalse(result['performanceAcceptance'])
        self.assertTrue((args.output/'plan.json').exists())
        self.assertEqual(before,self.packet_path.read_bytes())

    def test_distinct_parent_cannot_use_accepted_alias_without_own_pair(self):
        self.packet['arms']['A']=dict(self.packet['arms']['A'],revision='a'*40)
        with self.assertRaisesRegex(ValueError,'nonaccepted arms'):bundle.corrected_fixture_bindings(self.packet)


if __name__ == '__main__':
    unittest.main()
