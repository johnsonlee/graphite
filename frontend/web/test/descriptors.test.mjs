import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { analyze } from '../dist/analyzer.js';

function sourceGraph(t, source) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'graphite-descriptors-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  fs.writeFileSync(path.join(root, 'main.mjs'), source);
  return analyze(root);
}

test('fields, methods and resolved calls use the same distinct owners for anonymous classes', t => {
  const g = sourceGraph(t, [
    'const A = class { #p = 1; static s = 2; read() { return this.#p; } };',
    'const B = class { #p = 3; static s = 4; read() { return this.#p; } };',
    'class Named { #p = 5; static s = 6; read() { return this.#p; } }',
    'new A().read(); new B().read(); new Named().read();',
  ].join('\n'));
  const owners = ['main.mjs.anonymous@1:11', 'main.mjs.anonymous@2:11', 'main.mjs.Named'];
  assert.deepEqual(g.methods.filter(m => m.name === 'read').map(m => m.declaringClass), owners);
  assert.deepEqual(g.nodes.filter(n => n.callee?.name === 'read').map(n => n.callee.declaringClass), owners);
  assert.deepEqual(g.nodes.filter(n => n.kind === 'Field').map(n => [n.declaringClass, n.name, n.isStatic]),
    owners.flatMap(owner => [[owner, '#p', false], [owner, 's', true]]));
});

test('long return types match parameter and return node spelling without collapsing distinct types', t => {
  const method = (name, marker) => {
    const fields = Array.from({ length: 45 }, (_, i) => `${i === 32 ? marker : 'shared' + i}: number`).join(', ');
    return `/** @param {{${fields}}} input */\nfunction ${name}(input) { return input; }\n${name}({});`;
  };
  const g = sourceGraph(t, [method('left', 'leftMarker'), method('right', 'rightMarker')].join('\n'));
  const returnTypes = [];
  for (const [name, marker] of [['left', 'leftMarker'], ['right', 'rightMarker']]) {
    const descriptor = g.methods.find(m => m.name === name);
    assert.equal(descriptor.returnType, descriptor.parameterTypes[0]);
    assert.ok(descriptor.returnType.includes(marker));
    assert.ok(!descriptor.returnType.includes('...'));
    const param = g.nodes.find(n => n.kind === 'Parameter' && n.method.name === name);
    const ret = g.nodes.find(n => n.kind === 'Return' && n.method.name === name);
    const call = g.nodes.find(n => n.callee?.name === name);
    assert.equal(param.paramType, ret.actualType);
    assert.equal(ret.actualType, descriptor.returnType);
    assert.deepEqual(call.callee, descriptor);
    returnTypes.push(descriptor.returnType);
  }
  assert.notEqual(returnTypes[0], returnTypes[1]);
});
