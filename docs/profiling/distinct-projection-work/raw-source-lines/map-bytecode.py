from collections import Counter
from pathlib import Path
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parent
text = (ROOT / 'mapped-javap.txt').read_text()
name = 'parallelRawDistinctCallSiteStringProjection$lambda$32$lambda$31$lambda$30('
start = text.index('  private static final boolean ' + name)
end = text.index('\n  private ', start + 1)
method = text[start:end]
(ROOT / 'per-node-javap.txt').write_text(method + '\n')
code, tables = method.split('    LineNumberTable:', 1)
line_table = [(int(bci), int(line)) for line, bci in re.findall(r'line (\d+): (\d+)', tables.split('    LocalVariableTable:')[0])]
instructions = {int(bci): instruction.strip() for bci, instruction in re.findall(r'^\s+(\d+): (.+)$', code, re.M)}


def source(line):
    # Exact relevant entries retained below from this class's Kotlin/KotlinDebug SMAP.
    if 3634 <= line <= 3641:
        return {'file': 'MappedWebGraphBackedGraph.kt', 'line': 2452 + line - 3634, 'inlineCallSiteLine': 574}
    if 3642 <= line <= 3644:
        return {'file': '_Collections.kt', 'line': 1755 + line - 3642, 'inlineCallSiteLine': 579}
    if line == 3645:
        return {'file': 'MappedWebGraphBackedGraph.kt', 'line': 2461, 'inlineCallSiteLine': 574}
    if line in (3646, 3650):
        return {'file': '_Collections.kt', 'line': 1557, 'inlineCallSiteLine': 599 if line == 3646 else 604}
    if 3647 <= line <= 3649 or 3651 <= line <= 3653:
        base = 3647 if line < 3650 else 3651
        return {'file': '_Collections.kt', 'line': 1628 + line - base, 'inlineCallSiteLine': 599 if base == 3647 else 604}
    assert 1 <= line <= 3252, line
    return {'file': 'MappedWebGraphBackedGraph.kt', 'line': line, 'inlineCallSiteLine': None}


def category(bci):
    if bci < 65: return 'interrupt'
    if bci < 72: return 'accounting'
    if bci < 176: return 'raw_address_read'
    if bci < 200: return 'scratch_write'
    if bci < 277 or 455 <= bci < 473: return 'range_or_control'
    if bci < 297: return 'predicate_index_read'
    if bci < 328: return 'exact_set_check'
    if bci < 455: return 'fallback_match'
    if bci < 608: return 'selected_tuple'
    if bci < 774: return 'visible_tuple'
    return 'limit_check'


verbose = (ROOT / 'mapped-javap-verbose.txt').read_text().split('SourceDebugExtension:\n', 1)[1].split('\nRuntimeVisibleAnnotations:', 1)[0]
(ROOT / 'class-smap.txt').write_text(verbose + '\n')
for entry in ('2452#1,8:3634', '2461#1:3645', '1755#2,3:3642', '1557#2:3646', '1628#2,3:3647', '1557#2:3650', '1628#2,3:3651', '574#1:3634,8', '579#1:3642,3', '599#1:3647,3', '604#1:3651,3'):
    assert entry in verbose, entry
mapped_rows = []
for row in json.loads((ROOT / 'summary.json').read_text())['rows']:
    mapped = []
    for frame in row['nodeLeafDistribution']:
        bci = frame['bytecodeIndex']
        assert bci in instructions, bci
        latest_start = max(entry[0] for entry in line_table if entry[0] <= bci)
        candidates = [entry[1] for entry in line_table if entry[0] == latest_start]
        line = frame['lineNumber']
        assert line in candidates, (bci, candidates, frame)
        mapped.append({**frame, 'instruction': instructions[bci], 'lineTableCandidates': candidates, 'sourceLocation': source(line), 'bytecodeRegion': category(bci)})
    groups = Counter()
    frame_types = Counter()
    for frame in mapped:
        loc = frame['sourceLocation']
        groups[(loc['file'], loc['line'], loc['inlineCallSiteLine'])] += frame['samples']
        frame_types[frame['frameType']] += frame['samples']
    regions = Counter()
    for frame in mapped: regions[frame['bytecodeRegion']] += frame['samples']
    assert sum(regions.values()) == row['nodeLeafSamples']
    mapped_rows.append({'phase': row['phase'], 'id': row['id'], 'stage': row['stage'], 'nodeLeafSamples': row['nodeLeafSamples'], 'frameTypes': dict(frame_types), 'bytecodeRegionSampleCounts': dict(regions), 'mappedLeafFrames': mapped,
                        'sourceSampleCounts': [{'file': k[0], 'line': k[1], 'inlineCallSiteLine': k[2], 'samples': v} for k, v in groups.items()]})
out = {'classSha256': json.loads((ROOT / 'bytecode-receipt.json').read_text())['classSha256'], 'javapExcerptSha256': hashlib.sha256((ROOT / 'per-node-javap.txt').read_bytes()).hexdigest(), 'smapSha256': hashlib.sha256((ROOT / 'class-smap.txt').read_bytes()).hexdigest(), 'scope': 'Recorded BCI/source locations, not exclusive instruction CPU costs; interpreted/C1 status pertains to these instrumented captures only.', 'rows': mapped_rows}
(ROOT / 'source-mapping.json').write_text(json.dumps(out, ensure_ascii=False, indent=2) + '\n')
print('Verified', sum(row['nodeLeafSamples'] for row in mapped_rows), 'per-node leaf samples against exact class BCI, line table and relevant SMAP entries')
