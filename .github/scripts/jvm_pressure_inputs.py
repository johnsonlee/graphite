"""Raw, single-arm inputs for the 26 JVM pressure-case oracles.

Reuse the owned core string exports and independent GR*/GTY readers. This module
does not execute a query, derive JVM properties, certify an upstream audit, or
claim semantic/performance acceptance. The caller must replay the producer and
string-export audits once, outside per-graph decoding, and bind their source and
runtime identities. All payloads below retain original IDs and float bits.
"""
import copy
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import struct

from native_core_proof import callsite_ordinals, declarations, legacy_wire as wire
from native_core_proof import wire_gty05

need = wire.need
TOPOLOGY_FILES = ('forward.graph', 'forward.offsets', 'forward.properties',
                  'graph.labels', 'graph.labelprefix')
READ_FILES = ('graph.strings', 'graph.metadata', 'graph.nodedata', 'graph.nodeoffsets',
              'graph.typeindex', 'graph.callsite-ordinals', 'forward.properties')


@dataclass(frozen=True)
class FloatBits:
    """No Python/Rust decimal rendering is substituted for JVM Float/Double."""
    width: int
    bits: bytes


@dataclass(frozen=True)
class EnumReference:
    owner: str
    name: str


def any_value(reader, depth=0):
    need(depth <= 256, 'nested raw value exceeds decoder bound')
    tag = reader.byte()
    if tag == 0: return reader.i()
    if tag == 1: return reader.q()
    if tag == 2: return reader.sid()
    if tag in (3, 4):
        return FloatBits(32 if tag == 3 else 64, bytes(reader.take(4 if tag == 3 else 8)))
    if tag == 5: return reader.boolean()
    if tag == 6: return None
    if tag == 7: return EnumReference(reader.sid(), reader.sid())
    if tag == 8: return [any_value(reader, depth + 1) for _ in range(reader.count(1))]
    raise wire.Invalid('unknown raw value tag')


def node_payloads(data, offsets, strings, type_index):
    """Yield raw records; exhaustion also runs the retained full framing checks.

    The retained reader deliberately discards many values. Decode those payloads
    at their original checked offsets, and check every payload boundary against
    the next original offset/EOF. Do not modify the retained comparison decoder.
    """
    expected_start = 8
    for identity in wire.node_records(data, offsets, strings, type_index):
        node, tag = identity['id'], identity['tag']
        start = struct.unpack_from('>q', offsets, 8 + 8 * node)[0] - 1
        need(start == expected_start, 'raw payload boundary differs from persisted order')
        r = wire.Reader(data, strings); r.pos = start
        need(r.i() == node and r.byte() == tag, 'raw payload node/tag binding')
        row = {'id': node, 'tag': tag}
        if tag == 0: row['value'] = r.i()
        elif tag == 1: row['value'] = r.sid()
        elif tag == 2: row['value'] = r.q()
        elif tag in (3, 4): row['value'] = FloatBits(32 if tag == 3 else 64, bytes(r.take(4 if tag == 3 else 8)))
        elif tag == 5: row['value'] = r.boolean()
        elif tag == 6: row['value'] = None
        elif tag == 7:
            row.update(enum_type=r.sid(), name=r.sid(), arguments=[any_value(r) for _ in range(r.count(1))])
        elif tag == 8: row.update(name=r.sid(), type=r.sid(), method=wire.method(r))
        elif tag == 9: row.update(owner=r.sid(), name=r.sid(), type=r.sid(), static=r.boolean())
        elif tag == 10: row.update(index=r.i(), type=r.sid(), method=wire.method(r))
        elif tag == 11:
            row['method'] = wire.method(r); row['actual_type'] = r.sid() if r.boolean() else None
        elif tag == 12:
            row.update(caller=wire.method(r), callee=wire.method(r), line=r.i(), receiver=r.i())
            row['arguments'] = [r.i() for _ in range(r.count())]
        elif tag == 13:
            row.update(name=r.sid(), owner=r.sid(), member=r.sid()); values = {}
            for _ in range(r.count(5)):
                key = r.sid(); need(key not in values, 'duplicate raw annotation key')
                values[key] = any_value(r)
            row['values'] = values
        elif tag == 14:
            row.update(path=r.sid(), key=r.sid(), value=any_value(r), format=r.sid())
            row['profile'] = r.sid() if r.boolean() else None
        elif tag == 15:
            row.update(path=r.sid(), source=r.sid(), format=r.sid())
            row['profile'] = r.sid() if r.boolean() else None
        else: raise wire.Invalid('unsupported raw node tag')
        expected_start = r.pos
        yield row
    need(expected_start == len(data), 'raw payload trailing bytes')


