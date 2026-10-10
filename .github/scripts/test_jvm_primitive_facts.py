"""Primitive packet/injection tests; supplied illustrative facts, no JDK run."""
import copy
import hashlib
import json
from pathlib import Path
import struct
import unittest
from unittest.mock import patch

import jvm_primitive_facts as f
import jvm_pressure_derivation as d
import jvm_pressure_inputs as inputs
import jvm_pressure_oracles as model
import test_jvm_pressure_derivation as examples


def encoded(value): return json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode('utf-8')


class PrimitiveFactsTests(unittest.TestCase):
    def setUp(self):
        blocked = patch('subprocess.Popen', side_effect=AssertionError('no Java/subprocess allowed'))
        blocked.start(); self.addCleanup(blocked.stop)
        self.graphs = []
        for gid in model.FIXTURE_GRAPH_IDS:
            graph = examples.graph(gid)
            graph['consumedPins'] = {f'/tiny-fixture/{gid}/graph.nodedata': 'a'*64,
                                    f'/tiny-fixture/{gid}/graph.strings': 'b'*64}
            self.graphs.append(graph)
        self.graphs[0]['nodes'] = {
            1: dict(id=1, tag=3, value=inputs.FloatBits(32, bytes.fromhex('3dcccccd'))),
            2: examples.call(2, 'org.jetbrains.\u212aotlin.Test'),
            3: dict(id=3, tag=4, value=inputs.FloatBits(64, bytes.fromhex('3fb999999999999a'))),
            4: dict(id=4, tag=3, value=inputs.FloatBits(32, bytes.fromhex('80000000'))),
            5: dict(id=5, tag=13, name='A', owner='X', member='m', values={'unicode': '\u0130',
                'nested': [inputs.FloatBits(32, bytes.fromhex('3dcccccd')), inputs.EnumReference('\u212a', 'N')]})}
        self.graphs[0]['nodeSlots'] = 6
        self.source = {'path': '/tiny-helper/JvmPrimitiveFacts.java', 'sha256': 'c'*64}
        self.helper = {'path': '/tiny-helper/classes/JvmPrimitiveFacts.class', 'sha256': 'd'*64}
        self.jdk = {'home': '/tiny-jdk', 'files': {n: {'path': '/tiny-jdk/'+n, 'sha256': 'e'*64} for n in f.JDK_FILES}}
        self.expected = {('float32', '3dcccccd'): '0.1', ('double', '3fb999999999999a'): '0.1',
                         ('float32', '80000000'): '-0.0', ('lowercaseRoot', 'org.jetbrains.\u212aotlin.Test'): 'org.jetbrains.kotlin.test',
                         ('lowercaseRoot', '\u0130'): 'i\u0307', ('lowercaseRoot', '\u212a'): 'k'}
        self.path = '/tiny-helper/requests.json'

    def packet(self):
        collector = f.RequestCollector()
        for graph in self.graphs: collector.add_graph(graph)
        return collector.finish(self.source, self.helper, self.jdk)

    def result(self, packet):
        raw = encoded(packet)
        return {'schema': 'graphite.jvm-primitive-facts.v1', 'scope': 'JDK_PRIMITIVE_CONVERSIONS_NOT_GRAPH_ORACLE',
                'request': {'path': self.path, 'sha256': hashlib.sha256(raw).hexdigest()},
                'helperSource': copy.deepcopy(packet['helperSource']), 'helperClass': copy.deepcopy(packet['helperClass']),
                'jdkImage': copy.deepcopy(packet['jdkImage']), 'graphCount': 64, 'operationCount': len(packet['operations']),
                'results': [dict(index=i, **op, output=self.expected[(op['operation'], op['input'])])
                            for i, op in enumerate(packet['operations'])],
                'sourceGraphBytesVerified': False, 'queryImplementationUsed': False}

    def consume(self, packet=None, result=None):
        packet = packet or self.packet(); result = result or self.result(packet)
        return f.PrimitiveFacts(encoded(packet), self.path, encoded(result))

    def test_all64_complete_raw_bindings_and_unique_primitive_operations(self):
        packet = self.packet()
        self.assertEqual(list(model.FIXTURE_GRAPH_IDS), [g['id'] for g in packet['sourceGraphs']])
        self.assertEqual(self.graphs[0]['consumedPins'], packet['sourceGraphs'][0]['consumedPins'])
        self.assertEqual(set(self.expected), {(o['operation'], o['input']) for o in packet['operations']})
        self.assertEqual(6, len(packet['operations']))
        self.assertNotIn('nodes', packet['sourceGraphs'][0])
        facts = self.consume(packet); facts.bind_graph(self.graphs[0])
        self.assertFalse(facts.executionAuthorityVerified)

    def test_actual_float_decimal_not_widened_binary32_and_unicode_expansion(self):
        facts = self.consume(); raw = inputs.FloatBits(32, bytes.fromhex('3dcccccd'))
        self.assertEqual(0.1, d.projected_value(raw, facts))
        self.assertNotEqual(struct.unpack('>f', raw.bits)[0], d.projected_value(raw, facts))
        self.assertEqual('-0x0.0p+0', d.projected_value(inputs.FloatBits(32, bytes.fromhex('80000000')), facts).hex())
        self.assertEqual(0.1, d.projected_value(inputs.FloatBits(64, bytes.fromhex('3fb999999999999a')), facts))
        self.assertEqual('i\u0307', d.lowered('\u0130', facts))
        self.assertEqual('org.jetbrains.kotlin.test', d.lowered('org.jetbrains.\u212aotlin.Test', facts))
        self.assertEqual('ascii', d.lowered('ASCII', facts))

    def test_complete26_injection_uses_same_bound_sources_and_preserves_original_projection(self):
        # Actual 26-predicate derivation from the tiny raw data, not a response
        # body injected as an expected answer. Primitive facts are illustrative.
        self.graphs[0]['nodes'][2] = examples.call(2, 'android.app.Activity', callee=examples.method('org.jetbrains.\u212aotlin.Test'))
        packet = self.packet(); facts = self.consume(packet); collector = d.Collector(primitive_facts=facts)
        for graph in self.graphs:
            links = [(1, 2, 0)] if graph['id'] == model.FIXTURE_GRAPH_IDS[0] else []
            collector.add_graph(graph, examples.edges(graph, links), len(links))
        result = collector.finish(); self.assertEqual(26, len(result['universes']))
        projected = result['universes']['slow-dataflowTargetHit']['rows'][0]['value']
        self.assertEqual(0.1, projected['value']); self.assertEqual((1, 2), (projected['source'], projected['target']))
        wrapped = result['universes']['wrapped-late_graph_prefix_query']['groups'][0]['variants'][0]
        self.assertEqual('org.jetbrains.\u212aotlin.Test', wrapped['callee'])
        self.assertFalse(result['oracleAuthorityVerified']); self.assertFalse(result['fresh64Acceptance'])

    def test_missing_fact_and_changed_raw_graph_never_fall_back_to_approximation(self):
        facts = self.consume()
        with self.assertRaisesRegex(ValueError, 'missing actual primitive fact'):
            d.projected_value(inputs.FloatBits(32, bytes.fromhex('3e4ccccd')), facts)
        with self.assertRaisesRegex(ValueError, 'missing actual primitive fact'): d.lowered('\u03a3', facts)
        graph = copy.deepcopy(self.graphs[0]); graph['consumedPins'][next(iter(graph['consumedPins']))] = 'f'*64
        with self.assertRaisesRegex(ValueError, 'actual graph input binding'): facts.bind_graph(graph)
        with self.assertRaisesRegex(ValueError, 'actual graph input binding'):
            d.Collector(primitive_facts=facts).add_graph(graph, examples.edges(graph), 0)

    def test_omitted_reordered_duplicate_and_changed_input_results_rejected(self):
        packet = self.packet(); original = self.result(packet)
        for mutation in ('omit', 'reorder', 'duplicate', 'index', 'input', 'operation'):
            result = copy.deepcopy(original)
            if mutation == 'omit': result['results'].pop()
            elif mutation == 'reorder': result['results'].reverse()
            elif mutation == 'duplicate': result['results'][1] = result['results'][0]
            elif mutation == 'index': result['results'][0]['index'] = True
            elif mutation == 'input': result['results'][0]['input'] = '3dccccce'
            else: result['results'][0]['operation'] = 'double'
            with self.subTest(mutation=mutation), self.assertRaises(ValueError): self.consume(packet, result)

    def test_request_bytes_jdk_image_helper_and_scope_rejected(self):
        packet = self.packet(); original = self.result(packet)
        for key in ('request', 'helperSource', 'helperClass', 'jdkImage', 'graphCount', 'sourceGraphBytesVerified', 'queryImplementationUsed'):
            result = copy.deepcopy(original)
            if key in ('request', 'helperSource', 'helperClass'): result[key]['sha256'] = 'f'*64
            elif key == 'jdkImage': result[key]['files']['lib/modules']['sha256'] = 'f'*64
            elif key == 'graphCount': result[key] = True
            else: result[key] = True
            with self.subTest(key=key), self.assertRaises(ValueError): self.consume(packet, result)
        with self.assertRaisesRegex(ValueError, 'request bytes'):
            f.PrimitiveFacts(encoded(packet)+b' ', self.path, encoded(original))

    def test_wrong_float_spelling_bits_nonfinite_and_signed_zero_rejected(self):
        packet = self.packet(); original = self.result(packet)
        index = next(i for i, o in enumerate(packet['operations']) if o['operation'] == 'float32' and o['input'] == '3dcccccd')
        for text in ('0.2', 'NaN', 'Infinity', '0', '0.1f', '1e-1', '0.1E+0'):
            result = copy.deepcopy(original); result['results'][index]['output'] = text
            with self.subTest(text=text), self.assertRaises(ValueError): self.consume(packet, result)
        index = next(i for i, o in enumerate(packet['operations']) if o['input'] == '80000000')
        result = copy.deepcopy(original); result['results'][index]['output'] = '0.0'
        with self.assertRaisesRegex(ValueError, 'exact original bits'): self.consume(packet, result)

    def test_nonfinite_facts_preserved_but_cannot_be_a_successful_gson_projection(self):
        for kind, bits, text in [('float32', '7f800000', 'Infinity'), ('float32', 'ffc01234', 'NaN'),
                                 ('double', 'fff0000000000000', '-Infinity')]:
            packet = self.packet(); packet['operations'].append({'operation': kind, 'input': bits})
            self.expected[(kind, bits)] = text; facts = self.consume(packet)
            self.assertEqual(text, facts.text(kind, bits))
            with self.assertRaisesRegex(ValueError, 'Gson rejects'):
                d.projected_value(inputs.FloatBits(32 if kind == 'float32' else 64, bytes.fromhex(bits)), facts)

    def test_closed_requests_no_queries_or_synthetic_authority_fields(self):
        original = self.packet()
        for mutation in ('scope', 'extra', 'operation', 'duplicate', 'graph', 'jdk', 'revision'):
            packet = copy.deepcopy(original)
            if mutation == 'scope': packet['scope'] = 'HTTP_QUERY_RESULTS'
            elif mutation == 'extra': packet['query'] = 'MATCH (n) RETURN n'
            elif mutation == 'operation': packet['operations'][0]['operation'] = 'evaluateQuery'
            elif mutation == 'duplicate': packet['operations'].append(packet['operations'][0])
            elif mutation == 'graph': packet['sourceGraphs'].pop()
            elif mutation == 'jdk': packet['jdkImage']['files'].pop('lib/modules')
            else: packet['sourceGraphs'][0]['revision'] = 'c'*40
            with self.subTest(mutation=mutation), self.assertRaises(ValueError): f.validate_request(packet)

    def test_partial_collection_duplicate_graph_and_unknown_raw_values_fail_closed(self):
        collector = f.RequestCollector(); collector.add_graph(self.graphs[0])
        with self.assertRaisesRegex(ValueError, 'complete successful'): collector.finish(self.source, self.helper, self.jdk)
        with self.assertRaisesRegex(ValueError, 'unique primitive'): collector.add_graph(self.graphs[0])
        with self.assertRaisesRegex(ValueError, 'already failed'): collector.add_graph(self.graphs[1])
        graph = copy.deepcopy(self.graphs[0]); graph['nodes'][1]['value'] = object()
        with self.assertRaisesRegex(ValueError, 'known primitive raw'): f.RequestCollector().add_graph(graph)

    def test_input_mutation_after_collection_and_result_parsing_does_not_change_facts(self):
        collector = f.RequestCollector()
        for graph in self.graphs: collector.add_graph(graph)
        self.graphs[0]['consumedPins'].clear()
        packet = collector.finish(self.source, self.helper, self.jdk)
        self.assertTrue(packet['sourceGraphs'][0]['consumedPins'])
        result = self.result(packet); facts = self.consume(packet, result)
        packet['operations'].clear(); result['results'].clear()
        self.assertEqual('0.1', facts.text('float32', '3dcccccd'))

    def test_roundtrip_check_does_not_claim_actual_jdk_execution_authority(self):
        packet = self.packet(); result = self.result(packet)
        item = next(r for r in result['results'] if r['operation'] == 'float32' and r['input'] == '3dcccccd')
        item['output'] = '0.10000000149011612'  # roundtrips, but is not JDK Float.toString output
        facts = self.consume(packet, result)
        self.assertFalse(facts.executionAuthorityVerified)
        # Only the later real owned phase/JDK/source audit can establish the
        # exact algorithm, rather than merely decimal representability.
        self.assertNotEqual(0.1, facts.number(inputs.FloatBits(32, bytes.fromhex('3dcccccd'))))

    def test_duplicate_json_keys_and_unknown_result_fields_rejected(self):
        packet = self.packet(); result = self.result(packet)
        raw = encoded(result); malformed = raw[:-1]+b',"graphCount":64}'
        with self.assertRaisesRegex(ValueError, 'duplicate response JSON key'):
            f.PrimitiveFacts(encoded(packet), self.path, malformed)
        result['performanceAcceptance'] = True
        with self.assertRaisesRegex(ValueError, 'result schema'): self.consume(packet, result)


if __name__ == '__main__': unittest.main()
