"""Independent read-only XML, classfile, correctness and receipt audit; no Java invocation."""
from pathlib import Path
import csv, gzip, hashlib, json, re, shlex, struct, xml.etree.ElementTree as ET, zipfile
P = Path(__file__).resolve().parent

def read(name):
    return json.loads((P / name).read_text())

def sha(data):
    return hashlib.sha256(data).hexdigest()

def file_sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

class Reader:
    def __init__(self, data): self.data, self.pos = data, 0
    def take(self, n):
        value = self.data[self.pos:self.pos+n]
        assert len(value) == n
        self.pos += n
        return value
    def u1(self): return self.take(1)[0]
    def u2(self): return int.from_bytes(self.take(2), 'big')
    def u4(self): return int.from_bytes(self.take(4), 'big')

def class_methods(data):
    r = Reader(data)
    assert r.u4() == 0xcafebabe
    r.take(4)
    cp = [None] * r.u2()
    i = 1
    while i < len(cp):
        tag = r.u1()
        if tag == 1: cp[i] = (tag, r.take(r.u2()).decode('utf-8', errors='replace'))
        elif tag in (3, 4): cp[i] = (tag, r.take(4))
        elif tag in (5, 6): cp[i] = (tag, r.take(8)); i += 1
        elif tag in (7, 8, 16, 19, 20): cp[i] = (tag, r.u2())
        elif tag in (9, 10, 11, 12, 17, 18): cp[i] = (tag, r.u2(), r.u2())
        elif tag == 15: cp[i] = (tag, r.u1(), r.u2())
        else: raise AssertionError(tag)
        i += 1
    def utf(i):
        assert cp[i][0] == 1
        return cp[i][1]
    def attrs(reader):
        result = {}
        for _ in range(reader.u2()):
            name = utf(reader.u2())
            result[name] = reader.take(reader.u4())
        return result
    r.take(6)
    r.take(2 * r.u2())
    for _ in range(r.u2()): r.take(6); attrs(r)
    methods = {}
    for _ in range(r.u2()):
        r.u2()
        name, desc = utf(r.u2()), utf(r.u2())
        attributes = attrs(r)
        if 'Code' in attributes:
            c = Reader(attributes['Code']); c.take(4)
            methods[(name, desc)] = c.take(c.u4())
    attrs(r)
    assert r.pos == len(data)
    def member(index):
        tag, clazz, name_type = cp[index]
        assert tag in (9, 10, 11) and cp[clazz][0] == 7 and cp[name_type][0] == 12
        return utf(cp[clazz][1]), utf(cp[name_type][1]), utf(cp[name_type][2])
    return methods, member

plan, reported = read('plan.json'), read('summary.json')
jar = Path(plan['jar'])
assert file_sha(jar) == plan['jarSha256'] == 'a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe'
assert plan['base'] == '4e328b0109e13c896b74004823fb049fcb19251a'
assert file_sha(plan['manifest']) == plan['manifestSha256']
assert file_sha(plan['template']) == plan['templateSha256']
template = json.loads(Path(plan['template']).read_text())
assert template[template.index('-jar') + 1] == str(jar)
name = 'parallelRawDistinctCallSiteStringProjection$lambda$32$lambda$31$lambda$30'
owner = 'io.johnsonlee.graphite.webgraph.MappedWebGraphBackedGraph'
with zipfile.ZipFile(jar) as z:
    graph_class = z.read(owner.replace('.', '/') + '.class')
    set_class = z.read('it/unimi/dsi/fastutil/ints/IntOpenHashSet.class')
graph_methods, graph_member = class_methods(graph_class)
set_methods, set_member = class_methods(set_class)
keys = [key for key in graph_methods if key[0] == name]
assert len(keys) == 1
method_key = keys[0]
method = owner + ' ' + name + ' ' + method_key[1]
contains = 'it.unimi.dsi.fastutil.ints.IntOpenHashSet contains (I)Z'
callback_code = graph_methods[method_key]
contains_code = set_methods[('contains', '(I)Z')]
assert len(callback_code) == 790 and len(contains_code) == 70
assert callback_code[322] == 182
callee = graph_member(int.from_bytes(callback_code[323:325], 'big'))
assert callee == ('it/unimi/dsi/fastutil/ints/IntOpenHashSet', 'contains', '(I)Z')
assert contains_code[35:42] == bytes([27, 28, 160, 0, 5, 4, 172])
assert contains_code[42:47] == bytes([45, 21, 4, 4, 96])
assert set_member(int.from_bytes(contains_code[49:51], 'big')) == ('it/unimi/dsi/fastutil/ints/IntOpenHashSet', 'mask', 'I')
class_hashes = {owner.replace('.', '/') + '.class': sha(graph_class), 'it/unimi/dsi/fastutil/ints/IntOpenHashSet.class': sha(set_class)}
assert class_hashes == reported['classes']
javap = read('javap-receipt.json')
assert javap['jar'] == str(jar) and javap['jarSha256'] == plan['jarSha256']
assert javap['classSha256'] == sha(set_class) and file_sha(P / javap['output']) == javap['outputSha256']

