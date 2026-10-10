"""Meaningful tiny raw-payload cases, not a real-graph or performance run."""
import copy
import struct
import unittest

import jvm_pressure_derivation as d
import jvm_pressure_inputs as inputs
import jvm_pressure_oracles as model
from native_core_proof import legacy_wire as wire
from native_core_proof.wire_gty05_test import parse as type_fixture
import test_jvm_pressure_inputs as raw_fixture


def method(owner='android.app.Activity', name='getProvider', result='void', params=()):
    return wire.Method(owner, name, tuple(params), result)


def call(node, owner='android.app.Activity', name='getProvider', callee=None):
    return dict(id=node, tag=12, caller=method(owner, name), callee=callee or method('java.lang.String', 'valueOf'),
                line=-1, receiver=-1, arguments=[], ordinal=-7, origin=None)


def graph(gid, rows=(), methods=(), table=None):
    return {'id': gid, 'revision': 'b'*40, 'nodes': {r['id']: r for r in rows},
            'nodeSlots': max((r['id'] for r in rows), default=-1)+1,
            'methods': {m.key: m for m in methods}, 'declarations': table if table is not None else wire.Types(),
            'fullNodeScanConsumed': True}


def edges(g, values=()):
    return struct.pack('>iiq', 0x47534501, g['nodeSlots'], len(values)) + b''.join(struct.pack('>iiB', *v) for v in values)


def complete(selected):
    collector = d.Collector()
    for gid in model.FIXTURE_GRAPH_IDS:
        g, links = selected.get(gid, (graph(gid), [])); collector.add_graph(g, edges(g, links), len(links))
    return collector.finish()


def rows(result, name): return result['universes'][name]['rows']