def _properties(raw):
    """Same narrow fresh-writer properties grammar as the core proof."""
    try: lines = raw.decode('ascii').splitlines()
    except UnicodeDecodeError: raise wire.Invalid('non-ASCII fresh properties')
    result = {}
    for line in lines:
        line = line.strip()
        if not line or line.startswith(('#', '!')): continue
        need('\\' not in line and '=' in line, 'unsupported fresh properties syntax')
        key, value = (s.strip() for s in line.split('=', 1))
        need(re.fullmatch(r'[A-Za-z0-9_.-]+', key) and key not in result, 'duplicate/invalid fresh property')
        result[key] = value
    return result


def graph_inputs(fixture, exports, graph_id):
    """Select a graph from already replayed upstream manifests, without hashing.

    This is an identity join, NOT a replacement for producer/export audit replay.
    Keep the original export row, including its actual serialized dictionary pin.
    """
    graphs = fixture['graphs']; rows = exports['graphs']
    need(len(graphs) == len(rows) == 64 and len({g['id'] for g in graphs}) == 64 and
         [g['id'] for g in graphs] == [g['id'] for g in rows], 'exact ordered own-writer64 export scope')
    need(exports['schema'] == 'graphite.native-core-string-export-audit.v1' and
         exports['status'] == 'PASS_ALL64_STRING_EXPORTS_INDEPENDENT_RAW_AUDIT' and
         exports['revision'] == fixture['writerRevision'], 'actual writer/export identity')
    selected = [i for i, g in enumerate(graphs) if g['id'] == graph_id]
    need(len(selected) == 1, 'unknown actual graph ID')
    index = selected[0]; graph = graphs[index]; row = rows[index]
    root = Path(graph['path'])
    need(root.is_absolute() and str(root) == str(root.resolve()), 'canonical actual graph root')
    files = {p: digest for p, digest in fixture['files'].items() if Path(p).parent == root}
    need(all(str(root / name) in files for name in READ_FILES), 'complete raw graph reader inputs')
    need(row['input'] == {'path': str(root/'graph.strings'), 'sha256': files[str(root/'graph.strings')]},
         'actual dictionary row belongs to graph')
    for name in ('stringsExport', 'stringsReceipt'):
        ref = row[name]; need(exports['pins'].get(ref['path']) == ref['sha256'], 'export audit evidence binding')
    need(type(graph['nodes']) is int and graph['nodes'] >= 0 and
         type(graph['callSites']) is int and 0 <= graph['callSites'] <= graph['nodes'], 'actual node counts')
    return copy.deepcopy({'id': graph_id, 'root': str(root), 'revision': fixture['writerRevision'],
                          'nodes': graph['nodes'], 'callSites': graph['callSites'],
                          'files': files, 'export': row})


def _read(path, digest):
    path = Path(path)
    need(path.is_absolute() and str(path) == str(path.resolve()) and path.is_file() and not path.is_symlink(),
         'canonical regular raw input')
    raw = path.read_bytes()
    need(hashlib.sha256(raw).hexdigest() == digest, 'same-read raw input changed: ' + str(path))
    return raw


def read_graph(spec):
    """Fully consume one graph's raw nodes, metadata, ordinals and declarations.

    Returned floats/enum references are raw values, not JSON or Cypher values.
    This stage makes no JVM property or whole-fixture oracle-authority claim.
    """
    root = Path(spec['root']); files = spec['files']; consumed = {}
    def read(name):
        path = str(root/name); need(path in files, 'missing actual raw input pin: ' + name)
        raw = _read(path, files[path]); consumed[path] = files[path]; return raw
    raw = {name: read(name) for name in READ_FILES}
    row = spec['export']
    need(row['id'] == spec['id'] and row['input'] ==
         {'path': str(root/'graph.strings'), 'sha256': files[str(root/'graph.strings')]}, 'dictionary graph binding')
    exported = _read(row['stringsExport']['path'], row['stringsExport']['sha256'])
    receipt_raw = _read(row['stringsReceipt']['path'], row['stringsReceipt']['sha256'])
    for ref in (row['stringsExport'], row['stringsReceipt']): consumed[ref['path']] = ref['sha256']
    context = wire_gty05.verified_strings_export(exported, json.loads(receipt_raw),
        files[str(root/'graph.strings')], row['helperClassSha256'], declarations.SOURCE_SHA)
    if str(root/'graph.strings.identity') in files:
        need(read('graph.strings.identity').hex() == wire.strings_export(exported)[1], 'dictionary semantic identity')
    props = _properties(raw['forward.properties']); type_path = str(root/'graph.types')
    binding = props.get('graphite.declaredTypes.sha256')
    if binding is None:
        need(type_path not in files and not (root/'graph.types').exists(), 'orphan type sidecar cannot supply declarations')
        table = wire_gty05.Types()
    else:
        type_raw = read('graph.types')
        # Reuse the reviewed GTY01–05 entry point, including active-property,
        # serialized dictionary, export helper and metadata bindings.
        table = declarations.load(root, row)
        need(_read(type_path, files[type_path]) == type_raw and
             _read(root/'forward.properties', files[str(root/'forward.properties')]) == raw['forward.properties'],
             'declaration authority changed during decode')
        for name in ('graph.metadata', 'graph.strings'):
            need(_read(root/name, files[str(root/name)]) == raw[name], 'declaration input changed during decode')
    nodes = {r['id']: r for r in node_payloads(raw['graph.nodedata'], raw['graph.nodeoffsets'],
                                            context.values, raw['graph.typeindex'])}
    need(len(nodes) == spec['nodes'], 'complete actual materialized node count')
    members = {n: (r['caller'].key, r['callee'].key) for n, r in nodes.items() if r['tag'] == 12}
    need(len(members) == spec['callSites'], 'complete actual CallSite count')
    methods = wire.metadata_methods(raw['graph.metadata'], context.values)
    ordinal = callsite_ordinals.decode(raw['graph.callsite-ordinals'],
        callsite_ordinals.bind_metadata(raw['graph.metadata']), members)
    for node, value in ordinal['rows'].items():
        nodes[node]['ordinal'] = value['ordinal']; nodes[node]['origin'] = value['origin']
    slots = struct.unpack_from('>i', raw['graph.nodeoffsets'], 4)[0]
    return {'id': spec['id'], 'revision': spec['revision'], 'nodeSlots': slots,
            'nodes': nodes, 'methods': methods, 'declarations': table,
            'consumedPins': consumed, 'fullNodeScanConsumed': True,
            'ordinalEntries': ordinal['ordinalEntries'], 'oracleAuthorityVerified': False,
            'jvmPropertyDerivationComplete': False, 'fresh64Acceptance': False}


