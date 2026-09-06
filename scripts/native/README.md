# Native server packaging

Building the JVM CLI now also requires Go (the version in `graphite-server/go.mod`).
Graph building and offline `query` still use Java 17. `serve` resolves an embedded
native binary, verifies its SHA256, and launches it without a Kotlin fallback.

`./gradlew :query:shadowJar :explore:shadowJar :query:installDist :explore:installDist`
includes the same `graphite-native` resource directory in both JARs and application
distributions. `run` uses the same resource task. The default contains Linux and
macOS binaries for amd64 and arm64. `-Pgraphite.nativeTargets=host` is an explicit
local-development option: that output is **not** a portable four-platform package.
Windows native serving remains incomplete; JVM build/query are retained.

The resource task runs `go run scripts/native/build.go` from the repository root.
It cross-builds with CGo disabled and baseline CPU targets, embeds the requested
version, and checks each executable's format/architecture (and absence of an ELF
interpreter). `SHA256SUMS` lists the binary bytes; `VERSION` identifies the package
version. Output is staged and checked before replacing the generated directory.
No cross-built executable is run by the packaging tool.

CI may provide a prebuilt resource directory:

```sh
go run scripts/native/build.go --version 1.2.3 --output /tmp/prebuilt/graphite-native
./gradlew :query:shadowJar -Pversion=1.2.3 -Pgraphite.nativePrebuilt=/tmp/prebuilt/graphite-native
```

The prebuilt path is relative to the repository root unless absolute. Its exact
version, target set, path set, architectures and SHA256 values must match; stale,
extra, symlinked, truncated or mislabelled resources fail the build. Use the same
`graphite.nativeTargets` for a prebuilt subset.

Version verification reads the actual initial `main.version` Go string from each
ELF/Mach-O executable, as well as the command package identity. Changing `VERSION`
alone cannot relabel an old executable. Prebuilt resources must retain symbols,
as the provided builder does. No cross-built executable is started to inspect it.

Correctness checks (no performance measurement):

```sh
go test -race scripts/native/build.go scripts/native/build_test.go
go vet scripts/native/build.go scripts/native/build_test.go
python3 -W error::ResourceWarning scripts/native/smoke.py --output /tmp/native-package-smoke.json
```

The smoke test checks both actual shadow JARs and both `installDist` scripts,
without a binary override, outside the repository working directory. It loads a
JVM-written four-CallSite store, compares the complete homepage bytes, and checks
SIGTERM and owned extraction cleanup. `--repo` and `--build` select separate source
and build snapshots. Cross-compilation alone does not establish runtime support;
local evidence and unrun remote checks are documented separately.
