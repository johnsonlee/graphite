"""Frozen original34 contract; stop when a strict-progress failure is already established."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

root = Path(__file__).parent
out = root / 'old34-pairs'
out.mkdir()  # An existing run is never overwritten or retried to green.
worktree = Path('/Users/johnsonlee/.codex/worktrees/ac7b5da2-2450-48c5-894c-5fd84ab6cb7d/graphite')
prior = Path('/private/tmp/graphite-mapped-tuple-evidence.t2461mo1')
sys.path.insert(0, str(worktree / '.github/scripts/wide-query-profile'))
from run import graph_identity

manifest = Path('/private/tmp/pr113-attempt131-ascii.JqgmHw/fixture64/graphs.tsv')
shutil.copyfile(prior / 'oracle.correctness', out / 'oracle.correctness')
template = json.loads((prior / 'oracle-command.json').read_text())
jars = {
    'base': Path('/private/tmp/graphite-next-baseline.T2FTs9/graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar'),
    'candidate': root / 'candidate-final-jmh.jar',
}
initial = {side: hashlib.sha256(path.read_bytes()).hexdigest() for side, path in jars.items()}
assert initial['base'] == 'a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe'
assert initial['candidate'] == json.loads((root / 'final-build-receipt.json').read_text())['jars']['webgraph']['sha256']
before = graph_identity(manifest)
(out / 'graph-input-before.json').write_text(json.dumps(before, indent=2) + '\n')
pairs = []
try:
    for pair in range(1, 4):
        measurements = {}
        order = ['candidate', 'base'] if pair % 2 else ['base', 'candidate']
        for side in order:
            prefix = out / f'{side}-global-wide-{pair}'
            cmd = template.copy()
            cmd[2] = str(jars[side])
            cmd[cmd.index('-rff') + 1] = str(prefix) + '.json'
            cmd[-1] = cmd[-1].replace('correctness.mode=record', 'correctness.mode=verify')
            cmd[-1] = cmd[-1].replace('pressure.output=' + str(prior / 'oracle.correctness'),
                                      'pressure.correctness.oracle=' + str(out / 'oracle.correctness'))
            cmd[-1] = cmd[-1].replace(str(prior / 'oracle.tsv'), str(prefix) + '.tsv')
            Path(str(prefix) + '-command.json').write_text(json.dumps(cmd, indent=2) + '\n')
            print('START', pair, side, flush=True)
            with Path(str(prefix) + '.log').open('w') as log:
                subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT, check=True)
            data = json.loads(Path(str(prefix) + '.json').read_text())
            assert len(data) == 1 and data[0]['benchmark'].endswith('.replayBroadQueries')
            metrics = data[0]['secondaryMetrics']
            measurements[side] = {key: metrics[key]['score'] for key in ('p95LatencyNanos', 'processCpuNanos', 'peakUsedHeapBytes', 'peakResidentSetBytes', 'availableProcessors', 'graphWorkerCount', 'segmentWorkerCount', 'graphScanPeakActiveWorkers', 'segmentScanPeakActiveWorkers')}
            assert measurements[side]['p95LatencyNanos'] > 0
            print('DONE', pair, side, measurements[side], flush=True)
        progress = measurements['candidate']['p95LatencyNanos'] < measurements['base']['p95LatencyNanos']
        candidate = measurements['candidate']
        processors = candidate['availableProcessors']
        assert processors == 4
        peaks = candidate['graphScanPeakActiveWorkers'] == 2 and candidate['segmentScanPeakActiveWorkers'] == 2
        resources = all(candidate[k] <= measurements['base'][k] * 1.15 for k in ('processCpuNanos', 'peakUsedHeapBytes', 'peakResidentSetBytes'))
        pairs.append({'pair': pair, 'order': order, 'measurements': measurements, 'strictProgress': progress,
                      'originalWorkerPeakCondition': peaks, 'originalResourceConditions': resources})
        if not progress or not peaks or not resources:
            print('ACCEPTANCE FAILED: strict progress, original peaks or resource condition; retain evidence and investigate within this direction before another candidate; no further acceptance pairs or CI for this snapshot', flush=True)
            break
    if len(pairs) == 3 and all(pair['strictProgress'] and pair['originalWorkerPeakCondition'] and pair['originalResourceConditions'] for pair in pairs):
        cmd = [arg.replace(str(prior), str(out)) for arg in json.loads((prior / 'comparison-command.json').read_text())]
        cmd[0] = shutil.which('node') or cmd[0]
        cmd[1] = str(worktree / cmd[1])
        (out / 'comparison-command.json').write_text(json.dumps(cmd, indent=2) + '\n')
        comparison = subprocess.run(cmd)
        (out / 'comparison-exit.json').write_text(json.dumps({'exitCode': comparison.returncode}) + '\n')
finally:
    after = graph_identity(manifest)
    (out / 'graph-input-after.json').write_text(json.dumps(after, indent=2) + '\n')
    unchanged = before == after and initial == {side: hashlib.sha256(path.read_bytes()).hexdigest() for side, path in jars.items()}
    receipt = {'pairs': pairs, 'jarHashes': initial, 'inputsUnchanged': unchanged,
               'strictProgressEveryPair': len(pairs) == 3 and all(pair['strictProgress'] for pair in pairs),
               'accepted': False, 'ciRun': False, 'note': 'Only exact-head green CI can accept a candidate.'}
    (out / 'local-progress.json').write_text(json.dumps(receipt, indent=2) + '\n')
    assert unchanged
