import test from 'node:test';
import type { TestContext } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { MARKER, existingReport, isStale, bodyFor, publish, GhGitHub, main } from './comment.ts';
import type { Artifact, Association, Comment, GitHub, Pull, Run } from './comment.ts';
const run = { id: 20, run_attempt: 2, head_sha: 'a'.repeat(40), html_url: 'https://example.invalid/run' };

test('only the marked bot comment is updated, across pagination and unrelated comments', () => {
  const comments = [{ id: 1, user: { login: 'human' }, body: MARKER },
    { id: 3, user: { login: 'github-actions[bot]' }, body: MARKER + '\nold report' },
    { id: 2, user: { login: 'github-actions[bot]' }, body: 'coverage' }];
  assert.equal(existingReport(comments)!.id, 3);
});

test('older runs and older/equal attempts cannot overwrite the current report', () => {
  const comment = { body: bodyFor('report', run, 'b'.repeat(40), run.html_url) };
  assert.equal(isStale(comment, { ...run, id: 19 }), true);
  assert.equal(isStale(comment, { ...run, run_attempt: 1 }), true);
  assert.equal(isStale(comment, run), true);
  assert.equal(isStale(comment, { ...run, run_attempt: 3 }), false);
  assert.equal(isStale(comment, { ...run, id: 21, run_attempt: 1 }), false);
});

test('large reports preserve provenance and link to full evidence below the API limit', () => {
  const body = bodyFor('| sample | 100 | 120 |\n'.repeat(10000), run, 'b'.repeat(40), run.html_url);
  assert.ok(body.length < 65536);
  assert.ok(body.startsWith(MARKER));
  assert.match(body, /comment is abbreviated/);
  assert.match(body, new RegExp(run.head_sha));
});

test('a completed old head never posts or overwrites a report', async t => {
  const h = harness(t, { initialPull: {
    number: 9, state: 'open', base: { sha: 'b'.repeat(40), repo: { id: 1 } }, head: { sha: 'c'.repeat(40) },
  } });
  await h.invoke();
  assert.equal(h.calls.creates.length + h.calls.updates.length, 0);
});

interface Options {
  run?: Partial<Run>;
  report?: boolean;
  markdown?: string;
  identity?: { schema?: string; baseSha?: string; headSha?: string };
  latestPull?: Pull;
  initialPull?: Pull;
  comparisonError?: string;
  mergeBaseSha?: string;
  artifacts?: Artifact[];
  comments?: Comment[];
  associatedPulls?: Association[];
}
function harness(t: TestContext, options: Options = {}) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'graphite-comment-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const baseSha = 'b'.repeat(40);
  const current: Run = {
    ...run, conclusion: 'success', event: 'pull_request', repository: { id: 1 },
    pull_requests: [{ number: 9, base: { sha: baseSha } }], ...options.run,
  };
  const initialPull: Pull = {
    number: 9, state: 'open', base: { sha: baseSha, repo: { id: 1 } }, head: { sha: run.head_sha },
  };
  const artifactName = `benchmark-report-9-${current.run_attempt}`;
  const reportDirectory = path.join(directory, artifactName);
  if (options.report !== false) {
    fs.mkdirSync(reportDirectory);
    fs.writeFileSync(path.join(reportDirectory, 'summary.md'), options.markdown || 'Measured base/head timings; report only.');
    fs.writeFileSync(path.join(reportDirectory, 'report.json'), JSON.stringify({
      schema: 'graphite-benchmark-report-v1', baseSha, headSha: run.head_sha, ...options.identity,
    }));
  }
  const repo = { owner: 'johnsonlee', repo: 'graphite' };
  const calls: {
    creates: { owner: string; repo: string; issue_number: number; body: string }[];
    updates: { comment_id: number; body: string }[];
    info: string[];
    gets: number[];
    pagination: { method: string; request: { commit_sha?: string; run_id?: number; issue_number?: number } }[];
    comparisons: { owner: string; repo: string; base: string; head: string }[];
  } = { creates: [], updates: [], info: [], gets: [], pagination: [], comparisons: [] };
  const github: GitHub = {
    async pull(number) {
      calls.gets.push(number);
      return calls.gets.length > 1 ? (options.latestPull || initialPull) : (options.initialPull || initialPull);
    },
    async mergeBase(base, head) {
      calls.comparisons.push({ ...repo, base, head });
      if (options.comparisonError) throw new Error(options.comparisonError);
      return options.mergeBaseSha || baseSha;
    },
    async artifacts(run_id) {
      calls.pagination.push({ method: 'artifacts', request: { run_id } });
      return options.artifacts || [{ id: 50, name: artifactName, expired: false }];
    },
    async comments(issue_number) {
      calls.pagination.push({ method: 'comments', request: { issue_number } });
      return options.comments || [];
    },
    async associatedPulls(commit_sha) {
      calls.pagination.push({ method: 'associated', request: { commit_sha } });
      return options.associatedPulls || [{ number: 9, base: { sha: baseSha } }];
    },
    async create(issue_number, body) { calls.creates.push({ ...repo, issue_number, body }); },
    async update(comment_id, body) { calls.updates.push({ comment_id, body }); },
  };
  const invoke = () => publish({ github, directory, info: message => calls.info.push(message),
    payload: { repository: { id: 1 }, workflow_run: current },
  });
  return { calls, invoke, reportDirectory, current, baseSha, initialPull, github };
}

