// Real CLI/JVM launcher correctness, not a performance benchmark.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { accessSync, closeSync, constants, copyFileSync, existsSync, fstatSync, mkdirSync, openSync, readFileSync, readSync, readdirSync, rmdirSync, statSync, unlinkSync, writeFileSync } from 'node:fs';
import { delimiter, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

const OPTIONS = ['JAVA_TOOL_OPTIONS', 'JAVA_OPTS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'];
const BUILD_TIMEOUT_MS = 120_000;
const LOW_CAPACITY_ERROR = 'Error: could not configure the JVM build: automatic JVM heap sizing requires at least 16 GiB of physical/container memory (8 GiB heap plus 8 GiB reserved); set JAVA_OPTS explicitly on smaller systems';

class BuildFailed extends Error {
  readonly exitCode: number | null;
  constructor(exitCode: number | null, signal: NodeJS.Signals | null, stderr: string) {
    const file = openSync(stderr, 'r');
    let diagnostic: string;
    try {
      const size = fstatSync(file).size;
      const bytes = Buffer.alloc(Math.min(size, 32_768));
      const count = readSync(file, bytes, 0, bytes.length, size - bytes.length);
      diagnostic = bytes.subarray(0, count).toString('utf8').trimEnd();
    } finally { closeSync(file); }
    super(`Build failed (${signal ?? exitCode}); see ${stderr}${diagnostic ? `\n${diagnostic}` : ''}`);
    this.exitCode = exitCode;
  }
}

function findTool(name: string, env: NodeJS.ProcessEnv): string {
  for (const directory of (env.PATH ?? '').split(delimiter)) {
    const path = resolve(directory, name);
    try { accessSync(path, constants.X_OK); if (statSync(path).isFile()) return path; } catch { /* Try the next PATH entry. */ }
  }
  throw new Error(`${name} must be installed`);
}

export async function runWithDeadline(command: string, args: string[], env: NodeJS.ProcessEnv, stdout: string, stderr: string, timeoutMs: number): Promise<void> {
  if (process.platform === 'win32') throw new Error('This Linux CI check requires POSIX process groups');
  const out = openSync(stdout, 'w');
  const err = openSync(stderr, 'w');
  const child = spawn(command, args, { env, detached: true, stdio: ['ignore', out, err] });
  let timedOut = false;
  let cancelled: string | undefined;
  const killGroup = (): void => {
    if (child.pid === undefined) return;
    try { process.kill(-child.pid, 'SIGKILL'); } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ESRCH') throw error;
    }
  };
  const interrupt = (): void => { cancelled = 'SIGINT'; killGroup(); };
  const terminate = (): void => { cancelled = 'SIGTERM'; killGroup(); };
  process.on('SIGINT', interrupt);
  process.on('SIGTERM', terminate);
  const timer = setTimeout(() => { timedOut = true; killGroup(); }, timeoutMs);
  try {
    const result = await new Promise<{ code: number | null; signal: NodeJS.Signals | null }>((resolveExit, reject) => {
      child.once('error', reject);
      child.once('close', (code, signal) => resolveExit({ code, signal }));
    });
    if (timedOut) throw new Error(`Build timed out after ${timeoutMs}ms; process group terminated`);
    if (cancelled) throw new Error(`Build cancelled by ${cancelled}; process group terminated`);
    if (result.code !== 0) throw new BuildFailed(result.code, result.signal, stderr);
  } finally {
    clearTimeout(timer);
    process.off('SIGINT', interrupt);
    process.off('SIGTERM', terminate);
    killGroup();
    closeSync(out);
    closeSync(err);
  }
}

function withoutComments(bytes: Buffer): string | undefined {
  if (bytes.includes(0)) return undefined;
  let text: string;
  try { text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes); } catch { return undefined; }
  // Preserve line endings and missing final newlines for every non-comment line.
  return text.match(/[^\n]*\n|[^\n]+$/g)?.filter(line => !line.startsWith('#')).join('') ?? '';
}

