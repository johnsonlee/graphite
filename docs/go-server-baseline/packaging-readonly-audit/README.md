# Native server delivery audit and migration proposal

Audit base: `3f08ecf27b0607493a40fb7ca9f261ba440184a8`, in `/tmp/graphite-go-packaging-audit-3f08ecf`. Pinned JVM main: `4e328b0109e13c896b74004823fb049fcb19251a`. This is a local source audit and small command-line observation, not a publication or performance run. `source-manifest.json` hashes every inspected delivery entry point in both revisions. No production files have been changed.

## Existing dependency and user-entry paths

| Entry point | Current dependency chain and behavior | Native delivery gap |
| --- | --- | --- |
| `graphite` installed by Homebrew | `publish.yml` builds `:query:shadowJar` → releases universal `graphite.jar` → generates tap formula → installs OpenJDK17 and a bash wrapper → every command executes Java | Formula has no native asset, platform selection or native dispatch |
| `graphite build` | `GraphiteCommand` → `BuildCommand` → `JavaProjectLoader` (SootUp/ASM) → `GraphStore.save(... prepareCallSiteStringIndex=true)` | This remains JVM work; removing it removes graph creation |
| `graphite query` | `QueryCommand` → JVM GraphStore AUTO loading → JVM CypherExecutor → text/JSON/CSV output | Keep the offline CLI command intact; it is not an HTTP query backend |
| `graphite serve` | Same wrapper/JAR → `ServeCommand.call` → Javalin, graph registry, topology and JVM Cypher | It does not call Go today |
| `java -jar graphite.jar serve ...` | `graphite-query` shadow JAR manifest → `MainKt` → same ServeCommand | Must bridge to native; leaving JVM here silently would leave a public server entry point unconverted |
| Standalone `graphite-explore.jar ...` | `:explore:shadowJar` → `ExploreMainKt` → `ExploreCommand : ServeCommand` | Legacy entry point still starts Javalin; not currently a release upload, but locally buildable |
| Gradle `:query:run`, `:explore:run`, `installDist`, `distZip`, generated start scripts | Application plugin launches those same main classes; default distribution contains JVM classpath/JARs | No native task/resource/distribution wiring; Go module is not in Gradle settings |
| Docker `graphite-explore` | Publish downloads released `graphite.jar`; Temurin17 image, uid/gid1000, `/app`, volume `/data`, port8080, Java `serve` entrypoint; defaults `--id app /data` | Native binary must become actual PID1; preserve existing flags, mounts, identity and image tags |
| Maven libraries | core/sootup/cypher/webgraph publishing via root Gradle/Sonatype tasks | Leave JVM public APIs and builder dependencies available; do not remove these modules because server is native |
| npm `graphite-mcp` | Bundled Node20-targeted TypeScript MCP process → HTTP at `GRAPHITE_URL` (default localhost:8080) | Does not spawn Java or Go; HTTP compatibility is its integration boundary |
| Native developer build | `go build ./cmd/graphite-server`, optional `-X main.version=...` | Works locally, but no archive/install/release pipeline exists |

The current release workflow builds only the unified query shadow JAR. The Docker publication supports `linux/amd64,linux/arm64`. The existing Go correctness workflow runs on Ubuntu/macOS, checks generated grammar/assets, tests/race/vet and builds an unversioned native temporary binary; it does not upload deliverables or connect publish jobs. JVM correctness remains a separate Gradle workflow.

## Features which must remain JVM-backed outside the server

`BuildCommand` accepts JAR/WAR/APK/class directories; `--include`, `--exclude`, `--include-libs`, `--lib-filter`, `--android-sdk`, output and verbosity. Its loader performs bytecode/callgraph/resource analysis, includes nested BOOT-INF/WEB-INF libraries when requested, and resolves Android platform jars via explicit/environment/OS/PATH lookup. It writes the authoritative graph and index formats consumed by the Go reader. This is not implemented by native server code. SootUp core/java/apk/callgraph frontends and ASM remain dependencies of `graphite-sootup`; resources and Kotlin APIs remain part of the product.

Keeping JVM `build` and offline `query` does not make the Go HTTP engine a JVM-backed implementation: `serve` must execute Go only for HTTP/query work. Java is needed for graph building and parser regeneration, and for the Java launcher when users explicitly choose `java -jar`; the standalone native server needs no JVM.

## Platform support: confirmed versus unverified

