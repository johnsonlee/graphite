"""Hash all frozen real64 graph files and enumerate new graph-local files."""
import hashlib
import json
from pathlib import Path

BASE = Path(__file__).resolve().parent
MANIFEST = BASE.parent / 'native64-profile-a7de0bec/fixture-files.json'
FILES = json.loads(MANIFEST.read_text())


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def audit(root):
    root = Path(root)
    expected = {f"{f['graphId']}/{f['file']}": f for f in FILES}
    matched, changed, missing = 0, [], []
    for relative, original in expected.items():
        path = root / relative
        if not path.is_file():
            missing.append(relative)
            continue
        digest = sha(path)
        if digest == original['sha256'] and path.stat().st_size == original['bytes']:
            matched += 1
        else:
            changed.append({'path': relative, 'sha256': digest, 'bytes': path.stat().st_size,
                            'originalSHA256': original['sha256']})
    added = []
    for graph in sorted({f['graphId'] for f in FILES}):
        for path in sorted((root / graph).rglob('*')):
            if path.is_file() and str(path.relative_to(root)) not in expected:
                added.append({'path': str(path.relative_to(root)), 'bytes': path.stat().st_size,
                              'sha256': sha(path)})
    return {'root': str(root), 'originalFiles': len(expected), 'matched': matched,
            'changed': changed, 'missing': missing, 'added': added}


if __name__ == '__main__':
    reference = json.loads((BASE / 'main-cold-preflight.json').read_text())['reference']
    roots = {'reference': reference}
    for name in ['main-cold', 'native-cold']:
        roots[name] = json.loads((BASE / (name + '-preflight.json')).read_text())['clone']
    results = {name: audit(root) for name, root in roots.items()}
    (BASE / 'cold-postrun-fixtures.json').write_text(json.dumps(results, indent=2) + '\n')
    for name, result in results.items():
        print(name, 'matched', result['matched'], 'changed', len(result['changed']),
              'missing', len(result['missing']), 'added', len(result['added']), flush=True)
    assert all(r['matched'] == 1152 and not r['missing'] and not r['changed'] for r in results.values())
