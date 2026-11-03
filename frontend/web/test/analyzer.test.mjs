import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import ts from 'typescript';
import { analyze, project } from '../dist/analyzer.js';

function fixture(t, files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'graphite-js-test-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  for (const [name, content] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(root, name)), { recursive: true });
    fs.writeFileSync(path.join(root, name), content);
  }
  return root;
}
const edge = (g, from, to, kind) => g.edges.some(e => e.from === from && e.to === to && e.kind === kind);
const calls = g => g.nodes.filter(n => n.kind === 'CallSite');
const argumentsOf = (g, call) => call.arguments.map(id => g.nodes[id]);
const formal = (g, name, index = 0) => g.nodes.find(n => n.kind === 'Parameter' && n.method.name === name && n.index === index);

test('emitted ESM imports retain concrete arguments, parameters, returns and artifact locations', t => {
  const root = fixture(t, {
    'lib.mjs': 'export function greet(name) { return name; }',
    'main.mjs': 'import { greet as hello } from "./lib.mjs";\nexport function run() { return hello("world"); }',
  });
  const g = analyze(root);
  const call = calls(g).find(n => n.callee.name === 'greet');
  assert.equal(call.callee.declaringClass, 'lib.mjs');
  assert.equal(call.caller.name, 'run');
  assert.equal(call.line, 2);
  const literal = g.nodes.find(n => n.kind === 'StringConstant' && n.value === 'world');
  assert.deepEqual(call.arguments, [literal.id]);
  const returned = g.nodes.find(n => n.kind === 'Return' && n.method.name === 'greet');
  assert.ok(edge(g, literal.id, call.id, 'PARAMETER_PASS'));
  assert.ok(edge(g, literal.id, formal(g, 'greet').id, 'PARAMETER_PASS'));
  assert.ok(edge(g, formal(g, 'greet').id, returned.id, 'RETURN_VALUE'));
  assert.ok(edge(g, returned.id, call.id, 'RETURN_VALUE'));
  assert.deepEqual(g, analyze(root));
});

test('native paths resolve nested emitted imports without admitting files outside the artifact root', t => {
  const root = fixture(t, {
    'emitted/lib/helper.mjs': 'export function helper(value) { return value; }',
    'emitted/nested/app.mjs': 'import { helper } from "../lib/helper.mjs"; import { outside } from "../../outside.mjs"; helper("inside"); outside();',
    'outside.mjs': 'export function outside() { return "not selected"; }',
  });
  const g = analyze(path.join(root, 'emitted'));
  const linked = calls(g).find(n => n.callee.name === 'helper');
  assert.equal(linked.caller.declaringClass, 'nested/app.mjs');
  assert.equal(linked.callee.declaringClass, 'lib/helper.mjs');
  assert.ok(edge(g, linked.arguments[0], formal(g, 'helper').id, 'PARAMETER_PASS'));
  const dynamic = calls(g).find(n => n.callee.name === 'outside');
  assert.equal(dynamic.callee.declaringClass, '<dynamic>');
  assert.deepEqual(g.classOrigins.map(n => n.origin).sort(), ['lib/helper.mjs', 'nested/app.mjs']);
});

test('reads emitted cache routes and modules but skips the Next compiler cache and dependencies', t => {
  const root = fixture(t, {
    'tsconfig.json': '{ invalid config: must not be consulted',
    'src/source.ts': 'invalid source {{{',
    '.next/server/app.js': 'function server() { return database("task"); }',
    '.next/static/chunks/app.js': 'function client() { return fetch("/api/tasks"); }',
    '.next/cache/broken.js': 'invalid cached file {{{',
    '.next/server/app/cache/page.js': 'function cachePage() { return renderPage(); }',
    'dist/cache/index.js': 'function cached() { return readCache(); }',
    'dist/lib.cjs': 'function library() { return console.log("built"); }',
    'build/nested/lib.mjs': 'export function nested() { return helper("nested"); }',
    'node_modules/broken.js': 'invalid dependency {{{',
  });
  const g = analyze(root);
  assert.deepEqual(calls(g).map(c => c.callee.name).sort(), ['database', 'fetch', 'helper', 'log', 'readCache', 'renderPage']);
  assert.deepEqual(g.classOrigins.map(c => c.origin).sort(), ['.next/server/app.js', '.next/server/app/cache/page.js', '.next/static/chunks/app.js', 'build/nested/lib.mjs', 'dist/cache/index.js', 'dist/lib.cjs']);
});

test('analyzes actual TypeScript/JSX compiler output, not erased declarations or JSX source', t => {
  const emitted = ts.transpileModule('type Name = string; function send(name: Name) { return name; } export const App = () => <div>{send("tsx")}</div>;', {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext, jsx: ts.JsxEmit.ReactJSX }, fileName: 'app.tsx',
  }).outputText;
  const root = fixture(t, { 'app.js': emitted, 'app.tsx': 'this source is deliberately invalid {{{' });
  const g = analyze(root);
  const send = calls(g).find(c => c.callee.name === 'send');
  assert.equal(send.callee.declaringClass, 'app.js');
  assert.equal(argumentsOf(g, send)[0].value, 'tsx');
  assert.ok(calls(g).some(c => c.callee.name === '_jsx'));
  assert.ok(!JSON.stringify(g).includes('app.tsx'));
});

test('source inputs and malformed JavaScript fail without a partial graph', t => {
  const root = fixture(t, { 'bad.js': 'function broken( {', 'main.ts': 'export const n = 1;', 'tsconfig.json': '{}' });
  assert.throws(() => analyze(root), /error TS/);
  assert.throws(() => analyze(path.join(root, 'main.ts')), /Compile/);
  assert.throws(() => analyze(path.join(root, 'tsconfig.json')), /Compile/);
  const empty = fixture(t, { 'main.tsx': '<div />' });
  assert.throws(() => analyze(empty), /No compiled JavaScript/);
});

test('class methods, closures and nested calls retain ownership', t => {
  const root = fixture(t, { 'main.js': `function identity(x) { return x; }
class Box { field = "field"; take(value) { return identity(value); } }
function run() { const value = "outer"; const box = new Box();
  function nested(value) { return box.take(identity(value)); }
  return nested(value);
}` });
  const g = analyze(root);
  const take = calls(g).find(n => n.callee.name === 'take');
  assert.equal(take.callee.declaringClass, 'main.js.Box');
  assert.equal(take.caller.name, 'nested');
  assert.equal(g.nodes[take.arguments[0]].callee.name, 'identity');
  const inner = calls(g).find(n => n.caller.name === 'nested' && n.callee.name === 'identity');
  assert.deepEqual(inner.arguments, [formal(g, 'nested').id]);
  const field = g.nodes.find(n => n.kind === 'Field');
  assert.equal(field.declaringClass, 'main.js.Box');
  assert.ok(g.edges.some(e => e.to === field.id && e.kind === 'FIELD_STORE' && g.nodes[e.from].value === 'field'));
});

