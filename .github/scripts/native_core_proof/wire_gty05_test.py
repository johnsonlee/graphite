import copy
import struct
import unittest
from unittest.mock import patch
from . import wire_gty05 as w

META = '11'*32
STRINGS = '22'*32

def row(kind, name='', scope=(0,-1), owner=-1, component=-1, variance='', arguments=()):
    return dict(kind=kind,name=name,scope=scope,owner=owner,component=component,variance=variance,arguments=list(arguments))

def fixture():
    return dict(rows=[
        row('class','java.lang.Object'), row('variable','T',(1,0)),
        row('class','java.util.List',arguments=[1]), row('primitive','int'), row('primitive','void'),
        row('array',component=3), row('variable','M',(2,0)), row('array',component=2),
        row('class','java.util.List'), row('array',component=8)],
        signatures=[([8,5],0),([],4)],
        fields=[('Example','items',8,2),('Example','matrix',9,7)],
        methods=[('Example','take',0,[2,5],6,[('M',(2,0),[0])]),('Example','empty',1,[],4,[])],
        classes=[('Example',[('T',(1,0),[0])],0,[])])


def encode(f, version=5):
    names=[]
    def sid(s):
        if s not in names:names.append(s)
        return names.index(s)
    i=lambda n:struct.pack('>i',n)
    refs=lambda values:i(len(values))+b''.join(i(x) for x in values)
    # Explicit independent descriptor expectations for the tiny fixture's v4 keys.
    field_desc={8:'Ljava/util/List;',9:'[Ljava/util/List;'}
    method_desc={0:'(Ljava/util/List;[I)Ljava/lang/Object;',1:'()V'}
    b=bytearray(i(0x47545900|version)+bytes.fromhex(META)+bytes.fromhex(STRINGS));positions={'rows':[],'formals':[]}
    def formal(values):
        out=bytearray(i(len(values)))
        for name,(tag,target),bounds in values:
            out+=i(sid(name))+bytes([tag,0,0,0])+i(target)+refs(bounds)
        return out
    b+=i(len(f['rows']))
    for t in f['rows']:
        positions['rows'].append(len(b))
        b+=bytes([w.KINDS.index(t['kind']),w.VARIANCES.index(t['variance']),t['scope'][0],0])
        b+=i(sid(t['name']) if t['name'] else -1)+i(t['scope'][1])+i(t['owner'])+i(t['component'])+refs(t['arguments'])
    if version==5:
        positions['signatureCount']=len(b);b+=i(len(f['signatures']))
        for params,result in f['signatures']:b+=refs(params)+i(result)
    positions['fields']=len(b);b+=i(len(f['fields']))
    for owner,name,key,value in f['fields']:
        b+=i(sid(owner))+i(sid(name))+i(key if version==5 else sid(field_desc[key]))+i(value)
    positions['methods']=len(b);b+=i(len(f['methods']))
    for owner,name,key,params,result,parameters in f['methods']:
        b+=i(sid(owner))+i(sid(name))+i(key if version==5 else sid(method_desc[key]))+refs(params)+i(result)
        positions['formals'].append(len(b));b+=formal(parameters)
    positions['classes']=len(b);b+=i(len(f['classes']))
    for name,parameters,parent,interfaces in f['classes']:
        b+=i(sid(name));positions['formals'].append(len(b));b+=formal(parameters)+i(parent)+refs(interfaces)
    return bytes(b),w.VerifiedStrings(tuple(names),STRINGS),positions


def parse(f=None, version=5):
    b,s,_=encode(f or fixture(),version)
    return w.declared_types(b,META,version,s)


