export const COMMENT_MARKER = '<!-- graphite-benchmark-regression-gate -->';
export const MAX_COMMENT_BYTES = 16000;
const metadataPattern = /<!-- graphite-benchmark-publication (\{[^\n]+\}) -->/;

export function renderBenchmarkSummary({ status, report, baseSha, candidateSha, runId, runAttempt, runUrl, artifactUrl, gateResult }) {
    if (![baseSha, candidateSha].every(value => /^[a-f0-9]{40}$/.test(value))) throw new Error('Invalid evidence SHA');
    if (status.baseSha !== baseSha || status.candidateSha !== candidateSha || status.runUrl !== runUrl || status.body !== report) {
        throw new Error('Report does not match exact-head evidence');
    }
    if (typeof status.passed !== 'boolean' || !Array.isArray(status.errors)) throw new Error('Invalid aggregate verdict');
    if (!/^\d+$/.test(String(runId)) || !/^[1-9]\d*$/.test(String(runAttempt))) throw new Error('Invalid run identity');
    for (const url of [runUrl, artifactUrl]) {
        if (!/^https:\/\/[^\s<>()[\]]+$/.test(url)) throw new Error('Invalid evidence URL');
    }
    if (!['success', 'failure', 'skipped', 'cancelled'].includes(gateResult)) throw new Error('Missing required gate job result');
    const passed = status.passed && status.errors.length === 0 && gateResult === 'success';
    const coverage = report.split('### Coverage summary')[1]?.split('### Product performance')[0] ?? '';
    const gates = [...coverage.matchAll(/\| `([^`\n]+)` \| \*\*(PASS|FAIL|MISSING)\*\* \|/g)];
    const clean = value => String(value).replace(/[\r\n`<>|]/g, ' ').slice(0, 180);
    const lines = [COMMENT_MARKER,
        `<!-- graphite-benchmark-publication ${JSON.stringify({ candidateSha, runId: String(runId), runAttempt: Number(runAttempt) })} -->`,
        '## Benchmark Regression Gate', '', `**${passed ? 'PASS' : 'FAIL'}**`, '',
        `Base: \`${baseSha}\``, `PR head: \`${candidateSha}\``,
        `Run: ${runId}, attempt ${runAttempt}`,
        `Required benchmark-regression-gate job: ${gateResult}. Component report aggregate: ${status.passed ? 'PASS' : 'FAIL'}.`, '',
        '| Component report | Result |', '|---|---|',
        ...gates.slice(0, 32).map(([, name, verdict]) => `| ${clean(name)} | ${verdict} |`), '',
        `Aggregate infrastructure errors: ${status.errors.length}. Component failures and every query verdict are in the full report.`,
        ...status.errors.slice(0, 5).map(error => `- ${clean(error)}`), '',
        'This is a bounded publication summary; it does not replace the complete evidence or the required CI check.',
        `[Full reports and status files](${artifactUrl}) · [Run logs and all raw artifacts](${runUrl})`, ''];
    const body = lines.join('\n');
    assertCommentSize(body);
    return body;
}

export function assertCommentSize(body) {
    if (Buffer.byteLength(body, 'utf8') > MAX_COMMENT_BYTES) throw new Error('Benchmark summary exceeds publication byte budget');
}

export function assertPublicationCurrent({ currentHead, candidateSha, runId, runAttempt, existingBody = '' }) {
    if (currentHead !== candidateSha) throw new Error('Refusing publication for superseded PR head');
    const match = existingBody.match(metadataPattern);
    if (!match) return;
    const previous = JSON.parse(match[1]);
    if (!/^\d+$/.test(previous.runId) || !Number.isInteger(previous.runAttempt)) throw new Error('Invalid previous publication identity');
    if (previous.candidateSha === candidateSha &&
        (BigInt(previous.runId) > BigInt(runId) ||
         previous.runId === String(runId) && previous.runAttempt > Number(runAttempt))) {
        throw new Error('Refusing to overwrite newer benchmark evidence');
    }
}

export async function publishBenchmarkSummary({ github, scope, issueNumber, candidateSha, runId, runAttempt, body }) {
    assertCommentSize(body);
    const comments = await github.paginate(github.rest.issues.listComments,
        { ...scope, issue_number: issueNumber, per_page: 100 });
    const existing = comments.filter(comment => comment.user?.login === 'github-actions[bot]' &&
        comment.body?.includes(COMMENT_MARKER));
    const { data: pr } = await github.rest.pulls.get({ ...scope, pull_number: issueNumber });
    for (const comment of existing.length ? existing : [{ body: '' }]) {
        assertPublicationCurrent({ currentHead: pr.head.sha, candidateSha, runId, runAttempt, existingBody: comment.body });
    }
    if (existing.length) {
        return github.rest.issues.updateComment({ ...scope, comment_id: existing[0].id, body });
    }
    return github.rest.issues.createComment({ ...scope, issue_number: issueNumber, body });
}

export function currentAttemptReportArtifact(artifacts, issueNumber, runAttempt) {
    if (!/^[1-9]\d*$/.test(String(issueNumber)) || !/^[1-9]\d*$/.test(String(runAttempt))) {
        throw new Error('Invalid report artifact identity');
    }
    const name = `benchmark-report-${issueNumber}-${runAttempt}`;
    const matches = artifacts.filter(item => item.name === name && !item.expired);
    if (matches.length !== 1) throw new Error('Full benchmark report artifact for current attempt is unavailable or ambiguous');
    return matches[0];
}
