import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { comparePersisted, PROFILE, runWithDeadline } from './build-jvm-defaults.ts';

const script = fileURLToPath(new URL('./build-jvm-defaults.ts', import.meta.url));

function fixture(root: string): { args: string[]; env: NodeJS.ProcessEnv; output: string } {
  const reference = join(root, 'reference');
  mkdirSync(reference);
  writeFileSync(join(reference, 'graph.bin'), Buffer.from([0, 255, 19]));
  writeFileSync(join(reference, 'forward.properties'), '# old timestamp\nnodes=3\n');
  const input = join(root, 'input with spaces.jar');
  const frontend = join(root, 'frontend with spaces.jar');
  writeFileSync(input, 'fixture input');
  writeFileSync(frontend, 'fixture frontend');
  const java = join(root, 'java');
  writeFileSync(java, `#!${process.execPath}\nimport { cpSync,writeFileSync } from 'node:fs';\nconst args=process.argv.slice(2);\nif(args.includes('-version')){console.log('probe ran');process.exit(0);}\nif(process.env.JDK_JAVA_OPTIONS || process.env._JAVA_OPTIONS)throw new Error('inherited JVM environment leaked');\nconst output=args[args.indexOf('-o')+1];\ncpSync(process.env.FIXTURE_REFERENCE,output,{recursive:true});\nwriteFileSync(output+'/forward.properties','# new timestamp\\nnodes='+(process.env.CORRUPT_GRAPH ? '4' : '3')+'\\n');\nconsole.log('actual JVM fixture build');\n`, { mode: 0o700 });
  const cli = join(root, 'cli with spaces');
  writeFileSync(cli, `#!${process.execPath}\nimport { spawnSync } from 'node:child_process';\nconst profile=${JSON.stringify(PROFILE)};\nif(process.env.JAVA_TOOL_OPTIONS || process.env.JDK_JAVA_OPTIONS || process.env._JAVA_OPTIONS)throw new Error('inherited options leaked into CLI');\nif(process.env.JAVA_OPTS && process.env.JAVA_OPTS !== '-Xmx2g')throw new Error('inherited JAVA_OPTS leaked');\nconst launch=(args,env)=>{const result=spawnSync(process.env.GRAPHITE_JAVA,args,{env,stdio:'inherit'});if(result.error)throw result.error;if(result.status!==0)process.exit(result.status ?? 1);};\nif(!process.env.JAVA_OPTS)launch([...profile.split(' '),'-XX:+PrintFlagsFinal','-version'],process.env);\nconst args=['-jar',process.env.GRAPHITE_FRONTEND_JVM,...process.argv.slice(2)];\nif(process.env.EXTRA_JAVA_CALL)args.push('--unexpected');\nlaunch(args,{...process.env,JAVA_TOOL_OPTIONS:process.env.JAVA_OPTS || profile});\n`, { mode: 0o700 });
  const output = join(root, 'evidence');
  return {
    args: [script, '--cli', cli, '--frontend', frontend, '--input', input, '--reference', reference, '--output', output, '--include', 'com.acme', '--android-sdk', join(root, 'sdk with spaces')],
    env: { ...process.env, PATH: `${root}${delimiter}${process.env.PATH ?? ''}`, FIXTURE_REFERENCE: reference,
      JAVA_TOOL_OPTIONS: '-Xmx99g', JAVA_OPTS: '-Xmx100g', JDK_JAVA_OPTIONS: '-Xmx101g', _JAVA_OPTIONS: '-Xmx102g' },
    output,
  };
}

