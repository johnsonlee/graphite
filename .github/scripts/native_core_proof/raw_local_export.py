"""Optional commands/binding inside the existing core proof owned-phase runner.

No independent runner, new acceptance schema or implicit promotion of claims.
The caller's already-audited producer manifests bind actual writer/JDK inputs.
"""
import csv
import os
from pathlib import Path
from .legacy_wire import need
from .raw_local_types import sha, load

SOURCE = Path(__file__).with_name('ExportRawLocals.java')


def configure(plan, fixtures, roots, toolchains):
    stages = []
    for arm in ('C', 'B'):
        writer = str(roots[arm] / 'runtime/writer.jar')
        java, javac = toolchains[arm]
        need(all(p in plan['pins'] for p in (writer, java, javac)), 'actual producer writer/JDK bound')
        provenance = str(roots[arm] / 'graphs/fixture-provenance.tsv')
        need(provenance in plan['pins'], 'actual provenance bound')
        for source in fixtures[arm]['inputJars']:
            need(plan['pins'].get(source['path']) == source['sha256'], 'actual source JAR bound')
            stages.append({'arm': arm, 'corpus': source['corpus'], 'sourceJar': source['path'],
                           'provenance': provenance, 'writerJar': writer, 'java': java, 'javac': javac})
    need(len(stages) == 8 and len({(r['arm'], r['corpus']) for r in stages}) == 8, 'both complete four-corpus exports')
    need(str(SOURCE.resolve()) in plan['pins'], 'reviewed raw helper control pin')
    plan['rawLocalExports'] = stages
    plan['maxOwnedPhases'] += 10
    return plan


def commands(plan):
    out = Path(plan['output'])
    for arm in ('C', 'B'):
        rows = [r for r in plan['rawLocalExports'] if r['arm'] == arm]
        need(len(rows) == 4, 'complete raw export corpus arm')
        first = rows[0]
        yield 'compile-raw-'+arm, [first['javac'], '-J-Xmx4g', '-J-XX:ActiveProcessorCount=4', '-proc:none',
              '-cp', first['writerJar'], '-d', str(out/'classes'/('raw-'+arm)), str(SOURCE)], 180
    for row in plan['rawLocalExports']:
        name = 'raw-'+row['arm']+'-'+row['corpus']
        yield name, [row['java'], '-Xmx4g', '-XX:ActiveProcessorCount=4', '-cp',
              str(out/'classes'/('raw-'+row['arm']))+os.pathsep+row['writerJar'], 'ExportRawLocals',
              row['corpus'], row['sourceJar'], row['provenance'], str(out/'raw-locals'/name)], 3600


def binding(plan, row):
    exports = {}
    identity = None
    for arm in ('C', 'B'):
        corpus = row['fieldAuthority']['corpus']
        stages = [s for s in plan['rawLocalExports'] if (s['arm'], s['corpus']) == (arm, corpus)]
        need(len(stages) == 1, 'one bound actual corpus export')
        stage = stages[0]
        need(sha(stage['provenance']) == plan['pins'][stage['provenance']], 'bound original shard provenance')
        with open(stage['provenance']) as stream:
            original = [r for r in csv.DictReader(stream, delimiter='\t') if r['graphId'] == row['id']]
        need(len(original) == 1, 'one original graph shard')
        current = (original[0]['shardBytecodeSha256'], int(original[0]['classCount']))
        need(identity is None or current == identity, 'paired identical bytecode shards')
        identity = current
        path = Path(plan['output'])/'raw-locals'/('raw-'+arm+'-'+corpus)/(row['id']+'.jsonl')
        ref = {'path': str(path.resolve()), 'sha256': sha(path)}
        load(ref, row['id'], *identity)
        exports[arm] = ref
    return {'graphId': row['id'], 'shardBytecodeSha256': identity[0], 'classCount': identity[1], 'exports': exports}


def phase_java(plan, name):
    for row in plan.get('rawLocalExports', []):
        if name in ('compile-raw-'+row['arm'], 'raw-'+row['arm']+'-'+row['corpus']):
            return row['java']
    return plan['java']
