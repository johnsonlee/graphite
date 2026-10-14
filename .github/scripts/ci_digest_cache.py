"""Job-wide cache of verified SHA-256 digests, keyed by the identity the kernel gives a file.

Every stage of the fixture64 chain hashes the files it consumes before it trusts them, and most of
those files were hashed by an earlier stage of the same job: a restore hashes every payload byte as
it lands and again at its final boundary, the core proof hashes every pin when it binds its plan and
again when it rebinds it at the end, and a seal hashes everything twice more. In run 915 a shard job
spent 32 of its 49 minutes hashing the same ~40 GB closure and 12 minutes proving graphs.

The cache keeps a file's digest next to its identity (device, inode, size, mtime and ctime in
nanoseconds). A later request for the same path with the same identity returns the recorded digest;
any other change hashes again. ctime cannot be set from user space, so a rewrite that restores the
size and the mtime still misses. Kernel timestamps have the granularity of the coarse clock (a few
milliseconds), so a digest is stored only once the file's identity is older than `SETTLE_NS`: a file
rewritten in the same tick as its hash cannot be mistaken for the bytes that were hashed. `record`
stores immediately, for a caller that wrote the bytes itself and hashed them on the way to disk.

The cache is one SQLite file named by `GRAPHITE_CI_DIGEST_CACHE`, shared by every process of the
job (WAL journal, so readers and the writer do not block each other). Without the variable every
call hashes, as before; a variable that names an unusable location is an error, never a silent
slow path.
"""
import hashlib
import os
import sqlite3
import stat
import threading
import time

ENV = 'GRAPHITE_CI_DIGEST_CACHE'
SETTLE_NS = 2_000_000_000
_SCHEMA = ('CREATE TABLE IF NOT EXISTS digests (path TEXT PRIMARY KEY, dev INTEGER, ino INTEGER, '
           'size INTEGER, mtime_ns INTEGER, ctime_ns INTEGER, sha256 TEXT NOT NULL)')


def hash_file(path):
    with open(path, 'rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def identity(st):
    return (st.st_dev, st.st_ino, st.st_size, st.st_mtime_ns, st.st_ctime_ns)


def _now_ns():
    return time.time_ns()


class DigestCache:
    def __init__(self, path):
        self.path = path
        self._lock = threading.Lock()
        self._connection = sqlite3.connect(path, timeout=120, check_same_thread=False, isolation_level=None)
        with self._lock:
            self._connection.execute('PRAGMA journal_mode=WAL')
            self._connection.execute('PRAGMA synchronous=NORMAL')
            self._connection.execute(_SCHEMA)

    def get(self, path, key):
        with self._lock:
            row = self._connection.execute(
                'SELECT sha256 FROM digests WHERE path=? AND dev=? AND ino=? AND size=? AND mtime_ns=? AND ctime_ns=?',
                (path, *key)).fetchone()
        return row[0] if row else None

    def put(self, path, key, digest):
        with self._lock:
            self._connection.execute('INSERT OR REPLACE INTO digests VALUES (?,?,?,?,?,?,?)', (path, *key, digest))

    def close(self):
        with self._lock:
            self._connection.close()


_caches = {}
_caches_lock = threading.Lock()


def cache():
    """The job's cache, or None when `GRAPHITE_CI_DIGEST_CACHE` is unset. One connection per process."""
    location = os.environ.get(ENV)
    if not location:
        return None
    with _caches_lock:
        value = _caches.get(location)
        if value is None:
            value = _caches[location] = DigestCache(location)
        return value


def _regular(path):
    real = os.path.realpath(path)
    st = os.stat(real)
    return (real, st) if stat.S_ISREG(st.st_mode) else (None, None)


def sha(path):
    """The SHA-256 of `path`'s bytes: recorded when the file's identity is unchanged, hashed otherwise."""
    store = cache()
    if store is None:
        return hash_file(path)
    real, before = _regular(path)
    if real is None:
        return hash_file(path)
    key = identity(before)
    recorded = store.get(real, key)
    if recorded is not None:
        return recorded
    digest = hash_file(path)
    after = os.stat(real)
    if identity(after) == key and _now_ns() - max(after.st_mtime_ns, after.st_ctime_ns) > SETTLE_NS:
        store.put(real, key, digest)
    return digest


def record(path, digest):
    """Store `digest` for `path` as it is now. Only for bytes the caller wrote and hashed itself."""
    store = cache()
    if store is None:
        return
    real, st = _regular(path)
    if real is not None:
        store.put(real, identity(st), digest)
