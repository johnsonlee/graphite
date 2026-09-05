import gzip
import hashlib
import json
import pathlib
import shutil

ROOT = pathlib.Path(__file__).parent
WORKSPACE = pathlib.Path('/Users/johnsonlee/.codex/worktrees/ac7b5da2-2450-48c5-894c-5fd84ab6cb7d/graphite')
DEST = WORKSPACE / 'docs/profiling/distinct-projection-work'

def digest(data):
    return hashlib.sha256(data).hexdigest()

receipts = []
for source, target_name in [(ROOT, 'compiler-without-tracing'), (pathlib.Path('/private/tmp/graphite-raw-or-plan-audit'), 'property-or-union-audit')]:
    target = DEST / target_name
    target.mkdir(parents=True, exist_ok=True)
    entries = []
    for path in sorted(source.iterdir()):
        if not path.is_file() or path.name == 'archive-receipt.json':
            continue
        data = path.read_bytes()
        destination = target / (path.name + '.gz' if path.name.endswith('.compilation.xml') else path.name)
        payload = gzip.compress(data, mtime=0) if destination.suffix == '.gz' else data
        destination.write_bytes(payload)
        decoded = gzip.decompress(destination.read_bytes()) if destination.suffix == '.gz' else destination.read_bytes()
        assert data == decoded
        entries.append({'source': str(path), 'destination': str(destination.relative_to(WORKSPACE)), 'sourceBytes': len(data), 'sourceSha256': digest(data), 'storedBytes': len(payload), 'storedSha256': digest(payload), 'decodedEqualsSource': True})
    receipt = {'source': str(source), 'entries': entries, 'count': len(entries)}
    (target / 'copy-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    receipts.append(receipt)
(ROOT / 'archive-receipt.json').write_text(json.dumps(receipts, indent=2) + '\n')
print(json.dumps({'archivedFiles': sum(r['count'] for r in receipts), 'allDecodedBytesEqual': True}))
