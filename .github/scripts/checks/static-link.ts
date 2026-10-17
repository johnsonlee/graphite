import { spawnSync } from 'node:child_process';
import { statSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

function inspect(command: string, args: string[], env: NodeJS.ProcessEnv): string {
  const result = spawnSync(command, args, { env, encoding: 'utf8', timeout: 30_000, maxBuffer: 16 * 1024 * 1024 });
  if (result.error || result.status !== 0) {
    throw new Error(`${command} inspection failed: ${result.error?.message ?? result.signal ?? result.status}\n${result.stderr ?? ''}`);
  }
  return `${result.stdout}${result.stderr}`;
}

export function checkStatic(binary: string, env: NodeJS.ProcessEnv = process.env): string {
  if (!statSync(binary).isFile()) throw new Error(`Not a regular binary file: ${binary}`);
  const description = inspect('file', ['-b', binary], env).trim();
  if (!description.includes('statically linked') && !description.includes('static-pie linked')) {
    throw new Error(`${binary} is not statically linked: ${description}`);
  }
  // -T exits unsuccessfully for legitimate static ELF files. -x inspects both
  // regular/dynamic symbols and headers, and must itself succeed.
  const symbols = inspect('objdump', ['-x', binary], env);
  if (symbols.includes('GLIBC_')) throw new Error(`${binary} references GLIBC symbols`);
  return `${binary}: ${description}`;
}

export function main(args: string[] = process.argv.slice(2)): void {
  if (args.length !== 1) throw new Error('Usage: static-link.ts <binary>');
  console.log(checkStatic(resolve(args[0]!)));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(); } catch (error) { console.error(error instanceof Error ? error.message : error); process.exitCode = 1; }
}
