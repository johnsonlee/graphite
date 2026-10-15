"""Small JVM response-model tests; no server, child process or real graph reads."""
import copy
import hashlib
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import jvm_pressure_oracles as oracle


class TypedKeyInverseTests(unittest.TestCase):
    def test_exact_roundtrip_types_nested_order_large_int_and_signed_zero(self):
        values=[None,True,False,1,9007199254740993,1.0,0.0,-0.0,
                float.fromhex('0x0.0000000000001p-1022'),float.fromhex('0x1.fffffffffffffp+1023'),
                {'z':None,'a':[True,1,1.0,-0.0,{'z':'é','a':['x',None]}]}]
        for value in values:
            with self.subTest(value=value):
                encoded=oracle.key(value);decoded=oracle.decode_key(encoded)
                self.assertEqual(encoded,oracle.key(decoded));self.assertIs(type(value),type(decoded))
        self.assertEqual('-0x0.0p+0',oracle.decode_key(oracle.key(-0.0)).hex())
        self.assertEqual(3,len({oracle.key(v) for v in (True,1,1.0)}))

    def test_invalid_or_noncanonical_encoded_keys_fail_closed(self):
        invalid=['["int",true]','["bool",1]','["str",2]','["null",0]','["array",{}]',
                 '["float","nan"]','["float","inf"]','["float","-0.0"]','["float",1.0]',
                 '["object",[["b",["null"]],["a",["null"]]]]',
                 '["object",[["a",["null"]],["a",["null"]]]]',
                 '["other",0]','["array",[NaN]]','[ "int",1 ]','["int",1.0]','[']
        for encoded in invalid:
            with self.subTest(encoded=encoded),self.assertRaises(ValueError):oracle.decode_key(encoded)
        with self.assertRaises(ValueError):oracle.decode_key(b'["int",1]')

    def test_object_insertion_order_changes_explicitly_but_arrays_and_types_do_not(self):
        original={'z':[2,1], 'a':{'z':1,'a':True}}
        decoded=oracle.decode_key(oracle.key(original))
        self.assertEqual(['a','z'],list(decoded));self.assertEqual([2,1],decoded['z'])
        self.assertNotEqual(json.dumps(original),json.dumps(decoded))
        self.assertEqual(oracle.key(original),oracle.key(decoded))
        decoded['a']['z']=99
        self.assertEqual(1,original['a']['z'])


class StreamingDigestTests(unittest.TestCase):
    def assert_original_digest(self, value):
        encoded = oracle.key(value).encode('utf-8')
        before = copy.deepcopy(value)
        self.assertEqual(hashlib.sha256(encoded).hexdigest(), oracle.digest(value))
        self.assertEqual(oracle.key(before), oracle.key(value))

    def test_exact_original_bytes_types_unicode_escaping_and_float_boundaries(self):
        values = [None, True, False, 0, -1, 9007199254740993, 10**100,
                  0.0, -0.0, 1.0, -1.5,
                  float.fromhex('0x0.0000000000001p-1022'),
                  float.fromhex('0x1.fffffffffffffp+1023'), '',
                  '\\"\b\f\n\r\t\x00\x1f/é中😀\u2028\u2029',
                  [], {}, {'😀': [True, 1, 1.0, -0.0], 'é': {'z': None, 'a': ''}},
                  ('x'*8191 + '\n😀"\\é')*3]
        for value in values:
            with self.subTest(kind=type(value).__name__): self.assert_original_digest(value)
        self.assertEqual(oracle.digest({'z': 1, 'a': 2}), oracle.digest({'a': 2, 'z': 1}))
        self.assertEqual(7, len({oracle.digest(v) for v in [True, 1, 1.0, '1', None, -0.0, 0.0]}))
        self.assertNotEqual(oracle.digest([1, 2]), oracle.digest([2, 1]))

    def test_all35_universe_shapes_and_nonempty_v1_v2_match_original(self):
        plain = JvmPressureOracleTests(); plain.setUp()
        distinct = JvmDistinctV2Tests(); distinct.setUp()
        for case in oracle.cases():
            entries = [(oracle.projected_row({'value': {'graphId': gid, 'id': 7}}, [gid]), 1)
                       for gid in case['targetGraphIds']] if case['family'] == 'full-projection' else []
            universe = (distinct.universe([], case) if case['family'] == 'wrapped-discovery'
                        else plain.universe(entries, case))
            with self.subTest(case=case['id']):
                oracle.CompiledLegalLimitOracle(universe, case)
                self.assert_original_digest(universe)
        self.assert_original_digest(plain.universe([(plain.first, 3), (plain.second, 1)]))
        groups = distinct.f.groups((distinct.f.visible(), distinct.g),
                                   (distinct.f.visible(callerMethod=distinct.f.f(1)), distinct.h))
        self.assert_original_digest(distinct.universe(groups))

    def test_bounded_updates_are_exact_original_stream_without_typed_or_key(self):
        value = {'nested': ['é\\\n😀'*20000, {'a': [True, 1, 1.0, None]}]}
        expected = oracle.key(value).encode('utf-8')
        updates = []
        real_sha256 = hashlib.sha256
        class ObservedHash:
            def __init__(self): self.actual = real_sha256()
            def update(self, raw):
                updates.append(bytes(raw)); self.actual.update(raw)
            def hexdigest(self): return self.actual.hexdigest()
        with patch.object(oracle.hashlib, 'sha256', side_effect=ObservedHash), \
                patch.object(oracle, 'typed', side_effect=AssertionError('whole typed tree')), \
                patch.object(oracle, 'key', side_effect=AssertionError('whole encoded key')):
            actual = oracle.digest(value)
        self.assertEqual(expected, b''.join(updates))
        self.assertEqual(real_sha256(expected).hexdigest(), actual)
        self.assertGreater(len(updates), 1)
        self.assertTrue(all(0 < len(chunk) <= 65536 for chunk in updates))

    def test_invalid_types_nonfinite_and_surrogates_fail_like_original(self):
        class Integer(int): pass
        class Text(str): pass
        invalid = [float('nan'), float('inf'), -float('inf'), (), set(), b'x',
                   Integer(1), Text('x'), {1: 'x'}, {Text('x'): 1}, object()]
        for value in invalid:
            with self.subTest(kind=type(value).__name__):
                with self.assertRaises(ValueError): oracle.key(value).encode('utf-8')
                with self.assertRaises(ValueError): oracle.digest(value)
        for text in ('\ud800', '\udfff', '\ud83d\ude00', 'x'*8191+'\ud800'):
            for value in (text, {text: 1}, [text]):
                with self.assertRaises(UnicodeEncodeError): oracle.key(value).encode('utf-8')
                with self.assertRaises(UnicodeEncodeError): oracle.digest(value)

    def test_cycles_and_excessive_recursion_fail_without_returning_partial_digest(self):
        array = []; array.append(array)
        mapping = {}; mapping['self'] = mapping
        deep = None
        for _ in range(2000): deep = [deep]
        for value in (array, mapping, deep):
            with self.assertRaises(RecursionError): oracle.key(value).encode('utf-8')
            with self.assertRaises(RecursionError): oracle.digest(value)
        supported = None
        for _ in range(40): supported = {'child': [supported]}
        self.assert_original_digest(supported)


