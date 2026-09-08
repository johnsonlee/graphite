"""Verify profiled full replay against original main, without claiming timing parity."""
import csv
import importlib.util
import json
from pathlib import Path

BASE = Path(__file__).resolve().parent
PILOT = BASE.parent / 'native64-latency-pilot'
spec = importlib.util.spec_from_file_location('pilot', PILOT / 'verify-pilot.py')
pilot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pilot)
out = BASE / 'run1'
receipt = pilot.read(out / 'process.json')
assert receipt['status'] == 'exited' and receipt['exitCode'] == 1
assert receipt['inputsUnchanged'] and receipt['fixtureAuditPassed']
assert receipt['baseRevision'] == '9237131f' and receipt['performanceMeasurement'] is False
inputs = pilot.read(out / 'inputs.json')
for path, digest in inputs.items():
    assert pilot.sha(Path(path)) == digest, path
for name in ['reference-preflight', 'clone-preflight', 'postrun-fixtures']:
    pilot.check_audit(pilot.read(out / (name + '.json')))
workload = pilot.read(pilot.WORKLOAD)
reference, archive = pilot.reference_cases('cold', workload)
records = [json.loads(line) for line in (out / 'observations.jsonl').read_text().splitlines()]
assert len(records) == 1269 and records[0]['performanceMeasurement'] is False
assert records[0]['cpuProfiledCase'] == 'filtered-count-zero'
assert records[-1]['completeReplay'] and not records[-1]['originalGatePassed']
assert records[-1]['error'] == 'replay had 1 failed cases'
with (PILOT / 'main-cold-run2/capture/main-observations.tsv').open() as stream:
    main = list(csv.DictReader(stream, delimiter='\t'))
comparisons = pilot.compare_cases(workload, reference, main, records[1:-1])
memory = pilot.read(out / 'memory.json')
assert memory['case'] == 'filtered-count-zero' and memory['index'] == 838 and memory['cpuProfiled']
assert all(command['exitCode'] == 0 for command in pilot.read(out / 'pprof-commands.json'))
assert (out / 'cpu.pprof').stat().st_size > 0
report = dict(completeOrderedCasesVerified=len(comparisons), successCount=1266,
              originalFailureRetained=True, noNewErrorsOrTimeouts=True,
              originalGraphFilesUnchanged=1152, inputsUnchanged=True,
              cpuProfiledCase='filtered-count-zero', cpuProfiledIndex=838,
              performanceMeasurement=False, acceptanceEvidence=False,
              profilingMemoryCounterDeltas={key:memory['after'][key]-memory['before'][key]
                  for key in ['TotalAlloc', 'Mallocs', 'Frees', 'NumGC', 'PauseTotalNs']},
              memoryCaveat='Counter interval includes profiler start/stop overhead; not an uninstrumented allocation benchmark',
              nativeReferenceRevision=receipt['baseRevision'],
              originalMainReferenceArchive=str(archive))
report['evidenceSHA256'] = {str(p):pilot.sha(p) for p in sorted(out.iterdir()) if p.is_file()}
(BASE / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({k:v for k,v in report.items() if k!='evidenceSHA256'},indent=2))
