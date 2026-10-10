"""Correctness-only tests; no child process, HTTP server or performance workload."""
import copy
import contextlib
import importlib.util
import json
from pathlib import Path
import signal
import tempfile
import threading
import unittest
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location('pressure', Path(__file__).with_name('multigraph_pressure.py'))
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
PRODUCTION_CATALOG = p.read(Path(__file__).parent / 'fixtures/multigraph-pressure-cases.json')
# Small metadata fixtures intentionally retain missing coverage for rejection tests.
# They never generate performance samples; the actual39 catalog is checked separately.
CATALOG = copy.deepcopy(PRODUCTION_CATALOG)
CATALOG['engines']['native']['cases'] = CATALOG['engines']['native']['cases'][:6]
CATALOG['engines']['native']['coveredFamilies'] = [
    'global-collect', 'global-schema', 'global-order', 'global-filter', 'global-four-scalar-distinct']


def make_plan(engine='jvm', source_catalog=None):
    catalog = (CATALOG if source_catalog is None else source_catalog)['engines'][engine]
    pins = {'/catalog.json': 'a' * 64, '/proof.json': 'b' * 64, '/runtime.json': 'c' * 64,
            '/source.json': 'd' * 64, '/java': 'e' * 64, '/graphite': 'f' * 64, '/app.jar': '1' * 64}
    graphs = [{'id': name, 'path': '/graphs/' + name} for name in catalog['graphIds']]
    pins.update({g['path'] + '/graph.nodes': '2' * 64 for g in graphs})
    arms = {}
    for name, role in [('C', 'accepted-baseline'), ('A', 'parent'), ('B', 'candidate')]:
        arms[name] = {'role': role, 'revision': '4f2ccf33b969e684972e56b5e810034e6e67c1b3' if name == 'C' else name.lower() * 40,
                      'runtimeManifest': {'path': '/runtime.json', 'sha256': pins['/runtime.json']},
                      'sourceManifest': {'path': '/source.json', 'sha256': pins['/source.json']},
                      'runtimeFiles': {'/java': pins['/java'], '/app.jar': pins['/app.jar']}, 'runtimeRoots': [],
                      'graphs': graphs, 'readiness': {'path': '/api/graphs', 'expected': {'count': len(graphs), 'graphs': graphs}},
                      'serverArgv': ['/java', '-Xmx8g', '-XX:ActiveProcessorCount=4', '-cp', '/app.jar', 'io.johnsonlee.graphite.cli.ExploreMainKt'],
                      'maxHeapBytes': 8 * 1024 ** 3}
        if engine == 'native':
            arms[name]['serverArgv'] = ['/graphite', 'serve']
            arms[name]['runtimeFiles'] = {'/graphite': pins['/graphite']}
    cases = []
    for source in catalog['cases']:
        case = {'id': source['id'], 'catalogId': source['id'], 'targetGraphIds': source.get('targetGraphIds', catalog['graphIds']),
                'request': source['request'], 'oracleByArm': {}}
        for arm in arms:
            oracle = {'kind': source['oracleKind'], 'proof': {'path': '/proof.json', 'sha256': pins['/proof.json']}}
            if engine == 'jvm':
                oracle.update(rowOrder=source['rowOrder'], value=source['expected'],
                              digest=p.digest_bytes(p.canonical(p.typed_envelope(source['expected'], source['rowOrder']))))
            else:
                oracle.update(source['expected']['base' if arm == 'C' else 'candidate'])
            case['oracleByArm'][arm] = oracle
        cases.append(case)
    plan = {'schema': p.PLAN_SCHEMA, 'operation': 'query', 'engine': engine,
            'catalog': {'path': '/catalog.json', 'sha256': pins['/catalog.json']}, 'pins': pins, 'arms': arms,
            'proofs': [{'path': '/proof.json', 'sha256': pins['/proof.json'], 'status': 'PASS', 'role': role}
                       for role in ['fixture-equivalence', 'independent-correctness', 'source-runtime']],
            'cells': [{'id': str(i) + arm, 'arm': arm, 'port': 23100 + i} for i, arm in enumerate('CABBAC')],
            'cases': cases, 'schedule': {'concurrency': 4, 'warmupPerCase': 2, 'measuredPerCase': 20},
            'comparisonPairs': {'parent': [[1, 2], [4, 3]], 'acceptedBaseline': [[0, 2], [5, 3]]},
            'limits': {'requestSeconds': 60, 'stageSeconds': 900, 'readinessSeconds': 180,
                       'bodyBytes': 1024 ** 2, 'rssIntervalSeconds': .005},
            'coverage': {'coveredFamilies': catalog['coveredFamilies'], 'requiredFamilies': catalog['requiredFamilies'],
                         'unavailableFamilies': sorted(set(catalog['requiredFamilies']) - set(catalog['coveredFamilies'])),
                         'unavailableOperations': ['construction', 'loading']}}

    proofs = []
    for key, arm in arms.items():
        for role, claims in [('source-runtime', {
                'revision': arm['revision'], 'patchSha256': None, 'sourceManifestSha256': pins['/source.json'],
                'runtimeManifestSha256': pins['/runtime.json'], 'runtimeFiles': arm['runtimeFiles']}),
                ('independent-correctness', {'graphIds': catalog['graphIds'],
                    'requestDigests': {c['id']: p.digest_bytes(p.canonical(c['request'])) for c in cases},
                    'oracleDigests': {c['id']: c['oracleByArm'][key]['digest'] for c in cases}})]:
            proof_path = '/' + role + '-' + key + '.json'
            pins[proof_path] = '3' * 64
            proofs.append({'path': proof_path, 'sha256': pins[proof_path], 'status': 'PASS', 'role': role,
                'arm': key, 'bindings': claims,
                'upstream': {path: pins[path] for path in ['/source.json', '/runtime.json', '/proof.json']}})
    proof_path = '/fixture-equivalence.json'
    pins[proof_path] = '4' * 64
    proofs.append({'path': proof_path, 'sha256': pins[proof_path], 'status': 'PASS', 'role': 'fixture-equivalence',
        'upstream': {'/proof.json': pins['/proof.json']}, 'bindings': {
            'graphsByArm': {key: arm['graphs'] for key, arm in arms.items()},
            'fixtureFiles': {path: digest for path, digest in pins.items() if path.startswith('/graphs/')},
            'realPersistedGraphs': True, 'completeSemanticEquivalence': True}})
    plan['proofs'] = proofs
    if engine == 'native':
        plan['nativeOracleBindings'] = {key: {
            'revision': arms[key]['revision'], 'graphs': arms[key]['graphs'],
            'sourceManifestSha256': pins['/source.json'], 'runtimeManifestSha256': pins['/runtime.json'],
            'cases': {case['id']: {k: case['oracleByArm'][key][k] for k in ('digest', 'rows')} for case in cases}}
            for key in ('A', 'B')}
    return plan


class FakeTransport:
    def __init__(self, raw=b'{"x":1}', fail_at=None):
        self.raw, self.fail_at = raw, fail_at
        self.closed = False

    def fetch(self, case, path, record):
        path.write_bytes(self.raw)
        record.update(wireCompleteNs=record['startNs'] + 1, completeBody=True, httpStatus=200,
                      deadlineExpired=False, requestBodySha256='fake', endpoint='/api/cypher')
        if record['sequence'] == self.fail_at:
            raise ValueError('injected partial/HTTP failure')

    def close_all(self):
        self.closed = True


class Clock:
    def __init__(self):
        self.lock = threading.Lock()
        self.value = 0

    def __call__(self):
        with self.lock:
            self.value += 10
            return self.value

    def advance(self, amount):
        with self.lock:
            self.value += amount


