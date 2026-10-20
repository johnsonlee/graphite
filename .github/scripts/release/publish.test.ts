import assert from 'node:assert/strict';
import { test } from 'node:test';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, writeFileSync, mkdirSync, chmodSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import { createGhTransport, configFromEnvironment, main, publish } from './publish.ts';
import type { GhTransport, ReleaseConfig } from './publish.ts';

const env = { RELEASE_TAG: 'v9.9.9-rc.1', RELEASE_VERSION: '9.9.9-rc.1', PRERELEASE: 'true', GH_REPO: 'acme/widgets' };
const config: ReleaseConfig = configFromEnvironment(env);
const sha = (bytes: Buffer) => createHash('sha256').update(bytes).digest('hex');
const quiet = () => {};
interface Asset { id: number; name: string; digest: string | null; bytes: Buffer }
interface Release { id: number; tag_name: string; draft: boolean; prerelease: boolean; assets: Asset[] }

class GitHub {
  config: ReleaseConfig;
  constructor(releaseConfig: ReleaseConfig = config) { this.config = releaseConfig; }
  releases: Release[] = [];
  calls: string[][] = [];
  mutations: string[] = [];
  nextAsset = 1000;
  partialCreate = false;
  ambiguousPublish = false;
  rejectPublish = false;
  noPublishMutation = false;
  failRead = false;
  failDownload = false;
  noUploadMutation = false;
  downloads = 0;

  add(release: Release, name: string, bytes: Buffer, withDigest = true): void {
    release.assets = release.assets.filter(asset => asset.name !== name);
    release.assets.push({ id: this.nextAsset++, name, bytes, digest: withDigest ? 'sha256:' + sha(bytes) : null });
  }
  draft(): Release {
    const release = { id: 100 + this.releases.length, tag_name: this.config.tag, draft: true, prerelease: this.config.prerelease, assets: [] };
    this.releases.push(release);
    return release;
  }
  readonly run: GhTransport = (args, destination) => {
    this.calls.push([...args]);
    if (args[0] === 'api' && args[1] === '--paginate') {
      assert.equal(args[2], '--slurp');
      if (this.failRead) throw new Error('API list unavailable');
      const endpoint = args[3];
      let values: unknown[];
      if (endpoint === 'repos/acme/widgets/releases?per_page=100') {
        values = this.releases.map(({ assets: _assets, ...release }) => release);
      } else {
        const match = /^repos\/acme\/widgets\/releases\/([0-9]+)\/assets\?per_page=100$/.exec(endpoint ?? '');
        assert(match, endpoint);
        const release = this.releases.find(release => release.id === Number(match[1])); assert(release);
        values = release.assets.map(({ bytes: _bytes, ...asset }) => asset);
      }
      // Each result is on its own page: both release and asset pagination are exercised.
      return JSON.stringify(values.map(value => [value]));
    }
    if (args[0] === 'api' && args[2] === '-H') {
      assert.equal(args[3], 'Accept: application/octet-stream');
      assert(destination);
      if (this.failDownload) throw new Error('Download failed');
      const assetId = Number(args[1]?.split('/').pop());
      const asset = this.releases.flatMap(release => release.assets).find(asset => asset.id === assetId); assert(asset);
      writeFileSync(destination, asset.bytes);
      this.downloads++;
      return '';
    }
    if (args[0] === 'api' && args[1] === '-X') {
      const operation = args[2], objectId = Number(args[3]?.split('/').pop());
      this.mutations.push(operation ?? '');
      if (operation === 'DELETE') {
        const release = this.releases.find(release => release.assets.some(asset => asset.id === objectId)); assert(release?.draft);
        release.assets = release.assets.filter(asset => asset.id !== objectId);
      } else {
        assert.equal(operation, 'PATCH');
        const release = this.releases.find(release => release.id === objectId); assert(release?.draft);
        assert.deepEqual(args.slice(4), ['-F', 'draft=false', '-F', `prerelease=${this.config.prerelease}`]);
        if (this.rejectPublish) throw new Error('Publish rejected before mutation');
        if (!this.noPublishMutation) { release.draft = false; release.prerelease = this.config.prerelease; }
        if (this.ambiguousPublish) throw new Error('Gateway timeout after publish');
      }
      return '';
    }
    assert.equal(args[0], 'release');
    assert.equal(args[2], this.config.tag);
    assert.equal(args[3], '--repo'); assert.equal(args[4], this.config.repository);
    const files = args.slice(args.indexOf('--') + 1);
    if (args[1] === 'create') {
      this.mutations.push('create');
      assert(args.includes('--draft')); assert(args.includes('--verify-tag')); assert(args.includes('--generate-notes')); assert.equal(args.includes('--prerelease'), this.config.prerelease);
      const release = this.draft();
      for (const file of files) {
        this.add(release, basename(file), readFileSync(file));
        if (this.partialCreate) throw new Error('Upload failed after first asset');
      }
    } else {
      assert.equal(args[1], 'upload'); assert(args.includes('--clobber')); assert.equal(files.length, 1);
      this.mutations.push('upload');
      const release = this.releases.find(release => release.draft); assert(release);
      if (!this.noUploadMutation) this.add(release, basename(files[0]!), readFileSync(files[0]!), false);
    }
    return '';
  };
}

