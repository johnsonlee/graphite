#!/usr/bin/env python3
"""Correctness checks for complete asset sets and byte-preserving generation."""
from pathlib import Path
import tempfile
import unittest

from generate import differences, synchronize


class AssetGenerationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.source = Path(self.temp.name) / 'source'
        self.target = Path(self.temp.name) / 'target'
        self.source.mkdir()
        self.target.mkdir()

    def test_rename_delete_and_binary_contents(self):
        (self.target / 'old.js').write_text('stale executable script')
        (self.target / 'deleted.css').write_text('stale style')
        (self.source / 'new.js').write_bytes(b'\xef\xbb\xbf\x00\xff\r\n')
        synchronize(self.source, self.target)
        self.assertEqual({p.name for p in self.target.iterdir()}, {'new.js'})
        self.assertEqual((self.target / 'new.js').read_bytes(), b'\xef\xbb\xbf\x00\xff\r\n')
        self.assertEqual(differences(self.source, self.target), [])

    def test_nested_paths_and_file_directory_replacement(self):
        (self.target / 'nested').write_text('old file')
        (self.source / 'nested').mkdir()
        (self.source / 'nested' / 'asset.js').write_text('new script')
        (self.target / 'flat').mkdir()
        (self.target / 'flat' / 'old.js').write_text('old nested script')
        (self.source / 'flat').write_text('new flat asset')
        synchronize(self.source, self.target)
        self.assertEqual((self.target / 'nested' / 'asset.js').read_text(), 'new script')
        self.assertEqual((self.target / 'flat').read_text(), 'new flat asset')
        self.assertEqual(differences(self.source, self.target), [])

    def test_check_reports_missing_extra_and_changed_without_writing(self):
        (self.source / 'missing.js').write_text('expected')
        (self.source / 'changed.js').write_text('new')
        (self.target / 'changed.js').write_text('old')
        (self.target / 'extra.js').write_text('obsolete')
        self.assertEqual(differences(self.source, self.target), ['changed.js', 'extra.js', 'missing.js'])
        self.assertEqual((self.target / 'changed.js').read_text(), 'old')
        self.assertTrue((self.target / 'extra.js').exists())
        self.assertFalse((self.target / 'missing.js').exists())

    def test_missing_source_preserves_outputs(self):
        (self.target / 'keep.js').write_text('existing asset')
        with self.assertRaises(FileNotFoundError):
            synchronize(self.source / 'missing', self.target)
        self.assertEqual((self.target / 'keep.js').read_text(), 'existing asset')


if __name__ == '__main__':
    unittest.main()
