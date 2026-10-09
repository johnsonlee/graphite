"""Tiny byte fixtures exercise dictionary/metadata/type-table binding only."""
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
from . import declarations as d
from . import wire_gty05 as w


class DeclarationBindingTests(unittest.TestCase):
    def setUp(self):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup)
        self.root=Path(temp.name).resolve();self.graph=self.root/'graph';self.graph.mkdir()
        self.output=self.root/'export';self.output.mkdir()
        (self.graph/'graph.strings').write_bytes(b'actual serialized dictionary input')
        (self.graph/'graph.metadata').write_bytes(b'actual metadata input')
        i=lambda n:struct.pack('>i',n)
        gso=i(0x47534f01)+i(1)+i(7)+b'Example'
        (self.output/'strings.bin').write_bytes(gso)
        values,semantic=w.legacy.strings_export(gso)
        receipt={'format':'GSO01','inputSha256':d.sha(self.graph/'graph.strings'),
                 'outputSha256':d.sha(self.output/'strings.bin'),'entryCount':1,
                 'semanticSha256':semantic,'helperClassSha256':'a'*64,'helperSourceSha256':d.SOURCE_SHA}
        (self.output/'receipt.json').write_text(json.dumps(receipt))
        self.row={'id':'fixture','input':self.ref(self.graph/'graph.strings'),
                  'stringsExport':self.ref(self.output/'strings.bin'),'stringsReceipt':self.ref(self.output/'receipt.json'),
                  'helperClassSha256':'a'*64}
        # One class row and one class declaration; no key text or generic string.
        table=i(0x47545905)+bytes.fromhex(d.sha(self.graph/'graph.metadata'))+bytes.fromhex(d.sha(self.graph/'graph.strings'))
        table+=i(1)+bytes([0,0,0,0])+i(0)+i(-1)+i(-1)+i(-1)+i(0)
        table+=i(0)+i(0)+i(0)+i(1)+i(0)+i(0)+i(-1)+i(0)
        (self.graph/'graph.types').write_bytes(table)
        self.bind_table()

    def bind_table(self):
        (self.graph/'forward.properties').write_text('graphclass=example.Graph\n' +
            'graphite.declaredTypes.sha256=' + d.sha(self.graph/'graph.types') + '\n')

    def ref(self,path):return {'path':str(path),'sha256':d.sha(path)}

    def test_resolves_actual_structural_table_against_exported_dictionary(self):
        table=d.load(self.graph,self.row)
        self.assertEqual('Example',table.render(0))
        self.assertEqual({'Example':{'formals':[],'super':None,'interfaces':[]}},table.classes)
        self.assertEqual({},table.fields);self.assertEqual({},table.methods)
        self.assertEqual([0],table.usage()['unusedTypeIds'])
        self.assertFalse(table.usage()['appendedOriginClaim'])

    def test_wrong_graph_or_dictionary_bytes_rejected(self):
        other=self.root/'other';other.mkdir();(other/'forward.properties').write_bytes((self.graph/'forward.properties').read_bytes());(other/'graph.types').write_bytes((self.graph/'graph.types').read_bytes());(other/'graph.strings').write_bytes(b'other dictionary')
        with self.assertRaisesRegex(w.Invalid,'dictionary export linkage'):d.load(other,self.row)
        (self.graph/'graph.strings').write_bytes(b'changed')
        with self.assertRaisesRegex(w.Invalid,'dictionary export linkage'):d.load(self.graph,self.row)

    def test_changed_export_and_helper_identity_rejected(self):
        self.row['helperClassSha256']='b'*64
        with self.assertRaisesRegex(w.Invalid,'helper binding'):d.load(self.graph,self.row)
        self.row['helperClassSha256']='a'*64
        (self.output/'strings.bin').write_bytes(b'changed')
        with self.assertRaisesRegex(w.Invalid,'evidence pin'):d.load(self.graph,self.row)

    def test_actual_metadata_not_matching_table_rejected(self):
        (self.graph/'graph.metadata').write_bytes(b'new metadata')
        with self.assertRaisesRegex(w.Invalid,'metadata digest binding'):d.load(self.graph,self.row)

    def test_corrupt_type_reference_or_trailing_data_rejected(self):
        path=self.graph/'graph.types';raw=path.read_bytes()
        for changed in (raw+b'extra',raw[:80]+struct.pack('>i',99)+raw[84:]):
            path.write_bytes(changed);self.bind_table()
            with self.assertRaises(w.Invalid):d.load(self.graph,self.row)

    def test_unsupported_format_rejected_without_guessing(self):
        path=self.graph/'graph.types';raw=path.read_bytes();path.write_bytes(raw[:3]+b'\x06'+raw[4:]);self.bind_table()
        with self.assertRaisesRegex(w.Invalid,'supported actual GTY header'):d.load(self.graph,self.row)

    def test_missing_properties_and_orphan_table_are_rejected(self):
        props=self.graph/'forward.properties';props.unlink()
        with self.assertRaisesRegex(w.Invalid,'missing actual declaration properties'):d.load(self.graph,self.row)
        props.write_text('graphclass=example.Graph\n')
        with self.assertRaisesRegex(w.Invalid,'orphan type sidecar'):d.load(self.graph,self.row)

    def test_uppercase_digest_matches_production_semantics(self):
        props=self.graph/'forward.properties'
        props.write_text('graphite.declaredTypes.sha256=' + d.sha(self.graph/'graph.types').upper() + '\n')
        self.assertEqual('Example',d.load(self.graph,self.row).render(0))

    def test_malformed_or_duplicate_properties_are_rejected(self):
        props=self.graph/'forward.properties'
        for text in ('graphite.declaredTypes.sha256=bad\n',
                     'graphite.declaredTypes.sha256:'+'a'*64+'\n',
                     'graphite.declaredTypes.sha256='+'a'*64+'\n'+'graphite.declaredTypes.sha256='+'b'*64+'\n',
                     'graphite.declaredTypes.sha256=\\u0061\n'):
            props.write_text(text)
            with self.subTest(text=text),self.assertRaisesRegex(w.Invalid,'malformed'):d.load(self.graph,self.row)
        props.write_bytes(b'bad=\xff')
        with self.assertRaisesRegex(w.Invalid,'malformed'):d.load(self.graph,self.row)

    def test_mismatch_missing_and_symlinked_table_are_rejected(self):
        path=self.graph/'graph.types';raw=path.read_bytes();path.write_bytes(raw+b'changed')
        with self.assertRaisesRegex(w.Invalid,'binding mismatch'):d.load(self.graph,self.row)
        path.unlink()
        with self.assertRaisesRegex(w.Invalid,'missing bound declaration table'):d.load(self.graph,self.row)
        target=self.root/'other.types';target.write_bytes(raw);path.symlink_to(target)
        with self.assertRaisesRegex(w.Invalid,'missing bound declaration table'):d.load(self.graph,self.row)


if __name__=='__main__':unittest.main()