test('missing/undefined arguments become explicit default actuals; null and supplied values remain', t => {
  const root = fixture(t, { 'main.js': `
function identity(value = "shared") { return value; }
identity(); identity(undefined); identity(void 0); identity("shared"); identity(null); identity(0); identity(false); identity("");
` });
  const g = analyze(root);
  const invocations = calls(g).filter(c => c.callee.name === 'identity');
  assert.equal(invocations.length, 8);
  assert.deepEqual(invocations.slice(0, 4).map(c => argumentsOf(g, c)[0].value), ['shared', 'shared', 'shared', 'shared']);
  assert.equal(argumentsOf(g, invocations[4])[0].kind, 'NullConstant');
  assert.deepEqual(invocations.slice(5).map(c => argumentsOf(g, c)[0].value), [0, false, '']);
  const shared = g.nodes.find(n => n.kind === 'StringConstant' && n.value === 'shared');
  assert.deepEqual(g.edges.filter(e => e.from === shared.id && e.to === formal(g, 'identity').id).map(e => e.kind), ['PARAMETER_PASS']);
  assert.deepEqual(g, analyze(root));
});

test('defaults refer to earlier normalized arguments and evaluate calls per invocation', t => {
  const root = fixture(t, { 'main.js': `
function fresh() { return Math.random(); }
function pair(first, second = first, third = fresh()) { return [first, second, third]; }
pair("left"); pair("right"); pair("skip", 1, 2);
` });
  const g = analyze(root);
  const invocations = calls(g).filter(c => c.callee.name === 'pair');
  assert.deepEqual(invocations.slice(0, 2).map(c => argumentsOf(g, c).slice(0, 2).map(n => n.value)), [['left', 'left'], ['right', 'right']]);
  const defaults = calls(g).filter(c => c.callee.name === 'fresh');
  assert.equal(defaults.length, 2, 'each missing default evaluates separately; supplied third arg does not');
  assert.notEqual(invocations[0].arguments[2], invocations[1].arguments[2]);
  assert.deepEqual(invocations.slice(0, 2).map(c => c.arguments[2]), defaults.map(c => c.id));
  for (const call of defaults) assert.ok(edge(g, call.id, formal(g, 'pair', 2).id, 'PARAMETER_PASS'));
});

test('unknown values select between supplied and default; shadowed undefined is an ordinary value', t => {
  const root = fixture(t, { 'main.js': `
function identity(value = "fallback") { return value; }
function forward(value) { return identity(value); }
function shadow(undefined) { return identity(undefined); }
forward(unknown()); shadow("provided");
` });
  const g = analyze(root);
  const fallback = g.nodes.find(n => n.kind === 'StringConstant' && n.value === 'fallback');
  for (const name of ['forward', 'shadow']) {
    const call = calls(g).find(c => c.caller.name === name && c.callee.name === 'identity');
    const join = argumentsOf(g, call)[0];
    assert.match(join.name, /undefined-select/);
    assert.ok(edge(g, formal(g, name).id, join.id, 'ASSIGN'));
    assert.ok(edge(g, fallback.id, join.id, 'ASSIGN'));
    assert.ok(edge(g, join.id, formal(g, 'identity').id, 'PARAMETER_PASS'));
  }
});

test('reads of reassigned bindings retain supported actual and default flows in both directions', t => {
  const g = analyze(fixture(t, { 'main.js': `
function f(a=42) { return a; }
function fromVoid() { let x=void 0; x=5; return f(x); }
function fromUndefined() { let x=undefined; x=5; return f(x); }
function toVoid() { let x=5; x=void 0; return f(x); }
function toUndefined() { let x=5; x=undefined; return f(x); }
` }));
  const five = g.nodes.find(n => n.kind === 'IntConstant' && n.value === 5);
  const fallback = g.nodes.find(n => n.kind === 'IntConstant' && n.value === 42);
  for (const name of ['fromVoid', 'fromUndefined', 'toVoid', 'toUndefined']) {
    const call = calls(g).find(n => n.caller.name === name && n.callee.name === 'f');
    const binding = g.nodes.find(n => n.name === 'x' && n.method.name === name);
    const [actual] = argumentsOf(g, call);
    assert.ok(edge(g, five.id, binding.id, 'ASSIGN'));
    if (name === 'fromUndefined') {
      // Use-site number agrees with the write of 5; no default is needed.
      assert.equal(actual.id, binding.id);
    } else {
      // The checker retains the initializer type at the use despite an incompatible write.
      assert.ok(edge(g, binding.id, actual.id, 'ASSIGN'));
      assert.ok(edge(g, fallback.id, actual.id, 'ASSIGN'));
    }
    assert.ok(edge(g, actual.id, call.id, 'PARAMETER_PASS'));
    assert.ok(edge(g, actual.id, formal(g, 'f').id, 'PARAMETER_PASS'));
  }
});

test('a declaration alias retains conflicting writes to the underlying binding', t => {
  const g = analyze(fixture(t, { 'main.js': `
function f(a=42) { return a; }
function fromVoid() { let x=void 0; x=5; const y=x; return f(y); }
function toVoid() { let x=5; x=void 0; const y=x; return f(y); }
` }));
  const five = g.nodes.find(n => n.kind === 'IntConstant' && n.value === 5);
  const fallback = g.nodes.find(n => n.kind === 'IntConstant' && n.value === 42);
  for (const name of ['fromVoid', 'toVoid']) {
    const binding = id => g.nodes.find(n => n.name === id && n.method.name === name);
    const call = calls(g).find(n => n.caller.name === name && n.callee.name === 'f');
    const [actual] = argumentsOf(g, call);
    assert.ok(edge(g, five.id, binding('x').id, 'ASSIGN'));
    assert.ok(edge(g, binding('x').id, binding('y').id, 'ASSIGN'));
    assert.ok(edge(g, binding('y').id, actual.id, 'ASSIGN'));
    assert.ok(edge(g, fallback.id, actual.id, 'ASSIGN'));
    assert.ok(edge(g, actual.id, call.id, 'PARAMETER_PASS'));
    assert.ok(edge(g, actual.id, formal(g, 'f').id, 'PARAMETER_PASS'));
  }
});

test('parenthesized, destructuring and iteration writes reach the supplied argument and its formal', async t => {
  const root = fixture(t, { 'main.mjs': `
function f(a=42) { return a; }
function parens() { let x=void 0; (x)=5; return f(x); }
function array() { let x=void 0; [x]=[5]; return f(x); }
function object() { let x=void 0; ({v:x}={v:5}); return f(x); }
function shorthand() { let x=void 0; ({x}={x:5}); return f(x); }
function values() { let x=void 0; for (x of [5]) return f(x); }
function keys() { let x=void 0; for (x in {key:5}) return f(x); }
export const outputs=[parens(),array(),object(),shorthand(),values(),keys()];
` });
  const runtime = await import(pathToFileURL(path.join(root, 'main.mjs')).href);
  assert.deepEqual(runtime.outputs, [5,5,5,5,5,'key']);
  const g = analyze(root);
  const five = g.nodes.find(n => n.kind === 'IntConstant' && n.value === 5);
  for (const name of ['parens', 'array', 'object', 'shorthand', 'values', 'keys']) {
    const binding = g.nodes.find(n => n.name === 'x' && n.method.name === name);
    const call = calls(g).find(n => n.caller.name === name && n.callee.name === 'f');
    const [actual] = argumentsOf(g, call);
    const incoming = g.edges.filter(e => e.to === binding.id && ['ASSIGN','ARRAY_LOAD'].includes(e.kind));
    if (name === 'parens') assert.ok(edge(g, five.id, binding.id, 'ASSIGN'));
    else {
      // The aggregate/iterable is a dependency of the binding, not necessarily the item itself.
      assert.ok(incoming.some(e => g.nodes[e.from].name?.startsWith('@') && edge(g, five.id, e.from, 'ASSIGN')));
    }
    assert.ok(actual.id === binding.id || edge(g, binding.id, actual.id, 'ASSIGN'));
    assert.ok(edge(g, actual.id, call.id, 'PARAMETER_PASS'));
    assert.ok(edge(g, actual.id, formal(g, 'f').id, 'PARAMETER_PASS'));
  }
});

