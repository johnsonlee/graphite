import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { renderBenchmarkSummary, assertPublicationCurrent, assertCommentSize, MAX_COMMENT_BYTES, publishBenchmarkSummary, currentAttemptReportArtifact } from './benchmark-comment.mjs';
const candidateSha = 'a'.repeat(40), baseSha = 'b'.repeat(40);
const runUrl = 'https://github.com/johnsonlee/graphite/actions/runs/123';
function evidence() {
    const report = '### Coverage summary\n| Cypher | `global-wide-pressure` | **FAIL** | Complete |\n### Product performance\n' +
        Array.from({ length: 72 }, (_, i) => `query-${i}: ${'失败'.repeat(3000)}\n`).join('');
    return { report, status: { body: report, baseSha, candidateSha, runUrl, passed: false,
        errors: Array.from({ length: 72 * 12 }, () => '失败'.repeat(1000)) },
        baseSha, candidateSha, runUrl, artifactUrl: `${runUrl}/artifacts/456`, runId: '123', runAttempt: 2, gateResult: 'success' };
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

test('same-head rerun cannot select an earlier attempt report or legacy artifact', () => {
    const artifacts = [{ id: 1, name: 'benchmark-report-125-1' }, { id: 2, name: 'benchmark-report-125' }];
    assert.throws(() => currentAttemptReportArtifact(artifacts, 125, 2), /current attempt/);
    const fresh = { id: 3, name: 'benchmark-report-125-2' };
    assert.equal(currentAttemptReportArtifact([...artifacts, fresh], 125, 2), fresh);
    assert.throws(() => currentAttemptReportArtifact([{ ...fresh, expired: true }], 125, 2), /current attempt/);
    assert.throws(() => currentAttemptReportArtifact([fresh, { ...fresh, id: 4 }], 125, 2), /ambiguous/);
});
test('passing component report cannot publish PASS when required job failed or did not execute', () => {
    const input = evidence();
    input.status.passed = true;
    input.status.errors = [];
    for (const gateResult of ['failure', 'skipped', 'cancelled']) {
        const body = renderBenchmarkSummary({ ...input, gateResult });
        assert.match(body, /\*\*FAIL\*\*/);
        assert.doesNotMatch(body, /\*\*PASS\*\*/);
        assert.ok(body.includes(`benchmark-regression-gate job: ${gateResult}`));
    }
    assert.match(renderBenchmarkSummary({ ...input, gateResult: 'success' }), /\*\*PASS\*\*/);
    assert.throws(() => renderBenchmarkSummary({ ...input, gateResult: undefined }), /gate job result/);
});
test('upload, download and API selection bind publication to current attempt and required job result', () => {
    const workflow = fs.readFileSync(new URL('../workflows/benchmark.yml', import.meta.url), 'utf8');
    assert.equal(workflow.match(/name: benchmark-report-\$\{\{ github.event.pull_request.number \}\}-\$\{\{ github.run_attempt \}\}/g)?.length, 2);
    assert.doesNotMatch(workflow, /name: benchmark-report-\$\{\{ github.event.pull_request.number \}\}\n/);
    assert.match(workflow, /currentAttemptReportArtifact\(artifacts, context.issue.number, process.env.RUN_ATTEMPT\)/);
    assert.match(workflow, /GATE_RESULT: \$\{\{ needs.benchmark-regression-gate.result \}\}/);
    assert.match(workflow, /gateResult: process.env.GATE_RESULT/);
});

test('staging cannot repackage an older aggregate report after a renderer failure', () => {
    const workflow = fs.readFileSync(new URL('../workflows/benchmark.yml', import.meta.url), 'utf8');
    const stage = workflow.split('    - name: Select latest successful benchmark artifacts\n')[1].split('    - name:')[0];
    const script = stage.split("        node <<'NODE'\n")[1].split('        NODE')[0]
        .split('\n').map(line => line.replace(/^        /, '')).join('\n');
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'benchmark-publication-'));
    try {
        const input = path.join(directory, 'downloaded-benchmark-results');
        for (const name of ['benchmark-report-125-1', 'benchmark-report-125-2', 'benchmark-method-125-1']) {
            fs.mkdirSync(path.join(input, name), { recursive: true });
            fs.writeFileSync(path.join(input, name, 'evidence.json'), '{}');
        }
        const result = spawnSync(process.execPath, ['-e', script], { cwd: directory, encoding: 'utf8' });
        assert.equal(result.status, 0, result.stderr);
        assert.deepEqual(fs.readdirSync(input), ['benchmark-method-125-1']);
        assert.ok(fs.existsSync(path.join(input, 'benchmark-method-125-1', 'evidence.json')));
    } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});
