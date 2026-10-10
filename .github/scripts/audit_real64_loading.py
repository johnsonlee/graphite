#!/usr/bin/env python3
"""Independent reconstruction of zero-query JVM readiness from Linux raw files."""
import argparse
from pathlib import Path
import re
import sys
sys.dont_write_bytecode = True
import audit_native_pressure_artifacts as artifacts
import multigraph_pressure as common
import run_real64_loading as loading

require = common.require
SCHEMA = 'graphite.jvm64-loading.audit.v1'


def reconstruct(record, samples):
    start, ready, after, high = (record[k] for k in ('startNs', 'readyNs', 'cpuAfterReady', 'readyWatermark'))
    require(start <= ready <= after['readStartNs'] <= after['readEndNs'] <= high['readStartNs']
            <= high['readEndNs'] <= high['identityAfter']['readStartNs'], 'ordered launch/ready/CPU/VmHWM boundaries')
    fields = ('pid', 'startTicks', 'ticksPerSecond', 'pageSize', 'backend')
    for row in [after, *samples, high['identityAfter']]:
        require(row['backend'] == 'linux-proc' and type(row['ticksPerSecond']) is int and row['ticksPerSecond'] > 0
                and type(row['pageSize']) is int and row['pageSize'] > 0, 'actual Linux resource units')
        common.validate_resource_sample(row)
        require(all(row[k] == after[k] for k in fields) and row['userTicks'] >= 0
                and row['systemTicks'] >= 0 and row['rssBytes'] >= 0, 'same live process and nonnegative samples')
    ids = re.findall(r'^Pid:\s+(\d+)\s*$', high['raw'], re.M)
    peaks = re.findall(r'^VmHWM:\s+(\d+)\s+kB\s*$', high['raw'], re.M)
    require(len(ids) == len(peaks) == len(re.findall(r'^VmHWM:', high['raw'], re.M))
            == len(re.findall(r'^Pid:', high['raw'], re.M)) == 1 and int(ids[0]) == high['pid'] == after['pid']
            and high['startTicks'] == after['startTicks'] and int(peaks[0])*1024 == high['peakRssBytes'],
            'original ready VmHWM identity and units')
    inner = [r for r in samples if start <= r['readStartNs'] and r['readEndNs'] <= ready]
    require(inner, 'complete raw sample inside loading')
    uncertainty = 2/after['ticksPerSecond']
    cpu = [max(0, max(r['cpuSeconds'] for r in inner)-uncertainty), after['cpuSeconds']+uncertainty]
    rss = [max(r['rssBytes'] for r in inner), high['peakRssBytes']]
    require(cpu[0] <= cpu[1] and 0 < rss[0] <= rss[1], 'reconstructed resource bounds')
    return {'wallMs': (ready-start)/1e6, 'cpuBoundsSeconds': cpu, 'rssBoundsBytes': rss,
            'cpuQuantizationSeconds': uncertainty, 'insideSamples': len(inner), 'excludedSamples': len(samples)-len(inner),
            'cpuReadAfterReadyNs': after['readEndNs']-ready, 'rssReadAfterReadyNs': high['readEndNs']-ready}


