# Native CLI signal exit-status compatibility

Base `de0608f0`, plus the frozen native HTML profiling patch
`a57591f69291d97b192488d3c302eb0c6f73de791d446444cb3790f0b823e83b`
(excluding its stale top-level README hunk), and the release patch
`19b53e606d3faa4baa63ef2fe031d9a1ccce1876151da5743a7002c66d17e0bb`.
The delivered patch is a **delta after those layers**, not another copy of them.
Its baseline `main.go` also matches root's `f4873238` byte-for-byte.

The native server previously handled TERM/INT by cancelling a context, completing
shutdown and returning0. The context discarded which signal had arrived. This
was visible through direct native execution, Homebrew's direct native branch and
Docker's native PID1. The JVM bridge already preserved the JVM parent's status.

Actual process and container observations, after each server had loaded the same
existing JVM-written four-CallSite fixture and answered its catalog request:

| Route | Pinned main | Previous native | Candidate |
|---|---:|---:|---:|
| Direct TERM | 143 | 0 | 143 |
| Direct INT | 130 | 0 | 130 |
| Homebrew direct TERM | 143 | 0 | 143 |
| Homebrew nested argument file, parent INT | JVM130 | Bridge130 | Bridge130 |
| Docker PID1 TERM | 143 | 0 (retained release observation) | 143 |
| Docker PID1 INT | 130 | 0 | 130 |
| TERM with profiler report-save failure | 143 | 1 | 143 |

The failing JVM profiler case prints `[ERROR] Could not open output file`; the
existing destination directory and its `keep` file remain intact. That evidence
rules out mapping only a successful Go return0. The signal must take precedence
over the command's return code after cleanup. Go still prints its flush error.
Without an OS signal, help returns0, invalid arguments return2, and a failed
profile save returns1, preserving the existing native profiler contract.

Implementation scope is five files: `main.go`, a small `signals.go`, its tests,
and the expected shutdown statuses in the two release verification scripts.
The listener remembers the first delivered TERM/INT notification and cancels the
command context. Shutdown and `executeProfiled` finish first. The listener is then
unregistered and joined before its state is read; a queued notification is not
lost if normal completion races listener scheduling. Cleanup is idempotent, and
unregistration precedes joining on both ordinary return and panic unwinding.
`main` exits with143/130 for an observed TERM/INT. No store, query, profiler format,
JVM bridge, Homebrew production script or Dockerfile is changed by this delta.

Evidence:

- `main-initial-observations.json` contains the three successful main direct and
  old-wrapper observations. A subsequent report-error fixture initially used a
  path containing spaces, exposing the original Homebrew wrapper's unquoted agent
  argument limitation. That failure occurred before server startup and is retained;
  the report-error case alone was corrected to use a space-free destination.
- `before/` then records the remaining baseline observations, including the real
  JVM async-profiler save failure and the old Go0/1 results. Previously completed
  simple JVM signals were not rerun.
- `final/` records the final Darwin ARM64 binary's direct TERM/INT, Homebrew direct
  TERM, nested-file bridge INT, signal-plus-save-error, ordinary help and invalid
  arguments. Every enabled report is parsed as the actual native HTML document
  after process exit. `normal-flush-error.json` separately confirms exit1 without
  a signal and preserves the existing destination.
- `docker/` records main TERM/INT under the original pinned-main Dockerfile and
  freshly built pinned-main JAR, old native INT, and the first candidate TERM/INT.
  `docker-final/` repeats only the two candidate cases after the final cleanup
  ordering adjustment. Both final reports are copied from the stopped container
  and parsed, proving flush completed before143/130. `release-before-docker.json`
  is the unchanged earlier native TERM0 observation from the release freeze.
- `race.log` and `vet.log` are the final full-module Go checks. Tests cover context
  cancellation, first notification preservation, queued notification retention,
  ordinary cleanup and the observed exit-code precedence. Earlier candidate
  outputs are retained, rather than relabelled as final binary evidence.

The main CLI source starts Javalin and joins its main thread in
`ExploreCommand.call`; it does not override the VM's signal exit status. The
observed VM values above are the contract; this report does not assume Kotlin's
`finally` block itself handles an OS signal. The old Homebrew script is reproduced
from pinned main's publish workflow, changing only resolved installation paths.
Main uses an independent clean `git archive 4e328b0` build, not the modified root
worktree. Native process checks use macOS ARM64; Docker uses the local Linux ARM64
engine. JAR, binary, Docker image, source, runtime and command identities are in
`identity.json`. No Windows-console, repeated-signal, SIGKILL or physical AMD64
runtime claim is made by this bounded check.

Reproduction (from the combined source root):

```sh
cd graphite-server
go test -race ./...
go vet ./...
CGO_ENABLED=0 go build -trimpath -ldflags '-X main.version=3.0.0-signal-audit' -o /tmp/graphite-native-signal-final ./cmd/graphite-server
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags '-X main.version=3.0.0-signal-audit' -o /tmp/graphite-native-signal-final-linux-arm64 ./cmd/graphite-server
cd ..
python3 -W error::ResourceWarning docs/go-server-baseline/native-signal-parity/verify.py --native /tmp/graphite-native-signal-final --main-jar /tmp/graphite-native-signal-main-build-4e328b0/graphite-query/build/libs/graphite.jar --bridge-jar /tmp/graphite-native-release-build-274cd678/graphite-query/build/libs/graphite.jar --output /tmp/signal-final --expected-native-signal posix
python3 -W error::ResourceWarning docs/go-server-baseline/native-signal-parity/verify_docker_signals.py --candidate-only --candidate-image graphite-signal-native:final --output /tmp/signal-docker-final
```

Main was built with `:query:shadowJar --no-daemon -Dorg.gradle.jvmargs=-Xmx2g
-Pversion=1.0.0-SNAPSHOT` in `/tmp/graphite-native-signal-main-build-4e328b0`.
The original Docker context is `/tmp/graphite-native-signal-docker-main`.
The final native context is `/tmp/graphite-native-signal-docker-after`, based on
the frozen native Docker layout with the final Linux binary and pprof license.
Both use `docker build --platform linux/arm64`; build logs and exact context file
hashes are retained. These are local correctness images, not release artifacts.

Expectation updates outside production code:

- This delta updates `scripts/native/verify_native_wrapper.py` to expect143 for
  both direct and bridged TERM, and `verify_docker.py` to expect143 after stop.
- The immutable profiling freeze's `verify-binary.py` expected0 for server TERM
  in both enabled and disabled cases; a new invocation must expect143. Its old
  evidence is historical and must not be rewritten. Help and ordinary argument
  error checks stay unchanged.
- Existing JVM launcher, Gradle smoke and bridge parent-signal expectations stay
  143/130. Internal `execute` / `executeProfiled` tests using a programmatically
  cancelled context still return their ordinary code; they are not OS signals.

No64 runtime, performance benchmark, release, remote push or commit was run.
Profiler data is retained solely to prove valid flush behavior, with no latency,
CPU-efficiency, throughput or percentile conclusion.