test('nested defaults use per-call argument state, including a later write to an earlier argument', async t => {
  const root = fixture(t, { 'main.mjs': `
function pick(x=42) { return x; }
/** @param {number} a */
function relay(a,b=pick(a)) { return b; }
function update(a,b=(a=void 0),c=pick(a)) { return c; }
const u=void 0; export const results=[relay(u),relay(7),update(5)];
` });
  const runtime = await import(pathToFileURL(path.join(root, 'main.mjs')).href);
  assert.deepEqual(runtime.results, [42,7,42]);
  const g = analyze(root);
  const nested = calls(g).filter(n => n.callee.name === 'pick');
  assert.deepEqual(nested.map(n => [n.caller.name, argumentsOf(g, n)[0].value]), [['relay',42],['relay',7],['update',42]]);
  for (const n of nested) {
    const [value] = argumentsOf(g, n);
    assert.ok(edge(g, value.id, n.id, 'PARAMETER_PASS'));
    assert.ok(edge(g, value.id, formal(g, 'pick').id, 'PARAMETER_PASS'));
  }
});

// Trace concrete incoming data only; procedure return/call edges cannot manufacture an argument dependency.
function flowsTo(g, from, to) {
  const seen = new Set([from]);
  for (const id of seen) {
    if (id === to) return true;
    for (const e of g.edges) if (e.from === id && ['ASSIGN','FIELD_STORE','FIELD_LOAD','ARRAY_LOAD','PARAMETER_PASS'].includes(e.kind)) seen.add(e.to);
  }
  return false;
}

test('an explicitly written property and its alias retain supplied and default alternatives', async t => {
  const root = fixture(t, { 'main.mjs': `
function pick(a=42) { return a; }
function a() { const o={x:5}; o.x=void 0; return pick(o.x); }
function b() { const o={x:void 0}; o.x=5; return pick(o.x); }
function c() { const o={x:5}; o.x=void 0; const y=o.x; return pick(y); }
function d() { const o={x:void 0}; o.x=5; const y=o.x; return pick(y); }
export const results=[a(),b(),c(),d()];
` });
  const runtime = await import(pathToFileURL(path.join(root, 'main.mjs')).href);
  assert.deepEqual(runtime.results, [42,5,42,5]);
  const g = analyze(root);
  const fallback = g.nodes.find(n => n.kind === 'IntConstant' && n.value === 42);
  const writes = calls(g).filter(n => n.callee.name === 'pick');
  assert.equal(writes.length, 4);
  for (const call of writes) {
    const [actual] = argumentsOf(g, call);
    const read = g.nodes.find(n => n.method?.name === call.caller.name && n.name?.startsWith('@read:o.x'));
    assert.ok(flowsTo(g, read.id, actual.id));
    assert.ok(edge(g, fallback.id, actual.id, 'ASSIGN'));
    assert.ok(edge(g, actual.id, call.id, 'PARAMETER_PASS'));
    assert.ok(edge(g, actual.id, formal(g, 'pick').id, 'PARAMETER_PASS'));
  }
});

test('a conditional default preserves both states of every earlier argument it can write', async t => {
  const root = fixture(t, { 'main.mjs': `
function pick(x=42) { return x; }
function relay(a,b=(a=void 0),c=pick(a)) { return c; }
function outer(maybe) { return relay(7,maybe); }
function replaced(a,b=(a=7),c=a) { return [a,c]; }
function nested(maybe) { return replaced(1,maybe); }
export const results=[outer(1),outer(undefined),nested(8),nested(undefined)];
` });
  const runtime = await import(pathToFileURL(path.join(root, 'main.mjs')).href);
  assert.deepEqual(runtime.results, [7,42,[1,1],[7,7]]);
  const g = analyze(root);
  const literal = n => g.nodes.find(v => v.kind === 'IntConstant' && v.value === n).id;
  const relay = calls(g).find(n => n.callee.name === 'relay');
  const pick = calls(g).find(n => n.callee.name === 'pick');
  const undef = g.nodes.find(n => n.method?.name === 'relay' && n.varType === 'undefined' && n.name?.startsWith('@'));
  assert.ok(flowsTo(g, undef.id, relay.arguments[0]) && flowsTo(g, literal(7), relay.arguments[0]));
  assert.ok(flowsTo(g, literal(7), pick.arguments[0]) && flowsTo(g, literal(42), pick.arguments[0]));
  assert.ok(edge(g, pick.arguments[0], formal(g, 'pick').id, 'PARAMETER_PASS'));
  const replaced = calls(g).find(n => n.callee.name === 'replaced');
  assert.equal(replaced.arguments[0], replaced.arguments[2]);
  for (const slot of [0,2]) {
    const actual = replaced.arguments[slot];
    assert.ok(flowsTo(g, literal(1), actual) && flowsTo(g, literal(7), actual));
    assert.ok(edge(g, actual, replaced.id, 'PARAMETER_PASS'));
    assert.ok(edge(g, actual, formal(g, 'replaced', slot).id, 'PARAMETER_PASS'));
  }
});

test('argument snapshots, assignment results and pattern defaults retain earlier reads', async t => {
  const root = fixture(t, { 'main.mjs': `
function pick(x=42) { return x; }
/** @param {number} a */
function snapshot(a,b=pick(a,a=5)) { return b; }
/** @param {number} a */
function assigned(a,b=(a=a),c=pick(b)) { return c; }
/** @param {number} a */
function comma(a,b=(0,a),c=pick(b)) { return c; }
function unpack(a,b=([a=42]=[7]),c=pick(a)) { return c; }
function short(a,b=({a=7}={}),c=pick(a)) { return c; }
const u=void 0; export const results=[snapshot(u),assigned(u),comma(u),unpack(0),short(0)];
` });
  const runtime = await import(pathToFileURL(path.join(root, 'main.mjs')).href);
  assert.deepEqual(runtime.results, [42,42,42,7,7]);
  const g = analyze(root);
  for (const name of ['snapshot','assigned','comma','unpack','short']) {
    const call = calls(g).find(n => n.caller.name === name && n.callee.name === 'pick');
    const [actual] = argumentsOf(g, call);
    if (['unpack','short'].includes(name)) {
      const seven = g.nodes.find(n => n.kind === 'IntConstant' && n.value === 7);
      assert.ok(flowsTo(g, seven.id, actual.id));
    } else assert.equal(actual.value, 42);
    assert.ok(edge(g, actual.id, call.id, 'PARAMETER_PASS'));
    assert.ok(edge(g, actual.id, formal(g, 'pick').id, 'PARAMETER_PASS'));
  }
});