class StructuralWireTests(unittest.TestCase):
    def test_full_structural_keys_and_declaration_values_are_distinct(self):
        t=parse();key=('Example','take','(Ljava/util/List;[I)Ljava/lang/Object;')
        self.assertEqual({('Example','items','Ljava/util/List;'):2,('Example','matrix','[Ljava/util/List;'):7},t.fields)
        self.assertEqual([2,5],t.methods[key]['parameters'])
        self.assertEqual('method:Example#take(Ljava/util/List;[I)Ljava/lang/Object;',t.rows[6]['scope'])
        self.assertEqual('class:Example',t.rows[1]['scope'])
        p=t.method_properties(t.methods[key]);self.assertEqual(['java.util.List<T>','int[]'],p['generic_parameter_types'])
        self.assertEqual('M',p['generic_return_type']);self.assertEqual(['java.lang.Object'],p['type_parameters'][0]['bounds'])
        self.assertEqual('java.util.List<T>[]',t.render(7))
        self.assertEqual({'kind':'array','component':{'kind':'class','name':'java.util.List','arguments':[{'kind':'variable','name':'T','scope':'class:Example','arguments':[]}]},'arguments':[]},t.info(7))
        self.assertEqual([8,9],t.usage()['keyOnlyTypeIds']);self.assertFalse(t.usage()['appendedOriginClaim'])
        self.assertEqual(list(range(8)),t.usage()['projectionTypeIds'])

    def test_version4_uses_shared_descriptor_strings_but_structural_scopes(self):
        a,b=parse(version=4),parse()
        self.assertEqual((a.rows,a.fields,a.methods,a.classes),(b.rows,b.fields,b.methods,b.classes))
        self.assertEqual([],a.usage()['keyOnlyTypeIds'])

    def test_all_scope_tags_resolve_from_declarations_and_erased_descriptor(self):
        f=fixture();f['rows'][1]['scope']=(3,0);f['rows'][6]['scope']=(4,0)
        f['methods'][0][-1][0]=('M',(4,0),[0]);f['classes'][0][1][0]=('T',(3,0),[0])
        t=parse(f)
        self.assertEqual('unresolved:class:Example',t.rows[1]['scope'])
        expected='unresolved:method:Example#take(Ljava/util/List;[I)Ljava/lang/Object;'
        self.assertEqual(expected,t.rows[6]['scope']);self.assertEqual(expected,next(iter(t.methods.values()))['formals'][0]['scope'])

    def test_key_only_is_not_claimed_appended_without_prior_prefix(self):
        t=parse();prior=w.Types(copy.deepcopy(t.rows[:8]),copy.deepcopy(t.fields),copy.deepcopy(t.methods),copy.deepcopy(t.classes))
        proof=t.prove_append_only(prior);self.assertEqual([8,9],proof['appendedTypeIds'])
        prior.rows[0]['name']='Changed'
        with self.assertRaisesRegex(w.Invalid,'prefix'):t.prove_append_only(prior)

    def test_unused_row_is_valid_but_not_key_only_append(self):
        f=fixture();f['rows'].append(row('class','Unused'));t=parse(f)
        self.assertEqual([10],t.usage()['unusedTypeIds'])
        prior=w.Types(t.rows[:8],t.fields,t.methods,t.classes)
        with self.assertRaisesRegex(w.Invalid,'raw-key-only'):t.prove_append_only(prior)

    def test_changed_bindings_cannot_pass_prefix_comparison(self):
        t=parse();prior=w.Types(t.rows[:8],dict(t.fields),t.methods,t.classes)
        prior.fields[('Example','items','Ljava/util/List;')]=0
        with self.assertRaisesRegex(w.Invalid,'binding'):t.prove_append_only(prior)

    def test_bindings_and_version_fail_closed(self):
        b,s,_=encode(fixture())
        for version,meta,strings in [(4,META,s),(6,META,s),(5,'33'*32,s),(5,META,w.VerifiedStrings(s.values,'44'*32)),(5,META,s.values)]:
            with self.subTest(version=version,meta=meta,strings=type(strings).__name__):
                with self.assertRaises(w.Invalid):w.declared_types(b,meta,version,strings)
        with self.assertRaises(w.Invalid):w.declared_types(b+b'X',META,5,s)

    def test_every_truncation_rejected(self):
        b,s,_=encode(fixture())
        for size in range(len(b)):
            with self.subTest(size=size):
                with self.assertRaises(w.Invalid):w.declared_types(b[:size],META,5,s)

    def test_unknown_enums_reserved_bytes_and_scope_shapes_rejected(self):
        b,s,p=encode(fixture())
        changes=[(p['rows'][0],255),(p['rows'][0]+1,255),(p['rows'][0]+2,255),(p['rows'][0]+3,1),(p['formals'][0]+9,1)]
        for offset,value in changes:
            damaged=bytearray(b);damaged[offset]=value
            with self.subTest(offset=offset):
                with self.assertRaises(w.Invalid):w.declared_types(bytes(damaged),META,5,s)
        for scope in [(0,0),(1,-1),(1,1),(2,2),(3,1),(4,2)]:
            f=fixture();f['rows'][1]['scope']=scope
            with self.subTest(scope=scope):
                with self.assertRaises(w.Invalid):parse(f)

    def test_invalid_dictionary_type_and_signature_ids_rejected(self):
        b,s,p=encode(fixture())
        changes=[(p['rows'][0]+4,-2),(p['rows'][0]+4,len(s.values)),(p['rows'][0]+12,1000),
                 (p['fields']+12,1000),(p['methods']+12,-1),(p['methods']+12,2),(p['signatureCount'],-1)]
        for offset,value in changes:
            damaged=bytearray(b);damaged[offset:offset+4]=struct.pack('>i',value)
            with self.subTest(offset=offset,value=value):
                with self.assertRaises(w.Invalid):w.declared_types(bytes(damaged),META,5,s)

    def test_noncanonical_erased_rows_rejected(self):
        for change in [dict(kind='variable',name='X',scope=(1,0)),dict(arguments=[0]),dict(owner=0),dict(component=0),dict(scope=(1,0)),dict(variance='extends')]:
            f=fixture();f['rows'][8].update(change)
            with self.subTest(change=change):
                with self.assertRaises(w.Invalid):parse(f)
        for name in ['', '.Leading', 'Trailing.', 'Empty..Segment', 'bad/name','bad;name','bad[name']:
            f=fixture();f['rows'][8]['name']=name
            with self.subTest(name=name):
                with self.assertRaises(w.Invalid):parse(f)

    def test_void_restricted_to_method_return(self):
        for where in ['field','parameter','array']:
            f=fixture()
            if where=='field':f['fields'][0]=('Example','items',4,2)
            elif where=='parameter':f['signatures'][0]=([4],0)
            else:f['rows'][9]['component']=4
            with self.subTest(where=where):
                with self.assertRaisesRegex(w.Invalid,'void'):parse(f)
        self.assertIn(('Example','empty','()V'),parse().methods)

    def test_duplicate_signatures_rejected_by_shape_even_for_different_ids(self):
        f=fixture();f['rows'].append(row('class','java.util.List'));f['signatures'].append(([10,5],0))
        with self.assertRaisesRegex(w.Invalid,'duplicate erased method'):parse(f)

    def test_duplicate_member_keys_and_classes_rejected(self):
        for section in ['fields','methods','classes']:
            f=fixture();f[section].append(copy.deepcopy(f[section][0]))
            with self.subTest(section=section):
                with self.assertRaisesRegex(w.Invalid,'duplicate'):parse(f)

    def test_cycles_rejected_in_keys_and_unused_projection_rows(self):
        f=fixture();f['rows'][9]['component']=9
        with self.assertRaisesRegex(w.Invalid,'cycle'):parse(f)
        f=fixture();f['rows'].append(row('array',component=10))
        with self.assertRaisesRegex(w.Invalid,'cycle'):parse(f)

    def test_nested_unicode_raw_name_reconstructs_exact_descriptor(self):
        f=fixture();f['rows'][8]['name']='例.Outer$Inner'
        t=parse(f);self.assertIn(('Example','items','L例/Outer$Inner;'),t.fields)
        self.assertIn(('Example','matrix','[L例/Outer$Inner;'),t.fields)
        self.assertIn(('Example','take','(L例/Outer$Inner;[I)Ljava/lang/Object;'),t.methods)

    def test_legacy_empty_v3_remains_supported(self):
        data=struct.pack('>i',0x47545903)+bytes.fromhex(META)+bytes.fromhex(STRINGS)+struct.pack('>iiii',0,0,0,0)
        table=w.declared_types(data,META,3,w.VerifiedStrings((),STRINGS))
        self.assertEqual([],table.rows);self.assertEqual({},table.methods)


if __name__=='__main__':
    with patch('subprocess.Popen',side_effect=AssertionError('no child processes')):unittest.main()