args = shlex.split(template[template.index('-jvmArgs') + 1])
oracle_path = Path(next(x.split('=', 1)[1] for x in args if x.startswith('-Dgraphite.broad.pressure.correctness.oracle=')))
prior_path = Path(next(x.split('=', 1)[1] for x in args if x.startswith('-Dgraphite.broad.pressure.observations.output=')))
fields = ['id', 'family', 'shape', 'selectivity', 'operator', 'boundary', 'projection', 'targetGraphId', 'workloadIdentity', 'limit', 'outcome', 'rowCount', 'responseBytes', 'digest']
oracle = [line.split('|') for line in oracle_path.read_text().splitlines() if line and not line.startswith('#')]
assert len(oracle) == 34 and all(len(row) == 14 for row in oracle)
def rows(path):
    with Path(path).open() as stream: return list(csv.DictReader(stream, delimiter='\t'))
prior = rows(prior_path)
assert len(prior) == 34 and len({row['id'] for row in prior}) == 34
assert [[row[f] for f in fields] for row in prior] == oracle

forks = []
for number in range(1, 4):
    xml_path = P / f'fork-{number}.compilation.xml'
    data = xml_path.read_bytes() if xml_path.exists() else gzip.decompress(Path(str(xml_path) + '.gz').read_bytes())
    doc = ET.fromstring(data)
    assert doc.tag == 'hotspot_log' and len(doc.findall('tty')) == 1 and doc.find('hotspot_log_done') is not None
    tty = list(doc.find('tty'))
    queued = [dict(e.attrib) for e in tty if e.tag == 'task_queued' and e.get('method') == method]
    published = [dict(e.attrib) for e in tty if e.tag == 'nmethod' and e.get('method') == method]
    assert queued and all(e['bytes'] == '790' for e in queued + published)
    ids = {e['compile_id'] for e in queued + published}
    runtime = [{'eventIndex': i, 'tag': e.tag, **e.attrib, 'frames': [dict(f.attrib) for f in e.findall('jvms')]} for i, e in enumerate(tty)
               if e.get('method') == method or e.get('compile_id') in ids or any(f.get('method') == method for f in e.findall('jvms'))]
    c2 = [e for e in runtime if e['tag'] == 'nmethod' and e.get('compiler') == 'c2']
    traps = [e for e in runtime if e['tag'] == 'uncommon_trap']
    assert len(c2) == len(traps) == 1
    publication, trap = c2[0], traps[0]
    assert trap['compile_id'] == publication['compile_id'] and trap['eventIndex'] > publication['eventIndex']
    assert trap['reason'] == 'unstable_if' and trap['action'] == 'reinterpret' and trap['compiler'] == 'c2' and trap['level'] == '4'
    assert [(f['method'], f['bci'], f['bytes']) for f in trap['frames']] == [(contains, '37', '70'), (method, '322', '790')]
    invalid = [e for e in runtime if e['tag'] == 'make_not_entrant' and e['compile_id'] == trap['compile_id']]
    assert len(invalid) == 1 and invalid[0]['eventIndex'] > trap['eventIndex']
    later_c1 = [e for e in runtime if e['tag'] == 'nmethod' and e.get('compiler') == 'c1' and e['eventIndex'] > trap['eventIndex']]
    assert later_c1
    unpublished = [q for q in queued if q['compile_id'] not in {e.get('compile_id') for e in tty if e.tag == 'nmethod'}]
    assert len(unpublished) == 1 and float(unpublished[0]['stamp']) > float(trap['stamp'])
    completed, fragments, parse_traps = [], [], 0
    all_fragment_count = 0
    for compiler_log in doc.findall('compilation_log'):
        for task in compiler_log.findall('task'):
            if task.get('method') != method: continue
            record = {'attributes': dict(task.attrib), 'taskDone': [dict(x.attrib) for x in task.findall('task_done')], 'failures': [dict(x.attrib) for x in task.findall('.//failure')]}
            assert record['taskDone'] and all(x['success'] == '1' for x in record['taskDone']) and not record['failures']
            completed.append(record)
            parse_traps += len(task.findall('.//uncommon_trap'))
        for fragment in compiler_log.findall('fragment'):
            all_fragment_count += 1
            text = fragment.text or ''
            start = re.search(r'<task\b[^>]*>', text)
            assert start
            attrs = ET.fromstring(start.group(0) + '</task>').attrib
            if attrs.get('method') != method: continue
            assert attrs['compile_id'] == unpublished[0]['compile_id']
            assert '<task_done' not in text and '</task>' not in text
            starts = [dict(x.attrib) for x in compiler_log.findall('start_compile_thread')]
            assert starts and all(x.get('name', '').startswith('C2 CompilerThread') or x.get('name', '').startswith('C2CompilerThread') for x in starts)
            fragments.append({'task': dict(attrs), 'compilerLogThread': compiler_log.get('thread'), 'compilerThreadRecords': starts, 'textBytes': len(text.encode()), 'textSha256': sha(text.encode()), 'hasTaskDone': False})
    assert len(fragments) == 1 and unpublished[0]['compile_id'] not in {t['attributes']['compile_id'] for t in completed}
    main = reported['runs'][number - 1]
    assert main['xmlSha256'] == sha(data)
    assert main['runtimeEvents'] == runtime and main['queued'] == queued and main['published'] == published
    assert main['completedCompilerTasks'] == completed and main['incompleteCompilerFragmentCount'] == len(fragments)
    assert main['queuedWithoutPublishedNmethod'] == unpublished
    command = read(f'fork-{number}-command.json')
    expected = list(template)
    expected[expected.index('-rff') + 1] = str(P / f'fork-{number}.json')
    expected[expected.index('-jvmArgs') + 1] = ' '.join(args).replace(str(prior_path), str(P / f'fork-{number}.tsv')) + f' -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile={xml_path}'
    assert command == expected
    vm_args = doc.findtext('vm_arguments/args').strip()
    actual = shlex.split(vm_args)
    requested = shlex.split(command[command.index('-jvmArgs') + 1])
    assert actual[:len(requested)] == requested
    extra = actual[len(requested):]
    assert extra[:3] == ['-XX:+UnlockDiagnosticVMOptions', '-XX:+UnlockExperimentalVMOptions', '-DcompilerBlackholesEnabled=true']
    assert len(extra) == 4 and extra[3].startswith('-XX:CompileCommandFile=')
    properties = doc.findtext('vm_arguments/properties')
    assert '\njava.class.path=' + str(jar) + '\n' in properties
    assert '\njava.vm.specification.version=17\n' in properties
    assert not any(re.search(r'agentpath|agentlib|FlightRecorder|StartFlightRecording|Xcomp|Xbatch|CompileThreshold|TieredStopAtLevel|CompileCommand=', x) for x in actual)
    jmh = read(f'fork-{number}.json')
    assert len(jmh) == 1
    result = jmh[0]
    assert result['jvmArgs'] == requested and result['jdkVersion'].startswith('17.')
    assert result['warmupIterations'] == 0 and result['measurementIterations'] == 1 and result['forks'] == 1
    assert command[command.index('-prof') + 1] == 'gc'
    observed = rows(P / f'fork-{number}.tsv')
    assert len(observed) == 34 and [[r[f] for f in fields] for r in observed] == oracle
    assert all(int(r['latencyNanos']) > 0 and r['outcome'] == 'success' for r in observed)
    differences = [{'id': r['id'], 'fields': [k for k in r if k != 'latencyNanos' and r[k] != b[k]]} for r, b in zip(observed, prior)]
    assert all(not r['fields'] for r in differences) and set(observed[0]) == set(prior[0])
    assert main['oracleSignaturesVerified'] == 34 and main['nonLatencyDifferencesVersusPriorBase'] == []
    forks.append({'fork': number, 'xmlSha256': sha(data), 'runtimeEvents': runtime, 'c1Publications': [e for e in published if e.get('compiler') == 'c1'], 'c2Publication': publication, 'runtimeTrap': trap, 'invalidation': invalid[0], 'laterC1PublicationCount': len(later_c1), 'queuedWithoutNmethod': unpublished, 'incompleteTargetFragments': fragments, 'allCompilerFragments': all_fragment_count, 'completedTasks': completed, 'excludedCompilerParseTrapCount': parse_traps, 'oracleSignatures': 34, 'nonLatencyDifferences': [], 'actualVmArguments': actual, 'jmhAutomaticVmArguments': extra, 'commandMatchesTemplatePlusOnlyDeclaredChanges': True, 'parentSummaryMatches': True, 'hotspotLogDone': dict(doc.find('hotspot_log_done').attrib)})