test('first report creates one comment with actual revisions and artifact provenance', async t => {
  const h = harness(t);
  await h.invoke();
  assert.equal(h.calls.creates.length, 1);
  assert.equal(h.calls.updates.length, 0);
  assert.equal(h.calls.gets.length, 2);
  const request = h.calls.creates[0]!;
  assert.deepEqual({ owner: request.owner, repo: request.repo, issue_number: request.issue_number },
    { owner: 'johnsonlee', repo: 'graphite', issue_number: 9 });
  assert.ok(request.body.startsWith(MARKER));
  assert.ok(request.body.includes(`graphite-benchmark-run:20:2:${run.head_sha}`));
  assert.ok(request.body.includes(h.baseSha));
  assert.ok(request.body.includes(`${run.html_url}/artifacts/50`));
  assert.ok(request.body.includes('Measured base/head timings; report only.'));
});

test('existing bot marker is updated in place and does not create another comment', async t => {
  const previous = { id: 80, user: { login: 'github-actions[bot]' },
    body: bodyFor('old report', { ...run, id: 19 }, 'b'.repeat(40), run.html_url) };
  const h = harness(t, { comments: [
    { id: 1, user: { login: 'human' }, body: MARKER },
    { id: 2, user: { login: 'github-actions[bot]' }, body: 'unrelated coverage comment' }, previous,
  ] });
  await h.invoke();
  assert.equal(h.calls.creates.length, 0);
  assert.equal(h.calls.updates.length, 1);
  assert.equal(h.calls.updates[0]!.comment_id, 80);
  assert.ok(h.calls.updates[0]!.body.includes('graphite-benchmark-run:20:2:'));
  assert.ok(!h.calls.updates[0]!.body.includes('old report'));
});

test('head changing during publication prevents both create and update', async t => {
  for (const existing of [false, true]) {
    const h = harness(t, { latestPull: {
      number: 9, state: 'open', base: { sha: 'b'.repeat(40), repo: { id: 1 } }, head: { sha: 'c'.repeat(40) },
    }, comments: existing ? [{ id: 50, user: { login: 'github-actions[bot]' }, body: MARKER }] : [] });
    await h.invoke();
    assert.equal(h.calls.gets.length, 2);
    assert.equal(h.calls.creates.length + h.calls.updates.length, 0);
  }
});

test('base changing during publication prevents labeling old measurements as current', async t => {
  const h = harness(t, { latestPull: {
    number: 9, state: 'open', base: { sha: 'c'.repeat(40), repo: { id: 1 } }, head: { sha: run.head_sha },
  } });
  await h.invoke();
  assert.equal(h.calls.creates.length + h.calls.updates.length, 0);
});

