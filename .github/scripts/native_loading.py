"""Prospective Native64 registry-readiness evidence inside existing query processes.

No new launches or response replay. The caller has already consumed/validated
readiness and retains the original resource stream and request records.
"""
from pathlib import Path
import multigraph_pressure as common
from run_real64_loading import ready_watermark, bounds
from audit_real64_loading import reconstruct

DECLARATION = {
    'schema': 'graphite.native64-loading.measurement.v1',
    'engine': 'native', 'operation': 'loading', 'graphCount': 64,
    'boundary': 'Immediately before wrapper spawn through complete /api/graphs consumption and exact64 validation',
    'readiness': 'All64 MAPPED registrations and saved counts; includes required startup topology work',
    'cpu': 'Process-zero cumulative CPU; in-boundary sample lower and first post-ready sample upper with two ticks uncertainty',
    'rss': 'In-boundary sampled peak lower and first post-ready Linux VmHWM upper; later query RSS is excluded',
    'cache': 'Matched full closed-input reads before every process; uncontrolled OS cache; no disk-cold claim',
    'queriesIssuedDuringLoading': 0,
    'deferredWork': 'Registry-ready only; first-query work remains in query evidence',
    'aggregation': 'Fixed six CABBAC cells and two directions; no pooling or retry selection',
}
RAW_FILES = ('command.json', 'launch-owner.json', 'server.pid', 'readiness.body', 'resources.jsonl', 'time-v.log')


def validate_declaration(plan):
    common.require(plan.get('engine') == 'native' and plan.get('operation') == 'query' and
                   common.typed(plan.get('loadingMeasurement')) == common.typed(DECLARATION),
                   'explicit versioned Native64 loading declaration')
    common.require(all(len(arm['graphs']) == len({g['id'] for g in arm['graphs']}) == 64 and
                       arm['readiness']['expected']['count'] == 64 for arm in plan['arms'].values()),
                   'complete Native64 loading scope')


def validate_record(record, result, samples, lifecycle, pid):
    """Reuse the already loaded stream and requests; never read response bodies."""
    common.require(type(record['queriesIssuedDuringLoading']) is int and
                   all(type(record[k]) is int and record[k] >= 0 for k in ('startNs','readyNs')),
                   'original integer Native loading clock and query count')
    common.require(record['schema'] == 'graphite.native64-loading.cell.v1' and
                   record['queriesIssuedDuringLoading'] == 0 and record['graphCount'] == 64 and
                   record['readyNs'] <= result['readyNs'], 'original zero-query Native64 loading boundary')
    initial = record['initialSample']
    common.validate_resource_sample(initial)
    after = record['cpuAfterReady']; high = record['readyWatermark']
    common.require(initial['pid'] == after['pid'] == pid and
                   all(initial[k] == after[k] for k in ('startTicks','ticksPerSecond','pageSize','backend')) and
                   initial['backend'] == 'linux-proc' and
                   record['startNs'] <= initial['readStartNs'] <= initial['readEndNs'] <= record['readyNs'],
                   'actual live process between launch and complete readiness')
    loading_end = high['identityAfter']['readEndNs']
    for stage in result['stages'].values():
        common.require(stage['cpuStart']['readStartNs'] >= loading_end and
                       all(r['startNs'] >= loading_end for r in stage['requests']),
                       'no oracle warmup or pressure request before loading watermark completes')
    resources = reconstruct(record, samples)
    common.require(lifecycle['peakRssBytes'] >= high['peakRssBytes'], 'lifetime RSS only corroborates ready VmHWM')
    return resources


def audit(plan, result, root, samples, lifecycle, pid):
    validate_declaration(plan)
    record = result['loading']
    resources = validate_record(record, result, samples, lifecycle, pid)
    common.require(record['resources'] == resources, 'independently reconstructed Native loading bounds')
    return {'schema': 'graphite.native64-loading.audit.v1', 'status': 'PASS_RAW_NATIVE64_LOADING_AUDIT',
            'engine': 'native', 'operation': 'loading', 'graphCount': 64,
            'queriesIssuedDuringLoading': 0, 'resources': resources,
            'rawPins': {name: common.sha(Path(root)/name) for name in RAW_FILES},
            'performanceAcceptance': False, 'otherOperationsEligible': False}
