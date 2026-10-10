#!/usr/bin/env python3
"""Fixed zero-query JVM64 loading, using the CI producer's sealed CLI package.

The 194 launch/readiness protocol is retained. Linux ready VmHWM, not lifetime
GNU time RSS, bounds the loading peak. No query or extra runtime build occurs.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
import zipfile

sys.dont_write_bytecode = True
import audit_native_pressure_artifacts as artifacts
import multigraph_pressure as common
import run_real64_construction as construction
from run_native_query_correctness import persisted_readiness
from native_core_proof.method_classfile import parse as parse_class

require = common.require
FALSE = construction.FALSE
SCHEMA = 'graphite.jvm64-loading.plan.v1'
CELL = 'graphite.jvm64-loading.cell.v1'
EXECUTION = 'graphite.jvm64-loading.execution.v1'
SCOPE = {
    'boundary': 'Immediately before wrapper spawn through complete /api/graphs consumption and exact64 validation',
    'readiness': 'All64 MAPPED registrations and saved counts; includes required startup topology work',
    'cpu': 'Process-zero cumulative user+system CPU; in-boundary sample lower, first post-ready sample upper, with two ticks uncertainty',
    'rss': 'In-boundary sampled peak lower; first post-ready Linux VmHWM upper; GNU time lifetime RSS is only corroboration',
    'cache': 'Matched full closed-input reads before every process; uncontrolled OS cache; no disk-cold claim',
    'deferredWork': 'Registry-ready only; deferred first-query work is not counted as already paid',
    'aggregation': 'Fixed CABBAC and two directions; no pooling, retry selection, quiet-host or saturation claim',
}


def entrypoint(jar):
    names = ['io/johnsonlee/graphite/cli/'+name+'.class' for name in
             ('MainKt', 'GraphiteCommand', 'ServeCommand', 'GraphRegistry', 'ExploreRoutes')]
    names += ['picocli/CommandLine.class', 'io/javalin/Javalin.class']
    with zipfile.ZipFile(jar) as archive:
        entries = archive.namelist()
        require(all(entries.count(name) == 1 for name in names), 'actual packaged MainKt serve entrypoint and dependencies')
        main = archive.read(names[0])
        methods = parse_class(main)['methods']
        require(any(m['name'] == 'main' and m['descriptor'] == '([Ljava/lang/String;)V'
                    and m['accessFlags'] & 9 == 9 for m in methods), 'public static packaged main')
        require(b'Lio/johnsonlee/graphite/cli/ServeCommand;' in archive.read(names[1]), 'packaged serve subcommand')
        serve = archive.read(names[2])
        require(all(flag.encode() in serve for flag in ('--graph', '--data', '--port', '--load-mode',
                '--max-concurrent-cypher', '--cypher-max-timeout-ms', '--metrics')), 'packaged server options')
        return {name: common.digest_bytes(archive.read(name)) for name in names}


def prepare(packet, base, candidate):
    plan = construction.prepare(packet, base, candidate)
    producer = common.read(plan['producers']['path'])
    plan.update(schema=SCHEMA, operation='loading', queriesIssued=0, scope=SCOPE)
    plan.pop('timeouts'); plan.pop('cachePolicy')
    plan.pop('retentionPolicy'); plan.pop('retentionSources')
    plan['limits'] = {'readinessSeconds': 900, 'rssIntervalSeconds': .01}
    plan['python'] = str(Path(sys.executable).resolve())
    plan['pins'][plan['python']] = common.sha(plan['python'])
    for name, arm in plan['arms'].items():
        original = producer['arms'][name]
        fixture = common.pinned_authority_metadata(producer, original['fixtureManifest'])
        runtime = common.pinned_authority_metadata(plan, arm['runtimeManifest'])
        jar = str(Path(arm['runtimeManifest']['path']).parent/'runtime/graphite.jar')
        require(runtime['files'].get(jar) == plan['pins'].get(jar), 'sealed source-built JVM CLI bytes')
        arm.update(serverJar={'path': jar, 'sha256': runtime['files'][jar]}, packagedEntry=entrypoint(jar),
                   serverArgv=[arm['argv'][0], '-Xmx4g', '-XX:ActiveProcessorCount=4', '-cp', jar,
                               'io.johnsonlee.graphite.cli.MainKt', 'serve'],
                   fixtureManifest=original['fixtureManifest'], graphs=original['graphs'],
                   readiness={'path': '/api/graphs', 'expected': persisted_readiness(fixture)})
    for i, cell in enumerate(plan['cells']):
        cell['port'] = 22960 + i
    return plan


def validate(plan):
    require(plan['schema'] == SCHEMA, 'JVM loading plan schema')
    expected = prepare(plan['producers']['path'], plan['arms']['A']['revision'], plan['arms']['B']['revision'])
    require(common.typed(plan) == common.typed(expected), 'exact JVM zero-query loading plan')
    return plan


def verify_inputs(plan):
    artifacts.verify_pins(plan['pins'])
    for arm in plan['arms'].values():
        construction.verify_writer(plan, arm)
        fixture = common.pinned_authority_metadata(plan, arm['fixtureManifest'])
        require(artifacts.inventory(Path(arm['fixtureManifest']['path']).parent/'graphs') == fixture['files'],
                'closed unchanged saved64 inventory')
    return {'pins': plan['pins']}


def sample_valid(row):
    require(row['backend'] == 'linux-proc' and type(row['ticksPerSecond']) is int and row['ticksPerSecond'] > 0
            and type(row['pageSize']) is int and row['pageSize'] > 0,
            'Linux process sample units')
    common.validate_resource_sample(row)
    require(row['userTicks'] >= 0 and row['systemTicks'] >= 0 and row['rssBytes'] >= 0, 'nonnegative raw process resources')


def ready_watermark(pid, identity):
    start = time.perf_counter_ns()
    raw = (Path('/proc')/str(pid)/'status').read_text()
    end = time.perf_counter_ns()
    rows = re.findall(r'^VmHWM:\s+(\d+)\s+kB\s*$', raw, re.M)
    ids = re.findall(r'^Pid:\s+(\d+)\s*$', raw, re.M)
    require(len(rows) == len(ids) == len(re.findall(r'^VmHWM:', raw, re.M))
            == len(re.findall(r'^Pid:', raw, re.M)) == 1 and int(ids[0]) == pid and int(rows[0]) > 0,
            'actual post-ready VmHWM')
    after = common.proc_sample(pid, identity)
    sample_valid(after)
    return {'pid': pid, 'startTicks': identity, 'readStartNs': start, 'readEndNs': end,
            'peakRssBytes': int(rows[0])*1024, 'raw': raw, 'identityAfter': after}


def bounds(start, ready, after, samples, watermark):
    require(start <= ready <= after['readStartNs'] <= after['readEndNs'] <= watermark['readStartNs']
            <= watermark['readEndNs'] <= watermark['identityAfter']['readStartNs'], 'loading boundary reads')
    identity = ('pid', 'startTicks', 'ticksPerSecond', 'pageSize', 'backend')
    for sample in [after, *samples, watermark['identityAfter']]:
        sample_valid(sample)
        require(all(sample[key] == after[key] for key in identity), 'same live process and units throughout loading')
    require(watermark['pid'] == after['pid'] and watermark['startTicks'] == after['startTicks'], 'VmHWM process identity')
    inside = [row for row in samples if start <= row['readStartNs'] and row['readEndNs'] <= ready]
    require(inside, 'complete sample inside loading required')
    uncertainty = 2 / after['ticksPerSecond']
    cpu = [max(0, max(row['cpuSeconds'] for row in inside)-uncertainty), after['cpuSeconds']+uncertainty]
    rss = [max(row['rssBytes'] for row in inside), watermark['peakRssBytes']]
    require(cpu[0] <= cpu[1] and 0 < rss[0] <= rss[1], 'loading resource intervals')
    return {'wallMs': (ready-start)/1e6, 'cpuBoundsSeconds': cpu, 'rssBoundsBytes': rss,
            'cpuQuantizationSeconds': uncertainty, 'insideSamples': len(inside),
            'excludedSamples': len(samples)-len(inside), 'cpuReadAfterReadyNs': after['readEndNs']-ready,
            'rssReadAfterReadyNs': watermark['readEndNs']-ready}


def command(plan, arm, cell, out):
    args = [*arm['serverArgv'], '--data', str(out/'data'), '--port', str(cell['port']), '--load-mode', 'MAPPED',
            '--max-concurrent-cypher', '4', '--cypher-max-timeout-ms', '240000', '--metrics', *common.graph_arguments(arm['graphs'])]
    return ['/usr/bin/time', '-v', '-o', str(out/'time-v.log'), plan['python'],
            str(Path(common.__file__).resolve()), '_exec-server', '--pid-file', str(out/'server.pid'), '--', *args]


def run_cell(plan_file, plan, cell, out):
    out = Path(out).resolve(); out.mkdir(exist_ok=False)
    arm = plan['arms'][cell['arm']]
    record = {'schema': CELL, 'status': 'RUNNING', 'engine': 'jvm', 'operation': 'loading', 'graphCount': 64,
              'queriesIssued': 0, 'cell': cell, 'planSha256': common.sha(plan_file), 'errors': [], **FALSE}
    common.save(out/'running.json', record)
    proc = pid = identity = monitor = None
    handlers = {}
    def interrupted(sig, frame): raise InterruptedError('signal ' + str(sig))
    try:
        for sig in (signal.SIGINT, signal.SIGTERM): handlers[sig] = signal.signal(sig, interrupted)
        common.save(out/'identities-before.json', verify_inputs(plan))
        argv = command(plan, arm, cell, out); common.save(out/'command.json', argv)
        with socket.socket() as probe: probe.bind(('127.0.0.1', cell['port']))
        with common.deferred_signals():
            with (out/'stdout.log').open('x') as stdout, (out/'stderr.log').open('x') as stderr:
                record['startNs'] = time.perf_counter_ns()
                proc = subprocess.Popen(argv, stdout=stdout, stderr=stderr, start_new_session=True, env=construction.environment())
            common.save(out/'owner.json', {'runnerPid': os.getpid(), 'group': proc.pid})
        deadline = time.monotonic()+10
        while not (out/'server.pid').exists():
            require(proc.poll() is None and time.monotonic() < deadline, 'owned PID publication')
            time.sleep(.01)
        pid = int((out/'server.pid').read_text()); require(os.getpgid(pid) == proc.pid, 'owned server group')
        initial = common.proc_sample(pid); sample_valid(initial); identity = initial['startTicks']
        common.save(out/'process-identity.json', {'pid': pid, 'group': proc.pid, 'startTicks': identity, 'initialSample': initial})
        monitor = common.Monitor(lambda: common.proc_sample(pid, identity), plan['limits']['rssIntervalSeconds'], out/'resources.jsonl')
        monitor.thread.start()
        raw = common.readiness(cell['port'], arm, out/'data', plan['limits']['readinessSeconds'], proc, out)
        record['readyNs'] = time.perf_counter_ns()
        record['cpuAfterReady'] = common.proc_sample(pid, identity)
        record['readyWatermark'] = ready_watermark(pid, identity)
        (out/'readiness.body').write_bytes(raw)
        require(proc.poll() is None, 'server remained alive through readiness measurement')
        record['status'] = 'PASS_ZERO_QUERY_READY64'
    except BaseException as error:
        record.update(status='FAIL', errors=[repr(error)])
    finally:
        for sig in handlers: signal.signal(sig, signal.SIG_IGN)
        try:
            if monitor:
                try: monitor.finish()
                except BaseException as error: record['errors'].append('monitor: '+repr(error))
            try:
                record['cleanup'] = common.stop_owned(proc, pid, identity)
                cleanup = record['cleanup']
                require(cleanup['after'] == cleanup['errors'] == [] and
                        not any('KILL' in row['signal'] for row in cleanup.get('signals', [])), 'owned normal loading cleanup')
                if proc: record['lifecycle'] = common.lifecycle_time(out/'time-v.log')
                if record['status'] == 'PASS_ZERO_QUERY_READY64':
                    record['resources'] = bounds(record['startNs'], record['readyNs'], record['cpuAfterReady'],
                                                  monitor.samples, record['readyWatermark'])
                    require(record['lifecycle']['peakRssBytes'] >= record['readyWatermark']['peakRssBytes'], 'lifetime peak corroborates ready VmHWM')
            except BaseException as error: record['errors'].append('cleanup/resources: '+repr(error))
            try:
                common.save(out/'identities-after.json', verify_inputs(plan))
                require(common.sha(plan_file) == record['planSha256'], 'loading plan changed')
            except BaseException as error: record['errors'].append('final identity: '+repr(error))
            if record['errors']: record['status'] = 'FAIL'
            common.save(out/'result.json', record)
        finally:
            for sig, handler in handlers.items(): signal.signal(sig, handler)
    return record


def run(plan_file, out):
    require(sys.platform == 'linux', 'reviewed Linux loading resource backend')
    plan_file, out = Path(plan_file).resolve(), Path(out).resolve()
    plan = validate(common.read(plan_file)); out.mkdir(exist_ok=False)
    record = {'schema': EXECUTION, 'status': 'RUNNING', 'planSha256': common.sha(plan_file), 'cells': [],
              'unissued': [c['id'] for c in plan['cells']], 'errors': [], **FALSE}
    handlers = {}
    def interrupted(sig, frame): raise InterruptedError('signal ' + str(sig))
    try:
        for sig in (signal.SIGINT, signal.SIGTERM): handlers[sig] = signal.signal(sig, interrupted)
        for cell in plan['cells']:
            row = {'cell': cell, 'status': 'RUNNING'}; record['cells'].append(row); record['unissued'].remove(cell['id'])
            construction.save_progress(out/'execution.json', record)
            result = run_cell(plan_file, plan, cell, out/cell['id'])
            row['status'] = result['status']; row['resultSha256'] = common.sha(out/cell['id']/'result.json')
            require(result['status'] == 'PASS_ZERO_QUERY_READY64', 'loading cell failed '+cell['id'])
        record['status'] = 'PASS_ALL_SIX_ZERO_QUERY_READY64'
    except BaseException as error:
        record.update(status='FAIL', errors=[repr(error)])
    finally:
        for sig in handlers: signal.signal(sig, signal.SIG_IGN)
        try: construction.save_progress(out/'execution.json', record)
        finally:
            for sig, handler in handlers.items(): signal.signal(sig, handler)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('producers', 'base-sha', 'candidate-sha', 'output', 'prefix'): parser.add_argument('--'+name, required=True)
    args = parser.parse_args(); out, prefix = Path(args.output).resolve(), Path(args.prefix).resolve()
    require(not out.exists(), 'fresh JVM loading output'); out.mkdir(parents=True)
    measured = False
    try:
        plan = prepare(args.producers, args.base_sha, args.candidate_sha); common.save(out/'plan.json', plan)
        measured = True; result = run(out/'plan.json', out/'cells')
        require(result['status'] == 'PASS_ALL_SIX_ZERO_QUERY_READY64', 'loading failed; original cells retained')
        from audit_real64_loading import audit
        try: common.save(out/'audit.json', audit(out/'plan.json', out/'cells'))
        except BaseException as error:
            common.save(out/'audit.json', {'schema': 'graphite.jvm64-loading.audit.v1', 'status': 'FAIL', 'errors': [repr(error)], **FALSE})
            raise
        node = shutil.which('node'); require(node is not None, 'Node executable missing')
        argv = [node, str(Path(__file__).with_name('benchmark-loading.mjs')), str(out/'plan.json'), str(out), str(prefix)]
        common.save(out/'comparison-command.json', argv)
        completed = subprocess.run(argv, capture_output=True, text=True, timeout=60, check=False)
        (out/'comparison.stdout.log').write_text(completed.stdout); (out/'comparison.stderr.log').write_text(completed.stderr)
        verdict = common.read(str(prefix)+'-status.json')
        require(completed.returncode in (0, 1) and verdict['passed'] == (completed.returncode == 0)
                and verdict['planSha256'] == common.sha(out/'plan.json'), 'actual loading comparison')
        artifacts.verify_pins({argv[1]: plan['pins'][argv[1]]})
        common.save(out/'comparison-exit.json', {'exit': completed.returncode, 'statusSha256': common.sha(str(prefix)+'-status.json')})
        return completed.returncode
    except BaseException as error:
        value = {'schema': 'graphite.jvm64-loading.comparison.v1', 'engine': 'jvm', 'operation': 'loading',
                 'status': 'FAIL' if measured else 'UNAVAILABLE', 'passed': False, 'errors': [repr(error)], **FALSE}
        common.save(out/'failure.json', value); prefix.parent.mkdir(parents=True, exist_ok=True)
        construction.save_progress(str(prefix)+'-status.json', value)
        Path(str(prefix)+'-report.md').write_text('### JVM zero-query64 loading\n\n'+value['status']+'\n\n'+repr(error)+'\n')
        return 1


if __name__ == '__main__': sys.exit(main())