test('a valid run report quietly skips an already advanced PR base', async t => {
  for (const existing of [false, true]) {
    const h = harness(t, { initialPull: {
      number: 9, state: 'open', base: { sha: 'c'.repeat(40), repo: { id: 1 } }, head: { sha: run.head_sha },
    }, comments: existing ? [{ id: 50, user: { login: 'github-actions[bot]' }, body: MARKER }] : [] });
    await h.invoke();
    assert.equal(h.calls.creates.length + h.calls.updates.length, 0);
    assert.equal(h.calls.gets.length, 1);
    assert.equal(h.calls.pagination.filter(call => call.method === 'comments').length, 0);
    assert.match(h.calls.info[0]!, /PR base has changed/);
  }
});

test('an advanced PR base does not excuse an artifact that misstates its run base', async t => {
  const h = harness(t, { identity: { baseSha: 'c'.repeat(40) }, initialPull: {
    number: 9, state: 'open', base: { sha: 'c'.repeat(40), repo: { id: 1 } }, head: { sha: run.head_sha },
  } });
  await assert.rejects(h.invoke, /Report does not describe this workflow run/);
  assert.equal(h.calls.creates.length + h.calls.updates.length, 0);
  assert.equal(h.calls.info.length, 0);
});

test('missing-report fallback also quietly skips a recorded obsolete base', async t => {
  const h = harness(t, { report: false, artifacts: [], initialPull: {
    number: 9, state: 'open', base: { sha: 'c'.repeat(40), repo: { id: 1 } }, head: { sha: run.head_sha },
  } });
  await h.invoke();
  assert.equal(h.calls.creates.length + h.calls.updates.length, 0);
  assert.match(h.calls.info[0]!, /PR base has changed/);
});

test('an older run or old/equal attempt cannot overwrite a marked newer report', async t => {
  for (const recorded of [{ ...run, id: 21 }, { ...run, run_attempt: 3 }, run]) {
    const h = harness(t, { comments: [{ id: 50, user: { login: 'github-actions[bot]' },
      body: bodyFor('newer evidence', recorded, 'b'.repeat(40), run.html_url) }] });
    await h.invoke();
    assert.equal(h.calls.creates.length + h.calls.updates.length, 0);
    assert.equal(h.calls.info.length, 1);
    assert.match(h.calls.info[0]!, /Ignoring superseded run/);
  }
});

test('a newer retry updates the same comment instead of creating another', async t => {
  const h = harness(t, { comments: [{ id: 50, user: { login: 'github-actions[bot]' },
    body: bodyFor('attempt one', { ...run, run_attempt: 1 }, 'b'.repeat(40), run.html_url) }] });
  await h.invoke();
  assert.equal(h.calls.updates.length, 1);
  assert.equal(h.calls.creates.length, 0);
  assert.equal(h.calls.updates[0]!.comment_id, 50);
});

test('artifact head or base SHA mismatch is rejected without any comment mutation', async t => {
  for (const identity of [{ headSha: 'c'.repeat(40) }, { baseSha: 'c'.repeat(40) }]) {
    const h = harness(t, { identity });
    await assert.rejects(h.invoke, /Report does not describe this workflow run/);
    assert.equal(h.calls.creates.length + h.calls.updates.length, 0);
  }
});

test('missing report produces an explicit failure fallback, never invented measurements', async t => {
  const h = harness(t, { report: false, artifacts: [], run: { conclusion: 'failure' } });
  await h.invoke();
  assert.equal(h.calls.creates.length, 1);
  const body = h.calls.creates[0]!.body;
  assert.match(body, /Correctness\/OOM execution: \*\*failure\*\*/);
  assert.match(body, /No complete report was produced/);
  assert.match(body, /Timings are informational/);
  assert.ok(!body.includes('Measured base/head timings'));
  assert.ok(!body.includes('/artifacts/'));
});

