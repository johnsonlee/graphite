"""Full catalog execution and raw auditing with no child process or network."""
import copy
from dataclasses import replace
import json
from pathlib import Path
import struct
import signal
import tempfile
import unittest
from unittest.mock import patch, MagicMock

import audit_native_pressure_artifacts as artifacts
import audit_native_query_correctness as raw_audit
import multigraph_pressure as common
import run_native_query_correctness as runner
import native_portable_oracles as portable
import test_audit_native_pressure_artifacts as fixtures


class QueryCorrectnessTests(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.ArtifactAuditTests()
        self.fixture.revision = getattr(self, 'revision', 'b' * 40)
        self.fixture.role = getattr(self, 'role', 'candidate')
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root
        self.out = self.root / 'queries'
        self.out.mkdir()
        for graph in self.fixture.fixture['graphs']:
            directory = Path(graph['path'])
            (directory / 'forward.properties').write_text('nodes=' + str(graph['nodes']) + '\narcs=7\n')
            (directory / 'graph.nodedata').write_bytes(b'GRN\x03' + struct.pack('>i', graph['nodes']))
            (directory / 'graph.metadata').write_bytes(b'GRM\x03' + struct.pack('>i', 2))
        self.fixture.fixture['files'] = artifacts.inventory(self.fixture.out / 'graphs')
        self.fixture.write(self.fixture.out / 'fixture-manifest.json', self.fixture.fixture)
        self.artifact_path = self.root / 'artifact-audit.json'
        self.fixture.write(self.artifact_path, self.fixture.run_audit())
        # These tiny protocol fixtures are not the real JAR corpus. Keep all
        # versioned response payloads intact while bypassing only that corpus
        # match; portable-oracle tests cover rejection of different JAR hashes.
        load = portable.load
        corpus_match = patch.object(portable, 'load', side_effect=lambda **kwargs: load())
        corpus_match.start()
        self.addCleanup(corpus_match.stop)
        self.plan = runner.prepare(self.artifact_path, self.fixture.inputs_path,
                                   self.fixture.revision, self.fixture.role, self.out, 22840)

    def run_queries(self, failed_case=None, cleanup_error=False, close_error=False, final_drift=False):
        plan = self.plan
        class Process:
            pid = 12345
            def poll(self): return None
        class Transport:
            def __init__(self, port, limits): pass
            def fetch(self, case, path, record):
                oracle = case['oracleByArm'][plan['expectationVariant']]
                if oracle['kind'] == 'native-full-json-sha256-v1':
                    raw = Path(oracle['proof']['path']).read_bytes()
                else:
                    value = oracle['value'];rows = []
                    for entry in value['rows']:
                        rows.extend([entry['value']] * min(entry['multiplicity'], 50 - len(rows)))
                        if len(rows) == 50: break
                    raw = common.canonical({'columns': value['columns'], 'rows': rows, 'rowCount': len(rows),
                                            'graphCount': 64, 'total': {'value': min(value['totalMatches'], 51),
                                              'relation': 'gte' if value['totalMatches'] > 50 else 'eq'}})
                path.write_bytes(b'{}' if case['id'] == failed_case else raw)
                record.update(httpStatus=200, completeBody=True, deadlineExpired=False,
                              endpoint=case['request']['endpoint'],
                              requestBodySha256=common.digest_bytes(common.canonical(case['request']['body'])))
            def close_all(self):
                if close_error: raise OSError('transport close failed')
        def ready(*args):
            value = copy.deepcopy(plan['readiness']['expected'])
            value['data'] = plan['data']
            for graph in value['graphs']: graph['loadedAt'] = '2026-10-10T00:00:00Z'
            return common.canonical(value)
        def cleanup(*args):
            if final_drift:
                Path(plan['argv'][0]).write_bytes(b'changed during server lifetime')
            return {'group': 12345, 'after': [12345] if cleanup_error else [],
                    'errors': ['live child'] if cleanup_error else [], 'exit': -15}
        with patch.object(runner.subprocess, 'Popen', return_value=Process()), \
             patch.object(runner.socket, 'socket', return_value=MagicMock()), \
             patch.object(common, 'HTTPTransport', Transport), patch.object(common, 'readiness', side_effect=ready), \
             patch.object(common, 'stop_owned', side_effect=cleanup) as stop:
            result = runner.execute(plan, self.out)
        self.assertEqual(stop.call_count, 1)
        return result

    def test_all39_complete_responses_bind_current_revision_and_pass_independent_audit(self):
        result = self.run_queries()
        self.assertEqual(result['status'], runner.PASS)
        self.assertEqual(len(result['responses']), 39)
        self.assertEqual(result['revision'], 'b' * 40)
        self.assertNotEqual(result['revision'], self.plan['historicalExpectationRevisions']['B'])
        audited = raw_audit.audit(self.out)
        self.assertEqual(audited['status'], raw_audit.STATUS)
        self.assertEqual(len(audited['cases']), 39)
        self.assertEqual(audited['readiness']['totals']['edges'], 64 * 7)
        self.assertEqual(audited['readiness']['totals']['methods'], 64 * 2)
        self.assertFalse(audited['performanceAcceptance'])
        self.assertFalse(audited['completeSemanticEquivalence'])

    def test_measured_construction_handoff_preserves_all39_correctness_requests(self):
        self.fixture.construction_capture()
        audited_artifacts=self.fixture.run_audit(True)
        self.fixture.write(self.artifact_path,audited_artifacts)
        self.out=self.root/'queries-with-construction';self.out.mkdir()
        self.plan=runner.prepare(self.artifact_path,self.fixture.inputs_path,'b'*40,'candidate',self.out,22840)
        result=self.run_queries();audited=raw_audit.audit(self.out)
        self.assertEqual(39,len(result['responses']))
        self.assertEqual([c['id'] for c in self.plan['cases']],[c['case'] for c in audited['cases']])
        self.assertEqual(audited_artifacts['constructionResources']['rawSha256'],
                         self.plan['pins'][str(self.fixture.out/'prepare-real64/time-v.log')])
        self.assertFalse(self.plan['performanceAcceptance']);self.assertFalse(audited['performanceAcceptance'])
        self.assertFalse(audited['completeSemanticEquivalence'])

    def test_wrong_first_body_is_retained_and_stops_the_cohort(self):
        result = self.run_queries(failed_case=self.plan['cases'][0]['id'])
        self.assertEqual(result['status'], 'FAIL')
        self.assertEqual(len(result['responses']), 1)
        self.assertEqual(Path(result['responses'][0]['body']['path']).read_bytes(), b'{}')
        with self.assertRaisesRegex(ValueError, 'complete successful execution'):
            raw_audit.audit(self.out)

    def test_cleanup_failure_prevents_success_after_all39_responses(self):
        result = self.run_queries(cleanup_error=True)
        self.assertEqual(len(result['responses']), 39)
        self.assertEqual(result['status'], 'FAIL')

    def test_transport_close_error_still_stops_the_owned_server(self):
        result = self.run_queries(close_error=True)
        self.assertEqual(result['status'], 'FAIL')
        self.assertTrue(any('transport cleanup' in x for x in result['errors']))

    def test_input_mutation_after_queries_prevents_success(self):
        result = self.run_queries(final_drift=True)
        self.assertEqual(result['status'], 'FAIL')
        self.assertEqual(result['finalIdentity'], 'FAIL')

    def test_readiness_uses_saved_counts_and_rejects_bad_metadata(self):
        value = runner.persisted_readiness(self.fixture.fixture)
        self.assertEqual(value['totals']['methods'], 128)
        path = Path(self.fixture.fixture['graphs'][0]['path']) / 'graph.metadata'
        path.write_bytes(b'GRM\x03\xff\xff\xff\xff')
        with self.assertRaisesRegex(ValueError, 'persisted readiness counts'):
            runner.persisted_readiness(self.fixture.fixture)

    def test_wrong_artifact_revision_is_rejected_before_launch(self):
        with self.assertRaisesRegex(ValueError, 'requested revision/role'):
            runner.prepare(self.artifact_path, self.fixture.inputs_path, 'c' * 40, 'candidate', self.out, 22840)

    def test_sparse_node_id_capacity_is_not_the_actual_node_count(self):
        graph = self.fixture.fixture['graphs'][0]
        (Path(graph['path']) / 'forward.properties').write_text('nodes=1000000\narcs=7\n')
        value = runner.persisted_readiness(self.fixture.fixture)
        actual = next(g for g in value['graphs'] if g['id'] == graph['id'])
        self.assertEqual(actual['nodes'], graph['nodes'])
        self.assertNotEqual(actual['nodes'], 1000000)

    def test_independent_audit_rejects_mutated_raw_body(self):
        result = self.run_queries()
        body = Path(result['responses'][0]['body']['path'])
        body.write_text('{}')
        with self.assertRaisesRegex(ValueError, 'request and response bytes'):
            raw_audit.audit(self.out)

    def test_missing_response_cannot_be_a_complete_audit(self):
        self.run_queries()
        path = self.out / 'responses.jsonl'
        path.write_text('\n'.join(path.read_text().splitlines()[:-1]) + '\n')
        with self.assertRaisesRegex(ValueError, 'complete response journal'):
            raw_audit.audit(self.out)

    def test_plan_query_cannot_be_replaced_while_preserving_success_status(self):
        self.run_queries()
        plan = common.read(self.out / 'plan.json')
        plan['cases'][0]['request']['body']['query'] = 'RETURN 1'
        self.fixture.write(self.out / 'plan.json', plan)
        record = common.read(self.out / 'record.json')
        record['plan'] = artifacts.ref(self.out / 'plan.json')
        self.fixture.write(self.out / 'record.json', record)
        with self.assertRaisesRegex(ValueError, 'predetermined full query catalog'):
            raw_audit.audit(self.out)


class JvmSharedLifecycleTests(unittest.TestCase):
    """Explicitly modeled JVM authority/process; real shared response validation."""
    def setUp(self):
        import jvm_pressure_oracles as jvm
        self.jvm = jvm
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.out = Path(temporary.name).resolve()
        self.cases = jvm.cases()
        self.bodies = {}
        for case in self.cases:
            # A small complete legal universe, not a claim about any real graph.
            values = {key: None for key in case['columns']}
            if 'id' in values: values['id'] = 17
            if 'graphId' in values: values['graphId'] = case['targetGraphIds'][0]
            row = jvm.projected_row(values, [case['targetGraphIds'][0]])
            universe = {'schema': 'graphite.jvm-legal-row-universe.v1', 'policy': jvm.POLICY,
                        'caseId': case['id'], **{key: case[key] for key in (
                            'requestSha256', 'querySha256', 'registeredGraphIds', 'requestedGraphIds',
                            'targetGraphIds', 'columns', 'effectiveLimit')},
                        'totalMatches': 1, 'allGraphScansComplete': True,
                        'exactEncounterOrderClaim': False, 'rows': [{'value': row, 'multiplicity': 1}]}
            case['oracleByArm'] = {'B': {'kind': 'jvm-complete-legal-limit-multiset-v1',
                                        'value': universe, 'digest': jvm.digest(universe), 'rows': 1}}
            self.bodies[case['id']] = common.canonical({
                'mode': 'cross-graph', 'graphs': case['requestedGraphIds'], 'graphCount': 64,
                'columns': case['columns'], 'rows': [row], 'rowCount': 1, 'limit': case['effectiveLimit']})
        self.closure = MagicMock()
        self.contract = runner._ExecutionContract('jvm', 'graphite.jvm-query-correctness-plan.v1',
            runner.JVM_SCHEMA, runner.JVM_PASS, tuple(c['id'] for c in self.cases), self.closure)
        graphs = [{'id': gid, 'path': str(self.out/gid)} for gid in jvm.FIXTURE_GRAPH_IDS]
        ready_graphs = [dict(g, loadMode='MAPPED', nodes=1, edges=0, methods=0, callSites=0) for g in graphs]
        self.plan = {'schema': self.contract.plan_schema, 'revision': 'b'*40, 'role': 'candidate',
                     'cases': self.cases, 'graphs': graphs, 'expectationVariant': 'B',
                     'argv': ['modeled-java-server'], 'port': 22840, 'data': str(self.out/'data'),
                     'performanceAcceptance': False,
                     'limits': {'requestSeconds': 240, 'bodyBytes': 64*1024*1024, 'readinessSeconds': 900},
                     'readiness': {'expected': {'loadMode': 'MAPPED', 'count': 64,
                         'graphs': sorted(ready_graphs, key=lambda g: g['id']),
                         'totals': {'nodes': 64, 'edges': 0, 'methods': 0, 'callSites': 0}}}}

    def run_queries(self, failure=None, cleanup_failure=False, closure_failure=False):
        bodies = self.bodies; fetched = []; closed = []
        class Process:
            pid = 12345
            def poll(self): return None
        class Transport:
            def __init__(self, port, limits): pass
            def fetch(self, case, path, record):
                fetched.append(case['id'])
                path.write_bytes(b'{}' if failure == 'body' else bodies[case['id']])
                if failure == 'signal': signal.getsignal(signal.SIGTERM)(signal.SIGTERM, None)
                record.update(httpStatus=200, completeBody=True, deadlineExpired=False)
            def close_all(self): closed.append(True)
        value = copy.deepcopy(self.plan['readiness']['expected']); value['data'] = self.plan['data']
        for graph in value['graphs']: graph['loadedAt'] = '2026-10-10T00:00:00Z'
        if closure_failure: self.closure.side_effect = [None, ValueError('source changed during server lifetime')]
        previous = {sig: signal.getsignal(sig) for sig in (signal.SIGINT, signal.SIGTERM)}
        with patch.object(runner.subprocess, 'Popen', return_value=Process()) as launched, \
             patch.object(runner.socket, 'socket', return_value=MagicMock()), \
             patch.object(common, 'HTTPTransport', Transport), \
             patch.object(common, 'readiness', return_value=common.canonical(value)), \
             patch.object(common, 'stop_owned', return_value={'group': 12345, 'after': [12345] if cleanup_failure else [],
                                                            'errors': [], 'exit': -15}) as stopped:
            result = runner._execute_validated(self.plan, self.out, self.contract)
        self.assertEqual(1, launched.call_count); self.assertEqual(1, stopped.call_count)
        self.assertEqual([True], closed); self.assertEqual(2, self.closure.call_count)
        self.assertEqual(previous, {sig: signal.getsignal(sig) for sig in previous})
        return result, fetched

    def test_exact26_complete_bodies_and_scope_use_shared_lifecycle(self):
        result, fetched = self.run_queries()
        self.assertEqual(runner.JVM_PASS, result['status'], result['errors'])
        self.assertEqual(runner.JVM_SCHEMA, result['schema'])
        self.assertEqual(self.contract.expected_case_ids, tuple(fetched))
        self.assertEqual(26, len(result['responses']))
        for response in result['responses']:
            self.assertEqual(self.bodies[response['id']], Path(response['body']['path']).read_bytes())
            self.assertEqual(1, response['validation']['rows'])
        self.assertEqual(2, len(next(r for r in result['responses'] if r['id']=='routing-pair-dense')['targetGraphIds']))
        self.assertEqual(26, len((self.out/'responses.jsonl').read_text().splitlines()))
        self.assertFalse(result['performanceAcceptance']); self.assertFalse(result['completeSemanticEquivalence'])

    def test_wrong_body_is_retained_and_remaining25_are_not_issued(self):
        result, fetched = self.run_queries(failure='body')
        self.assertEqual('FAIL', result['status']); self.assertEqual([self.cases[0]['id']], fetched)
        self.assertEqual(b'{}', Path(result['responses'][0]['body']['path']).read_bytes())
        self.assertEqual('FAIL', result['responses'][0]['status'])
        self.assertEqual(1, len((self.out/'responses.jsonl').read_text().splitlines()))

    def test_cleanup_failure_cannot_pass_after_complete26(self):
        result, fetched = self.run_queries(cleanup_failure=True)
        self.assertEqual(26, len(fetched)); self.assertEqual('FAIL', result['status'])
        self.assertTrue(any('owned server cleanup' in e for e in result['errors']))

    def test_final_closure_failure_cannot_pass_after_complete26(self):
        result, fetched = self.run_queries(closure_failure=True)
        self.assertEqual(26, len(fetched)); self.assertEqual('FAIL', result['status'])
        self.assertEqual('FAIL', result['finalIdentity'])

    def test_signal_retains_failed_response_and_restores_handlers_after_cleanup(self):
        result, fetched = self.run_queries(failure='signal')
        self.assertEqual('FAIL', result['status']); self.assertEqual(1, len(fetched))
        self.assertIn('InterruptedError', result['responses'][0]['error'])
        self.assertEqual(self.bodies[fetched[0]], Path(result['responses'][0]['body']['path']).read_bytes())

    def test_contract_rejects_missing_duplicate_reordered_ids_and_unknown_engine(self):
        for ids in (self.contract.expected_case_ids[:-1], self.contract.expected_case_ids[::-1],
                    (self.contract.expected_case_ids[0],)*26):
            with self.subTest(ids=ids), self.assertRaisesRegex(ValueError, 'fixed engine'):
                runner._execute_validated(self.plan, self.out, replace(self.contract, expected_case_ids=ids))
        for changes in ({'engine': 'other'}, {'record_schema': runner.SCHEMA}, {'pass_status': runner.PASS}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                runner._execute_validated(self.plan, self.out, replace(self.contract, **changes))
        self.assertEqual([], list(self.out.iterdir()))
        self.closure.assert_not_called()

    def test_native_entry_and_jvm_contract_reject_wrong_plan_cases_or_oracles_before_launch(self):
        with self.assertRaisesRegex(ValueError, 'validated plan engine/scope'):
            runner.execute(self.plan, self.out)
        original = copy.deepcopy(self.plan)
        changes = [lambda p: p['cases'].pop(),
                   lambda p: p['cases'][0]['request']['body'].update(query='RETURN 1'),
                   lambda p: p['cases'][0]['oracleByArm']['B'].update(kind='native-full-json-sha256-v1'),
                   lambda p: p['limits'].update(bodyBytes=1)]
        for change in changes:
            plan = copy.deepcopy(original); change(plan)
            with self.subTest(change=change), self.assertRaises(ValueError):
                runner._execute_validated(plan, self.out, self.contract)
        self.assertEqual([], list(self.out.iterdir()))


if __name__ == '__main__':
    unittest.main()
