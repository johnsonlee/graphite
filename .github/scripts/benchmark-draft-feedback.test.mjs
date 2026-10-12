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

// The complete pre-matrix command inventory: additions/removals need an explicit contract update.
const expectedCommands = {
    "controls": [
        "python3 -B -m unittest discover -s candidate/.github/scripts -p 'test_jvm_pressure_source_rules.py'",
        "python3 -B -m unittest discover -s candidate/.github/scripts -p 'test_native_pressure_checkpoint_smoke.py' -v",
        "node --test candidate/.github/scripts/benchmark-*.test.mjs",
        "python3 -m unittest discover -s candidate/.github/scripts -p 'test_multigraph_pressure.py'",
        "python3 -m unittest discover -s candidate/.github/scripts -p 'test_native_legal_response.py'",
        "python3 -m unittest discover -s candidate/.github/scripts -p 'test_prepare_native_pressure_plan.py'",
        "python3 -B -m unittest discover -s candidate/.github/scripts -p 'test_native_loading.py'",
        "python3 -m unittest discover -s candidate/.github/scripts -p 'test_run_prepared_native_pressure.py'",
        "python3 -m unittest discover -s candidate/.github/scripts -p 'test_real64_construction.py'",
        "python3 -m unittest discover -s candidate/.github/scripts -p 'test_real64_loading.py'",
        "python3 -B -m unittest discover -s candidate/.github/scripts -p 'test_real64_lifecycle_split.py'",
        "python3 -m unittest discover -s candidate/.github/scripts -p 'test_produce_native_pressure_artifacts.py'",
        "python3 -m unittest discover -s candidate/.github/scripts -p 'test_audit_native_pressure_artifacts.py'",
        "python3 -m unittest discover -s candidate/.github/scripts -p 'test_native_portable_oracles.py'",
        "python3 -m unittest discover -s candidate/.github/scripts -p 'test_native_query_correctness.py'",
        "python3 -m unittest discover -s candidate/backend/bench -p 'test_snapshot*.py'"
    ],
    "jvm-handoff": [
        "python3 -B -m unittest discover -s candidate/.github/scripts -p 'test_prepare_jvm_pressure_plan.py'",
        "python3 -B -m unittest discover -s candidate/.github/scripts -p 'test_jvm_pending_handoff.py'",
        "python3 -m unittest discover -s candidate/.github/scripts -p 'test_jvm_pressure_oracles.py'",
        "python3 -B -m unittest discover -s candidate/.github/scripts -p 'test_jvm_pressure_inputs.py'",
        "python3 -B -m unittest discover -s candidate/.github/scripts -p 'test_jvm_pressure_derivation.py'",
        "python3 -B -m unittest discover -s candidate/.github/scripts -p 'test_jvm_pressure_distinct.py'",
        "python3 -B -m unittest discover -s candidate/.github/scripts -p 'test_jvm_primitive_facts.py'",
        "python3 -B -m unittest discover -s candidate/.github/scripts -p 'test_derive_jvm_pressure_oracles.py'",
        "python3 -B -m unittest discover -s candidate/.github/scripts -p 'test_jvm_query_correctness.py'"
    ],
    "core-protocol": [
        "python3 -B -m unittest discover -s candidate/.github/scripts -p 'test_native_pressure_checkpoint.py'",
        "python3 -B -m unittest discover -s candidate/.github/scripts -p 'test_native_producer_workflow.py'",
        "python3 -B -m unittest discover -s candidate/.github/scripts -p 'test_native_producer_arm_checkpoint.py'",
        "python3 -B -m unittest discover -s candidate/.github/scripts -p 'test_native_producer_merge.py'",
        "python3 -B -m unittest discover -s candidate/.github/scripts -p 'test_native_pressure_restore_chain.py'",
        "python3 -B -m unittest discover -s candidate/.github/scripts -p 'test_native_pressure_consumer_chain.py'",
        "python3 -B -m unittest discover -s candidate/.github/scripts -p 'test_native_pressure_worker_chain.py'",
        "python3 -m unittest discover -s candidate/.github/scripts -p 'test_run_native_core_equivalence.py'",
        "python3 -B -m unittest discover -s candidate/.github/scripts -p 'test_native_core_worker_inputs.py'",
        "python3 -m unittest discover -s candidate/.github/scripts/native_core_proof -t candidate/.github/scripts -p '*_test.py'",
        "python3 -m unittest discover -s candidate/.github/scripts -p 'test_export_native_core_strings.py'",
        "python3 -m unittest discover -s candidate/.github/scripts -p 'test_export_native_core_marker.py'",
        "python3 -m unittest discover -s candidate/.github/scripts -p 'test_bind_native_core_formatter.py'",
        "python3 -m unittest discover -s candidate/.github/scripts -p 'test_run_native_formatter_tests.py'"
    ],
    "assembly": [
        "python3 -m unittest discover -s candidate/.github/scripts -p 'test_assemble_native_pressure_producers.py'"
    ],
    "normalization": [
        "python3 -m unittest discover -s candidate/.github/scripts -p 'test_normalize_native_pressure_oracles.py'"
    ]
};