def audit(plan_file, root):
    plan_file, root = Path(plan_file).resolve(), Path(root).resolve()
    plan = loading.validate(common.read(plan_file)); digest = common.sha(plan_file)
    execution = common.read(root/'execution.json')
    require(execution['schema'] == loading.EXECUTION and execution['status'] == 'PASS_ALL_SIX_ZERO_QUERY_READY64'
            and execution['errors'] == execution['unissued'] == [] and execution['planSha256'] == digest
            and all(execution[k] is False for k in loading.FALSE), 'complete bounded loading execution')
    require([r['cell'] for r in execution['cells']] == plan['cells'], 'all six fixed CABBAC attempts')
    rows, groups = [], []
    for cell, receipt in zip(plan['cells'], execution['cells']):
        out, arm = root/cell['id'], plan['arms'][cell['arm']]
        record = common.read(out/'result.json')
        require(receipt['resultSha256'] == common.sha(out/'result.json') and receipt['status'] == record['status']
                == 'PASS_ZERO_QUERY_READY64' and record['schema'] == loading.CELL and record['engine'] == 'jvm'
                and record['operation'] == 'loading' and record['graphCount'] == 64 and record['queriesIssued'] == 0
                and record['cell'] == cell and record['planSha256'] == digest and record['errors'] == []
                and all(record[k] is False for k in loading.FALSE), 'actual zero-query JVM64 cell')
        require(common.read(out/'identities-before.json') == common.read(out/'identities-after.json')
                == {'pins': plan['pins']}, 'closed unchanged loading inputs')
        require(common.read(out/'command.json') == loading.command(plan, arm, cell, out), 'exact bounded actual server command')
        common.validate_readiness_body((out/'readiness.body').read_bytes(), arm, out/'data')
        require(not any((out/name).exists() for name in ('oracle','warmup','pressure','bodies','requests.jsonl')),
                'no query stages in loading evidence')
        owner, published, cleanup = common.read(out/'owner.json'), common.read(out/'process-identity.json'), record['cleanup']
        pid = int((out/'server.pid').read_text()); initial = published['initialSample']
        common.validate_resource_sample(initial)
        require(owner['group'] == published['group'] == cleanup['group'] and owner['group'] != owner['runnerPid']
                and type(owner['group']) is int and owner['group'] > 1 and cleanup['after'] == cleanup['errors'] == []
                and cleanup['exit'] in (0, 143, -15) and not any('KILL' in r['signal'] for r in cleanup['signals']),
                'normal owned process cleanup')
        groups.append(owner['group'])
        require(pid == published['pid'] == initial['pid'] == record['cpuAfterReady']['pid']
                and published['startTicks'] == initial['startTicks'] == record['cpuAfterReady']['startTicks']
                and record['startNs'] <= initial['readStartNs'] <= initial['readEndNs'] <= record['readyNs'],
                'published process identity inside loading')
        samples = [common.parse(row) for row in (out/'resources.jsonl').read_bytes().splitlines()]
        resources = reconstruct(record, samples)
        require(resources == record['resources'], 'independent loading resource reconstruction')
        life = common.lifecycle_time(out/'time-v.log')
        require(life == record['lifecycle'] and life['peakRssBytes'] >= record['readyWatermark']['peakRssBytes'],
                'lifetime resources corroborate but do not replace ready watermark')
        raw = [out/name for name in ('result.json','command.json','owner.json','process-identity.json','server.pid',
                'readiness.body','resources.jsonl','time-v.log','stdout.log','stderr.log','identities-before.json','identities-after.json')]
        raw += sorted(out.glob('readiness-attempt-*'))
        rows.append({'cell': cell, 'revision': arm['revision'], 'serverJar': arm['serverJar'], 'resources': resources,
                     'rawPins': {str(p.relative_to(root)): common.sha(p) for p in raw}})
    require(len(set(groups)) == 6, 'six distinct owned JVM processes')
    loading.verify_inputs(plan); require(common.sha(plan_file) == digest, 'loading plan unchanged after audit')
    return {'schema': SCHEMA, 'status': 'PASS_RAW_ZERO_QUERY_LOADING_AUDIT', 'engine': 'jvm', 'operation': 'loading',
            'planSha256': digest, 'executionSha256': common.sha(root/'execution.json'), 'rows': rows,
            'scope': loading.SCOPE, 'comparisonModel': plan['comparisonModel'],
            'correctedComparability': plan['producers'], **loading.FALSE}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('plan','root','output'): parser.add_argument('--'+name, required=True)
    args = parser.parse_args(); require(not Path(args.output).exists(), 'fresh loading audit receipt')
    try: result = audit(args.plan,args.root)
    except BaseException as error:
        common.save(args.output, {'schema': SCHEMA, 'status': 'FAIL', 'errors': [repr(error)], **loading.FALSE}); return 1
    common.save(args.output,result); return 0


if __name__ == '__main__': sys.exit(main())
