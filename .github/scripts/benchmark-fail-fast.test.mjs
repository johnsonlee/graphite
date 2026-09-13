import assert from 'node:assert/strict';
import fs from 'node:fs';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const workflow = fs.readFileSync(new URL('../workflows/benchmark.yml', import.meta.url), 'utf8');
const starts = [...workflow.matchAll(/^  ([a-z][a-z0-9-]*):\n/gm)];
const jobs = new Map(starts.map((match, i) => [match[1], workflow.slice(match.index, starts[i + 1]?.index)]));
const needs = name => jobs.get(name).match(/^    needs: \[([^\]]+)\]/m)?.[1].split(',').map(s => s.trim()) ?? [];
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
function script(name) {
    const body = jobs.get(name);
    const marker = '        script: |\n';
    assert.ok(body.includes(marker));
    return body.slice(body.indexOf(marker) + marker.length).split('\n').map(line => line.replace(/^          /, '')).join('\n');
}
async function monitor(name, snapshots, { apiError, clock = Date } = {}) {
    const calls = [], cancellations = [], messages = [], delays = [];
    const github = {
        paginate: async (route, parameters) => {
            calls.push({ route, parameters });
            if (apiError) throw apiError;
            assert.ok(snapshots.length, 'monitor must terminate or cancel rather than poll forever');
            return snapshots.shift();
        },
        rest: { actions: { cancelWorkflowRun: async parameters => { cancellations.push(parameters); } } },
    };
    await new AsyncFunction('github', 'context', 'core', 'process', 'setTimeout', 'Date', script(name))(
        github, { repo: { owner: 'owner', repo: 'repo' }, runId: 123 },
        { error: message => messages.push(message) }, { env: { GITHUB_RUN_ATTEMPT: '2' } },
        (resolve, delay) => { delays.push(delay); resolve(); }, clock);
    return { calls, cancellations, messages, delays };
}
const completed = (name, conclusion = 'success') => ({ name, status: 'completed', conclusion, html_url: `https://example.test/${name}` });
const active = name => ({ name, status: 'in_progress', conclusion: null });
const verdicts = () => Array.from({ length: 72 }, (_, i) => completed(`wide-query-latency-query-${i}`));

test('cheap contracts gate expensive entry jobs and real CPU accounting gates fixture64 without DAG cycles', () => {
    const visited = new Set(), visiting = new Set();
    function visit(name) {
        assert.ok(jobs.has(name), `unknown dependency ${name}`);
        assert.ok(!visiting.has(name), `dependency cycle at ${name}`);
        if (visited.has(name)) return;
        visiting.add(name);
        needs(name).forEach(visit);
        visiting.delete(name); visited.add(name);
    }
    for (const name of jobs.keys()) visit(name);
    assert.deepEqual(needs('benchmark-prerequisites'), ['candidate-gate-tests', 'build-explore-jmh']);
    assert.deepEqual(needs('prepare-fixture64'), ['benchmark-prerequisites', 'validate-cpu-accounting']);
    for (const name of ['build-wrapped-query-jmh', 'method-level', 'budgeted-collection', 'budgeted-mapped-string', 'large-corpus']) {
        assert.ok(needs(name).includes('benchmark-prerequisites'), `${name} starts only after cheap contracts`);
    }
    const prerequisites = jobs.get('benchmark-prerequisites');
    assert.match(prerequisites, /MethodBenchmarkServerLifecycleContract/);
    assert.match(prerequisites, /MethodCompatibilityCpuAccountingContract/);
    assert.match(prerequisites, /sha256sum -c candidate-explore-jmh.jar.sha256/);
    assert.match(prerequisites, /if: always\(\)/);
    assert.doesNotMatch(prerequisites, /prepareBenchmarkFixtures|prepare-fixture64|java -jar/);
    for (const name of ['benchmark-fail-fast', 'benchmark-fail-fast-late', 'benchmark-prerequisites']) {
        assert.ok(needs('benchmark-regression-gate').includes(name));
    }
    assert.match(jobs.get('benchmark-regression-gate'), /FAIL_FAST_LATE_JOB.*needs.benchmark-fail-fast-late.result/);
});

test('heavy matrices cancel siblings while cheap named query verdicts remain complete diagnostics', () => {
    for (const name of ['build-explore-jmh', 'build-wrapped-query-jmh', 'method-compatibility-shard', 'wrapped-query-latency-shard', 'wide-latency-measurements']) {
        assert.match(jobs.get(name), /fail-fast: true/);
    }
    assert.match(jobs.get('wide-query-latency-gate'), /fail-fast: false/);
    for (const name of ['benchmark-fail-fast', 'benchmark-fail-fast-late']) {
        const job = jobs.get(name);
        assert.match(job, /permissions:\n      actions: write/);
        assert.doesNotMatch(job, /checkout@|contents: write|pull-requests: write|\n      run:/);
    }
    assert.deepEqual(needs('benchmark-fail-fast'), ['benchmark-prerequisites']);
    assert.deepEqual(needs('benchmark-fail-fast-late'), ['build-wide-latency-bundle']);
});

