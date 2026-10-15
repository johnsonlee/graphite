const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { MARKER, existingReport, isStale, bodyFor, publish } = require('./benchmark-comment.cjs');
const run = { id: 20, run_attempt: 2, head_sha: 'a'.repeat(40), html_url: 'https://example.invalid/run' };

test('only the marked bot comment is updated, across pagination and unrelated comments', () => {
  const comments = [{ id: 1, user: { login: 'human' }, body: MARKER },
    { id: 3, user: { login: 'github-actions[bot]' }, body: MARKER + '\nold report' },
    { id: 2, user: { login: 'github-actions[bot]' }, body: 'coverage' }];
  assert.equal(existingReport(comments).id, 3);
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

test('a completed old head never posts or overwrites a report', async () => {
  let writes = 0;
  const github = { paginate: async () => [], rest: {
    pulls: { get: async () => ({ data: { number: 9, state: 'open', base: { repo: { id: 1 } }, head: { sha: 'c'.repeat(40) } } }) },
    actions: { listWorkflowRunArtifacts() {} },
    issues: { updateComment: async () => { writes++; }, createComment: async () => { writes++; } },
  } };
  await publish({ github, core: { info() {} }, directory: '/unused', context: {
    repo: { owner: 'owner', repo: 'repo' }, payload: { repository: { id: 1 }, workflow_run: {
      ...run, repository: { id: 1 }, event: 'pull_request', pull_requests: [{ number: 9 }],
    } },
  } });
  assert.equal(writes, 0);
});

function harness(t, options = {}) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'graphite-comment-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const baseSha = 'b'.repeat(40);
  const current = {
    ...run, conclusion: 'success', event: 'pull_request', repository: { id: 1 },
    pull_requests: [{ number: 9, base: { sha: baseSha } }], ...options.run,
  };
  const initialPull = {
    number: 9, state: 'open', base: { sha: baseSha, repo: { id: 1 } }, head: { sha: run.head_sha },
  };
  const reportDirectory = path.join(directory, 'benchmark-report-9');
  if (options.report !== false) {
    fs.mkdirSync(reportDirectory);
    fs.writeFileSync(path.join(reportDirectory, 'summary.md'), options.markdown || 'Measured base/head timings; report only.');
    fs.writeFileSync(path.join(reportDirectory, 'report.json'), JSON.stringify({
      schema: 'graphite-benchmark-report-v1', baseSha, headSha: run.head_sha, ...options.identity,
    }));
  }
  const calls = { creates: [], updates: [], info: [], gets: [], pagination: [] };
  const github = { rest: {
    pulls: { get: async request => {
      calls.gets.push(request);
      return { data: calls.gets.length > 1 ? (options.latestPull || initialPull) : (options.initialPull || initialPull) };
    } },
    repos: { listPullRequestsAssociatedWithCommit() {} },
    actions: { listWorkflowRunArtifacts() {} },
    issues: {
      listComments() {},
      createComment: async request => { calls.creates.push(request); return { data: { id: 100 } }; },
      updateComment: async request => { calls.updates.push(request); return { data: { id: request.comment_id } }; },
    },
  } };
  github.paginate = async (method, request) => {
    calls.pagination.push({ method, request });
    if (method === github.rest.actions.listWorkflowRunArtifacts) {
      return options.artifacts || [{ id: 50, name: 'benchmark-report-9', expired: false }];
    }
    if (method === github.rest.issues.listComments) return options.comments || [];
    if (method === github.rest.repos.listPullRequestsAssociatedWithCommit) {
      return options.associatedPulls || [{ number: 9, base: { sha: baseSha } }];
    }
    throw new Error('Unexpected GitHub pagination method');
  };
  const invoke = () => publish({ github, directory, core: { info: message => calls.info.push(message) },
    context: { repo: { owner: 'johnsonlee', repo: 'graphite' }, payload: { repository: { id: 1 }, workflow_run: current } },
  });
  return { calls, invoke, reportDirectory, current, baseSha, initialPull, github };
}

test('first report creates one comment with actual revisions and artifact provenance', async t => {
  const h = harness(t);
  await h.invoke();
  assert.equal(h.calls.creates.length, 1);
  assert.equal(h.calls.updates.length, 0);
  assert.equal(h.calls.gets.length, 2);
  const request = h.calls.creates[0];
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
  assert.equal(h.calls.updates[0].comment_id, 80);
  assert.ok(h.calls.updates[0].body.includes('graphite-benchmark-run:20:2:'));
  assert.ok(!h.calls.updates[0].body.includes('old report'));
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

test('an older run or old/equal attempt cannot overwrite a marked newer report', async t => {
  for (const recorded of [{ ...run, id: 21 }, { ...run, run_attempt: 3 }, run]) {
    const h = harness(t, { comments: [{ id: 50, user: { login: 'github-actions[bot]' },
      body: bodyFor('newer evidence', recorded, 'b'.repeat(40), run.html_url) }] });
    await h.invoke();
    assert.equal(h.calls.creates.length + h.calls.updates.length, 0);
    assert.equal(h.calls.info.length, 1);
    assert.match(h.calls.info[0], /Ignoring superseded run/);
  }
});

test('a newer retry updates the same comment instead of creating another', async t => {
  const h = harness(t, { comments: [{ id: 50, user: { login: 'github-actions[bot]' },
    body: bodyFor('attempt one', { ...run, run_attempt: 1 }, 'b'.repeat(40), run.html_url) }] });
  await h.invoke();
  assert.equal(h.calls.updates.length, 1);
  assert.equal(h.calls.creates.length, 0);
  assert.equal(h.calls.updates[0].comment_id, 50);
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
  const body = h.calls.creates[0].body;
  assert.match(body, /Correctness\/OOM execution: \*\*failure\*\*/);
  assert.match(body, /No complete report was produced/);
  assert.match(body, /Timings are informational/);
  assert.ok(!body.includes('Measured base/head timings'));
  assert.ok(!body.includes('/artifacts/'));
});

test('missing downloaded Markdown and expired artifacts also use explicit fallback', async t => {
  const missing = harness(t, { report: false });
  await missing.invoke();
  assert.match(missing.calls.creates[0].body, /No complete report was produced/);
  const expired = harness(t, { artifacts: [{ id: 50, name: 'benchmark-report-9', expired: true }] });
  await expired.invoke();
  assert.match(expired.calls.creates[0].body, /No complete report was produced/);
  assert.ok(!expired.calls.creates[0].body.includes('Measured base/head timings'));
});

test('commit association fallback still obtains PR identity from GitHub', async t => {
  const h = harness(t, { run: { pull_requests: [] } });
  await h.invoke();
  const lookup = h.calls.pagination.find(call => call.method === h.github.rest.repos.listPullRequestsAssociatedWithCommit);
  assert.equal(lookup.request.commit_sha, run.head_sha);
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

test('without association base SHA, artifact base must still match the current PR', async t => {
  const h = harness(t, { run: { pull_requests: [{ number: 9 }] }, identity: { baseSha: 'c'.repeat(40) } });
  await assert.rejects(h.invoke, /Report does not describe this workflow run/);
  assert.equal(h.calls.creates.length + h.calls.updates.length, 0);
});

test('artifact base remains guarded on the final read when association omits it', async t => {
  const h = harness(t, { run: { pull_requests: [{ number: 9 }] }, latestPull: {
    number: 9, state: 'open', base: { sha: 'c'.repeat(40), repo: { id: 1 } }, head: { sha: run.head_sha },
  } });
  await h.invoke();
  assert.equal(h.calls.creates.length + h.calls.updates.length, 0);
});

test('failure fallback without a recorded base labels it unavailable', async t => {
  const h = harness(t, { report: false, artifacts: [], run: { conclusion: 'failure', pull_requests: [{ number: 9 }] } });
  await h.invoke();
  assert.match(h.calls.creates[0].body, /Base: `unavailable`/);
  assert.ok(!h.calls.creates[0].body.includes(h.baseSha));
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
  assert.equal(h.calls.updates[0].comment_id, 77);
  assert.ok(h.calls.updates[0].body.startsWith(MARKER));
});
