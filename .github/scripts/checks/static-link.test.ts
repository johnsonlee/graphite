import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, join } from 'node:path';
import { checkStatic, main } from './static-link.ts';

test('both static ELF forms require successful complete inspection without GLIBC versions', t => {
  const root = mkdtempSync(join(tmpdir(), 'static-link-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const binary = join(root, 'binary with spaces');
  writeFileSync(binary, 'fixture');
  const log = join(root, 'calls.jsonl');
  for (const tool of ['file', 'objdump']) writeFileSync(join(root, tool), `#!${process.execPath}\nimport { appendFileSync } from 'node:fs';\nappendFileSync(process.env.INSPECT_LOG,JSON.stringify({tool:${JSON.stringify(tool)},args:process.argv.slice(2)})+'\\n');\nconsole.log(process.env.${tool === 'file' ? 'FILE_DESCRIPTION' : 'SYMBOLS'});\nprocess.exit(Number(process.env.${tool === 'file' ? 'FILE_STATUS' : 'OBJDUMP_STATUS'} ?? 0));\n`, { mode: 0o700 });
  const env = { ...process.env, PATH: `${root}${delimiter}${process.env.PATH ?? ''}`, INSPECT_LOG: log, SYMBOLS: 'SYMBOL TABLE: musl', FILE_DESCRIPTION: 'ELF 64-bit LSB executable, statically linked' };
  assert.match(checkStatic(binary, env), /statically linked/);
  assert.match(checkStatic(binary, { ...env, FILE_DESCRIPTION: 'ELF 64-bit LSB pie executable, static-pie linked' }), /static-pie linked/);
  assert.deepEqual(readFileSync(log, 'utf8').trim().split('\n').map(line => JSON.parse(line)), [
    { tool: 'file', args: ['-b', binary] }, { tool: 'objdump', args: ['-x', binary] },
    { tool: 'file', args: ['-b', binary] }, { tool: 'objdump', args: ['-x', binary] },
  ]);
  assert.throws(() => checkStatic(binary, { ...env, FILE_DESCRIPTION: 'ELF dynamically linked' }), /not statically linked/);
  assert.throws(() => checkStatic(binary, { ...env, SYMBOLS: '00000 GLIBC_2.34' }), /references GLIBC/);
  assert.throws(() => checkStatic(binary, { ...env, FILE_STATUS: '1' }), /file inspection failed/);
  assert.throws(() => checkStatic(binary, { ...env, OBJDUMP_STATUS: '1', SYMBOLS: '' }), /objdump inspection failed/);
  assert.throws(() => checkStatic(binary, { ...env, PATH: join(root, 'missing-tools') }), /file inspection failed/);
  assert.throws(() => checkStatic(root, env), /Not a regular binary/);
  assert.throws(() => main([]), /Usage:/);
});
