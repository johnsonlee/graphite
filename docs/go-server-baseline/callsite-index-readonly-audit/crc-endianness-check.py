#!/usr/bin/env python3
"""Check main's typed-field CRC encoding on one real index, not a benchmark."""
import array, hashlib, json, pathlib, struct, sys, zlib
path = pathlib.Path(sys.argv[1])
data = path.read_bytes()
magic, version, strings, calls = struct.unpack_from('>4i', data)
assert magic == 0x47524353 and version == 2
unique = struct.unpack_from('>4i', data, 48)
trigrams = struct.unpack_from('>i', data, 64)[0]
csr_end = 76 + sum(8 * u + 4 * calls for u in unique)
assert len(data) == csr_end + 8 * strings + 8 * trigrams + 8
def reverse_fields(chunk, code, width):
    values = array.array(code)
    assert values.itemsize == width
    values.frombytes(chunk)
    values.byteswap()
    return values.tobytes()
chunks = [reverse_fields(data[:16], 'I', 4), data[16:48],
          reverse_fields(data[48:68], 'I', 4), reverse_fields(data[68:76], 'Q', 8),
          reverse_fields(data[76:csr_end], 'I', 4), reverse_fields(data[csr_end:-8], 'Q', 8)]
actual = 0
for chunk in chunks:
    actual = zlib.crc32(chunk, actual)
expected = struct.unpack_from('>Q', data, len(data) - 8)[0]
raw = zlib.crc32(data[:-8])
assert actual == expected and raw != expected
print(json.dumps({'path': str(path), 'sha256': hashlib.sha256(data).hexdigest(),
                  'bytes': len(data), 'footerCRC32': expected,
                  'typedLittleEndianCRC32': actual, 'rawFileCRC32': raw,
                  'typedEncodingMatches': True, 'rawEncodingMatches': False,
                  'scope': 'One real index checksum; no posting semantic validation or performance measurement.'}, indent=2))
