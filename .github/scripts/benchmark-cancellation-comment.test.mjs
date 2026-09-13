import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
const workflow = fs.readFileSync(new URL('../workflows/benchmark.yml', import.meta.url), 'utf8');
const job = workflow.split('  benchmark-comment:\n')[1];
const cancellation = job.split('    - name: Publish bounded cancellation evidence\n')[1];
const source = cancellation.split('        script: |\n')[1].split('\n').map(line => line.replace(/^          /, '')).join('\n');
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
const head = 'a'.repeat(40), base = 'b'.repeat(40);
async function run({ currentHead = head, previous, apiError } = {}) {
    const writes = [], failures = [], reads = [];
    const github = {
        paginate: async (route, args) => {
            reads.push({ route, args });
            if (route === 'jobs') throw new Error('unexpected route');
            if (typeof route === 'string' && route.includes('attempt_number')) {
                assert.equal(args.attempt_number, 2);
                return Array.from({ length: 72 }, () => ({ name: 'query-失败'.repeat(1000), status: 'completed', conclusion: 'failure' }));
            }
            if (route === 'artifacts') return [{ id: 1, name: 'wide-latency-samples-standard-125-2' }, { id: 2, name: 'old-125-1' }];
            if (route === 'comments') return previous ? [{ id: 5, user: { login: 'github-actions[bot]' }, body: previous }] : [];
            throw new Error('Unexpected API route');
        },
        rest: { actions: { listWorkflowRunArtifacts: 'artifacts' },
            pulls: { get: async () => ({ data: { head: { sha: currentHead } } }) },
            issues: { listComments: 'comments', createComment: async input => { if (apiError) throw apiError; writes.push(input); },
                updateComment: async input => { if (apiError) throw apiError; writes.push(input); } } }
    };
    await new AsyncFunction('github', 'context', 'process', 'core', 'Buffer', source)(github,
        { repo: { owner: 'owner', repo: 'repo' }, runId: 123, issue: { number: 125 }, serverUrl: 'https://github.com' },
        { env: { HEAD_SHA: head, BASE_SHA: base, RUN_ATTEMPT: '2' } },
        { setFailed: message => failures.push(message) }, Buffer);
    return { writes, failures, reads };
}
test('cancelled run uses only bounded inline API evidence and marks incomplete verdict visibly', async () => {
    const result = await run();
    assert.equal(result.writes.length, 1);
    const body = result.writes[0].body;
    assert.ok(Buffer.byteLength(body) < 16000);
    assert.match(body, /CANCELLED — complete benchmark verdict unavailable/);
    assert.match(body, /Observed terminal hard failures: 72/);
    assert.ok(body.includes(head) && body.includes(base));
    assert.match(body, /wide-latency-samples-standard-125-2/);
    assert.doesNotMatch(body, /old-125-1|\*\*PASS/);
    assert.equal(result.failures.length, 1);
    assert.equal(result.reads.length, 3);
});
test('cancelled publisher rejects old head, run, attempt and propagates HTTP errors', async () => {
    await assert.rejects(run({ currentHead: base }), /superseded/);
    for (const [runId, runAttempt] of [['124', 1], ['123', 3]]) {
        const previous = '<!-- graphite-benchmark-regression-gate -->\n<!-- graphite-benchmark-publication ' +
            JSON.stringify({ candidateSha: head, runId, runAttempt }) + ' -->';
        await assert.rejects(run({ previous }), /newer/);
    }
    await assert.rejects(run({ apiError: new Error('HTTP 422') }), /HTTP 422/);
});
test('only cheap publisher survives cancellation; normal steps cannot checkout or download then', () => {
    assert.match(job, /^    if: \$\{\{ always\(\) \}\}$/m);
    assert.match(job, /timeout-minutes: 3/);
    const normal = job.split('    - name: Publish bounded cancellation evidence')[0];
    const steps = normal.split('    - name: ').slice(1);
    assert.equal(steps.length, 3);
    for (const step of steps) assert.match(step, /if: \$\{\{ !cancelled\(\) && needs.benchmark-regression-gate.result != 'cancelled' \}\}/);
    assert.match(cancellation, /if: \$\{\{ cancelled\(\) \|\| needs.benchmark-regression-gate.result == 'cancelled' \}\}/);
    assert.doesNotMatch(source, /require\(|import\(|setTimeout|setInterval|downloadArtifact|child_process|exec\(/);
    assert.doesNotMatch(cancellation, /actions\/checkout|actions\/download-artifact|\brun:|java |gradlew|compareWideLatency/);
    assert.doesNotMatch(job, /actions: write|contents: write|continue-on-error/);
});

test('post-cancellation job routes to API cleanup even when its own cancelled() is false', async () => {
    const stepBlocks = job.split('    - name: ').slice(1);
    const eligible = (block, ownCancelled, upstreamResult) => {
        const expression = block.match(/if: \$\{\{ (.+) \}\}/)[1]
            .replaceAll('needs.benchmark-regression-gate.result', 'upstreamResult');
        return new Function('cancelled', 'upstreamResult', `return (${expression});`)(() => ownCancelled, upstreamResult);
    };
    for (const ownCancelled of [false, true]) {
        for (const upstreamResult of ['success', 'failure', 'skipped', 'cancelled']) {
            const selected = stepBlocks.filter(block => eligible(block, ownCancelled, upstreamResult));
            const cancellationExpected = ownCancelled || upstreamResult === 'cancelled';
            if (cancellationExpected) {
                assert.equal(selected.length, 1);
                assert.ok(selected[0].startsWith('Publish bounded cancellation evidence'));
            } else {
                assert.equal(selected.length, 3);
                assert.ok(selected.every(block => !block.startsWith('Publish bounded cancellation evidence')));
            }
        }
    }
    // Reproduce run 34739834718: the new job itself was not cancelled, its prerequisite was.
    const selected = stepBlocks.filter(block => eligible(block, false, 'cancelled'));
    assert.equal(selected.length, 1);
    assert.equal(selected[0].split('        script: |\n')[1].split('\n')
        .map(line => line.replace(/^          /, '')).join('\n'), source);
    const result = await run();
    assert.equal(result.writes.length, 1);
    assert.match(result.writes[0].body, /CANCELLED — complete benchmark verdict unavailable/);
    assert.equal(result.failures.length, 1);
});
