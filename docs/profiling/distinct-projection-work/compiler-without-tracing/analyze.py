import csv
import gzip
import hashlib
import json
import pathlib
import xml.etree.ElementTree as ET
import zipfile

ROOT = pathlib.Path(__file__).parent
METHOD = 'parallelRawDistinctCallSiteStringProjection$lambda$32$lambda$31$lambda$30'
CLASS = 'io.johnsonlee.graphite.webgraph.MappedWebGraphBackedGraph'
PRIOR = pathlib.Path('/private/tmp/graphite-attempt140._5jztd0a/old34-pairs')
FIELDS = ['id', 'family', 'shape', 'selectivity', 'operator', 'boundary', 'projection', 'targetGraphId', 'workloadIdentity', 'limit', 'outcome', 'rowCount', 'responseBytes', 'digest']

def sha(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()

def target(method):
    return method.startswith(CLASS + ' ' + METHOD + ' (')

oracle = (PRIOR / 'oracle.correctness').read_text().splitlines()
assert len(oracle) == 34
reference = list(csv.DictReader((PRIOR / 'base-global-wide-1.tsv').open(), delimiter='\t'))
summary = {'scope': 'Three frozen-main compiler-log diagnostic JVMs; no method tracing or async-profiler; GC profiler retained. This is not a gate or before/after comparison.', 'runs': []}
plan = json.loads((ROOT / 'plan.json').read_text())
with zipfile.ZipFile(plan['jar']) as jar:
    summary['classes'] = {entry: hashlib.sha256(jar.read(entry)).hexdigest() for entry in [CLASS.replace('.', '/') + '.class', 'it/unimi/dsi/fastutil/ints/IntOpenHashSet.class']}
for number in range(1, 4):
    path = ROOT / f'fork-{number}.compilation.xml'
    raw_xml = path.read_bytes() if path.exists() else gzip.decompress(path.with_suffix(path.suffix + '.gz').read_bytes())
    doc = ET.fromstring(raw_xml)
    tty = doc.find('tty')
    events = list(tty)
    queued = [e for e in events if e.tag == 'task_queued' and target(e.get('method', ''))]
    published = [e for e in events if e.tag == 'nmethod' and target(e.get('method', ''))]
    ids = {e.get('compile_id') for e in queued + published}
    relevant = []
    for position, e in enumerate(events):
        frames = [j.attrib for j in e.findall('jvms')]
        if e.get('compile_id') in ids or any(target(j.get('method', '')) for j in e.findall('jvms')):
            relevant.append({'eventIndex': position, 'tag': e.tag, **e.attrib, 'frames': frames})
    runtime_traps = [e for e in relevant if e['tag'] == 'uncommon_trap']
    c2 = [e for e in published if e.get('compiler') == 'c2']
    assert c2, 'The exact raw callback has a published C2 nmethod in each recording'
    for trap in runtime_traps:
        assert trap['compile_id'] in {e.get('compile_id') for e in c2}
        assert trap['reason'] == 'unstable_if' and trap['action'] == 'reinterpret'
        assert [(f['bci'], f['method'].split(' (')[0]) for f in trap['frames']] == [
            ('37', 'it.unimi.dsi.fastutil.ints.IntOpenHashSet contains'), ('322', CLASS + ' ' + METHOD)]
    rows = list(csv.DictReader((ROOT / f'fork-{number}.tsv').open(), delimiter='\t'))
    assert len(rows) == 34
    actual = ['|'.join(row[field] for field in FIELDS) for row in rows]
    assert actual == oracle
    differences = [{'id': row['id'], 'field': key, 'reference': old[key], 'actual': row[key]}
                   for row, old in zip(rows, reference) for key in row if key != 'latencyNanos' and row[key] != old[key]]
    assert all(row['outcome'] == 'success' for row in rows)
    compiler_tasks = [e for e in doc.findall('./compilation_log/task') if target(e.get('method', ''))]
    fragments = [e.text or '' for e in doc.findall('./compilation_log/fragment') if METHOD in (e.text or '')]
    run = {
        'fork': number, 'xmlSha256': hashlib.sha256(raw_xml).hexdigest(), 'process': doc.attrib,
        'actualVmArguments': doc.findtext('./vm_arguments/args'),
        'runtimeEvents': relevant,
        'queued': [e.attrib for e in queued], 'published': [e.attrib for e in published],
        'queuedWithoutPublishedNmethod': [e.attrib for e in queued if e.get('compile_id') not in {p.get('compile_id') for p in published}],
        'completedCompilerTasks': [{'attributes': e.attrib, 'taskDone': [d.attrib for d in e.findall('task_done')], 'failures': [f.attrib for f in e.iter('failure')]} for e in compiler_tasks],
        'incompleteCompilerFragmentCount': len(fragments),
        'oracleSignaturesVerified': len(actual), 'nonLatencyDifferencesVersusPriorBase': differences,
        'observedLatenciesMs': {row['id']: int(row['latencyNanos']) / 1e6 for row in rows},
        'hotspotLogDone': doc.find('hotspot_log_done').attrib,
    }
    summary['runs'].append(run)
summary['limits'] = [
    'Runtime tty events are separate from speculative compiler parse uncommon_trap nodes; only runtime events are counted.',
    'C2 publication proves compilation, not the proportion of a query spent in compiled code. Runtime trap proves that compiled code was encountered.',
    'Compiler logging may perturb timings. There are no query timestamp windows, so compilation events are not attributed to targeted versus dense queries.',
    'Queued tasks without nmethod publication or incomplete compiler fragments are not compile failures.',
    'Three repeated traps do not prove the cause of previous candidate regressions, an exclusive deoptimization cost, or a 10x optimization opportunity.',
    'No additional warmup, forced compilation, threshold change, candidate production code, or acceptance rerun.'
]
(ROOT / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
lines = ['# 冻结 main：关闭 method tracing 后的编译诊断', '',
    '三次独立 JVM 的原 34 条查询全部通过 14 字段 oracle，共 102 条；64 个真实图的 1,088 个文件前后 hash 不变。受保护的冻结 JAR 未修改或重建。仅增加 HotSpot 编译日志选项，保留原 GC profiler，没有 method tracing、async-profiler，也未人为增加预热或强制编译选项。', '',
    '新的证据排除了“该节点循环在此次短回放中完全没有 C2 编译”的说法：三份运行时日志都发布了这个精确 790-byte callback 的 C2 nmethod，随后都有一次 unstable_if / reinterpret 事件，其内联调用栈是 IntOpenHashSet.contains BCI 37 → callback BCI 322。', '',
    '| fork | 首次 C1 发布（JVM 秒） | C2 发布 | C2 trap | 再次 C2 排队但无发布 |', '|---|---:|---:|---:|---:|']
for run in summary['runs']:
    c1 = next(e for e in run['published'] if e['compiler'] == 'c1')
    c2 = next(e for e in run['published'] if e['compiler'] == 'c2')
    trap = next(e for e in run['runtimeEvents'] if e['tag'] == 'uncommon_trap')
    pending = run['queuedWithoutPublishedNmethod'][-1]
    lines.append(f"| {run['fork']} | {c1['stamp']} | {c2['stamp']} | {trap['stamp']} | {pending['stamp']} |")
lines += ['',
    '冻结 JAR 的 javap 显示 contains BCI 37 比较查询 key 与初始非空槽位中的 key，失败分支进入后续线性探测。这是去优化发生的位置；记录未提供此次 key、槽位值或完整分支历史，不能直接说是碰撞次数或命中率导致整体瓶颈。callback BCI 322 正是 exactMatchSets 的 contains 调用点。', '',
    'C2 trap 后有该 callback 的 make_not_entrant 以及新的 C1 nmethod。后续 C2 任务排队但在日志结束前未见 nmethod 发布，且存在未完成的 compiler fragment；这不等于编译失败。编译日志中的 parse 阶段 uncommon_trap 是生成代码的描述，不能混入运行时 trap 计数。', '',
    '旧采样中的 Interpreted/C1 标签不能代表没有 tracing 的完整执行状态。本轮既没有查询时间窗口，也不是 tracing 开关的配对试验，不能据此确定哪条查询承担了去优化时间，更不能把日志运行耗时当作新代码收益或解释既有失败。', '',
    'summary.json 保留了 JVM 记录的实际参数，包括 JMH 自动加入的 compiler-blackhole 选项与临时 CompileCommandFile 路径。临时文件内容没有另行保存，不能声称已逐份核验其完整字节；本轮命令相对原模板只新增编译日志选项。', '',
    '下一步证据需要回答：这个运行时转折发生在初始选择还是来源补全，以及去优化后的工作是否占据慢查询的关键路径。当前不启动生产优化；单纯缩短代码、按属性合并 OR 或移除线程池仍没有 10x 收益证据。', '',
    '可复算：`python3 analyze.py`。`summary.json` 保存全部原始事件属性、已完成/未完成编译任务、每条查询耗时与非耗时差异；`fork-*-command.json`、`runs.json`、`plan.json`、`completion.json` 和前后图清单保存范围与输入凭据。']
(ROOT / 'README.md').write_text('\n'.join(lines) + '\n')
print(json.dumps({'forks': 3, 'oracleSignaturesVerified': 102, 'runtimeTraps': [sum(e['tag'] == 'uncommon_trap' for e in r['runtimeEvents']) for r in summary['runs']], 'nonLatencyDifferenceCounts': [len(r['nonLatencyDifferencesVersusPriorBase']) for r in summary['runs']]}))
