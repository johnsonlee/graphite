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
  assert.match(build.stderr, /skipped 2 conflicting relationship occurrences/);
  const result = JSON.parse(run(['query', graph, "MATCH (p:Parameter)-[r]->(v:LocalVariable) WHERE v.name = 'cur' RETURN r.kind AS kind", '--format', 'json']).stdout);
  assert.deepEqual(result.rows, [{ kind: 'ASSIGN' }]);
} finally { fs.rmSync(dir, { recursive: true, force: true }); }
