/** Reconcile mutable drafts, verify immutable published releases, then publish/read back. */
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { closeSync, mkdtempSync, openSync, readSync, readdirSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { parseVersion } from './version.ts';

export interface ReleaseConfig {
  tag: string;
  version: string;
  prerelease: boolean;
  repository: string;
}
/** Binary downloads go to a file, so fallback hashing does not buffer a release asset in memory. */
export type GhTransport = (args: readonly string[], outputFile?: string) => string;
export type CommandRunner = (program: string, args: readonly string[], outputFile?: string) => string;
interface Release { id: number; draft: boolean; prerelease: boolean }
interface Asset { id: number; name: string; digest: string | null }
interface LocalAsset { name: string; path: string; digest: string }

function demand(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}
function object(value: unknown): Record<string, unknown> {
  demand(value !== null && typeof value === 'object' && !Array.isArray(value), 'Invalid GitHub API object');
  return value as Record<string, unknown>;
}
function id(value: unknown): number {
  demand(typeof value === 'number' && Number.isSafeInteger(value) && value > 0, 'Invalid GitHub object ID');
  return value;
}

export function configFromEnvironment(env: NodeJS.ProcessEnv): ReleaseConfig {
  const tag = env['RELEASE_TAG'] ?? '', version = env['RELEASE_VERSION'] ?? '';
  const parsed = parseVersion(tag);
  demand(parsed, 'RELEASE_TAG must be a strict v-prefixed SemVer tag');
  demand(version === parsed.version, 'RELEASE_VERSION does not match RELEASE_TAG');
  demand(env['PRERELEASE'] === String(parsed.prerelease), 'PRERELEASE does not match the parsed release tag');
  const repository = env['GH_REPO'] ?? '';
  demand(/^[A-Za-z0-9][A-Za-z0-9-]*\/[A-Za-z0-9_.-]+$/.test(repository)
    && !['.', '..'].includes(repository.split('/')[1]!), 'GH_REPO must be owner/name');
  return { tag, version, prerelease: parsed.prerelease, repository };
}

const execute: CommandRunner = (program, args, outputFile) => {
  const fd = outputFile === undefined ? undefined : openSync(outputFile, 'w');
  try {
    const result = spawnSync(program, [...args], {
      stdio: ['ignore', fd ?? 'pipe', 'pipe'], env: process.env, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024,
    });
    if (result.error) throw result.error;
    demand(result.status === 0, `${program} failed (${result.signal ?? result.status}): ${result.stderr ?? ''}`);
    return result.stdout ?? '';
  } finally {
    if (fd !== undefined) closeSync(fd);
  }
};
export function createGhTransport(runner: CommandRunner = execute): GhTransport {
  return (args, outputFile) => runner('gh', args, outputFile);
}

function hashFile(path: string): string {
  const digest = createHash('sha256'), buffer = Buffer.alloc(1024 * 1024);
  const fd = openSync(path, 'r');
  try {
    for (;;) {
      const length = readSync(fd, buffer, 0, buffer.length, null);
      if (length === 0) break;
      digest.update(buffer.subarray(0, length));
    }
  } finally { closeSync(fd); }
  return digest.digest('hex');
}
function localAssets(directory: string): LocalAsset[] {
  demand(statSync(directory).isDirectory(), `No assets directory ${directory}`);
  const files = readdirSync(directory, { withFileTypes: true }).filter(entry => entry.isFile());
  demand(files.length > 0, `No regular asset files in ${directory}`);
  return files.map(entry => {
    // gh release upload interprets # as a display-label separator, not a filename byte.
    demand(!entry.name.includes('#'), `Asset name cannot contain gh's label separator '#': ${entry.name}`);
    const path = join(directory, entry.name);
    return { name: entry.name, path, digest: hashFile(path) };
  }).sort((a, b) => Buffer.compare(Buffer.from(a.name), Buffer.from(b.name)));
}
function pages(gh: GhTransport, endpoint: string): unknown[] {
  const value: unknown = JSON.parse(gh(['api', '--paginate', '--slurp', endpoint]));
  demand(Array.isArray(value) && value.every(Array.isArray), 'Expected paginated GitHub API arrays');
  return value.flat() as unknown[];
}
function readRelease(gh: GhTransport, config: ReleaseConfig): Release | undefined {
  const matches = pages(gh, `repos/${config.repository}/releases?per_page=100`)
    .map(object).filter(release => release['tag_name'] === config.tag).map(release => {
      demand(typeof release['draft'] === 'boolean' && typeof release['prerelease'] === 'boolean', 'Invalid release flags');
      return { id: id(release['id']), draft: release['draft'], prerelease: release['prerelease'] };
    });
  const published = matches.filter(release => !release.draft), drafts = matches.filter(release => release.draft);
  demand(published.length <= 1, `Multiple published releases carry tag ${config.tag}`);
  if (published.length === 1) return published[0];
  demand(drafts.length <= 1, `Multiple draft releases carry tag ${config.tag}; delete all but one and re-run`);
  return drafts[0];
}
function remoteAssets(gh: GhTransport, config: ReleaseConfig, release: Release): Asset[] {
  const assets = pages(gh, `repos/${config.repository}/releases/${release.id}/assets?per_page=100`).map(value => {
    const asset = object(value);
    demand(typeof asset['name'] === 'string' && asset['name'].length > 0, 'Invalid remote asset name');
    demand(asset['digest'] == null || typeof asset['digest'] === 'string', 'Invalid remote asset digest');
    return { id: id(asset['id']), name: asset['name'], digest: asset['digest'] ?? null } as Asset;
  });
  demand(new Set(assets.map(asset => asset.name)).size === assets.length, 'Duplicate remote asset names');
  demand(new Set(assets.map(asset => asset.id)).size === assets.length, 'Duplicate remote asset IDs');
  const scratch = mkdtempSync(join(tmpdir(), 'graphite-release-'));
  try {
    return assets.map(asset => {
      if (asset.digest !== null && /^sha256:[0-9a-f]{64}$/i.test(asset.digest)) {
        return { ...asset, digest: asset.digest.slice(7).toLowerCase() };
      }
      const downloaded = join(scratch, String(asset.id));
      gh(['api', `repos/${config.repository}/releases/assets/${asset.id}`, '-H', 'Accept: application/octet-stream'], downloaded);
      return { ...asset, digest: hashFile(downloaded) };
    });
  } finally { rmSync(scratch, { recursive: true, force: true }); }
}
function verifyAssets(gh: GhTransport, config: ReleaseConfig, release: Release, expected: LocalAsset[]): void {
  const actual = remoteAssets(gh, config, release);
  demand(actual.length === expected.length && expected.every(local => actual.some(remote => remote.name === local.name && remote.digest === local.digest)),
    `Release assets differ by name or SHA-256: expected ${JSON.stringify(expected.map(({ name, digest }) => ({ name, digest })))}, actual ${JSON.stringify(actual)}`);
}
function checkPublished(gh: GhTransport, config: ReleaseConfig, release: Release, expected: LocalAsset[]): void {
  demand(release.prerelease === config.prerelease, `Published prerelease flag differs from parsed tag ${config.tag}`);
  try { verifyAssets(gh, config, release, expected); }
  catch (error) { throw new Error(`Release ${config.tag} is published and immutable; it cannot be repaired, release a new tag`, { cause: error }); }
}

export function publish(
  directory: string,
  config: ReleaseConfig,
  gh: GhTransport = createGhTransport(),
  log: (message: string) => void = message => console.error(`publish-release: ${message}`),
): 'published' | 'already-published' {
  const expected = localAssets(resolve(directory));
  let release = readRelease(gh, config);
  if (release && !release.draft) {
    log(`Release ${config.tag} is already published; verifying without mutations`);
    checkPublished(gh, config, release, expected);
    return 'already-published';
  }
  if (!release) {
    log(`Creating draft ${config.tag} with ${expected.length} assets`);
    gh(['release', 'create', config.tag, '--repo', config.repository, '--draft', '--verify-tag', '--title', `Release ${config.tag}`,
      '--generate-notes', ...(config.prerelease ? ['--prerelease'] : []), '--', ...expected.map(asset => asset.path)]);
    release = readRelease(gh, config);
    demand(release, `Draft ${config.tag} was not created`);
    if (!release.draft) {
      checkPublished(gh, config, release, expected);
      return 'already-published';
    }
  }
  const actual = remoteAssets(gh, config, release);
  for (const asset of expected) {
    if (!actual.some(remote => remote.name === asset.name && remote.digest === asset.digest)) {
      log(`Uploading ${asset.name}`);
      gh(['release', 'upload', config.tag, '--repo', config.repository, '--clobber', '--', asset.path]);
    }
  }
  for (const asset of actual) {
    if (!expected.some(local => local.name === asset.name)) {
      log(`Removing stale draft asset ${asset.name}`);
      gh(['api', '-X', 'DELETE', `repos/${config.repository}/releases/assets/${asset.id}`]);
    }
  }
  verifyAssets(gh, config, release, expected);
  log(`Publishing ${config.tag} (prerelease=${config.prerelease})`);
  try {
    gh(['api', '-X', 'PATCH', `repos/${config.repository}/releases/${release.id}`, '-F', 'draft=false', '-F', `prerelease=${config.prerelease}`]);
  } catch (error) { log(`Publish request did not return success; reading back state: ${String(error)}`); }
  release = readRelease(gh, config);
  demand(release && !release.draft, `Release ${config.tag} is still a draft or missing`);
  checkPublished(gh, config, release, expected);
  return 'published';
}

export function main(
  argv: readonly string[],
  env: NodeJS.ProcessEnv = process.env,
  gh: GhTransport = createGhTransport(),
  output: (line: string) => void = console.log,
  error: (line: string) => void = console.error,
): number {
  if (argv.length !== 1) { error('Usage: bun release/publish.ts <assets-dir>'); return 2; }
  try {
    const config = configFromEnvironment(env);
    output(publish(argv[0]!, config, gh, message => error(`publish-release: ${message}`)));
    return 0;
  } catch (cause) {
    error(`publish-release: ${cause instanceof Error ? cause.message : String(cause)}`);
    return 1;
  }
}

if (import.meta.main) process.exitCode = main(process.argv.slice(2));
