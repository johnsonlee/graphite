"""Small byte/receipt correctness tests only: no child process or real graph."""
import copy
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

import jvm_pressure_inputs as p
from native_core_proof import callsite_ordinals_test as ord_fixture
from native_core_proof import wire_gty05 as gty

I = lambda n: struct.pack('>i', n)
Q = lambda n: struct.pack('>q', n)
sha = lambda raw: hashlib.sha256(raw).hexdigest()


def node_files(records):
    data = I(0x47524e03) + I(len(records)); offsets = {}; tags = {n: [] for n in range(16)}
    for node, tag, payload in records:
        offsets[node] = len(data) + 1; tags[tag].append(node)
        data += I(node) + bytes([tag]) + payload
    slots = max(offsets, default=-1) + 1
    index = I(0x47525403) + I(16); at = 8 + 16 * 13
    for tag in range(16):
        index += bytes([tag]) + I(len(tags[tag])) + Q(at); at += 4 * len(tags[tag])
    index += b''.join(I(n) for tag in range(16) for n in tags[tag])
    return data, I(0x47524c03) + I(slots) + b''.join(Q(offsets.get(n, 0)) for n in range(slots)), index


def all_payloads(strings):
    sid = lambda text: I(strings.index(text))
    method = sid('Example') + sid('m') + I(1) + sid('int[]') + sid('void')
    values = [I(-7), sid('android.permission.INTERNET'), Q(2**55 + 3), bytes.fromhex('80000000'),
              bytes.fromhex('7ff8000000000042'), b'\x01', b'',
              sid('Example') + sid('enum') + I(2) + b'\x07' + sid('Example') + sid('enum') + b'\x06',
              sid('local') + sid('int[]') + method,
              sid('Example') + sid('items') + sid('Example') + b'\x01',
              I(0) + sid('int[]') + method, method + b'\x00',
              method + method + I(-1) + I(-1) + I(2) + I(1) + I(3),
              sid('Annotation') + sid('Example') + sid('m') + I(2) + sid('value') +
              b'\x08' + I(3) + b'\x02' + sid('android.permission.INTERNET') + b'\x06' +
              b'\x07' + sid('Example') + sid('enum') + sid('id') + b'\x00' + I(99),
              sid('path') + sid('key') + b'\x05\x00' + sid('format') + b'\x01' + sid('profile'),
              sid('path') + sid('source') + sid('format') + b'\x00']
    return [(tag * 2 + 1, tag, value) for tag, value in enumerate(values)], method


class RawInputsTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve(); self.graph = self.root/'graph'; self.graph.mkdir()
        self.strings = sorted(['Annotation', 'Example', 'T', 'android.permission.INTERNET', 'enum', 'format',
                               'id', 'int[]', 'items', 'java.util.List', 'key', 'local', 'm', 'path',
                               'profile', 'source', 'value', 'void'])
        self.records, self.method = all_payloads(self.strings)
        data, offsets, index = node_files(self.records)
        ordinal, binding = ord_fixture.encode([(25, -3)])
        metadata = I(0x47524d03) + I(1) + self.method + I(0) * 7 + I(0x47524202) + binding
        for name, raw in {'graph.nodedata': data, 'graph.nodeoffsets': offsets, 'graph.typeindex': index,
                          'graph.metadata': metadata, 'graph.callsite-ordinals': ordinal,
                          'graph.strings': b'actual tiny serialized dictionary',
                          'forward.properties': b'graphclass=tiny.CorrectnessFixture\n'}.items():
            (self.graph/name).write_bytes(raw)
        for name in p.TOPOLOGY_FILES:
            if not (self.graph/name).exists(): (self.graph/name).write_bytes(b'tiny ' + name.encode())
        exported = I(0x47534f01) + I(len(self.strings)) + b''.join(I(len(s.encode())) + s.encode() for s in self.strings)
        self.export = self.root/'strings.bin'; self.export.write_bytes(exported)
        semantic = p.wire.strings_export(exported)[1]
        (self.graph/'graph.strings.identity').write_bytes(bytes.fromhex(semantic))
        self.receipt = self.root/'receipt.json'
        self.receipt.write_text(json.dumps({'format': 'GSO01', 'inputSha256': sha((self.graph/'graph.strings').read_bytes()),
            'outputSha256': sha(exported), 'entryCount': len(self.strings), 'semanticSha256': semantic,
            'helperClassSha256': 'a'*64, 'helperSourceSha256': p.declarations.SOURCE_SHA}))
        self.row = {'id': 'fixture-android-00', 'input': self.ref(self.graph/'graph.strings'),
                    'stringsExport': self.ref(self.export), 'stringsReceipt': self.ref(self.receipt),
                    'helperClassSha256': 'a'*64}
        self.refresh()
        no_child = patch('subprocess.Popen', side_effect=AssertionError('no child process allowed'))
        no_child.start(); self.addCleanup(no_child.stop)

    def ref(self, path): return {'path': str(path), 'sha256': sha(path.read_bytes())}

    def refresh(self):
        self.spec = {'id': self.row['id'], 'root': str(self.graph), 'revision': 'b'*40, 'nodes': len(self.records),
                     'callSites': 1, 'files': {str(f): sha(f.read_bytes()) for f in self.graph.iterdir()},
                     'export': copy.deepcopy(self.row)}

    def add_table(self):
        sid = lambda text: I(self.strings.index(text))
        row = lambda kind, name, scope, target, args: bytes([kind, 0, scope, 0]) + sid(name) + I(target) + I(-1)*2 + I(len(args)) + b''.join(map(I, args))
        raw = I(0x47545905) + bytes.fromhex(sha((self.graph/'graph.metadata').read_bytes())) + bytes.fromhex(sha((self.graph/'graph.strings').read_bytes()))
        raw += I(3) + row(0, 'Example', 0, -1, []) + row(3, 'T', 1, 0, []) + row(0, 'java.util.List', 0, -1, [1])
        raw += I(0) + I(1) + sid('Example') + sid('items') + I(0) + I(2) + I(0)
        raw += I(1) + sid('Example') + I(1) + sid('T') + bytes([1, 0, 0, 0]) + I(0) + I(1) + I(0) + I(-1) + I(0)
        (self.graph/'graph.types').write_bytes(raw)
        (self.graph/'forward.properties').write_text('graphite.declaredTypes.sha256=' + sha(raw) + '\n')
        self.refresh()

    def test_complete_all16_original_payloads_and_full_method_identity(self):
        graph = p.read_graph(self.spec); nodes = graph['nodes']
        self.assertEqual([i*2+1 for i in range(16)], list(nodes))
        self.assertEqual(-7, nodes[1]['value']); self.assertEqual(2**55+3, nodes[5]['value'])
        self.assertEqual('android.permission.INTERNET', nodes[3]['value'])
        self.assertEqual(p.FloatBits(32, bytes.fromhex('80000000')), nodes[7]['value'])
        self.assertEqual(p.FloatBits(64, bytes.fromhex('7ff8000000000042')), nodes[9]['value'])
        self.assertIs(nodes[11]['value'], True); self.assertIsNone(nodes[13]['value'])
        self.assertEqual([p.EnumReference('Example', 'enum'), None], nodes[15]['arguments'])
        self.assertEqual(('Example', 'm', '([I)V'), nodes[17]['method'].key)
        self.assertEqual({'id': 19, 'tag': 9, 'owner': 'Example', 'name': 'items', 'type': 'Example', 'static': True}, nodes[19])
        self.assertEqual(0, nodes[21]['index']); self.assertIsNone(nodes[23]['actual_type'])
        self.assertEqual((-1, -1, [1, 3], -3, None), tuple(nodes[25][k] for k in ('line', 'receiver', 'arguments', 'ordinal', 'origin')))
        self.assertEqual(['android.permission.INTERNET', None, p.EnumReference('Example', 'enum')], nodes[27]['values']['value'])
        self.assertEqual(99, nodes[27]['values']['id'])  # raw collision retained; property precedence is a later JVM rule
        self.assertIs(nodes[29]['value'], False); self.assertEqual('profile', nodes[29]['profile'])
        self.assertEqual('source', nodes[31]['source']); self.assertIsNone(nodes[31]['profile'])
        self.assertEqual([('Example', 'm', '([I)V')], list(graph['methods']))
        self.assertEqual(32, graph['nodeSlots']); self.assertTrue(graph['fullNodeScanConsumed'])
        self.assertFalse(graph['oracleAuthorityVerified']); self.assertFalse(graph['jvmPropertyDerivationComplete'])
        self.assertFalse(graph['fresh64Acceptance'])

    def test_gty05_structural_binding_and_composition_reuses_actual_dictionary(self):
        self.add_table(); graph = p.read_graph(self.spec); table = graph['declarations']
        self.assertIsInstance(table, gty.StructuralTypes)
        self.assertEqual({('Example', 'items', 'LExample;'): 2}, table.fields)
        self.assertEqual('java.util.List<T>', table.render(2))
        self.assertEqual('class:Example', table.info(2)['arguments'][0]['scope'])
        self.assertNotIn('java.util.List<T>', self.strings)
        self.assertEqual(19, graph['nodes'][19]['id'])

    def test_prefeature_graph_has_no_declaration_sidecar(self):
        self.assertEqual({}, p.read_graph(self.spec)['declarations'].fields)
        self.add_table(); (self.graph/'forward.properties').write_text('graphclass=fixture\n'); self.refresh()
        with self.assertRaisesRegex(ValueError, 'orphan'): p.read_graph(self.spec)

    def test_missing_malformed_mismatched_and_stale_types_rejected(self):
        self.add_table(); original = (self.graph/'forward.properties').read_bytes()
        for bad in (b'graphite.declaredTypes.sha256=bad\n', b'graphite.declaredTypes.sha256=' + b'a'*64 + b'\n',
                    original + original, b'graphite.declaredTypes.sha256:\\u0061\n'):
            (self.graph/'forward.properties').write_bytes(bad); self.refresh()
            with self.subTest(bad=bad), self.assertRaises(ValueError): p.read_graph(self.spec)
        (self.graph/'forward.properties').write_bytes(original); (self.graph/'graph.types').unlink(); self.refresh()
        with self.assertRaisesRegex(ValueError, 'missing actual raw input pin'): p.read_graph(self.spec)

    def test_same_read_hash_dictionary_helper_and_semantic_identity_rejected(self):
        original = (self.graph/'graph.nodedata').read_bytes()
        (self.graph/'graph.nodedata').write_bytes(original + b'changed')
        with self.assertRaisesRegex(ValueError, 'same-read raw input changed'): p.read_graph(self.spec)
        (self.graph/'graph.nodedata').write_bytes(original)
        self.spec['export']['helperClassSha256'] = 'b'*64
        with self.assertRaisesRegex(ValueError, 'export helper binding'): p.read_graph(self.spec)
        self.refresh(); (self.graph/'graph.strings.identity').write_bytes(bytes(32)); self.refresh()
        with self.assertRaisesRegex(ValueError, 'dictionary semantic identity'): p.read_graph(self.spec)

    def test_truncation_trailing_invalid_id_offset_and_type_index_rejected(self):
        data = (self.graph/'graph.nodedata').read_bytes(); offsets = (self.graph/'graph.nodeoffsets').read_bytes(); index = (self.graph/'graph.typeindex').read_bytes()
        for cut in range(len(data)):
            with self.subTest(cut=cut), self.assertRaises(ValueError): list(p.node_payloads(data[:cut], offsets, self.strings, index))
        mutations = [(data+b'X', offsets, index), (data[:8]+I(0)+data[12:], offsets, index),
                     (data, offsets[:16]+Q(0)+offsets[24:], index), (data, offsets, index[:-1]+b'\xff')]
        for d, o, x in mutations:
            with self.assertRaises(ValueError): list(p.node_payloads(d, o, self.strings, x))

    def test_metadata_and_complete_ordinal_payload_are_not_inferred(self):
        ordinal = self.graph/'graph.callsite-ordinals'; raw = ordinal.read_bytes(); ordinal.write_bytes(raw[:-1]+b'\x04'); self.refresh()
        with self.assertRaisesRegex(ValueError, 'block SHA'): p.read_graph(self.spec)
        ordinal.write_bytes(raw); self.refresh(); self.spec['callSites'] = 0
        with self.assertRaisesRegex(ValueError, 'CallSite count'): p.read_graph(self.spec)
        self.refresh(); self.spec['nodes'] = 15
        with self.assertRaisesRegex(ValueError, 'materialized node count'): p.read_graph(self.spec)

    def test_raw_value_all_tags_signed_zero_nested_enum_and_null(self):
        sid = lambda s: I(self.strings.index(s))
        raw = b'\x08'+I(9)+b'\x00'+I(-2)+b'\x01'+Q(2**60+1)+b'\x02'+sid('Example')+b'\x03'+bytes.fromhex('80000000')+b'\x04'+bytes.fromhex('7ff0000000000000')+b'\x05\x00'+b'\x06'+b'\x07'+sid('Example')+sid('enum')+b'\x08'+I(1)+b'\x06'
        reader = p.wire.Reader(raw, self.strings); values = p.any_value(reader); reader.done()
        self.assertEqual([-2, 2**60+1, 'Example', p.FloatBits(32, bytes.fromhex('80000000')),
                          p.FloatBits(64, bytes.fromhex('7ff0000000000000')), False, None,
                          p.EnumReference('Example', 'enum'), [None]], values)
        for bad in (b'\xff', b'\x05\x02', b'\x02'+I(999), b'\x08'+I(-1), b'\x03\x00'):
            with self.subTest(bad=bad), self.assertRaises(ValueError): p.any_value(p.wire.Reader(bad, self.strings))

    def test_raw_value_depth_and_duplicate_annotation_keys_rejected(self):
        with self.assertRaisesRegex(ValueError, 'bound'): p.any_value(p.wire.Reader((b'\x08'+I(1))*258+b'\x06'))
        sid = lambda s: I(self.strings.index(s))
        payload = sid('Annotation')+sid('Example')+sid('m')+I(2)+(sid('id')+b'\x06')*2
        data, offsets, index = node_files([(0, 13, payload)])
        with self.assertRaisesRegex(ValueError, 'duplicate annotation key'):
            list(p.node_payloads(data, offsets, self.strings, index))

    def test_noncanonical_and_symlink_input_rejected(self):
        path = self.graph/'graph.nodedata'; raw = path.read_bytes(); path.unlink()
        target = self.root/'other'; target.write_bytes(raw); path.symlink_to(target)
        with self.assertRaisesRegex(ValueError, 'canonical regular'): p.read_graph(self.spec)

    def bundle(self):
        graphs = []; rows = []; files = {}; pins = {}
        for i in range(64):
            gid = f'fixture-{i:02d}'; root = self.root/gid
            graphs.append({'id': gid, 'path': str(root), 'nodes': 16, 'callSites': 1})
            for name in p.READ_FILES: files[str(root/name)] = 'a'*64
            row = copy.deepcopy(self.row); row['id'] = gid; row['input'] = {'path': str(root/'graph.strings'), 'sha256': 'a'*64}
            rows.append(row)
            for key in ('stringsExport', 'stringsReceipt'): pins[row[key]['path']] = row[key]['sha256']
        return {'graphs': graphs, 'files': files, 'writerRevision': 'b'*40}, {
            'schema': 'graphite.native-core-string-export-audit.v1',
            'status': 'PASS_ALL64_STRING_EXPORTS_INDEPENDENT_RAW_AUDIT', 'revision': 'b'*40,
            'graphs': rows, 'pins': pins}

    def test_exact_all64_identity_join_keeps_actual_export_and_no_file_scan(self):
        fixture, exports = self.bundle(); result = p.graph_inputs(fixture, exports, 'fixture-17')
        self.assertEqual('fixture-17', result['id']); self.assertEqual(16, result['nodes'])
        self.assertEqual(exports['graphs'][17], result['export'])
        exports['graphs'][17]['id'] = 'changed'
        self.assertEqual('fixture-17', result['export']['id'])

    def test_incomplete_reordered_wrong_revision_dictionary_or_unbound_export_rejected(self):
        for change in ('count', 'order', 'revision', 'dictionary', 'pin'):
            fixture, exports = self.bundle()
            if change == 'count': exports['graphs'].pop()
            elif change == 'order': exports['graphs'].reverse()
            elif change == 'revision': exports['revision'] = 'c'*40
            elif change == 'dictionary': exports['graphs'][17]['input']['sha256'] = 'c'*64
            else: exports['pins'].clear()
            with self.subTest(change=change), self.assertRaises(ValueError): p.graph_inputs(fixture, exports, 'fixture-17')

    def edge_receipt(self):
        raw = struct.pack('>iiq', 0x47534501, 32, 2)+struct.pack('>iiB', 1, 3, 0)+struct.pack('>iiB', 3, 25, 248)
        out = self.root/'actual-edges.bin'; out.write_bytes(raw)
        source = self.root/'VerifyTopology.java'; source.write_bytes(b'tiny source')
        compiled = self.root/'VerifyTopology.class'; compiled.write_bytes(b'tiny class')
        receipt = {'schema': 'graphite.raw-labeled-edge-export.v1', 'format': 'GSE01', 'graphRoot': str(self.graph),
            'sourceInputs': {n: self.ref(self.graph/n) for n in p.TOPOLOGY_FILES}, 'output': self.ref(out),
            'nodeSlots': 32, 'labeledEdges': 2, 'helperSource': self.ref(source), 'helperClass': self.ref(compiled),
            'allSequentialOffsetsChecked': True, 'allRandomAccessOffsetsChecked': True,
            'allLabelPrefixOffsetsChecked': True, 'rawNodeIdsPreserved': True, 'mappingApplied': False,
            'fullScanConsumed': True}
        helper_pins = {str(source): sha(source.read_bytes()), str(compiled): sha(compiled.read_bytes())}
        return receipt, helper_pins

    def test_raw_edges_keep_original_ids_and_full_unsigned_label_octet(self):
        receipt, helper = self.edge_receipt()
        raw = p.edge_export_input(receipt, self.graph, self.spec['files'], helper)
        self.assertEqual([(1, 3, 0), (3, 25, 248)], list(p.edge_records(raw, {1, 3, 25}, 32, 2)))
        self.assertEqual([], list(p.edge_records(struct.pack('>iiq', 0x47534501, 0, 0), {}, 0, 0)))

    def test_raw_edge_receipt_rejects_mapped_partial_unbound_or_changed_outputs(self):
        receipt, helper = self.edge_receipt()
        for key, value in [('mappingApplied', True), ('rawNodeIdsPreserved', False), ('fullScanConsumed', False),
                           ('labeledEdges', True), ('graphRoot', str(self.root/'other'))]:
            bad = copy.deepcopy(receipt); bad[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): p.edge_export_input(bad, self.graph, self.spec['files'], helper)
        for key in ('forward.graph', 'graph.labels'):
            bad = copy.deepcopy(receipt); bad['sourceInputs'][key]['sha256'] = 'e'*64
            with self.assertRaises(ValueError): p.edge_export_input(bad, self.graph, self.spec['files'], helper)
        with self.assertRaisesRegex(ValueError, 'helper binding'): p.edge_export_input(receipt, self.graph, self.spec['files'], {})
        Path(receipt['output']['path']).write_bytes(b'partial')
        with self.assertRaisesRegex(ValueError, 'same-read'): p.edge_export_input(receipt, self.graph, self.spec['files'], helper)

    def test_raw_edge_truncation_header_order_endpoints_duplicates_and_tail_rejected(self):
        receipt, _ = self.edge_receipt(); raw = Path(receipt['output']['path']).read_bytes()
        for cut in range(len(raw)):
            with self.subTest(cut=cut), self.assertRaises(ValueError): list(p.edge_records(raw[:cut], {1, 3, 25}, 32, 2))
        header = raw[:16]
        for bad in (raw+b'X', header+raw[25:]+raw[16:25], header+raw[16:25]*2,
                    header+struct.pack('>iiB', 1, 4, 0)+raw[25:]):
            with self.assertRaises(ValueError): list(p.edge_records(bad, {1, 3, 25}, 32, 2))
        with self.assertRaises(ValueError): list(p.edge_records(raw, {1, 3, 25}, 31, 2))

    def test_edge_receipt_cannot_repin_malformed_header_or_move_output_into_graph(self):
        receipt, helper = self.edge_receipt(); path = Path(receipt['output']['path']); raw = path.read_bytes()
        for malformed in (b'', raw[:-1], raw+b'x', I(0x47534502)+raw[4:], raw[:8]+Q(1)+raw[16:]):
            path.write_bytes(malformed); changed = copy.deepcopy(receipt); changed['output'] = self.ref(path)
            with self.subTest(raw=malformed), self.assertRaisesRegex(ValueError, 'header/count/length'):
                p.edge_export_input(changed, self.graph, self.spec['files'], helper)
        path.write_bytes(raw); inner = self.graph/'edges.bin'; inner.write_bytes(raw)
        changed = copy.deepcopy(receipt); changed['output'] = self.ref(inner)
        with self.assertRaisesRegex(ValueError, 'outside original graph'): p.edge_export_input(changed, self.graph, self.spec['files'], helper)

    def test_nonsequential_record_ids_and_absent_ordinal_are_not_normalized(self):
        # Persisted record order is independent of numeric node IDs. The index
        # still has to preserve exact per-tag order and all empty offsets.
        sid = lambda s: I(self.strings.index(s))
        records = [(7, 1, sid('Example')), (2, 1, sid('value'))]
        data, offsets, index = node_files(records)
        self.assertEqual([7, 2], [r['id'] for r in p.node_payloads(data, offsets, self.strings, index)])
        ordinal, binding = ord_fixture.encode([])
        (self.graph/'graph.callsite-ordinals').write_bytes(ordinal)
        metadata = self.graph/'graph.metadata'; metadata.write_bytes(metadata.read_bytes()[:-32]+binding)
        self.refresh(); graph = p.read_graph(self.spec)
        self.assertIsNone(graph['nodes'][25]['ordinal']); self.assertIsNone(graph['nodes'][25]['origin'])
        self.assertEqual(0, graph['ordinalEntries'])


if __name__ == '__main__': unittest.main()