class DerivationTests(unittest.TestCase):
    def setUp(self): self.cases = {c['id']: c for c in model.cases()}

    def test_accessor_keys_and_annotation_precedence_differ_from_property_map(self):
        row = dict(id=7, tag=13, name='VisibleAnnotation', owner='Owner', member='m', values={
            'id': 999, 'name': d.HIT, 'class': 'hidden', 'member': 'hidden',
            'type': None, 'graphId': 'wrong', 'qualifiedId': 'wrong', 'values': d.HIT,
            'value': [None, inputs.EnumReference('android.permission', 'INTERNET')]})
        values, keys = d.node_properties(row, 'real', wire.Types())
        self.assertEqual((7, 'VisibleAnnotation', 'Owner', 'm', 'AnnotationNode', 'real', 'real:7'),
                         tuple(values[k] for k in ('id', 'name', 'class', 'member', 'type', 'graphId', 'qualifiedId')))
        self.assertEqual(len(keys), len(set(keys))); self.assertEqual(['id', 'name', 'class', 'member'], keys[:4])
        self.assertIsInstance(values['values'], d.MapText)
        self.assertTrue(d.contains_text(values['values'], d.HIT))
        self.assertEqual(['AnnotationNode', 'Annotation'], list(d.LABELS[13]))
        hidden = dict(row, values={'name': d.HIT})
        v, k = d.node_properties(hidden, 'real', wire.Types())
        self.assertNotIn('values', k); self.assertFalse(any(d.contains_text(v.get(key), d.HIT) for key in k))

    def test_direct_value_contains_does_not_coerce_enum_list_or_number(self):
        enum = inputs.EnumReference('android.permission', 'INTERNET')
        row = dict(id=1, tag=7, enum_type='E', name='N', arguments=[enum])
        gid = model.FIXTURE_GRAPH_IDS[0]; values, keys = d.node_properties(row, gid, wire.Types())
        self.assertFalse(d.node_matches(self.cases['slow-valueHit'], row, values, keys))
        self.assertTrue(d.node_matches(self.cases['slow-dynamicHit'], row, values, keys))
        projected = d.node_projection(self.cases['slow-dynamicHit'], row, values)
        self.assertEqual({'enumClass': 'android.permission', 'enumName': 'INTERNET'}, projected['value'])
        self.assertEqual('N', values['name'])

    def test_scoped_map_list_text_boundary_proof_and_enum_dot_join(self):
        self.assertTrue(d.contains_text({'key': [None, inputs.EnumReference('android.permission', 'INTERNET')]}, d.HIT))
        self.assertFalse(d.contains_text({'android.permission': 'INTERNET'}, d.HIT))
        self.assertFalse(d.contains_text(['android.permission.', 'INTERNET'], d.HIT))
        self.assertFalse(d.contains_text([123, False, inputs.FloatBits(32, bytes.fromhex('7fc00000'))], d.HIT))
        self.assertTrue(d.contains_text(d.MapText({'key': {'nested': d.MISS}}), d.MISS))
        with self.assertRaisesRegex(ValueError, 'reviewed literal'): d.contains_text({'x': 'y'}, 'x=y')

    def test_all_typed_projections_enum_null_double_signed_zero_and_float_authority_gap(self):
        zero = inputs.FloatBits(64, bytes.fromhex('8000000000000000'))
        value = d.projected_value([zero, {'value': inputs.EnumReference('E', 'N'), 'missing': None}, True, 2**61+1])
        self.assertEqual('-0x0.0p+0', value[0].hex())
        self.assertEqual([value[0], {'value': {'enumClass': 'E', 'enumName': 'N'}}, True, 2**61+1], model.gson_value(value))
        for raw in (bytes.fromhex('3dcccccd'), bytes(4)):
            with self.assertRaisesRegex(d.MissingJvmSemantics, 'Float32'): d.projected_value(inputs.FloatBits(32, raw))
        with self.assertRaisesRegex(ValueError, 'nonfinite'): d.projected_value(inputs.FloatBits(64, bytes.fromhex('7ff0000000000000')))

    def test_lowercase_source_rules_and_explicit_unicode_gap(self):
        self.assertEqual('getprovider', d.lowered('GETProvider')); self.assertEqual('', d.lowered(None))
        self.assertIsNone(d.lowered(12)); self.assertIsNone(d.lowered(['GET']))
        with self.assertRaisesRegex(d.MissingJvmSemantics, 'Locale.ROOT'): d.lowered('\u212aotlin')
        gid = model.FIXTURE_GRAPH_IDS[0]; row = call(1, 'GraphitePressureAbsentRoutingPairX')
        values, keys = d.node_properties(row, gid, wire.Types())
        # The literal on the RHS is not lowercased by the request.
        self.assertFalse(d.node_matches(self.cases['routing-pair-miss'], row, values, keys))

    def test_full_method_descriptors_line_ordinal_and_virtual_type_keys(self):
        row = call(1, callee=method('Owner', 'overload', 'java.lang.Object', ['int[][]']))
        values, keys = d.node_properties(row, model.FIXTURE_GRAPH_IDS[0], wire.Types())
        self.assertEqual('Owner.overload(int[][])', values['callee_signature'])
        self.assertEqual('([[I)Ljava/lang/Object;', values['callee_descriptor'])
        self.assertIsNone(values['line']); self.assertEqual(-7, values['ordinal'])
        self.assertEqual('CallSiteNode', values['type']); self.assertNotIn('type', keys)
        self.assertEqual(['CallSiteNode'], list(d.LABELS[12]))

    def test_generic_presence_joins_structural_full_keys_and_parameter_bounds(self):
        table = type_fixture(); gid = model.FIXTURE_GRAPH_IDS[0]
        field = dict(id=1, tag=9, owner='Example', name='items', type='java.util.List', static=False)
        values, keys = d.node_properties(field, gid, table)
        self.assertEqual('java.util.List<T>', values['generic_type'])
        self.assertEqual('class:Example', values['type_info']['arguments'][0]['scope'])
        self.assertEqual(['generic_type', 'type_info'], [k for k in keys if k in ('generic_type', 'type_info')])
        m = method('Example', 'take', 'java.lang.Object', ['java.util.List', 'int[]'])
        parameter = dict(id=2, tag=10, index=1, type='int[]', method=m)
        self.assertEqual(5, d.declaration_id(parameter, table))
        self.assertIsNone(d.declaration_id(dict(parameter, index=-1), table))
        self.assertIsNone(d.declaration_id(dict(parameter, index=2), table))
        self.assertIsNone(d.declaration_id(dict(parameter, method=method('Example', 'take', 'void', m.parameters)), table))
        result = dict(id=3, tag=11, method=m, actual_type=None)
        self.assertEqual(6, d.declaration_id(result, table))

    def test_each_literal_discovery_operator_and_scope(self):
        checks = {
            'wrapped-zero_hit_query': call(1, name='GRAPHITE_LATENCY_NO_SUCH_SYMBOL_9F36'),
            'wrapped-dense_distributed_method_query': call(1, 'X', 'GETTHING'),
            'wrapped-early_graph_prefix_query': call(1, 'ANDROID.app.Test', 'm'),
            'wrapped-middle_graphs_prefix_query': call(1, 'ORG.APACHE.HADOOP.HIVE.Test', 'm'),
            'wrapped-late_graph_prefix_query': call(1, 'ORG.JETBRAINS.KOTLIN.Test', 'm'),
            'wrapped-broadly_distributed_prefix_query': call(1, 'JAVA.lang.String', 'm'),
            'wrapped-first_last_graph_bimodal_query': call(1, 'ORG.JETBRAINS.KOTLIN.Test', 'm'),
            'wrapped-skewed_mixed_operator_query': call(1, 'X', 'FooPROVIDER'),
            'routing-pair-dense': call(1, 'GET.Class', 'm')}
        for name, row in checks.items():
            values, keys = d.node_properties(row, model.FIXTURE_GRAPH_IDS[0], wire.Types())
            with self.subTest(case=name): self.assertTrue(d.node_matches(self.cases[name], row, values, keys))
        row = checks['routing-pair-dense']; values, keys = d.node_properties(row, model.FIXTURE_GRAPH_IDS[1], wire.Types())
        self.assertFalse(d.node_matches(self.cases['routing-pair-dense'], row, values, keys))

    def test_complete26_universes_include_named_miss_positive_controls_and_all_dataflow_cases(self):
        gid = model.FIXTURE_GRAPH_IDS[0]
        nodes = [dict(id=1, tag=1, value=d.HIT), dict(id=2, tag=1, value=d.MISS),
                 dict(id=3, tag=1, value=d.DECLARED_MISS), call(4), call(5, d.MISS), call(6, d.DECLARED_MISS),
                 dict(id=938826, tag=6, value=None), dict(id=93882699, tag=6, value=None)]
        g = graph(gid, nodes); result = complete({gid: (g, [(1, 4, 0), (2, 5, 8)])})
        self.assertEqual(set(self.cases), set(result['universes']))
        for name in ('dynamic-miss', 'callsite-dynamic-miss', 'slow-valueHit', 'slow-valueMiss', 'slow-dynamicHit',
                     'slow-dynamicMiss', 'slow-qualifiedIdHit', 'slow-qualifiedIdMiss', 'slow-wrappedCallerHit',
                     'slow-wrappedCallerMiss', *d.DATAFLOW_IDS):
            with self.subTest(case=name): self.assertGreater(result['universes'][name]['totalMatches'], 0)
        source = rows(result, 'slow-dataflowSourceHit')[0]['value']
        self.assertEqual({'source': 1, 'target': 4, 'relationship': 'DATAFLOW', 'value': d.HIT,
                          'caller': 'android.app.Activity', 'graphId': gid, '$metadata': {'graphIds': [gid]}}, source)
        self.assertFalse(result['oracleAuthorityVerified']); self.assertFalse(result['fresh64Acceptance'])
        self.assertEqual(64, len(result['graphs']))

    def test_distinct_merges_complete_cross_graph_provenance_without_limit_early_stop(self):
        first, last = model.FIXTURE_GRAPH_IDS[0], model.FIXTURE_GRAPH_IDS[-1]
        a = graph(first, [call(1), call(2)]); b = graph(last, [call(3)])
        result = complete({first: (a, []), last: (b, [])})
        entry = rows(result, 'wrapped-early_graph_prefix_query')[0]
        self.assertEqual(1, entry['multiplicity']); self.assertNotIn('n.graph_id', entry['value'])
        self.assertEqual([first, last], entry['value']['$metadata']['graphIds'])
        routing = rows(result, 'routing-pair-dense')
        self.assertEqual([2, 1], [r['multiplicity'] for r in routing])
        self.assertEqual([[first], [last]], [r['value']['$metadata']['graphIds'] for r in routing])

    def test_unordered_limit_retains_full_universe_and_validates_any_legal_complete_subset(self):
        gid = model.FIXTURE_GRAPH_IDS[0]; nodes = [dict(id=n, tag=1, value=d.HIT) for n in range(60)]
        result = complete({gid: (graph(gid, nodes), [])}); universe = result['universes']['slow-valueHit']
        self.assertEqual(60, universe['totalMatches']); self.assertEqual(60, len(universe['rows']))
        case = self.cases['slow-valueHit']; verifier = model.CompiledLegalLimitOracle(universe, case)
        selected = [r['value'] for r in reversed(universe['rows'][10:])]
        body = dict(mode='cross-graph', graphs=case['requestedGraphIds'], graphCount=64, columns=case['columns'],
                    rows=selected, rowCount=50, limit=50)
        verifier.validate(body)
        body['rows'][0] = body['rows'][1]
        with self.assertRaises(ValueError): verifier.validate(body)

    def test_features_use_all_materialized_nodes_and_full_return_inclusive_method_keys(self):
        table = type_fixture(); gid = model.FIXTURE_GRAPH_IDS[0]
        m = method('Example', 'take', 'java.lang.Object', ['java.util.List', 'int[]'])
        other = method('Example', 'take', 'void', m.parameters)
        nodes = [dict(id=1, tag=9, owner='Example', name='items', type='java.util.List', static=False),
                 dict(id=2, tag=9, owner='Example', name='missing', type='java.util.List', static=True),
                 dict(id=3, tag=10, index=0, type='java.util.List', method=m),
                 dict(id=4, tag=10, index=3, type='java.util.List', method=m),
                 dict(id=5, tag=11, actual_type=None, method=m)]
        result = complete({gid: (graph(gid, nodes, [m, other], table), [])})
        feature = [e['value'] for e in rows(result, 'feature-nodes')]
        self.assertEqual({('FieldNode', True, 1), ('FieldNode', False, 1), ('ParameterNode', True, 1),
                          ('ParameterNode', False, 1), ('ReturnNode', True, 1)}, {(r['kind'], r['rendered'], r['members']) for r in feature})
        methods = [e['value'] for e in rows(result, 'feature-methods')]
        self.assertEqual({(True, 1), (False, 1)}, {(r['returnRendered'], r['members']) for r in methods})
        for row in methods:
            self.assertEqual([row['returnRendered']]*5, [row[k] for k in ('returnRendered', 'returnStructured', 'parametersRendered', 'parametersStructured', 'formalParameters')])

    def test_raw_reader_outputs_feed_properties_without_native_collector(self):
        fixture = raw_fixture.RawInputsTests(); fixture.setUp(); self.addCleanup(fixture.doCleanups)
        raw = inputs.read_graph(fixture.spec); values, keys = d.node_properties(raw['nodes'][27], raw['id'], raw['declarations'])
        self.assertTrue(d.node_matches(self.cases['slow-dynamicHit'], raw['nodes'][27], values, keys))
        projected = d.node_projection(self.cases['slow-dynamicHit'], raw['nodes'][27], values)
        self.assertEqual(['android.permission.INTERNET', None, {'enumClass': 'Example', 'enumName': 'enum'}], projected['value'])
        self.assertEqual(['AnnotationNode', 'Annotation'], projected['labels'])

    def test_failures_poison_incomplete_collector_and_do_not_silently_skip_records(self):
        gid = model.FIXTURE_GRAPH_IDS[0]; collector = d.Collector(); g = graph(gid, [call(1, '\u212aotlin')])
        with self.assertRaises(d.MissingJvmSemantics): collector.add_graph(g, edges(g), 0)
        with self.assertRaisesRegex(ValueError, 'complete successful'): collector.finish()
        with self.assertRaisesRegex(ValueError, 'previous graph'): collector.add_graph(graph(model.FIXTURE_GRAPH_IDS[1]), edges(graph(model.FIXTURE_GRAPH_IDS[1])), 0)
        collector = d.Collector(); g = graph(gid)
        collector.add_graph(g, edges(g), 0)
        with self.assertRaisesRegex(ValueError, 'complete successful'): collector.finish()
        with self.assertRaisesRegex(ValueError, 'unique registered'): collector.add_graph(g, edges(g), 0)

    def test_projected_float_dataflow_and_mixed_distinct_are_explicit_authority_gaps(self):
        gid = model.FIXTURE_GRAPH_IDS[0]
        g = graph(gid, [dict(id=1, tag=3, value=inputs.FloatBits(32, bytes.fromhex('3dcccccd'))), call(2)])
        with self.assertRaisesRegex(d.MissingJvmSemantics, 'Float32'): complete({gid: (g, [(1, 2, 0)])})
        annotation = dict(id=1, tag=13, name='A', owner='X', member='m', values={'caller_class': 'android.Test', 'caller_name': 123})
        with self.assertRaisesRegex(d.MissingJvmSemantics, 'non-string projection'): complete({gid: (graph(gid, [annotation]), [])})

    def test_invalid_dataflow_kind_truncation_and_wrong_node_identity_rejected(self):
        gid = model.FIXTURE_GRAPH_IDS[0]; g = graph(gid, [dict(id=1, tag=1, value=d.HIT), call(2)])
        with self.assertRaisesRegex(ValueError, 'kind ordinal'): complete({gid: (g, [(1, 2, 9 << 3)])})
        collector = d.Collector()
        with self.assertRaisesRegex(ValueError, 'byte length'): collector.add_graph(g, edges(g, [(1, 2, 0)])[:-1], 1)
        bad = copy.deepcopy(g); bad['nodes'][1]['id'] = 3
        with self.assertRaisesRegex(ValueError, 'ID binding'): d.Collector().add_graph(bad, edges(bad), 0)


if __name__ == '__main__': unittest.main()
