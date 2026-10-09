import struct
import unittest
from .local_array_corrections import compare, legacy_array_type, local_slot


def text(value):
    raw = value.encode('utf-8')
    return struct.pack('>i', len(raw)) + raw


def local(value, name='$stack4', node=46732, tag=8, method=b'exact-method'):
    return struct.pack('>iB', node, tag) + text(name) + text(value) + method


class LocalArrayTests(unittest.TestCase):
    def test_primitive_and_reference_dimensions(self):
        for base in ('byte', 'java.lang.String', 'owner.Outer$Inner'):
            for dimensions in (2, 3, 255):
                rows = []
                a, b = local(base+'[]'*dimensions), local(base+'[]')
                self.assertEqual(compare(a, b, ('Owner', 'm', '()V'), rows), b)
                self.assertEqual(rows, [{'nodeId':46732, 'name':'$stack4',
                    'method':['Owner','m','()V'], 'B':base+'[]'*dimensions,
                    'C':base+'[]', 'dimensions':dimensions}])

    def test_unchanged_scalar_and_array_preserve_no_exception(self):
        for value in ('int', 'byte[]', 'byte[][]'):
            rows = []
            self.assertEqual(compare(local(value), local(value), (), rows), local(value))
            self.assertEqual(rows, [])

    def test_base_rank_and_direction_mismatches_fail(self):
        for a,b in [('byte[][]','int[]'), ('byte[][]','byte'), ('byte[]','byte[][]'),
                    ('byte[][][]','byte[][]'), ('int','byte'), ('[]'*2,'[]'),
                    ('byte'+'[]'*256,'byte[]'), ('byte[bad][][]','byte[bad][]')]:
            with self.subTest(a=a,b=b), self.assertRaises(ValueError):
                legacy_array_type(a,b)

    def test_node_identity_name_and_tag_remain_exact(self):
        for changed in [local('byte[]', node=7), local('byte[]', name='renamed'),
                        local('byte[]', tag=7)]:
            with self.assertRaises(ValueError):
                compare(local('byte[][]'), changed, (), [])

    def test_nonlocal_array_strings_are_not_normalized(self):
        for tag in (7,9,10,11,12,13,14,15):
            with self.assertRaises(ValueError):
                compare(local('byte[][]', tag=tag), local('byte[]', tag=tag), (), [])

    def test_method_and_other_suffix_payload_remain_exact(self):
        rows = []
        with self.assertRaises(ValueError):
            compare(local('byte[][]', method=b'Owner.m(byte[][])'),
                    local('byte[]', method=b'Owner.m(byte[])'), (), rows)
        self.assertEqual(rows, [])
        with self.assertRaises(ValueError):
            compare(local('byte[][]') + b'extra', local('byte[]'), (), rows)

    def test_identical_array_literal_in_name_is_preserved(self):
        rows = []
        self.assertEqual(compare(local('byte[][]', name='literal byte[][]'),
            local('byte[]', name='literal byte[][]'), (), rows),
            local('byte[]', name='literal byte[][]'))
        self.assertEqual(rows[0]['name'], 'literal byte[][]')

    def test_canonical_bounds_utf8_and_negative_lengths(self):
        data = local('byte[][]', method=b'')
        for end in range(len(data)):
            with self.assertRaises((ValueError, UnicodeDecodeError)):
                local_slot(data[:end])
        for bad in (struct.pack('>iB',1,8)+struct.pack('>i',-1),
                    struct.pack('>iB',1,8)+struct.pack('>i',1)+b'\xff'):
            with self.assertRaises((ValueError, UnicodeDecodeError)):
                local_slot(bad)


if __name__ == '__main__':
    unittest.main()
