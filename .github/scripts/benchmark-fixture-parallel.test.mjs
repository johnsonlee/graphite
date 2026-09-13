import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const workflow = fs.readFileSync(new URL('../workflows/benchmark.yml', import.meta.url), 'utf8');
const starts = [...workflow.matchAll(/^  ([a-z][a-z0-9-]*):\n/gm)];
const jobs = Object.fromEntries(starts.map((m, i) => [m[1], workflow.slice(m.index, starts[i + 1]?.index)]));
function permitted(job, inputs, generated, hit, cancelled = false) {
    const expression = jobs[job].match(/^    if: \$\{\{ (.+) \}\}$/m)[1]
        .replaceAll('needs.prepare-fixture64-inputs.result', JSON.stringify(inputs))
        .replaceAll('needs.generate-fixture64.result', JSON.stringify(generated))
        .replaceAll('needs.prepare-fixture64-inputs.outputs.cache-hit', JSON.stringify(hit))
        .replaceAll('cancelled()', String(cancelled));
    return Function(`return (${expression});`)();
}

test('executed DAG predicates accept verified cache hits and both successful producers only', () => {
    assert.equal(permitted('prepare-fixture64', 'success', 'skipped', 'true'), true);
    assert.equal(permitted('prepare-fixture64', 'success', 'success', 'false'), true);
    assert.equal(permitted('generate-fixture64', 'success', 'skipped', 'true'), false);
    assert.equal(permitted('generate-fixture64', 'success', 'skipped', ''), true);
    for (const hit of ['true', 'false', '']) {
        for (const failure of ['failure', 'cancelled']) {
            assert.equal(permitted('prepare-fixture64', failure, 'success', hit), false);
            assert.equal(permitted('prepare-fixture64', 'success', failure, hit), false);
        }
        assert.equal(permitted('prepare-fixture64', 'success', 'success', hit, true), false);
    }
    assert.equal(permitted('prepare-fixture64', 'success', 'skipped', 'false'), false);
});

test('two independent jobs share sealed inputs; aggregate never builds graphs or changes latency scheduling', () => {
    const producer = jobs['generate-fixture64'];
    assert.match(producer, /runs-on: ubuntu-latest/);
    assert.match(producer, /fail-fast: true\n      max-parallel: 2\n      matrix:\n        copy: \[primary, repeat\]/);
    assert.equal((producer.match(/prepare-fixture64-graphs\.sh/g) ?? []).length, 1);
    for (const job of [producer, jobs['prepare-fixture64']]) {
        assert.match(job, /fixture64-inputs-\$\{\{ github.run_id \}\}-\$\{\{ github.run_attempt \}\}/);
        assert.match(job, /sha256sum --strict -c SHA256SUMS/);
        assert.match(job, /EXPECTED_SHA: \$\{\{ needs.prepare-fixture64-inputs.outputs.inputs-sha \}\}/);
    }
    assert.doesNotMatch(jobs['prepare-fixture64'], /prepare-fixture64-graphs\.sh|gradlew|jmhJar/);
    for (const copy of ['primary', 'repeat']) assert.ok(jobs['prepare-fixture64'].includes(`fixture64-generated-${copy}-`));
    assert.match(jobs['prepare-fixture64-inputs'], /candidate\/\.github\/scripts\/seal-fixture64\.sh/);
    const driver = fs.readFileSync(new URL('./run-wide-latency-shard.sh', import.meta.url), 'utf8');
    assert.match(driver, /for FORK in 1 2 3; do/);
    assert.match(driver, /ORDER=\(candidate base\)/);
    assert.match(driver, /ORDER=\(base candidate\)/);
    assert.doesNotMatch(driver, /java .*&\s*$/m);
});

function fixture(t) {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'parallel-fixture-'));
    t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
    fs.copyFileSync(new URL('./seal-fixture64.sh', import.meta.url), path.join(directory, 'seal-fixture64.sh'));
    fs.mkdirSync(path.join(directory, 'output/graphs'), { recursive: true });
    fs.mkdirSync(path.join(directory, 'repeat'));
    fs.mkdirSync(path.join(directory, 'fixtures'));
    for (const name of ['graphs.tsv', 'fixture-provenance.tsv']) {
        fs.writeFileSync(path.join(directory, 'output/graphs', name), 'proof\n');
        fs.writeFileSync(path.join(directory, 'repeat', name), 'proof\n');
    }
    fs.writeFileSync(path.join(directory, 'generator.jar'), 'generator');
    for (const name of ['android', 'tika', 'hive', 'kotlin']) fs.writeFileSync(path.join(directory, 'fixtures', name + '.jar'), name);
    for (const [name, receipt] of [['test-fixture64-reproducibility.sh', '$5'], ['reuse-fixture64-receipt.sh', '$4']]) {
        fs.writeFileSync(path.join(directory, name), `#!/usr/bin/env bash\nset -euo pipefail\necho '${name}' >> "$TRACE"\ntest "\${REJECT:-}" != true\nprintf '{"passed":true,"inputKey":"original"}\\n' > "${receipt}"\n`, { mode: 0o755 });
    }
    const run = (mode, reject = false) => spawnSync(fs.existsSync('/opt/homebrew/bin/bash') ? '/opt/homebrew/bin/bash' : 'bash',
        ['seal-fixture64.sh', mode, 'generator.jar', 'fixtures', 'output', 'repeat', 'exact-key', 'a'.repeat(40)],
        { cwd: directory, encoding: 'utf8', env: { ...process.env, TRACE: path.join(directory, 'trace'), REJECT: String(reject) } });
    return { directory, run };
}

