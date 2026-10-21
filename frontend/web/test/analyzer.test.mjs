import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { analyze } from '../dist/analyzer.js';

function fixture(t, files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'graphite-ts-test-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  for (const [name, content] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(root, name)), { recursive: true });
    fs.writeFileSync(path.join(root, name), content);
  }
  return root;
}
const edge = (g, from, to, kind) => g.edges.some(e => e.from === from && e.to === to && e.kind === kind);
const calls = g => g.nodes.filter(n => n.kind === 'CallSite');

test('resolves imported aliases and connects concrete arguments, parameters and returns', t => {
  const root = fixture(t, {
    'tsconfig.json': '{"compilerOptions":{"strict":true},"include":["src/**/*.ts"]}',
    'src/lib.ts': 'export function greet(name: string): string { return name; }',
    'src/main.ts': 'import { greet as hello } from "./lib";\nexport function run() { const result = hello("world"); return result; }',
  });
  const g = analyze(root);
  const call = calls(g).find(n => n.callee.name === 'greet');
  assert.deepEqual(call.callee, { declaringClass: 'src/lib.ts', name: 'greet', parameterTypes: ['string'], returnType: 'string' });
  assert.equal(call.caller.name, 'run');
  assert.equal(call.line, 2);
  const literal = g.nodes.find(n => n.kind === 'StringConstant' && n.value === 'world');
  assert.deepEqual(call.arguments, [literal.id]);
  const formal = g.nodes.find(n => n.kind === 'Parameter' && n.method.name === 'greet');
  const returned = g.nodes.find(n => n.kind === 'Return' && n.method.name === 'greet');
  assert.ok(edge(g, literal.id, call.id, 'PARAMETER_PASS'));
  assert.ok(edge(g, literal.id, formal.id, 'PARAMETER_PASS'));
  assert.ok(edge(g, formal.id, returned.id, 'RETURN_VALUE'));
  assert.ok(edge(g, returned.id, call.id, 'RETURN_VALUE'));
  assert.deepEqual(g, analyze(root), 'same input yields byte-stable interchange');
});

test('honors extended configuration, excludes and path alias resolution', t => {
  const root = fixture(t, {
    'base.json': '{"compilerOptions":{"baseUrl":".","paths":{"@lib":["src/lib.ts"]}},"include":["src/**/*.ts"],"exclude":["src/ignored.ts"]}',
    'tsconfig.json': '{"extends":"./base.json"}',
    'src/lib.ts': 'export const twice = (n: number): number => n * 2;',
    'src/main.ts': 'import { twice } from "@lib"; export const result = twice(4);',
    'src/ignored.ts': 'syntax error {{{',
  });
  const g = analyze(root);
  const call = calls(g).find(n => n.callee.name === 'twice');
  assert.equal(call.callee.declaringClass, 'src/lib.ts');
  assert.equal(g.nodes[call.arguments[0]].value, 4);
  assert.ok(!g.methods.some(m => m.declaringClass.includes('ignored')));
});

test('class methods, shadowed locals, closures and nested calls retain ownership', t => {
  const root = fixture(t, {
    'main.ts': `function identity(x: string) { return x; }
class Box { field = "field"; take(value: string): string { return identity(value); } }
function run() { const value = "outer"; const box = new Box();
  function nested(value: string) { return box.take(identity(value)); }
  return nested(value);
}`,
  });
  const g = analyze(root);
  const take = calls(g).find(n => n.callee.name === 'take');
  assert.equal(take.callee.declaringClass, 'main.ts.Box');
  assert.equal(take.caller.name, 'nested');
  assert.equal(g.nodes[take.arguments[0]].callee.name, 'identity');
  const nestedParameter = g.nodes.find(n => n.kind === 'Parameter' && n.method.name === 'nested');
  const inner = calls(g).find(n => n.caller.name === 'nested' && n.callee.name === 'identity');
  assert.deepEqual(inner.arguments, [nestedParameter.id]);
  const field = g.nodes.find(n => n.kind === 'Field');
  assert.equal(field.name, 'field');
  assert.equal(field.declaringClass, 'main.ts.Box');
  assert.ok(g.edges.some(e => e.to === field.id && e.kind === 'FIELD_STORE' && g.nodes[e.from].value === 'field'));
});

test('JS and TSX sources record calls; unresolved dependencies are explicit', t => {
  const root = fixture(t, {
    'tsconfig.json': '{"compilerOptions":{"allowJs":true,"jsx":"preserve"},"include":["*.js","*.tsx"]}',
    'app.tsx': 'import { send } from "missing-library"; export const App = () => <div>{send("tsx")}</div>;',
    'plain.js': 'export function run() { console.log("javascript"); }',
  });
  const warnings = [];
  const g = analyze(root, m => warnings.push(m));
  assert.ok(warnings.some(m => m.includes('semantic diagnostic')));
  const send = calls(g).find(n => n.callee.name === 'send');
  assert.equal(send.callee.declaringClass, '<dynamic>');
  assert.equal(g.nodes[send.arguments[0]].value, 'tsx');
  assert.equal(calls(g).find(n => n.callee.name === 'log').caller.name, 'run');
});

