#!/usr/bin/env python3
"""Independent file-identity and typed CRC check; no query/performance claim."""
import array
import datetime
import hashlib
import json
import pathlib
import struct
import zlib

root = pathlib.Path(__file__).resolve().parents[3]
source = root / 'graphite-server/internal/store/testdata/callsite-index/real64-correctness/validated-indexes.json'
observations = json.loads(source.read_text())
assert len(observations) == 64
records = []
for observed in observations:
    path = pathlib.Path(observed['path']) / 'graph.callsite-string-index'
    data = path.read_bytes()
    assert len(data) == observed['indexBytes']
    assert hashlib.sha256(data).hexdigest() == observed['indexSHA256']
    magic, version, strings, calls = struct.unpack_from('>4i', data)
    unique = struct.unpack_from('>4i', data, 48)
    trigrams = struct.unpack_from('>i', data, 64)[0]
    assert magic == 0x47524353 and version == 2
    assert strings == observed['info']['StringCount'] and calls == observed['info']['CallSiteCount']
    end = 76 + sum(8 * n + 4 * calls for n in unique)
    assert end + 8 * strings + 8 * trigrams + 8 == len(data)
    crc = 0
    def words(begin, end, width):
        global crc
        for start in range(begin, end, 32768):
            values = array.array('I' if width == 4 else 'Q')
            assert values.itemsize == width
            values.frombytes(data[start:min(start + 32768, end)])
            values.byteswap()  # Reverse each stored numeric word, independent of native byte order.
            crc = zlib.crc32(values.tobytes(), crc)
    words(0, 16, 4)
    crc = zlib.crc32(data[16:48], crc)
    words(48, 68, 4)
    words(68, 76, 8)
    words(76, end, 4)
    words(end, len(data) - 8, 8)
    footer = struct.unpack_from('>Q', data, len(data) - 8)[0]
    assert crc == footer, observed['graph']
    records.append({'graph': observed['graph'], 'sha256': observed['indexSHA256'],
                    'bytes': len(data), 'typedCRC32': crc, 'footerMatches': True})
result = {'finishedAtUTC': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'sourceSHA256': hashlib.sha256(source.read_bytes()).hexdigest(),
          'scriptSHA256': hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),
          'graphs': len(records), 'bytes': sum(r['bytes'] for r in records),
          'purpose': 'Independent raw-file hash and typed-CRC audit, not a repeated Go CSR validation or benchmark.',
          'records': records}
path = pathlib.Path(__file__).with_name('real-index-verification.json')
with path.open('x') as f:
    json.dump(result, f, indent=2)
    f.write('\n')
print(f'Independent hashes and typed CRC pass: {len(records)}/64 indexes')
