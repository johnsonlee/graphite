import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import { renderBenchmarkSummary, assertPublicationCurrent, assertCommentSize, MAX_COMMENT_BYTES, publishBenchmarkSummary } from './benchmark-comment.mjs';
const candidateSha = 'a'.repeat(40), baseSha = 'b'.repeat(40);
const runUrl = 'https://github.com/johnsonlee/graphite/actions/runs/123';
function evidence() {
    const report = '### Coverage summary\n| Cypher | `global-wide-pressure` | **FAIL** | Complete |\n### Product performance\n' +
        Array.from({ length: 72 }, (_, i) => `query-${i}: ${'失败'.repeat(3000)}\n`).join('');
    return { report, status: { body: report, baseSha, candidateSha, runUrl, passed: false,
        errors: Array.from({ length: 72 * 12 }, () => '失败'.repeat(1000)) },
        baseSha, candidateSha, runUrl, artifactUrl: `${runUrl}/artifacts/456`, runId: '123', runAttempt: 2 };
}
test('72-query worst-case failure evidence stays bounded without modifying complete reports', () => {
    const input = evidence();
    const original = JSON.stringify(input);
    assert.ok(Buffer.byteLength(input.report) > 298000);
    const body = renderBenchmarkSummary(input);
    assert.ok(Buffer.byteLength(body) < MAX_COMMENT_BYTES);
    assert.match(body, /\*\*FAIL\*\*/);
    assert.match(body, /global-wide-pressure \| FAIL/);
    assert.match(body, /errors: 864/);
    assert.ok(body.includes(candidateSha) && body.includes(baseSha));
    assert.ok(body.includes(input.artifactUrl));
    assert.equal(JSON.stringify(input), original);
});
test('SHA, report bytes, run URL and verdict tampering cannot publish', () => {
    for (const patch of [{ candidateSha: baseSha }, { baseSha: candidateSha }, { body: 'wrong' },
        { runUrl: `${runUrl}0` }, { passed: undefined }]) {
        const input = evidence(); Object.assign(input.status, patch);
        assert.throws(() => renderBenchmarkSummary(input));
    }
    assert.throws(() => assertCommentSize('中'.repeat(MAX_COMMENT_BYTES)));
});
test('old head and older same-head run or attempt cannot replace newer evidence', () => {
    const body = renderBenchmarkSummary(evidence());
    const current = { currentHead: candidateSha, candidateSha, runId: '123', runAttempt: 2, existingBody: body };
    assert.doesNotThrow(() => assertPublicationCurrent(current));
    assert.throws(() => assertPublicationCurrent({ ...current, currentHead: baseSha }), /superseded/);
    assert.throws(() => assertPublicationCurrent({ ...current, runId: '122' }), /newer/);
    assert.throws(() => assertPublicationCurrent({ ...current, runAttempt: 1 }), /newer/);
    assert.doesNotThrow(() => assertPublicationCurrent({ ...current, runId: '124' }));
});
test('publication failures remain visible and stale writers are serialized', () => {
    const workflow = fs.readFileSync(new URL('../workflows/benchmark.yml', import.meta.url), 'utf8').split('  benchmark-comment:\n')[1];
    assert.ok(workflow);
    assert.doesNotMatch(workflow, /continue-on-error/);
    assert.match(workflow, /cancel-in-progress: false/);
    assert.match(workflow, /await publishBenchmarkSummary/);
});

test('HTTP 422 is propagated, and superseded head performs no comment mutation', async () => {
    const body = renderBenchmarkSummary(evidence());
    let writes = 0;
    let currentHead = candidateSha;
    const github = { paginate: async () => [{ id: 7, user: { login: 'github-actions[bot]' }, body }],
        rest: { pulls: { get: async () => ({ data: { head: { sha: currentHead } } }) },
            issues: { listComments() {}, updateComment: async () => { writes++; throw new Error('HTTP 422 Validation Failed'); },
                createComment: async () => { writes++; } } } };
    const input = { github, scope: { owner: 'owner', repo: 'repo' }, issueNumber: 125,
        candidateSha, runId: '123', runAttempt: 2, body };
    await assert.rejects(publishBenchmarkSummary(input), /HTTP 422/);
    assert.equal(writes, 1);
    currentHead = baseSha;
    await assert.rejects(publishBenchmarkSummary(input), /superseded/);
    assert.equal(writes, 1);
});
