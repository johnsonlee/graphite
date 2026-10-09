"""No-child, no-real-fixture tests for new canonical-reader decisions."""
import struct, unittest
from .core_semantics import CanonicalReader, metadata, comparisons, overview
from .legacy_wire import Invalid
I=lambda x:struct.pack('>i',x)
class CanonicalTests(unittest.TestCase):
    def test_sid_is_value_not_integer(self):
        a=CanonicalReader(I(0)+I(17),['same']);b=CanonicalReader(I(1)+I(17),['unused','same'])
        self.assertEqual(a.sid(),b.sid());self.assertEqual(a.i(),b.i());self.assertEqual(a.canonical(),b.canonical())
    def test_every_other_byte_retained(self):
        a=CanonicalReader(bytes.fromhex('7fc00001'));b=CanonicalReader(bytes.fromhex('7fc00002'))
        a.take(4);b.take(4);self.assertNotEqual(a.canonical(),b.canonical())
    def test_bad_sid_and_surrogate_rejected(self):
        with self.assertRaises(Invalid):CanonicalReader(I(1),['x']).sid()
        with self.assertRaises(UnicodeError):CanonicalReader(I(0),['\ud800']).sid()
    def test_metadata_preserves_two_return_descriptors(self):
        # Same erased signature, distinct return: both rows must remain.
        strings=['Owner','m','Object','String']
        row=lambda result:I(0)+I(1)+I(0)+I(result)
        data=I(0x47524d03)+I(2)+row(2)+row(3)+I(0)*7
        result=metadata(data,strings,{})
        self.assertIn(b'Object',result);self.assertIn(b'String',result)
        with self.assertRaises(Invalid):metadata(I(0x47524d03)+I(2)+row(2)+row(2)+I(0)*7,strings,{})
    def test_comparison_exact_reference_remap(self):
        a=I(0x47524303)+I(1)+I(4)+I(6)+I(2)+I(8)
        b=I(0x47524303)+I(1)+I(5)+I(6)+I(2)+I(9)
        self.assertEqual(comparisons(a,{4:5,8:9}),comparisons(b,{}))
        self.assertNotEqual(comparisons(a,{}),comparisons(b,{}))
    def test_overview_rejects_trailing(self):
        data=I(0x47524f03)+I(7)+I(1)+I(0)+I(2)+I(0)
        self.assertIn(b'Owner',overview(data,['Owner']))
        with self.assertRaises(Invalid):overview(data+b'\x00',['Owner'])

class CallSiteReferenceTests(unittest.TestCase):
    @staticmethod
    def callsite(receiver,arguments,line=37,node_id=3):
        from .core_semantics import nodes
        strings=['Owner','callee','caller','void']
        method=lambda name:I(0)+I(name)+I(0)+I(3)
        row=I(node_id)+bytes([12])+method(2)+method(1)+I(line)+I(receiver)+I(len(arguments))+b''.join(map(I,arguments))
        data=I(0x47524e03)+I(1)+row
        offsets=I(0x47524c03)+I(node_id+1)+b''.join(struct.pack('>q',9 if i==node_id else 0) for i in range(node_id+1))
        start=8+16*13;position=start;parts=[]
        for tag in range(16):
            count=1 if tag==12 else 0
            parts.append(bytes([tag])+I(count)+struct.pack('>q',position));position+=4*count
        index=I(0x47525403)+I(16)+b''.join(parts)+I(node_id)
        return data,offsets,strings,index
    def canonical(self,receiver,args,remap=None,**kw):
        from .core_semantics import nodes
        return list(nodes(*self.callsite(receiver,args,**kw),remap=remap))[0][3]
    def test_unchanged_receiver_under_field_swap_rejected(self):
        self.assertNotEqual(self.canonical(1,[],{1:2,2:1}),self.canonical(1,[]))
        self.assertEqual(self.canonical(1,[],{1:2,2:1}),self.canonical(2,[]))
    def test_unchanged_argument_under_field_swap_rejected(self):
        self.assertNotEqual(self.canonical(-1,[1],{1:2,2:1}),self.canonical(-1,[1]))
        self.assertEqual(self.canonical(-1,[1,2,3],{1:2,2:1}),self.canonical(-1,[2,1,3]))
    def test_sentinel_line_and_nonfield_id_remain_exact(self):
        self.assertEqual(self.canonical(-1,[],{1:2,2:1}),self.canonical(-1,[]))
        self.assertNotEqual(self.canonical(-1,[],{1:2,2:1},line=1),self.canonical(-1,[],line=2))
        self.assertNotEqual(self.canonical(-1,[],{1:2,2:1},node_id=3),self.canonical(-1,[],node_id=4))
    def test_mapping_must_be_closed_fields_not_sentinel_or_nonfield(self):
        from .core_semantics import validate_field_remap
        validate_field_remap({1:2,2:1},{1,2},{1,2})
        for mapping in ({-1:1,1:-1},{1:2},{1:3,3:1}):
            with self.assertRaises(Invalid):validate_field_remap(mapping,{1,2},{1,2})

if __name__=='__main__':unittest.main()