test('missing downloaded Markdown and expired artifacts also use explicit fallback', async t => {
  const missing = harness(t, { report: false });
  await missing.invoke();
  assert.match(missing.calls.creates[0]!.body, /No complete report was produced/);
  const expired = harness(t, { artifacts: [{ id: 50, name: 'benchmark-report-9-2', expired: true }] });
  await expired.invoke();
  assert.match(expired.calls.creates[0]!.body, /No complete report was produced/);
  assert.ok(!expired.calls.creates[0]!.body.includes('Measured base/head timings'));
});

test('commit association fallback still obtains PR identity from GitHub', async t => {
  const h = harness(t, { run: { pull_requests: [] } });
  await h.invoke();
  const lookup = h.calls.pagination.find(call => call.method === 'associated');
  assert.equal(lookup!.request.commit_sha, run.head_sha);
  assert.equal(h.calls.creates.length, 1);
});

test('non-PR and foreign-repository runs perform no publication API calls', async t => {
  for (const change of [{ event: 'push' }, { repository: { id: 2 } }]) {
    const h = harness(t, { run: change });
    await h.invoke();
    assert.equal(h.calls.pagination.length, 0);
    assert.equal(h.calls.gets.length, 0);
    assert.equal(h.calls.creates.length + h.calls.updates.length, 0);
  }
});

test('oversized or redirected identity files are rejected as artifact data', async t => {
  const oversized = harness(t);
  fs.writeFileSync(path.join(oversized.reportDirectory, 'report.json'), ' '.repeat(4 * 1024 * 1024 + 1));
  await assert.rejects(oversized.invoke, /Invalid report identity/);
  assert.equal(oversized.calls.creates.length + oversized.calls.updates.length, 0);
  const redirected = harness(t);
  const identity = path.join(redirected.reportDirectory, 'report.json');
  fs.renameSync(identity, path.join(redirected.reportDirectory, 'outside.json'));
  fs.symlinkSync('outside.json', identity);
  await assert.rejects(redirected.invoke, /Invalid report identity/);
  assert.equal(redirected.calls.creates.length + redirected.calls.updates.length, 0);
});

test('without recorded run base, an artifact cannot establish its own comparison identity', async t => {
  const h = harness(t, { run: { pull_requests: [{ number: 9 }] }, identity: { baseSha: 'c'.repeat(40) } });
  await assert.rejects(h.invoke, /Report does not describe this workflow run/);
  assert.equal(h.calls.creates.length + h.calls.updates.length, 0);
});

test('even a plausible artifact base cannot replace a missing recorded run base', async t => {
  const h = harness(t, { run: { pull_requests: [{ number: 9 }] } });
  await assert.rejects(h.invoke, /Report does not describe this workflow run/);
  assert.equal(h.calls.comparisons.length, 0);
  assert.equal(h.calls.creates.length + h.calls.updates.length, 0);
});

test('failure fallback without a recorded base labels it unavailable', async t => {
  const h = harness(t, { report: false, artifacts: [], run: { conclusion: 'failure', pull_requests: [{ number: 9 }] } });
  await h.invoke();
  assert.match(h.calls.creates[0]!.body, /Base: `unavailable`/);
  assert.ok(!h.calls.creates[0]!.body.includes(h.baseSha));
});

test('an existing legacy benchmark bot report is migrated in place', async t => {
  const legacyMarker = '<!-- graphite-benchmark-regression-gate -->';
  const h = harness(t, { comments: [
    { id: 1, user: { login: 'human' }, body: legacyMarker },
    { id: 77, user: { login: 'github-actions[bot]' }, body: legacyMarker + '\nPrevious gate report' },
  ] });
  await h.invoke();
  assert.equal(h.calls.creates.length, 0);
  assert.equal(h.calls.updates.length, 1);
  assert.equal(h.calls.updates[0]!.comment_id, 77);
  assert.ok(h.calls.updates[0]!.body.startsWith(MARKER));
});


