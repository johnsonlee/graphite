// Real CLI/JVM launcher correctness, not a performance benchmark.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { accessSync, closeSync, constants, mkdirSync, openSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { delimiter, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

export const PROFILE = '-Xmx8g -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:G1MaxNewSizePercent=30 -XX:MinHeapFreeRatio=20 -XX:GCTimeRatio=4';
const OPTIONS = ['JAVA_TOOL_OPTIONS', 'JAVA_OPTS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'];
const BUILD_TIMEOUT_MS = 120_000;

function findJava(env: NodeJS.ProcessEnv): string {
  for (const directory of (env.PATH ?? '').split(delimiter)) {
    const path = resolve(directory, 'java');
    try { accessSync(path, constants.X_OK); if (statSync(path).isFile()) return path; } catch { /* Try the next PATH entry. */ }
  }
  throw new Error('Java must be installed');
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
    if (result.code !== 0) throw new Error(`Build failed (${result.signal ?? result.code}); see ${stderr}`);
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

const shellQuote = (text: string): string => `'${text.replaceAll("'", "'\\''")}'`;

function observer(root: string): string {
  const module = join(root, 'java-observer.ts');
  writeFileSync(module, `import { appendFileSync } from 'node:fs';\nimport { spawnSync } from 'node:child_process';\nappendFileSync(process.env.BUILD_JVM_LOG, JSON.stringify({args:process.argv.slice(2),toolOptions:process.env.JAVA_TOOL_OPTIONS ?? null})+'\\n');\nconst result=spawnSync(process.env.BUILD_REAL_JAVA,process.argv.slice(2),{env:process.env,stdio:'inherit'});\nif(result.error)throw result.error;\nif(result.signal)process.kill(process.pid,result.signal);\nelse process.exit(result.status ?? 1);\n`);
  const wrapper = join(root, 'java-observer');
  // Shell exec preserves the group while safely quoting a runtime path with spaces.
  writeFileSync(wrapper, `#!/bin/sh\nexec ${shellQuote(process.execPath)} ${shellQuote(module)} "$@"\n`, { mode: 0o700 });
  return wrapper;
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
  const java = findJava(inherited);
  mkdirSync(dirname(root), { recursive: true });
  mkdirSync(root); // A prior run must not supply stale invocation or graph evidence.
  const env = { ...inherited };
  for (const key of OPTIONS) delete env[key];
  env.GRAPHITE_JAVA = observer(root);
  env.GRAPHITE_FRONTEND_JVM = frontend;
  env.BUILD_REAL_JAVA = java;
  for (const [name, override] of [['default', undefined], ['explicit-small-heap', '-Xmx2g']] as const) {
    const log = join(root, `${name}.jsonl`);
    const caseEnv: NodeJS.ProcessEnv = { ...env, BUILD_JVM_LOG: log };
    if (override !== undefined) caseEnv.JAVA_OPTS = override;
    const graph = join(root, name);
    const buildArgs = ['build', input, '-o', graph, '--include', values.include!];
    if (values['android-sdk']) buildArgs.push('--android-sdk', resolve(values['android-sdk']));
    await runWithDeadline(cli, buildArgs, caseEnv, join(root, `${name}.stdout`), join(root, `${name}.stderr`), BUILD_TIMEOUT_MS);
    const calls: unknown[] = readFileSync(log, 'utf8').trimEnd().split('\n').map(line => JSON.parse(line));
    const expected = [];
    if (override === undefined) expected.push({ args: [...PROFILE.split(' '), '-XX:+PrintFlagsFinal', '-version'], toolOptions: null });
    expected.push({ args: ['-jar', frontend, ...buildArgs], toolOptions: override ?? PROFILE });
    assert.deepEqual(calls, expected, `${name}: unexpected Java invocations`);
    comparePersisted(reference, graph);
    console.log(`PASS ${name}: exact JVM invocations and persisted graph match`);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error(error instanceof Error ? error.message : error); process.exitCode = 1; });
}
