"""Independent read-only evidence audit. No Java, Gradle or query execution."""
from pathlib import Path
import ast, hashlib, json, re, subprocess, zipfile, xml.etree.ElementTree as ET
P = Path(__file__).resolve().parent
R = P / 'repo'
def sha(p):
    h = hashlib.sha256()
    with Path(p).open('rb') as f:
        for b in iter(lambda: f.read(1024 * 1024), b''): h.update(b)
    return h.hexdigest()
def read(n): return json.loads((P / n).read_text())
def git(*args): return subprocess.check_output(['git', '-C', str(R), *args])
build, pre, byte = read('build-receipt.json'), read('prebuild-source-receipt.json'), read('bytecode-receipt.json')
assert build['buildExit'] == 0 and build['buildSuccessful']
for key, name in [('buildCommandSha256', 'build-command.json'), ('buildLogSha256', 'build.log')]:
    assert build[key] == sha(P / name)
command = read('build-command.json')
assert command['command'] == ['./gradlew', ':webgraph:test', ':webgraph:detekt', ':webgraph:jmhJar', ':webgraph:verifyJmhJarExcludesTests', '--no-daemon']
assert command['environment']['JAVA_TOOL_OPTIONS'] == '-XX:ActiveProcessorCount=4'
assert 'openjdk@17' in command['environment']['JAVA_HOME']
log = (P / 'build.log').read_text()
assert 'BUILD SUCCESSFUL' in log
for task in build['checks']: assert '> Task ' + task in log
counts = dict.fromkeys(['tests', 'failures', 'errors', 'skipped'], 0)
suites = []
for file in sorted((P / 'test-results').glob('TEST-*.xml')):
    root = ET.parse(file).getroot()
    cases = root.findall('testcase')
    assert len(cases) == int(root.attrib['tests'])
    assert not any(c.find('failure') is not None or c.find('error') is not None or c.find('skipped') is not None for c in cases)
    for k in counts: counts[k] += int(root.attrib.get(k, 0))
    receipt = next(s for s in build['suites'] if Path(s['archive']).name == file.name)
    assert sha(file) == receipt['sha256'] == sha(R / receipt['file'])
    assert [c.attrib['name'] for c in cases] == receipt['testNames']
    suites.append({'name': root.attrib['name'], 'count': len(cases), 'sha256': sha(file)})