test('a retry never reads an artifact from an earlier attempt', async t => {
  const h = harness(t, { artifacts: [{ id: 49, name: 'benchmark-report-9-1', expired: false }] });
  fs.renameSync(h.reportDirectory, path.join(path.dirname(h.reportDirectory), 'benchmark-report-9-1'));
  await h.invoke();
  assert.equal(h.calls.creates.length, 1);
  assert.match(h.calls.creates[0]!.body, /No complete report was produced/);
  assert.ok(!h.calls.creates[0]!.body.includes('Measured base/head timings'));
  assert.ok(!h.calls.creates[0]!.body.includes('/artifacts/49'));
});

test('a divergent target branch compares and labels the actual merge base', async t => {
  const mergeBase = 'c'.repeat(40);
  const h = harness(t, { mergeBaseSha: mergeBase, identity: { baseSha: mergeBase } });
  await h.invoke();
  assert.deepEqual(h.calls.comparisons, [{ owner: 'johnsonlee', repo: 'graphite', base: h.baseSha, head: run.head_sha }]);
  assert.equal(h.calls.creates.length, 1);
  assert.ok(h.calls.creates[0]!.body.includes(`Base: \`${mergeBase}\``));
  assert.ok(!h.calls.creates[0]!.body.includes(`Base: \`${h.baseSha}\``));
});

test('target branch tip cannot masquerade as comparison merge base', async t => {
  const h = harness(t, { mergeBaseSha: 'c'.repeat(40) });
  await assert.rejects(h.invoke, /Report does not describe this workflow run/);
  assert.equal(h.calls.creates.length + h.calls.updates.length, 0);
});

test('valid merge-base evidence quietly skips an advanced controller base tip', async t => {
  const mergeBase = 'c'.repeat(40);
  const h = harness(t, { mergeBaseSha: mergeBase, identity: { baseSha: mergeBase }, initialPull: {
    number: 9, state: 'open', base: { sha: 'd'.repeat(40), repo: { id: 1 } }, head: { sha: run.head_sha },
  } });
  await h.invoke();
  assert.equal(h.calls.creates.length + h.calls.updates.length, 0);
  assert.match(h.calls.info[0]!, /PR base has changed/);
});

test('merge-base lookup failure cannot authorize report data', async t => {
  const h = harness(t, { comparisonError: 'GitHub comparison unavailable' });
  await assert.rejects(h.invoke, /GitHub comparison unavailable/);
  assert.equal(h.calls.creates.length + h.calls.updates.length, 0);
});

test('missing-report fallback labels unresolved merge base unavailable', async t => {
  const h = harness(t, { comparisonError: 'GitHub comparison unavailable', report: false, artifacts: [] });
  await h.invoke();
  assert.match(h.calls.creates[0]!.body, /Base: `unavailable`/);
  assert.match(h.calls.creates[0]!.body, /No complete report was produced/);
});

test('Unicode truncation retains complete rows and never splits a surrogate pair', () => {
  const row = '| 样本 🧪 | 100 | 120 |\n';
  const body = bodyFor(row.repeat(10000), run, 'b'.repeat(40), run.html_url);
  assert.ok(body.length < 65536);
  assert.equal(Buffer.from(body, 'utf8').toString('utf8'), body);
  for (const line of body.split('\n').filter(line => line.startsWith('|'))) assert.equal(line, row.trimEnd());
  const longLine = bodyFor('🧪'.repeat(100000), run, 'b'.repeat(40), run.html_url);
  assert.ok(longLine.length < 65536);
  assert.ok(!longLine.includes('🧪'));
  assert.match(longLine, /comment is abbreviated/);
});

test('Markdown artifacts also reject symlinks, nonregular files and oversized data', async t => {
  for (const mode of ['symlink', 'directory', 'oversized']) {
    const h = harness(t);
    const filename = path.join(h.reportDirectory, 'summary.md');
    if (mode === 'symlink') {
      fs.renameSync(filename, `${filename}.outside`);
      fs.symlinkSync('summary.md.outside', filename);
    } else if (mode === 'directory') {
      fs.unlinkSync(filename);
      fs.mkdirSync(filename);
    } else fs.writeFileSync(filename, ' '.repeat(4 * 1024 * 1024 + 1));
    await assert.rejects(h.invoke, /Invalid benchmark Markdown artifact/);
    assert.equal(h.calls.creates.length + h.calls.updates.length, 0);
  }
});

