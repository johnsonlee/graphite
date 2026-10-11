"""Correctness of per-open ZIP indexes; no performance workload."""
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
from . import field_authority as field, method_authority as method
from . import field_authority_test as fixtures
from .legacy_wire import Invalid


class JarDirectoryTests(unittest.TestCase):
    def authority(self, root, kind, duplicate=False):
        spec, graphs = fixtures.FieldAuthorityTests().authority(root, duplicate)
        cls = field.Authority if kind == 'field' else method.Authority
        return cls(spec, graphs['B'], graphs['C'], root/'out')

    def lookup(self, authority, kind, owner):
        return authority._field_class(owner) if kind == 'field' else authority.klass(owner)

    def test_duplicate_owner_rejected_before_class_evidence_for_both_authorities(self):
        for kind in ('field', 'method'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp); authority = self.authority(root, kind, duplicate=True)
                try:
                    with self.assertRaisesRegex(Invalid, 'duplicate'):
                        self.lookup(authority, kind, 'A')
                    self.assertEqual(authority._entry_counts['A.class'], 2)
                    self.assertFalse((root/'out').exists())
                finally: authority.finish()

    def test_index_once_per_open_preserves_missing_rules_raw_evidence_and_reopening(self):
        for kind in ('field', 'method'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp); authority = self.authority(root, kind)
                opened = []; original = zipfile.ZipFile.infolist
                def inventory(archive):
                    opened.append(archive); return original(archive)
                def missing(owner):
                    if kind == 'field':
                        with self.assertRaisesRegex(Invalid, 'missing/duplicate exact declared owner'):
                            self.lookup(authority, kind, owner)
                    else: self.assertIsNone(self.lookup(authority, kind, owner))
                with patch.object(zipfile.ZipFile, 'infolist', inventory), \
                        patch.object(zipfile.ZipFile, 'namelist', side_effect=AssertionError('no repeated name scan')):
                    try:
                        result = self.lookup(authority, kind, 'A')
                        evidence = Path(result['rawClassFile'] if kind == 'field' else result['evidence']['rawClassFile'])
                        self.assertEqual(evidence.read_bytes(), fixtures.class_bytes())
                        missing('Missing'); missing('OtherMissing')
                        self.assertEqual(len(opened), 1)
                        authority.finish()
                        self.assertIsNone(authority._entry_counts)
                        missing('StillMissing')
                        self.assertEqual(len(opened), 2)
                        self.assertIsNot(opened[0], opened[1])
                    finally: authority.finish()

    def test_final_jar_mutation_rejected_after_index_use(self):
        for kind in ('field', 'method'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as tmp:
                authority = self.authority(Path(tmp), kind)
                self.lookup(authority, kind, 'A')
                with authority.jar.open('ab') as stream: stream.write(b'changed')
                with self.assertRaisesRegex(Invalid, 'classfile authority input changed'): authority.finish()
                self.assertIsNone(authority.zip)
                self.assertIsNone(authority._entry_counts)
