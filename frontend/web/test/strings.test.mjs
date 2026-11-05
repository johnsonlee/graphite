import test from 'node:test';
import assert from 'node:assert/strict';
import { encodeArtifactString, decodeArtifactString } from '../dist/strings.js';

const prefix = '\0js:utf16:';

function roundtrip(original) {
  const encoded = encodeArtifactString(original);
  assert.equal(Buffer.from(encoded, 'utf8').toString('utf8'), encoded, 'stored value must survive UTF-8');
  assert.equal(JSON.parse(JSON.stringify(encoded)), encoded, 'interchange JSON must preserve stored value');
  assert.equal(decodeArtifactString(encoded), original, 'every original UTF-16 code unit must survive');
}

test('ordinary Unicode including surrogate pairs, NUL and combining characters is unchanged', () => {
  for (const value of ['', 'hello', '中文 한글', '\0', '\0js:utf16', 'x\0js:utf16:', 'e\u0301', 'é', '😀', '\ud800\udc00', '\udbff\udfff', 'a😀z']) {
    assert.equal(encodeArtifactString(value), value);
    roundtrip(value);
  }
});

test('lone and misordered surrogates preserve exact code units with fixed-width hexadecimal', () => {
  const cases = [
    ['\ud800', 'd800'], ['\udfff', 'dfff'], ['a\ud800z', '0061d800007a'],
    ['\udc00\ud800', 'dc00d800'], ['\ud800\ud800', 'd800d800'], ['\udc00\udc00', 'dc00dc00'],
    ['\ud800\udc00\ud800', 'd800dc00d800'], ['\udc00\ud800\udc00', 'dc00d800dc00'],
    ['\udbff\udfff\udfff', 'dbffdfffdfff'], ['\0\ud800\uffff', '0000d800ffff'],
  ];
  for (const [original, payload] of cases) {
    assert.equal(encodeArtifactString(original), prefix + payload);
    roundtrip(original);
  }
});

test('reserved-prefix originals are escaped, including strings identical to another encoded value', () => {
  const surrogate = '\ud800';
  const encodedSurrogate = prefix + 'd800';
  assert.equal(encodeArtifactString(surrogate), encodedSurrogate);
  assert.notEqual(encodeArtifactString(encodedSurrogate), encodedSurrogate);
  assert.equal(decodeArtifactString(encodeArtifactString(encodedSurrogate)), encodedSurrogate);
  for (const original of [prefix, prefix + 'ordinary', prefix + 'd800', prefix + '\ud800', prefix + prefix]) {
    assert.notEqual(encodeArtifactString(original), original);
    roundtrip(original);
  }
});

test('every individual UTF-16 code unit survives; only surrogates require encoding', () => {
  for (let unit = 0; unit <= 0xffff; unit++) {
    const original = String.fromCharCode(unit);
    const encoded = encodeArtifactString(original);
    assert.equal(encoded === original, unit < 0xd800 || unit > 0xdfff);
    roundtrip(original);
  }
});

test('deterministic arbitrary UTF-16 strings survive storage and do not collide', () => {
  let state = 0x31415926;
  const random = () => {
    state ^= state << 13;
    state ^= state >>> 17;
    state ^= state << 5;
    return state >>> 0;
  };
  const originalsByEncoding = new Map();
  for (let sample = 0; sample < 2048; sample++) {
    let original = sample % 7 === 0 ? prefix : '';
    const length = random() % 128;
    for (let index = 0; index < length; index++) original += String.fromCharCode(random() & 0xffff);
    roundtrip(original);
    const encoded = encodeArtifactString(original);
    if (originalsByEncoding.has(encoded)) assert.equal(originalsByEncoding.get(encoded), original);
    originalsByEncoding.set(encoded, original);
  }
});

test('decoder rejects truncated, nonhex and noncanonical reserved encodings', () => {
  for (const payload of ['', 'd80', 'd8000', 'zzzz', 'D800', 'd800\n', '0061', 'd800dc00']) {
    assert.throws(() => decodeArtifactString(prefix + payload), /JavaScript UTF-16 string encoding/);
  }
});