class JvmPressureOracleTests(unittest.TestCase):
    def setUp(self):
        self.cases = {c['id']: c for c in oracle.cases()}
        self.case = self.cases['slow-dynamicHit']
        self.graph = self.case['requestedGraphIds'][0]
        self.first = oracle.projected_row({'id': 17, 'labels': ['ResourceValueNode', 'ResourceValue', 'Resource'],
            'value': ['android.permission.INTERNET', None], 'caller': None, 'graphId': self.graph}, [self.graph])
        self.second = oracle.projected_row({'id': 18, 'labels': ['StringConstant', 'Constant'],
            'value': 'android.permission.INTERNET', 'graphId': self.graph}, [self.graph])

    def universe(self, entries, case=None):
        case = case or self.case
        return {'schema': 'graphite.jvm-legal-row-universe.v1', 'policy': oracle.POLICY,
                'caseId': case['id'], **{k: copy.deepcopy(case[k]) for k in (
                    'requestSha256', 'querySha256', 'registeredGraphIds', 'requestedGraphIds',
                    'targetGraphIds', 'columns', 'effectiveLimit')},
                'allGraphScansComplete': True, 'exactEncounterOrderClaim': False,
                'totalMatches': sum(count for _, count in entries),
                'rows': [{'value': copy.deepcopy(row), 'multiplicity': count} for row, count in entries]}

    def response(self, rows, case=None):
        case = case or self.case
        return {'mode': 'cross-graph', 'graphs': list(case['requestedGraphIds']),
                'graphCount': len(case['requestedGraphIds']), 'columns': list(case['columns']),
                'rows': copy.deepcopy(rows), 'rowCount': len(rows), 'limit': case['effectiveLimit']}

    def test_all35_definitions_preserve_original_queries_and_eight_families(self):
        catalog = json.loads(Path(__file__).with_name('fixtures').joinpath('multigraph-pressure-cases.json').read_text())
        queries = {c['id']: c['request']['body']['query'] for engine in catalog['engines'].values() for c in engine['cases']}
        self.assertEqual(35, len(self.cases))
        self.assertEqual(set(oracle.FAMILIES), {c['family'] for c in self.cases.values()})
        for case in self.cases.values():
            with self.subTest(case=case['id']):
                if case['family'] not in ('full-projection', 'global-wide'):
                    self.assertEqual(queries[case['id']], case['request']['body']['query'])
                self.assertNotIn('ORDER BY', case['request']['body']['query'])
                self.assertEqual('/api/cypher/graphs', case['request']['endpoint'])
                self.assertEqual(case['registeredGraphIds'], case['requestedGraphIds'])
                self.assertEqual(case['requestedGraphIds'], case['request']['body']['graphs'])
                self.assertEqual(64, len(case['requestedGraphIds']))
                self.assertEqual(2 if case['family'] in ('graph-routing', 'full-projection') else 64, len(case['targetGraphIds']))
                self.assertEqual(hashlib.sha256(case['request']['body']['query'].encode()).hexdigest(), case['querySha256'])
                self.assertEqual(case, oracle.validate_case(case))
        self.assertEqual(12, sum(c['family'] == 'full-slow-shape-catalog' for c in self.cases.values()))

    def test_raw_global_wide_query_and_non_distinct_complete_scalar_rows(self):
        case = list(self.cases.values())[-1]
        self.assertEqual('global-wide-four-properties-zero', case['id'])
        needle = '__graphite_fixture64_absent_fixture-android-00__'
        expected_query = (
            'MATCH (n)\n'
            f"WHERE n.caller_class CONTAINS '{needle}' OR n.caller_name CONTAINS '{needle}' OR "
            f"n.callee_class CONTAINS '{needle}' OR n.callee_name CONTAINS '{needle}'\n"
            'RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name\nLIMIT 200')
        self.assertEqual(expected_query, case['request']['body']['query'])
        columns = ['n.caller_class', 'n.caller_name', 'n.callee_class', 'n.callee_name']
        self.assertEqual(columns, case['columns'])
        self.assertEqual(200, case['effectiveLimit'])
        self.assertEqual(list(oracle.FIXTURE_GRAPH_IDS), case['targetGraphIds'])
        row = oracle.projected_row(dict(zip(columns, [needle, 'm', 'Callee', 'f'])), [self.graph])
        compiled = oracle.CompiledLegalLimitOracle(self.universe([(row, 2)], case), case)
        self.assertEqual(2, compiled.validate(self.response([row, row], case))['rows'])
        with self.assertRaises(ValueError): compiled.validate(self.response([row], case))
        bad = copy.deepcopy(row); bad['n.callee_name'] = 'tampered'
        with self.assertRaises(ValueError): compiled.validate(self.response([row, bad], case))
        empty = oracle.CompiledLegalLimitOracle(self.universe([], case), case)
        self.assertEqual(0, empty.validate(self.response([], case))['rows'])
        with self.assertRaises(ValueError): empty.validate(self.response([row], case))

    def test_full_projection_requires_all_rows_from_two_graphs_and_rejects_nested_tamper(self):
        case = self.cases['return-full']
        entries = [(oracle.projected_row({'value': {'id': 7, 'type': 'ReturnNode', 'graphId': gid,
                    'type_info': {'kind': 'class', 'name': 'List', 'arguments': [
                        {'kind': 'variable', 'name': 'T', 'scope': 'Owner', 'arguments': []}]}}}, [gid]), 1)
                   for gid in case['targetGraphIds']]
        universe = self.universe(entries, case)
        compiled = oracle.CompiledLegalLimitOracle(universe, case)
        rows = [r for r, _ in entries]
        self.assertEqual(2, compiled.validate(self.response(rows[::-1], case))['rows'])
        for mutate in ('type', 'nested', 'provenance', 'duplicate', 'missing'):
            bad = copy.deepcopy(rows)
            if mutate == 'type': bad[0]['value'].pop('type')
            elif mutate == 'nested': bad[0]['value']['type_info']['arguments'][0]['scope'] = 'wrong'
            elif mutate == 'provenance': bad[0]['$metadata']['graphIds'] = [case['targetGraphIds'][1]]
            elif mutate == 'duplicate': bad[1] = bad[0]
            else: bad.pop()
            with self.subTest(mutate=mutate), self.assertRaises(ValueError): compiled.validate(self.response(bad, case))
        with self.assertRaisesRegex(ValueError, 'both target'):
            oracle.CompiledLegalLimitOracle(self.universe(entries[:1], case), case)
        with self.assertRaisesRegex(ValueError, 'within limit'):
            oracle.CompiledLegalLimitOracle(self.universe([(entries[0][0], 5000), entries[1]], case), case)
        with self.assertRaisesRegex(ValueError, 'within limit'):
            oracle.CompiledLegalLimitOracle(self.universe([], case), case)

    def test_incomplete_singleton_duplicate_or_foreign_graphs_rejected(self):
        for ids in ([self.graph], list(oracle.FIXTURE_GRAPH_IDS)[:-1],
                    [self.graph]*64, list(oracle.FIXTURE_GRAPH_IDS)[:-1]+['foreign']):
            with self.subTest(ids=ids[:2]), self.assertRaises(ValueError):
                oracle.cases(ids)

    def test_request_and_routing_scope_cannot_be_rewritten(self):
        original = self.cases['routing-pair-dense']
        for edit in ('targets', 'body', 'query', 'limit', 'family'):
            case = copy.deepcopy(original)
            if edit == 'targets': case['targetGraphIds'] = list(case['requestedGraphIds'])
            if edit == 'body': case['request']['body']['graphs'] = list(case['targetGraphIds'])
            if edit == 'query': case['request']['body']['query'] += ' ORDER BY n.caller_class'
            if edit == 'limit': case['effectiveLimit'] = 50
            if edit == 'family': case['family'] = 'wrapped-discovery'
            with self.subTest(edit=edit), self.assertRaises(ValueError): oracle.validate_case(case)

    def test_legal_limit_is_complete_typed_submultiset_and_not_fixed_order(self):
        compiled = oracle.CompiledLegalLimitOracle(self.universe([(self.first, 30), (self.second, 25)]), self.case)
        value = self.response([self.second]*25 + [self.first]*25)
        result = compiled.validate(value)
        self.assertEqual(50, result['rows'])
        self.assertEqual(55, result['completeMatches'])
        self.assertFalse(result['exactEncounterOrderClaim'])
        self.assertFalse(result['oracleAuthorityVerified'])
        self.assertFalse(result['fresh64Acceptance'])
        self.assertFalse(result['performanceAcceptance'])
        value['rows'].reverse()
        self.assertEqual(result, compiled.validate(value))

    def test_duplicate_overconsumption_and_nonmember_rows_rejected(self):
        compiled = oracle.CompiledLegalLimitOracle(self.universe([(self.first, 30), (self.second, 25)]), self.case)
        with self.assertRaisesRegex(ValueError, 'multiplicity'): compiled.validate(self.response([self.first]*31 + [self.second]*19))
        bad = copy.deepcopy(self.second); bad['id'] = 99
        with self.assertRaisesRegex(ValueError, 'multiplicity'): compiled.validate(self.response([self.first]*30 + [self.second]*19 + [bad]))

    def test_under_limit_requires_every_match_and_duplicate(self):
        compiled = oracle.CompiledLegalLimitOracle(self.universe([(self.first, 2), (self.second, 1)]), self.case)
        self.assertEqual(3, compiled.validate(self.response([self.second, self.first, self.first]))['rows'])
        with self.assertRaisesRegex(ValueError, 'cardinality'): compiled.validate(self.response([self.first, self.second]))
        with self.assertRaisesRegex(ValueError, 'multiplicity'): compiled.validate(self.response([self.second]*3))

    def test_all_four_dataflow_requests_keep_full_typed_endpoint_and_edge_results(self):
        for name in ('dataflowSourceHit', 'dataflowSourceMiss', 'dataflowTargetHit', 'dataflowTargetMiss'):
            case = self.cases['slow-' + name]
            with self.subTest(case=name):
                self.assertIn('(c)-[r:DATAFLOW]->(n)', case['request']['body']['query'])
                self.assertEqual(['source', 'target', 'relationship', 'value', 'caller', 'graphId'], case['columns'])
                row = oracle.projected_row({'source': 12, 'target': 29, 'relationship': 'DATAFLOW',
                    'value': 'android.permission.INTERNET', 'caller': 'android.app.Activity',
                    'graphId': self.graph}, [self.graph])
                entries = [] if name.endswith('Miss') else [(row, 2)]
                compiled = oracle.CompiledLegalLimitOracle(self.universe(entries, case), case)
                rows = [] if not entries else [row, row]
                self.assertEqual(len(rows), compiled.validate(self.response(rows, case))['rows'])
                if entries:
                    for field, value in (('source', 12.0), ('target', 30), ('relationship', 'CALL'),
                                         ('value', 'android.permission.OTHER'), ('caller', 'android.app.Other')):
                        bad = copy.deepcopy(rows); bad[0][field] = value
                        with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'multiplicity'):
                            compiled.validate(self.response(bad, case))

    def test_jvm_labels_are_complete_concrete_values_not_match_aliases(self):
        row = oracle.projected_row({'id': 29, 'labels': ['CallSiteNode'], 'caller': 'android.app.Activity',
                                   'graphId': self.graph}, [self.graph])
        case = self.cases['slow-wrappedCallerHit']
        compiled = oracle.CompiledLegalLimitOracle(self.universe([(row, 1)], case), case)
        self.assertEqual(1, compiled.validate(self.response([row], case))['rows'])
        alias = copy.deepcopy(row); alias['labels'] = ['CallSite']
        with self.assertRaisesRegex(ValueError, 'multiplicity'):
            compiled.validate(self.response([alias], case))

    def test_empty_oracle_requires_complete_empty_jvm_envelope(self):
        case = self.cases['dynamic-miss']
        compiled = oracle.CompiledLegalLimitOracle(self.universe([], case), case)
        value = self.response([], case)
        self.assertEqual(0, compiled.validate(value)['rows'])
        value['rows'].append(self.first); value['rowCount'] = 1
        with self.assertRaisesRegex(ValueError, 'cardinality'): compiled.validate(value)

    def test_missing_extra_native_total_and_wrong_envelope_values_rejected(self):
        compiled = oracle.CompiledLegalLimitOracle(self.universe([(self.first, 1)]), self.case)
        original = self.response([self.first])
        changes = {'extra': {'total': {'value': 1, 'relation': 'eq'}}, 'rowCount': {'rowCount': True},
                   'graphCount': {'graphCount': 2}, 'limit': {'limit': 200}, 'mode': {'mode': 'fanout'},
                   'columns': {'columns': list(reversed(original['columns']))},
                   'graphs': {'graphs': list(reversed(original['graphs']))}}
        for name, change in changes.items():
            value = copy.deepcopy(original); value.update(change)
            with self.subTest(change=name), self.assertRaises(ValueError): compiled.validate(value)
        for field in oracle.ENVELOPE:
            value = copy.deepcopy(original); del value[field]
            with self.subTest(missing=field), self.assertRaises(ValueError): compiled.validate(value)

    def test_routing_envelope64_and_row_provenance2_are_separate(self):
        case = self.cases['routing-pair-dense']; target = case['targetGraphIds'][0]
        row = oracle.projected_row(dict(zip(case['columns'], ['A', 'call', 'B', 'target'])), [target])
        compiled = oracle.CompiledLegalLimitOracle(self.universe([(row, 1)], case), case)
        self.assertEqual(1, compiled.validate(self.response([row], case))['rows'])
        value = self.response([row], case); value['graphs'] = list(case['targetGraphIds']); value['graphCount'] = 2
        with self.assertRaisesRegex(ValueError, 'requested graphs'): compiled.validate(value)
        value = self.response([row], case); value['rows'][0]['$metadata']['graphIds'] = [case['requestedGraphIds'][1]]
        with self.assertRaisesRegex(ValueError, 'predicate-scoped'): compiled.validate(value)

    def test_complete_sorted_provenance_is_part_of_typed_row(self):
        case = self.cases['wrapped-zero_hit_query']
        # A small universe only tests the validator, not this query's matching.
        row = oracle.projected_row({'caller': 'A', 'callerMethod': 'x', 'callee': 'B', 'calleeMethod': 'y'}, list(oracle.FIXTURE_GRAPH_IDS[:2]))
        compiled = oracle.CompiledLegalLimitOracle(self.universe([(row, 1)], case), case)
        for ids in ([self.graph], list(reversed(row['$metadata']['graphIds'])), [self.graph, self.graph]):
            value = self.response([row], case); value['rows'][0]['$metadata']['graphIds'] = ids
            with self.subTest(ids=ids), self.assertRaises(ValueError): compiled.validate(value)

    def test_all_wrapped_distinct_cases_reject_repeated_projection_multiplicity(self):
        row = oracle.projected_row({'caller': 'A', 'callerMethod': 'get', 'callee': 'B', 'calleeMethod': 'get'}, [self.graph])
        for case in self.cases.values():
            if case['family'] != 'wrapped-discovery': continue
            with self.subTest(case=case['id']), self.assertRaisesRegex(ValueError, 'DISTINCT projected row multiplicity'):
                oracle.CompiledLegalLimitOracle(self.universe([(row, 2)], case), case)

    def test_all_wrapped_distinct_cases_merge_provenance_before_visible_uniqueness(self):
        first = oracle.projected_row({'caller': 'A', 'callerMethod': 'get', 'callee': 'B', 'calleeMethod': 'get'}, [self.graph])
        second = copy.deepcopy(first); second['$metadata']['graphIds'] = [self.case['requestedGraphIds'][1]]
        for case in self.cases.values():
            if case['family'] != 'wrapped-discovery': continue
            with self.subTest(case=case['id']), self.assertRaisesRegex(ValueError, 'DISTINCT visible projection'):
                oracle.CompiledLegalLimitOracle(self.universe([(first, 1), (second, 1)], case), case)
            merged = oracle.projected_row({k: v for k, v in first.items() if k != '$metadata'},
                                         first['$metadata']['graphIds'] + second['$metadata']['graphIds'])
            compiled = oracle.CompiledLegalLimitOracle(self.universe([(merged, 1)], case), case)
            self.assertEqual(1, compiled.validate(self.response([merged], case))['rows'])

    def test_graph_projection_and_provenance_must_agree(self):
        bad = copy.deepcopy(self.first); bad['graphId'] = self.case['targetGraphIds'][1]
        with self.assertRaisesRegex(ValueError, 'graph ID and provenance'):
            oracle.CompiledLegalLimitOracle(self.universe([(bad, 1)]), self.case)

    def test_gson_null_omission_and_nested_array_nulls(self):
        self.assertNotIn('caller', self.first)
        self.assertIsNone(self.first['value'][1])
        self.assertEqual({'nested': {'a': [None, {}]}}, oracle.gson_value({'missing': None, 'nested': {'a': [None, {'missing': None}]}}))
        compiled = oracle.CompiledLegalLimitOracle(self.universe([(self.first, 1)]), self.case)
        changed = copy.deepcopy(self.first); changed['caller'] = None
        with self.assertRaisesRegex(ValueError, 'map nulls'): compiled.validate(self.response([changed]))
        changed = copy.deepcopy(self.first); changed['value'] = ['android.permission.INTERNET']
        with self.assertRaisesRegex(ValueError, 'multiplicity'): compiled.validate(self.response([changed]))

    def test_json_scalar_types_signed_zero_and_nested_order_are_exact(self):
        values = [True, 1, 1.0, '1', None, -0.0, 0.0]
        self.assertEqual(len(values), len({oracle.key(v) for v in values}))
        row = copy.deepcopy(self.first); row['value'] = [1, True, 1.0, -0.0]
        compiled = oracle.CompiledLegalLimitOracle(self.universe([(row, 1)]), self.case)
        for value in ([True, 1, 1.0, -0.0], [1, True, 1.0, 0.0], [1.0, True, 1, -0.0]):
            altered = copy.deepcopy(row); altered['value'] = value
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, 'multiplicity'):
                compiled.validate(self.response([altered]))

    def test_nonfinite_and_duplicate_raw_json_keys_rejected(self):
        for value in (float('nan'), float('inf'), -float('inf')):
            with self.assertRaisesRegex(ValueError, 'nonfinite'): oracle.gson_value(value)
        for raw in ('{"rows":[],"rows":[]}', '{"rows":[{"id":1,"id":2}]}', '{"rows":[NaN]}', '{"rows":[Infinity]}'):
            with self.subTest(raw=raw), self.assertRaises(ValueError): oracle.parse_response(raw)
        self.assertEqual({'a': [None, True, 1, 1.0]}, oracle.parse_response('{"a":[null,true,1,1.0]}'))

    def test_incomplete_misbound_or_ambiguous_universe_rejected(self):
        original = self.universe([(self.first, 1)])
        for field, value in [('allGraphScansComplete', False), ('exactEncounterOrderClaim', True),
                             ('totalMatches', 2), ('totalMatches', True), ('requestSha256', '0'*64),
                             ('targetGraphIds', []), ('caseId', 'slow-valueHit')]:
            bad = copy.deepcopy(original); bad[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError): oracle.CompiledLegalLimitOracle(bad, self.case)
        for count in (0, -1, True, 1.0):
            bad = copy.deepcopy(original); bad['rows'][0]['multiplicity'] = count
            with self.subTest(count=count), self.assertRaises(ValueError): oracle.CompiledLegalLimitOracle(bad, self.case)
        bad = self.universe([(self.first, 1), (self.first, 1)])
        with self.assertRaisesRegex(ValueError, 'unique encoded'): oracle.CompiledLegalLimitOracle(bad, self.case)

    def test_universe_is_precompiled_and_external_mutation_cannot_change_validation(self):
        source = self.universe([(self.first, 1)])
        compiled = oracle.CompiledLegalLimitOracle(source, self.case)
        source['rows'][0]['value']['id'] = 99
        source['rows'].clear()
        with patch.object(oracle, 'validate_case', side_effect=AssertionError('must not recompile')):
            self.assertEqual(1, compiled.validate(self.response([self.first]))['rows'])
        with self.assertRaises(TypeError): compiled.allowed['anything'] = 2