- **Confirmed delivery intent:** a universal Java17 JAR, Homebrew's JAR formula, and Linux amd64/arm64 Docker images. CI exercises JVM on Ubuntu and native Go on Ubuntu/macOS. Local main/native observations here are macOS ARM64.
- **Confirmed native implementation restriction:** `mmap_unix.go` implements mmap only on Linux and macOS. Other OSes return a MAPPED error; default server mode is MAPPED. Cross-compiling an executable does not prove it can load a graph on Windows.
- **Source-level broader JVM portability:** original server uses Java NIO and contains no Windows/other-OS exclusion. The builder explicitly documents Windows Android SDK discovery. The published JAR is not OS-labelled. Thus Windows cannot simply be declared unsupported by the old product; however this repository does not provide Windows runtime CI or a captured Windows server run. Other Java17 OS/architecture combinations are similarly not established here.
- **Initial concrete native matrix:** Darwin amd64/arm64 and Linux amd64/arm64 covers the existing native CI families and explicit Docker architectures. Runtime support for every previously working Java platform remains a recorded release gap until tested/implemented. An unavailable target must produce an explicit bridge error, never quietly launch Kotlin server. That explicit error is truthful, but is not 100% platform feature preservation.

## Observed command-line differences

`cli-observations.json` records exact stdout/stderr/status for pinned main standalone and unified `serve` (same compiled main classes via saved slim+explore classpath) and native CLI. This is not a rebuilt release-JAR smoke test. `capture_cli.py` reproduces the observations without opening any graph or listener.

| Case | Pinned main | Native current |
| --- | --- | --- |
| `--help`, `-h` | status0, help to stdout | status0, different help to stderr |
| Unknown option | status2, diagnostic plus usage | status1, different diagnostic plus usage |
| `serve --version` / standalone `--version` | status0, empty output (subcommand has no version provider) | status0, `graphite 3.0.0-audit` to stderr |
| No initial graph/data | status1 and required-data error | Same captured status and error |

Top-level `graphite --version` is separately provided by the JVM GraphiteVersionProvider. Native artifacts must stamp the release version consistently for HTTP/version information. The startup banner still says “in development”; it should be updated only when declaring the native delivery supported. Existing Picocli option parsing, `--` handling, dash-containing/path-with-space arguments, ordering of options, streams, exit codes and stderr diagnostics need a bounded CLI contract suite before changing installed dispatch.

Homebrew also intercepts `--profile` anywhere in arguments, loads async-profiler as a Java agent, and writes `${GRAPHITE_PROFILE:-profile.html}`. This currently covers `serve`. Routing `serve` to Go cannot preserve that feature by passing the JVM agent flag or profiling only the bridge. A native profiling implementation capable of the documented HTML artifact, with the same option/environment selection, or an explicitly accepted contract change is separate required work. Raw Go pprof bytes named `.html` would not preserve it. Keep the existing implementation for JVM build/query.

## Proposed smallest coherent delivery

