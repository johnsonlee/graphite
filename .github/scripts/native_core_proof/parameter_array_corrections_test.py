import struct
import unittest
from types import SimpleNamespace
from .legacy_wire import Method
from .parameter_array_corrections import compare, parameter_slot
from .local_array_corrections_test import text


def parameter(value, index=1, node=81093, tag=10, tail=b'proved-method'):
    return struct.pack('>iBi', node, tag, index) + text(value) + tail


def methods(value='double[][]', old='double[]'):
    a = Method('Owner', 'm', ('int[]', value), 'void')
    b = Method('Owner', 'm', ('int[]', old), 'void')
    return a, b, SimpleNamespace(mapping={a.key:b.key})


class ParameterArrayTests(unittest.TestCase):
    def test_column_bound_to_classfile_proved_exact_parameter(self):
        for value, old in [('double[][]','double[]'), ('pkg.Inner[][][]','pkg.Inner[]')]:
            a,b,authority = methods(value,old)
            rows = []
            self.assertEqual(compare(parameter(value), parameter(old), a,b,authority,rows), parameter(old))
            self.assertEqual(rows[0]['index'], 1)
            self.assertEqual(rows[0]['actualMethod'], list(a.key))
            self.assertEqual(rows[0]['referenceMethod'], list(b.key))
            self.assertEqual(rows[0]['B'], value)
            self.assertEqual(rows[0]['C'], old)

    def test_missing_or_different_method_authority_rejected(self):
        a,b,authority = methods()
        for mapping in ({}, {a.key:('Wrong','m',b.key[2])}):
            authority.mapping = mapping
            with self.assertRaises(ValueError):
                compare(parameter('double[][]'), parameter('double[]'), a,b,authority,[])

    def test_column_cannot_disagree_with_descriptor(self):
        a,b,authority = methods()
        for value,old in [('double[][][]','double[]'), ('int[][]','int[]')]:
            with self.assertRaises(ValueError):
                compare(parameter(value), parameter(old), a,b,authority,[])

    def test_index_bounds_and_wrong_slot_rejected(self):
        a,b,authority = methods()
        for index in (-1,0,2,100):
            with self.assertRaises(ValueError):
                compare(parameter('double[][]',index), parameter('double[]',index), a,b,authority,[])

    def test_identity_index_and_nonparameter_tag_rejected(self):
        a,b,authority = methods()
        for value in (parameter('double[]',node=0), parameter('double[]',index=0),
                      parameter('double[]',tag=8)):
            with self.assertRaises(ValueError):
                compare(parameter('double[][]'), value, a,b,authority,[])

    def test_other_payload_bytes_are_not_normalized(self):
        a,b,authority = methods()
        rows = []
        with self.assertRaises(ValueError):
            compare(parameter('double[][]',tail=b'literal double[][]'),
                    parameter('double[]',tail=b'literal double[]'), a,b,authority,rows)
        self.assertEqual(rows, [])

    def test_canonical_slot_bounds(self):
        data = parameter('double[][]',tail=b'')
        for end in range(len(data)):
            with self.assertRaises(ValueError):
                parameter_slot(data[:end])
        with self.assertRaises(ValueError):
            parameter_slot(struct.pack('>iBii',1,10,1,-1))


if __name__ == '__main__':
    unittest.main()
