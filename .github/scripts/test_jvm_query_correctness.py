"""Tiny HTTP-wrapper contracts; upstream applicability/execution is modeled.

No JVM or network executes. Saved counts, JAR inspection, universe/body validation,
shared lifecycle, closure verification and response auditing run for real.
"""
import copy
import json
from pathlib import Path
import struct
import unittest
from unittest.mock import MagicMock, patch
import zipfile

import jvm_query_correctness as p
import test_native_query_correctness as shared


def write(path, value):
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2)+'\n')
    return p.artifacts.ref(path)


def main_class():
    # Minimal classfile metadata fixture; never executed by a VM.
    u2 = lambda x: struct.pack('>H', x)
    utf = lambda x: b'\1'+u2(len(x))+x
    pool = [utf(b'io/johnsonlee/graphite/cli/MainKt'), b'\7'+u2(1),
            utf(b'java/lang/Object'), b'\7'+u2(3), utf(b'main'), utf(b'([Ljava/lang/String;)V')]
    return (bytes.fromhex('cafebabe')+u2(0)+u2(52)+u2(7)+b''.join(pool)+
            b''.join(map(u2, (1, 2, 4, 0, 0, 1, 9, 5, 6, 0, 0))))


class JvmCorrectnessWrapperTests(unittest.TestCase):
    def setUp(self):
        self.base = shared.JvmSharedLifecycleTests(); self.base.setUp(); self.addCleanup(self.base.doCleanups)
        self.root = self.base.out; self.out = self.root/'http'; self.out.mkdir()
        self.raw = self.root/'raw'; self.raw.mkdir()
        producer = self.root/'producer'; runtime_dir = producer/'runtime'; runtime_dir.mkdir(parents=True)
        source_dir = self.root/'source'; source_dir.mkdir()
        self.java = self.root/'jdk/bin/java'; self.java.parent.mkdir(parents=True); self.java.write_bytes(b'nonexecuted JDK identity')
        self.jar = runtime_dir/'graphite.jar'; writer = runtime_dir/'writer.jar'; writer.write_bytes(b'sealed writer fixture')
        prefix = 'io/johnsonlee/graphite/cli/'
        self.entries = {prefix+'MainKt.class': main_class(), prefix+'GraphiteCommand.class': b'Lio/johnsonlee/graphite/cli/ServeCommand;',
            prefix+'ServeCommand.class': b'--graph --data --port --load-mode --max-concurrent-cypher --cypher-max-timeout-ms --metrics',
            prefix+'GraphRegistry.class': b'registry', prefix+'ExploreRoutes.class': b'routes',
            'picocli/CommandLine.class': b'picocli', 'io/javalin/Javalin.class': b'javalin'}
        with zipfile.ZipFile(self.jar, 'w') as archive:
            for name, raw in self.entries.items(): archive.writestr(name, raw)
        graphs = []
        for gid in p.model.FIXTURE_GRAPH_IDS:
            path = producer/'graphs'/gid; path.mkdir(parents=True)
            (path/'forward.properties').write_text('nodes=32\narcs=2\n')
            (path/'graph.nodedata').write_bytes(b'GRN\3'+struct.pack('>i', 16))
            (path/'graph.metadata').write_bytes(b'GRM\3'+struct.pack('>i', 1))
            graphs.append({'id': gid, 'path': str(path), 'nodes': 16, 'callSites': 1})
        revision = 'b'*40
        self.source_ref = write(producer/'source-manifest.json', {'root': str(source_dir), 'revision': revision})
        self.runtime = {'revision': revision, 'files': p.artifacts.inventory(runtime_dir),
                        'toolchainIdentity': {'java': str(self.java)}}
        runtime_ref = write(producer/'runtime-manifest.json', self.runtime)
        self.fixture = {'writerRevision': revision, 'graphs': graphs, 'files': p.artifacts.inventory(producer/'graphs')}
        fixture_ref = write(producer/'fixture-manifest.json', self.fixture)
        pins = {**self.runtime['files'], **self.fixture['files'], str(self.java): p.common.sha(self.java)}
        for ref in (self.source_ref, runtime_ref, fixture_ref): pins[ref['path']] = ref['sha256']
        self.raw_plan = {'revision': revision, 'role': 'candidate', 'arm': 'B', 'producerRoot': str(producer),
                         'sourceManifest': self.source_ref, 'runtimeManifest': runtime_ref, 'fixtureManifest': fixture_ref,
                         'writerJar': str(writer), 'java': str(self.java), 'graphs': [{'id': g['id']} for g in graphs], 'pins': pins}
        plan_ref = write(self.raw/'plan.json', self.raw_plan)
        universes = {}
        for case in self.base.cases:
            universe = copy.deepcopy(case['oracleByArm']['B']['value'])
            if case['family'] == 'wrapped-discovery':
                groups = p.derive.distinct.DistinctGroups()
                groups.add({column: None for column in case['columns']}, case['targetGraphIds'][0])
                universe.pop('rows')
                universe.update(schema=p.derive.distinct.SCHEMA, equalityPolicy=p.derive.distinct.EQUALITY,
                                groups=groups.finish())
            universes[case['id']] = write(self.raw/'universes'/(case['id']+'.json'), universe)
        record_ref = write(self.raw/'record.json', {'plan': plan_ref, 'outputFiles': p.artifacts.inventory(self.raw)})
        self.replayed = {'status': p.derive.PASS, 'record': record_ref, 'universes': universes,
                         'actualPrimitiveExecutionVerified': True, 'completeRawDerivationReplayed': True,
                         **{key: False for key in p.derive.FALSE_CLAIMS}}
        cases = p.model.cases()
        self.rules = {'schema': 'graphite.jvm-source-rule-applicability.v1', 'status': 'PASS_REVIEWED_JVM_SOURCE_RULE_APPLICABILITY',
            'revision': revision, 'sourceManifest': self.source_ref, 'runtimeManifest': runtime_ref,
            'packagedRuntime': {'graphiteJar': p.artifacts.ref(self.jar), 'writerJar': p.artifacts.ref(writer)},
            'requestContracts': [{key: case[key] for key in ('id', 'requestSha256', 'querySha256', 'requestedGraphIds', 'targetGraphIds')}
                                 for case in cases], 'pins': {}, 'sourceRuleApplicabilityVerified': True,
            'upstreamExecutionReplayRequired': True, 'oracleAuthorityVerified': False,
            'fresh64Acceptance': False, 'performanceAcceptance': False}
        self.order = []
        self.replay_mock = patch.object(p.derive, 'replay', side_effect=self.replay)
        self.source_mock = patch.object(p, 'source_rules', side_effect=self.rules_audit)
        self.replay_mock.start(); self.addCleanup(self.replay_mock.stop)
        self.source_mock.start(); self.addCleanup(self.source_mock.stop)

    def replay(self, path):
        self.order.append('raw-replay'); self.assertEqual(self.raw, path)
        return copy.deepcopy(self.replayed)

    def rules_audit(self, plan):
        self.order.append('source-rules'); self.assertEqual(self.raw_plan, plan)
        return copy.deepcopy(self.rules)

    def execute(self, bad_body=False, cleanup_failure=False):
        fetched = []; bodies = self.base.bodies
        class Process:
            pid = 12345
            def poll(self): return None
        class Transport:
            def __init__(self, port, limits): pass
            def fetch(self, case, path, record):
                fetched.append(case['id']); path.write_bytes(b'{}' if bad_body else bodies[case['id']])
                record.update(httpStatus=200, completeBody=True, deadlineExpired=False,
                    endpoint=case['request']['endpoint'], requestBodySha256=p.common.digest_bytes(p.common.canonical(case['request']['body'])))
            def close_all(self): pass
        def ready(port, arm, data, *args):
            value = copy.deepcopy(arm['readiness']['expected']); value['data'] = str(data)
            for graph in value['graphs']: graph['loadedAt'] = '2026-10-10T00:00:00Z'
            return p.common.canonical(value)
        with patch.object(p.lifecycle.subprocess, 'Popen', return_value=Process()) as launch, \
             patch.object(p.lifecycle.socket, 'socket', return_value=MagicMock()), \
             patch.object(p.common, 'HTTPTransport', Transport), patch.object(p.common, 'readiness', side_effect=ready), \
             patch.object(p.common, 'stop_owned', return_value={'group': 12345, 'after': [12345] if cleanup_failure else [],
                                                              'errors': [], 'exit': -15}) as stop:
            result = p.execute(self.raw, self.out, 22840)
        self.assertEqual(1, launch.call_count); self.assertEqual(1, stop.call_count)
        return result, fetched

    def test_all26_raw_expectations_then_actual_response_audit(self):
        record, fetched = self.execute(); self.assertEqual(p.lifecycle.JVM_PASS, record['status'], record['errors'])
        self.assertEqual(['raw-replay', 'source-rules'], self.order)
        result = p.audit(self.out)
        self.assertEqual(['raw-replay', 'source-rules']*2, self.order)
        self.assertEqual(p.AUDIT, result['status']); self.assertEqual(26, len(result['cases']))
        self.assertEqual([case['id'] for case in p.model.cases()], fetched)
        self.assertTrue(result['oracleAuthorityVerified']); self.assertFalse(result['fresh64Acceptance'])
        self.assertFalse(result['completeSemanticEquivalence']); self.assertFalse(result['performanceAcceptance'])
        self.assertEqual(64*16, result['readiness']['totals']['nodes'])
        plan = p.common.read(self.out/'plan.json')
        self.assertEqual(8, sum(c['oracleByArm']['B']['value']['schema'] == p.derive.distinct.SCHEMA for c in plan['cases']))
        self.assertEqual([str(self.java), '-Xmx4g', '-XX:ActiveProcessorCount=4', '-cp', str(self.jar),
                          'io.johnsonlee.graphite.cli.MainKt', 'serve'], plan['argv'][:7])
        for case in result['cases']:
            self.assertEqual(self.base.bodies[case['case']], Path(case['body']['path']).read_bytes())

    def test_execute_rejects_missing_or_nonempty_output_before_upstream_replay(self):
        for output in (self.root/'absent', self.raw):
            with self.subTest(output=output), self.assertRaisesRegex(ValueError, 'fresh empty'):
                p.execute(self.raw, output, 22840)
        self.assertEqual([], self.order)
        marker = self.out/'existing.txt'; marker.write_bytes(b'keep')
        with self.assertRaisesRegex(ValueError, 'fresh empty'): p.execute(self.raw, self.out, 22840)
        self.assertEqual(b'keep', marker.read_bytes()); self.assertEqual([], self.order)

    def test_missing_actual_upstream_execution_prevents_source_audit_and_launch(self):
        self.replayed['actualPrimitiveExecutionVerified'] = False
        with self.assertRaisesRegex(ValueError, 'independent raw derivation'):
            p.prepare(self.raw, self.out, 22840)
        self.assertEqual(['raw-replay'], self.order); self.assertEqual([], list(self.out.iterdir()))

    def test_revision_source_manifest_and_request_scope_mismatch_rejected(self):
        original = copy.deepcopy(self.rules)
        for field in ('revision', 'sourceManifest', 'requestContracts', 'sourceRuleApplicabilityVerified'):
            self.rules = copy.deepcopy(original)
            if field == 'revision': self.rules[field] = 'c'*40
            elif field == 'sourceManifest': self.rules[field]['sha256'] = '0'*64
            elif field == 'requestContracts': self.rules[field][-1]['targetGraphIds'] = p.model.FIXTURE_GRAPH_IDS[:1]
            else: self.rules[field] = False
            with self.subTest(field=field), self.assertRaises(ValueError): p.prepare(self.raw, self.out, 22840)

    def test_missing_universe_and_changed_derived_bytes_rejected(self):
        missing = self.replayed['universes'].pop(self.base.cases[0]['id'])
        with self.assertRaisesRegex(ValueError, 'complete26'): p.prepare(self.raw, self.out, 22840)
        self.replayed['universes'][self.base.cases[0]['id']] = missing
        Path(missing['path']).write_text('{}')
        with self.assertRaisesRegex(ValueError, 'authority metadata changed'): p.prepare(self.raw, self.out, 22840)

    def test_changed_sealed_package_is_rejected(self):
        self.jar.write_bytes(b'changed')
        with self.assertRaises((ValueError, zipfile.BadZipFile)): p.prepare(self.raw, self.out, 22840)

    def test_malformed_body_preserved_stops_remaining_and_cannot_audit(self):
        result, fetched = self.execute(bad_body=True)
        self.assertEqual('FAIL', result['status']); self.assertEqual(1, len(fetched))
        self.assertEqual(b'{}', Path(result['responses'][0]['body']['path']).read_bytes())
        with self.assertRaisesRegex(ValueError, 'complete successful'): p.audit(self.out)

    def test_cleanup_failure_cannot_pass_after_all26(self):
        result, fetched = self.execute(cleanup_failure=True)
        self.assertEqual(26, len(fetched)); self.assertEqual('FAIL', result['status'])
        with self.assertRaisesRegex(ValueError, 'complete successful'): p.audit(self.out)

    def test_repinning_observed_body_and_journal_never_changes_independent_expectation(self):
        result, _ = self.execute()
        response = result['responses'][0]; body = Path(response['body']['path'])
        value = p.common.read(body); value['rows'][0]['id'] = 999
        body.write_bytes(p.common.canonical(value))
        response['body'] = p.artifacts.ref(body)
        response['validation'] = {'canonicalSha256': p.common.digest_bytes(p.common.canonical(value)), 'rows': 1}
        (self.out/'responses.jsonl').write_text(''.join(p.common.canonical(r).decode()+'\n' for r in result['responses']))
        write(self.out/'record.json', result)
        with self.assertRaises(ValueError): p.audit(self.out)

    def test_tampered_full_body_journal_or_owner_cannot_audit(self):
        result, _ = self.execute(); self.assertEqual(p.lifecycle.JVM_PASS, result['status'])
        body = Path(result['responses'][0]['body']['path']); original_body = body.read_bytes(); body.write_bytes(b'{}')
        with self.assertRaisesRegex(ValueError, 'request and response bytes'): p.audit(self.out)
        body.write_bytes(original_body)
        journal = self.out/'responses.jsonl'; original_journal = journal.read_bytes()
        journal.write_text('\n'.join(journal.read_text().splitlines()[:-1])+'\n')
        with self.assertRaisesRegex(ValueError, 'complete response journal'): p.audit(self.out)
        journal.write_bytes(original_journal)
        owner = p.common.read(self.out/'owner.json'); owner['argv'] += ['--foreign']
        write(self.out/'owner.json', owner)
        with self.assertRaisesRegex(ValueError, 'MainKt server launch'): p.audit(self.out)


if __name__ == '__main__': unittest.main()
