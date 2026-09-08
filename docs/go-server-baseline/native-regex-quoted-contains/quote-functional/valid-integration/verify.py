from pathlib import Path
import base64, hashlib, json
p = Path(__file__).resolve().parent
manifest = json.loads((p / 'manifest.json').read_text())
for name, digest in manifest.items():
    assert hashlib.sha256((p / name).read_bytes()).hexdigest() == digest, name
cases = json.loads((p / 'cases.json').read_text())
java = (p / 'java.txt').read_bytes()
assert len(cases) == len(java.splitlines()) == 2030
for name in ['repeat-java.txt', 'staged-go.txt', 'repeat-staged-go.txt']:
    assert java == (p / name).read_bytes(), name
lines = java.splitlines()
head = (p / 'head-go.txt').read_bytes().splitlines()
assert len(head) == len(lines)
indices = [i for i, (a, b) in enumerate(zip(lines, head)) if a != b]
comparison = json.loads((p / 'comparison.json').read_text())
assert indices == comparison['headMismatchCaseIndices']
assert len(indices) == comparison['headMismatches'] == 174
assert sum(line.startswith(b'OK ') for line in lines) == 1643
assert sum(line.startswith(b'ERR ') for line in lines) == 387
# Ensure the Java helper embeds the exact complete input corpus, in order.
source = (p / 'QuoteReview.java').read_text()
rows = []
for case in cases:
    a = base64.b64encode(case['Pattern'].encode()).decode()
    b = base64.b64encode(case['Text'].encode()).decode()
    rows.append('new String[]{"' + a + '","' + b + '"}')
assert 'String[][] cases={' + ','.join(rows) + '};' in source
print('Verified 2,030 actual-Java/staged-Go cases, two repetitions, and 174 preserved HEAD mismatches.')
