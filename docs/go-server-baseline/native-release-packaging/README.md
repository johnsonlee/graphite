# Native release, Homebrew and Docker correctness

This is the second delivery delta, based on `274cd678` plus the separately frozen
Gradle resource patch `1f27e1329d36f55815adfd5cb073dc04e60db2e0ee216007227b8e0138b0a22b`.
No remote workflow, tap update, release upload, image push or repository commit
was performed. The generated audit-version URLs are proposed release locations;
this record does not claim those URLs have been published.

The production change connects one verified resource set to the release archives,
universal/legacy JARs, Homebrew's matching native resource and a native Docker PID1.
Java17 graph building and offline query are retained. `scripts/native/RELEASE.md`
describes the interfaces and complete local reproduction commands. The added
`CommandRouteMainKt` parses the actual Picocli model without executing commands,
so `--profile @args` selects a single runtime writer even through nested files.
An owned private IPC directory and explicit PID/wait/signal handling cover the
route probe's lifetime. There is no Kotlin server fallback.

Local evidence:

- `gradle.log`: both shadow JARs, both distributions, two new actual Picocli route
  tests, and query detekt pass. The inherited Gradle block separately tested the
  six launcher methods and twelve existing build/query tests.
- `launcher.json`: nine actual shell/JAR cases. Four direct/argument-file/nested
  routes preserve arguments and stdin, select only the Go profiler and preserve
  child status37. Five actual JVM cases (build, query, global help/version and an
  argument-file parse error) select only async-profiler. The build writes a real
  ten-node persisted store from a compiled class, and the independent existing
  JVM-written four-CallSite fixture returns count4. The genuine JVM HTML outputs
  are retained beside the JSON. `Profiling started` is an existing async-profiler
  stdout prefix and is recorded, not removed by production code.
- `probe-lifecycle.json`: parent-PID SIGTERM and SIGINT while the actual route
  child is blocked terminate that child, preserve statuses143/130, remove its
  private IPC directory and leave no orphan.
- `native-html/`: two additional full wrapper checks use the independently frozen
  native profiling binary supplied by the profiling task. Both direct `serve` and
  nested argument-file dispatch issue30 bounded functional requests, get equal
  responses, and flush genuine self-contained Go HTML with a nonempty sampled
  tree. Only Go writes the selected report. Direct native SIGTERM exits0;
  the JVM bridge's parent status remains143. These are distinct current shutdown
  contracts, not a claim of signal-status equality with the previous JVM wrapper.
- `docker-arm64.json`, `docker-amd64.json`: actual local images built from the
  release archives load the same four-CallSite store, serve the complete expected
  homepage, run the native executable as PID1 under UID/GID1000, and stop with0.
  Default `/app`, port8080, `/data`, and `--id app /data` are checked. ARM64 runs
  in the local Linux ARM64 Docker engine; AMD64 uses that engine's cross-architecture
  execution support. This does not substitute for execution on physical AMD64.
- `homebrew-formula.json`: the real local Homebrew Formulary parses the generated
  formula and selects the exact Darwin ARM64 archive URL and checksum. No user
  installation or tap mutation is performed; other formula branches are checked
  structurally by the release tests, not claimed to be locally installed.
- `release-tests.log`: archive bytes/permissions and metadata, exact platform
  formula checksums, and rejection of wrong checksum/version/architecture,
  duplicate SHA entries and unsafe archive paths. The final archive/JAR bytes
  match those used to stage and run the Docker images.

The native profiling binary is an explicit extra input, not the native binary
inside this delta's base JARs/archives. `identity.json` records its final hash and
source-manifest location separately. Root integration must combine the profiling
module with the final packaging source and rebuild before declaring default
release profiling complete. The future native-delivery CI workflow requires that
module. Root also independently tightened the inherited Gradle verifier's binary
version check and runtime embed input tracking; this delta calls that verifier
rather than duplicating it. Those root changes are not misattributed to this base.
Windows native serving remains incomplete. Only the documented local cases have
been executed; remote CI and actual release distribution remain unverified.

Retained failures and corrections:

1. Initial route test build passed tests but failed detekt's varargs and return
   count rules. The final implementation uses a narrowly explained Java-varargs
   suppression and a single accumulated help flag.
2. Initial license collection walked all modules and requested an unused test-only
   dependency over a network that timed out. It now collects only actual server
   runtime dependencies across all four targets. The next attempt exposed
   Homebrew's Go license placement outside `GOROOT`; its actual Cellar path is
   handled explicitly. Neither failed invocation produced a successful release.
3. A premature Docker/Formula check after that failed archive generation failed
   on absent inputs. Those logs remain; the final checks used complete verified
   archives and passed.
4. Two launcher harness assumptions were corrected: persisted nodes are in
   `graph.nodedata`, and async-profiler prefixes stdout. Their JSON failures remain.
5. A relative native-HTML evidence path was incorrectly interpreted from the
   wrapper's temporary working directory. The verifier now makes output absolute;
   the production profiler correctly rejected the nonexistent path.
6. An intermediate pipe-only probe used a Bash3.2 process-substitution PID that
   cannot be waited on (`wait` returns127), causing wrong routing and a lifecycle
   failure. Actual wrapper/profile tests caught it. It was replaced by an ordinary
   background child with a private output file, which Bash3.2 can wait on. Final
   routing and lifecycle tests were rerun after this correction. A minimal local
   reproduction of the rejected primitive is:

   ```sh
   /bin/bash -c 'exec 3< <(printf ok); p=$!; read -r a <&3; wait "$p"'
   ```

Build snapshot: `/tmp/graphite-native-release-build-274cd678`, made from a clean
`git archive 274cd678`, the frozen Gradle files, and the two new JVM route files,
then `git init` with no commit to avoid the existing publishing plugin's detached
worktree/JGit issue. Exact source/binary/archive hashes, runtime identities and
completion codes are in `identity.json`. Small functional checks may have shared
the host with root's separate work; no64 runtime or performance experiment was
started here. Sampling verifies profiler functionality only. No elapsed-time,
throughput, percentile or speedup conclusion is drawn from these checks.