def edge_records(raw, actual_nodes, node_slots, edge_count):
    """Stream original GSE01 edges; fully exhaust before accepting a scan."""
    need(type(node_slots) is int and node_slots >= 0 and type(edge_count) is int and edge_count >= 0,
         'integer raw edge counts')
    need(len(raw) >= 16 and struct.unpack_from('>iiq', raw) == (0x47534501, node_slots, edge_count),
         'exact raw edge header/counts')
    need(len(raw) == 16 + edge_count * 9, 'complete raw edge byte length')
    need(all(type(n) is int and 0 <= n < node_slots for n in actual_nodes), 'actual raw node range')
    previous = (-1, -1)
    for offset in range(16, len(raw), 9):
        source, target, label = struct.unpack_from('>iiB', raw, offset)
        need(source in actual_nodes and target in actual_nodes, 'raw edge endpoint absent from graph')
        need((source, target) > previous, 'raw adjacency unique ordered edges')
        previous = source, target
        yield source, target, label


def edge_export_input(receipt, graph_root, source_pins, helper_pins):
    """Check raw edge receipt identity against already audited phase/input pins.

    The caller must additionally bind this exact receipt to the actual successful
    topology phase. A self-asserted receipt is not source/execution authority.
    """
    checks = ('allSequentialOffsetsChecked', 'allRandomAccessOffsetsChecked',
              'allLabelPrefixOffsetsChecked', 'rawNodeIdsPreserved', 'fullScanConsumed')
    required = {'schema', 'format', 'graphRoot', 'sourceInputs', 'output', 'nodeSlots',
                'labeledEdges', 'helperSource', 'helperClass', 'mappingApplied', *checks}
    need(set(receipt) == required and receipt['schema'] == 'graphite.raw-labeled-edge-export.v1' and
         receipt['format'] == 'GSE01', 'exact raw edge receipt shape')
    need(receipt['graphRoot'] == str(Path(graph_root).resolve()) and
         all(receipt[k] is True for k in checks) and receipt['mappingApplied'] is False,
         'complete original-ID raw edge export')
    need(set(receipt['sourceInputs']) == set(TOPOLOGY_FILES), 'complete raw topology source closure')
    for name, ref in receipt['sourceInputs'].items():
        expected = str(Path(graph_root)/name)
        need(ref == {'path': expected, 'sha256': source_pins.get(expected)} and
             re.fullmatch('[0-9a-f]{64}', ref['sha256'] or ''), 'actual topology source binding')
    for key in ('helperSource', 'helperClass'):
        ref = receipt[key]
        need(set(ref) == {'path', 'sha256'} and helper_pins.get(ref['path']) == ref['sha256'] and
             re.fullmatch('[0-9a-f]{64}', ref['sha256'] or ''), 'actual topology helper binding')
    for key in ('nodeSlots', 'labeledEdges'):
        need(type(receipt[key]) is int and receipt[key] >= 0, 'integer topology receipt counts')
    ref = receipt['output']; need(set(ref) == {'path', 'sha256'}, 'raw edge output reference')
    path = Path(ref['path'])
    need(path.is_absolute() and str(path) == str(path.resolve()) and
         not path.is_relative_to(Path(graph_root).resolve()), 'raw edges outside original graph root')
    raw = _read(path, ref['sha256'])
    need(len(raw) >= 16 and struct.unpack_from('>iiq', raw) ==
         (0x47534501, receipt['nodeSlots'], receipt['labeledEdges']) and
         len(raw) == 16 + 9 * receipt['labeledEdges'], 'raw edge receipt header/count/length binding')
    return raw