test('void-typed calls keep both possible inputs; known earlier arguments keep their precision', async t => {
  const root = fixture(t, { 'main.mjs': `
function f(a=42) { return a; }
function noop() {}
/** @type {() => void} */ const opaque = () => 7;
export const absent = f(noop());
export const supplied = f(opaque());
function bridge(a, b=f(a)) { return b; }
bridge(); bridge(void 0); bridge(7);
` });
  // Execute only this fixed test module to independently verify both runtime outcomes.
  const runtime = await import(pathToFileURL(path.join(root, 'main.mjs')).href);
  assert.deepEqual([runtime.absent, runtime.supplied], [42, 7]);
  const g = analyze(root);
  const fallback = g.nodes.find(n => n.kind === 'IntConstant' && n.value === 42);
  const top = calls(g).filter(n => n.caller.name === '<module>' && n.callee.name === 'f');
  assert.equal(top.length, 2);
  for (const outer of top) {
    const nested = calls(g).filter(n => n.line === outer.line && n.id !== outer.id);
    assert.equal(nested.length, 1);
    const [supplied] = nested;
    const [actual] = argumentsOf(g, outer);
    assert.ok(edge(g, supplied.id, actual.id, 'ASSIGN'));
    assert.ok(edge(g, fallback.id, actual.id, 'ASSIGN'));
    assert.ok(edge(g, actual.id, outer.id, 'PARAMETER_PASS'));
    assert.ok(edge(g, actual.id, formal(g, 'f').id, 'PARAMETER_PASS'));
  }
  const nested = calls(g).filter(n => n.caller.name === 'bridge' && n.callee.name === 'f');
  assert.deepEqual(nested.map(c => argumentsOf(g, c)[0].value), [42, 42, 7]);
});

test('void expressions keep effects even when their value triggers a default', t => {
  const root = fixture(t, { 'main.js': 'function use(x="default") { return x; } use(void effect());' });
  const g = analyze(root);
  assert.equal(calls(g).filter(c => c.callee.name === 'effect').length, 1);
  assert.equal(argumentsOf(g, calls(g).find(c => c.callee.name === 'use'))[0].value, 'default');
});

test('CLI builds emitted JavaScript with type errors and leaves unresolved calls dynamic', t => {
  const root = fixture(t, { 'main.js': '// @ts-check\n/** @type {number} */ const n = "artifact"; missing(n);' });
  const output = path.join(root, 'graph.json');
  const cli = path.resolve('dist/cli.js');
  const result = spawnSync(process.execPath, [cli, 'build', root, '-o', output], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  assert.doesNotMatch(result.stderr, /semantic diagnostic|error TS2322/);
  const graph = JSON.parse(fs.readFileSync(output, 'utf8'));
  const missing = calls(graph).find(n => n.callee.name === 'missing');
  assert.ok(graph.edges.some(e => e.from === missing.id && e.kind === 'CALL' && e.isDynamic));
});

test('literal spread defaults and rest arguments retain explicit parameter flow', t => {
  const root = fixture(t, { 'main.js': `
function identity(value = "shared") { return value; } identity(...[]); identity(...[undefined]);
function forward(...values) { for (const value of values) console.log(value); } forward('first', 'second');
function split(head, ...rest) {} split('head', 'tail1', 'tail2');
` });
  const g = analyze(root);
  assert.deepEqual(calls(g).filter(c => c.callee.name === 'identity').map(c => argumentsOf(g, c)[0].value), ['shared', 'shared']);
  const literal = g.nodes.find(n => n.kind === 'StringConstant' && n.value === 'second');
  assert.ok(edge(g, literal.id, formal(g, 'forward').id, 'PARAMETER_PASS'));
  const sink = calls(g).find(n => n.callee.name === 'log');
  assert.ok(edge(g, formal(g, 'forward').id, sink.arguments[0], 'ARRAY_LOAD'));
  const split = calls(g).find(n => n.callee.name === 'split');
  assert.ok(edge(g, split.arguments[0], formal(g, 'split').id, 'PARAMETER_PASS'));
  for (const tail of split.arguments.slice(1)) assert.ok(edge(g, tail, formal(g, 'split', 1).id, 'PARAMETER_PASS'));
});

test('object methods, class arrows and sibling blocks retain distinct identities', t => {
  const root = fixture(t, { 'main.js': `
const a = { run() { return 'a'; } }; const b = { run() { return 'b'; } }; a.run(); b.run();
class A { run = () => 'A'; } class B { run = () => 'B'; } new A().run(); new B().run();
{ const f = () => 'one'; f(); } { const f = () => 2; f(); }
` });
  const g = analyze(root);
  assert.deepEqual(calls(g).filter(n => n.callee.name === 'run').map(n => n.callee.declaringClass).sort(), ['main.js.A', 'main.js.B', 'main.js.a', 'main.js.b']);
  const sameNames = calls(g).filter(n => n.callee.name === 'f');
  assert.notEqual(sameNames[0].callee.declaringClass, sameNames[1].callee.declaringClass);
});

test('compound assignments preserve dependency paths', t => {
  const root = fixture(t, { 'main.js': 'function source(){return -42} let x=0; x+=source(); console.log(x,-42);' });
  const g = analyze(root);
  const source = calls(g).find(c => c.callee.name === 'source');
  const sink = calls(g).find(c => c.callee.name === 'log');
  assert.equal(argumentsOf(g, sink)[1].value, -42);
  const reaches = (from, to, seen = new Set()) => from === to || (!seen.has(from) && (seen.add(from), g.edges.filter(e => e.from === from && e.kind !== 'CALL').some(e => reaches(e.to, to, seen))));
  assert.ok(reaches(source.id, sink.id));
});

test('frontend CLI preserves an existing output and refuses unsupported options', t => {
  const root = fixture(t, { 'main.js': 'console.log("hello")', 'output.json': 'preserve' });
  const cli = path.resolve('dist/cli.js');
  let result = spawnSync(process.execPath, [cli, 'build', path.join(root, 'main.js'), '-o', path.join(root, 'output.json')], { encoding: 'utf8' });
  assert.equal(result.status, 1);
  assert.equal(fs.readFileSync(path.join(root, 'output.json'), 'utf8'), 'preserve');
  result = spawnSync(process.execPath, [cli, 'build', root, '--fold', 'x', '-o', path.join(root, 'new.json')], { encoding: 'utf8' });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Unsupported .* frontend option: --fold/);
  assert.ok(!fs.existsSync(path.join(root, 'new.json')));
});

