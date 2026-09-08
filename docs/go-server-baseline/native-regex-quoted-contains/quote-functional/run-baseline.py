"""Retain pre-fix Go failures against the complete actual-Java quote corpus."""
import hashlib
import json
import subprocess
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
OUT = HERE / 'baseline'
OUT.mkdir()
SOURCE = Path('/Users/johnsonlee/.codex/benchmarks/graphite/ast-cache-61828229-build-v1/source')
PARENT = Path('/Users/johnsonlee/.codex/benchmarks/graphite/regex-quote-baseline-diagnostic-bc708-v1')
PARENT.mkdir()
MODULE = PARENT / 'module'
subprocess.run(['/bin/cp', '-cRp', str(SOURCE), str(MODULE)], check=True)
(PARENT / 'docs').symlink_to(ROOT / 'docs', target_is_directory=True)
test = subprocess.check_output(['git', 'show', ':graphite-server/internal/javaregex/quote_test.go'], cwd=ROOT, text=True)
test = test.split('func TestQuotePreprocessingUsesEscapedCodePoints')[0].replace('\n\t"strings"', '')
(MODULE / 'internal/javaregex/quote_oracle_baseline_test.go').write_text(test)
(OUT / 'quote_oracle_baseline_test.go.txt').write_text(test)
paths = [p for p in MODULE.rglob('*') if p.is_file()]
paths += [HERE.parent / 'pattern-main.json', HERE.parent / 'quote-index-controls/pattern-main.json', Path(__file__).resolve()]
inputs = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
(OUT / 'inputs.json').write_text(json.dumps(inputs, indent=2) + '\n')
command = ['/opt/homebrew/Cellar/go/1.22.0/libexec/bin/go', 'test', '-count=1', '-run', '^TestQuotePreprocessingAndDiagnosticsMatchActualJava$', '-json', './internal/javaregex']
with (OUT / 'test.jsonl').open('x') as log:
    result = subprocess.run(command, cwd=MODULE, stdout=log, stderr=subprocess.STDOUT)
events = [json.loads(line) for line in (OUT / 'test.jsonl').read_text().splitlines()]
failed = [e['Test'] for e in events if e.get('Action') == 'fail' and '/' in e.get('Test', '')]
passed = [e['Test'] for e in events if e.get('Action') == 'pass' and '/' in e.get('Test', '')]
unchanged = all(hashlib.sha256(Path(p).read_bytes()).hexdigest() == h for p, h in inputs.items())
receipt = dict(command=command, cwd=str(MODULE), exitCode=result.returncode,
               inputsUnchanged=unchanged, inputCount=len(inputs), failedCases=failed,
               passedCases=len(passed), totalCases=len(failed)+len(passed),
               performanceMeasurement=False, baselineRevision='bc708a810fce0d111988c72a3105820bfd1cf06e')
(OUT / 'receipt.json').write_text(json.dumps(receipt, indent=2)+'\n')
print(json.dumps({k: v for k, v in receipt.items() if k != 'failedCases'}, indent=2), flush=True)
assert unchanged and result.returncode == 1 and len(failed)+len(passed) == 686 and failed