test('aggregate executes comparison, binds receipt and seals only after success', t => {
    const { directory, run } = fixture(t);
    const result = run('miss'); assert.equal(result.status, 0, result.stderr);
    assert.equal(fs.readFileSync(path.join(directory, 'trace'), 'utf8'), 'test-fixture64-reproducibility.sh\n');
    assert.equal(JSON.parse(fs.readFileSync(path.join(directory, 'output/fixture-reproducibility.json'))).inputKey, 'exact-key');
    assert.equal(JSON.parse(fs.readFileSync(path.join(directory, 'output/fixture64.complete.json'))).candidateSha, 'a'.repeat(40));
});

test('cache hit executes reuse verification without repeat generation', t => {
    const { directory, run } = fixture(t);
    fs.rmSync(path.join(directory, 'repeat'), { recursive: true });
    const result = run('hit'); assert.equal(result.status, 0, result.stderr);
    assert.equal(fs.readFileSync(path.join(directory, 'trace'), 'utf8'), 'reuse-fixture64-receipt.sh\n');
    assert.equal(JSON.parse(fs.readFileSync(path.join(directory, 'output/fixture-reproducibility.json'))).inputKey, 'original');
});

for (const mode of ['hit', 'miss']) test(`${mode} verifier failure cannot write a complete marker`, t => {
    const { directory, run } = fixture(t);
    assert.notEqual(run(mode, true).status, 0);
    assert.equal(fs.existsSync(path.join(directory, 'output/fixture64.complete.json')), false);
});

test('missing second artifact fails before calling legacy script that could regenerate it', t => {
    const { directory, run } = fixture(t);
    fs.rmSync(path.join(directory, 'repeat'), { recursive: true });
    assert.notEqual(run('miss').status, 0);
    assert.equal(fs.existsSync(path.join(directory, 'trace')), false);
    assert.equal(fs.existsSync(path.join(directory, 'repeat')), false);
    assert.equal(fs.existsSync(path.join(directory, 'output/fixture64.complete.json')), false);
});

test('executed producer verification rejects changed JARs, checksum list, and runtime', t => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'fixture-inputs-'));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    const input = path.join(root, 'fixture64-inputs');
    fs.mkdirSync(input); fs.mkdirSync(path.join(root, 'bin'));
    fs.writeFileSync(path.join(input, 'generator.jar'), 'same-jar');
    fs.writeFileSync(path.join(input, 'java-runtime.txt'), '17.0.20+1\n');
    fs.writeFileSync(path.join(root, 'bin/java'), '#!/bin/sh\necho "    java.runtime.version = ${TEST_JAVA_VERSION}"\n', { mode: 0o755 });
    const sum = spawnSync('bash', ['-c', 'sha256sum generator.jar java-runtime.txt > SHA256SUMS; sha256sum SHA256SUMS'], { cwd: input, encoding: 'utf8' });
    assert.equal(sum.status, 0, sum.stderr);
    const body = jobs['generate-fixture64'].split('    - name: Verify identical generator and fixture inputs\n')[1]
        .split('      run: |\n')[1].split('\n    - ')[0].split('\n').map(line => line.replace(/^        /, '')).join('\n');
    const run = (runtime = '17.0.20+1') => spawnSync('bash', ['-c', body], { cwd: root, encoding: 'utf8',
        env: { ...process.env, PATH: path.join(root, 'bin') + ':' + process.env.PATH, EXPECTED_SHA: sum.stdout.split(' ')[0], TEST_JAVA_VERSION: runtime } });
    assert.equal(run().status, 0);
    assert.notEqual(run('17.0.21+1').status, 0);
    fs.writeFileSync(path.join(input, 'generator.jar'), 'tampered');
    assert.notEqual(run().status, 0);
    fs.writeFileSync(path.join(input, 'generator.jar'), 'same-jar');
    fs.appendFileSync(path.join(input, 'SHA256SUMS'), '\n');
    assert.notEqual(run().status, 0);
    assert.match(jobs['prepare-fixture64-inputs'], /fixture64-real-v2-temurin17-\$\{\{ steps.fixture-java.outputs.version \}\}/);
    for (const job of ['generate-fixture64', 'prepare-fixture64']) {
        assert.match(jobs[job], /java-version: \$\{\{ needs.prepare-fixture64-inputs.outputs.java-version \}\}/);
    }
});
