"""Tiny source-value semantics and complete DISTINCT representatives only."""
import copy
import struct
import unittest

import jvm_pressure_distinct as d
import jvm_pressure_inputs as raw
import jvm_pressure_oracles as model


def f(value, width=64): return raw.FloatBits(width, struct.pack('>f' if width == 32 else '>d', value))

def facts(value):
    known = {(32, f(.1,32).bits): '0.1', (64, f(.1).bits): '0.1',
             (32, f(1,32).bits): '1.0', (64, f(1).bits): '1.0',
             (64, f(9007199254740992).bits): '9.007199254740992E15',
             (32, f(-0.,32).bits): '-0.0', (64, f(-0.).bits): '-0.0',
             (32, f(0.,32).bits): '0.0', (64, f(0.).bits): '0.0',
             (64, f(float('inf')).bits): 'Infinity', (64, f(float('nan')).bits): 'NaN'}
    return known[(value.width, value.bits)]


def visible(**changes):
    return {'n.graph_id': None, 'caller':'android.Test', 'callerMethod': 1,
            'callee': None, 'calleeMethod': None, **changes}


def groups(*values):
    result = d.DistinctGroups(facts)
    for row, graph in values: result.add(row, graph)
    return result.finish()


class DistinctTests(unittest.TestCase):
    def test_numeric_keys_use_exact_decimal_facts_not_binary_widening(self):
        self.assertEqual(d.semantic_key(1), d.semantic_key(f(1,32), facts))
        self.assertEqual(d.semantic_key(1), d.semantic_key(f(1), facts))
        self.assertEqual(d.semantic_key(f(.1,32), facts), d.semantic_key(f(.1), facts))
        self.assertNotEqual(d.semantic_key(9007199254740993), d.semantic_key(f(9007199254740992), facts))
        self.assertEqual(['number','123456789012345678901234567890123456789',-38], d.decimal_key('1.2345678901234567890123456789012345678900'))
        self.assertEqual(d.semantic_key(0), d.semantic_key(f(-0.), facts))
        self.assertNotEqual(d.semantic_key(True), d.semantic_key(1))

    def test_missing_or_wrong_facts_and_raw_python_float_are_rejected(self):
        with self.assertRaisesRegex(ValueError,'facts'): d.semantic_key(f(1))
        with self.assertRaisesRegex(ValueError,'roundtrip'): d.semantic_key(f(1), lambda _: '2.0')
        with self.assertRaises(ValueError): d.semantic_key(1.0)
        collector = d.DistinctGroups()
        with self.assertRaises(ValueError): collector.add(visible(callerMethod=f(1)), 'g')
        with self.assertRaisesRegex(ValueError,'previous'): collector.finish()

    def test_enum_list_map_null_semantics_are_pre_gson(self):
        enum = raw.EnumReference('E','N')
        self.assertNotEqual(d.semantic_key(enum), d.semantic_key('E.N'))
        self.assertNotEqual(d.semantic_key(enum), d.semantic_key({'enumClass':'E','enumName':'N'}))
        self.assertEqual(d.semantic_key([{'n':1,'v':None}]), d.semantic_key([{'v':None,'n':f(1)}],facts))
        self.assertNotEqual(d.semantic_key([1,2]), d.semantic_key([2,1]))
        self.assertNotEqual(d.semantic_key({}), d.semantic_key({'x':None}))
        self.assertEqual({'enumClass':'E','enumName':'N'},d.projected_value(enum))

    def test_group_keeps_whole_observed_variants_and_complete_provenance(self):
        result = groups((visible(), 'g2'), (visible(callerMethod=f(1)), 'g1'), (visible(),'g1'))
        self.assertEqual(1,len(result)); self.assertEqual(['g1','g2'],result[0]['graphIds'])
        self.assertEqual(2,len(result[0]['variants']))
        self.assertEqual({int,float},{type(v['callerMethod']) for v in result[0]['variants']})
        self.assertTrue(all('n.graph_id' not in v for v in result[0]['variants']))
        pair = groups((visible(callerMethod=1,calleeMethod=f(1)),'g'),(visible(callerMethod=f(1),calleeMethod=1),'g'))[0]
        self.assertEqual([(int,float),(float,int)],[(type(v['callerMethod']),type(v['calleeMethod'])) for v in pair['variants']])

    def test_signed_zero_variants_and_nonfinite_groups_preserve_total(self):
        result=groups((visible(callerMethod=f(-0.)),'g'),(visible(callerMethod=f(0.)),'g'))
        self.assertEqual(1,len(result)); self.assertEqual(2,len(result[0]['variants']))
        result=groups((visible(callerMethod=f(float('inf'))),'g'))
        self.assertEqual(1,len(result)); self.assertEqual([],result[0]['variants'])
        self.assertEqual(['nonfinite','NaN'],d.semantic_key(f(float('nan')),facts))

    def test_snapshot_and_bound_facts_are_not_mutable_aliases(self):
        calls=[]
        collector=d.DistinctGroups(lambda value: calls.append(value) or facts(value))
        row=visible(callerMethod=f(1));collector.add(row,'g');collector.add(row,'g')
        self.assertEqual(1,len(calls));row['caller']='changed'
        result=collector.finish();result[0]['variants'][0]['caller']='changed'
        self.assertEqual('android.Test',collector.finish()[0]['variants'][0]['caller'])

    def test_projection_uses_float_decimal_and_preserves_large_integer(self):
        self.assertEqual(.1,d.projected_value(f(.1,32),facts))
        self.assertNotEqual(struct.unpack('>f',f(.1,32).bits)[0],d.projected_value(f(.1,32),facts))
        self.assertEqual(9223372036854775807,d.projected_value(9223372036854775807))
        self.assertEqual('-0x0.0p+0',d.projected_value(f(-0.),facts).hex())

    def test_key_grammar_rejects_noncanonical_forms(self):
        for key in (['number','10',0],['number','-0',0],['number','0',1],['number','1',True],['map',[['z',['null']],['a',['null']]]],['enum','E'],['unknown']):
            with self.subTest(key=key),self.assertRaises(ValueError):d.validate_key(key)


if __name__ == '__main__': unittest.main()
