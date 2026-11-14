// Run after building both the Rust CLI and this frontend.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const cli = path.resolve(process.argv[2]);
const frontend = fileURLToPath(new URL('../dist/cli.js', import.meta.url));
const { version } = JSON.parse(fs.readFileSync(new URL('../package.json', import.meta.url), 'utf8'));
const run = args => {
  const result = spawnSync(cli, args, { encoding: 'utf8', timeout: 60000, env: { ...process.env, GRAPHITE_FRONTEND_JS: frontend } });
  assert.equal(result.status, 0, result.error?.message ?? result.stderr);
  return result;
};
const list = run(['frontend', 'list']).stdout.trim().split(/\r?\n/).find(line => line.startsWith('js\t'));
assert.equal(list?.split('\t')[1], version);
for (const selector of ['js', 'ts']) assert.equal(JSON.parse(run(['frontend', 'describe', selector]).stdout).version, version);

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'graphite-js-cli-'));
try {
  const input = path.join(dir, 'main.js'), graph = path.join(dir, 'out.graphite');
  fs.writeFileSync(input, 'function pick(list) { let cur = list; for (cur of list) {} for (cur of list) {} return cur; } pick([1,2]);');
  const build = run(['build', '--lang', 'js', input, '-o', graph]);
  assert.match(build.stderr, /kept ARRAY_LOAD, dropped ASSIGN/);
  const result = JSON.parse(run(['query', graph, "MATCH (p:Parameter)-[r]->(v:LocalVariable) WHERE v.name = 'cur' RETURN r.kind AS kind", '--format', 'json']).stdout);
  assert.deepEqual(result.rows, [{ kind: 'ARRAY_LOAD' }]);

  // A bare output selects an archive and leaves an existing sibling directory alone.
  // Compare actual values and both published files after failed rebuilds.
  const rebuildInput = path.join(dir, 'rebuild.js');
  const output = path.join(dir, 'rebuilt'), rebuilt = output + '.graphite';
  fs.mkdirSync(output);
  fs.writeFileSync(path.join(output, 'keep.txt'), 'unrelated user data');
  const strings = saved => JSON.parse(run(['query', saved,
    'MATCH (s:StringConstant) RETURN s.value AS value ORDER BY value', '--format', 'json']).stdout).rows;
  const snapshot = () => [rebuilt, rebuilt + '.sha256'].map(file => fs.readFileSync(file).toString('hex'));
  for (const selector of ['js', 'ts', 'es']) {
    fs.writeFileSync(rebuildInput, `console.log("${selector}");`);
    run(['build', '--lang', selector, rebuildInput, '-o', output]);
    run(['verify', rebuilt]);
    assert.deepEqual(strings(rebuilt), [{ value: selector }]);
    assert.deepEqual(fs.readdirSync(output), ['keep.txt']);
    assert.equal(fs.readFileSync(path.join(output, 'keep.txt'), 'utf8'), 'unrelated user data');
    const unpacked = path.join(dir, selector + '-unpacked');
    run(['unpack', rebuilt, unpacked]);
    assert.deepEqual(strings(unpacked), [{ value: selector }]);
  }
  const previous = snapshot();
  fs.writeFileSync(rebuildInput, 'function broken( {');
  const failed = spawnSync(cli, ['build', '--lang', 'js', rebuildInput, '-o', output], {
    encoding: 'utf8', timeout: 60000, env: { ...process.env, GRAPHITE_FRONTEND_JS: frontend },
  });
  assert.equal(failed.status, 1, failed.error?.message ?? failed.stderr);
  assert.match(failed.stderr, /No parseable JavaScript artifacts remain/);
  assert.deepEqual(snapshot(), previous);
  assert.deepEqual(strings(rebuilt), [{ value: 'es' }]);
  fs.writeFileSync(rebuildInput, 'console.log("replacement");');
  const digest = fs.readFileSync(rebuilt + '.sha256');
  fs.writeFileSync(rebuilt + '.sha256', '0'.repeat(64) + '  rebuilt.graphite\n');
  const damagedPair = snapshot();
  const refused = spawnSync(cli, ['build', '--lang', 'js', rebuildInput, '-o', output], {
    encoding: 'utf8', timeout: 60000, env: { ...process.env, GRAPHITE_FRONTEND_JS: frontend },
  });
  assert.equal(refused.status, 2, refused.error?.message ?? refused.stderr);
  assert.match(refused.stderr, /refusing to replace/);
  assert.deepEqual(snapshot(), damagedPair);
  fs.writeFileSync(rebuilt + '.sha256', digest);
  run(['verify', rebuilt]);
  assert.deepEqual(strings(rebuilt), [{ value: 'es' }]);

  const usage = spawnSync(cli, ['build','--lang','js','-o',path.join(dir,'missing.graphite')], {
    encoding:'utf8', env:{ ...process.env, GRAPHITE_FRONTEND_JS:frontend },
  });
  assert.equal(usage.status, 2, usage.stderr);
  assert.match(usage.stderr, /Expected one emitted JavaScript input/);
  assert.match(usage.stderr, /Usage: graphite build/);
  assert.ok(!fs.existsSync(path.join(dir,'missing.graphite')));

  const inspectArgs = (program, args) => spawnSync(program, args, {
    encoding:'utf8', cwd:dir, env:{ ...process.env, GRAPHITE_FRONTEND_JS:frontend },
  });
  for (const name of ['app.txt','app.ts','app.json','no-extension']) fs.writeFileSync(path.join(dir,name), 'invalid artifact');
  for (const [inputs,reason] of [
    ...['app.txt','app.json','app.ts','missing.ts','no-extension'].map(input => [[input], /Compile the project first/]),
    [['main.js','app.ts'], /Expected exactly one emitted JavaScript input/],
    [['app.ts','main.js'], /Expected exactly one emitted JavaScript input/],
    [['--fold','app.ts','main.js'], /Unsupported JavaScript frontend option: --fold/],
  ]) {
    const direct = inspectArgs(process.execPath, [frontend,'build','-o','bad.json',...inputs]);
    const shell = inspectArgs(cli, ['build','--lang','js','-o','bad.graphite',...inputs]);
    for (const result of [direct,shell]) {
      assert.equal(result.status, 2, result.stderr);
      assert.match(result.stderr, reason);
    }
    assert.equal(direct.stderr.split('\n')[0], shell.stderr.split('\n')[0]);
  }
  assert.ok(!fs.existsSync(path.join(dir,'bad.json')) && !fs.existsSync(path.join(dir,'bad.graphite')));
  for (const name of ['.next','output.ts']) {
    const dotted = path.join(dir,name);
    fs.mkdirSync(dotted);
    fs.writeFileSync(path.join(dotted,'app.js'), 'sink("artifact")');
    const direct = inspectArgs(process.execPath, [frontend,'build','-o',name+'.json','--',name]);
    assert.equal(direct.status, 0, direct.stderr);
    assert.ok(JSON.parse(fs.readFileSync(path.join(dir,name+'.json'),'utf8')).nodes.some(n => n.value === 'artifact'));
    const saved = path.join(dir,name+'.graphite');
    run(['build','--lang','js','-o',saved,'--',dotted]);
    const values = JSON.parse(run(['query',saved,'MATCH (s:StringConstant) RETURN s.value AS value','--format','json']).stdout);
    assert.deepEqual(values.rows,[{ value:'artifact' }]);
  }

  const cls = path.join(dir, 'member.mjs'), member = path.join(dir, 'member.graphite');
  const memberSource = 'class A { constructor(){ this.x = "secret"; } get(){ return this.x; } } sink(new A().get())';
  fs.writeFileSync(cls, memberSource);
  run(['build', '--lang', 'js', cls, '-o', member]);
  run(['verify', member]);
  const slice = run(['query', member, "MATCH (s:StringConstant {value:'secret'})-[:DATAFLOW]->(:LocalVariable)-[:DATAFLOW]->(read:LocalVariable)-[:DATAFLOW]->(:Return)-[:DATAFLOW]->(get:CallSite)-[:DATAFLOW]->(sink:CallSite) WHERE get.callee_name='get' AND sink.callee_name='sink' RETURN read.name AS read", '--format', 'json']);
  assert.deepEqual(JSON.parse(slice.stdout).rows, [{ read: `@read:x@1:${memberSource.lastIndexOf('this.x') + 'this.'.length + 1}` }]);

  const ctors = path.join(dir, 'constructors.mjs'), constructors = path.join(dir, 'constructors.graphite');
  fs.writeFileSync(ctors, ['class C { m() {} }', 'class D { constructor(x=7) {} }', 'class E extends D {}',
    'new C();', 'new E(5);', 'new E();', 'new Error("e");', 'function unknown(K) { new K(); }'].join('\n'));
  run(['build', '--lang', 'js', ctors, '-o', constructors]);
  run(['verify', constructors]);
  const allocations = query => JSON.parse(run(['query', constructors, query, '--format', 'json']).stdout).rows;
  const allocationCalls = allocations("MATCH (c:CallSite)-[r:CALL]->(:CallSite) RETURN c.line AS line, c.callee_class AS cls, c.callee_name AS name, r.dynamic AS dynamic ORDER BY line");
  assert.deepEqual(allocationCalls.map(c => [c.line,c.cls,c.name,c.dynamic]), [
    [4,'constructors.mjs.C','constructor',false], [5,'constructors.mjs.E','constructor',false],
    [6,'constructors.mjs.E','constructor',false], [7,'external:typescript/lib.ErrorConstructor','constructor',false],
    [8,'<dynamic>','K',true],
  ]);
  assert.deepEqual(allocations("MATCH (v:IntConstant)-[r:DATAFLOW]->(p:Parameter) WHERE r.kind='PARAMETER_PASS' RETURN v.value AS value, p.index AS i ORDER BY value"),
    [{value:5,i:0},{value:7,i:0}]);

  const dyn = path.join(dir, 'dynamic.mjs'), dynamic = path.join(dir, 'dynamic.graphite');
  fs.writeFileSync(dyn, ['async function invoke(t, a, b, c) {',
    '  (t.default || t)("fallback"); (a ? b : c)("choice");', '  (await load())("awaited");',
    '  t[key()]("element"); new (a ? b : c)("allocation");', '}',
    'Intl.getCanonicalLocales("en");', '[1].map(x => x); [1].find(x => x);'].join('\n'));
  run(['build', '--lang', 'js', dyn, '-o', dynamic]);
  run(['verify', dynamic]);
  const dynamicRows = query => JSON.parse(run(['query', dynamic, query, '--format', 'json']).stdout).rows;
  assert.deepEqual(dynamicRows("MATCH (s:StringConstant)-[:DATAFLOW]->(c:CallSite)-[r:CALL]->(:CallSite) WHERE c.callee_name='<call>' RETURN s.value AS arg, r.virtual AS receiver, r.dynamic AS dyn ORDER BY arg"),
    ['allocation','awaited','choice','element','fallback'].map(arg => ({ arg, receiver:arg === 'element', dyn:true })));
  assert.deepEqual(dynamicRows("MATCH (c:CallSite) WHERE c.callee_name='load' OR c.callee_name='key' RETURN c.callee_name AS n ORDER BY n"),
    [{ n:'key' },{ n:'load' }]);
  assert.deepEqual(dynamicRows("MATCH (c:CallSite) WHERE c.callee_class STARTS WITH 'external:typescript/lib' RETURN c.callee_class AS cls, c.callee_name AS n ORDER BY cls, n"),
    [{ cls:'external:typescript/lib.Array', n:'find' },{ cls:'external:typescript/lib.Array', n:'map' },
      { cls:'external:typescript/lib.Intl', n:'getCanonicalLocales' }]);

  const bindings = path.join(dir,'bindings.mjs'), libraryGraph = path.join(dir,'bindings.graphite');
  fs.writeFileSync(bindings, [
    'console.log("direct");', 'const log = console.log; log("alias");',
    '/** @param {typeof console.log} callback */ function via(callback) { callback("typed"); }',
    'function unknown(obj) { obj.log("unknown"); }',
    '/** @param {ErrorConstructor} C */ function make(C) { new C("runtime"); }',
  ].join('\n'));
  run(['build','--lang','js',bindings,'-o',libraryGraph]);
  run(['verify',libraryGraph]);
  const flags = JSON.parse(run(['query',libraryGraph,
    "MATCH (s:StringConstant)-[:DATAFLOW]->(c:CallSite)-[r:CALL]->(:CallSite) RETURN s.value AS arg,c.callee_class AS cls,r.dynamic AS dyn,r.virtual AS virt ORDER BY arg",
    '--format','json']).stdout).rows;
  assert.deepEqual(flags.map(r => [r.arg,r.cls,r.dyn,r.virt]), [
    ['alias','external:typescript/lib.Console',false,false], ['direct','external:typescript/lib.Console',false,true],
    ['runtime','external:typescript/lib.ErrorConstructor',true,false], ['typed','<dynamic>',true,false],
    ['unknown','<dynamic>',true,true],
  ]);

  const short = path.join(dir, 'shorthand'), shorthand = path.join(dir, 'shorthand.graphite');
  fs.mkdirSync(short);
  fs.writeFileSync(path.join(short, 'lib.mjs'), 'export const exported = "imported";');
  fs.writeFileSync(path.join(short, 'main.mjs'), [
    'import { exported as data } from "./lib.mjs";',
    'const secret = "s"; sink({ secret }); importedSink({ data });',
    'function page() { const data = "inner"; return { props: { data } }; } nestedSink(page());',
  ].join('\n'));
  run(['build', '--lang', 'js', short, '-o', shorthand]);
  run(['verify', shorthand]);
  const shorthandRows = query => JSON.parse(run(['query', shorthand, query, '--format', 'json']).stdout).rows;
  assert.deepEqual(shorthandRows("MATCH (s:StringConstant)-[:DATAFLOW]->(:LocalVariable)-[:DATAFLOW]->(:LocalVariable)-[:DATAFLOW]->(c:CallSite) RETURN s.value AS value, c.callee_name AS sink ORDER BY value"),
    [{ value:'imported', sink:'importedSink' }, { value:'s', sink:'sink' }]);
  assert.deepEqual(shorthandRows("MATCH (s:StringConstant)-[:DATAFLOW]->(:LocalVariable)-[:DATAFLOW]->(:LocalVariable)-[:DATAFLOW]->(:LocalVariable)-[:DATAFLOW]->(:Return)-[:DATAFLOW]->(:CallSite)-[:DATAFLOW]->(c:CallSite) RETURN s.value AS value, c.callee_name AS sink"),
    [{ value:'inner', sink:'nestedSink' }]);

  const app = path.join(dir, 'entry-app'), entry = path.join(app,'main.js');
  const entryGraph = path.join(dir,'entry.graphite');
  fs.mkdirSync(app);
  fs.writeFileSync(entry, 'import {save} from "./lib.js"; save("secret");');
  fs.writeFileSync(path.join(app,'lib.js'), 'export function save(value) { return value; }');
  fs.writeFileSync(path.join(app,'unused.js'), 'function broken( {');
  const single = run(['build','--lang','js',entry,'-o',entryGraph]);
  assert.doesNotMatch(single.stderr, /unused.js|unparsable/);
  run(['verify',entryGraph]);
  const flow = run(['query', entryGraph, "MATCH (s:StringConstant {value:'secret'})-[r:DATAFLOW]->(p:Parameter) WHERE r.kind='PARAMETER_PASS' RETURN p.method AS method, p.index AS i", '--format','json']);
  assert.deepEqual(JSON.parse(flow.stdout).rows, [{ method:'lib.js.save(any)', i:0 }]);

  const overflow = path.join(dir, 'overflow.mjs'), saved = path.join(dir, 'overflow.graphite');
  fs.writeFileSync(overflow, ['function take(x = -1e400) { return x; }','take(1e400);','take(-1e400);','take();'].join('\n'));
  run(['build', '--lang', 'js', overflow, '-o', saved]);
  run(['verify', saved]);
  const rows = q => JSON.parse(run(['query', saved, q, '--format', 'json']).stdout).rows;
  const calls = rows("MATCH (v:LocalVariable)-[r:DATAFLOW]->(c:CallSite) WHERE r.kind='PARAMETER_PASS' AND c.callee_name='take' RETURN v.id AS id, v.type AS t, c.line AS line ORDER BY line");
  assert.deepEqual(calls.map(c => [c.line,c.t]), [[2,'number'],[3,'number'],[4,'number']]);
  const ids = calls.map(c => c.id).sort((a,b) => a-b);
  assert.equal(new Set(ids).size,3);
  assert.deepEqual(rows("MATCH (v:LocalVariable)-[r:DATAFLOW]->(p:Parameter) WHERE r.kind='PARAMETER_PASS' RETURN v.id AS id, p.index AS i ORDER BY id"),ids.map(id => ({id,i:0})));
  for (const label of ['NullConstant','DoubleConstant']) assert.deepEqual(rows(`MATCH (n:${label}) RETURN count(*) AS n`),[{n:0}]);
} finally { fs.rmSync(dir, { recursive: true, force: true }); }
