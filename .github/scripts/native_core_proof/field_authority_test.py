"""Synthetic classfiles and maps only; no real graph/JAR or child process."""
import copy,hashlib,json,struct,tempfile,unittest,zipfile
from pathlib import Path
from . import field_authority as f
from .legacy_wire import Invalid
I=lambda n:struct.pack('>I',n)
S=lambda n:struct.pack('>H',n)
def class_bytes(owner='A',name='x',descriptor='Ljava/lang/Object;',signature='TT;',static=False):
    pool=[]
    def utf(s):pool.append(b'\x01'+S(len(s.encode()))+s.encode());return len(pool)
    own=utf(owner);pool.append(b'\x07'+S(own));this=len(pool)
    obj=utf('java/lang/Object');pool.append(b'\x07'+S(obj));super_=len(pool)
    ni,di=utf(name),utf(descriptor);attributes=b''
    if signature is not None:si=utf('Signature');vi=utf(signature);attributes=S(si)+I(2)+S(vi)
    field=S(8 if static else 0)+S(ni)+S(di)+S(int(signature is not None))+attributes
    return bytes.fromhex('cafebabe')+S(0)+S(52)+S(len(pool)+1)+b''.join(pool)+S(1)+S(this)+S(super_)+S(0)+S(1)+field+S(0)+S(0)
def field(desc='Ljava/lang/Object;',sig='TT;',static=False):return {'name':'x','descriptor':desc,'signature':sig,'static':static}
class FakeAuthority:
    def __init__(self,value):self.value=value;self.calls=[]
    def field(self,owner,name):self.calls.append((owner,name));return {'field':self.value,'class':{'owner':owner}}
