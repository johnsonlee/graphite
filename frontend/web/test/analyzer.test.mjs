import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import ts from 'typescript';
import { analyze } from '../dist/analyzer.js';

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

test('reads hidden Next server/client outputs, dist and build; never reads source, cache or dependencies', t => {
  const root = fixture(t, {
    'tsconfig.json': '{ invalid config: must not be consulted',
    'src/source.ts': 'invalid source {{{',
    '.next/server/app.js': 'function server() { return database("task"); }',
    '.next/static/chunks/app.js': 'function client() { return fetch("/api/tasks"); }',
    '.next/cache/broken.js': 'invalid cached file {{{',
    'dist/lib.cjs': 'function library() { return console.log("built"); }',
    'build/nested/lib.mjs': 'export function nested() { return helper("nested"); }',
    'node_modules/broken.js': 'invalid dependency {{{',
  });
  const g = analyze(root);
  assert.deepEqual(calls(g).map(c => c.callee.name).sort(), ['database', 'fetch', 'helper', 'log']);
  assert.deepEqual(g.classOrigins.map(c => c.origin).sort(), ['.next/server/app.js', '.next/static/chunks/app.js', 'build/nested/lib.mjs', 'dist/lib.cjs']);
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

test('void expressions keep effects even when their value triggers a default', t => {
  const root = fixture(t, { 'main.js': 'function use(x="default") { return x; } use(void effect());' });
  const g = analyze(root);
  assert.equal(calls(g).filter(c => c.callee.name === 'effect').length, 1);
  assert.equal(argumentsOf(g, calls(g).find(c => c.callee.name === 'use'))[0].value, 'default');
});

test('literal spread defaults and rest arguments retain explicit parameter flow', t => {
  const root = fixture(t, { 'main.js': `
function identity(value = "shared") { return value; } identity(...[]); identity(...[undefined]);
function forward(...values) { for (const value of values) console.log(value); } forward('first', 'second');
` });
  const g = analyze(root);
  assert.deepEqual(calls(g).filter(c => c.callee.name === 'identity').map(c => argumentsOf(g, c)[0].value), ['shared', 'shared']);
  const literal = g.nodes.find(n => n.kind === 'StringConstant' && n.value === 'second');
  assert.ok(edge(g, literal.id, formal(g, 'forward').id, 'PARAMETER_PASS'));
  const sink = calls(g).find(n => n.callee.name === 'log');
  assert.ok(edge(g, formal(g, 'forward').id, sink.arguments[0], 'ARRAY_LOAD'));
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
