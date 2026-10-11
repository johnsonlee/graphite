import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import test from 'node:test';

const workflow = fs.readFileSync(new URL('../workflows/benchmark.yml', import.meta.url), 'utf8');
const jobs = new Map([...workflow.split('jobs:\n')[1].matchAll(/^  ([\w-]+):\n([\s\S]*?)(?=^  [\w-]+:\n|$(?![\s\S]))/gm)]
    .map(match => [match[1], match[2]]));

test('draft submissions retain fast controls and ready transitions trigger full acceptance', () => {
    assert.match(workflow, /types: \[opened, synchronize, reopened, ready_for_review, converted_to_draft\]/);
    assert.match(workflow, /group: benchmark-\$\{\{ github.event.pull_request.number \}\}\n  cancel-in-progress: true/);
    const fast = jobs.get('candidate-gate-tests');
    assert.ok(fast);
    assert.doesNotMatch(fast.split('    steps:')[0], /^    if:/m);
    assert.match(fast, /test_jvm_pressure_source_rules.py/);
    assert.match(fast, /test_native_pressure_checkpoint_smoke.py/);
    assert.match(fast, /node --test candidate\/\.github\/scripts\/benchmark-\*.test.mjs/);
    // Every expensive branch and report consumer must opt out, including jobs
    // with always()/!cancelled() that can otherwise run after skipped parents.
    assert.ok(jobs.size >= 35);
    for (const [name, body] of jobs) {
        if (['candidate-gate-tests', 'benchmark-regression-gate'].includes(name)) continue;
        assert.match(body.split('    steps:')[0], /^    if: \$\{\{ !github.event.pull_request.draft(?: \}\}| &&)/m, name);
    }
});

test('draft aggregate runs and fails even when every fast check passes', t => {
    const gate = jobs.get('benchmark-regression-gate');
    assert.match(gate.split('    steps:')[0], /^    if: always\(\)$/m);
    const first = gate.match(/    - name: Keep draft feedback[\s\S]*?(?=\n    - name:)/)[0];
    assert.match(first, /Keep draft feedback separate from full acceptance/);
    assert.match(first, /if: \$\{\{ github.event.pull_request.draft \}\}/);
    const script = first.split('      run: |\n')[1].split('\n').map(line => line.replace(/^        /, '')).join('\n');
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'graphite-draft-gate-'));
    t.after(() => fs.rmSync(directory, {recursive: true, force: true}));
    for (const result of ['success', 'failure', 'cancelled', 'skipped']) {
        const summary = path.join(directory, result);
        const run = spawnSync('bash', ['-e', '-c', script], {encoding: 'utf8',
            env: {...process.env, CANDIDATE_GATE_TEST_JOB: result, GITHUB_STEP_SUMMARY: summary}});
        assert.equal(run.status, 1, result);
        assert.match(run.stdout, /Full benchmark acceptance is required before merge/);
        assert.match(fs.readFileSync(summary, 'utf8'), /Full benchmark acceptance was not run/);
    }
    assert.equal((gate.match(/if: \$\{\{ always\(\) && !github.event.pull_request.draft \}\}/g) ?? []).length, 3);
    assert.match(gate, /if \[ "\$\{!JOB\}" != success \]/);
    assert.match(gate, /if\(!s.passed\) process.exit\(1\)/);
    assert.doesNotMatch(gate, /continue-on-error: true[\s\S]*Keep draft feedback/);
});
