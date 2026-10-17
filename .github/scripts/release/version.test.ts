import assert from 'node:assert/strict';
import { test } from 'node:test';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { main, parseVersion } from './version.ts';

test('strict SemVer preserves version text and classifies only the prerelease part', () => {
  for (const [tag, version, prerelease] of [
    ['v3.0.0', '3.0.0', false],
    ['v3.0.0-alpha.1', '3.0.0-alpha.1', true],
    ['v3.0.0-rc.1+build.5', '3.0.0-rc.1+build.5', true],
    ['v3.0.0+linux-1', '3.0.0+linux-1', false],
    ['v0.0.1', '0.0.1', false],
    ['v10.20.30-0.3.7', '10.20.30-0.3.7', true],
    ['v999999999999999999999.0.0+001', '999999999999999999999.0.0+001', false],
    ['v1.0.0-01a', '1.0.0-01a', true],
  ] as const) assert.deepEqual(parseVersion(tag), { version, prerelease });
});

test('malformed, non-ASCII and trailing-line-break tags cannot produce workflow outputs', () => {
  for (const tag of ['', 'v', 'vfoo', 'vv3.0.0', '3.0.0', 'v3.0', 'v03.0.0', 'v3.00.0',
    'v3.0.0-', 'v3.0.0-01', 'v3.0.0+', 'v3.0.0-rc.1 ', 'V3.0.0', 'v3.0.0.1',
    'v3.0.0\n', 'v3.0.0\r\n', 'v3.٠.0', 'v3.0.0-rc..1', 'v3.0.0+build..1',
    'v3.0.0\nprerelease=false']) assert.equal(parseVersion(tag), null, tag);
});

test('argument failures do not emit partial output', () => {
  for (const [argv, code] of [[[], 2], [['v1.0.0', 'extra'], 2], [['--self-test'], 1]] as const) {
    const output: string[] = [], errors: string[] = [];
    assert.equal(main(argv, line => output.push(line), line => errors.push(line)), code);
    assert.deepEqual(output, []);
    assert.equal(errors.length, 1);
  }
});

test('Bun CLI emits exactly the workflow key/value contract and a failing status for invalid tags', () => {
  const entry = fileURLToPath(new URL('./version.ts', import.meta.url));
  const good = spawnSync(process.execPath, [entry, 'v3.0.0+linux-1'], { encoding: 'utf8' });
  assert.equal(good.status, 0, good.stderr);
  assert.equal(good.stdout, 'version=3.0.0+linux-1\nprerelease=false\n');
  const bad = spawnSync(process.execPath, [entry, 'v3.0.0\n'], { encoding: 'utf8' });
  assert.equal(bad.status, 1);
  assert.equal(bad.stdout, '');
});
