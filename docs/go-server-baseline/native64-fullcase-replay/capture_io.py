"""Read byte-preserved captures from a local raw file or its committed gzip."""
import gzip
import hashlib
import json
from pathlib import Path

BASE = Path(__file__).resolve().parent


def stream(name):
    raw = BASE / name / 'responses.jsonl'
    return raw.open('rb') if raw.exists() else gzip.open(str(raw) + '.gz', 'rb')


def records(name):
    with stream(name) as source:
        return [json.loads(line) for line in source]


def capture_sha(name):
    digest = hashlib.sha256()
    with stream(name) as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()