class PressureTests(unittest.TestCase):
    def setUp(self):
        self.child_guard = patch.object(p.subprocess, 'Popen', side_effect=AssertionError('No child in correctness tests'))
        self.child_guard.start()
        self.addCleanup(self.child_guard.stop)

    def test_both_declared_plans_and_full_multi_graph_catalogs(self):
        for engine, cases, graphs in [('jvm', 2, 3), ('native', 6, 64)]:
            plan = make_plan(engine)
            p.validate_plan(plan, CATALOG)
            self.assertEqual(len(plan['cases']), cases)
            self.assertEqual(len(plan['arms']['B']['graphs']), graphs)
            self.assertTrue(plan['coverage']['unavailableFamilies'])
            self.assertEqual(plan['coverage']['unavailableOperations'], ['construction', 'loading'])

    def test_jvm_producer_authority_never_enters_native_or_mixed_proof_modes(self):
        for engine, extra in [('native', None), ('jvm', 'producerAuthority'), ('jvm', 'correctedProducerAuthority')]:
            plan = make_plan(engine)
            ref = {'path': '/proof.json', 'sha256': plan['pins']['/proof.json']}
            plan['jvmProducerAuthority'] = ref
            if extra: plan[extra] = ref
            with self.subTest(engine=engine, extra=extra), self.assertRaisesRegex(ValueError, 'separate JVM producer'):
                p.validate_plan(plan, CATALOG)

    def test_singleton_or_semantically_narrowed_query_cannot_replace_catalog(self):
        for mutation in ['scope', 'endpoint', 'query', 'graphIds']:
            plan = make_plan()
            plan = copy.deepcopy(plan)
            if mutation == 'scope':
                plan['cases'][0]['targetGraphIds'] = ['tika']
            elif mutation == 'endpoint':
                plan['cases'][0]['request']['endpoint'] = '/api/graphs/tika/cypher'
            elif mutation == 'query':
                plan['cases'][0]['request']['body']['query'] = "MATCH(n) WHERE n.graphId='tika' RETURN n"
            else:
                plan['arms']['B']['graphs'] = plan['arms']['B']['graphs'][:1]
            with self.assertRaises(ValueError, msg=mutation):
                p.validate_plan(plan, CATALOG)

    def test_exact_two_graph_catalog_scope_with_full64_readiness(self):
        catalog = copy.deepcopy(CATALOG)
        native = catalog['engines']['native']
        pair = [native['graphIds'][0], native['graphIds'][-1]]
        source = native['cases'][0]
        source['targetGraphIds'] = pair
        source['request']['body']['query'] = (
            "MATCH (n) WHERE n.graphId IN " + repr(pair) + " RETURN n.graphId LIMIT 50")
        source['querySha256'] = p.digest_bytes(source['request']['body']['query'].encode())
        plan = make_plan('native', catalog)
        p.validate_plan(plan, catalog)
        self.assertEqual(pair, plan['cases'][0]['targetGraphIds'])
        self.assertEqual(64, plan['arms']['B']['readiness']['expected']['count'])
        for wrong in [native['graphIds'], pair[:1], [pair[0], pair[0]], pair[::-1]]:
            bad = copy.deepcopy(plan)
            bad['cases'][0]['targetGraphIds'] = wrong
            with self.subTest(scope=wrong), self.assertRaises(ValueError):
                p.validate_plan(bad, catalog)

    def test_declared_scope_itself_cannot_be_singleton_duplicate_or_unloaded(self):
        for targets in [[], ['a'], ['a', 'a'], ['a', 'outside'], None]:
            with self.subTest(scope=targets), self.assertRaises(ValueError):
                p.declared_query_scope({'targetGraphIds': targets}, ['a', 'b', 'c'])
        self.assertEqual(['a', 'b', 'c'], p.declared_query_scope({}, ['a', 'b', 'c']))

    def test_missing_coverage_cannot_be_erased(self):
        for key, value in [('unavailableFamilies', []), ('unavailableOperations', []), ('requiredFamilies', [])]:
            plan = make_plan()
            plan['coverage'][key] = value
            with self.assertRaises(ValueError):
                p.validate_plan(plan, CATALOG)

    def test_heap_overrides_unpinned_runtime_and_wrong_baseline_rejected(self):
        for mutation in ['heap', 'second-heap', 'override', 'unbound', 'baseline', 'classpath']:
            plan = make_plan()
            arm = plan['arms']['B']
            if mutation == 'heap':
                arm['serverArgv'][1] = '-Xmx9g'
            elif mutation == 'second-heap':
                arm['serverArgv'].insert(2, '-Xmx1g')
            elif mutation == 'override':
                arm['serverArgv'].insert(2, '-XX:MaxHeapSize=99999999999')
            elif mutation == 'unbound':
                arm['runtimeManifest']['sha256'] = None
            elif mutation == 'baseline':
                plan['arms']['C']['revision'] = '1' * 40
            else:
                arm['serverArgv'][-2] = '/untracked.jar'
            with self.assertRaises(ValueError, msg=mutation):
                p.validate_plan(plan, CATALOG)

    def test_packaged_jvm_serve_entrypoint_preserves_launch_constraints(self):
        for entrypoint in [['io.johnsonlee.graphite.cli.ExploreMainKt'],
                           ['io.johnsonlee.graphite.cli.MainKt', 'serve']]:
            plan = make_plan()
            for arm in plan['arms'].values():
                arm['serverArgv'][-1:] = entrypoint
            p.validate_plan(plan, CATALOG)
            for suffix in [[], ['io.johnsonlee.graphite.cli.MainKt'],
                           ['io.johnsonlee.graphite.cli.MainKt', 'query'],
                           ['io.johnsonlee.graphite.cli.MainKt', 'serve', '--load-mode', 'EAGER'],
                           ['io.johnsonlee.graphite.cli.ExploreMainKt', 'serve']]:
                bad = copy.deepcopy(plan)
                argv = bad['arms']['B']['serverArgv']
                argv[argv.index('-cp') + 2:] = suffix
                with self.subTest(entrypoint=entrypoint, suffix=suffix), self.assertRaisesRegex(ValueError, 'reviewed JVM entrypoint'):
                    p.validate_plan(bad, CATALOG)
            bad = copy.deepcopy(plan)
            bad['arms']['B']['serverArgv'][1] = '-Xmx9g'
            with self.assertRaisesRegex(ValueError, 'JVM heap ceiling'):
                p.validate_plan(bad, CATALOG)
            bad = copy.deepcopy(plan)
            argv = bad['arms']['B']['serverArgv']
            argv[argv.index('-cp') + 1] = '/untracked.jar'
            with self.assertRaisesRegex(ValueError, 'closed classpath entry'):
                p.validate_plan(bad, CATALOG)

    def test_fixed_per_case_sample_schedule_and_pairs(self):
        for key, value in [('concurrency', 1), ('measuredPerCase', 19), ('warmupPerCase', 0)]:
            plan = make_plan()
            plan['schedule'][key] = value
            with self.assertRaises(ValueError):
                p.validate_plan(plan, CATALOG)
        plan = make_plan()
        plan['comparisonPairs']['parent'][0] = [0, 2]
        with self.assertRaises(ValueError):
            p.validate_plan(plan, CATALOG)

    def test_typed_envelope_rejects_wrong_types_scope_and_unexpected_fields(self):
        case = make_plan()['cases'][0]
        expected = case['oracleByArm']['B']['value']
        p.validate_response(p.canonical(expected), case, 'B')
        for name, value in [('graphCount', True), ('graphs', ['tika']), ('rowCount', 1), ('extra', 1), ('limit', 50.0)]:
            broken = copy.deepcopy(expected)
            broken[name] = value
            with self.assertRaises(ValueError, msg=name):
                p.validate_response(p.canonical(broken), case, 'B')

    def test_multiset_only_outer_rows_preserves_nested_order_and_scalar_types(self):
        base = {'mode': 'cross-graph', 'graphs': ['a', 'b'], 'graphCount': 2, 'columns': ['x'],
                'rows': [{'x': [1, True]}, {'x': [2, False]}], 'rowCount': 2, 'limit': 20}
        reversed_rows = {**base, 'rows': list(reversed(base['rows']))}
        self.assertEqual(p.typed_envelope(base, 'outer-multiset'), p.typed_envelope(reversed_rows, 'outer-multiset'))
        self.assertNotEqual(p.typed_envelope(base, 'exact'), p.typed_envelope(reversed_rows, 'exact'))
        broken = copy.deepcopy(base)
        broken['rows'][0]['x'] = [True, 1]
        self.assertNotEqual(p.typed_envelope(base, 'outer-multiset'), p.typed_envelope(broken, 'outer-multiset'))
        self.assertNotEqual(p.typed(1), p.typed(True))
        self.assertNotEqual(p.typed(1), p.typed(1.0))

    def test_native_oracle_keeps_every_field_row_order_and_provenance(self):
        body = {'columns': ['x'], 'rows': [{'x': 1, '$metadata': {'graphIds': ['a', 'b']}}, {'x': 2}], 'rowCount': 2}
        case = {'oracleByArm': {'B': {'kind': 'native-full-json-sha256-v1', 'rows': 2,
                                     'digest': p.digest_bytes(p.canonical(body))}}}
        p.validate_response(p.canonical(body), case, 'B')
        for changed in [{**body, 'rows': list(reversed(body['rows']))},
                        {**body, 'rows': [{'x': True}, {'x': 2}]}, {**body, 'extra': 0}]:
            with self.assertRaises(ValueError):
                p.validate_response(p.canonical(changed), case, 'B')

    def test_duplicate_nonfinite_and_truncated_json_rejected(self):
        for raw in [b'{"x":1,"x":2}', b'{"x":NaN}', b'{"x":Infinity}', b'{"x":']:
            with self.assertRaises(ValueError):
                p.parse(raw)

    def test_inline_validation_is_in_latency_and_every_case_has_20_samples(self):
        clock = Clock()
        def validate(raw, case):
            clock.advance(1000)
            return {'canonicalSha256': p.digest_bytes(raw), 'rows': 1}
        tasks = [{'id': name} for _ in range(20) for name in ['slow', 'other']]
        with tempfile.TemporaryDirectory() as tmp:
            result = p.run_stage(tasks, FakeTransport(), validate, clock, threading.Event(), Path(tmp) / 'stage')
            self.assertEqual(result['status'], 'PASS')
            self.assertEqual(result['issued'], 40)
            self.assertEqual(result['unissued'], [])
            self.assertTrue(all(r['latencyNs'] >= 1000 for r in result['requests']))
            self.assertEqual({k: v['n'] for k, v in result['caseStatistics'].items()}, {'slow': 20, 'other': 20})
            self.assertEqual(result['endNs'], max(r['validationCompleteNs'] for r in result['requests']))
            self.assertEqual(len(list((Path(tmp) / 'stage/bodies').iterdir())), 40)

    def test_other_workers_refill_while_one_validation_is_blocked(self):
        blocked = threading.Event()
        refilled = threading.Event()
        lock = threading.Lock()
        validations = 0
        def validate(raw, case):
            nonlocal validations
            with lock:
                validations += 1
                index = validations
            if index == 1:
                blocked.set()
                if not refilled.wait(5):
                    raise AssertionError('batch barrier blocked refill')
            else:
                self.assertTrue(blocked.wait(5))
                if index >= 6:
                    refilled.set()
            return {}
        with tempfile.TemporaryDirectory() as tmp:
            result = p.run_stage([{'id': 'x'}] * 12, FakeTransport(), validate, Clock(), threading.Event(), Path(tmp) / 'stage')
            self.assertEqual(result['status'], 'PASS')
            self.assertTrue(refilled.is_set())
            for worker in range(4):
                rows = [r for r in result['requests'] if r['worker'] == worker]
                self.assertTrue(all(a['validationCompleteNs'] <= b['startNs'] for a, b in zip(rows, rows[1:])))

    def test_failure_preserves_issued_partial_body_and_stops_refill_without_retry(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = p.run_stage([{'id': 'x'}] * 100, FakeTransport(fail_at=0), lambda raw, case: {}, Clock(),
                                 threading.Event(), Path(tmp) / 'stage')
            self.assertEqual(result['status'], 'FAIL')
            self.assertLess(result['issued'], 100)
            self.assertEqual(len(result['requests']), result['issued'])
            self.assertEqual(result['unissued'], list(range(result['issued'], 100)))
            self.assertEqual((Path(tmp) / 'stage/bodies/000000.body').read_bytes(), b'{"x":1}')
            self.assertEqual(len({r['sequence'] for r in result['requests']}), result['issued'])

    def test_stage_deadline_closes_owned_transport_and_retains_failure(self):
        released = threading.Event()
        class Blocking(FakeTransport):
            def fetch(self, case, path, record):
                released.wait(5)
                raise TimeoutError('aborted')
            def close_all(self):
                released.set()
        with tempfile.TemporaryDirectory() as tmp:
            result = p.run_stage([{'id': 'x'}] * 20, Blocking(), lambda raw, case: {}, Clock(),
                                 threading.Event(), Path(tmp) / 'stage', deadline_seconds=.001)
            self.assertEqual(result['status'], 'FAIL')
            self.assertTrue(result['allWorkersStopped'])
            self.assertEqual(result['error'], 'stage deadline exceeded')
            self.assertLessEqual(result['issued'], 4)

    def test_exact_nearest_ranks_and_no_case_pooling(self):
        values = list(range(20, 0, -1))
        self.assertEqual(p.nearest_rank(values, .5), 10)
        self.assertEqual(p.nearest_rank(values, .95), 19)
        rows = [{'sequence': i, 'caseId': 'slow' if i < 20 else 'fast', 'latencyNs': 1000+i if i < 20 else i-19}
                for i in range(40)]
        result = p.statistics(rows)
        self.assertEqual(result['slow']['p95Ns'], 1018)
        self.assertEqual(result['fast']['p95Ns'], 19)

    def test_rss_whole_reads_only_and_empty_interval_fails(self):
        rows = [{'readStartNs': a, 'readEndNs': b, 'rssBytes': rss}
                for a, b, rss in [(9, 11, 999), (10, 20, 20), (19, 21, 888)]]
        self.assertEqual(p.summarize_interval(rows, 10, 20),
                         {'lowerBoundBytes': 20, 'eligible': 1, 'excluded': 2, 'scope': 'whole-read sampled lower bound'})
        with self.assertRaises(ValueError):
            p.summarize_interval(rows, 11, 19)

    def test_cpu_enclosing_upper_inner_lower_do_not_hide_boundary_lag(self):
        def sample(start, end, cpu):
            return {'readStartNs': start, 'readEndNs': end, 'cpuSeconds': cpu, 'rssBytes': 50,
                    'pid': 42, 'startTicks': 100, 'ticksPerSecond': 100}
        stage = {'startNs': 10, 'endNs': 100, 'cpuStart': sample(0, 2, 1), 'cpuEnd': sample(110, 112, 2)}
        value = p.resource_summary(stage, [sample(20, 21, 1.2), sample(80, 81, 1.7)], {'peakRssBytes': 100})
        self.assertAlmostEqual(value['cpuLowerBoundSeconds'], .48)
        self.assertAlmostEqual(value['cpuUpperBoundSeconds'], 1.02)
        self.assertEqual(value['cpuEndLagNs'], 12)
        self.assertEqual(value['rss']['upperBoundBytes'], 100)

    def test_mac_ps_and_bsd_time_use_original_units_and_precision(self):
        raw = 'Fri Oct  9 12:03:04 2026 1-02:03:04.25 1024'
        value = p.parse_macos_sample(raw, 22, 100, 200)
        self.assertEqual(value['cpuSeconds'], 93784.25)
        self.assertEqual(value['rssBytes'], 1024 ** 2)
        self.assertEqual(value['cpuBoundaryUncertaintySeconds'], .02)
        with self.assertRaises(ValueError):
            p.parse_macos_sample(raw, 22, 100, 200, 'different process')
        with tempfile.TemporaryDirectory() as tmp, patch.object(p.sys, 'platform', 'darwin'):
            file = Path(tmp) / 'time'
            file.write_text(' 1.50 real 1.20 user 0.10 sys\n 123456 maximum resident set size\n')
            result = p.lifecycle_time(file)
            self.assertEqual(result['peakRssBytes'], 123456)
            self.assertEqual(result['realSeconds'], 1.5)

    def test_linux_proc_units_and_gnu_time_peak(self):
        fields = ['0'] * 22
        fields[11], fields[12], fields[19], fields[21] = '110', '25', '987', '64'
        raw = '42 (process with spaces) ' + ' '.join(fields)
        with patch.object(p.sys, 'platform', 'linux'), patch.object(Path, 'read_text', return_value=raw), \
                patch.object(p.os, 'sysconf', side_effect=lambda key: 100 if key == 'SC_CLK_TCK' else 4096):
            value = p.proc_sample(42, 987)
            self.assertEqual(value['cpuSeconds'], 1.35)
            self.assertEqual(value['rssBytes'], 262144)
            with self.assertRaises(ValueError):
                p.proc_sample(42, 1)
        with tempfile.TemporaryDirectory() as tmp, patch.object(p.sys, 'platform', 'linux'):
            file = Path(tmp) / 'time'
            file.write_text('User time (seconds): 1.20\nSystem time (seconds): 0.30\nElapsed (wall clock) time (h:mm:ss or m:ss): 0:02.10\nMaximum resident set size (kbytes): 2048\n')
            value = p.lifecycle_time(file)
            self.assertEqual(value['peakRssBytes'], 2 * 1024 ** 2)
            self.assertEqual(value['userSeconds'] + value['systemSeconds'], 1.5)

    def test_deferred_signal_publishes_owner_then_interrupts_parent(self):
        handlers, events = {}, []
        def install(sig, handler):
            handlers[sig] = handler
        with patch.object(p.signal, 'getsignal', return_value=signal.SIG_DFL), patch.object(p.signal, 'signal', side_effect=install):
            with self.assertRaises(InterruptedError):
                with p.deferred_signals():
                    handlers[signal.SIGTERM](signal.SIGTERM, None)
                    events.extend(['child assigned', 'owner journal saved'])
        self.assertEqual(events, ['child assigned', 'owner journal saved'])
        self.assertEqual(handlers[signal.SIGTERM], signal.SIG_DFL)

    def test_cleanup_checks_group_even_when_leader_already_exited(self):
        proc = MagicMock(pid=1001)
        proc.poll.return_value = 0
        proc.wait.return_value = 0
        with patch.object(p, 'group_members', side_effect=[[{'pid': 1002}], [], [], []]), \
                patch.object(p.os, 'getpgrp', return_value=55), patch.object(p.os, 'killpg') as kill:
            result = p.stop_owned(proc, None, None)
        kill.assert_called_once_with(1001, signal.SIGTERM)
        self.assertEqual(result['after'], [])
        self.assertIn('wrapper exited before requested shutdown', result['errors'])
        proc.wait.assert_called()

    def test_cleanup_foreign_pid_never_signals_that_pid_but_reaps_owned_group(self):
        proc = MagicMock(pid=1001)
        proc.poll.return_value = None
        with patch.object(p.os, 'getpgrp', return_value=55), patch.object(p.os, 'getpgid', return_value=999), \
                patch.object(p.os, 'kill') as kill, patch.object(p, 'group_members', return_value=[]):
            result = p.stop_owned(proc, 1002, 1)
        kill.assert_not_called()
        self.assertTrue(any('foreign server PID' in e for e in result['errors']))
        self.assertEqual(result['after'], [])

    def test_runtime_and_graph_closed_inventory_rejects_extra_and_symlink(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            graph = root / 'graph'
            graph.mkdir()
            (graph / 'nodes').write_bytes(b'correct')
            proof = root / 'proof.json'
            proof.write_text('{"status":"PASS","manifest":"expected","upstream":{}}')
            plan = {'pins': {str(graph/'nodes'): p.sha(graph/'nodes'), str(proof): p.sha(proof)},
                    'proofs': [{'path': str(proof), 'status': 'PASS', 'upstream': {}, 'bindings': {'manifest': 'expected'}}],
                    'arms': {'A': {'graphs': [{'path': str(graph)}], 'runtimeRoots': [], 'runtimeFiles': {}}}}
            p.verify_inputs(plan)
            (graph / 'extra').write_text('untracked')
            with self.assertRaises(ValueError):
                p.verify_inputs(plan)
            (graph / 'extra').unlink()
            (graph / 'link').symlink_to(graph / 'nodes')
            with self.assertRaises(ValueError):
                p.verify_inputs(plan)

    def test_http_partial_and_error_bodies_are_retained(self):
        case = {'request': {'endpoint': '/api/cypher', 'body': {'query': 'q'}}}
        for mode in ['http-error', 'partial', 'oversize']:
            connection = MagicMock()
            response = MagicMock(status=500 if mode == 'http-error' else 200, length=0)
            response.getheaders.return_value = []
            if mode == 'partial':
                response.read1.side_effect = [b'abc', p.http.client.IncompleteRead(b'de', 9)]
            else:
                response.read1.side_effect = [b'abcdef', b'']
            connection.getresponse.return_value = response
            with tempfile.TemporaryDirectory() as tmp, patch.object(p.http.client, 'HTTPConnection', return_value=connection):
                transport = p.HTTPTransport(12345, {'requestSeconds': 60, 'bodyBytes': 4 if mode == 'oversize' else 100})
                body = Path(tmp) / 'body'
                record = {}
                with self.assertRaises(Exception):
                    transport.fetch(case, body, record)
                self.assertEqual(body.read_bytes(), b'abcde' if mode == 'partial' else b'abcdef')
                self.assertEqual(transport.connections, {})
                connection.close.assert_called()

    def test_close_all_uses_saved_socket_after_http_connection_detaches_it(self):
        transport = p.HTTPTransport(1, {'requestSeconds': 1, 'bodyBytes': 100})
        connection = MagicMock(sock=None)
        owned_socket = MagicMock()
        transport.connections[connection] = [owned_socket]
        transport.close_all()
        owned_socket.shutdown.assert_called_once_with(p.socket.SHUT_RDWR)
        connection.close.assert_called_once()

    def test_readiness_rejects_changed_graph_totals_and_full_scope(self):
        arm = {'graphs': [{'id': 'a'}, {'id': 'b'}], 'readiness': {'expected': {
            'count': 2, 'graphs': [{'id': 'a', 'nodes': 3, 'edges': 4}, {'id': 'b', 'nodes': 5, 'edges': 6}], 'nodes': 8, 'edges': 10}}}
        value = copy.deepcopy(arm['readiness']['expected'])
        value['data'] = '/tmp/data'
        for graph in value['graphs']:
            graph['loadedAt'] = '2026-10-09T12:00:00Z'
        p.validate_readiness_body(p.canonical(value), arm, Path('/tmp/data'))
        for key in ['edges', 'nodes']:
            wrong = copy.deepcopy(value)
            wrong['graphs'][0][key] += 1
            with self.assertRaises(ValueError):
                p.validate_readiness_body(p.canonical(wrong), arm, Path('/tmp/data'))
        wrong = copy.deepcopy(value)
        wrong['graphs'] = wrong['graphs'][:1]
        with self.assertRaises(ValueError):
            p.validate_readiness_body(p.canonical(wrong), arm, Path('/tmp/data'))

    def test_raw_resource_tampering_is_detected(self):
        raw = 'Fri Oct  9 12:03:04 2026 00:04.25 1024'
        row = p.parse_macos_sample(raw, 22, 100, 200)
        p.validate_resource_sample(row)
        row['rssBytes'] += 1024
        with self.assertRaises(ValueError):
            p.validate_resource_sample(row)

    def test_run_failure_still_cleans_owned_group_and_preserves_final_pin_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            planfile = root / 'plan.json'
            plan = make_plan()
            p.save(planfile, plan)
            out = root / 'new-cell'
            proc = MagicMock(pid=1001)
            proc.poll.return_value = None
            cleanup = {'group': 1001, 'after': [], 'errors': [], 'signals': [], 'exit': 0}
            def fake_spawn(command, **kwargs):
                Path(command[command.index('--pid-file') + 1]).write_text('1002')
                return proc
            with contextlib.ExitStack() as stack:
                stack.enter_context(patch.object(p.sys, 'platform', 'linux'))
                stack.enter_context(patch.object(p, 'validate_plan', return_value=plan))
                stack.enter_context(patch.object(p, 'verify_inputs', side_effect=[{'status':'PASS','pins':{}}, ValueError('final pin drift')]))
                stack.enter_context(patch.object(p.subprocess, 'Popen', side_effect=fake_spawn))
                stack.enter_context(patch.object(p.socket, 'socket'))
                stack.enter_context(patch.object(p.os, 'getpgid', return_value=1001))
                stack.enter_context(patch.object(p, 'proc_sample', return_value={'startTicks': 10}))
                stack.enter_context(patch.object(p, 'Monitor'))
                stack.enter_context(patch.object(p, 'readiness', side_effect=ValueError('wrong complete graph body')))
                stop = stack.enter_context(patch.object(p, 'stop_owned', return_value=cleanup))
                stack.enter_context(patch.object(p, 'lifecycle_time', return_value={'peakRssBytes': 100}))
                result = p.run_cell(planfile, '0C', out)
            self.assertEqual(result['status'], 'FAIL')
            self.assertIn('wrong complete graph body', result['errors'][0])
            self.assertTrue(any('final pin drift' in error for error in result['errors']))
            self.assertFalse(result['acceptanceEligible'])
            stop.assert_called_once_with(proc, 1002, 10)
            self.assertEqual(p.read(out / 'cleanup.json'), cleanup)
            self.assertEqual(p.read(out / 'launch-owner.json')['group'], 1001)
            self.assertEqual(p.read(out / 'result.json'), result)

    def test_successful_body_is_not_rehashed_after_validated_completion(self):
        body = b'{"x":1}'
        with tempfile.TemporaryDirectory() as tmp, patch.object(p, 'sha', side_effect=AssertionError('late file hash')):
            result = p.run_stage([{'id': 'x'}] * 8, FakeTransport(body), lambda raw, case: {}, Clock(),
                                 threading.Event(), Path(tmp) / 'stage')
        self.assertEqual(result['status'], 'PASS')
        self.assertTrue(all(row['bodySha256'] == p.digest_bytes(body) for row in result['requests']))

    def test_result_eligibility_and_coverage_cannot_be_fabricated(self):
        plan = make_plan()
        result = {'coverage': copy.deepcopy(plan['coverage']), 'engine': 'jvm', 'operation': 'query',
                  'otherOperationsEligible': False, 'status': 'PASS', 'errors': [],
                  'stages': {name: {'status': 'PASS', 'resources': {}} for name in ['oracle', 'warmup', 'pressure']}}
        self.assertFalse(p.evidence_eligible(result, plan))
        for key, value in [('coverage', {**result['coverage'], 'unavailableFamilies': []}),
                           ('engine', 'native'), ('operation', 'loading'), ('otherOperationsEligible', True)]:
            changed = copy.deepcopy(result)
            changed[key] = value
            with self.assertRaises(ValueError):
                p.evidence_eligible(changed, plan)
        with tempfile.TemporaryDirectory() as tmp, patch.object(p, 'validate_plan', return_value=plan):
            root = Path(tmp)
            planfile = root/'input.json'
            p.save(planfile, plan)
            p.save(root/'plan.json', plan)
            result.update(schema=p.RESULT_SCHEMA, planSha256=p.sha(planfile), acceptanceEligible=True)
            p.save(root/'result.json', result)
            with self.assertRaisesRegex(ValueError, 'eligibility'):
                p.audit(planfile, root)

    def test_arbitrary_pass_receipt_is_not_arm_source_runtime_or_fixture_proof(self):
        for mutation in ['missing-arm', 'runtime-drift', 'source-drift', 'oracle-drift', 'fake-fixture', 'unlinked-upstream']:
            plan = make_plan()
            proof = plan['proofs'][0]
            if mutation == 'missing-arm':
                proof.pop('arm')
            elif mutation == 'runtime-drift':
                proof['bindings']['runtimeManifestSha256'] = '0' * 64
            elif mutation == 'source-drift':
                proof['bindings']['revision'] = '0' * 40
            elif mutation == 'oracle-drift':
                plan['proofs'][1]['bindings']['oracleDigests'] = {}
            elif mutation == 'fake-fixture':
                plan['proofs'][-1]['bindings']['completeSemanticEquivalence'] = False
            else:
                proof['upstream'] = {}
            with self.assertRaises(ValueError, msg=mutation):
                p.validate_plan(plan, CATALOG)

    def test_resources_reject_valid_but_unrelated_process_or_units(self):
        base = {'readStartNs': 0, 'readEndNs': 1, 'pid': 42, 'startTicks': 100,
                'backend': 'linux-proc', 'ticksPerSecond': 100, 'pageSize': 4096, 'cpuSeconds': 1, 'rssBytes': 100}
        stage = {'startNs': 2, 'endNs': 20, 'cpuStart': base,
                 'cpuEnd': {**base, 'readStartNs': 21, 'readEndNs': 22, 'cpuSeconds': 2}}
        inside = {**base, 'readStartNs': 10, 'readEndNs': 11, 'cpuSeconds': 1.5}
        for key, value in [('pid', 43), ('startTicks', 101), ('backend', 'macOS-ps'), ('ticksPerSecond', 1000), ('pageSize', 16384)]:
            with self.assertRaisesRegex(ValueError, 'sample process'):
                p.resource_summary(stage, [{**inside, key: value}], {'peakRssBytes': 1000})

    def test_jvm_arms_must_use_the_same_resolved_jdk_and_hash(self):
        for executable, digest in [('/other-jdk/bin/java', 'e' * 64), ('/alias/../java', '7' * 64)]:
            plan = make_plan()
            plan['pins'][executable] = digest
            plan['arms']['A']['serverArgv'][0] = executable
            plan['arms']['A']['runtimeFiles'][executable] = digest
            with self.assertRaisesRegex(ValueError, 'matched resolved JDK'):
                p.validate_plan(plan, CATALOG)

    def test_readiness_slow_drip_is_aborted_by_absolute_watchdog(self):
        proc = MagicMock()
        proc.poll.return_value = None
        connection = MagicMock()
        socket = connection.sock
        response = MagicMock(status=200, length=0)
        callbacks = []
        def timer(seconds, callback):
            callbacks.append(callback)
            handle = MagicMock()
            handle.is_alive.return_value = False
            return handle
        def getresponse():
            connection.sock = None  # Connection:close transfers the socket to response.
            return response
        def first_chunk(size):
            callbacks[0]()
            return b'{"a":"complete but too late"}'
        connection.getresponse.side_effect = getresponse
        response.read1.side_effect = first_chunk
        with tempfile.TemporaryDirectory() as tmp, patch.object(p.http.client, 'HTTPConnection', return_value=connection), \
                patch.object(p.threading, 'Timer', side_effect=timer):
            with self.assertRaisesRegex(TimeoutError, 'absolute deadline'):
                p.readiness(1, {}, Path(tmp)/'data', 1, proc, tmp)
            self.assertEqual((Path(tmp)/'readiness-attempt-0001.body').read_bytes(), b'{"a":"complete but too late"}')
            self.assertTrue(p.read(Path(tmp)/'readiness-attempt-0001.json')['deadlineExpired'])
        socket.shutdown.assert_called_once_with(p.socket.SHUT_RDWR)

    def test_readiness_complete_valid_body_after_deadline_cannot_pass(self):
        proc = MagicMock()
        proc.poll.return_value = None
        connection = MagicMock()
        response = MagicMock(status=200, length=0)
        connection.getresponse.return_value = response
        now = [100.0]
        chunks = [b'{}', b'']
        def chunk(size):
            now[0] = 102.0
            return chunks.pop(0)
        response.read1.side_effect = chunk
        with tempfile.TemporaryDirectory() as tmp, patch.object(p.http.client, 'HTTPConnection', return_value=connection), \
                patch.object(p.time, 'monotonic', side_effect=lambda: now[0]), \
                patch.object(p, 'validate_readiness_body') as validator:
            with self.assertRaisesRegex(TimeoutError, 'absolute deadline'):
                p.readiness(1, {}, Path(tmp)/'data', 1, proc, tmp)
            validator.assert_not_called()
            self.assertEqual((Path(tmp)/'readiness-attempt-0001.body').read_bytes(), b'{}')

    def test_clean_environment_removes_jvm_python_native_injection(self):
        with patch.dict(p.os.environ, {'JAVA_TOOL_OPTIONS': '-Xmx99g', 'PYTHONPATH': '/bad', 'DYLD_UNKNOWN': '/bad',
                                       'GRAPHITE_NO_FASTPATH': '1', 'SAFE_INPUT': 'keep'}, clear=True):
            env = p.clean_env()
        self.assertEqual(env, {'SAFE_INPUT': 'keep', 'LC_ALL': 'C', 'LANG': 'C'})


class ResolvedGraphArgumentTests(unittest.TestCase):
    def test_launch_paths_are_the_same_canonical_paths_required_by_readiness(self):
        with tempfile.TemporaryDirectory() as folder:
            raw = str(Path(folder) / 'unused' / '..' / 'graph')
            graphs = [{'id': 'one', 'path': raw}, {'id': 'two', 'path': raw + '-two'}]
            args = p.graph_arguments(graphs)
            self.assertEqual(['--graph', 'one:' + str(Path(folder).resolve() / 'graph'),
                              '--graph', 'two:' + str(Path(folder).resolve() / 'graph-two')], args)
            self.assertNotIn(raw, args[1])
            self.assertEqual(raw, graphs[0]['path'])


class CompiledLegalDispatchTests(unittest.TestCase):
    def case(self):
        from test_native_legal_response import LegalResponseTest
        case, value, body = LegalResponseTest().fixture()
        case['oracleByArm'] = {'C': {'kind': 'native-complete-legal-limit-multiset-v1',
                                   'value': value, 'rows': 50,
                                   'digest': p.digest_bytes(p.canonical(value))}}
        return case, body

    def test_dispatch_compiles_once_and_validates_every_returned_row(self):
        import native_legal_response as legal
        case, body = self.case()
        with patch.object(legal, 'key', wraps=legal.key) as key:
            validator = p.compile_response_validator([case], 'C')
            self.assertEqual(60, key.call_count)
            for _ in range(3):
                self.assertEqual({'canonicalSha256': p.digest_bytes(p.canonical(body)), 'rows': 50},
                                 validator(p.canonical(body), case))
            self.assertEqual(60 + 150, key.call_count)
        bad = copy.deepcopy(body); bad['rows'][0]['target'] = 10000
        with self.assertRaises(ValueError): validator(p.canonical(bad), case)

    def test_uncompiled_or_tampered_legal_oracle_never_runs(self):
        case, body = self.case()
        with self.assertRaisesRegex(ValueError, 'compiled before requests'):
            p.validate_response(p.canonical(body), case, 'C')
        case['oracleByArm']['C']['value']['totalMatches'] = 10
        with self.assertRaisesRegex(ValueError, 'legal oracle digest'):
            p.compile_response_validator([case], 'C')
        case, _ = self.case(); case['oracleByArm']['C']['rows'] = 49
        with self.assertRaisesRegex(ValueError, 'legal oracle row count'):
            p.compile_response_validator([case], 'C')

    def test_existing_exact_dispatch_remains_strict_and_unknown_kind_fails(self):
        body = {'columns': ['x'], 'rows': [{'x': 1}], 'rowCount': 1}
        case = {'id': 'exact', 'oracleByArm': {'C': {'kind': 'native-full-json-sha256-v1',
                'digest': p.digest_bytes(p.canonical(body)), 'rows': 1}}}
        validator = p.compile_response_validator([case], 'C')
        self.assertEqual(1, validator(p.canonical(body), case)['rows'])
        body['rows'][0]['x'] = '1'
        with self.assertRaises(ValueError): validator(p.canonical(body), case)
        case['oracleByArm']['C']['kind'] = 'unknown'
        with self.assertRaisesRegex(ValueError, 'unknown response oracle kind'):
            validator(p.canonical(body), case)


class ExpandedProductionCatalogTests(unittest.TestCase):
    def test_all39_cases_keep_original_queries_and_multigraph_scopes(self):
        native = PRODUCTION_CATALOG['engines']['native']
        self.assertEqual(39, len(native['cases']))
        self.assertEqual(39, len({c['id'] for c in native['cases']}))
        self.assertEqual(set(native['requiredFamilies']), set(native['coveredFamilies']))
        original = p.read(Path(__file__).parent / 'fixtures/declared-types-native-responses.json')
        for case in native['cases']:
            self.assertEqual(case['querySha256'], p.digest_bytes(case['request']['body']['query'].encode()))
            scope = p.declared_query_scope(case, native['graphIds'])
            self.assertGreaterEqual(len(scope), 2)
            if 'sourceCatalogEntry' in case:
                entry = case['sourceCatalogEntry']
                self.assertEqual(1, original.count(entry))
                for side in ('base', 'candidate'):
                    self.assertEqual({'digest': entry[side+'Digest'], 'rows': entry[side+'Rows']}, case['expected'][side])
            else:
                self.assertEqual('independent-persisted-graph-oracle-v1', case['sourceKind'])
        self.assertEqual(15, sum('sourceCatalogEntry' in c for c in native['cases']))
        self.assertEqual(24, sum('sourceKind' in c for c in native['cases']))

    def test_hit_miss_routing_and_legal_limit_cases_are_not_substituted(self):
        import native_legal_response as legal
        cases = PRODUCTION_CATALOG['engines']['native']['cases']
        self.assertEqual({'slow-'+shape+kind for shape in ('value','qualifiedId','dynamic','wrappedCaller','dataflowSource','dataflowTarget')
                          for kind in ('Hit','Miss')}, {c['id'] for c in cases if c['id'].startswith('slow-')})
        self.assertEqual(8, sum(c.get('family') == 'wrapped-discovery' for c in cases))
        self.assertEqual(2, sum(c.get('family') == 'feature-presence' for c in cases))
        routes = [c for c in cases if c.get('family') == 'graph-routing']
        self.assertEqual(2, len(routes))
        for case in routes:
            self.assertEqual(['fixture-android-00','fixture-kotlin-compiler-15'], case['targetGraphIds'])
            self.assertIn("n.graphId IN ['fixture-android-00', 'fixture-kotlin-compiler-15']", case['request']['body']['query'])
        dataflow = [c for c in cases if c['oracleKind'] == 'native-complete-legal-limit-multiset-v1']
        self.assertEqual(4, len(dataflow))
        for case in dataflow: legal.parse(case)

    def test_legal_plan_requires_exact_pinned_full_expected_payload(self):
        from test_native_legal_response import LegalResponseTest
        case, value, _ = LegalResponseTest().fixture()
        case.update(oracleKind='native-complete-legal-limit-multiset-v1', expected={side:{
            'digest':p.digest_bytes(p.canonical(value)), 'rows':50} for side in ('base','candidate')})
        catalog = copy.deepcopy(CATALOG)
        catalog['engines']['native']['graphIds'] = case['targetGraphIds']
        catalog['engines']['native']['cases'] = [case]
        plan = make_plan('native',catalog)
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)/'expected.json';path.write_text(json.dumps(value))
            ref = {'path':str(path),'sha256':p.sha(path)}
            plan['pins'][str(path)] = ref['sha256']
            for oracle in plan['cases'][0]['oracleByArm'].values():oracle.update(value=value,valueProof=ref)
            p.validate_plan(plan,catalog)
            missing = copy.deepcopy(plan);missing['pins'].pop(str(path))
            with self.assertRaisesRegex(ValueError,'payload pin'):p.validate_plan(missing,catalog)
            path.write_text('{}')
            with self.assertRaisesRegex(ValueError,'payload pin'):p.validate_plan(plan,catalog)




class JvmLegalDispatchTests(unittest.TestCase):
    def fixture(self, name='slow-dynamicHit', grouped=False):
        import jvm_pressure_oracles as jvm
        import jvm_pressure_distinct as distinct
        case=copy.deepcopy(next(c for c in jvm.cases() if c['id']==name)); gid=case['targetGraphIds'][0]
        universe={'schema':'graphite.jvm-legal-row-universe.v1','policy':jvm.POLICY,'caseId':name,
                  **{k:copy.deepcopy(case[k]) for k in ('requestSha256','querySha256','registeredGraphIds',
                      'requestedGraphIds','targetGraphIds','columns','effectiveLimit')},
                  'allGraphScansComplete':True,'exactEncounterOrderClaim':False,'totalMatches':1}
        if grouped:
            values={'n.graph_id':None,'caller':'android.Test','callerMethod':1,'callee':None,'calleeMethod':None}
            collector=distinct.DistinctGroups();collector.add(values,gid)
            universe.update(schema=distinct.SCHEMA,equalityPolicy=distinct.EQUALITY,groups=collector.finish())
            row=jvm.projected_row(universe['groups'][0]['variants'][0],[gid])
        else:
            values={column: 'getValue' for column in case['columns']}
            if 'graphId' in values:values['graphId']=gid
            if 'id' in values:values['id']=7
            if 'labels' in values:values['labels']=['StringConstant','Constant']
            row=jvm.projected_row(values,[gid])
            universe['rows']=[{'value':row,'multiplicity':1}]
        rows = [row]
        if case['family'] == 'full-projection':
            rows = [jvm.projected_row({'value': {'id': 7, 'graphId': graph}}, [graph])
                    for graph in case['targetGraphIds']]
            universe.update(rows=[{'value': r, 'multiplicity': 1} for r in rows], totalMatches=len(rows))
        case['oracleByArm']={'B':{'kind':'jvm-complete-legal-limit-multiset-v1','value':universe,
                                 'digest':jvm.digest(universe),'rows':len(rows)}}
        body={'mode':'cross-graph','graphs':case['requestedGraphIds'],'graphCount':64,'columns':case['columns'],
              'rows':rows,'rowCount':len(rows),'limit':case['effectiveLimit']}
        return case,body

    def test_actual_body_digest_is_separate_from_universe_and_no_request_recompile(self):
        import jvm_pressure_oracles as jvm
        case,body=self.fixture();validator=p.compile_response_validator([case],'B')
        expected={'rows':1,'canonicalSha256':p.digest_bytes(p.canonical(body))}
        self.assertNotEqual(expected['canonicalSha256'],case['oracleByArm']['B']['digest'])
        with patch.object(jvm,'validate_case',side_effect=AssertionError('request must not compile')):
            for _ in range(3):self.assertEqual(expected,validator(p.canonical(body),case))
        altered=copy.deepcopy(body);altered['rows'][0]['id']='7'
        with self.assertRaises(ValueError):validator(p.canonical(altered),case)

    def test_routing_preserves_requested64_and_target2_and_checks_entire_envelope(self):
        case,body=self.fixture('routing-pair-dense');validator=p.compile_response_validator([case],'B')
        self.assertEqual(64,len(body['graphs']));self.assertEqual(2,len(case['targetGraphIds']))
        self.assertEqual(1,validator(p.canonical(body),case)['rows'])
        for field in ('graphs','provenance','value','duplicate-key'):
            bad=copy.deepcopy(body)
            if field=='graphs':bad['graphs']=case['targetGraphIds'];bad['graphCount']=2
            elif field=='provenance':bad['rows'][0]['$metadata']['graphIds']=[case['requestedGraphIds'][1]]
            elif field=='value':bad['rows'][0]['caller']=999
            raw=p.canonical(bad)
            if field=='duplicate-key':raw=raw[:-1]+b',"rowCount":1}'
            with self.subTest(field=field),self.assertRaises(ValueError):validator(raw,case)

    def test_v2_dispatch_retains_group_matching(self):
        case,body=self.fixture('wrapped-dense_distributed_method_query',grouped=True)
        validator=p.compile_response_validator([case],'B');self.assertEqual(1,validator(p.canonical(body),case)['rows'])
        body['rows'][0]['callerMethod']=True
        with self.assertRaisesRegex(ValueError,'capacity'):validator(p.canonical(body),case)

    def test_compilation_rejects_unbound_definition_universe_and_row_count(self):
        import jvm_pressure_oracles as jvm
        original,body=self.fixture()
        with self.assertRaisesRegex(ValueError,'compiled before requests'):p.validate_response(p.canonical(body),original,'B')
        for mutation in ('id','query','requested','target','family','columns','digest','rows','universe-scope'):
            case=copy.deepcopy(original);oracle=case['oracleByArm']['B']
            if mutation=='id':case['id']='unknown'
            elif mutation=='query':case['request']['body']['query']+=' LIMIT 1'
            elif mutation=='requested':case['request']['body']['graphs']=case['requestedGraphIds'][:1]
            elif mutation=='target':case['targetGraphIds']=case['targetGraphIds'][:2]
            elif mutation=='family':case['family']='feature-presence'
            elif mutation=='columns':case['columns']=['invented']
            elif mutation=='digest':oracle['digest']='0'*64
            elif mutation=='rows':oracle['rows']=True
            else:
                oracle['value']['requestedGraphIds']=oracle['value']['requestedGraphIds'][:2]
                oracle['digest']=jvm.digest(oracle['value'])
            with self.subTest(mutation=mutation),self.assertRaises(ValueError):p.compile_response_validator([case],'B')

    def test_all34_known_definitions_dispatch_but_do_not_relax_plan_authority(self):
        import jvm_pressure_oracles as jvm
        cases=[]; bodies={}
        for source in jvm.cases():
            case,body=self.fixture(source['id']); cases.append(case); bodies[case['id']]=body
        validator=p.compile_response_validator(cases,'B')
        self.assertEqual(34,len(cases))
        for case in cases:
            body=bodies[case['id']]
            self.assertEqual(body['rowCount'],validator(p.canonical(body),case)['rows'])
        legacy=make_plan();legacy['cases'][0]['oracleByArm']['B']=cases[0]['oracleByArm']['B']
        with self.assertRaises(ValueError):p.validate_plan(legacy,CATALOG)


class ExecutionAuthorityReuse(unittest.TestCase):
    """Tiny filesystem/Git protocols; semantic replay and HTTP launches are mocked.

    All closure/pin/config checks use their real implementations. The fixture is
    deliberately not a usable pressure plan or a substitute real64 authority.
    """
    def setUp(self):
        import subprocess
        import audit_native_pressure_artifacts as artifacts
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve(); self.checkout = self.root/'checkout'
        self.checkout.mkdir(); self.producer = self.root/'producer'; self.producer.mkdir()
        self.pins = {}
        def git(*args):
            return subprocess.check_output(['git', '-C', str(self.checkout), *args], stderr=subprocess.DEVNULL).decode().strip()
        self.git = git
        git('init', '-q'); git('config', 'user.name', 'Protocol'); git('config', 'user.email', 'protocol@example.invalid')
        (self.checkout/'.gitignore').write_text('frontend/jvm/webgraph/build/\n')
        (self.checkout/'source.kt').write_text('class Original\n')
        git('add', '.'); git('commit', '-qm', 'original')
        revision = git('rev-parse', 'HEAD')
        source_files = artifacts.source_inventory(self.checkout, revision); self.pins.update(source_files)
        env = {key: str(self.producer/key.lower()) for key in ('HOME', 'CARGO_HOME', 'GRADLE_USER_HOME')}
        self.env = env
        configuration = artifacts.configs(self.checkout, env)
        self.writer = self.checkout/'frontend/jvm/webgraph/build/libs/writer-jmh.jar'; self.file(self.writer, 'writer')
        self.phase = self.producer/'build-jvm'; phase = self.metadata(self.phase/'record.json', {'status': 'PASS'})
        packet = self.metadata(self.producer/'producer.json', {'buildEnvironment': env,
            'configurationBefore': configuration, 'configurationAfter': configuration,
            'phaseReceipts': {phase['path']: phase['sha256']}})
        source = self.metadata(self.producer/'source-manifest.json', {'root': str(self.checkout), 'revision': revision, 'files': source_files})
        artifact = self.metadata(self.producer/'artifact-audit.json', {'producerPacket': packet, 'sourceManifest': source})
        runtime_file = self.producer/'runtime/writer.jar'; self.file(runtime_file, 'writer')
        graph = self.producer/'graphs/g0'; self.file(graph/'graph.nodes', 'tiny raw node')
        self.raw = self.root/'raw'; self.metadata(self.raw/'plan.json', {})
        self.file(self.raw/'universes/case.json', '{}')
        self.query = self.root/'query'
        query_plan = self.metadata(self.query/'plan.json', {'rawDerivationRoot': str(self.raw)})
        query = self.metadata(self.query/'audit.json', {'plan': query_plan})
        self.file(self.query/'bodies/case.json', '{"value":1}')
        (self.query/'readiness-attempts').mkdir()
        self.core = self.root/'core'; upstream = {}
        for name in ('CStrings', 'BStrings', 'marker', 'formatterTests', 'formatterSource'):
            upstream[name] = self.metadata(self.root/name/'audit.json', {})
            self.file(self.root/name/'classes/Helper.class', 'class')
        self.file(self.root/'CStrings/exports/g0/strings.bin', 'strings')
        self.file(self.root/'formatterTests/results/xml/module/test.xml', '<testsuite/>')
        self.classpath = self.root/'test-classpath'; self.file(self.classpath/'Test.class', 'class')
        self.absent = self.root/'absent-classpath'
        for module in ('sootup', 'webgraph'):
            self.metadata(self.root/f'formatterTests/results/classpath-{module}-before.json', {
                'classpath': [{'path': str(self.classpath), 'kind': 'directory'}, {'path': str(self.absent), 'kind': 'absent'}]})
        self.metadata(self.core/'plan.json', {'upstream': upstream})
        core_ref = self.metadata(self.core/'audit.json', {})
        self.file(self.core/'graphs/g0/topology.json', '{}')
        arm = {'revision': revision, 'artifactAudit': artifact, 'queryAudit': query,
               'runtimeRoots': [str(self.producer/'runtime')], 'runtimeFiles': {str(runtime_file): self.pins[str(runtime_file)]},
               'graphs': [{'id': 'g0', 'path': str(graph)}]}
        authority = self.metadata(self.root/'authority.json', {'arms': {'B': arm}, 'correctedComparisons': {'B': {'audit': core_ref}}})
        self.plan = {'jvmProducerAuthority': authority, 'pins': dict(self.pins), 'proofs': [], 'arms': {'B': arm},
                     'cells': [{'id': str(i)} for i in range(6)]}
        self.plan_file = self.root/'pressure-plan.json'; p.save(self.plan_file, self.plan)
        self.stack = contextlib.ExitStack(); self.addCleanup(self.stack.close)
        self.replay = self.stack.enter_context(patch.object(p, 'validate_plan', side_effect=lambda value: value))

    def file(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True); path.write_text(value); self.pins[str(path)] = p.sha(path)

    def metadata(self, path, value):
        self.file(path, json.dumps(value)); return {'path': str(path), 'sha256': self.pins[str(path)]}

    def context(self):
        return p._VerifiedPressureExecution.open(self.plan_file)

    def test_six_cells_one_full_replay_and_six_real_raw_audit_calls(self):
        context = self.context(); calls = []
        def run(plan_path, cell, output, plan):
            calls.append(('run', cell)); Path(output).mkdir(parents=True); p.save(Path(output)/'raw.json', {'body': cell, 'cpu': 3, 'cleanup': []})
            return {'status': 'PASS'}
        def audit(plan_path, output, plan):
            raw = p.read(Path(output)/'raw.json'); self.assertEqual(raw, {'body': Path(output).name, 'cpu': 3, 'cleanup': []})
            calls.append(('audit', raw['body'])); return {'status': 'PASS'}
        with patch.object(p, '_run_cell', side_effect=run), patch.object(p, '_audit', side_effect=audit):
            for i in range(6):
                out = self.root/'execution'/str(i); context.run_cell(str(i), out); context.audit_cell(out)
        self.assertEqual(self.replay.call_count, 1)
        self.assertEqual(calls, [(op, str(i)) for i in range(6) for op in ('run', 'audit')])

    def test_native_corrected_mode_reuses_one_full_replay(self):
        self.plan['correctedProducerAuthority'] = self.plan.pop('jvmProducerAuthority')
        self.plan_file.write_text(json.dumps(self.plan)); context = self.context()
        with patch.object(p, '_run_cell', return_value={'status': 'PASS'}), patch.object(p, '_audit', return_value={'status': 'PASS'}):
            context.run_cell('0', self.root/'out'); context.audit_cell(self.root/'out')
        self.assertEqual(self.replay.call_count, 1)

    def test_public_run_and_audit_each_replay_upstream(self):
        with patch.object(p, '_run_cell', return_value={'status': 'PASS'}), patch.object(p, '_audit', return_value={'status': 'PASS'}):
            p.run_cell(self.plan_file, '0', self.root/'out'); p.audit(self.plan_file, self.root/'out')
        self.assertEqual(self.replay.call_count, 2)

    def test_corrected_native_mode_reuses_authority_but_rechecks_query_evidence(self):
        self.plan['correctedProducerAuthority'] = self.plan.pop('jvmProducerAuthority')
        self.plan_file.write_text(json.dumps(self.plan))
        context = self.context()
        with patch.object(p, '_run_cell', return_value={'status': 'PASS'}) as run, \
                patch.object(p, '_audit', return_value={'status': 'PASS'}) as audit:
            for i in range(6):
                output = self.root/'execution'/str(i)
                context.run_cell(str(i), output); context.audit_cell(output)
            self.assertEqual((self.replay.call_count, run.call_count, audit.call_count), (1, 6, 6))
            (self.query/'readiness-attempts/extra.body').write_text('changed')
            with self.assertRaisesRegex(ValueError, 'closure changed'):
                context.audit_cell(output)
            self.assertEqual(audit.call_count, 6)
        with self.assertRaisesRegex(ValueError, 'invalidated'): context._check_unchanged()

    def test_legacy_context_keeps_public_full_replay(self):
        del self.plan['jvmProducerAuthority']; self.plan_file.write_text(json.dumps(self.plan))
        context = self.context()
        with patch.object(p, '_run_cell', return_value={'status': 'PASS'}), patch.object(p, '_audit', return_value={'status': 'PASS'}):
            context.run_cell('0', self.root/'out'); context.audit_cell(self.root/'out')
        self.assertEqual(self.replay.call_count, 3)

    def test_cannot_construct_serialize_or_mutate_plan_copy(self):
        import pickle
        with self.assertRaises(TypeError): p._VerifiedPressureExecution()
        context = self.context()
        with self.assertRaises(TypeError): pickle.dumps(context)
        context.plan['cells'].clear(); self.assertEqual(len(context.plan['cells']), 6)
        context._check_unchanged()

    def assert_mutation_rejected(self, mutate):
        context = self.context(); mutate()
        with patch.object(p, '_run_cell') as run:
            with self.assertRaises((ValueError, OSError)): context.run_cell('0', self.root/'execution')
            run.assert_not_called()
        with self.assertRaisesRegex(ValueError, 'invalidated'): context._check_unchanged()

    def test_tracked_dirty_source_rejected(self):
        self.assert_mutation_rejected(lambda: (self.checkout/'source.kt').write_text('class Changed'))

    def test_untracked_source_addition_rejected(self):
        self.assert_mutation_rejected(lambda: (self.checkout/'new.kt').write_text('class Added'))

    def test_changed_source_head_rejected_even_same_tree(self):
        self.assert_mutation_rejected(lambda: self.git('commit', '--allow-empty', '-qm', 'new revision'))

    def test_absent_configuration_appearing_rejected(self):
        def mutate():
            path = Path(self.env['GRADLE_USER_HOME'])/'gradle.properties'; path.parent.mkdir(); path.write_text('changed=true')
        self.assert_mutation_rejected(mutate)

    def test_absent_init_directory_appearing_rejected(self):
        self.assert_mutation_rejected(lambda: (Path(self.env['GRADLE_USER_HOME'])/'init.d').mkdir(parents=True))

    def test_source_writer_added_ignored_jar_rejected(self):
        self.assert_mutation_rejected(lambda: (self.writer.parent/'added-jmh.jar').write_text('other'))

    def test_absent_classpath_appearing_rejected(self):
        self.assert_mutation_rejected(lambda: self.absent.mkdir())

    def test_classpath_new_class_rejected(self):
        self.assert_mutation_rejected(lambda: (self.classpath/'Extra.class').write_text('class'))

    def test_new_empty_directory_rejected(self):
        self.assert_mutation_rejected(lambda: (self.query/'readiness-attempts/extra').mkdir())

    def test_new_xml_rejected(self):
        self.assert_mutation_rejected(lambda: (self.root/'formatterTests/results/xml/extra.xml').write_text('<testsuite/>'))

    def test_new_string_export_rejected(self):
        self.assert_mutation_rejected(lambda: (self.root/'CStrings/exports/g0/new.bin').write_text('extra'))

    def test_deleted_core_payload_rejected(self):
        self.assert_mutation_rejected(lambda: (self.core/'graphs/g0/topology.json').unlink())

    def test_symlink_substitution_rejected(self):
        def mutate():
            target = self.root/'external'; target.write_text('class')
            path = self.classpath/'Test.class'; path.unlink(); path.symlink_to(target)
        self.assert_mutation_rejected(mutate)

    def test_same_bytes_symlink_root_rejected(self):
        def mutate():
            other = self.root/'relocated'; self.classpath.rename(other); self.classpath.symlink_to(other, target_is_directory=True)
        self.assert_mutation_rejected(mutate)

    def test_plan_same_bytes_symlink_substitution_rejected(self):
        def mutate():
            other = self.root/'plan-copy.json'; other.write_bytes(self.plan_file.read_bytes())
            self.plan_file.unlink(); self.plan_file.symlink_to(other)
        self.assert_mutation_rejected(mutate)

    def test_plan_repinned_to_changed_raw_input_rejected(self):
        def mutate():
            path = self.raw/'universes/case.json'; path.write_text('{"changed":true}')
            self.plan['pins'][str(path)] = p.sha(path); self.plan_file.write_text(json.dumps(self.plan))
        self.assert_mutation_rejected(mutate)

    def test_capture_is_bracketed_by_full_authority_replay(self):
        def changed(plan):
            (self.classpath/'New.class').write_text('class'); return plan
        self.replay.side_effect = changed
        with self.assertRaisesRegex(ValueError, 'during complete'): self.context()

    def test_mutation_during_run_or_raw_audit_invalidates(self):
        for operation in ('run', 'audit'):
            with self.subTest(operation=operation):
                context = self.context()
                def changed(*args):
                    (self.query/'readiness-attempts/extra').mkdir(); return {'status': 'PASS'}
                with patch.object(p, '_run_cell', side_effect=changed), patch.object(p, '_audit', side_effect=changed):
                    with self.assertRaisesRegex(ValueError, 'closure changed'):
                        (context.run_cell('0', self.root/'out') if operation == 'run' else context.audit_cell(self.root/'out'))
                (self.query/'readiness-attempts/extra').rmdir()
                with self.assertRaisesRegex(ValueError, 'invalidated'): context._check_unchanged()

    def test_failed_operation_permanently_invalidates(self):
        context = self.context()
        with patch.object(p, '_run_cell', return_value={'status': 'FAIL'}):
            with self.assertRaisesRegex(ValueError, 'cell execution/audit failed'): context.run_cell('0', self.root/'out')
        with self.assertRaisesRegex(ValueError, 'invalidated'): context.audit_cell(self.root/'out')

    def test_output_cannot_mutate_frozen_upstream_tree(self):
        context = self.context()
        with patch.object(p, '_run_cell') as run:
            with self.assertRaisesRegex(ValueError, 'overlaps'): context.run_cell('0', self.raw/'new-output')
            run.assert_not_called()

    def test_raw_response_resource_cleanup_validation_is_never_cached(self):
        context = self.context(); output = self.root/'out'; output.mkdir()
        path = output/'raw.json'; good = {'body': 1, 'cpu': 3, 'cleanup': []}; p.save(path, good)
        def audit(*args):
            p.require(p.read(path) == good, 'raw evidence changed'); return {'status': 'PASS'}
        with patch.object(p, '_audit', side_effect=audit) as checked:
            context.audit_cell(output); path.write_text(json.dumps({**good, 'cleanup': ['live-child']}))
            with self.assertRaisesRegex(ValueError, 'raw evidence changed'): context.audit_cell(output)
        self.assertEqual(checked.call_count, 2)

    def actual_audit_fixture(self):
        """Arbitrary protocol timestamps, never measurements or performance evidence."""
        output = self.root/'actual-audit'; output.mkdir()
        body = p.canonical({'columns': ['v'], 'rows': [{'v': 7}]})
        case = {'id': 'tiny', 'request': {'endpoint': '/api/cypher', 'body': {'query': 'RETURN 7 AS v'}},
                'oracleByArm': {'B': {'kind': 'native-full-json-sha256-v1', 'rows': 1, 'digest': p.digest_bytes(body)}}}
        self.plan.update(engine='native', operation='query', cases=[case], limits={'requestSeconds': 60},
                         coverage={'unavailableFamilies': ['synthetic-protocol-only']}, cells=[{'id': '0', 'arm': 'B', 'port': 23333}])
        arm = self.plan['arms']['B']; arm['serverArgv'] = ['/never-executed/graphite', 'serve']
        arm['readiness'] = {'expected': {'count': 1, 'graphs': [{'id': 'g0', 'nodes': 1, 'edges': 0}]}}
        self.plan_file.write_text(json.dumps(self.plan)); p.save(output/'plan.json', self.plan)
        for name in ('identities-before.json', 'identities-after.json'): p.save(output/name, {'status': 'PASS', 'pins': self.plan['pins']})
        readiness = copy.deepcopy(arm['readiness']['expected']); readiness['data'] = str(output/'data')
        readiness['graphs'][0]['loadedAt'] = '2026-10-10T00:00:00Z'; (output/'readiness.body').write_bytes(p.canonical(readiness))
        command = ['/usr/bin/time', '-l', '--', *arm['serverArgv'], '--data', str(output/'data'), '--port', '23333',
                   '--load-mode', 'MAPPED', '--max-concurrent-cypher', '4', '--cypher-max-timeout-ms', '60000', '--metrics', *p.graph_arguments(arm['graphs'])]
        p.save(output/'command.json', command)
        cleanup = {'group': 22, 'after': [], 'errors': [], 'exit': 0, 'signals': []}
        p.save(output/'cleanup.json', cleanup); p.save(output/'launch-owner.json', {'group': 22, 'runnerPid': 23})
        (output/'server.pid').write_text('22')
        (output/'time-v.log').write_text('1.0 real 0.1 user 0.1 sys\n1048576 maximum resident set size\n')
        lifecycle = p.lifecycle_time(output/'time-v.log'); stages = {}; samples = []
        def sample(start, end): return p.parse_macos_sample('Fri Oct  9 12:03:04 2026 00:04.25 1024', 22, start, end)
        for index, (name, count) in enumerate((('oracle', 1), ('warmup', 2), ('pressure', 20))):
            directory = output/name; (directory/'bodies').mkdir(parents=True); records = []; begin = 1000*index+100
            for i in range(count):
                start = begin+10*i; (directory/f'bodies/{i:06}.body').write_bytes(body)
                records.append({'worker': 0, 'sequence': i, 'caseId': 'tiny', 'status': 'PASS', 'bodyFile': f'bodies/{i:06}.body',
                    'endpoint': '/api/cypher', 'requestBodySha256': p.digest_bytes(p.canonical(case['request']['body'])),
                    'httpStatus': 200, 'completeBody': True, 'deadlineExpired': False, 'startNs': start,
                    'wireCompleteNs': start+2, 'validationCompleteNs': start+5, 'latencyNs': 5,
                    'bodyBytes': len(body), 'bodySha256': p.digest_bytes(body), 'canonicalSha256': p.digest_bytes(body), 'rows': 1})
            end = records[-1]['validationCompleteNs']; inner = sample(begin+1, begin+2); samples.append(inner)
            stage = {'status': 'PASS', 'requests': records, 'unissued': [], 'allWorkersStopped': True, 'journalErrors': [],
                'cpuStart': sample(begin-10, begin-9), 'cpuEnd': sample(end+10, end+11), 'startNs': begin, 'endNs': end,
                'observedMaxWorkerConcurrency': 1, 'observedMaxWireConcurrency': 1, 'caseStatistics': p.statistics(records)}
            for worker in range(4):
                (directory/f'worker-{worker}.jsonl').write_text(''.join(json.dumps(row)+'\n' for row in records if row['worker'] == worker))
            p.save(directory/'stage.json', stage)
            stages[name] = {**stage, 'resources': p.resource_summary(stage, [inner], lifecycle)}
        for stage in stages.values(): stage['resources'] = p.resource_summary(stage, samples, lifecycle)
        (output/'resources.jsonl').write_text(''.join(json.dumps(row)+'\n' for row in samples))
        result = {'schema': p.RESULT_SCHEMA, 'planSha256': p.sha(self.plan_file), 'status': 'PASS', 'errors': [],
            'acceptanceEligible': False, 'cell': self.plan['cells'][0], 'arm': arm, 'engine': 'native', 'operation': 'query',
            'coverage': self.plan['coverage'], 'otherOperationsEligible': False, 'cleanup': cleanup, 'lifecycle': lifecycle, 'stages': stages}
        p.save(output/'result.json', result)
        return output

    def test_actual_raw_audit_pass_then_body_resource_and_cleanup_tampering_rejected(self):
        output = self.actual_audit_fixture()
        for filename, mutation, message in (
                ('pressure/bodies/000000.body', lambda raw: raw.replace('7', '8'), 'raw body'),
                ('resources.jsonl', lambda raw: raw.replace('"rssBytes": 1048576', '"rssBytes": 1048577'), 'raw macOS sample'),
                ('cleanup.json', lambda raw: raw.replace('"after": []', '"after": [22]'), 'cleanup proof')):
            with self.subTest(filename=filename):
                context = self.context(); result = context.audit_cell(output)
                self.assertEqual(result['completeBodies'], 23); self.assertFalse(result['acceptanceEligible'])
                path = output/filename; original = path.read_text(); changed = mutation(original)
                self.assertNotEqual(original, changed); path.write_text(changed)
                with self.assertRaisesRegex(ValueError, message): context.audit_cell(output)
                path.write_text(original)
                with self.assertRaisesRegex(ValueError, 'invalidated'): context.audit_cell(output)


if __name__ == '__main__':
    unittest.main(verbosity=2)