class JvmDistinctV2Tests(unittest.TestCase):
    def setUp(self):
        import jvm_pressure_distinct as distinct
        import test_jvm_pressure_distinct as fixture
        self.d=distinct; self.f=fixture
        self.case=next(c for c in oracle.cases() if c['id']=='wrapped-dense_distributed_method_query')
        self.g,self.h=self.case['targetGraphIds'][:2]

    def universe(self, groups, case=None):
        case=case or self.case
        return {'schema':self.d.SCHEMA,'policy':oracle.POLICY,'equalityPolicy':self.d.EQUALITY,
                'caseId':case['id'],**{k:copy.deepcopy(case[k]) for k in ('requestSha256','querySha256',
                    'registeredGraphIds','requestedGraphIds','targetGraphIds','columns','effectiveLimit')},
                'allGraphScansComplete':True,'exactEncounterOrderClaim':False,'totalMatches':len(groups),'groups':groups}

    def response(self, rows):
        return {'mode':'cross-graph','graphs':self.case['requestedGraphIds'],'graphCount':64,
                'columns':self.case['columns'],'rows':rows,'rowCount':len(rows),'limit':self.case['effectiveLimit']}

    def row(self, value, ids=None): return oracle.projected_row(value,ids or [self.g])

    def old_variant_index(self, groups, case):
        """Original projection/check/key path, retained only as a correctness reference."""
        index = {}
        for group_id, group in enumerate(groups):
            for visible in group['variants']:
                row = oracle.projected_row(visible, group['graphIds'])
                oracle.need(oracle.typed(oracle.gson_value(visible)) == oracle.typed(visible), 'canonical Gson variant')
                oracle.need(type(row) is dict and set(row) <= set(case['columns']) | {'$metadata'}, 'columns')
                oracle.need(oracle.typed(oracle.gson_value(row)) == oracle.typed(row), 'canonical row')
                ids = row['$metadata']['graphIds']
                oracle.need(ids and ids == sorted(set(ids)) and set(ids) <= set(case['targetGraphIds']), 'provenance')
                oracle.typed(row)
                index.setdefault(oracle.key(row), []).append(group_id)
        return {key: tuple(ids) for key, ids in index.items()}

    def test_compiled_variants_keep_original_index_without_projection_copies(self):
        groups = self.f.groups((self.f.visible(callerMethod=1, calleeMethod={}), self.g),
                               (self.f.visible(callerMethod=self.f.f(1), calleeMethod={}), self.h),
                               (self.f.visible(callerMethod=1, calleeMethod={'x': None}), self.g),
                               (self.f.visible(callerMethod=self.f.f(-0.0)), self.g),
                               (self.f.visible(callerMethod=self.f.f(0.0)), self.g))
        frozen = oracle.key(groups)
        for case in oracle.cases():
            if case['family'] != 'wrapped-discovery': continue
            expected = self.old_variant_index(groups, case)
            with self.subTest(case=case['id']), \
                    patch.object(oracle, 'projected_row', side_effect=AssertionError('redundant projection')), \
                    patch.object(oracle, 'gson_value', side_effect=AssertionError('redundant Gson tree')):
                compiled = self.d.CompiledGroups(groups, case)
            self.assertEqual(expected, dict(compiled.index))
            self.assertEqual(len(groups), compiled.total)
            self.assertEqual(frozen, oracle.key(groups))
            for group in groups:
                for variant in group['variants']:
                    compiled.validate([self.row(variant, group['graphIds'])])
        # The index owns encoded strings, not aliases into the source tree.
        groups[0]['variants'][0]['callerMethod'] = 'changed'
        self.assertEqual(expected, dict(compiled.index))

    def test_canonical_variant_rejects_nested_map_null_but_keeps_list_null_and_types(self):
        base = self.f.groups((self.f.visible(), self.g))[0]['variants'][0]
        variants = [dict(base, callerMethod=[None, True, 1, 1.0, -0.0, {'unicode': 'é😀'}]),
                    dict(base, callerMethod={'nested': {'bad': None}}),
                    dict(base, callerMethod={'nested': [float('inf')]}),
                    dict(base, callerMethod=(1,)), dict(base, callerMethod={1: 'bad'})]
        for number, visible in enumerate(variants):
            group = self.f.groups((self.f.visible(), self.g))[0]
            group['variants'] = [visible]
            try: expected = self.old_variant_index([group], self.case)
            except ValueError:
                self.assertNotEqual(0, number)
                with self.subTest(visible=visible), self.assertRaises(ValueError):
                    self.d.CompiledGroups([group], self.case)
            else:
                self.assertEqual(0, number)
                self.assertEqual(expected, dict(self.d.CompiledGroups([group], self.case).index))
        for ids in ([], ['', self.g], [self.g, self.g], [self.h, self.g], ['foreign'], [1], (self.g,)):
            group = self.f.groups((self.f.visible(), self.g))[0]; group['graphIds'] = ids
            with self.subTest(ids=ids), self.assertRaises(ValueError): self.d.CompiledGroups([group], self.case)

    def test_canonical_row_validation_has_no_projection_or_tagged_tree_and_stays_strict(self):
        row = self.row(self.f.visible(callerMethod=[None, True, 1, 1.0, -0.0, {'x': 'é😀'}]))
        before = oracle.key(row)
        with patch.object(oracle, 'gson_value', side_effect=AssertionError('Gson copy')), \
                patch.object(oracle, 'typed', side_effect=AssertionError('tagged copy')):
            oracle.validate_row(row, self.case['columns'], self.case['targetGraphIds'], self.case['family'])
        self.assertEqual(before, oracle.key(row))
        for mutation in ('null', 'surrogate', 'surrogate-key', 'metadata', 'metadata-extra', 'foreign', 'extra-column'):
            bad = copy.deepcopy(row)
            if mutation == 'null': bad['callerMethod'] = {'x': None}
            elif mutation == 'surrogate': bad['callerMethod'] = '\ud800'
            elif mutation == 'surrogate-key': bad['callerMethod'] = {'\udfff': 1}
            elif mutation == 'metadata': bad['$metadata'] = []
            elif mutation == 'metadata-extra': bad['$metadata']['extra'] = 1
            elif mutation == 'foreign': bad['$metadata']['graphIds'] = ['foreign']
            else: bad['extra'] = 1
            with self.subTest(mutation=mutation), self.assertRaises((ValueError, UnicodeEncodeError)):
                oracle.validate_row(bad, self.case['columns'], self.case['targetGraphIds'], self.case['family'])
        cycle = []; cycle.append(cycle)
        deep = None
        for _ in range(2000): deep = [deep]
        for value in (cycle, deep):
            with self.assertRaises(RecursionError): oracle._validate_canonical_gson(value)

    def test_mixed_numeric_representatives_each_allowed_but_group_capacity_is_one(self):
        groups=self.f.groups((self.f.visible(),self.g),(self.f.visible(callerMethod=self.f.f(1)),self.h))
        checker=oracle.CompiledLegalLimitOracle(self.universe(groups),self.case)
        for variant in groups[0]['variants']:
            self.assertEqual(1,checker.validate(self.response([self.row(variant,[self.g,self.h])]))['rows'])
        with self.assertRaises(ValueError):checker.validate(self.response([self.row(groups[0]['variants'][0])]))
        # Add a second group so cardinality passes; two representations of the first must still fail.
        groups+=self.f.groups((self.f.visible(callerMethod=2),self.g))
        checker=oracle.CompiledLegalLimitOracle(self.universe(groups),self.case)
        with self.assertRaisesRegex(ValueError,'capacity'):
            checker.validate(self.response([self.row(v,[self.g,self.h]) for v in groups[0]['variants']]))

    def test_json_collision_requires_augmenting_match_not_greedy_selection(self):
        groups=self.f.groups((self.f.visible(calleeMethod={}),self.g),
            (self.f.visible(callerMethod=self.f.f(1),calleeMethod={}),self.g),
            (self.f.visible(calleeMethod={'x':None}),self.g))
        self.assertEqual(2,len(groups))
        checker=oracle.CompiledLegalLimitOracle(self.universe(groups),self.case)
        # Integer row can fill either group; floating row can only fill the first.
        values=[self.row(v) for v in groups[0]['variants']]
        self.assertEqual(2,checker.validate(self.response(values))['rows'])
        self.assertEqual(2,checker.validate(self.response(list(reversed(values))))['rows'])
        identical=self.row(groups[0]['variants'][0])
        self.assertEqual(2,checker.validate(self.response([identical,identical]))['rows'])
        only_float=self.row(groups[0]['variants'][1])
        with self.assertRaisesRegex(ValueError,'capacity'):checker.validate(self.response([only_float,only_float]))

    def test_enum_and_map_json_collision_keep_two_distinct_groups(self):
        import jvm_pressure_inputs as raw
        groups=self.f.groups((self.f.visible(callerMethod=raw.EnumReference('E','N')),self.g),
            (self.f.visible(callerMethod={'enumClass':'E','enumName':'N'}),self.g))
        self.assertEqual(2,len(groups));self.assertEqual(groups[0]['variants'],groups[1]['variants'])
        checker=oracle.CompiledLegalLimitOracle(self.universe(groups),self.case)
        same=self.row(groups[0]['variants'][0])
        self.assertEqual(2,checker.validate(self.response([same,same]))['rows'])

    def test_whole_row_variants_cannot_be_recombined(self):
        groups=self.f.groups((self.f.visible(callerMethod=1,calleeMethod=self.f.f(1)),self.g),
                            (self.f.visible(callerMethod=self.f.f(1),calleeMethod=1),self.g))
        checker=oracle.CompiledLegalLimitOracle(self.universe(groups),self.case)
        invented=self.f.visible(callerMethod=1,calleeMethod=1)
        with self.assertRaisesRegex(ValueError,'capacity'):checker.validate(self.response([self.row(invented)]))

    def test_v2_only_eight_wrapped_and_old_v1_shape_remains_strict(self):
        for case in oracle.cases():
            source=self.universe([],case)
            if case['family']=='wrapped-discovery':oracle.CompiledLegalLimitOracle(source,case)
            else:
                with self.assertRaisesRegex(ValueError,'eight wrapped'):oracle.CompiledLegalLimitOracle(source,case)
        source=self.universe([]);source['schema']='graphite.jvm-legal-row-universe.v1'
        with self.assertRaisesRegex(ValueError,'shape'):oracle.CompiledLegalLimitOracle(source,self.case)

    def test_groups_provenance_shape_counts_and_key_canonicality_are_strict(self):
        original=self.universe(self.f.groups((self.f.visible(),self.g)))
        for name in ('duplicate','foreign','missing-column','variant-null','extra','count','policy','empty'):
            bad=copy.deepcopy(original)
            if name=='duplicate':bad['groups']*=2;bad['totalMatches']=2
            if name=='foreign':bad['groups'][0]['graphIds']=['foreign']
            if name=='missing-column':bad['groups'][0]['semanticKey'][1].pop()
            if name=='variant-null':bad['groups'][0]['variants'][0]['caller']=None
            if name=='extra':bad['groups'][0]['variants'][0]['unexpected']='x'
            if name=='count':bad['totalMatches']=2
            if name=='policy':bad['equalityPolicy']='plain'
            if name=='empty':bad['groups'][0]['variants']=[]
            with self.subTest(name=name),self.assertRaises(ValueError):oracle.CompiledLegalLimitOracle(bad,self.case)

    def test_precompiled_snapshot_and_limit_keep_all_groups(self):
        groups=self.f.groups(*[(self.f.visible(callerMethod=i),self.g) for i in range(60)])
        source=self.universe(groups);checker=oracle.CompiledLegalLimitOracle(source,self.case)
        selected=[self.row(g['variants'][0]) for g in groups[10:]][::-1]
        source['groups'].clear()
        self.assertEqual(50,checker.validate(self.response(selected))['rows'])
        with self.assertRaises(TypeError):checker.distinct.index['x']=(0,)

    def test_unserializable_group_is_not_silently_dropped(self):
        groups=self.f.groups((self.f.visible(callerMethod=self.f.f(float('inf'))),self.g))
        checker=oracle.CompiledLegalLimitOracle(self.universe(groups),self.case)
        self.assertEqual(1,checker.total)
        with self.assertRaisesRegex(ValueError,'cardinality'):checker.validate(self.response([]))


if __name__ == '__main__':
    unittest.main()
