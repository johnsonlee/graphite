# Native CLI and JAR bridge milestone

Base `1c235916`, implementation worktree `/tmp/graphite-go-cli-bridge-1c235916`. This is a bounded CLI/launcher change. No remote workflow, release, tap update or container publication has been run. The earlier packaging audit and its original 15 observations are unchanged.

The unified `GraphiteCommand` now registers `NativeServeCommand`; the standalone Explore main registers `NativeExploreCommand`. They share Picocli's original option declarations with the reference ServeCommand, so help, version and parser errors are handled before native resolution. Their `call` path launches only native code. Existing Kotlin server classes remain for reference/library tests; there is no failure branch that starts them from either user main. Build and offline query registrations remain unchanged.

## Bridge contract

Resolution checks `-Dgraphite.server.binary`, then `GRAPHITE_SERVER_BINARY`; an explicitly selected path must be a nonempty regular executable file. An invalid override fails instead of searching for another executable. Without an override, the bridge selects a known `darwin-amd64`, `darwin-arm64`, `linux-amd64` or `linux-arm64` resource, verifies its entry in `/graphite-native/SHA256SUMS`, and extracts `/graphite-native/<platform>/graphite-server` into a private directory. It limits manifest/binary size, verifies SHA-256 before making the file executable, and removes owned extraction files after exit. Overrides are never deleted. Missing, duplicated or corrupt resources fail explicitly; nothing is downloaded at launch.

The command vector contains `serve` followed by Picocli's expanded subcommand arguments. There is no shell evaluation. Working directory, environment and stdin/stdout/stderr are inherited. Normal child exit status is returned unchanged. Parent interruption/shutdown terminates the child, waits for bounded graceful completion, then force-kills if needed. A direct-JAR invocation necessarily retains a Java launcher parent; HTTP/Cypher run in the Go child. Future shell/container launchers can exec native directly.

Both target selection and the current Store mmap implementation support Linux/macOS. Unsupported automatic platform selection gives a clear error while build/query remain available. This is not evidence of complete Windows runtime support; that remains a separate recorded requirement.

## Native command-line behavior

The Go command uses the same plain 80-column usage text captured from pinned main for unified `serve` and legacy standalone Explore invocation. Help goes to stdout, syntax/conversion errors exit 2, and startup/runtime validation errors exit 1. As in main, the serve/Explore `--version` exits 0 without output; top-level `graphite --version` remains the JVM version provider. Native HTTP version data is still supplied by `-X main.version=...` at build time.

Parsing supports interspersed options, repeatable graph mappings, explicit empty values, option aliases/attached short port values, help/version precedence, duplicate-option errors, strict signed integer limits, Java-compatible BMP decimal digits, booleans and load-mode enum validation, `--`, and default Picocli argument files. Argument-file behavior follows the actual bundled Picocli bytecode: StreamTokenizer quoting/escapes/comments, cwd-relative nested files, per-top-level cycle detection, and `@@` literals. This is a parser over options, not a lookup of preselected command strings. The static usage files are independently compared against main's output.

`graphite-server/cmd/graphite-server/testdata/cli/main.json` contains 146 actual pinned-main observations, across unified serve and standalone Explore modes. `capture.py` records the exact invocations and recreates this finite default-configuration corpus; unit tests compare full stdout, stderr and exit code. Additional tests inspect parsed argument-file values and distinguish provided empty paths/IDs from omitted arguments. This evidence does not establish every Picocli system-property customization, interactive ANSI/terminal-width behavior, non-UTF-8 argument-file locale or JVM initialization stack trace.

## Independent execution evidence

- Five focused `NativeServerLauncherTest` methods cover platform aliases, verified extraction/private permissions/deletion, corrupt/missing/duplicate/oversized manifest rejection, argument preservation and exit status using real child processes, and both public command classes.
- Existing `GraphiteCommandTest` passes, including actual preserved JVM graph building and offline queries.
- Both actual shadow JARs pass 18 external process checks: argv/stdin/stdout/stderr/environment/cwd, status37 propagation, verified embedded-resource extraction and cleanup, corrupt-resource rejection before starting a child, missing-resource failure without Kotlin fallback, parent-PID SIGTERM, process-group SIGINT/SIGTERM, forced cleanup of a child ignoring termination, and native HTTP startup from each JAR.
- HTTP checks load the independently JVM-written four-CallSite store, assert its catalog and compare the entire embedded homepage byte-for-byte. No 64-graph process or performance experiment is used.
- Final full native module `go test -race ./...` and `go vet ./...` pass. JVM targeted tests, existing unified CLI tests, explore/query detekt and both shadow-JAR builds pass.

`lifecycle-results.json` records each process result. Parent statuses are 143 for SIGTERM and 130 for group SIGINT; no tested child remained alive after parent exit. The force-kill cases are correctness deadlines, not timing measurements. `lifecycle-identity.json` records actual JAR/native hashes and paths. `source-identity.json` records source hashes, pinned main artifacts and exact build commands.

Reproduce native CLI comparisons from `graphite-server`:

```sh
python3 cmd/graphite-server/testdata/cli/capture.py
go test -race ./cmd/graphite-server
go test -race ./...
go vet ./...
CGO_ENABLED=0 go build -trimpath -ldflags '-X main.version=3.0.0-audit' -o /tmp/graphite-native-cli-bridge-server ./cmd/graphite-server
```

The repository's publishing plugin fails to configure a detached Git worktree because its Git repository resolver does not understand the worktree gitdir; the initial failure log is retained. The successful JVM build used `/tmp/graphite-cli-bridge-build-1c235916`, an archive of the same base plus byte-identical changed JVM sources/tests and `git init` (no commit), with an explicit version. Exact commands and logs are recorded. This is a local build workaround, not a hidden production modification. Two initial detekt failures led to extracting smaller IO helpers. Two early external verifier attempts asserted incorrect catalog field names; those harness failures are retained and were corrected to the actual `nodes` field before claiming success.

Reproduce the actual JAR/process checks after building the shadow JARs:

```sh
python3 graphite-explore/src/test/native-launcher/verify.py /tmp/graphite-go-cli-bridge-1c235916 /tmp/graphite-cli-bridge-build-1c235916 /tmp/graphite-native-cli-bridge-server /tmp/graphite-cli-bridge-lifecycle-freeze-evidence
```

The verifier's embedded-resource tests add the already compiled child probe to temporary copies of the actual JARs. This exercises real JAR resource lookup/extraction, but is not the forthcoming release resource assembly pipeline.

## Required next delivery block

At this milestone, normal Gradle shadow-JAR tasks still do not package native executables. Starting serve from those JARs therefore requires the explicit binary override; without it the new bridge fails clearly. The resource preparation tasks, multi-platform archives/checksum assets, release workflow, Homebrew dispatch and Docker native entrypoint must be integrated before declaring a complete default user installation. The current native startup banner still describes development status. Native HTML profiling equivalent to the Homebrew `--profile` behavior and Windows runtime support remain unfinished; neither is silently dropped or claimed complete. Maven/npm and remote publication have not changed in this block.
