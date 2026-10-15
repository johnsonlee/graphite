#!/usr/bin/env python3
"""Reconstruct usable-save resources and validity from all six original phases.

Reuses the independent artifact auditor, not the producer's resource summaries.
Same-writer validity of fresh outputs is separate from the corrected bundle's
cross-version comparability proof; neither claims complete semantic equality.
"""
import argparse
from pathlib import Path
import sys
sys.dont_write_bytecode = True
import audit_native_pressure_artifacts as artifacts
import multigraph_pressure as common
import run_real64_construction as construction

require = common.require
SCHEMA = 'graphite.real64-construction.audit.v1'


def audit(plan_path, root):
    plan_path, root = Path(plan_path).resolve(), Path(root).resolve()
    plan = construction.validate(common.read(plan_path))
    record = common.read(root/'execution.json')
    digest = common.sha(plan_path)
    require(record['schema'] == construction.RESULT and record['status'] == 'PASS_ALL_SIX_USABLE_SAVED64'
            and record['errors'] == record['unissued'] == [] and record['planSha256'] == digest,
            'complete six-cell construction execution')
    require(all(record[key] is False for key in construction.FALSE), 'preserved bounded claims')
    require([row['cell'] for row in record['cells']] == plan['cells'], 'exact CABBAC order, no omitted failures')
    require(common.read(root/'identities-before.json') == common.read(root/'identities-after.json')
            == {'pins': plan['pins']}, 'unchanged complete source/runtime/workload/proof inputs')
    catalog = common.read(Path(__file__).parent/'fixtures/multigraph-pressure-cases.json')
    rows, groups = [], []
    retained = dict(plan['retentionSources'])
    for cell, result in zip(plan['cells'], record['cells']):
        directory, arm = root/cell['id'], plan['arms'][cell['arm']]
        require(common.read(directory/'cell.json') == result and result['status'] == 'PASS_USABLE_SAVED64'
                and result['errors'] == [] and result['revision'] == arm['revision']
                and result['writer'] == arm['writer'] and all(result[key] is False for key in construction.FALSE),
                'actual cell and sealed writer identity')
        graphs = directory/'graphs'
        commands = {'prepare-real64': [*arm['argv'], str(graphs)],
                    'verify-real64': [*arm['argv'], '--verify', str(graphs/'graphs.tsv'), str(graphs/'fixture-provenance.tsv')]}
        for name, key in [('prepare-real64', 'construction'), ('verify-real64', 'verify')]:
            phase = artifacts.check_phase(directory/name/'record.json', commands[name], directory,
                                          construction=name == 'prepare-real64')
            require(phase == result[key] and phase['name'] == name
                    and phase['timeoutSeconds'] == plan['timeouts'][name], 'original actual phase and deadline')
            groups.append(phase['cleanup']['group'])
        fixture = common.pinned_authority_metadata({'pins': {result['fixture']['path']: result['fixture']['sha256']}}, result['fixture'])
        require(result['fixture'] == artifacts.ref(directory/'fixture-manifest.json')
                and fixture['sourceManifestSha256'] == arm['sourceManifest']['sha256']
                and all(fixture[key] is False for key in construction.FALSE), 'fresh output source binding and scope')
        runtime = common.pinned_authority_metadata(plan, arm['runtimeManifest'])
        artifacts.verify_fixture(directory, fixture, runtime, plan['sourceInputs']['path'], catalog,
                                 arm['revision'], writer_path=arm['writer']['path'])
        retained.update(fixture['files'])
        retention = result['retention']
        require(retention['policy'] == plan['retentionPolicy'] == construction.RETENTION,
                'untimed retention policy')
        links = retention['links']
        require(len({link['path'] for link in links}) == len(links), 'unique retained output paths')
        for link in links:
            require(fixture['files'].get(link['path']) == retained.get(link['source']) == link['sha256']
                    and Path(link['path']).stat().st_size == link['bytes'], 'retained output and source byte identity')
            artifacts.verify_pins({link['source']: link['sha256']})
        require(retention['duplicateLogicalBytes'] == sum(link['bytes'] for link in links),
                'retention byte accounting')
        for line in (graphs/'fixture-provenance.tsv').read_text().splitlines()[1:]:
            row = line.split('\t')
            index = graphs/row[0]/'graph.callsite-string-index'
            require(index.stat().st_size == int(row[16]) and common.sha(index) == row[17],
                    'actual persisted CallSite index bytes')
        raw = [directory/'cell.json', directory/'fixture-manifest.json']
        raw += [directory/name/leaf for name in commands for leaf in ('record.json', 'owner.json', 'stdout.log', 'stderr.log')]
        raw.append(directory/'prepare-real64/time-v.log')
        rows.append({'cell': cell, 'revision': arm['revision'], 'writer': arm['writer'],
                     'graphCount': 64, 'resources': result['construction']['constructionResources'],
                     'rawPins': {str(p.relative_to(root)): common.sha(p) for p in raw}})
    require(len(groups) == len(set(groups)) == 12, 'twelve distinct owned writer/readback phases')
    artifacts.verify_pins(plan['pins'])
    require(common.sha(plan_path) == digest, 'audit plan unchanged')
    return {'schema': SCHEMA, 'status': 'PASS_RAW_USABLE_SAVE_AUDIT', 'engine': 'jvm',
            'operation': 'construction', 'planSha256': digest,
            'executionSha256': common.sha(root/'execution.json'), 'rows': rows,
            'scope': construction.SCOPE, 'comparisonModel': plan['comparisonModel'],
            'freshOutputValidity': 'same sealed writer; independent provenance/index inventory; embedded and second readback',
            'correctedComparability': plan['producers'], **construction.FALSE}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('plan', 'root', 'output'):
        parser.add_argument('--'+name, required=True)
    args = parser.parse_args()
    require(not Path(args.output).exists(), 'fresh independent audit receipt')
    try:
        result = audit(args.plan, args.root)
    except BaseException as error:
        common.save(args.output, {'schema': SCHEMA, 'status': 'FAIL', 'errors': [repr(error)], **construction.FALSE})
        return 1
    common.save(args.output, result)
    return 0


if __name__ == '__main__':
    sys.exit(main())
