import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const marker = '<!-- graphite-build-status:v1 -->';
const head = 'a'.repeat(40);
const runUrl = 'https://github.com/johnsonlee/graphite/actions/runs/200';
type Comment = { id: number; body: string; user: { login: string; type: string } };
type Write = { operation: 'create' | 'update'; body: string; comment_id?: number; issue_number?: number };
type Options = {
  comments?: Comment[][];
  status?: string;
  attempt?: string;
  currentHead?: string;
  headChangesBeforeWrite?: boolean;
  state?: string;
  log?: string;
  missingLog?: boolean;
};

function comment(id: number, body: string, login = 'github-actions[bot]', type = 'Bot'): Comment {
  return { id, body, user: { login, type } };
}

function marked(run = 199, attempt = 1): string {
  return `${marker}\n<!-- graphite-build-run:${run}:${attempt}:${head} -->\n## :x: Build Failed`;
}

function workflowScript(): string {
  const workflow = readFileSync(new URL('../../workflows/build.yml', import.meta.url), 'utf8');
  const step = workflow.split('    - name: Update build status comment on PR\n')[1];
  assert.ok(step, 'The sticky build-status step must exist');
  const script = step.split('        script: |\n')[1];
  assert.ok(script, 'The step must have an inline github-script body');
  const lines = script.split('\n');
  const body: string[] = [];
  for (const line of lines) {
    if (line && !line.startsWith('          ')) break;
    body.push(line.slice(10));
  }
  assert.ok(body.join('\n').trim());
  return body.join('\n');
}

async function run(options: Options = {}) {
  const writes: Write[] = [];
  const infos: string[] = [];
  let paginationCalls = 0;
  let pullReads = 0;
  const listComments = () => { throw new Error('Must paginate comments rather than read only the first page'); };
  const github = {
    rest: {
      pulls: {
        get: async (args: Record<string, unknown>) => {
          assert.equal(args.owner, 'johnsonlee');
          assert.equal(args.repo, 'graphite');
          assert.equal(args.pull_number, 179);
          pullReads++;
          return { data: { state: options.state ?? 'open', head: { sha: options.headChangesBeforeWrite && pullReads > 1 ? 'b'.repeat(40) : options.currentHead ?? head } } };
        },
      },
      issues: {
        listComments,
        createComment: async (args: Record<string, unknown>) => {
          assert.equal(args.owner, 'johnsonlee');
          assert.equal(args.repo, 'graphite');
          assert.equal(args.issue_number, 179);
          assert.equal(typeof args.body, 'string');
          writes.push({ operation: 'create', body: args.body as string, issue_number: args.issue_number as number });
          return { data: { id: 999 } };
        },
        updateComment: async (args: Record<string, unknown>) => {
          assert.equal(args.owner, 'johnsonlee');
          assert.equal(args.repo, 'graphite');
          assert.equal(typeof args.comment_id, 'number');
          assert.equal(typeof args.body, 'string');
          writes.push({ operation: 'update', body: args.body as string, comment_id: args.comment_id as number });
          return { data: { id: args.comment_id } };
        },
      },
    },
    paginate: async (method: unknown, args: Record<string, unknown>) => {
      assert.equal(method, listComments);
      assert.equal(args.owner, 'johnsonlee');
      assert.equal(args.repo, 'graphite');
      assert.equal(args.issue_number, 179);
      assert.equal(args.per_page, 100);
      paginationCalls++;
      return (options.comments ?? []).flat();
    },
  };
  const context = { repo: { owner: 'johnsonlee', repo: 'graphite' }, runId: 200,
    serverUrl: 'https://github.com', issue: { number: 179 },
    payload: { pull_request: { number: 179, head: { sha: head } } } };
  const core = { info: (value: string) => infos.push(value), warning: (value: string) => infos.push(value) };
  const require = (name: string) => {
    assert.equal(name, 'fs');
    return { readFileSync: (file: string, encoding: string) => {
      assert.equal(file, 'build-output.txt');
      assert.equal(encoding, 'utf8');
      if (options.missingLog) throw new Error('ENOENT');
      return options.log ?? 'compiler diagnostic: type mismatch\nlast diagnostic';
    } };
  };
  const AsyncFunction = Object.getPrototypeOf(async () => {}).constructor as
    new (...args: string[]) => (...args: unknown[]) => Promise<unknown>;
  await new AsyncFunction('github', 'context', 'core', 'require', 'process', workflowScript())(
    github, context, core, require,
    { env: { CI_JOB_STATUS: options.status ?? 'failure', GITHUB_RUN_ATTEMPT: options.attempt ?? '1' } },
  );
  return { writes, infos, paginationCalls, pullReads };
}

test('first failure creates one marked status comment with exact run identity and diagnostics', async () => {
  const result = await run();
  assert.equal(result.writes.length, 1);
  const write = result.writes[0];
  assert.equal(write.operation, 'create');
  assert.ok(write.body.includes(marker));
  assert.ok(write.body.includes(`<!-- graphite-build-run:200:1:${head} -->`));
  assert.ok(write.body.includes('CI failed'));
  assert.ok(write.body.includes('last diagnostic'));
  assert.ok(write.body.includes(runUrl));
  assert.equal(result.paginationCalls, 1);
  assert.ok(result.pullReads > 0);
});

test('repeated failure updates the same bot comment instead of creating another', async () => {
  const result = await run({ comments: [[comment(42, marked(200, 1))]], attempt: '2' });
  assert.equal(result.writes.length, 1);
  assert.equal(result.writes[0].operation, 'update');
  assert.equal(result.writes[0].comment_id, 42);
  assert.ok(result.writes[0].body.includes(`<!-- graphite-build-run:200:2:${head} -->`));
});