test('compiler-lowered ES5 defaults are lifted to explicit actuals', t => {
  const emitted = ts.transpileModule('function pick(a: string = "first", b = a) { return b; } pick(); pick(undefined); pick(null as any);', {
    compilerOptions: { target: ts.ScriptTarget.ES5, module: ts.ModuleKind.CommonJS },
  }).outputText;
  assert.match(emitted, /if \(a === void 0\)/);
  const root = fixture(t, { 'main.js': emitted });
  const g = analyze(root);
  const invocations = calls(g).filter(c => c.callee.name === 'pick');
  assert.deepEqual(invocations.slice(0, 2).map(c => argumentsOf(g, c).map(n => n.value)), [['first', 'first'], ['first', 'first']]);
  assert.deepEqual(argumentsOf(g, invocations[2]).map(n => n.kind), ['NullConstant', 'NullConstant']);
  const first = g.nodes.find(n => n.kind === 'StringConstant' && n.value === 'first');
  assert.deepEqual(g.edges.filter(e => e.from === first.id && e.to === formal(g, 'pick').id).map(e => e.kind), ['PARAMETER_PASS']);
});

test('ordinary nullish guards are not confused with default parameter lowering', t => {
  const root = fixture(t, { 'main.js': 'function pick(a) { if (a == undefined) { a="nullish"; } return a; } pick(null);' });
  const g = analyze(root);
  assert.equal(argumentsOf(g, calls(g).find(c => c.callee.name === 'pick'))[0].kind, 'NullConstant');
  const value = g.nodes.find(n => n.kind === 'StringConstant' && n.value === 'nullish');
  const binding = g.nodes.find(n => n.name === '@binding:a');
  assert.ok(edge(g, value.id, binding.id, 'ASSIGN'));
});

test('default expressions retain captured writes and can update earlier effective arguments', t => {
  const root = fixture(t, { 'main.js': `
let x="before"; function use(a=(x="after")) { return a; } use(); sink(x);
function pair(a,b=(a="updated")) { return a; } pair("initial");
` });
  const g = analyze(root);
  const after = g.nodes.find(n => n.kind === 'StringConstant' && n.value === 'after');
  const sink = calls(g).find(c => c.callee.name === 'sink');
  assert.ok(edge(g, after.id, sink.arguments[0], 'ASSIGN'));
  assert.deepEqual(argumentsOf(g, calls(g).find(c => c.callee.name === 'pair')).map(n => n.value), ['updated', 'updated']);
  assert.ok(g.edges.every(e => !(e.kind === 'ASSIGN' && g.nodes[e.to].kind.endsWith('Constant'))), 'assignments never mutate constant nodes');
});

test('spread operands flow directly into emitted object and array arguments', t => {
  const root = fixture(t, { 'main.js': `const defaults = { token: 'object source' }, values = ['array source'];
observe(defaults, values); send({ ...defaults, retries: 3 }); consume([...values]);` });
  const g = analyze(root);
  const observed = calls(g).find(n => n.callee.name === 'observe');
  for (const [index, sink] of ['send', 'consume'].entries()) {
    const call = calls(g).find(n => n.callee.name === sink);
    assert.ok(edge(g, observed.arguments[index], call.arguments[0], 'ASSIGN'), sink);
    assert.ok(edge(g, call.arguments[0], call.id, 'PARAMETER_PASS'), sink);
  }
});

test('unknown spread preserves possible flow to every shifted formal and return', t => {
  const root = fixture(t, { 'main.js': 'function f(a,b) { return b; } const xs=["first","second"]; f(...xs,"last");' });
  const g = analyze(root);
  const second = g.nodes.find(n => n.kind === 'StringConstant' && n.value === 'second');
  const returned = g.nodes.find(n => n.kind === 'Return' && n.method.name === 'f');
  const reaches = (from, to, seen = new Set()) => from === to || (!seen.has(from) && (seen.add(from), g.edges.filter(e => e.from === from && e.kind !== 'CALL').some(e => reaches(e.to, to, seen))));
  assert.ok(reaches(second.id, returned.id));
  const call = calls(g).find(c => c.callee.name === 'f');
  assert.match(argumentsOf(g, call)[1].name, /spread-argument/);
});

test('property reads and writes, and formal versus reassigned binding, have distinct nodes', t => {
  const root = fixture(t, { 'main.js': 'function f(x) { x="same"; return x; } f("same"); const obj={}; obj.self=obj; sink(obj.self);' });
  const g = analyze(root);
  const same = g.nodes.find(n => n.kind === 'StringConstant' && n.value === 'same');
  const binding = g.nodes.find(n => n.name === '@binding:x');
  assert.ok(edge(g, same.id, binding.id, 'ASSIGN'));
  assert.ok(edge(g, same.id, formal(g, 'f').id, 'PARAMETER_PASS'));
  const obj = g.nodes.find(n => n.kind === 'LocalVariable' && n.name === 'obj');
  const slot = g.nodes.find(n => n.name === `@property:${obj.id}:self`);
  const read = argumentsOf(g, calls(g).find(c => c.callee.name === 'sink'))[0];
  assert.notEqual(slot.id, read.id);
  assert.ok(edge(g, obj.id, slot.id, 'FIELD_STORE'));
  assert.ok(edge(g, slot.id, read.id, 'FIELD_LOAD'));
});

test('signed numeric separators preserve concrete normalized default arguments', t => {
  const g = analyze(fixture(t, { 'main.mjs': `
    function send(decimal = -1_000, hex = +0xAB_CD) { return decimal + hex; }
    send();
  ` }));
  const call = calls(g).find(c => c.callee.name === 'send');
  const args = argumentsOf(g, call);
  assert.deepEqual(args.map(n => [n.kind, n.value]), [['IntConstant', -1000], ['IntConstant', 43981]]);
  for (const [i, arg] of args.entries()) {
    assert.ok(edge(g, arg.id, call.id, 'PARAMETER_PASS'));
    assert.ok(edge(g, arg.id, formal(g, 'send', i).id, 'PARAMETER_PASS'));
  }
});