test('closed PRs and PRs targeting another repository are never published', async t => {
  for (const pull of [
    { number: 9, state: 'closed', base: { sha: 'b'.repeat(40), repo: { id: 1 } }, head: { sha: run.head_sha } },
    { number: 9, state: 'open', base: { sha: 'b'.repeat(40), repo: { id: 2 } }, head: { sha: run.head_sha } },
  ]) {
    const h = harness(t, { initialPull: pull });
    await h.invoke();
    assert.equal(h.calls.creates.length + h.calls.updates.length, 0);
  }
});

test('gh transport paginates association, artifact and comment reads using GET and flattens every page', async () => {
  const requests: { args: string[]; input: string | undefined }[] = [];
  const github = new GhGitHub('johnsonlee/graphite', (args, input) => {
    requests.push({ args, input });
    if (args[1]!.includes('/commits/')) return JSON.stringify([[{ number: 1 }], [{ number: 9 }]]);
    if (args[1]!.includes('/artifacts')) return JSON.stringify([
      { artifacts: [{ id: 1, name: 'unrelated', expired: false }] },
      { artifacts: [{ id: 50, name: 'benchmark-report-9-2', expired: false }] },
    ]);
    return JSON.stringify([[{ id: 1 }], [{ id: 2 }]]);
  });
  assert.deepEqual(await github.associatedPulls(run.head_sha), [{ number: 1 }, { number: 9 }]);
  assert.deepEqual((await github.artifacts(20)).map(item => item.id), [1, 50]);
  assert.deepEqual((await github.comments(9)).map(item => item.id), [1, 2]);
  assert.deepEqual(requests.map(request => request.args), [
    `commits/${run.head_sha}/pulls`, 'actions/runs/20/artifacts', 'issues/9/comments',
  ].map(endpoint => ['api', `repos/johnsonlee/graphite/${endpoint}?per_page=100`, '--hostname', 'github.com',
    '--method', 'GET', '--header', 'Accept: application/vnd.github+json', '--paginate', '--slurp']));
  assert.ok(requests.every(request => request.input === undefined));
});

test('gh transport resolves exact pull/merge-base endpoints and sends comment JSON only through stdin', async () => {
  const requests: { args: string[]; input: string | undefined }[] = [];
  const github = new GhGitHub('johnsonlee/graphite', (args, input) => {
    requests.push({ args, input });
    if (args[1]!.includes('/compare/')) return JSON.stringify({ merge_base_commit: { sha: 'c'.repeat(40) } });
    return JSON.stringify({ number: 9 });
  });
  assert.equal((await github.pull(9)).number, 9);
  assert.equal(await github.mergeBase('b'.repeat(40), run.head_sha), 'c'.repeat(40));
  const body = '样本 🧪\n"quoted" \\path $(do-not-execute) @file';
  await github.create(9, body);
  await github.update(50, body);
  assert.deepEqual(requests.map(request => request.args[1]), [
    'repos/johnsonlee/graphite/pulls/9',
    `repos/johnsonlee/graphite/compare/${'b'.repeat(40)}...${run.head_sha}`,
    'repos/johnsonlee/graphite/issues/9/comments', 'repos/johnsonlee/graphite/issues/comments/50',
  ]);
  assert.deepEqual(requests.map(request => request.args[5]), ['GET', 'GET', 'POST', 'PATCH']);
  for (const request of requests.slice(2)) {
    assert.deepEqual(request.args.slice(-2), ['--input', '-']);
    assert.deepEqual(JSON.parse(request.input!), { body });
    assert.ok(!request.args.includes(body));
    assert.ok(!request.args.includes('--paginate'));
  }
});

