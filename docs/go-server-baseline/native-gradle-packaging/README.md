# Native Gradle packaging correctness

Base: `274cd678` (CLI bridge, including literal `@` protection). This bounded
change closes default Gradle resource staging. It does not complete release,
Homebrew, Docker, native profiling or Windows delivery; those remain separate.

`prepareNativeServer` generates one shared resource directory for `run`, slim and
shadow JARs, and `installDist`. The default package carries four native binaries:
Linux/macOS × amd64/arm64. Versions, exact SHA256/path sets, architecture and
static ELF linkage are checked by the standard-library-only Go packaging tool.
The prebuilt mode performs the same validation before copying. Build/test details
and developer options are in `scripts/native/README.md`.

Verified locally on macOS ARM64:

- Default four-target cross-build, both shadow JARs and both `installDist` outputs.
- Six bridge unit tests and twelve existing unified CLI tests, including real JVM
  graph construction and persisted resources; JVM build/query remain available.
- Four actual installed entry points, outside the repository and without a binary
  override: unified JAR, legacy Explore JAR, and both Gradle application scripts.
  Each loads the existing JVM-written four-CallSite fixture, returns the expected
  catalog, and serves exactly the embedded homepage bytes. SIGTERM yields 143,
  and the launcher's private executable and extraction directory are removed.
- The same artifacts rebuilt from validated prebuilt resources; hashes unchanged.
- Packaging tool race tests and vet. Corrupt SHA, duplicate manifest, wrong version,
  unexpected files/symlinks, truncation, wrong architecture, and unsafe output alias
  are rejected; invalid input does not replace a complete previous output.

The first install-script smoke invocation failed because the harness supplied an
unquoted `JAVA_OPTS` temp-directory value containing spaces. Its result is kept in
`smoke-initial-failure.json`; quoting the harness option fixed it. Production start
scripts were unchanged. The final four checks are in `smoke.json`.

Reproduction from the source root (Java17, Go per go.mod):

```sh
go test -race scripts/native/build.go scripts/native/build_test.go
go vet scripts/native/build.go scripts/native/build_test.go
./gradlew :query:shadowJar :explore:shadowJar :query:installDist :explore:installDist :explore:test --tests io.johnsonlee.graphite.cli.NativeServerLauncherTest :query:test --tests io.johnsonlee.graphite.cli.GraphiteCommandTest --no-daemon -Dorg.gradle.jvmargs=-Xmx2g -Pversion=3.0.0-packaging-audit
python3 -W error::ResourceWarning scripts/native/smoke.py --output /tmp/native-package-smoke.json
go run scripts/native/build.go --version 3.0.0-packaging-audit --prebuilt "$PWD/graphite-explore/build/generated/native-resources/graphite-native" --output /tmp/graphite-native-prebuilt-audit/graphite-native
./gradlew :query:shadowJar :explore:shadowJar :query:installDist :explore:installDist --no-daemon -Dorg.gradle.jvmargs=-Xmx2g -Pversion=3.0.0-packaging-audit -Pgraphite.nativePrebuilt=/tmp/graphite-native-prebuilt-audit/graphite-native
```

The local build used a clean `git archive 274cd678` snapshot plus the exact changed
Gradle/tool files, initialized with `git init` and no commit, because the existing
Sonatype plugin cannot resolve this detached worktree's JGit directory. Paths,
source and artifact hashes, runtime identities and completion codes are recorded
in `identity.json`. No remote CI, release or publishing action was run. Added CI
steps are a future gate, not local evidence. Only macOS ARM64 execution is proven
here; the other three artifacts passed format/hash checks, not runtime tests.
No performance test or claim is made.