test('negative zero survives the frontend JSON protocol distinctly from zero', t => {
  const root = fixture(t, { 'main.js': 'sink(-0, 0);' });
  const output = path.join(root, 'graph.json');
  const result = spawnSync(process.execPath, [path.resolve('dist/cli.js'), 'build', root, '-o', output], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  const g = JSON.parse(fs.readFileSync(output, 'utf8'));
  const actuals = argumentsOf(g, calls(g).find(c => c.callee.name === 'sink'));
  assert.ok(Object.is(actuals[0].value, -0));
  assert.ok(Object.is(actuals[1].value, 0));
  assert.equal(actuals[0].kind, 'DoubleConstant');
  assert.equal(actuals[1].kind, 'IntConstant');
});

test('separate emitted scripts do not share minified global declaration symbols', t => {
  const root = fixture(t, { 'server.js': 'function e(x="server") { return x; } e();', 'client.js': 'function e(x="client") { return x; } e();' });
  const g = analyze(root);
  assert.deepEqual(calls(g).map(c => [c.caller.declaringClass, c.callee.declaringClass, argumentsOf(g, c)[0].value]), [
    ['client.js', 'client.js', 'client'], ['server.js', 'server.js', 'server'],
  ]);
});

test('guard evaluation effects are not discarded as compiler-lowered defaults', t => {
  const root = fixture(t, { 'main.js': 'function f(x) { if (x === void effect()) { x="fallback"; } return x; } f("given");' });
  const g = analyze(root);
  assert.equal(calls(g).filter(c => c.callee.name === 'effect').length, 1);
  assert.equal(argumentsOf(g, calls(g).find(c => c.callee.name === 'f'))[0].value, 'given');
});

test('frontend protocol preserves valid JS lone UTF16 surrogate strings reversibly', async t => {
  const { decodeArtifactString } = await import('../dist/strings.js');
  const root = fixture(t, { 'main.js': 'sink("\\ud800", "\\udc00", "\\ud83d\\ude00", "\\0js:utf16:d800");' });
  const output = path.join(root, 'graph.json');
  const result = spawnSync(process.execPath, [path.resolve('dist/cli.js'), 'build', root, '-o', output], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  const g = JSON.parse(fs.readFileSync(output, 'utf8'));
  const values = argumentsOf(g, calls(g).find(c => c.callee.name === 'sink')).map(n => n.value);
  assert.deepEqual(values.map(decodeArtifactString), ['\ud800', '\udc00', '😀', '\0js:utf16:d800']);
  assert.equal(values[2], '😀');
  assert.notEqual(values[0], values[3]);
});

test('default-expression calls remain visible for externally entered functions without inventing calls', t => {
  const root = fixture(t, { 'library.mjs': 'export function handle(value=readInput("external")) { return value; }' });
  const g = analyze(root);
  const read = calls(g).find(c => c.callee.name === 'readInput');
  assert.equal(calls(g).length, 1, 'no invented invocation of exported handle');
  assert.equal(read.caller.name, 'handle');
  assert.equal(argumentsOf(g, read)[0].value, 'external');
  assert.ok(edge(g, read.id, formal(g, 'handle').id, 'PARAMETER_PASS'));
});

test('lone surrogates in inferred literal types are encoded consistently with protocol text', async t => {
  const { encodeArtifactString } = await import('../dist/strings.js');
  const root = fixture(t, { 'main.js': 'unknownTarget("\\udead"); unknownTarget("\\udf06\\ud834");' });
  const g = analyze(root);
  const visits = value => {
    if (typeof value === 'string') assert.equal(new TextDecoder('utf-8', { fatal: true }).decode(new TextEncoder().encode(value)), value);
    else if (Array.isArray(value)) value.forEach(visits);
    else if (value && typeof value === 'object') Object.values(value).forEach(visits);
  };
  visits(g);
  assert.deepEqual(calls(g).map(c => c.callee.parameterTypes[0]), [encodeArtifactString('"\udead"'), encodeArtifactString('"\udf06\ud834"')]);
});

test('deep emitted concatenation and comma chains keep their operands and later calls', t => {
  const terms = Array(4500).fill('"x"');
  const root = fixture(t, { 'app.js': `
    function sink(value) { return value; }
    sink("first" + ${terms.join(' + ')} + "last");
    sink((${terms.join(', ')}, "result"));
    sink("after");
  ` });
  const graph = analyze(root);
  const sites = calls(graph).filter(site => site.callee.name === 'sink');
  assert.equal(sites.length, 3);
  // Walk backwards through the actual ASSIGN edges to both ends of the concatenation.
  const parents = new Map();
  for (const { from, to, kind } of graph.edges) if (kind === 'ASSIGN') {
    if (!parents.has(to)) parents.set(to, []);
    parents.get(to).push(from);
  }
  const pending = [sites[0].arguments[0]], seen = new Set();
  while (pending.length) {
    const id = pending.pop();
    if (seen.has(id)) continue;
    seen.add(id);
    pending.push(...(parents.get(id) ?? []));
  }
  for (const value of ['first', 'x', 'last']) {
    assert.ok(seen.has(graph.nodes.find(n => n.kind === 'StringConstant' && n.value === value).id));
  }
  assert.equal(graph.nodes[sites[1].arguments[0]].value, 'result');
  assert.equal(graph.nodes[sites[2].arguments[0]].value, 'after');
});

test('specific edges win in either statement order and warnings identify every dropped pair', t => {
  for (const statements of [
    'cur = list; other = list; for (cur of list) {} for (other of list) {}',
    'for (other of list) {} for (cur of list) {} other = list; cur = list;',
  ]) {
    const root = fixture(t, { 'loop.js': `function pick(list) { let cur, other; ${statements} return cur; } pick([1, 2]);` });
    const warnings = [];
    const graph = analyze(root, message => warnings.push(message));
    const list = formal(graph, 'pick');
    for (const name of ['cur', 'other']) {
      const local = graph.nodes.find(n => n.kind === 'LocalVariable' && n.name === name);
      assert.deepEqual(graph.edges.filter(e => e.from === list.id && e.to === local.id).map(e => e.kind), ['ARRAY_LOAD']);
      assert.ok(warnings.includes(`  ${list.id} -> ${local.id}: kept ARRAY_LOAD, dropped ASSIGN`));
    }
    assert.equal(warnings.length, 3);
    assert.match(warnings[0], /conflicting v3 relationships on 2 node pair/);
  }
});

test('unparsable files are warned about and excluded from import resolution while valid files remain queryable', t => {
  const root = fixture(t, {
    'good.mjs': 'import { bad } from "./invalid.mjs"; import { keep } from "./lib.mjs"; keep("safe"); bad();',
    'lib.mjs': 'export function keep(x) { return x; }',
    'invalid.mjs': 'export function bad() {} const = ;',
    'bad.js': 'function broken( {',
  });
  const warnings = [], graph = analyze(root, w => warnings.push(w));
  assert.match(warnings[0], /skipped 2 unparsable JavaScript artifact.*out of 4/);
  assert.ok(warnings.some(w => w.includes('invalid.mjs') && w.includes('error TS')));
  assert.ok(warnings.some(w => w.includes('bad.js') && w.includes('error TS')));
  assert.ok(graph.methods.every(m => !['invalid.mjs', 'bad.js'].includes(m.declaringClass)));
  const keep = calls(graph).find(n => n.callee.name === 'keep');
  assert.equal(keep.callee.declaringClass, 'lib.mjs');
  assert.equal(graph.nodes[keep.arguments[0]].value, 'safe');
  const bad = calls(graph).find(n => n.callee.name === 'bad');
  assert.ok(graph.edges.some(e => e.from === bad.id && e.kind === 'CALL' && e.isDynamic));
});

test('library symbol identities survive vendoring and different target library declarations', async t => {
  const root = fixture(t, { 'main.mjs': `export function log(s) { console.log(s); console.log(s); return Promise.resolve(s); }` });
  const expected = analyze(root);
  const sandbox = fixture(t, { 'frontend/package.json': '{"type":"module"}' });
  const frontend = path.join(sandbox, 'frontend');
  const vendored = path.join(sandbox, 'vendor', 'compiler');
  fs.cpSync(path.dirname(path.dirname(ts.sys.getExecutingFilePath())), vendored, { recursive: true });
  fs.mkdirSync(path.join(frontend, 'node_modules'));
  fs.symlinkSync(vendored, path.join(frontend, 'node_modules', 'typescript'), 'junction');
  fs.cpSync(new URL('../dist/', import.meta.url), path.join(frontend, 'dist'), { recursive: true });
  const { analyze: relocated, project: vendoredProject } = await import(pathToFileURL(path.join(frontend, 'dist', 'analyzer.js')).href);
  const actual = relocated(root);
  assert.deepEqual(actual, expected);
  const [first, second, promise] = calls(actual);
  assert.equal(first.callee.declaringClass, 'external:typescript/lib.Console');
  assert.equal(promise.callee.declaringClass, 'external:typescript/lib.PromiseConstructor');
  assert.strictEqual(first.callee, second.callee, 'repeated calls share one encoded external descriptor');
  assert.strictEqual(first.caller, actual.methods.find(m => m.name === 'log'), 'definitions and references agree');
  assert.ok(Object.isFrozen(first.callee) && Object.isFrozen(first.callee.parameterTypes));
  assert.ok(!JSON.stringify(actual).includes(sandbox));

  // Substitute the real ES5 default library in the isolated compiler copy.
  // Confirm that the checker actually resolved a different declaration.
  const errors = fixture(t, { 'errors.mjs': 'new Error("e");' });
  function signatureFile(load) {
    const { program } = load(errors);
    const file = program.getSourceFile(program.getRootFileNames()[0]);
    const ctor = file.statements[0].expression;
    return path.basename(program.getTypeChecker().getResolvedSignature(ctor).declaration.getSourceFile().fileName);
  }
  const lib = path.join(vendored, 'lib');
  const defaultLib = target => path.join(lib, ts.getDefaultLibFileName({ target }));
  fs.copyFileSync(defaultLib(ts.ScriptTarget.ES5), defaultLib(ts.ScriptTarget.ESNext));
  assert.notEqual(signatureFile(project), signatureFile(vendoredProject));
  for (const graph of [analyze(errors), relocated(errors)]) {
    const [ctor] = calls(graph);
    assert.deepEqual([ctor.callee.declaringClass, ctor.callee.name], ['external:typescript/lib.ErrorConstructor', 'constructor']);
  }
});

test('escaped literal parameter types survive a binding reassignment without being re-escaped', async t => {
  const { encodeArtifactString } = await import('../dist/strings.js');
  const root = fixture(t, { 'main.js': String.raw`/** @param {'\udead'} x */ function mutate(x) { x = '\udead'; return x; } mutate('\udead');` });
  const graph = analyze(root);
  const parameter = formal(graph, 'mutate');
  const binding = graph.nodes.find(n => n.name === '@binding:x');
  assert.equal(parameter.paramType, encodeArtifactString('"\udead"'));
  assert.equal(binding.varType, parameter.paramType);
  assert.strictEqual(binding.method, parameter.method);
});

test('CLI recognizes help/version only as options, not as an output or a positional artifact', t => {
  const root = fixture(t, { '--help/main.js': 'sink("argument");' });
  const cli = path.resolve('dist/cli.js');
  const result = spawnSync(process.execPath, [cli, 'build', '-o', '--version', '--', '--help'], { cwd: root, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, '');
  const graph = JSON.parse(fs.readFileSync(path.join(root, '--version'), 'utf8'));
  assert.ok(calls(graph).some(n => n.callee.name === 'sink'));
  assert.match(spawnSync(process.execPath, [cli, 'build', '--help'], { encoding: 'utf8' }).stdout, /Usage: graphite build/);
});

function propertyFlows(g, literal, sink) {
  const from = g.nodes.find(n => n.kind === 'StringConstant' && n.value === literal)?.id;
  const target = calls(g).find(c => c.callee.name === sink)?.arguments[0];
  assert.notEqual(from, undefined);
  assert.notEqual(target, undefined);
  const seen = new Set([from]);
  const pending = [from];
  while (pending.length) {
    const id = pending.pop();
    if (id === target) return true;
    for (const edge of g.edges) {
      if (edge.from === id && edge.kind !== 'CALL' && !seen.has(edge.to)) {
        seen.add(edge.to);
        pending.push(edge.to);
      }
    }
  }
  return false;
}

test('class receiver slots link constructor writes to call results and respect lexical this', t => {
  const g = analyze(fixture(t, { 'app.js': `
    class A {
      constructor() { this.x = "secret"; }
      get() { return this.x; }
      arrow() { return (() => this.x)(); }
      inner() { function unrelated() { return this.x; } return unrelated(); }
      static write() { this.x = "static"; }
      static read() { return this.x; }
    }
    class B { get() { return this.x; } }
    const C = class { static { this.x = "class expression"; } };
    classExpressionSink(C.x);
    sink(new A().get()); arrowSink(new A().arrow()); innerSink(new A().inner());
    staticSink(A.read()); otherSink(new B().get()); namedStaticSink(A.x);
  ` }));
  assert.ok(propertyFlows(g, 'secret', 'sink'));
  assert.ok(propertyFlows(g, 'secret', 'arrowSink'));
  for (const sink of ['innerSink', 'staticSink', 'otherSink', 'namedStaticSink'])
    assert.equal(propertyFlows(g, 'secret', sink), false, sink);
  assert.ok(propertyFlows(g, 'class expression', 'classExpressionSink'));
  assert.ok(propertyFlows(g, 'static', 'staticSink'));
  assert.ok(propertyFlows(g, 'static', 'namedStaticSink'));
  assert.equal(propertyFlows(g, 'static', 'sink'), false);
});

test('nested slots preserve their root, shadowed bindings and anonymous receiver occurrences', t => {
  const g = analyze(fixture(t, { 'app.js': `
    const obj = {}, other = {};
    obj.cfg.url = "nested";
    nestedSink((obj["cfg"]).url); otherSink(other.cfg.url);
    { const obj = {}; shadowSink(obj.cfg.url); }
    make().cfg.url = "anonymous"; anonymousSink(make().cfg.url);
    class C { field = "initial"; get() { return this.field; } }
    const one = new C(), two = new C();
    one.field = "owned"; sameField(one["field"]); otherField(two.field); throughGetter(one.get());
  ` }));
  assert.ok(propertyFlows(g, 'nested', 'nestedSink'));
  assert.equal(propertyFlows(g, 'nested', 'otherSink'), false);
  assert.equal(propertyFlows(g, 'nested', 'shadowSink'), false);
  assert.equal(propertyFlows(g, 'anonymous', 'anonymousSink'), false);
  assert.ok(propertyFlows(g, 'owned', 'sameField'));
  assert.equal(propertyFlows(g, 'owned', 'otherField'), false);
  assert.ok(propertyFlows(g, 'initial', 'otherField'));
  assert.ok(propertyFlows(g, 'owned', 'throughGetter'));
});

test('computed key bindings share a slot without colliding with literal spellings or shadows', t => {
  const source = 'const cfg = {}, key = getKey(); cfg[key] = "computed"; sameSink(cfg[key]);';
  const prior = analyze(fixture(t, { 'app.js': source }));
  const keyId = prior.nodes.find(n => n.name === 'key').id;
  const g = analyze(fixture(t, { 'app.js': source + `
    literalSink(cfg['@computed:${keyId}']);
    { const key = getKey(); shadowKeySink(cfg[key]); }
    cfg[getKey()] = "ephemeral"; otherKeySink(cfg[getKey()]);
    cfg['name'] = 'literal'; namedSink(cfg.name);
  ` }));
  assert.ok(propertyFlows(g, 'computed', 'sameSink'));
  assert.equal(propertyFlows(g, 'computed', 'literalSink'), false);
  assert.equal(propertyFlows(g, 'computed', 'shadowKeySink'), false);
  assert.equal(propertyFlows(g, 'ephemeral', 'otherKeySink'), false);
  assert.ok(propertyFlows(g, 'literal', 'namedSink'));
});

test('detached module calls keep their exported name and evaluate the original comma expression', t => {
  const root = fixture(t, { 'entry.cjs': `
    const react = require("missing-react");
    function observe() { return 0; }
    (observe(), (0, react.useState))("ext");
    (0, react.jsx)("section");
    (0, react["jsxDev"])("debug");
    (react.direct)("direct");
  ` });
  const graph = analyze(root);
  const sites = calls(graph);
  assert.equal(sites.filter(call => call.callee.name === 'observe').length, 1);
  for (const [name, value] of [['useState', 'ext'], ['jsx', 'section'], ['jsxDev', 'debug']]) {
    const call = sites.find(call => call.callee.name === name);
    assert.equal(call.callee.declaringClass, '<dynamic>');
    assert.equal(call.receiver, undefined);
    assert.equal(graph.nodes[call.arguments[0]].value, value);
    const relation = graph.edges.find(e => e.kind === 'CALL' && e.from === call.id);
    assert.equal(relation.isDynamic, true);
    assert.equal(relation.isVirtual, false);
  }
  const direct = sites.find(call => call.callee.name === 'direct');
  assert.equal(graph.nodes[direct.receiver].name, 'react');
  assert.ok(graph.edges.some(e => e.from === direct.id && e.kind === 'CALL' && e.isVirtual));

});

test('allocations identify the constructed class while implicit constructors forward to real formals', t => {
  const g = analyze(fixture(t, { 'classes.mjs': [
    'class C { m() {} }', 'class D { constructor(value = 7, ...rest) {} }',
    'class E extends D {}', 'class F extends E {}', 'class G extends E { constructor() { super(); } }',
    'new C();', 'new E(5);', 'new E();', 'new E(undefined);', 'new E(null);', 'new F(10, 20, 30);', 'new G();',
    'const Alias = E; new Alias(12);', 'let K = choose ? D : E; new K(15);', 'function run(unknown) { new unknown(); }',
  ].join('\n') }));
  const byLine = line => calls(g).find(c => c.line === line);
  const dynamic = c => g.edges.find(e => e.from === c.id && e.to === c.id && e.kind === 'CALL').isDynamic;
  for (const [line, cls] of [[5,'E'], [6,'C'], [7,'E'], [8,'E'], [9,'E'], [10,'E'], [11,'F'], [12,'G'], [13,'E']]) {
    const call = byLine(line);
    assert.equal(call.callee.declaringClass, 'classes.mjs.' + cls);
    assert.equal(call.callee.name, 'constructor');
    assert.equal(dynamic(call), false);
    assert.ok(g.methods.some(m => m === call.callee));
  }
  for (const line of [14,15]) {
    const call = byLine(line);
    assert.equal(call.callee.declaringClass, '<dynamic>');
    assert.equal(dynamic(call), true);
  }
  const [value, rest] = g.nodes.filter(n => n.kind === 'Parameter' && n.method.declaringClass === 'classes.mjs.D');
  for (const [line, expected] of [[5,7], [7,5], [8,7], [9,7], [10,null], [11,10], [13,12]]) {
    const [arg] = argumentsOf(g, byLine(line));
    assert.equal(arg.kind === 'NullConstant' ? null : arg.value, expected);
    assert.ok(edge(g, arg.id, value.id, 'PARAMETER_PASS'));
  }
  for (const arg of argumentsOf(g, byLine(11)).slice(1)) assert.ok(edge(g, arg.id, rest.id, 'PARAMETER_PASS'));
  assert.equal(calls(g).length, 11); // ten allocations, plus the one source super() call
  assert.equal(g.methods.filter(m => m.declaringClass === 'classes.mjs.E' && m.name === 'constructor').length, 1);
});

test('standard-library construct signatures use stable constructor names', t => {
  const g = analyze(fixture(t, { 'builtins.js': 'new Error("e"); new Map(); new Promise(resolve => resolve());' }));
  for (const builtin of ['Error', 'Map', 'Promise']) {
    const call = calls(g).find(c => c.callee.declaringClass.endsWith('.' + builtin + 'Constructor'));
    assert.ok(call, builtin);
    assert.match(call.callee.declaringClass, /^external:typescript\/lib\./);
    assert.equal(call.callee.name, 'constructor');
  }
});

test('library namespace members and global functions keep distinct symbol owners', t => {
  const g = analyze(fixture(t, { 'globals.js': 'Intl.getCanonicalLocales("en"); new Intl.Collator(); parseInt("2");' }));
  assert.deepEqual(calls(g).map(c => [c.callee.declaringClass,c.callee.name]), [
    ['external:typescript/lib.Intl','getCanonicalLocales'], ['external:typescript/lib.Intl.CollatorConstructor','constructor'],
    ['external:typescript/lib','parseInt'],
  ]);
});

test('unknown expression callees share a marker while preserving arguments, receivers and evaluation', t => {
  const g = analyze(fixture(t, { 'dynamic.mjs': [
    'async function run(t, a, b, c) {',
    '  (t.default || t)("fallback");',
    '  (a ? b : c)("choice");',
    '  (await load())("awaited");',
    '  t[key()]("element");',
    '  new (a ? b : c)("allocation");',
    '  t.named("member"); t["literal"]("literal"); new b("binding");',
    '}',
  ].join('\n') }));
  const sites = calls(g), markers = sites.filter(c => c.callee.name === '<call>');
  assert.deepEqual(markers.map(c => [c.line, g.nodes[c.arguments[0]].value, c.receiver !== undefined]), [
    [2,'fallback',false], [3,'choice',false], [4,'awaited',false], [5,'element',true], [6,'allocation',false],
  ]);
  for (const c of markers) {
    assert.equal(c.callee.declaringClass, '<dynamic>');
    const e = g.edges.find(e => e.from === c.id && e.kind === 'CALL');
    assert.equal(e.isDynamic, true);
    assert.equal(e.isVirtual, c.receiver !== undefined);
  }
  for (const name of ['load','key','named','literal','b']) assert.equal(sites.filter(c => c.callee.name === name).length, 1);
});
