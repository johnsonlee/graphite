"""Capture two bounded constructor oracles, preserving all raw observations."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile


def sha(path):
    return hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def manifest(directory):
    return {str(p.relative_to(directory)): sha(p) for p in sorted(directory.rglob('*')) if p.is_file()}


parser = argparse.ArgumentParser()
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
here = Path(__file__).resolve().parent
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=False)
java_home = Path('/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home')
main = Path('/tmp/graphite-go-main-baseline-clone-4e328b0')
original_jar = main / 'graphite-explore/build/libs/graphite-explore.jar'
original_fixture = Path('/Users/johnsonlee/.codex/benchmarks/graphite/work-context-seek-main-v1/variants/locals')
frozen = output / 'frozen'
frozen.mkdir()
shutil.copyfile(original_jar, frozen / 'graphite-explore.jar')
for name in ['ConstructorOracle.java', 'cases.json', 'run.py']:
    shutil.copyfile(here / name, frozen / name)
shutil.copytree(original_fixture, frozen / 'fixtures/locals')
sources = [main / ('graphite-cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/' + name)
           for name in ['CypherExecutor.kt', 'CypherExecutionBudget.kt', 'QueryPipeline.kt']]
for source in sources:
    shutil.copyfile(source, frozen / source.name)
inputs = [original_jar, *sources, *sorted(p for p in frozen.rglob('*') if p.is_file()),
          java_home / 'bin/java', java_home / 'bin/javac', java_home / 'lib/modules',
          java_home / 'lib/server/libjvm.dylib', java_home / 'release']
input_hashes = {str(p): sha(p) for p in inputs}
write(output / 'inputs.json', input_hashes)
original_manifest = manifest(original_fixture)
commands = []


def run(phase, command, directory):
    start = datetime.datetime.now(datetime.timezone.utc).isoformat()
    with (directory / (phase + '.stdout')).open('wb') as stdout, (directory / (phase + '.stderr')).open('wb') as stderr:
        result = subprocess.run([str(item) for item in command], stdout=stdout, stderr=stderr)
    commands.append(dict(phase=phase, command=[str(item) for item in command], exitCode=result.returncode,
                         startUTC=start, endUTC=datetime.datetime.now(datetime.timezone.utc).isoformat()))
    write(output / 'commands.json', commands)
    if result.returncode:
        raise RuntimeError(f'{phase} failed: {result.returncode}')


classes = frozen / 'classes'
classes.mkdir()
run('compile', [java_home / 'bin/javac', '-cp', frozen / 'graphite-explore.jar', '-d', classes,
                frozen / 'ConstructorOracle.java'], output)
captures = []
for label in ['main-capture', 'repeat-capture']:
    directory = output / label
    directory.mkdir()
    shutil.copytree(frozen / 'fixtures', directory / 'fixtures')
    before = manifest(directory / 'fixtures')
    write(directory / 'fixture-before.json', before)
    run(label, [java_home / 'bin/java', '-Xmx512m', '-cp', str(classes) + ':' + str(frozen / 'graphite-explore.jar'),
                'ConstructorOracle', frozen / 'cases.json', directory / 'fixtures', directory / 'main.json'], directory)
    after = manifest(directory / 'fixtures')
    write(directory / 'fixture-after.json', after)
    assert before == after, 'Fixture changed; retain and investigate'
    assert (directory / 'main.json').read_bytes() == (directory / (label + '.stdout')).read_bytes()
    captures.append(json.loads((directory / 'main.json').read_text()))
assert captures[0] == captures[1], 'Full repeated observations differ'
assert original_manifest == manifest(original_fixture), 'Original source fixture changed'
assert all(sha(Path(p)) == value for p, value in input_hashes.items()), 'Frozen input changed'
write(output / 'receipt.json', dict(inputsUnchanged=True, originalFixtureUnchanged=True,
    fixturesUnchanged=True, fullRecordsEqual=True, cases=len(captures[0]['cases']),
    commands=commands, performanceMeasurements=0))
assert not (here / 'main.json').exists(), 'Do not overwrite previous evidence'
for name in ['inputs.json', 'commands.json', 'receipt.json', 'compile.stdout', 'compile.stderr']:
    shutil.copyfile(output / name, here / name)
for label in ['main-capture', 'repeat-capture']:
    destination = here / label
    destination.mkdir()
    for source in (output / label).iterdir():
        if source.is_file():
            shutil.copyfile(source, destination / source.name)
shutil.copyfile(output / 'main-capture/main.json', here / 'main.json')
with tarfile.open(here / 'fixtures.tar.gz', 'w:gz') as archive:
    for source in sorted((frozen / 'fixtures').rglob('*')):
        if source.is_file():
            archive.add(source, arcname=str(source.relative_to(frozen / 'fixtures')), recursive=False)
with tarfile.open(here / 'input-sources.tar.gz', 'w:gz') as archive:
    for source in sorted(frozen.iterdir()):
        if source.suffix in ['.kt', '.java', '.json', '.py']:
            archive.add(source, arcname=source.name, recursive=False)
write(here / 'archive-manifest.json', manifest(here))
print(f'{len(captures[0]["cases"])} cases; complete repeated observations equal; all exits 0')
