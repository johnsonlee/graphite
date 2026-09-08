#!/usr/bin/env python3
"""Offline analysis of diagnostic trace runs. No runtime launch or expected-count mask."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')


def named(value):
    label, path = value.split('=', 1)
    assert label and all(c.isalnum() or c in '-_' for c in label)
    return label, Path(path).resolve()


def prefix_cases(path, wanted=None):
    answer = {}
    with path.open() as stream:
        for line in stream:
            record = json.loads(line)
            if record.get('kind') != 'case' or record.get('phase') != 'replay':
                continue
            index = record['index']
            if wanted is None or index in wanted:
                assert index not in answer
                answer[index] = record
            if wanted is not None and set(answer) == set(wanted):
                break
    return answer


def differences(left, right, path=''):
    if type(left) is not type(right):
        return [{'path': path, 'left': left, 'right': right}]
    if isinstance(left, dict):
        out = []
        for key in sorted(set(left) | set(right)):
            child = path + '/' + key
            if key not in left or key not in right:
                out.append({'path': child, 'leftPresent': key in left,
                            'rightPresent': key in right, 'left': left.get(key), 'right': right.get(key)})
            else:
                out.extend(differences(left[key], right[key], child))
        return out
    if isinstance(left, list):
        if len(left) != len(right):
            return [{'path': path, 'left': left, 'right': right}]
        return [item for i, (a, b) in enumerate(zip(left, right))
                for item in differences(a, b, path + '/' + str(i))]
    return [] if left == right else [{'path': path, 'left': left, 'right': right}]


def capture(root):
    receipt = json.loads((root / 'receipt.json').read_text())
    assert receipt['status'] == 'complete', 'Refuse incomplete/live capture'
    records = [json.loads(line) for line in (root / 'trace.jsonl').read_text().splitlines()]
    summary = records[-1]
    assert summary['kind'] == 'trace_summary'
    events = records[:-1]
    assert [e['sequence'] for e in events] == list(range(summary['reserved']))
    assert not summary['dropped'] and not summary['unpublished']
    cases = prefix_cases(root / 'responses.jsonl')
    by_query = defaultdict(list)
    for event in events:
        by_query[event['queryIndex']].append(event)
    graph_by_store = {}
    for event in events:
        if event['kind'] in ['candidate_entry', 'ordinary_suffix_source']:
            graph_by_store[event['related']] = event['graph']
    executors = []
    for event in events:
        if event['kind'] == 'executor_create':
            identity = event['object']
            executors.append({'executor': identity, 'created': event,
                'coreStarts': [e for e in events if e['kind'] == 'executor_core_start' and e['object'] == identity],
                'coreExits': [e for e in events if e['kind'] == 'executor_core_exit' and e['object'] == identity]})
    queries = []
    for index, case in sorted(cases.items()):
        current = by_query[index]
        begin = next(e['sequence'] for e in current if e['kind'] == 'query_begin')
        end = next(e['sequence'] for e in current if e['kind'] == 'query_end')
        futures, workers, tasks, active = {}, {}, [], {}
        graphs = defaultdict(list)
        for event in current:
            kind, obj, related = event['kind'], event['object'], event['related']
            if event['graph']:
                graph = graph_by_store.get(related, event['graph'])
                graphs[graph].append(event)
            if kind == 'future_create':
                futures[obj] = {'future': obj, 'worker': related, 'workerOrdinal': event['a'],
                                'events': [event], 'tasks': []}
                workers[related] = obj
            elif kind in ['future_start', 'future_cancel', 'future_finish', 'future_joined']:
                if obj in futures:
                    futures[obj]['events'].append(event)
            if kind == 'source_dequeue' and event['a'] >= 0:
                task = {'worker': obj, 'future': related, 'sourceIndex': event['a'],
                        'dequeueSequence': event['sequence'], 'graphs': [], 'events': []}
                tasks.append(task); active[obj] = task
                if related in futures:
                    futures[related]['tasks'].append(len(tasks) - 1)
            task = active.get(obj)
            if task is not None:
                task['events'].append(event)
                if event['graph']:
                    graph = graph_by_store.get(related, event['graph'])
                    if graph not in task['graphs']:
                        task['graphs'].append(graph)
                if kind == 'source_outcome':
                    task['outcomeSequence'] = event['sequence']
                    task['failed'] = bool(event['b'])
                    active.pop(obj)
        interruptions = {event['object']: event for event in current if event['kind'] == 'worker_interrupt'}
        first_cancel = min((event['sequence'] for event in current if event['kind'] == 'future_cancel'), default=None)
        for task in tasks:
            interruption = interruptions.get(task['worker'])
            task['workerInterrupt'] = interruption
            task['dequeuedAfterWorkerInterrupt'] = interruption is not None and task['dequeueSequence'] > interruption['sequence']
            task['dequeuedAfterFirstFutureCancel'] = first_cancel is not None and task['dequeueSequence'] > first_cancel
            task['pollRejections'] = [event for event in task['events'] if event['kind'].endswith('_rejected')]
            task['publications'] = [event for event in task['events'] if event['kind'] == 'range_publication']
        pool_before = []
        for executor in executors:
            if executor['created']['sequence'] >= begin:
                continue
            starts = [e for e in executor['coreStarts'] if e['sequence'] < begin]
            exits = [e for e in executor['coreExits'] if e['sequence'] < begin]
            pool_before.append({'executor': executor['executor'], 'createdAtQuery': executor['created']['queryIndex'],
                'capacity': executor['created']['a'], 'observedCoreStartsBeforeQuery': len(starts),
                'observedCoreExitsBeforeQuery': len(exits),
                'capacityReachedByObservedStarts': len(starts) >= executor['created']['a']})
        graph_summary = {}
        for graph, graph_events in graphs.items():
            graph_summary[graph] = {'eventCounts': dict(Counter(e['kind'] for e in graph_events)),
                'candidateEntries': [e for e in graph_events if e['kind'] == 'candidate_entry'],
                'anchors': [e for e in graph_events if e['kind'] == 'mapped_anchor'],
                'pollRejections': [e for e in graph_events if e['kind'].endswith('_rejected')],
                'publications': [e for e in graph_events if e['kind'] == 'range_publication']}
        queries.append({'index': index, 'id': case['id'], 'beginSequence': begin, 'endSequence': end,
            'eventCounts': dict(Counter(e['kind'] for e in current)), 'poolBeforeQuery': pool_before,
            'legacyEntries': [e for e in current if e['kind'] == 'legacy_runner_enter'],
            'fixedEntries': [e for e in current if e['kind'] == 'fixed_runner_enter'],
            'prefixAndCancellation': [e for e in current if e['kind'] in ['ordinary_prefix_consume', 'fixed_limit_stop', 'future_cancel', 'worker_interrupt']],
            'futures': list(futures.values()), 'tasks': tasks, 'graphs': graph_summary,
            'before': case['before'], 'after': case['after']})
    return {'capture': str(root), 'receiptSHA256': sha(root / 'receipt.json'),
            'traceSHA256': sha(root / 'trace.jsonl'), 'responsesSHA256': sha(root / 'responses.jsonl'),
            'traceSummary': summary, 'executors': executors, 'queries': queries,
            'betweenQueryEvents': by_query.get(-1, []), 'graphByStore': graph_by_store}, cases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--capture', action='append', required=True, type=named, metavar='LABEL=DIRECTORY')
    parser.add_argument('--reference', action='append', default=[], type=named, metavar='LABEL=JSONL')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args(); output = args.output.resolve()
    assert not output.exists(); output.mkdir(parents=True)
    reports, responses, identities = {}, {}, []
    for label, root in args.capture:
        report, cases = capture(root)
        assert label not in responses
        reports[label] = report; responses[label] = cases
        write(output / (label + '-history.json'), report)
        identities.append({'label': label, 'path': str(root / 'responses.jsonl'), 'sha256': report['responsesSHA256'], 'instrumented': True})
    wanted = set().union(*(set(cases) for cases in responses.values()))
    for label, path in args.reference:
        assert label not in responses
        responses[label] = prefix_cases(path, wanted)
        identities.append({'label': label, 'path': str(path), 'sha256': sha(path), 'instrumented': False})
    comparisons = []
    # Every supplied trace is compared with every supplied plain/main reference;
    # trace-to-trace is also retained. No field is excluded or normalized.
    labels = list(responses)
    for i, left in enumerate(labels):
        for right in labels[i+1:]:
            if left not in reports and right not in reports:
                continue
            diffs = []
            for index in sorted(set(responses[left]) | set(responses[right])):
                for item in differences(responses[left].get(index), responses[right].get(index)):
                    item['caseIndex'] = index
                    item['category'] = 'state' if item['path'].startswith(('/before/', '/after/')) else 'publicOrRecord'
                    diffs.append(item)
            filename = left + '-vs-' + right + '-differences.json'
            write(output / filename, diffs)
            comparisons.append({'left': left, 'right': right, 'leftCaseCount': len(responses[left]),
                'rightCaseCount': len(responses[right]), 'differences': len(diffs),
                'byCategory': dict(Counter(item['category'] for item in diffs)), 'file': filename})
    summary = {'diagnosticOnly': True, 'noRuntimeLaunched': True, 'inputs': identities,
        'limitations': ['Hooks perturb scheduling; results describe only these instrumented runs.',
            'Sequence numbers order reservations, not exact channel/lock linearization.',
            'Core events count executor goroutines, not OS threads; individual core identity is unavailable.',
            'Worker ownership is split by actual source dequeue/outcome; one future can execute many sources.',
            'Between-query events retain queryIndex -1. No desired speculative cache count is assumed.',
            'Comparisons include every captured case field; no error/state mask or comparator is changed.'],
        'comparisons': comparisons,
        'captures': {label: {'queryCount': len(report['queries']), 'eventCount': report['traceSummary']['reserved'],
            'queries': [{'index': q['index'], 'poolBeforeQuery': q['poolBeforeQuery'],
                'legacyEntryCount': len(q['legacyEntries']), 'fixedEntryCount': len(q['fixedEntries']),
                'sourceTasks': len(q['tasks']), 'taskWorkers': len({t['worker'] for t in q['tasks']}),
                'futuresStarted': sum(any(e['kind'] == 'future_start' for e in f['events']) for f in q['futures']),
                'futuresCanceledBeforeStart': sum(any(e['kind'] == 'future_cancel' and e['a'] == 0 for e in f['events']) for f in q['futures']),
                'pollRejections': sum(len(t['pollRejections']) for t in q['tasks']),
                'rangePublications': sum(len(g['publications']) for g in q['graphs'].values())}
                for q in report['queries']]} for label, report in reports.items()}}
    write(output / 'summary.json', summary)
    write(output / 'analysis-manifest.json', [{'path': p.name, 'bytes': p.stat().st_size, 'sha256': sha(p)}
          for p in sorted(output.iterdir()) if p.is_file()])
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