1. **Freeze the CLI contract and one native artifact layout.** Build CGO-disabled, version-stamped native executables for the four initial targets, named with explicit OS/architecture; emit SHA256SUMS. Keep the ANTLR-generated parser and embedded assets in source, so compiling a release binary does not require Java or frontend tooling. Test each build target, and native startup/catalog/UI on actual available OS runners. Add explicit unsupported-platform tests instead of claiming cross-build equals runtime support.
2. **One native launcher for every JVM server entry point.** Add a small shared JVM bridge used by the unified `serve` call and legacy Explore main. It should select only a known platform asset; an explicitly configured path (for development/installers) must exist and be executable or fail. Otherwise use the packaged, versioned resource. Preserve argument vector without shell interpolation, current directory, environment and inherited stdin/stdout/stderr. Do not fall back to Javalin on any error. Keep original server classes available to tests/reference oracles, but remove them from the user `call` route.
3. **Keep direct-JAR and Gradle users working offline.** Package all supported target executables plus their hash manifest as resources in both distributed shadow JARs and application distributions. The bridge extracts the selected file into a private per-process directory with bounded cleanup, verifies its manifest digest and sets executable permissions before launch. A secure per-process extraction avoids cross-user cache writes and partial-file races. No executable download at launch. `run`/`installDist`/`shadowJar` must depend on an explicit native-resource preparation task; release can consume CI-built matrix outputs, while local builds can prepare the host target (document that host-only development JAR scope). This needs an intentional Gradle-Go dependency, not just a publish-workflow change.
4. **Define process lifecycle and verify it.** Use ProcessBuilder with inherited streams, wait for completion, return normal child status unchanged, and a shutdown hook that terminates and waits for the child with a bounded force-kill fallback. Test SIGINT/SIGTERM to the parent PID and to its process group, and interruption during startup; no orphan listener or shutdown deadlock. Java cannot POSIX-exec itself using standard ProcessBuilder, so a direct-JAR invocation retains a Java launcher parent; its server/query child remains wholly native. Do not claim literal signal identity without tests. Homebrew/shell and Docker should `exec` the native binary directly so ordinary installs and containers have native PID1.
5. **Wire both release channels in one change.** Preserve the universal `graphite.jar` for build/query/direct-JAR users; add native tarballs and checksum assets to the same release. Homebrew installs JAR and matching native artifact; `graphite serve ...` execs native, while all other commands keep existing JVM argument handling and OpenJDK dependency. `--profile` requires the native implementation described above before full parity. Docker copies the published target binary into a non-root Linux runtime, preserves image repositories/tags, uid/gid1000, `/app`, `/data`, port8080, CLI defaults and SIGTERM behavior. The server image no longer needs a JRE; graph building remains the host/unified CLI function, as current Docker's entrypoint is already serve-only. Document JVM environment variables as builder/query-only and define native resource settings rather than pretending `JAVA_TOOL_OPTIONS` configures Go.
6. **Keep ancillary delivery intact.** Maven and npm publication remain unchanged. MCP connects to the same HTTP API. Update installation, legacy PATH advice, Docker examples, native-only usage and Java requirement boundaries together. Include archive licenses/notices, generated parser/runtime licenses and native Math provenance. Do not republish, push tap changes or cut a release during local implementation review.

## Static frontend/build details

The four JVM `src/main/resources/web` files and Go embedded `internal/web/assets` files are byte-identical at this audit base. Go's binary embeds them; no npm frontend build or runtime resource directory is required. The existing frontend still references Cytoscape from cdnjs; no new offline guarantee follows from embedding. Keep the JVM location as the shared source until intentionally moving ownership. `generate.py` currently copies top-level files but does not remove stale assets; a renamed/deleted source asset could survive in Go. Strengthen the sync/check to compare the complete file set, including untracked outputs, and test deletion as well as changed contents. Release must build from checked assets or run this verified generation, never pick up arbitrary developer output.

## Concrete file scope for the implementation phase

| Area | Proposed files |
| --- | --- |
| Shared launcher and JVM routing | New `graphite-explore/.../NativeServerLauncher.kt` and tests; `ExploreCommand.kt`/`ExploreMain.kt` only at user launch boundary; preserve test/reference route classes |
| Native CLI contract/profiling | `graphite-server/cmd/graphite-server/main.go` and tests; new native profiling implementation/tests if preserving `--profile` HTML behavior |
| Native artifact creation | New checked script under `graphite-server/scripts/` producing archives and checksums; Gradle task/configuration in explore/query build files to stage/embed target resources; distribution/start-script tests |
| Packaging workflows | `.github/workflows/go-server.yml`, `.github/workflows/publish.yml`; native artifact build job must finish before JAR/release/Homebrew/Docker jobs consume it |
| Container | `graphite-explore/Dockerfile` and, if necessary, `.dockerignore`; retain existing published image identity |
| Asset consistency | `internal/web/generate.py`, focused sync test and workflow check |
| User documentation | root README, native README, scripts README; explicit unsupported-platform/profile gaps until solved |

Before implementation, the remaining decisions are whether the universal JAR contains the four supported binaries (recommended to preserve existing single-file offline launch), how native HTML profiling is supplied, and how to close versus explicitly report the unverified Windows/other-Java-platform gap. A shell-only Homebrew edit would leave direct-JAR, Gradle and Docker users on the old server and is therefore not the complete migration.

## Acceptance evidence required

Local/package smoke: archive extraction outside the repo, version/help and argument/exit tests, a real small graph built by the preserved JVM `build`, offline query text/JSON/CSV, native serve catalog/HTTP/UI/resource access, MCP HTTP smoke, direct-JAR embedded extraction, path-with-spaces, missing/corrupt native resource, subprocess exit and signal cleanup. Container smoke must verify default flags, bind mount permissions, non-root user and shutdown. Preserve existing full-module native race/vet, JVM tests and frontend tests. No new 64 runtime was started during this audit, and package correctness does not substitute for the required performance acceptance.
