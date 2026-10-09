import io,tempfile,unittest,zipfile,warnings
from unittest.mock import patch
from . import method_authority
from pathlib import Path
from . import field_authority as f
from . import marker_authority as m
from .field_authority_test import class_bytes,S
from .inherited_field_authority_test import klass,field
from .legacy_wire import Invalid

def marker(owner='java/io/Serializable',flags=0x0601):
    pool=[]
    def utf(s):pool.append(b'\x01'+S(len(s.encode()))+s.encode());return len(pool)
    a=utf(owner);pool.append(b'\x07'+S(a));b=utf('java/lang/Object');pool.append(b'\x07'+S(b))
    return bytes.fromhex('cafebabe')+S(0)+S(52)+S(5)+b''.join(pool)+S(flags)+S(2)+S(4)+S(0)+S(0)+S(0)+S(0)

class FixtureMarker:
    def __init__(self):self.calls=0
    def archive(self,out):
        self.calls+=1
        return dict(m.marker_class(marker()),sourceKind='EXACT_BOOTSTRAP_MARKER_NOT_CORPUS_JAR')

class Markers(unittest.TestCase):
    def authority(self,rows=(),enabled=True):
        stream=io.BytesIO()
        with zipfile.ZipFile(stream,'w') as z:
            with warnings.catch_warnings():
                warnings.simplefilter('ignore')
                for name,raw in rows:z.writestr(name,raw)
        stream.seek(0);a=f.Authority.__new__(f.Authority);a.cache={};a.zip=zipfile.ZipFile(stream);self.addCleanup(a.zip.close)
        tmp=tempfile.TemporaryDirectory();self.addCleanup(tmp.cleanup);a.out=Path(tmp.name);a.jar_digest='corpus';a.allow_inherited_fields=True;a.marker=FixtureMarker() if enabled else None
        return a
    def test_method_initialization_strips_only_field_marker_without_mutating_spec(self):
        spec={'platformMarker':{'path':'marker','sha256':'digest'},'schema':'ordinary','keep':7}
        with patch.object(f.Authority,'__init__',return_value=None) as parent:
            method_authority.Authority(spec,'actual','reference','out',allow_legacy_collisions=True)
        parent.assert_called_once_with({'schema':'ordinary','keep':7},'actual','reference','out')
        self.assertEqual({'path':'marker','sha256':'digest'},spec['platformMarker'])

    def test_real_class_parser_requires_exact_empty_marker(self):
        self.assertEqual([],m.marker_class(marker())['fields'])
        for raw in [marker('java/lang/Runnable'),marker(flags=1),class_bytes(owner='java/io/Serializable'),marker()[:-1],marker()+b'x']:
            with self.assertRaises((ValueError,Invalid)):m.marker_class(raw)
    def test_only_absent_serializable_can_use_opt_in_authority(self):
        a=self.authority();c=a._field_class('java.io.Serializable');self.assertEqual('EXACT_BOOTSTRAP_MARKER_NOT_CORPUS_JAR',c['sourceKind'])
        self.assertIs(c,a._field_class('java.io.Serializable'));self.assertEqual(1,a.marker.calls)
        with self.assertRaises(Invalid):a._field_class('java.lang.Runnable')
        self.assertEqual(1,a.marker.calls)
    def test_default_still_fails_for_missing_platform_class(self):
        with self.assertRaises(Invalid):self.authority(enabled=False)._field_class('java.io.Serializable')
    def test_corpus_definition_takes_precedence(self):
        a=self.authority([('java/io/Serializable.class',class_bytes(owner='java/io/Serializable'))])
        c=a._field_class('java.io.Serializable');self.assertEqual('x',c['fields'][0]['name']);self.assertEqual(0,a.marker.calls)
    def test_duplicate_corpus_definition_never_falls_back(self):
        entry=('java/io/Serializable.class',class_bytes(owner='java/io/Serializable'))
        a=self.authority([entry,entry])
        with self.assertRaises(Invalid):a._field_class('java.io.Serializable')
        self.assertEqual(0,a.marker.calls)
    def test_exact_marker_completes_interface_before_declared_parent(self):
        a=self.authority();a.cache={'C':klass('C',parent='P',interfaces=['java.io.Serializable']),'P':klass('P',[field()],parent='Missing')}
        proof=a.field_key(('C','x','double[][]',False))
        self.assertEqual('P',proof['class']['owner']);self.assertEqual(['C','java.io.Serializable','P'],[p['owner'] for p in proof['lookupSteps']]);self.assertEqual(1,a.marker.calls)
    def test_other_missing_interface_cannot_be_skipped(self):
        a=self.authority();a.cache={'C':klass('C',parent='P',interfaces=['java.lang.Runnable','java.io.Serializable']),'P':klass('P',[field()])}
        with self.assertRaises(Invalid):a.field_key(('C','x','double[][]',False))
        self.assertEqual(0,a.marker.calls)

if __name__=='__main__':unittest.main()