before, after = read('graph-content-before.json'), read('graph-content-after.json')
assert before == after and len(before) == 64 and len({g['id'] for g in before}) == 64
manifest_entries = [line.split('\t') for line in Path(plan['manifest']).read_text().splitlines() if line and not line.startswith('#')]
assert [g['id'] for g in before] == [r[0] for r in manifest_entries]
assert sum(len(g['files']) for g in before) == 1088
for graph in before:
    assert len({f['path'] for f in graph['files']}) == len(graph['files'])
    assert all(f['size'] >= 0 and re.fullmatch('[0-9a-f]{64}', f['sha256']) for f in graph['files'])
runs = read('runs.json')
assert [r['fork'] for r in runs] == [1, 2, 3]
assert all(r['exitCode'] == 0 and r['jarSha256Before'] == r['jarSha256After'] == plan['jarSha256'] and r['endedEpoch'] > r['startedEpoch'] for r in runs)
assert all(runs[i]['endedEpoch'] <= runs[i+1]['startedEpoch'] for i in range(2))
completion = read('completion.json')
assert completion['forks'] == 3 and completion['graphCount'] == 64 and completion['graphFiles'] == 1088 and completion['graphsUnchanged'] and completion['jarSha256'] == plan['jarSha256']
inputs = {n: file_sha(P / n) for n in ['plan.json', 'runs.json', 'completion.json', 'run.py', 'summary.json', 'README.md', 'graph-content-before.json', 'graph-content-after.json', 'javap-receipt.json', *[f'fork-{i}{suffix}' for i in range(1, 4) for suffix in ['-command.json', '.tsv', '.json', '.log']]]}
inputs.update({str(oracle_path): file_sha(oracle_path), str(prior_path): file_sha(prior_path), str(plan['manifest']): file_sha(plan['manifest']), str(plan['template']): file_sha(plan['template'])})
output = {'passed': True, 'parentReportErrors': [], 'newJavaBuildTimingOrPerformanceRun': False, 'ranParentAnalysisScript': False, 'method': 'Independent original XML tty-event reconstruction, separate compiler tasks/fragments, binary classfile Code and constant-pool parsing, exact TSV signatures and field comparisons, command/actual VM/receipt cross-checks.', 'jar': str(jar), 'jarSha256IndependentlyRead': file_sha(jar), 'classSha256': class_hashes, 'bytecode': {'callbackMethod': method, 'callbackCodeBytes': 790, 'callbackBci322': {'opcode': 'invokevirtual', 'member': callee}, 'containsCodeBytes': 70, 'containsBci37': {'opcode': 'if_icmpne', 'targetBci': 42, 'operands': 'iload_1 (key), iload_2 (initial nonempty slot)', 'fallthrough': 'iconst_1; ireturn', 'unequalTarget': 'linear probing starts at key[(pos + 1) & mask]'}}, 'forks': forks, 'oracleSignatureCount': 102, 'allNonLatencyFieldsEqualPriorBase': True, 'inputReceipts': {'graphs': 64, 'files': 1088, 'manifestGraphOrderEqual': True, 'allBeforeAfterPathSizeSha256EntriesEqual': True, 'independentlyRehashedGraphContent': False, 'allThreeJarBeforeAfterReceiptsMatchCurrentlyRehashedJar': True, 'runProcessesSucceededAndSequential': True}, 'inputSha256': inputs, 'limits': ['No query timestamp windows; no query attribution or deoptimization-time, latency, regression-cause or speedup inference.', 'Compiler logging may perturb runtime. These forks are not a controlled tracing-on/off comparison or an acceptance gate.', 'The later C2 task is evidenced by its C2 compiler fragment. No nmethod before log end and no task_done in its fragment do not establish a compilation failure.', 'Runtime uncommon_trap events are counted only directly under tty, never compiler parse nodes.', 'Actual VM includes standard JMH compiler-blackhole flags and a temporary CompileCommandFile; no separately added forced-compilation options. The temporary file contents were not independently recovered.', 'Graph integrity audit compares the complete captured before/after inventories, manifest order, and runner hashing implementation; it does not rehash the large live graph corpus again.', 'JAR and both classes were independently read and hashed now; historical pre/post identity is backed by captured run receipts.']}
(P / 'independent-audit.json').write_text(json.dumps(output, indent=2, ensure_ascii=False) + '\n')
print(json.dumps({'passed': True, 'oracle': 102, 'forks': [{'fork': f['fork'], 'c2': f['c2Publication']['compile_id'], 'trap': f['runtimeTrap']['stamp'], 'unpublished': f['queuedWithoutNmethod'][0]['compile_id'], 'excludedCompilerParseTraps': f['excludedCompilerParseTrapCount']} for f in forks]}, ensure_ascii=False))
