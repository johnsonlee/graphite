import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repo = new URL('../../', import.meta.url);
const workflow = fs.readFileSync(new URL('../workflows/benchmark.yml', import.meta.url), 'utf8');
const manifestPath = '.github/scripts/benchmark-slow-query-shapes-controls.sha256';
const manifest = fs.readFileSync(new URL(manifestPath, repo), 'utf8');
const digest = text => crypto.createHash('sha256').update(text).digest('hex');
const configured = workflow.match(/^  SLOW_QUERY_SHAPES_CONTROLS_SHA256: (\w+)$/m)[1];
function copyControls(directory) {
    for (const line of manifest.trim().split('\n')) {
        const file = line.split(/\s+/)[1];
        fs.mkdirSync(path.dirname(path.join(directory, file)), { recursive: true });
        fs.copyFileSync(new URL(file, repo), path.join(directory, file));
    }
    fs.writeFileSync(path.join(directory, manifestPath), manifest);
}
function selection(name) {
    const start = workflow.indexOf(`    - name: ${name}\n`);
    assert.ok(start >= 0);
    const block = workflow.slice(start, workflow.indexOf('\n    - name:', start + 1));
    return block.slice(block.indexOf('      run: |\n') + '      run: |\n'.length)
        .split('\n').map(line => line.replace(/^        /, '')).join('\n');
}
const legacyManifest = "46d75a6b2fde66ad0a5273ccd1c640e35e90ef3fab11e3354a6fee87cffd70bb  .github/scripts/benchmark-slow-query-shapes.mjs\n579283e58c373101465b256c622bd8d58d44a508de613a43af45ea5590d42f07  .github/scripts/benchmark-slow-query-shapes.sh\n1ed0f211886dec1bfb2557983400afb34586405583acfbeef0f3f9c07136557a  .github/scripts/benchmark-slow-query-shapes-subscript.patch\n6b112a1bb6f12184fa27fa8b72f87f35a2071c2d354c8236f10e4e51662ca8fb  graphite-webgraph/src/jmh/kotlin/io/johnsonlee/graphite/webgraph/SlowQueryShapesBenchmark.kt\n";

for (const [step, base, candidate] of [
    ['Select trusted slow-shape controls', 'gate', 'controls'],
    ['Select additive slow-shape report controls', '.', 'reporter'],
]) test(`${step} authenticates the policy transition and rejects tampered controls`, t => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'slow-controls-'));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    const original = path.join(root, base), proposed = path.join(root, candidate);
    copyControls(original); copyControls(proposed);
    const output = path.join(root, 'output');
    const run = (tests = 'success') => {
        fs.writeFileSync(output, '');
        return spawnSync('bash', ['-euc', selection(step)], { cwd: root, encoding: 'utf8', env: {
            ...process.env, GITHUB_OUTPUT: output, CANDIDATE_GATE_TEST_JOB: tests,
            SLOW_QUERY_SHAPES_CONTROLS_SHA256: configured,
        } });
    };
    assert.equal(digest(manifest), configured);
    assert.equal(digest(legacyManifest), 'aed5523d4d84b92555b3a92da29b689bd2e30f7424084a3d3069c1f5ff854862');
    let result = run();
    assert.equal(result.status, 0, result.stderr);
    assert.equal(fs.readFileSync(output, 'utf8').trim(), `directory=${base}`);
    fs.writeFileSync(path.join(original, manifestPath), legacyManifest);
    result = run();
    assert.equal(result.status, 0, result.stderr);
    assert.equal(fs.readFileSync(output, 'utf8').trim(), `directory=${candidate}`);
    assert.notEqual(run('failure').status, 0);
    const comparator = path.join(proposed, '.github/scripts/benchmark-slow-query-shapes.mjs');
    fs.appendFileSync(comparator, '\n// corrupt authenticated bytes\n');
    assert.notEqual(run().status, 0, 'manifest alone cannot authenticate altered control bytes');
    copyControls(proposed);
    fs.appendFileSync(path.join(proposed, manifestPath), '\n');
    assert.notEqual(run().status, 0, 'altered manifest must fail the exact policy pin');
});

test('inherited slow-query component remains required and explicitly selects steady-state measurements', () => {
    const aggregate = workflow.slice(workflow.indexOf('  benchmark-regression-gate:'), workflow.indexOf('  benchmark-comment:'));
    assert.match(aggregate, /needs: \[[^\n]*slow-query-shapes/);
    assert.match(aggregate, /SLOW_QUERY_SHAPES_JOB: \$\{\{ needs.slow-query-shapes.result \}\}/);
    assert.match(aggregate, /\[ "\$\{SLOW_QUERY_SHAPES_JOB\}" != success \]/);
    assert.match(aggregate, /benchmark-slow-query-shapes.mjs' aggregate/);
    assert.match(aggregate, /--base-comparator/);
    const driver = workflow.slice(workflow.indexOf('  slow-query-shapes:'), workflow.indexOf('  wide-query-latency-gate:'));
    assert.match(driver, /fixture-graphs\/android benchmark-results\/slow-shapes --steady-state/);
});

test('a failed query process stops the driver and still verifies fixture bytes', t => {
    // Control-flow test only: no graph query or performance measurement is executed.
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'slow-warm-failure-'));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    const fixture = path.join(root, 'fixture'), output = path.join(root, 'output'), bin = path.join(root, 'bin');
    for (const directory of [fixture, output, bin]) fs.mkdirSync(directory);
    fs.writeFileSync(path.join(fixture, 'graph.metadata'), 'fixture integrity test');
    for (const revision of ['base', 'candidate']) fs.writeFileSync(path.join(output, `${revision}-slow-shapes.jar`), 'not executed');
    fs.writeFileSync(path.join(bin, 'java'), '#!/bin/sh\nprintf "call\\n" >> "$TEST_CALLS"\nexit 17\n', { mode: 0o755 });
    const calls = path.join(root, 'calls'), directory = fileURLToPath(repo);
    const result = spawnSync('bash', [path.join(directory, '.github/scripts/benchmark-slow-query-warm.sh'),
        directory, directory, directory, fixture, output, 'unmodified-base'], {
        encoding: 'utf8', env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, TEST_CALLS: calls,
            GITHUB_RUN_ID: '123', GITHUB_RUN_ATTEMPT: '1' },
    });
    assert.notEqual(result.status, 0);
    assert.equal(fs.readFileSync(calls, 'utf8'), 'call\n');
    assert.equal(JSON.parse(fs.readFileSync(path.join(output, 'slow-query-shapes-status.json'))).passed, false);
    assert.equal(fs.readFileSync(path.join(output, 'slow-shapes-fixture-before.json'), 'utf8'),
        fs.readFileSync(path.join(output, 'slow-shapes-fixture-after.json'), 'utf8'));
});