test('Bun CLI and observer preserve exact default/override launches and clean Java environment', t => {
  const root = mkdtempSync(join(tmpdir(), 'build-defaults-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const setup = fixture(root);
  const result = spawnSync(process.execPath, setup.args, { env: setup.env, encoding: 'utf8', timeout: 15_000 });
  assert.equal(result.status, 0, `${result.stderr}\n${result.stdout}`);
  assert.match(result.stdout, /PASS default:/);
  assert.match(result.stdout, /PASS explicit-small-heap:/);
  const defaults = readFileSync(join(setup.output, 'default.jsonl'), 'utf8').trim().split('\n').map(line => JSON.parse(line));
  assert.equal(defaults.length, 2);
  assert.deepEqual(defaults[0], { args: [...PROFILE.split(' '), '-XX:+PrintFlagsFinal', '-version'], toolOptions: null });
  assert.equal(defaults[1].toolOptions, PROFILE);
  assert.deepEqual(defaults[1].args.slice(-2), ['--android-sdk', join(root, 'sdk with spaces')]);
  const override = JSON.parse(readFileSync(join(setup.output, 'explicit-small-heap.jsonl'), 'utf8'));
  assert.equal(override.toolOptions, '-Xmx2g');
  assert.match(readFileSync(join(setup.output, 'default.stdout'), 'utf8'), /actual JVM fixture build/);
  const repeated = spawnSync(process.execPath, setup.args, { env: setup.env, encoding: 'utf8' });
  assert.notEqual(repeated.status, 0, 'existing evidence must not be reused');
});

test('unexpected invocation and corrupted persisted graph each fail the executable CLI check', t => {
  for (const extra of [{ EXTRA_JAVA_CALL: '1' }, { CORRUPT_GRAPH: '1' }]) {
    const root = mkdtempSync(join(tmpdir(), 'build-defaults-reject-'));
    t.after(() => rmSync(root, { recursive: true, force: true }));
    const setup = fixture(root);
    const result = spawnSync(process.execPath, setup.args, { env: { ...setup.env, ...extra }, encoding: 'utf8', timeout: 15_000 });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, 'EXTRA_JAVA_CALL' in extra ? /unexpected Java invocations/ : /Persisted file differs/);
  }
});

test('persisted comparison ignores only text comment lines and preserves bytes, paths and line endings', t => {
  const root = mkdtempSync(join(tmpdir(), 'persisted-equality-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const left = join(root, 'left'); const right = join(root, 'right');
  mkdirSync(left); mkdirSync(right);
  const a = join(left, 'file'); const b = join(right, 'file');
  writeFileSync(a, '# one\nnodes=3\n'); writeFileSync(b, '# two\n# extra\nnodes=3\n');
  comparePersisted(left, right);
  for (const changed of ['nodes=4\n', 'nodes=3', 'nodes=3\r\n', '\uFEFFnodes=3\n', '\uFEFF# timestamp\nnodes=3\n']) {
    writeFileSync(b, changed); assert.throws(() => comparePersisted(left, right), /Persisted file differs/);
  }
  writeFileSync(a, Buffer.from([35, 0, 255])); writeFileSync(b, Buffer.from([35, 0, 254]));
  assert.throws(() => comparePersisted(left, right), /Persisted file differs/);
  writeFileSync(b, readFileSync(a)); writeFileSync(join(right, 'extra'), 'x');
  assert.throws(() => comparePersisted(left, right), /Persisted entries differ/);
});

test('Bun timeout terminates both the launcher and its descendant process group', async t => {
  const root = mkdtempSync(join(tmpdir(), 'build-defaults-timeout-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const heartbeat = join(root, 'heartbeat');
  const childPid = join(root, 'descendant.pid');
  const parentPid = join(root, 'parent.pid');
  const descendant = `import { appendFileSync } from 'node:fs';setInterval(()=>appendFileSync(${JSON.stringify(heartbeat)},'x'),20);`;
  const launcher = `import {spawn} from 'node:child_process';import {writeFileSync} from 'node:fs';writeFileSync(${JSON.stringify(parentPid)},String(process.pid));const child=spawn(process.execPath,['-e',${JSON.stringify(descendant)}],{stdio:'inherit'});writeFileSync(${JSON.stringify(childPid)},String(child.pid));setInterval(()=>{},1000);`;
  t.after(() => { for (const path of [parentPid, childPid]) if (existsSync(path)) { try { process.kill(Number(readFileSync(path)), 'SIGKILL'); } catch { /* Already stopped. */ } } });
  await assert.rejects(runWithDeadline(process.execPath, ['-e', launcher], process.env, join(root, 'stdout'), join(root, 'stderr'), 700), /timed out.*process group terminated/);
  assert.ok(existsSync(childPid), 'descendant really started');
  assert.ok(readFileSync(heartbeat).length > 0, 'descendant performed work before timeout');
  const stopped = readFileSync(heartbeat);
  await delay(100);
  assert.deepEqual(readFileSync(heartbeat), stopped, 'descendant must stop after parent timeout');
  assert.throws(() => process.kill(Number(readFileSync(parentPid)), 0), /ESRCH/);
});
