"""Archive only terminal captures without altering original JSONL bytes."""
import gzip
import hashlib
import json
from pathlib import Path
BASE = Path(__file__).resolve().parent
archives = {}
for state in ['cold', 'warm', 'startup-prepared']:
    name = 'native-' + state
    receipt = json.loads((BASE / (name + '-process.json')).read_text())
    assert receipt['status'] == 'exited' and receipt['inputsUnchanged'] and receipt['binaryUnchanged']
    path = BASE / name / 'responses.jsonl'
    target = Path(str(path) + '.gz')
    digest = hashlib.sha256()
    with path.open('rb') as source, target.open('xb') as raw:
        with gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as output:
            for block in iter(lambda: source.read(1024*1024), b''):
                digest.update(block)
                output.write(block)
    recovered = hashlib.sha256()
    with gzip.open(target, 'rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''):
            recovered.update(block)
    assert recovered.hexdigest() == digest.hexdigest()
    archives[name] = {'originalSHA256':digest.hexdigest(), 'gzipSHA256':hashlib.sha256(target.read_bytes()).hexdigest(), 'originalBytes':path.stat().st_size, 'gzipBytes':target.stat().st_size}
(BASE / 'capture-archives.json').write_text(json.dumps(archives,indent=2)+'\n')
print('Verified',len(archives),'byte-preserving archives')
