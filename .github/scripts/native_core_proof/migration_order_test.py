"""Pure tiny-wire correctness checks; no children or real graph files."""
import struct
import unittest
from .core_semantics import compare_exact_nodes, nodes, validate_node_index
from . import core_semantics_test as canonical_tests
from .legacy_wire import Invalid
I=lambda value:struct.pack('>i',value)
Q=lambda value:struct.pack('>q',value)


def fixture(rows):
    data=I(0x47524e03)+I(len(rows)); positions={}; groups={tag:[] for tag in range(16)}
    for node,tag,payload in rows:
        positions[node]=len(data);groups[tag].append(node)
        data+=I(node)+bytes([tag])+payload
    slots=max(positions,default=-1)+1
    offsets=I(0x47524c03)+I(slots)+b''.join(Q(positions[node]+1 if node in positions else 0) for node in range(slots))
    cursor=8+16*13;header=[];body=[]
    for tag,ids in groups.items():
        header.append(bytes([tag])+I(len(ids))+Q(cursor));chunk=b''.join(map(I,ids));body.append(chunk);cursor+=len(chunk)
    typeindex=I(0x47525403)+I(16)+b''.join(header+body)
    index=I(0x47524903)+I(len(rows))+b''.join(I(node)+bytes([tag])+Q(positions[node]) for node,tag,_ in rows)
    return (data,offsets,['text'],typeindex),index,{node:tag for node,tag,_ in rows}


class MigrationOrderTests(unittest.TestCase):
    rows=[(1,0,I(7)),(4,1,I(0)),(2,5,b'\x01')]
    def test_physical_reorder_keeps_complete_id_semantics_and_valid_indexes(self):
        a,ai,at=fixture(self.rows);b,bi,bt=fixture(list(reversed(self.rows)))
        self.assertNotEqual(a[0],b[0]);self.assertNotEqual(ai,bi)
        self.assertEqual(compare_exact_nodes(nodes(*a),nodes(*b)),3)
        self.assertEqual(validate_node_index(ai,a[1],at)['rows'],3)
        self.assertEqual(validate_node_index(bi,b[1],bt)['rows'],3)
    def test_same_tag_reorder_rejected_with_otherwise_valid_indexes(self):
        rows=[(1,0,I(7)),(4,1,I(0)),(2,0,I(9))]
        a,ai,at=fixture(rows);b,bi,bt=fixture(list(reversed(rows)))
        self.assertEqual(validate_node_index(ai,a[1],at)['status'],'PASS')
        self.assertEqual(validate_node_index(bi,b[1],bt)['status'],'PASS')
        with self.assertRaisesRegex(Invalid,'per-tag ordered node IDs differ'):
            compare_exact_nodes(nodes(*a),nodes(*b))
    def test_missing_id_either_side_rejected(self):
        a,_,_=fixture(self.rows);b,_,_=fixture(self.rows[:-1])
        for left,right in ((a,b),(b,a)):
            with self.subTest(leftCount=len(left[0])):
                with self.assertRaises(Invalid):compare_exact_nodes(nodes(*left),nodes(*right))
    def test_duplicate_comparison_ids_both_sides_rejected(self):
        a,_,_=fixture(self.rows);values=list(nodes(*a))
        for left,right in ((values+[values[0]],values),(values,values+[values[0]])):
            with self.assertRaises(Invalid):compare_exact_nodes(left,right)
    def test_duplicate_wire_id_rejected(self):
        a,_,_=fixture(self.rows);broken=bytearray(a[0]);broken[17:21]=I(1)
        with self.assertRaises(Invalid):list(nodes(bytes(broken),*a[1:]))
    def test_tag_and_value_changes_rejected(self):
        a,_,_=fixture(self.rows)
        for changed in ((1,3,I(7)),(1,0,I(8))):
            b,_,_=fixture([changed]+self.rows[1:])
            with self.subTest(changed=changed):
                with self.assertRaises(Invalid):compare_exact_nodes(nodes(*a),nodes(*b))
    def test_receiver_and_argument_reference_changes_rejected(self):
        original=canonical_tests.CallSiteReferenceTests.callsite(1,[2])
        for receiver,args in ((2,[2]),(1,[1])):
            changed=canonical_tests.CallSiteReferenceTests.callsite(receiver,args)
            with self.assertRaises(Invalid):compare_exact_nodes(nodes(*original),nodes(*changed))
    def test_stale_cross_file_offset_rejected(self):
        a,ai,at=fixture(self.rows);b,_,_=fixture(list(reversed(self.rows)))
        with self.assertRaises(Invalid):validate_node_index(ai,b[1],at)
    def test_index_duplicate_missing_unknown_and_tag_rejected(self):
        a,index,tags=fixture(self.rows)
        mutations=[]
        duplicate=bytearray(index);duplicate[21:25]=I(1);mutations.append(bytes(duplicate))
        missing=bytearray(index[:-13]);missing[4:8]=I(2);mutations.append(bytes(missing))
        unknown=bytearray(index);unknown[8:12]=I(3);mutations.append(bytes(unknown))
        wrongtag=bytearray(index);wrongtag[12]=3;mutations.append(bytes(wrongtag))
        for data in mutations:
            with self.subTest(data=data):
                with self.assertRaises(Invalid):validate_node_index(data,a[1],tags)
    def test_index_framing_rejected(self):
        a,index,tags=fixture(self.rows)
        for data in (index+b'\0',index[:-1],I(0)+index[4:]):
            with self.assertRaises(Invalid):validate_node_index(data,a[1],tags)
    def test_complete_node_stream_framing_not_skipped(self):
        a,_,_=fixture(self.rows)
        for data in (a[0]+b'\0',a[0][:-1]):
            with self.assertRaises(Invalid):compare_exact_nodes(nodes(data,*a[1:]),nodes(*a))

if __name__=='__main__':unittest.main()
