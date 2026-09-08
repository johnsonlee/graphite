"""Preserve both the failed lifecycle comparison and the final module checks."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


p = argparse.ArgumentParser()
p.add_argument('--failed', type=Path, required=True)
p.add_argument('--passed', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
out = a.output.resolve()
records = []
for source, name, expected in [(a.failed.resolve(), 'preliminary-v1', [1]),
                               (a.passed.resolve(), 'evidence', [0, 0, 0])]:
    receipt = json.loads((source / 'receipt.json').read_text())
    assert [s['exitCode'] for s in receipt['steps']] == expected
    assert all(s['inputsUnchanged'] for s in receipt['steps'])
    destination = out / name
    destination.mkdir(parents=True, exist_ok=False)
    files = []
    for original in sorted(source.rglob('*')):
        if not original.is_file():
            continue
        target = destination / original.relative_to(source)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(original, target)
        assert sha(original) == sha(target)
        files.append(dict(source=str(original), archive=str(target.relative_to(out)),
                          bytes=original.stat().st_size, sha256=sha(original)))
    records.append(dict(name=name, steps=receipt['steps'], files=files))
(out / 'check-copy-verification.json').write_text(json.dumps(dict(
    runs=records, performanceMeasurements=0,
    failedRunScope='All 52 leading-work operation subtests passed; parent final snapshots compared live Go caches with closed JVM caches. The corrected test closes Go stores, checks ErrStoreClosed, and qualifies unavailable private post-close storage observers.'
), indent=2) + '\n')
print('Preserved failed v1 and passing v2 checks byte-exactly')