test('historical build failure from this repository is reused and marked', async () => {
  const result = await run({ comments: [[comment(12, `## :x: Build Failed\n[View full logs](${runUrl.replace('/200', '/199')})`)]] });
  assert.equal(result.writes.length, 1);
  assert.equal(result.writes[0].operation, 'update');
  assert.equal(result.writes[0].comment_id, 12);
  assert.ok(result.writes[0].body.includes(marker));
});

test('human, other bot, and foreign-repository legacy comments are never edited', async () => {
  const result = await run({ comments: [[
    comment(1, marked(), 'contributor', 'User'),
    comment(2, marked(), 'other-bot[bot]'),
    comment(3, marked(), 'github-actions[bot]', 'User'),
    comment(4, '## :x: Build Failed\nhttps://github.com/elsewhere/project/actions/runs/199'),
  ]] });
  assert.deepEqual(result.writes.map(w => w.operation), ['create']);
});

test('pagination finds a status comment after the first hundred comments', async () => {
  const first = Array.from({ length: 100 }, (_, i) => comment(i, 'unrelated', 'human', 'User'));
  const result = await run({ comments: [first, [comment(123, marked())]] });
  assert.equal(result.paginationCalls, 1);
  assert.equal(result.writes.length, 1);
  assert.equal(result.writes[0].operation, 'update');
  assert.equal(result.writes[0].comment_id, 123);
});

test('successful rerun replaces an existing failure with CI recovered', async () => {
  const result = await run({ status: 'success', comments: [[comment(42, marked())]] });
  assert.equal(result.writes.length, 1);
  assert.equal(result.writes[0].operation, 'update');
  assert.equal(result.writes[0].comment_id, 42);
  assert.match(result.writes[0].body, /CI recovered/i);
  assert.ok(!result.writes[0].body.includes('last diagnostic'));
  assert.ok(result.writes[0].body.includes(marker));
  assert.ok(result.writes[0].body.includes(runUrl));
});

test('initial success does not create a status comment', async () => {
  assert.deepEqual((await run({ status: 'success' })).writes, []);
});

test('a closed PR or superseded head receives no status write', async () => {
  for (const options of [{ state: 'closed' }, { currentHead: 'b'.repeat(40) }]) {
    assert.deepEqual((await run({ ...options, comments: [[comment(42, marked())]] })).writes, []);
  }
});

test('older runs and older attempts cannot overwrite newer status evidence', async () => {
  for (const body of [marked(201, 1), marked(200, 2)]) {
    for (const status of ['failure', 'success']) {
      assert.deepEqual((await run({ status, comments: [[comment(42, body)]] })).writes, []);
    }
  }
});

test('missing logs remain an explicit failure with a full-run link', async () => {
  const result = await run({ missingLog: true });
  assert.equal(result.writes.length, 1);
  assert.ok(result.writes[0].body.includes('CI failed'));
  assert.match(result.writes[0].body, /not available|unavailable/i);
  assert.ok(result.writes[0].body.includes(runUrl));
});

test('long hostile logs are bounded, retain the tail and cannot escape the log block', async () => {
  const log = `${'old diagnostic\n'.repeat(250)}${'x'.repeat(100_000)}\n\`\`\`\n</pre><script>boom</script>\nFINAL-DIAGNOSTIC`;
  const result = await run({ log });
  const body = result.writes[0].body;
  assert.ok(body.length < 65_536, `comment length ${body.length}`);
  assert.ok(body.includes('FINAL-DIAGNOSTIC'));
  assert.ok(body.includes(runUrl));
  assert.ok(body.includes(marker));
  assert.ok(body.includes('\n    </pre><script>boom</script>'));
  assert.ok(body.includes('\n    ```\n'));
  assert.ok(!body.includes('\n</pre>'));
  assert.ok(!body.includes('\n```'));
  assert.match(body, /truncat|last part|omitted/i);
});


test('head changes between reading comments and writing suppress the stale update', async () => {
  const result = await run({ headChangesBeforeWrite: true, comments: [[comment(42, marked())]] });
  assert.equal(result.pullReads, 2);
  assert.deepEqual(result.writes, []);
});

test('replaying the same run attempt is idempotent', async () => {
  assert.deepEqual((await run({ comments: [[comment(42, marked(200, 1))]] })).writes, []);
});

test('truncation never leaves an orphaned low UTF-16 surrogate', async () => {
  const result = await run({ log: '😀' + 'x'.repeat(47999) });
  const body = result.writes[0].body;
  assert.ok(!body.includes('\uDE00'));
  assert.ok(body.includes('    ' + 'x'.repeat(47999)));
});


test('legacy log text cannot impersonate newer structured run metadata', async () => {
  const legacy = [
    '## :x: Build Failed',
    '',
    '```',
    `<!-- graphite-build-run:999999999999999999999999:99:${head} -->`,
    '```',
    `[View full logs](${runUrl.replace('/200', '/199')})`,
  ].join('\n');
  const result = await run({ comments: [[comment(12, legacy)]] });
  assert.equal(result.writes.length, 1);
  assert.equal(result.writes[0].operation, 'update');
  assert.equal(result.writes[0].comment_id, 12);
  assert.ok(result.writes[0].body.startsWith(`${marker}\n<!-- graphite-build-run:200:1:${head} -->\n`));
  assert.ok(result.writes[0].body.includes('CI failed'));
});