def row(node,key):return node,f.canonical_field_payload(key)
class FieldAuthorityTests(unittest.TestCase):
    def test_classfile_raw_descriptor_signature_static(self):
        raw=class_bytes(static=True);p=f.parse(raw);x=p['fields'][0]
        self.assertEqual((p['owner'],x['descriptor'],x['signature'],x['static']),('A','Ljava/lang/Object;','TT;',True));self.assertTrue(x['rawFieldInfoHex'])
    def test_classfile_truncation_trailing_bad_cp(self):
        raw=class_bytes()
        for bad in (raw[:-1],raw+b'\0',raw[:10]+b'\xff'+raw[11:]):
            with self.assertRaises((Invalid,ValueError)):f.parse(bad)
    def test_variable_and_array_variable_correction(self):
        for c,b,d,s in [('T','java.lang.Object','Ljava/lang/Object;','TT;'),('T[]','java.lang.Object[]','[Ljava/lang/Object;','[TT;')]:
            self.assertEqual(f.correction(('A','x',c,False),('A','x',b,False),field(d,s)),'LEGACY_TYPE_VARIABLE_CLASSNAME')
    def test_owner_inner_correction_with_nested_arguments(self):
        sig='La/Outer<TK;>.Inner<Ljava/util/List<+TV;>;>;'
        self.assertEqual(f.correction(('A','x','a.Outer',False),('A','x','a.Outer$Inner',False),field('La/Outer$Inner;',sig)),'LEGACY_OUTER_ONLY_INNER_CLASSNAME')
    def test_dimension_correction_only_without_signature(self):
        self.assertEqual(f.correction(('A','x','int[]',False),('A','x','int[][][]',False),field('[[[I',None)),'LEGACY_ARRAY_DIMENSION_COLLAPSE')
        with self.assertRaises(Invalid):f.correction(('A','x','int[]',False),('A','x','int[][]',False),field('[[I','[[I'))
    def test_wrong_candidate_old_static_owner_rejected(self):
        c=('A','x','T',False);b=('A','x','java.lang.Object',False)
        for aa,bb,ff in [(c,('A','x','wrong',False),field()),(('A','x','wrong',False),b,field()),(c,b,field(static=True)),(c,('B','x','java.lang.Object',False),field())]:
            with self.assertRaises(Invalid):f.correction(aa,bb,ff)
    def test_signature_complete_framing_and_no_unknown_case(self):
        for sig in ('TT;garbage','Lx<;','[','Lx<>;','Lx.Inner','V','T;'):
            with self.assertRaises(Invalid):f.Signature(sig).done()
        with self.assertRaises(Invalid):f.correction(('A','x','x',False),('A','x','java.lang.Object',False),field('Ljava/lang/Object;','Lx;'))
    def test_exact_matches_never_use_authority(self):
        k=('A','x','T',False);a=FakeAuthority(None);m,remap,records=f.match_fields({k:row(1,k)},{k:row(1,k)},a)
        self.assertEqual((m,remap,records,a.calls),({k:k},{},[],[]))
    def test_corrected_closed_swap_and_raw_evidence(self):
        b=('A','x','java.lang.Object',False);c=('A','x','T',False);z=('A','z','int',False)
        m,r,records=f.match_fields({b:row(2,b),z:row(1,z)},{c:row(1,c),z:row(2,z)},FakeAuthority(field()))
        self.assertEqual(r,{2:1,1:2});self.assertEqual(m[b],c);self.assertEqual(records[0]['C']['key'],list(c));self.assertEqual(records[0]['B']['canonicalPayloadHex'],row(2,b)[1].hex())
    def test_nonclosed_field_ids_and_non_type_payload_rejected(self):
        b=('A','x','java.lang.Object',False);c=('A','x','T',False)
        for ar,br in [({b:row(1,b)},{c:row(2,c)}),({b:(1,row(1,b)[1]+b'\0')},{c:row(1,c)})]:
            with self.assertRaises(Invalid):f.match_fields(ar,br,FakeAuthority(field()))
    def test_ambiguous_or_missing_field_group_rejected(self):
        b=('A','x','java.lang.Object',False);b2=('A','x','other',False);c=('A','x','T',False)
        for a,ref in [({b:row(1,b),b2:row(2,b2)},{c:row(1,c)}),({b:row(1,b)},{('A','z','T',False):row(1,('A','z','T',False))})]:
            with self.assertRaises(Invalid):f.match_fields(a,ref,FakeAuthority(field()))
    def test_correction_receipt_survives_later_strict_callsite_failure(self):
        from unittest.mock import patch
        from . import core_semantics as core
        class A(FakeAuthority):
            pins={}
            def finish(self):pass
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);a=root/'B';b=root/'C';a.mkdir();b.mkdir()
            for graph in (a,b):
                row=I(0)+b'\x09'+I(0)+I(1)+I(2)+b'\x00'
                (graph/'graph.nodedata').write_bytes(I(0x47524e03)+I(1)+row)
                (graph/'graph.nodeoffsets').write_bytes(I(0x47524c03)+I(1)+struct.pack('>q',9))
                offset=8+16*13;parts=[]
                for tag in range(16):
                    count=int(tag==9);parts.append(bytes([tag])+I(count)+struct.pack('>q',offset));offset+=4*count
                (graph/'graph.typeindex').write_bytes(I(0x47525403)+I(16)+b''.join(parts)+I(0))
                (graph/'graph.nodeindex').write_bytes(I(0x47524903)+I(1)+I(0)+b'\x09'+struct.pack('>q',8))
            def strings(export,original):return ['A','x','java.lang.Object' if original.parent==a else 'T']
            with patch.object(core,'load_strings',side_effect=strings),patch.object(f,'Authority',return_value=A(field())),patch.object(core.callsite_index,'callsites',side_effect=Invalid('later strict failure')):
                with self.assertRaisesRegex(Invalid,'later strict failure'):core.prove(a,b,root,root,root/'out',True,{'graphId':'g'})
            receipt=json.loads((root/'out/field-corrections.json').read_text());self.assertEqual(receipt['exceptionCount'],1);self.assertFalse((root/'out/receipt.json').exists());self.assertTrue((root/'out/raw-field-key-differences.json').exists())

    def authority(self,root,duplicate=False):
        jar=root/'a.jar'
        with zipfile.ZipFile(jar,'w') as z:z.writestr('A.class',class_bytes())
        spec={'schema':'graphite.classfile-field-authority.v1','graphId':'g','corpus':'android','jar':{'corpus':'android','path':str(jar),'sha256':f.sha(jar)},'arms':{}}
        graphs={}
        for label in ('C','B'):
            d=root/label;d.mkdir();pr=d/'provenance.tsv';graph=d/'g';graphs[label]=graph
            pr.write_text('graphId\tcorpus\tsourceJar\tsourceJarSha256\tgraphPath\n'+'g\tandroid\ta.jar\t'+f.sha(jar)+'\t'+str(graph)+'\n')
            mf=d/'fixture.json';mf.write_text(json.dumps({'files':{str(pr):f.sha(pr)},'graphs':[{'id':'g','path':str(graph)}],'inputJars':[spec['jar']]}))
            spec['arms'][label]={'fixtureManifest':{'path':str(mf),'sha256':f.sha(mf)},'provenance':{'path':str(pr),'sha256':f.sha(pr)}}
        return spec,graphs
    def test_bound_own_writer_jar_and_missing_inherited_owner(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);spec,g=self.authority(root);a=f.Authority(spec,g['B'],g['C'],root/'out')
            try:
                self.assertEqual(a.field('A','x')['field']['signature'],'TT;')
                for owner,name in [('A','missing'),('Parent','x')]:
                    with self.assertRaises(Invalid):a.field(owner,name)
            finally:a.finish()
    def test_provenance_wrong_jar_and_graph_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);spec,g=self.authority(root)
            with self.assertRaises(Invalid):f.Authority(spec,g['C'],g['B'],root/'out')
            spec['jar']['sha256']='0'*64
            with self.assertRaises(Invalid):f.Authority(spec,g['B'],g['C'],root/'out')
    def test_authority_final_mutation_fails(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);spec,g=self.authority(root);a=f.Authority(spec,g['B'],g['C'],root/'out');Path(spec['jar']['path']).write_bytes(b'changed')
            with self.assertRaises(Invalid):a.finish()
if __name__=='__main__':unittest.main()
