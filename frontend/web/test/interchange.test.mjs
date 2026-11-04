import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { writeInterchange } from '../dist/interchange.js';

function temp(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'graphite-interchange-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  return path.join(dir, 'graph.json');
}

function records() {
  const method = { declaringClass: 'm', name: 'call', parameterTypes: ['string'], returnType: 'void' };
  const nodes = [{ id: 0, kind: 'DoubleConstant', value: -0 }, { id: 1, kind: 'IntConstant', value: 0 },
    { id: 2, kind: 'StringConstant', value: 'abc🙂"\\\n'.repeat(32768) }];
  const edges = Array.from({ length: 2048 }, (_, i) => ({ from: i, to: i + 1, kind: 'ASSIGN' }));
  return { version: 1, methods: [method], nodes, edges, classOrigins: [{ className: 'm', origin: 'example.js' }] };
}

test('bounded short writes round-trip every array, UTF-8 data and signed zero', t => {
  const output = temp(t);
  const graph = records();
  const original = fs.writeSync;
  let short = 0;
  t.mock.method(fs, 'writeSync', (fd, buffer, offset, length) => {
    assert.ok(buffer.byteLength <= 64 * 1024);
    if (length > 1001) short++;
    return original(fd, buffer, offset, Math.min(length, 1001));
  });
  writeInterchange(output, graph);
  assert.ok(short > 5, 'the writer must continue after partial writes');
  assert.deepEqual(JSON.parse(fs.readFileSync(output,'utf8')), graph);
  assert.throws(() => writeInterchange(output, graph), { code: 'EEXIST' });
  assert.deepEqual(JSON.parse(fs.readFileSync(output,'utf8')), graph);
});

test('one large inline literal has linear encoder input and preserves pairs across windows', t => {
  const output = temp(t);
  const node = { id: 0, kind: 'StringConstant', value: '' };
  const prefix = JSON.stringify(node).indexOf('""') + 1;
  // Put the first emoji across a natural input window, then change alignment
  // repeatedly with ASCII, BMP, supplementary characters and JSON escapes.
  node.value = 'A'.repeat(Math.floor(64 * 1024 / 3) - prefix - 1) + '🙂' + 'A界🙂"\\'.repeat(256 * 1024);
  const graph = { version: 1, methods: [], nodes: [node], edges: [], classOrigins: [] };
  const expected = JSON.stringify(graph);
  let offered = 0, largest = 0;
  const encode = TextEncoder.prototype.encodeInto;
  t.mock.method(TextEncoder.prototype, 'encodeInto', function (input, destination) {
    offered += input.length;
    largest = Math.max(largest, input.length);
    return encode.call(this, input, destination);
  });
  writeInterchange(output, graph);
  assert.ok(largest <= 64 * 1024, 'the encoder never receives a record-sized suffix');
  assert.ok(offered <= 2 * expected.length, 'total input offered, including retries, is linear');
  assert.equal(fs.readFileSync(output, 'utf8'), expected);
});

test('I/O failure or no forward progress closes the file and removes partial output', t => {
  const original = fs.writeSync;
  for (const mode of ['error','zero']) {
    const output = temp(t);
    let fd, writes = 0;
    const mock = t.mock.method(fs, 'writeSync', (f, buffer, off, len) => {
      fd = f;
      if (writes++ === 0) return original(f, buffer, off, Math.min(len, 13));
      if (mode === 'zero') return 0;
      throw new Error('injected I/O failure');
    });
    assert.throws(() => writeInterchange(output, records()), mode === 'zero' ? /no progress/ : /injected I\/O failure/);
    mock.mock.restore();
    assert.ok(!fs.existsSync(output));
    assert.throws(() => fs.fstatSync(fd), { code: 'EBADF' });
    writeInterchange(output, records());
    assert.deepEqual(JSON.parse(fs.readFileSync(output,'utf8')),records());
  }
});

test('a replacement output is not removed when the original write fails', t => {
  const output = temp(t);
  const original = fs.writeSync;
  t.mock.method(fs, 'writeSync', () => {
    fs.unlinkSync(output);
    const replacement = fs.openSync(output, 'wx');
    try { original(replacement, 'replacement'); } finally { fs.closeSync(replacement); }
    throw new Error('failed original write');
  });
  assert.throws(() => writeInterchange(output, records()), /failed original write/);
  assert.equal(fs.readFileSync(output, 'utf8'), 'replacement');
});

test('an invalid numeric constant cannot silently serialize as JSON null', t => {
  const output = temp(t), graph = records();
  graph.nodes[0].value = Infinity;
  assert.throws(() => writeInterchange(output, graph), /Non-finite DoubleConstant/);
  assert.ok(!fs.existsSync(output));
});

test('overflowing emitted literals remain numeric actuals through direct calls and defaults', t => {
  const output = temp(t), input = path.join(path.dirname(output),'main.mjs');
  fs.writeFileSync(input, [
    'function take(x = -1e400) { return x; }',
    'take(1e400);',
    'take(-1e400);',
    'take();',
  ].join('\n'));
  const run = spawnSync(process.execPath, [path.resolve('dist/cli.js'), 'build', input, '--output', output], { encoding:'utf8' });
  assert.equal(run.status,0,run.stderr);
  const g = JSON.parse(fs.readFileSync(output,'utf8'));
  const formal = g.nodes.find(n => n.kind === 'Parameter' && n.method.name === 'take');
  const calls = g.nodes.filter(n => n.kind === 'CallSite' && n.callee.name === 'take');
  assert.deepEqual(calls.map(n => n.line),[2,3,4]);
  const ids = new Set();
  for (const call of calls) {
    const [arg] = call.arguments;
    ids.add(arg);
    assert.equal(g.nodes[arg].kind,'LocalVariable');
    assert.equal(g.nodes[arg].varType,'number');
    for (const target of [call.id,formal.id]) assert.ok(g.edges.some(e => e.from === arg && e.to === target && e.kind === 'PARAMETER_PASS'));
  }
  assert.equal(ids.size,3);
  assert.ok(!g.nodes.some(n => n.kind === 'NullConstant' || n.kind === 'DoubleConstant'));
});
