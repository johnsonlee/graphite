import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { comparePersisted, runWithDeadline } from './build-jvm-defaults.ts';

const PROFILE = '-Xmx8g -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:G1MaxNewSizePercent=30 -XX:MinHeapFreeRatio=20 -XX:GCTimeRatio=4';
const script = fileURLToPath(new URL('./build-jvm-defaults.ts', import.meta.url));

function fixture(root: string): { args: string[]; env: NodeJS.ProcessEnv; output: string } {
  const reference = join(root, 'reference');
  mkdirSync(reference);
  writeFileSync(join(reference, 'graph.bin'), Buffer.from([0, 255, 19]));
  writeFileSync(join(reference, 'forward.properties'), '# old timestamp\nnodes=3\n');
  const input = join(root, 'input with spaces.jar');
  const frontend = join(root, 'frontend with spaces.jar');
  writeFileSync(input, 'fixture input');
  const source = join(root, 'FixtureFrontend.java');
  writeFileSync(source, `
import java.nio.file.*;
public class FixtureFrontend {
    public static void main(String[] args) throws Exception {
        if (System.getenv("JDK_JAVA_OPTIONS") != null || System.getenv("_JAVA_OPTIONS") != null)
            throw new IllegalStateException("Inherited JVM environment leaked");
        Path output = null;
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals("-o")) output = Path.of(args[i + 1]);
        Files.createDirectory(output);
        try (var files = Files.list(Path.of(System.getenv("FIXTURE_REFERENCE")))) {
            for (Path file : files.toList()) Files.copy(file, output.resolve(file.getFileName()));
        }
        Files.writeString(output.resolve("forward.properties"), "# new timestamp\\nnodes="
            + (System.getenv("CORRUPT_GRAPH") == null ? "3" : "4") + "\\n");
        System.out.println("actual JVM fixture build");
    }
}
`);
  for (const [tool, args] of [
    ['javac', ['--release', '17', '-d', root, source]],
    ['jar', ['--create', '--file', frontend, '--main-class', 'FixtureFrontend', '-C', root, 'FixtureFrontend.class']],
  ] as const) {
    const result = spawnSync(tool, args, { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
  }
  const cli = join(root, 'cli with spaces');
  writeFileSync(cli, `#!${process.execPath}
import { spawnSync } from 'node:child_process';
const profile=${JSON.stringify(PROFILE)};
if(process.env.JAVA_TOOL_OPTIONS || process.env.JDK_JAVA_OPTIONS || process.env._JAVA_OPTIONS)throw new Error('inherited options leaked into CLI');
if(process.env.JAVA_OPTS && process.env.JAVA_OPTS !== '-Xmx2g')throw new Error('inherited JAVA_OPTS leaked');
const launch=(args,env)=>{const result=spawnSync(process.env.GRAPHITE_JAVA,args,{env,stdio:'inherit'});if(result.error)throw result.error;if(result.status!==0)process.exit(result.status ?? 1);};
if(!process.env.JAVA_OPTS)launch([...profile.split(' '),'-XX:+PrintFlagsFinal','-version'],process.env);
const frontend=process.env.SKIP_AGENT ? process.env.FIXTURE_ORIGINAL_FRONTEND : process.env.GRAPHITE_FRONTEND_JVM;
let options=process.env.JAVA_OPTS || profile;
if(process.env.WRONG_HEAP)options+=' -Xmx1g';
if(process.env.WRONG_GC)options+=' -XX:GCTimeRatio=99';
if(process.env.MIX_PROFILE && process.env.JAVA_OPTS)options=profile+' '+process.env.JAVA_OPTS;
launch(['-jar',frontend,...process.argv.slice(2)],{...process.env,JAVA_TOOL_OPTIONS:options});
`, { mode: 0o700 });
  const output = join(root, 'evidence');
  return {
    args: [script, '--cli', cli, '--frontend', frontend, '--input', input, '--reference', reference, '--output', output, '--include', 'com.acme', '--android-sdk', join(root, 'sdk with spaces')],
    env: { ...process.env, FIXTURE_REFERENCE: reference, FIXTURE_ORIGINAL_FRONTEND: frontend,
      JAVA_TOOL_OPTIONS: '-Xmx99g', JAVA_OPTS: '-Xmx100g', JDK_JAVA_OPTIONS: '-Xmx101g', _JAVA_OPTIONS: '-Xmx102g' },
    output,
  };
}

test('JAR agent checks the real build JVM defaults and override after environment cleanup', t => {
  const root = mkdtempSync(join(tmpdir(), 'build-defaults-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const setup = fixture(root);
  const originalFrontend = readFileSync(setup.env.FIXTURE_ORIGINAL_FRONTEND!);
  const result = spawnSync(process.execPath, setup.args, { env: setup.env, encoding: 'utf8', timeout: 30_000 });
  assert.equal(result.status, 0, `${result.stderr}\n${result.stdout}`);
  assert.deepEqual(readFileSync(setup.env.FIXTURE_ORIGINAL_FRONTEND!), originalFrontend, 'original frontend JAR must remain unchanged');
  assert.match(result.stdout, /PASS default:/);
  assert.match(result.stdout, /PASS explicit-small-heap:/);
  const defaults = JSON.parse(readFileSync(join(setup.output, 'default.json'), 'utf8'));
  assert.deepEqual(defaults, { case: 'default', maxHeapBytes: 8 * 1024 ** 3, useG1GC: true,
    g1MaxNewSizePercent: 30, minHeapFreeRatio: 20, gcTimeRatio: 4 });
  const override = JSON.parse(readFileSync(join(setup.output, 'explicit-small-heap.json'), 'utf8'));
  assert.equal(override.maxHeapBytes, 2 * 1024 ** 3);
  assert.match(readFileSync(join(setup.output, 'default.stdout'), 'utf8'), /actual JVM fixture build/);
  assert.ok(!existsSync(join(setup.output, 'java-observer.ts')));
  const repeated = spawnSync(process.execPath, setup.args, { env: setup.env, encoding: 'utf8' });
  assert.notEqual(repeated.status, 0, 'existing evidence must not be reused');
});

test('wrong effective heap or GC, omitted agent and corrupted graph fail the executable check', t => {
  for (const [variable, expected] of [
    ['WRONG_HEAP', /Effective JVM MaxHeapSize/],
    ['WRONG_GC', /Effective JVM GCTimeRatio/],
    ['MIX_PROFILE', /Explicit override must contain only -Xmx2g/],
    ['SKIP_AGENT', /ENOENT/],
    ['CORRUPT_GRAPH', /Persisted file differs/],
  ] as const) {
    const root = mkdtempSync(join(tmpdir(), 'build-defaults-reject-'));
    t.after(() => rmSync(root, { recursive: true, force: true }));
    const setup = fixture(root);
    const result = spawnSync(process.execPath, setup.args, { env: { ...setup.env, [variable]: '1' }, encoding: 'utf8', timeout: 30_000 });
    assert.notEqual(result.status, 0);
    const failedCase = variable === 'MIX_PROFILE' ? 'explicit-small-heap' : 'default';
    const javaError = readFileSync(join(setup.output, `${failedCase}.stderr`), 'utf8');
    assert.match(result.stderr + javaError, expected);
    if (variable.startsWith('WRONG_') || variable === 'MIX_PROFILE') {
      assert.ok(!existsSync(join(setup.output, `${failedCase}.json`)), 'failed JVM validation must not produce a receipt');
      assert.ok(!existsSync(join(setup.output, failedCase)), 'frontend main must not run after failed validation');
    }
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
