import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

test('workflow source resolution permits base advancement without trusting the candidate controller', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'graphite-sources-'));
  const git = (...args: string[]) => {
    const result = spawnSync('git', args, { cwd: directory, encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout.trim();
  };
  try {
    git('init', '-q');
    git('config', 'user.name', 'Source resolution test');
    git('config', 'user.email', 'test@example.invalid');
    const commit = (message: string) => {
      git('add', '.');
      git('commit', '-qm', message, '--allow-empty');
      return git('rev-parse', 'HEAD');
    };
    const root = commit('common history');
    const anchor = commit('pre-migration base');
    writeFileSync(path.join(directory, 'main.txt'), 'independent main change');
    const advanced = commit('advanced main');
    mkdirSync(path.join(directory, 'benchmark'));
    writeFileSync(path.join(directory, 'benchmark/Cargo.toml'), '# trusted controller');
    const snapshot = commit('reviewed migration snapshot');
    writeFileSync(path.join(directory, 'benchmark/Cargo.toml'), '# newer trusted controller');
    const trusted = commit('land newer controller');
    git('checkout', '--detach', anchor);
    mkdirSync(path.join(directory, 'benchmark'));
    writeFileSync(path.join(directory, 'benchmark/Cargo.toml'), '# candidate must not choose policy');
    const head = commit('candidate');
    git('checkout', '--detach', root);
    const unrelated = commit('sibling history outside migration ancestry');
    git('checkout', '--detach', head);

    const workflow = readFileSync(new URL('../../workflows/benchmark.yml', import.meta.url), 'utf8');
    const block = workflow.split('    - name: Resolve product ancestry and trusted controller source\n')[1]
      .split('    - name: Checkout trusted controller and frontend harness\n')[0];
    const script = block.split('      run: |\n')[1].split('\n').map(line => line.slice(8)).join('\n');
    const anchorMatch = script.match(/git merge-base --is-ancestor ([0-9a-f]{40})/);
    const pinMatch = script.match(/controller_ref=([0-9a-f]{40})/);
    assert.ok(anchorMatch);
    assert.ok(pinMatch);
    // Substitute real fixture commits for the immutable repository-specific anchors.
    const runnable = script.replace(anchorMatch[1], anchor).replace(pinMatch[1], snapshot);
    const run = (base: string, overrides: Record<string, string> = {}) => {
      const output = path.join(directory, 'output');
      writeFileSync(output, '');
      const result = spawnSync('bash', ['-e', '-o', 'pipefail', '-c', runnable], {
        cwd: directory, encoding: 'utf8', env: { ...process.env,
          TARGET_BASE: base, PR_HEAD: head, PR_NUMBER: '179', GITHUB_REPOSITORY: 'johnsonlee/graphite',
          GITHUB_OUTPUT: output, ...overrides },
      });
      return { status: result.status, output: readFileSync(output, 'utf8'), error: result.stderr };
    };
    for (const base of [anchor, advanced]) {
      const result = run(base);
      assert.equal(result.status, 0, result.error);
      assert.equal(result.output, `comparison-base=${anchor}\ncontroller-ref=${snapshot}\n`);
      assert.ok(!result.output.includes(`controller-ref=${head}`));
    }
    // Once available, the target's controller takes precedence, for all PRs.
    const normal = run(trusted, { PR_NUMBER: '180' });
    assert.equal(normal.status, 0, normal.error);
    assert.equal(normal.output, `comparison-base=${anchor}\ncontroller-ref=${trusted}\n`);
    for (const [base, overrides] of [
      [advanced, { PR_NUMBER: '180' }],
      [advanced, { GITHUB_REPOSITORY: 'someone/graphite' }],
      [advanced, { PR_HEAD: anchor }],
      [unrelated, {}],
    ] as [string, Record<string, string>][]) {
      const result = run(base, overrides);
      assert.notEqual(result.status, 0);
      assert.equal(result.output, '');
    }
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