test('monitor paginates only current attempt and waits for terminal failure before cancelling', async () => {
    const result = await monitor('benchmark-fail-fast', [
        [{ ...active('validate-cpu-accounting'), conclusion: 'failure' }],
        [completed('validate-cpu-accounting', 'failure'), active('large-corpus-benchmarks')],
    ]);
    assert.equal(result.calls.length, 2);
    for (const { route, parameters } of result.calls) {
        assert.match(route, /attempts\/\{attempt_number\}\/jobs$/);
        assert.deepEqual(parameters, { owner: 'owner', repo: 'repo', run_id: 123, attempt_number: 2, per_page: 100 });
    }
    assert.deepEqual(result.cancellations, [{ owner: 'owner', repo: 'repo', run_id: 123 }]);
    assert.match(result.messages[0], /validate-cpu-accounting: failure.*Logs:/);
    assert.deepEqual(result.delays, [15000]);
});

test('early monitor covers the queue gap until late monitor is actually running', async () => {
    const result = await monitor('benchmark-fail-fast', [
        [completed('build-wide-latency-bundle'), { name: 'benchmark-fail-fast-late', status: 'queued' }],
        [completed('build-wide-latency-bundle'), active('benchmark-fail-fast-late')],
    ]);
    assert.equal(result.calls.length, 2);
    assert.equal(result.cancellations.length, 0);
    assert.deepEqual(result.delays, [15000]);
    const failed = await monitor('benchmark-fail-fast', [[completed('benchmark-fail-fast-late', 'failure')]]);
    assert.equal(failed.cancellations.length, 1);
});

test('late monitor ends after all work without waiting on itself or dependent reporting jobs', async () => {
    const excluded = [active('benchmark-fail-fast'), active('benchmark-fail-fast-late'),
        active('benchmark-regression-gate'), completed('benchmark-comment', 'failure')];
    const result = await monitor('benchmark-fail-fast-late', [
        [...verdicts(), ...excluded, active('large-corpus-benchmarks')],
        [...verdicts(), ...excluded, completed('large-corpus-benchmarks')],
    ]);
    assert.equal(result.calls.length, 2);
    assert.equal(result.cancellations.length, 0);
});

test('API and observation failures are visible rather than silently disabling cancellation', async () => {
    await assert.rejects(monitor('benchmark-fail-fast', [], { apiError: new Error('403 permission denied') }), /403 permission denied/);
    let calls = 0;
    await assert.rejects(monitor('benchmark-fail-fast-late', [], {
        clock: { now: () => calls++ === 0 ? 0 : 360 * 60 * 1000 },
    }), /bounded observation window/);
    const earlyFailure = await monitor('benchmark-fail-fast-late', [[completed('benchmark-fail-fast', 'failure')]]);
    assert.equal(earlyFailure.cancellations.length, 1);
    for (const conclusion of ['timed_out', 'action_required', 'startup_failure']) {
        const result = await monitor('benchmark-fail-fast-late', [[completed('wide-latency-measurements-full-scan', conclusion)]]);
        assert.equal(result.cancellations.length, 1);
    }
});


test('fork policy skips write-token monitors explicitly without accepting failed or cancelled monitors', () => {
    const policy = /if: \$\{\{ github\.event\.pull_request\.head\.repo\.full_name == github\.repository \}\}/;
    for (const name of ['benchmark-fail-fast', 'benchmark-fail-fast-late']) assert.match(jobs.get(name), policy);
    const aggregate = jobs.get('benchmark-regression-gate');
    assert.match(aggregate, /MONITORS_REQUIRED: \$\{\{ github\.event\.pull_request\.head\.repo\.full_name == github\.repository \}\}/);
    const start = aggregate.indexOf('        case "${MONITORS_REQUIRED}" in');
    const end = aggregate.indexOf('        if [ "${CANDIDATE_GATE_TEST_JOB}"', start);
    assert.ok(start > 0 && end > start);
    const check = aggregate.slice(start, end);
    for (const required of ['true', 'false', '', 'unexpected']) {
        for (const early of ['success', 'skipped', 'failure', 'cancelled']) {
            for (const late of ['success', 'skipped', 'failure', 'cancelled']) {
                const result = spawnSync('bash', ['-euc', check], { encoding: 'utf8', env: {
                    ...process.env, MONITORS_REQUIRED: required, PREREQUISITE_JOB: 'success',
                    FAIL_FAST_JOB: early, FAIL_FAST_LATE_JOB: late,
                } });
                const expected = required === 'true' ? 'success' : required === 'false' ? 'skipped' : null;
                assert.equal(result.status === 0, expected !== null && early === expected && late === expected,
                    `${required}/${early}/${late}`);
            }
        }
    }
    const failedPrerequisite = spawnSync('bash', ['-euc', check], { env: { ...process.env,
        MONITORS_REQUIRED: 'false', PREREQUISITE_JOB: 'failure', FAIL_FAST_JOB: 'skipped', FAIL_FAST_LATE_JOB: 'skipped',
    } });
    assert.notEqual(failedPrerequisite.status, 0, 'fork policy never waives prerequisite failures');
    assert.doesNotMatch(workflow, /pull_request_target:/);
});
