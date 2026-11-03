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

  const cls = path.join(dir, 'member.mjs'), member = path.join(dir, 'member.graphite');
  fs.writeFileSync(cls, 'class A { constructor(){ this.x = "secret"; } get(){ return this.x; } } sink(new A().get())');
  run(['build', '--lang', 'js', cls, '-o', member]);
  run(['verify', member]);
  const slice = run(['query', member, "MATCH (s:StringConstant {value:'secret'})-[:DATAFLOW]->(:LocalVariable)-[:DATAFLOW]->(:LocalVariable)-[:DATAFLOW]->(:Return)-[:DATAFLOW]->(get:CallSite)-[:DATAFLOW]->(sink:CallSite) WHERE get.callee_name='get' AND sink.callee_name='sink' RETURN count(*) AS n", '--format', 'json']);
  assert.deepEqual(JSON.parse(slice.stdout).rows, [{ n: 1 }]);

  const ctors = path.join(dir, 'constructors.mjs'), constructors = path.join(dir, 'constructors.graphite');
  fs.writeFileSync(ctors, ['class C { m() {} }', 'class D { constructor(x=7) {} }', 'class E extends D {}',
    'new C();', 'new E(5);', 'new E();', 'new Error("e");', 'function unknown(K) { new K(); }'].join('\n'));
  run(['build', '--lang', 'js', ctors, '-o', constructors]);
  run(['verify', constructors]);
  const allocations = query => JSON.parse(run(['query', constructors, query, '--format', 'json']).stdout).rows;
  const allocationCalls = allocations("MATCH (c:CallSite)-[r:CALL]->(:CallSite) RETURN c.line AS line, c.callee_class AS cls, c.callee_name AS name, r.dynamic AS dynamic ORDER BY line");
  assert.deepEqual(allocationCalls.map(c => [c.line,c.cls,c.name,c.dynamic]), [
    [4,'constructors.mjs.C','constructor',false], [5,'constructors.mjs.E','constructor',false],
    [6,'constructors.mjs.E','constructor',false], [7,'external:typescript/lib.ErrorConstructor','constructor',true],
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
