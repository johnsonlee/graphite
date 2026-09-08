"""Freeze and build native runtime before any timed real64 invocation."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[2]
GO_MODULE = ROOT / 'graphite-server'

def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda: f.read(1024 * 1024), b''): h.update(b)
    return h.hexdigest()

def write(path, data): path.write_text(json.dumps(data, indent=2) + '\n')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    subprocess.run(['python3', str(BASE / 'main-tooling/build.py'), '--verify-only'], check=True)
    out = args.output.resolve()
    out.mkdir()
    source = out / 'source'
    subprocess.run(['/bin/cp', '-cRp', str(GO_MODULE), str(source)], check=True)
    files = {str(p.relative_to(GO_MODULE)): sha(p) for p in GO_MODULE.rglob('*') if p.is_file()}
    assert all(sha(source / p) == h for p, h in files.items())
    write(out / 'module-source.json', files)
    go = Path(shutil.which('go')).resolve()
    env = json.loads(subprocess.check_output([str(go), 'env', '-json'], cwd=source, text=True))
    deps = subprocess.check_output([str(go), 'list', '-deps', '-json', './cmd/graphite-benchmark-latency'], cwd=source, text=True)
    (out / 'dependencies.jsonstream').write_text(deps)
    paths = list(source.rglob('*.go')) + [source / 'go.mod', source / 'go.sum', source / 'internal/benchmarkcase/testdata/main64.json', go]
    decoder, position = json.JSONDecoder(), 0
    while position < len(deps):
        if deps[position].isspace(): position += 1; continue
        package, position = decoder.raw_decode(deps, position)
        assert not any(package.get(k) for k in ['CgoFiles', 'SwigFiles', 'SwigCXXFiles'])
        if package['ImportPath'] == 'unsafe': continue
        for key in ['GoFiles', 'SFiles', 'HFiles', 'CFiles', 'SysoFiles', 'EmbedFiles']:
            paths.extend(Path(package['Dir']) / p for p in package.get(key, []))
        if package.get('Module', {}).get('GoMod'): paths.append(Path(package['Module']['GoMod']))
    goroot = Path(env['GOROOT'])
    paths += [goroot / 'VERSION']
    paths += [p for parent in ['pkg/tool', 'pkg/include'] for p in (goroot / parent).rglob('*') if p.is_file()]
    paths += list(BASE.rglob('*.py')) + list((BASE / 'main-tooling').glob('*.java'))
    for parent in ['native64-fullcase-replay', 'native64-latency-pilot']:
        paths += list((BASE.parent / parent).glob('*.py'))
    paths += [BASE.parent / 'native64-profile-a7de0bec/fixture-files.json']
    inputs = {str(p): sha(p) for p in sorted(set(paths)) if p.is_file()}
    binary = out / 'graphite-benchmark-latency'
    command = [str(go), 'build', '-o', str(binary), './cmd/graphite-benchmark-latency']
    with (out / 'build.log').open('x') as log:
        subprocess.run(command, cwd=source, stdout=log, stderr=subprocess.STDOUT, check=True)
    assert all(sha(Path(p)) == h for p, h in inputs.items())
    inputs[str(binary)] = sha(binary)
    main_cp = (BASE / 'main-tooling/classpath.txt').read_text().strip()
    for component in main_cp.split(os.pathsep):
        p = Path(component)
        for item in p.rglob('*') if p.is_dir() else [p]:
            if item.is_file(): inputs[str(item)] = sha(item)
    for p in (BASE / 'main-tooling').rglob('*'):
        if p.is_file() and '__pycache__' not in p.parts: inputs[str(p)] = sha(p)
    settings = subprocess.run(['java', '-XshowSettings:properties', '-version'], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True).stdout
    java_home = Path(next(line.split('=', 1)[1].strip() for line in settings.splitlines() if line.strip().startswith('java.home =')))
    java = (java_home / 'bin/java').resolve()
    for p in [java, java_home / 'release', java_home / 'lib/modules', java_home / 'lib/server/libjvm.dylib']:
        inputs[str(p)] = sha(p)
    write(out / 'inputs.json', inputs)
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    internal_files = [p for p in GO_MODULE.rglob('*.go') if 'internal' in p.relative_to(GO_MODULE).parts]
    for p in internal_files:
        committed = subprocess.check_output(['git', 'show', revision + ':' + str(p.relative_to(ROOT))], cwd=ROOT)
        assert hashlib.sha256(committed).hexdigest() == sha(p), p
    write(out / 'build.json', dict(nativeBinary=str(binary), nativeBinarySHA256=sha(binary), nativeModule=str(source),
          engineRevision=revision, engineSourceFilesMatchedCommit=len(internal_files), buildCommand=command,
          mainClasspath=main_cp, java=str(java), javaSettings=settings, goEnvironment=env,
          runtimeEnvironment={k: os.environ.get(k) for k in ['GOGC','GOMEMLIMIT','GOMAXPROCS','GODEBUG','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS']},
          machine=subprocess.check_output(['uname','-a'],text=True).strip(), cpuCount=os.cpu_count(),
          moduleFiles=len(files), inputFiles=len(inputs), inputsUnchanged=True))
    print('Frozen measurement build:', out, flush=True)

if __name__ == '__main__': main()