test('gh transport rejects malformed responses and command failures without a write retry', async () => {
  let calls = 0;
  const invalid = new GhGitHub('johnsonlee/graphite', () => { calls++; return 'not JSON'; });
  await assert.rejects(() => invalid.comments(9), SyntaxError);
  assert.equal(calls, 1);
  const failure = new GhGitHub('johnsonlee/graphite', () => { throw new Error('gh failed'); });
  await assert.rejects(() => failure.create(9, 'body'), /gh failed/);
  assert.throws(() => new GhGitHub('https://attacker.invalid/repo'), /Invalid GITHUB_REPOSITORY/);
});

test('CLI rejects missing environment or unsupported arguments before invoking gh', async () => {
  await assert.rejects(() => main([], {}), /Usage:/);
  await assert.rejects(() => main(['--directory', 'reports', '--head', run.head_sha], {}), /Usage:/);
  await assert.rejects(() => main(['--directory', 'reports'], {}), /GITHUB_EVENT_PATH and GITHUB_REPOSITORY are required/);
});

test('actual CLI subprocess uses gh arguments and JSON stdin without exposing its token', t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'graphite-comment-cli-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const event = path.join(directory, 'event.json');
  const capture = path.join(directory, 'body.json');
  const commands = path.join(directory, 'commands');
  fs.writeFileSync(event, JSON.stringify({ repository: { id: 1 }, workflow_run: {
    ...run, event: 'pull_request', conclusion: 'failure', repository: { id: 1 },
    pull_requests: [{ number: 9, base: { sha: 'b'.repeat(40) } }],
  } }));
  // This fixture is the only gh executable on the child's prefixed PATH. It
  // serves local responses and records stdin; no test can publish to GitHub.
  fs.writeFileSync(path.join(directory, 'gh'), `#!/bin/sh
printf '%s\\n' "$@" >> "$COMMENT_TEST_COMMANDS"
if [ "$COMMENT_TEST_FAIL" = 1 ]; then echo 'fixture failure' >&2; exit 7; fi
if [ "$6" = POST ]; then cat > "$COMMENT_TEST_CAPTURE"; printf '{}'; exit 0; fi
case "$2" in
  */actions/runs/*) printf '[{"artifacts":[]}]' ;;
  */pulls/9) printf '${JSON.stringify({ number: 9, state: 'open', base: { sha: 'b'.repeat(40), repo: { id: 1 } }, head: { sha: run.head_sha } })}' ;;
  */compare/*) printf '${JSON.stringify({ merge_base_commit: { sha: 'b'.repeat(40) } })}' ;;
  */issues/9/comments*) printf '[[]]' ;;
  *) echo 'unexpected gh endpoint' >&2; exit 8 ;;
esac
`, { mode: 0o700 });
  const env = { ...process.env, PATH: `${directory}${path.delimiter}${process.env.PATH ?? ''}`,
    GITHUB_EVENT_PATH: event, GITHUB_REPOSITORY: 'johnsonlee/graphite', GH_TOKEN: 'test-token-must-not-be-logged',
    COMMENT_TEST_COMMANDS: commands, COMMENT_TEST_CAPTURE: capture, COMMENT_TEST_FAIL: '0' };
  const cli = fileURLToPath(new URL('./comment.ts', import.meta.url));
  const result = spawnSync(process.execPath, [cli, '--directory', directory], { env, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  const body: { body: string } = JSON.parse(fs.readFileSync(capture, 'utf8'));
  assert.match(body.body, /No complete report was produced/);
  assert.ok(body.body.startsWith(MARKER));
  const logged = fs.readFileSync(commands, 'utf8');
  assert.match(logged, /--paginate\n--slurp/);
  assert.match(logged, /--input\n-/);
  assert.ok(!`${logged}${result.stderr}${result.stdout}`.includes(env.GH_TOKEN));
  fs.unlinkSync(capture);
  const failed = spawnSync(process.execPath, [cli, '--directory', directory], {
    env: { ...env, COMMENT_TEST_FAIL: '1' }, encoding: 'utf8',
  });
  assert.equal(failed.status, 1);
  assert.match(failed.stderr, /gh api failed \(7\): fixture failure/);
  assert.ok(!fs.existsSync(capture));
});