assert counts == build['testResults'] == {'tests': 195, 'failures': 0, 'errors': 0, 'skipped': 0}
assert len(suites) == 7
source = R / pre['onlyChangedProductionFile']
assert sha(source) == pre['sourceAfterSha256'] == build['sourceSha256']
assert hashlib.sha256(git('show', 'HEAD:' + pre['onlyChangedProductionFile'])).hexdigest() == pre['sourceBeforeSha256']
assert git('rev-parse', 'HEAD').decode().strip() == 'b94b8caa8dea10d1d2ddb74a0a0c39a3ab5351f0'
assert git('diff', '--name-only').decode().splitlines() == [pre['onlyChangedProductionFile']]
assert git('diff') == (P / 'candidate-source.diff').read_bytes()
production = [p for p in git('ls-files').decode().splitlines() if '/src/main/' in p or '/src/jmh/' in p]
changed = [p for p in production if (R / p).read_bytes() != git('show', '4e328b0109e13c896b74004823fb049fcb19251a:' + p)]
assert len(production) == 130 and changed == [pre['onlyChangedProductionFile']]
test = R / 'graphite-webgraph/src/test/kotlin/io/johnsonlee/graphite/webgraph/RawExactStringPropertyFlagsTest.kt'
assert sha(test) == 'd77b2ebdab630728c8adb1f1e78f3b7835742c0acf6855d74ff10b06c463ff66'
assert next(s for s in suites if s['name'].endswith('RawExactStringPropertyFlagsTest'))['count'] == 8
assert next(s for s in suites if s['name'].endswith('ParallelDistinctDisjunctionTest'))['count'] == 4
# Reuse only our independent classfile decoder definitions, never the parent's inspect script.
parser = Path('/private/tmp/graphite-main-compilation-diagnostic/independent-audit.py')
tree = ast.parse(parser.read_text())
selected = [n for n in tree.body if isinstance(n, (ast.ClassDef, ast.FunctionDef)) and n.name in ('Reader', 'class_methods')]
ns = {}; exec(compile(ast.Module(body=selected, type_ignores=[]), str(parser), 'exec'), ns)
class_results = {}
for side in ('base', 'candidate'):
    jar = Path(byte['jars'][side]['path'])
    assert sha(jar) == byte['jars'][side]['sha256']
    with zipfile.ZipFile(jar) as z:
        data = z.read(byte['class'].replace('.', '/') + '.class')
        if side == 'candidate':
            assert not (set(z.namelist()) & {str(p.relative_to(R / 'graphite-webgraph/build/classes/kotlin/test')) for p in (R / 'graphite-webgraph/build/classes/kotlin/test').rglob('*.class')})
            assert z.read('io/johnsonlee/graphite/webgraph/MappedWebGraphBackedGraph.class') == (R / 'graphite-webgraph/build/classes/kotlin/main/io/johnsonlee/graphite/webgraph/MappedWebGraphBackedGraph.class').read_bytes()
    reported = byte['results'][side]
    assert hashlib.sha256(data).hexdigest() == reported['classSha256']
    methods, member = ns['class_methods'](data)
    key = next(k for k in methods if k[0].startswith('parallelRawDistinctCallSiteStringProjection$') and 'Ref$IntRef;' in k[1])
    code = methods[key]
    assert len(code) == byte['nodeCodeBytes'][side]
    javap = (P / (side + '-per-node-javap.txt')).read_text().split('    LineNumberTable:')[0]
    ins = [(int(b), op, rest) for b, op, rest in re.findall(r'^\s+(\d+):\s+(\S+)(.*)$', javap, re.M)]
    assert len(ins) == byte['nodeInstructionCounts'][side]
    assert ins[-1][0] == len(code) - 1
    sites = {}
    for b, op, rest in ins:
        if op in ('invokevirtual', 'invokestatic', 'invokeinterface', 'invokespecial'):
            assert code[b] == {'invokevirtual':182, 'invokestatic':184, 'invokeinterface':185, 'invokespecial':183}[op]
            target = member(int.from_bytes(code[b+1:b+3], 'big'))
            assert (target[0] + '.' + target[1] + ':' + target[2] in rest.replace(chr(34), '') or (target[0] == byte['class'].replace('.', '/') and target[1] + ':' + target[2] in rest.replace(chr(34), ''))), (target, rest)
            sites.setdefault('.'.join(target[:2]), []).append(b)
        elif op in ('baload', 'arraylength', 'ishl', 'iand'):
            assert code[b] == {'baload':51, 'arraylength':190, 'ishl':120, 'iand':126}[op]
    class_results[side] = {'classSha256': hashlib.sha256(data).hexdigest(), 'nodeMethod': key[0], 'descriptor': key[1], 'codeBytes': len(code), 'instructions': len(ins), 'byteLoads': [b for b,op,_ in ins if op=='baload'], 'relevantSites': {k:v for k,v in sites.items() if any(n in k for n in ('List.get', 'Number.intValue', 'IntOpenHashSet.contains', 'getIndices', 'IntIterator.nextInt', 'Integer.valueOf'))}}
assert class_results['candidate']['descriptor'].count('[B') == class_results['base']['descriptor'].count('[B') + 1
assert class_results['base']['codeBytes'] == 790 and class_results['candidate']['codeBytes'] == 853
for method, sites in class_results['base']['relevantSites'].items():
    assert len(class_results['candidate']['relevantSites'][method]) == len(sites)
assert len(class_results['candidate']['byteLoads']) == len(class_results['base']['byteLoads']) + 1
assert sha(P / 'candidate-jmh.jar') == build['jmhJarSha256']
assert (P / 'candidate-jmh.jar').stat().st_mode & 0o777 == 0o444
for name, expected in pre['unrelatedUntrackedSnapshot'].items():
    f = Path(pre['workspace']) / name
    assert {'sha256':sha(f), 'size':f.stat().st_size, 'mtimeNs':f.stat().st_mtime_ns} == expected
out = {'passed': True, 'scope': 'Independent build/XML/source/classfile audit; no performance inference or execution.', 'testCounts': counts, 'suites': suites, 'productionFiles':len(production), 'changedProductionFiles':changed, 'newTestSha256':sha(test), 'bytecode':class_results, 'candidateJarSha256':build['jmhJarSha256'], 'baselineJarSha256':byte['jars']['base']['sha256'], 'hashes':{n:sha(P/n) for n in ['build-receipt.json','build-command.json','build.log','bytecode-receipt.json','candidate-source.diff','measurement-plan.json','independent-build-audit.py']}, 'decoderSourceSha256':sha(parser), 'limitations':['Static instructions do not establish runtime cost or speedup.', 'New candidate validation pass, byte-array zeroing and memory filling are not zero-cost; no extra storage work units are charged, consistent with old in-memory hash construction.', 'Unit tests establish helper semantics and existing mapped correctness; they do not independently measure which branch each real graph takes.', 'Pre-interrupted cancellation is tested; periodic in-construction interruption follows source checks, without a new deterministic mid-loop test.', 'This audit does not validate ongoing v3 control or acceptance results.']}
(P/'independent-build-audit.json').write_text(json.dumps(out,indent=2)+'\n')
print(json.dumps({'passed':out['passed'],'tests':counts,'codeBytes':{k:v['codeBytes'] for k,v in class_results.items()}}))
