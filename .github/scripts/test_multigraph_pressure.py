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


if __name__ == '__main__':
    unittest.main(verbosity=2)