test('fast-control matrix dispatches every original command exactly once without suppressing failures', () => {
    const fast = jobs.get('candidate-gate-tests');
    const header = fast.split('    steps:')[0];
    assert.match(header, /name: candidate-gate-tests \(\$\{\{ matrix.group \}\}\)/);
    assert.match(header, /timeout-minutes: 15\n/);
    assert.match(header, /strategy:\n      fail-fast: false\n      matrix:\n        group: \[controls, jvm-handoff, core-protocol, assembly, normalization\]/);
    assert.doesNotMatch(fast, /continue-on-error:|always\(\)|max-parallel:/);
    const dispatched = Object.fromEntries(Object.keys(expectedCommands).map(group => [group, []]));
    const steps = [...fast.matchAll(/^    - name: ([^\n]+)\n([\s\S]*?)(?=^    - name: |$(?![\s\S]))/gm)];
    const checkout = steps.shift();
    assert.equal(checkout[1], 'Checkout PR gate tests');
    assert.match(checkout[2], /uses: actions\/checkout@v7/);
    assert.match(checkout[2], /ref: \$\{\{ github.event.pull_request.head.sha \}\}/);
    assert.match(checkout[2], /path: candidate/);
    assert.match(checkout[2], /persist-credentials: false/);
    assert.doesNotMatch(checkout[2], /\bif:|\brun:/);
    for (const [_, name, body] of steps) {
        const filters = [...body.matchAll(/^      if: \$\{\{ matrix.group == '([a-z-]+)' \}\}$/gm)];
        assert.equal(filters.length, 1, name);
        const group = filters[0][1];
        assert.ok(Object.hasOwn(dispatched, group), name);
        const run = body.match(/^      run: (.+)\n?([\s\S]*)$/m);
        assert.ok(run, name);
        const command = run[1] === '>-' ? run[2].trim().replace(/\s+/g, ' ') : run[1].trim();
        if (run[1] !== '>-') assert.equal(run[2].trim(), '', name);
        dispatched[group].push(command);
    }
    assert.deepEqual(dispatched, expectedCommands);
    const all = Object.values(dispatched).flat();
    assert.equal(all.length, 41);
    assert.equal(new Set(all).size, all.length);
    assert.match(dispatched.controls[0], /test_jvm_pressure_source_rules.py/);
});

test('required acceptance gate rejects every non-success matrix aggregate', t => {
    const gate = jobs.get('benchmark-regression-gate');
    assert.match(gate.split('    steps:')[0], /needs: \[candidate-gate-tests,/);
    const enforce = gate.match(/    - name: Enforce benchmark gate\n([\s\S]*)$/)[1];
    assert.match(enforce, /CANDIDATE_GATE_TEST_JOB: \$\{\{ needs.candidate-gate-tests.result \}\}/);
    const script = enforce.split('      run: |\n')[1].split('\n').map(line => line.replace(/^        /, '')).join('\n');
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'graphite-matrix-gate-'));
    t.after(() => fs.rmSync(directory, {recursive: true, force: true}));
    fs.mkdirSync(path.join(directory, 'benchmark-results'));
    fs.writeFileSync(path.join(directory, 'benchmark-results/benchmark-authoritative-status.json'), '{"passed":true}');
    const env = Object.fromEntries([...enforce.matchAll(/^        (\w+_JOB):/gm)].map(match => [match[1], 'success']));
    assert.equal(Object.keys(env).length, 15);
    for (const result of ['success', 'failure', 'cancelled', 'skipped', '']) {
        const run = spawnSync('bash', ['-e', '-c', script], {cwd: directory, encoding: 'utf8',
            env: {...process.env, ...env, CANDIDATE_GATE_TEST_JOB: result}});
        assert.equal(run.status, result === 'success' ? 0 : 1, result || 'missing');
        if (result !== 'success') assert.match(run.stdout, /Required job CANDIDATE_GATE_TEST_JOB finished/);
    }
});
