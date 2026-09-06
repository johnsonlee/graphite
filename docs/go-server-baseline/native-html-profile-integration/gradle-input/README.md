# Native embedded-resource Gradle input verification

The candidate adds four static input patterns to `prepareNativeServer`: profiling `report.html`, server `spec/**`, and Java regex `*.json` / `*.json.gz`. It changes one line in `graphite-explore/build.gradle.kts`; it does not execute Go during Gradle configuration. Every current production `go:embed` resource is now a declared task input.

## Exact base and delivery

The source worktree is `/tmp/graphite-go-profile-gradle-input-22eff982`, pinned to `22eff9820fb05d081093f2ad08d62b895c143446`. The effective base additionally contains the five root Gradle/native-builder snapshot files and the previously frozen native profiling implementation. Their exact hashes and profiling patch identity are in `effective-base.json`. They are dependencies, not this correction's delta.

- `html-only.patch` preserves the initial independently tested HTML-only correction.
- `embed-after-html.patch` adds the remaining resource patterns to that correction; use this if HTML is already included.
- `combined.patch` is the complete one-line correction relative to the recorded effective base.
- `source-manifest.json` records 3,627 source files and verifies byte equality between source and build checkouts. All 12 profiling files still match their prior frozen manifest. Both probe resources are restored to original bytes.

The publishing plugin could not open a linked worktree's repository pointer (`01-unfixed-initial.log`). Builds therefore ran in the ordinary shared local clone `/tmp/graphite-go-profile-gradle-input-22eff982-build`, at the same commit with byte-identical effective-base and candidate sources. No commit was made. The first artifact collector guessed the query JAR filename incorrectly after successful builds; that failure is preserved in `03-first-build-before-artifact-path-correction.log` and `driver-correction.txt`. The corrected complete sequence follows. A later input-audit helper initially used a relative output path in the build clone; no source or build changed, and the helper was rerun with the absolute evidence path.

## Executed correctness checks

Each build used:

```sh
./gradlew --no-daemon --max-workers=2 :explore:shadowJar :query:shadowJar \
  -Pgraphite.nativeTargets=host -Pversion=profile-input-check --console=plain
```

Mutation probes added `--info`. `observations.json` contains the nine HTML phases (03–11), and `embed-observations.json` the seven JSON phases (12–18), including commands, exit codes, four task statuses, native SHA-256 and both JAR SHA-256 values. Every phase exited zero.

| Probe | Before correction | After correction | Unchanged repeat |
|---|---|---|---|
| Only `internal/profiling/report.html` changed | All four tasks incorrectly UP-TO-DATE; binary and both JARs unchanged | Native prepare, resources, and both shadow JARs executed; embedded binaries changed | All four UP-TO-DATE; both JARs byte-identical |
| Only `internal/server/spec/openapi.json` changed | Same stale result with HTML-only correction | Same four tasks executed; embedded binaries changed | Same stable UP-TO-DATE result |

The collectors verified the changed source set contains exactly the named resource. In every phase, `graphite-native/darwin-arm64/graphite-server` inside both `graphite-explore.jar` and query `graphite.jar` matches the built binary SHA, and each JAR's `SHA256SUMS` agrees. Restoring each original resource restores the exact original native binary SHA `a228e94f4eeb735ec198478a43e4cdc8f73a413410711a4bf32aab764189e050`; restoration may reuse the JAR build cache. Subsequent unchanged builds remain UP-TO-DATE.

The HTML probe ran the changed binary with profiling enabled and `--help`; its generated report contains the changed title (`probe-cli.json`, `probe-generated-profile.html`). The JSON probe started an empty-catalog server, checked the changed title through one `/openapi.json` request, and stopped successfully with SIGTERM (`probe-openapi.json`). These prove resource use by the binary, in addition to ZIP packaging.

`embed-input-audit.json` enumerates all eight production embed declarations and their eleven resolved files. `inspect-inputs.init.gradle` then reads the actual Gradle `prepareNativeServer.inputs.files` collection without changing production configuration. `actual-input-verification.json` confirms: all eleven resources occur in the actual 127-file input collection (`actual-gradle-inputs.txt`, `19-actual-inputs.log`). This verifies current regex tables too, without repeating full JAR mutations for every table. Future new embed locations require corresponding static input coverage; this is not an automatic embed parser in Gradle.

## Scope

This is build correctness evidence on host `darwin-arm64`, not a four-target runtime test or benchmark. There was no CPU burn, 64-graph run, or performance measurement. Native runtime production code was unchanged; the already frozen profiling implementation's race/vet evidence remains separate. The full Gradle/JAR rebuilds and actual task-input inspection directly exercise this one-line build change. Root and all earlier frozen worktrees were left unchanged.
