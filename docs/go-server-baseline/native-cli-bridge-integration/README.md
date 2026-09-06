# Independent CLI/JAR integration verification

Root starts from `4182478e` and applies the frozen 35-file CLI/JAR bridge patch
`1de847ac329c212f8c5819f70e61e4d5226e878e474cc2be739e26470e9655a6`.
The original patch and manifest are retained here; `source.json` hashes the actual
independent integration archive. The archive contains no unrelated local changes.
Its temporary `git init` and explicit version avoid the publishing plugin's
existing detached-worktree resolver failure, without changing production build logic.

Independent review reproduced double argument-file expansion: with a file `literal`
containing `--help`, pinned main starts `--data @@literal --port 0` normally and
serves a catalog rooted at the literal `@literal` directory. The frozen bridge
expands `@@literal` in Picocli, then Go opens `literal` again and exits 2. Complete
before observations and actual commands are in `double-atfile-before.json`.

The integrated launcher re-escapes every leading `@` in Picocli's expanded tokens
before passing them to the native parser. Go then performs its normal single
unescape and receives the same literal values main parsed. No shell evaluation,
public option or environment protocol is added. This includes bare `@`, multiple
literal prefixes, and tokens obtained through nested argument files. Two JVM
files differ from the freeze: this boundary and six actual-child transport checks
in the existing public-command tests; the other 33 frozen files match byte-for-byte.

Verification completed against actual rebuilt artifacts:

- Full integrated native module `go test -race ./...` and `go vet ./...` pass.
- Six `NativeServerLauncherTest` methods, explore/query detekt, and both shadow
  JAR builds pass. The preserved `GraphiteCommandTest` also passes actual JVM build
  and offline query behavior.
- All 18 independent real-JAR process/resource/native-HTTP checks pass: argument,
  stdio, cwd/environment, child exit status, verified embedded extraction/cleanup,
  corrupt/missing resources, parent/group termination and force-kill cleanup.
- Twelve real server starts (four argument forms × pinned main / native Explore
  JAR / native unified JAR) all produce the expected complete empty catalog, with
  canonical literal data directory and normal live HTTP service. Each owned process
  is stopped. `literal-args-final/` retains incremental observations and final summary.

`verification.json`, `preserved-jvm-command.json`, `lifecycle-completion.json` and
`lifecycle/identity.json` record exact commands, exit codes and artifact hashes.
`verify-literal-args.py` is the independent main/JAR/native regression harness.
The compiled probe was preserved outside the source tree; `helper-relocation.json`
records its hash/path without altering the original lifecycle receipts. Tiny/empty
graphs are functional fixtures only: no performance claim is made here.

This commit establishes native dispatch, not complete default packaging. Default
Gradle builds do not yet include native executables and need an explicit
`GRAPHITE_SERVER_BINARY` or `-Dgraphite.server.binary` override. Resource assembly,
release artifacts, Homebrew/Docker routing, HTML profiling and Windows runtime
support remain required follow-up. Nothing was remotely published. Broader Cypher
parity and the full 64-graph main-relative 10× P95 goal remain open.
