/** Strict v-prefixed SemVer; build metadata never marks a release as prerelease. */
export interface ReleaseVersion {
  version: string;
  prerelease: boolean;
}

const SEMVER = /^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-((?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/;

export function parseVersion(tag: string): ReleaseVersion | null {
  const match = SEMVER.exec(tag);
  // JS $ also matches before a final newline. A tag must match every input byte.
  if (!match || match[0] !== tag) return null;
  return { version: tag.slice(1), prerelease: match[4] !== undefined };
}

export function main(
  argv: readonly string[],
  output: (line: string) => void = console.log,
  error: (line: string) => void = console.error,
): number {
  if (argv.length !== 1) {
    error('Usage: bun release/version.ts v<MAJOR>.<MINOR>.<PATCH>[-<prerelease>][+<build>]');
    return 2;
  }
  const tag = argv[0]!;
  const parsed = parseVersion(tag);
  if (!parsed) {
    error(`RELEASE_TAG must be v<MAJOR>.<MINOR>.<PATCH>[-<prerelease>][+<build>], got ${JSON.stringify(tag)}`);
    return 1;
  }
  output(`version=${parsed.version}`);
  output(`prerelease=${parsed.prerelease}`);
  return 0;
}

if (import.meta.main) process.exitCode = main(process.argv.slice(2));
