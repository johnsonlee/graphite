import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import ci_digest_cache as cache

SCRIPTS = Path(__file__).resolve().parent


def plain(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


class DigestCacheTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.db = str(self.root/'cache.sqlite')
        self.file = self.root/'graph.nodes'; self.file.write_bytes(b'alpha'*1000)
        self.addCleanup(self.close)
        self.old = patch.dict(os.environ, {cache.ENV: self.db}); self.old.start(); self.addCleanup(self.old.stop)
        # Kernel timestamps are current; hashes store only once the identity has settled.
        self.clock = patch.object(cache, '_now_ns', lambda: 1 << 62); self.clock.start(); self.addCleanup(self.clock.stop)

    def close(self):
        with cache._caches_lock:
            for store in cache._caches.values(): store.close()
            cache._caches.clear()

    def hashed(self):
        calls = []
        original = cache.hash_file
        def counting(path):
            calls.append(str(path)); return original(path)
        return patch.object(cache, 'hash_file', side_effect=counting), calls

    def test_unset_variable_hashes_every_call_and_keeps_no_cache(self):
        with patch.dict(os.environ, {cache.ENV: ''}):
            self.assertIsNone(cache.cache())
            counter, calls = self.hashed()
            with counter:
                self.assertEqual(plain(self.file), cache.sha(self.file))
                self.assertEqual(plain(self.file), cache.sha(self.file))
                cache.record(self.file, '0'*64)
                self.assertEqual(plain(self.file), cache.sha(self.file))
            self.assertEqual(3, len(calls))
            self.assertFalse(Path(self.db).exists())

    def test_unchanged_identity_is_hashed_once_and_any_change_hashes_again(self):
        counter, calls = self.hashed()
        with counter:
            first = cache.sha(self.file)
            self.assertEqual(plain(self.file), first)
            self.assertEqual(first, cache.sha(str(self.file)))
            self.assertEqual(1, len(calls), 'second request served from the record')
            before = self.file.stat()
            # Same size, same mtime: only ctime tells, and it cannot be restored from user space.
            self.file.write_bytes(b'alphb'*1000)
            os.utime(self.file, ns=(before.st_atime_ns, before.st_mtime_ns))
            self.assertNotEqual(before.st_ctime_ns, self.file.stat().st_ctime_ns)
            self.assertEqual(plain(self.file), cache.sha(self.file))
            self.assertEqual(2, len(calls))
            self.file.write_bytes(b'longer'*1000)
            self.assertEqual(plain(self.file), cache.sha(self.file))
            self.assertEqual(3, len(calls))
            self.assertEqual(plain(self.file), cache.sha(self.file))
            self.assertEqual(3, len(calls))

    def test_a_file_changed_within_the_settle_window_is_not_recorded(self):
        counter, calls = self.hashed()
        with counter, patch.object(cache, '_now_ns', lambda: self.file.stat().st_ctime_ns + cache.SETTLE_NS):
            self.assertEqual(plain(self.file), cache.sha(self.file))
            self.assertEqual(plain(self.file), cache.sha(self.file))
        self.assertEqual(2, len(calls), 'a young identity is hashed again')
        with counter:
            self.assertEqual(plain(self.file), cache.sha(self.file))
            self.assertEqual(plain(self.file), cache.sha(self.file))
        self.assertEqual(3, len(calls), 'stored once the identity settled')

    def test_record_stores_immediately_and_a_later_change_still_hashes(self):
        counter, calls = self.hashed()
        with counter, patch.object(cache, '_now_ns', lambda: self.file.stat().st_ctime_ns):
            cache.record(self.file, plain(self.file))
            self.assertEqual(plain(self.file), cache.sha(self.file))
            self.assertEqual([], calls)
            self.file.write_bytes(b'other'*1000)
            self.assertEqual(plain(self.file), cache.sha(self.file))
            self.assertEqual(1, len(calls))

    def test_record_of_a_wrong_digest_is_served_only_while_the_identity_holds(self):
        # record() trusts its caller; the cache never re-reads bytes to check a record.
        cache.record(self.file, '0'*64)
        self.assertEqual('0'*64, cache.sha(self.file))
        self.file.write_bytes(b'alpha'*1000); self.file.touch()
        self.assertEqual(plain(self.file), cache.sha(self.file))

    def test_symlinks_directories_and_missing_files_behave_as_hashing_does(self):
        link = self.root/'link'; link.symlink_to(self.file.name)
        counter, calls = self.hashed()
        with counter:
            self.assertEqual(plain(self.file), cache.sha(link))
            self.assertEqual(plain(self.file), cache.sha(self.file), 'the link resolves to the same identity')
        self.assertEqual(1, len(calls))
        with self.assertRaises(IsADirectoryError): cache.sha(self.root)
        with self.assertRaises(FileNotFoundError): cache.sha(self.root/'absent')
        cache.record(self.root, 'f'*64)  # not a regular file: nothing recorded
        with self.assertRaises(IsADirectoryError): cache.sha(self.root)
        with self.assertRaises(FileNotFoundError): cache.record(self.root/'absent', 'f'*64)

    def test_records_are_shared_across_processes_of_one_job(self):
        digest = plain(self.file)
        code = ('import sys, ci_digest_cache as c\n'
                'c.record(sys.argv[1], sys.argv[2])\n')
        subprocess.run([sys.executable, '-B', '-c', code, str(self.file), 'e'*64], check=True, cwd=SCRIPTS,
                       env={**os.environ, cache.ENV: self.db})
        self.assertEqual('e'*64, cache.sha(self.file), 'the child\'s record is visible here')
        counter, calls = self.hashed()
        with counter:
            cache.sha(self.file)
        self.assertEqual([], calls)
        probe = subprocess.run([sys.executable, '-B', '-c',
                                'import sys, ci_digest_cache as c\nprint(c.sha(sys.argv[1]))', str(self.file)],
                               check=True, cwd=SCRIPTS, env={**os.environ, cache.ENV: self.db},
                               capture_output=True, text=True)
        self.assertEqual('e'*64, probe.stdout.strip())
        self.assertNotEqual(digest, 'e'*64)

    def test_an_unusable_cache_location_is_an_error_not_a_slow_path(self):
        with patch.dict(os.environ, {cache.ENV: str(self.root/'missing'/'cache.sqlite')}):
            with self.assertRaises(Exception): cache.sha(self.file)

    def test_pinned_helpers_route_through_the_cache(self):
        import multigraph_pressure, native_pressure_checkpoint
        counter, calls = self.hashed()
        with counter:
            self.assertEqual(plain(self.file), multigraph_pressure.sha(self.file))
            self.assertEqual(plain(self.file), native_pressure_checkpoint.sha(self.file))
        self.assertEqual(1, len(calls))


if __name__ == '__main__':
    unittest.main()
