/** Exercise the unchanged publisher CLI and real gh against a local Unix-socket HTTP API.
 * This checks the HTTP/argv boundary, not GitHub permissions or release policy.
 * The real gh binary is required; its Unix-socket transport prevents remote I/O.
 * Child processes receive only a fake token and an isolated gh configuration.
 */
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const tag = 'v9.9.9-rc.1';
const sha = (value: Buffer) => createHash('sha256').update(value).digest('hex');
interface Asset { id: number; name: string; bytes: Buffer }
interface Request { method: string; path: string; body: Buffer }

async function command(program: string, args: string[], env: NodeJS.ProcessEnv): Promise<{ code: number | null; stdout: string; stderr: string }> {
  return new Promise((resolve, reject) => {
    const child = spawn(program, args, { env, detached: true, stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '', stderr = '';
    child.stdout.setEncoding('utf8').on('data', data => { stdout += data; });
    child.stderr.setEncoding('utf8').on('data', data => { stderr += data; });
    const timeout = setTimeout(() => {
      // The CLI runs gh synchronously; terminate its whole group if it stalls.
      if (child.pid !== undefined) {
        try { process.kill(-child.pid, 'SIGKILL'); } catch { /* Already exited. */ }
      }
      reject(new Error('Local publisher integration timed out'));
    }, 5_000);
    child.on('error', error => { clearTimeout(timeout); reject(error); });
    child.on('close', code => { clearTimeout(timeout); resolve({ code, stdout, stderr }); });
  });
}

test('real gh publishes, repairs drafts and verifies immutable assets through a local HTTP API', { timeout: 30_000 }, async t => {
  const directory = mkdtempSync(join(tmpdir(), 'gh-http-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const ghVersion = spawnSync('gh', ['--version'], { encoding: 'utf8', timeout: 5_000 });
  assert.equal(ghVersion.status, 0, 'The release integration test requires the real GitHub CLI');
  assert.match(ghVersion.stdout, /^gh version /);
  const assetDirectory = join(directory, 'release assets'), ghConfig = join(directory, 'gh-config');
  mkdirSync(assetDirectory); mkdirSync(ghConfig);
  const socket = join(directory, 'api.sock');
  writeFileSync(join(ghConfig, 'config.yml'), `http_unix_socket: ${socket}\n`);
  const expected = new Map([
    ['graphite.jar', Buffer.from([0, 255, 128, 10, 13, 0])],
    ['graphite-9.9.9-rc.1-linux.tar.gz', Buffer.from('archive bytes\n')],
  ]);
  for (const [name, bytes] of expected) writeFileSync(join(assetDirectory, name), bytes);
  let draft: boolean | undefined, prerelease = true, nextId = 100;
  let assets: Asset[] = [];
  let origin = '';
  const requests: Request[] = [], errors: string[] = [];
  const release = () => ({ id: 7, tag_name: tag, draft, prerelease, name: `Release ${tag}`, assets: assets.map(metadata), upload_url: `${origin}/uploads/7/assets{?name,label}`, html_url: `${origin}/acme/widgets/releases/tag/${tag}` });
  const metadata = (asset: Asset) => ({ id: asset.id, name: asset.name, url: `${origin}/api/v3/repos/acme/widgets/releases/assets/${asset.id}`, size: asset.bytes.length, digest: null });
  const send = (response: ServerResponse, value: unknown, status = 200) => {
    response.writeHead(status, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(value));
  };
  const handle = async (request: IncomingMessage, response: ServerResponse) => {
    const buffers: Buffer[] = [];
    for await (const chunk of request) buffers.push(Buffer.from(chunk));
    const body = Buffer.concat(buffers), url = new URL(request.url!, origin), method = request.method!;
    requests.push({ method, path: url.pathname + url.search, body });
    assert.equal(request.headers.authorization, 'token local-test-token');
    const route = url.pathname.replace(/^\/api\/v3/, '');
    const paginate = (values: unknown[]) => {
      const page = Number(url.searchParams.get('page') ?? '1');
      if (page < values.length) {
        const next = new URL(url); next.searchParams.set('page', String(page + 1));
        response.setHeader('Link', `<${next}>; rel="next"`);
      }
      send(response, values.slice(page - 1, page));
    };
    if (method === 'POST' && route === '/api/graphql') {
      const input = JSON.parse(body.toString()) as { query: string; variables: Record<string, unknown> };
      if (input.query.includes('RepositoryFindRef')) {
        assert.deepEqual(input.variables, { name: 'widgets', owner: 'acme', tagName: `refs/tags/${tag}` });
        send(response, { data: { repository: { ref: { id: 'local-tag-ref' } } } });
      } else {
        assert.match(input.query, /RepositoryReleaseByTag/);
        assert.deepEqual(input.variables, { name: 'widgets', owner: 'acme', tagName: tag });
        send(response, { data: { repository: { release: { databaseId: 7, isDraft: draft } } } });
      }
    } else if (method === 'GET' && route === '/repos/acme/widgets/releases') {
      paginate([{ id: 6, tag_name: 'v0.0.1', draft: false, prerelease: false }, ...(draft === undefined ? [] : [release()])]);
    } else if (method === 'GET' && route === `/repos/acme/widgets/git/ref/tags/${tag}`) {
      send(response, { ref: `refs/tags/${tag}`, object: { sha: 'a'.repeat(40), type: 'commit' } });
    } else if (method === 'POST' && route === '/repos/acme/widgets/releases/generate-notes') {
      send(response, { name: `Release ${tag}`, body: 'Local fixture release notes' });
    } else if (method === 'POST' && route === '/repos/acme/widgets/releases') {
      const input = JSON.parse(body.toString()) as Record<string, unknown>;
      assert.equal(input['tag_name'], tag); assert.equal(input['draft'], true); assert.equal(input['prerelease'], true);
      draft = true; send(response, release(), 201);
    } else if (method === 'GET' && (route === `/repos/acme/widgets/releases/tags/${tag}` || route === '/repos/acme/widgets/releases/7')) {
      send(response, release());
    } else if (method === 'GET' && route === '/repos/acme/widgets/releases/7/assets') {
      paginate(assets.map(metadata));
    } else if (method === 'POST' && route === '/uploads/7/assets') {
      assert.equal(draft, true, 'Uploads must only occur while the release is a draft');
      const name = url.searchParams.get('name'); assert(name);
      assert(!assets.some(asset => asset.name === name), 'Clobber must remove the existing asset before upload');
      const asset = { id: nextId++, name, bytes: body }; assets.push(asset); send(response, metadata(asset), 201);
    } else if (route.startsWith('/repos/acme/widgets/releases/assets/')) {
      const id = Number(route.split('/').pop()), asset = assets.find(value => value.id === id); assert(asset);
      if (method === 'GET') {
        assert.equal(request.headers.accept, 'application/octet-stream');
        response.writeHead(200, { 'Content-Type': 'application/octet-stream' }); response.end(asset.bytes);
      } else {
        assert.equal(method, 'DELETE'); assert.equal(draft, true);
        assets = assets.filter(value => value.id !== id); response.writeHead(204); response.end();
      }
    } else if (method === 'PATCH' && route === '/repos/acme/widgets/releases/7') {
      assert.equal(draft, true);
      assert.deepEqual(JSON.parse(body.toString()), { draft: false, prerelease: true });
      assert.deepEqual(new Map(assets.map(asset => [asset.name, sha(asset.bytes)])), new Map([...expected].map(([name, bytes]) => [name, sha(bytes)])));
      draft = false; prerelease = true; send(response, release());
    } else {
      throw new Error(`Unexpected gh request: ${method} ${url.pathname} ${body.toString()}`);
    }
  };
  const server = createServer((request, response) => {
    void handle(request, response).catch(error => { errors.push(String(error)); send(response, { message: String(error) }, 500); });
  });
  await new Promise<void>(resolve => server.listen(socket, resolve));
  t.after(() => new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve())));
  origin = 'http://local-test.invalid';
  // No inherited credentials/proxies: even gh's host/config selection is isolated.
  const environment: NodeJS.ProcessEnv = {
    PATH: process.env['PATH'], GH_HOST: 'local-test.invalid', GH_ENTERPRISE_TOKEN: 'local-test-token',
    GH_CONFIG_DIR: ghConfig, GH_PROMPT_DISABLED: '1', GH_NO_UPDATE_NOTIFIER: '1',
    RELEASE_TAG: tag, RELEASE_VERSION: '9.9.9-rc.1', PRERELEASE: 'true', GH_REPO: 'acme/widgets',
  };
  const run = () => command(process.execPath, [fileURLToPath(new URL('./publish.ts', import.meta.url)), assetDirectory], environment);
  const first = await run();
  assert.equal(first.code, 0, first.stderr + '\n' + errors.join('\n'));
  assert.equal(first.stdout, 'published\n'); assert.equal(draft, false);
  assert(requests.some(request => request.path.includes('page=2')), 'real gh must follow HTTP pagination');
  assert(requests.some(request => request.path.startsWith('/uploads/7/assets?')), 'real gh must upload local file bytes');

  requests.length = 0;
  const repeat = await run();
  assert.equal(repeat.code, 0, repeat.stderr); assert.equal(repeat.stdout, 'already-published\n');
  assert(requests.every(request => request.method === 'GET'), 'immutable reruns must not mutate remote state');

  // Simulate an interrupted draft with wrong, missing and extra content.
  draft = true;
  assets = [{ id: nextId++, name: 'graphite.jar', bytes: Buffer.from('wrong') }, { id: nextId++, name: 'obsolete.txt', bytes: Buffer.from('stale') }];
  requests.length = 0;
  const repaired = await run();
  assert.equal(repaired.code, 0, repaired.stderr + '\n' + errors.join('\n'));
  assert.equal(repaired.stdout, 'published\n');
  assert(requests.some(request => request.method === 'DELETE'), 'draft clobber and stale asset deletion must use real gh');
  assert(!requests.some(request => request.method === 'POST' && request.path === '/api/v3/repos/acme/widgets/releases'), 'repair must reuse the draft');

  writeFileSync(join(assetDirectory, 'graphite.jar'), 'different published content');
  requests.length = 0;
  const mismatch = await run();
  assert.equal(mismatch.code, 1); assert.match(mismatch.stderr, /published and immutable/);
  assert(requests.every(request => request.method === 'GET'), 'a published mismatch must fail without mutations');
  assert.deepEqual(errors, []);
});