export function comparePersisted(reference: string, actual: string): void {
  const expected = readdirSync(reference, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name));
  const observed = readdirSync(actual, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name));
  assert.deepEqual(observed.map(entry => entry.name), expected.map(entry => entry.name), `Persisted entries differ: ${actual}`);
  for (const [index, entry] of expected.entries()) {
    const other = observed[index]!;
    const left = join(reference, entry.name);
    const right = join(actual, entry.name);
    assert.equal(other.isDirectory(), entry.isDirectory(), `Persisted entry type differs: ${right}`);
    assert.equal(other.isFile(), entry.isFile(), `Persisted entry type differs: ${right}`);
    if (entry.isDirectory()) comparePersisted(left, right);
    else if (entry.isFile()) {
      const a = readFileSync(left);
      const b = readFileSync(right);
      if (!a.equals(b)) {
        const filteredA = withoutComments(a);
        const filteredB = withoutComments(b);
        assert.ok(filteredA !== undefined && filteredB !== undefined && filteredA === filteredB, `Persisted file differs: ${right}`);
      }
    } else throw new Error(`Unsupported persisted entry: ${right}`);
  }
}

export async function main(argv: string[] = process.argv.slice(2), inherited: NodeJS.ProcessEnv = process.env): Promise<void> {
  const { values } = parseArgs({ args: argv, strict: true, options: {
    cli: { type: 'string' }, frontend: { type: 'string' }, input: { type: 'string' }, reference: { type: 'string' }, output: { type: 'string' },
    include: { type: 'string', default: 'com.acme' }, 'android-sdk': { type: 'string' },
  } });
  for (const key of ['cli', 'frontend', 'input', 'reference', 'output'] as const) assert.ok(values[key], `Missing --${key}`);
  const cli = resolve(values.cli!);
  const frontend = resolve(values.frontend!);
  const input = resolve(values.input!);
  const reference = resolve(values.reference!);
  const root = resolve(values.output!);
  const java = findTool('java', inherited);
  mkdirSync(dirname(root), { recursive: true });
  mkdirSync(root); // A prior run must not supply stale JVM or graph evidence.
  const env = { ...inherited };
  for (const key of OPTIONS) delete env[key];
  const classes = join(root, 'agent-classes');
  mkdirSync(classes);
  const source = fileURLToPath(new URL('../../../benchmark/jvm/src/main/java/BuildJvmDefaults.java', import.meta.url));
  await runWithDeadline(findTool('javac', env), ['--release', '17', '-d', classes, source], env,
    join(root, 'javac.stdout'), join(root, 'javac.stderr'), BUILD_TIMEOUT_MS);
  // HotSpot's OS bean detects container-aware capacity independently of the
  // native CLI's procfs/cgroup parser. Do not accept an arbitrary exit-1 refusal.
  const capacityLog = join(root, 'memory-capacity.json');
  await runWithDeadline(java, ['-cp', classes, 'BuildJvmDefaults', 'memory-capacity'], env,
    capacityLog, join(root, 'memory-capacity.stderr'), BUILD_TIMEOUT_MS);
  const capacity = JSON.parse(readFileSync(capacityLog, 'utf8'));
  assert.ok(Number.isSafeInteger(capacity.totalMemoryBytes) && capacity.totalMemoryBytes > 0,
    'Missing independently observed JVM memory capacity');
  const checkedFrontend = join(root, 'frontend.jar');
  copyFileSync(frontend, checkedFrontend);
  const manifest = join(root, 'agent.mf');
  writeFileSync(manifest, 'Launcher-Agent-Class: BuildJvmDefaults\n');
  await runWithDeadline(findTool('jar', env), ['--update', '--file', checkedFrontend, '--manifest', manifest,
    '-C', classes, 'BuildJvmDefaults.class'], env, join(root, 'jar.stdout'), join(root, 'jar.stderr'), BUILD_TIMEOUT_MS);
  env.GRAPHITE_JAVA = java;
  env.GRAPHITE_FRONTEND_JVM = checkedFrontend;
  for (const [name, override] of [['default', undefined], ['explicit-small-heap', '-Xmx2g']] as const) {
    const log = join(root, `${name}.json`);
    const caseEnv: NodeJS.ProcessEnv = { ...env, BUILD_JVM_DEFAULTS_CASE: name, BUILD_JVM_DEFAULTS_RECEIPT: log };
    if (override !== undefined) caseEnv.JAVA_OPTS = override;
    const graph = join(root, name);
    const archive = `${graph}.graphite`;
    const buildArgs = ['build', input, '-o', graph, '--include', values.include!];
    if (values['android-sdk']) buildArgs.push('--android-sdk', resolve(values['android-sdk']));
    const stderr = join(root, `${name}.stderr`);
    try {
      await runWithDeadline(cli, buildArgs, caseEnv, join(root, `${name}.stdout`), stderr, BUILD_TIMEOUT_MS);
    } catch (error) {
      // Hosted runners can advertise 16 GB while MemTotal is below 16 GiB.
      // The automatic policy must refuse those hosts rather than violate its
      // reserve. Verify that exact refusal, then still build with the override.
      // Unknown memory/cgroup detection, timeout and JVM failures remain errors.
      const expectedRefusal = `Writing ${archive} (appended .graphite to --output)\n${LOW_CAPACITY_ERROR}`;
      if (name !== 'default' || !(error instanceof BuildFailed) || error.exitCode !== 1
          || readFileSync(stderr, 'utf8').trim() !== expectedRefusal) throw error;
      assert.ok(capacity.totalMemoryBytes < 16 * 1024 ** 3,
        `CLI refused automatic heap despite independently observed capacity ${capacity.totalMemoryBytes}`);
      assert.ok(!existsSync(log), 'capacity refusal must not start the frontend JVM');
      assert.ok(!existsSync(graph), 'capacity refusal must not produce a graph');
      assert.ok(!existsSync(archive), 'capacity refusal must not produce an archive');
      assert.ok(!existsSync(`${archive}.sha256`), 'capacity refusal must not produce a digest');
      writeFileSync(join(root, 'default-refusal.json'), JSON.stringify({
        case: name, outcome: 'expected-low-capacity-refusal', totalMemoryBytes: capacity.totalMemoryBytes, diagnostic: LOW_CAPACITY_ERROR,
      }, null, 2) + '\n');
      console.log('PASS default: low-capacity policy refused automatic heap before frontend launch');
      continue;
    }
    const receipt = JSON.parse(readFileSync(log, 'utf8'));
    assert.equal(receipt.case, name, 'Missing effective JVM configuration receipt');
    assert.ok(!existsSync(graph), 'build must produce an archive, not an output directory');
    assert.ok(statSync(archive).isFile(), 'build must produce a graph archive');
    assert.ok(statSync(`${archive}.sha256`).isFile(), 'build must produce an archive digest');
    await runWithDeadline(cli, ['verify', archive], caseEnv,
      join(root, `${name}.verify.stdout`), join(root, `${name}.verify.stderr`), BUILD_TIMEOUT_MS);
    await runWithDeadline(cli, ['unpack', archive, graph], caseEnv,
      join(root, `${name}.unpack.stdout`), join(root, `${name}.unpack.stderr`), BUILD_TIMEOUT_MS);
    // Packing adds this verified container metadata; every frontend file must still match.
    assert.ok(!existsSync(join(reference, 'META-INF', 'graphite.manifest')), 'reference must be a frontend directory');
    unlinkSync(join(graph, 'META-INF', 'graphite.manifest'));
    if (readdirSync(join(graph, 'META-INF')).length === 0) rmdirSync(join(graph, 'META-INF'));
    comparePersisted(reference, graph);
    console.log(`PASS ${name}: effective JVM configuration and persisted graph match`);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error(error instanceof Error ? error.message : error); process.exitCode = 1; });
}