function fixture(t: { after: (fn: () => void) => void }): string {
  const directory = mkdtempSync(join(tmpdir(), 'graphite-publish-test-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  writeFileSync(join(directory, 'graphite.jar'), 'jar');
  writeFileSync(join(directory, 'graphite-9.9.9-rc.1-linux.tar.gz'), 'linux');
  return directory;
}
function run(directory: string, github: GitHub) { return publish(directory, config, github.run, quiet); }

test('fresh tag is created as a draft with complete assets, verified, then published', t => {
  const directory = fixture(t), github = new GitHub();
  assert.equal(run(directory, github), 'published');
  assert.deepEqual(github.mutations, ['create', 'PATCH']);
  assert.equal(github.releases.length, 1);
  const release = github.releases[0]!;
  assert.equal(release.draft, false); assert.equal(release.prerelease, true);
  assert.deepEqual(release.assets.map(asset => asset.name).sort(), ['graphite-9.9.9-rc.1-linux.tar.gz', 'graphite.jar']);
  assert(github.calls.filter(call => call[0] === 'api' && call[1] === '--paginate').length >= 5);
});

test('published re-run is read-only, including digest fallback and a stale draft', t => {
  const directory = fixture(t), github = new GitHub();
  run(directory, github);
  github.releases[0]!.assets[0]!.digest = null;
  github.draft(); github.mutations = [];
  assert.equal(run(directory, github), 'already-published');
  assert.deepEqual(github.mutations, []);
  assert.equal(github.downloads, 1);
  assert.equal(github.releases[1]!.draft, true);
});

test('immutable mismatched assets or prerelease state fail without a mutation', t => {
  const directory = fixture(t), github = new GitHub();
  run(directory, github); github.mutations = [];
  writeFileSync(join(directory, 'graphite.jar'), 'different bytes');
  assert.throws(() => run(directory, github), /immutable.*cannot be repaired/);
  writeFileSync(join(directory, 'graphite.jar'), 'jar');
  github.releases[0]!.prerelease = false;
  assert.throws(() => run(directory, github), /prerelease flag differs/);
  assert.deepEqual(github.mutations, []);
});

test('partial upload recovery repairs a draft, removes stale assets and never creates a second release', t => {
  const directory = fixture(t), github = new GitHub();
  github.partialCreate = true;
  assert.throws(() => run(directory, github), /Upload failed/);
  assert.equal(github.releases[0]!.draft, true);
  assert.equal(github.releases[0]!.assets.length, 1);
  github.add(github.releases[0]!, 'obsolete [asset].txt', Buffer.from('stale'), false);
  github.mutations = [];
  assert.equal(run(directory, github), 'published');
  assert.deepEqual(github.mutations, ['upload', 'DELETE', 'PATCH']);
  assert.equal(github.releases.length, 1);
  assert.equal(github.releases[0]!.assets.length, 2);
  assert.equal(github.releases[0]!.draft, false);
  assert(github.downloads >= 2, 'missing SHA-256 digests must be checked by downloaded content');
});

test('ambiguous publish success is resolved by read-back rather than request exit status', t => {
  const directory = fixture(t), github = new GitHub();
  github.ambiguousPublish = true;
  assert.equal(run(directory, github), 'published');
  assert.equal(github.releases[0]!.draft, false);
  assert.equal(github.mutations.filter(operation => operation === 'PATCH').length, 1);
});

test('a rejected publish or a successful response leaving a draft never reports success', t => {
  const directory = fixture(t);
  for (const field of ['rejectPublish', 'noPublishMutation'] as const) {
    const github = new GitHub(); github[field] = true;
    assert.throws(() => run(directory, github), /still a draft or missing/);
    assert.equal(github.releases[0]!.draft, true);
  }
});

test('conflicting release ownership, duplicate assets, failed reads/downloads and incomplete reconciliation stop before publishing', t => {
  const directory = fixture(t);
  const drafts = new GitHub(); drafts.draft(); drafts.draft();
  assert.throws(() => run(directory, drafts), /Multiple draft/); assert.deepEqual(drafts.mutations, []);
  const published = new GitHub(); published.draft().draft = false; published.draft().draft = false;
  assert.throws(() => run(directory, published), /Multiple published/); assert.deepEqual(published.mutations, []);
  const duplicate = new GitHub(), release = duplicate.draft();
  duplicate.add(release, 'same', Buffer.from('x')); release.assets.push({ ...release.assets[0]!, id: 12345 });
  assert.throws(() => run(directory, duplicate), /Duplicate remote asset names/); assert.deepEqual(duplicate.mutations, []);
  const read = new GitHub(); read.failRead = true;
  assert.throws(() => run(directory, read), /API list unavailable/); assert.deepEqual(read.mutations, []);
  const download = new GitHub(); download.add(download.draft(), 'missing', Buffer.from('x'), false); download.failDownload = true;
  assert.throws(() => run(directory, download), /Download failed/); assert.deepEqual(download.mutations, []);
  const incomplete = new GitHub(); incomplete.draft(); incomplete.noUploadMutation = true;
  assert.throws(() => run(directory, incomplete), /assets differ/); assert(!incomplete.mutations.includes('PATCH'));
});

test('asset names are argv data and digest fallback downloads by exact numeric asset ID', t => {
  const directory = fixture(t), github = new GitHub();
  const name = 'space $(false) [literal].jar'; writeFileSync(join(directory, name), 'safe');
  const release = github.draft(); github.add(release, name, Buffer.from('old'), false);
  assert.equal(run(directory, github), 'published');
  assert(github.calls.some(args => args[1] === 'upload' && args.at(-1) === join(directory, name)));
  const download = github.calls.find(args => args[0] === 'api' && args[2] === '-H'); assert(download);
  assert.match(download[1]!, /^repos\/acme\/widgets\/releases\/assets\/[0-9]+$/);
  assert.equal(release.assets.find(asset => asset.name === name)?.bytes.toString(), 'safe');
});

test('transport passes literal argv and a binary output file to gh, without shell execution', () => {
  const calls: unknown[] = [];
  const gh = createGhTransport((program, args, file) => { calls.push({ program, args, file }); return '[[]]'; });
  const args = ['release', 'upload', 'v1.0.0', '--', '/tmp/$(false) space.jar'];
  assert.equal(gh(args), '[[]]');
  gh(['api', 'asset'], '/tmp/output file');
  assert.deepEqual(calls, [{ program: 'gh', args, file: undefined }, { program: 'gh', args: ['api', 'asset'], file: '/tmp/output file' }]);
});

test('CLI rejects arguments, conflicting version metadata, invalid repository and empty assets before transport', t => {
  const directory = fixture(t), calls: unknown[] = [], output: string[] = [], errors: string[] = [];
  const gh: GhTransport = args => { calls.push(args); throw new Error('must not reach gh'); };
  assert.equal(main([], env, gh, line => output.push(line), line => errors.push(line)), 2);
  assert.equal(main([directory, 'extra'], env, gh, quiet, quiet), 2);
  for (const changed of [{ RELEASE_TAG: 'v3.0.0\n' }, { RELEASE_VERSION: 'other' }, { PRERELEASE: 'false' }, { PRERELEASE: 'yes' }, { GH_REPO: '../widgets' }, { GH_REPO: 'acme/..' }, { GH_REPO: 'https://example.com/acme/widgets' }]) {
    assert.equal(main([directory], { ...env, ...changed }, gh, line => output.push(line), line => errors.push(line)), 1);
  }
  const empty = join(directory, 'empty'); mkdirSync(empty);
  assert.equal(main([empty], env, gh, quiet, quiet), 1);
  assert.deepEqual(output, []); assert.deepEqual(calls, []); assert(errors.length > 0);
});

test('CLI publishes through injected transport with exact stdout status', t => {
  const directory = fixture(t), github = new GitHub(), output: string[] = [];
  assert.equal(main([directory], env, github.run, line => output.push(line), quiet), 0);
  assert.equal(main([directory], env, github.run, line => output.push(line), quiet), 0);
  assert.deepEqual(output, ['published', 'already-published']);
});

test('stable build metadata containing a hyphen creates and publishes without a prerelease flag', t => {
  const directory = fixture(t);
  const stable = configFromEnvironment({ ...env, RELEASE_TAG: 'v9.9.9+linux-1', RELEASE_VERSION: '9.9.9+linux-1', PRERELEASE: 'false' });
  const github = new GitHub(stable);
  assert.equal(publish(directory, stable, github.run, quiet), 'published');
  assert.equal(github.releases[0]!.prerelease, false);
  assert(!github.calls.find(args => args[1] === 'create')?.includes('--prerelease'));
});

test('unusable digest metadata falls back to actual bytes, and mismatched draft content is replaced', t => {
  const directory = fixture(t), github = new GitHub(), draft = github.draft();
  github.add(draft, 'graphite.jar', Buffer.from('wrong bytes'));
  draft.assets[0]!.digest = 'sha256:not-a-valid-digest';
  assert.equal(run(directory, github), 'published');
  assert(github.downloads >= 1);
  assert.equal(draft.assets.find(asset => asset.name === 'graphite.jar')?.bytes.toString(), 'jar');
});

test('real subprocess transport preserves binary output and argv and propagates gh failures', t => {
  const directory = fixture(t), executable = join(directory, 'gh');
  writeFileSync(executable, `#!${process.execPath}\n` +
    `if(process.argv[2]==='fail'){process.stderr.write('transport denied');process.exit(7);}\n` +
    `if(process.argv[2]==='binary')process.stdout.write(Buffer.from([0,255,128,10]));\n` +
    `else process.stdout.write(JSON.stringify(process.argv.slice(2)));\n`);
  chmodSync(executable, 0o755);
  const oldPath = process.env['PATH'];
  process.env['PATH'] = directory + ':' + (oldPath ?? '');
  try {
    const gh = createGhTransport();
    assert.deepEqual(JSON.parse(gh(['literal', '$(false)', 'space name'])), ['literal', '$(false)', 'space name']);
    const target = join(directory, 'download');
    gh(['binary'], target);
    assert.deepEqual(readFileSync(target), Buffer.from([0, 255, 128, 10]));
    assert.throws(() => gh(['fail']), /gh failed \(7\): transport denied/);
    // Exercise the actual CLI entry point against a read-only published release.
    const assets = join(directory, 'assets'); mkdirSync(assets);
    writeFileSync(join(assets, 'graphite.jar'), 'jar');
    const release = [{ id: 7, tag_name: config.tag, draft: false, prerelease: true }];
    const remote = [{ id: 8, name: 'graphite.jar', digest: 'sha256:' + sha(Buffer.from('jar')) }];
    writeFileSync(executable, `#!${process.execPath}\n` +
      `const args=process.argv.slice(2);if(args[0]!=='api'||args[1]!=='--paginate'||args[2]!=='--slurp')process.exit(9);\n` +
      `if(args[3]==='repos/acme/widgets/releases?per_page=100')process.stdout.write(${JSON.stringify(JSON.stringify([release]))});\n` +
      `else if(args[3]==='repos/acme/widgets/releases/7/assets?per_page=100')process.stdout.write(${JSON.stringify(JSON.stringify([remote]))});\n` +
      `else process.exit(9);\n`);
    const cli = spawnSync(process.execPath, [fileURLToPath(new URL('./publish.ts', import.meta.url)), assets], {
      env: { ...process.env, ...env }, encoding: 'utf8',
    });
    assert.equal(cli.status, 0, cli.stderr);
    assert.equal(cli.stdout, 'already-published\n');
  } finally {
    if (oldPath === undefined) delete process.env['PATH']; else process.env['PATH'] = oldPath;
  }
});