test('syntax/config errors and unsupported project references fail instead of saving partial graphs', t => {
  const root = fixture(t, { 'bad.ts': 'function broken( {' });
  assert.throws(() => analyze(root), /error TS/);
  fs.writeFileSync(path.join(root, 'tsconfig.json'), '{ bad json');
  assert.throws(() => analyze(root), /error TS/);
  fs.writeFileSync(path.join(root, 'tsconfig.json'), '{"files":["bad.ts"],"references":[{"path":"./sub"}]}');
  assert.throws(() => analyze(root), /Project references/);
});

test('frontend CLI preserves an existing output and refuses unsupported options', t => {
  const root = fixture(t, { 'main.ts': 'console.log("hello")', 'output.json': 'preserve' });
  const cli = path.resolve('dist/cli.js');
  let result = spawnSync(process.execPath, [cli, 'build', path.join(root, 'main.ts'), '-o', path.join(root, 'output.json')], { encoding: 'utf8' });
  assert.equal(result.status, 1);
  assert.equal(fs.readFileSync(path.join(root, 'output.json'), 'utf8'), 'preserve');
  result = spawnSync(process.execPath, [cli, 'build', root, '--fold', 'x', '-o', path.join(root, 'new.json')], { encoding: 'utf8' });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Unsupported TypeScript frontend option: --fold/);
  assert.ok(!fs.existsSync(path.join(root, 'new.json')));
});

test('object and class arrow methods have distinct persisted identities', t => {
  const root = fixture(t, { 'main.ts': `
const a = { run() { return 'a'; } }; const b = { run() { return 'b'; } }; a.run(); b.run();
class A { run = () => 'A'; } class B { run = () => 'B'; }
new A().run(); new B().run();
` });
  const g = analyze(root);
  const targets = calls(g).filter(n => n.callee.name === 'run').map(n => n.callee.declaringClass).sort();
  assert.deepEqual(targets, ['main.ts.A', 'main.ts.B', 'main.ts.a', 'main.ts.b']);
  assert.equal(new Set(g.methods.map(m => JSON.stringify(m))).size, g.methods.length);
});

test('compound assignments, default arguments and negative constants retain source flow', t => {
  const root = fixture(t, { 'main.ts': `
function source() { return -42; }
function use(value = source()) { return value; }
let x = 0; x += source(); console.log(x, -42); use();
` });
  const g = analyze(root);
  const sourceCalls = calls(g).filter(n => n.callee.name === 'source');
  assert.equal(sourceCalls.length, 2);
  const sink = calls(g).find(n => n.callee.name === 'log');
  assert.equal(g.nodes[sink.arguments[1]].value, -42);
  const topLevelSource = sourceCalls.find(n => n.caller.name === '<module>');
  const reaches = (from, to, seen = new Set()) => from === to || (!seen.has(from) && (seen.add(from), g.edges.filter(e => e.from === from && e.kind !== 'CALL').some(e => reaches(e.to, to, seen))));
  assert.ok(reaches(topLevelSource.id, sink.id));
  const defaultCall = sourceCalls.find(n => n.caller.name === 'use');
  const parameter = g.nodes.find(n => n.kind === 'Parameter' && n.method.name === 'use');
  assert.ok(edge(g, defaultCall.id, parameter.id, 'ASSIGN'));
});

test('library declaration identities do not expose frontend installation paths', t => {
  const root = fixture(t, { 'main.ts': 'const map = new Map<string,string>(); map.get("x");' });
  const g = analyze(root);
  const get = calls(g).find(n => n.callee.name === 'get');
  assert.equal(get.callee.declaringClass, 'external:typescript/lib/lib.es2015.collection.d.ts.Map');
  assert.ok(!JSON.stringify(g).includes(process.cwd()));
});

test('sibling blocks, rest arguments and iterable bindings preserve distinct identities and flow', t => {
  const root = fixture(t, { 'main.ts': `
{ const f = () => 'one'; f(); } { const f = () => 2; f(); }
function forward(...values: string[]) { for (const value of values) console.log(value); }
forward('first', 'second');
` });
  const g = analyze(root);
  const sameNames = calls(g).filter(n => n.callee.name === 'f');
  assert.equal(sameNames.length, 2);
  assert.notEqual(sameNames[0].callee.declaringClass, sameNames[1].callee.declaringClass);
  const formal = g.nodes.find(n => n.kind === 'Parameter' && n.method.name === 'forward');
  const literal = g.nodes.find(n => n.kind === 'StringConstant' && n.value === 'second');
  assert.ok(edge(g, literal.id, formal.id, 'PARAMETER_PASS'));
  const sink = calls(g).find(n => n.callee.name === 'log');
  assert.ok(edge(g, formal.id, sink.arguments[0], 'ARRAY_LOAD'));
});
