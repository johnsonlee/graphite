# Native release and launcher chain

The Go server is distributed alongside the Java 17 CLI. Java remains required
for graph construction and offline query. The universal `graphite.jar` and legacy
`graphite-explore.jar` embed four binaries: Linux/macOS × amd64/arm64. The separate
`graphite-server-VERSION-OS-ARCH.tar.gz` archives need no JVM for serving. Native
Windows serving remains incomplete; it is not silently handled by Kotlin.

Local preparation, after the Gradle build in `README.md`:

```sh
python3 scripts/native/release.py --version 1.2.3 \
  --resources graphite-explore/build/generated/native-resources/graphite-native \
  --query-jar graphite-query/build/libs/graphite.jar \
  --explore-jar graphite-explore/build/libs/graphite-explore.jar \
  --output release-assets
```

The output must be empty. The tool invokes the shared Go prebuilt verifier,
compares every embedded JAR binary against those resources, and creates four
archives, the two JARs, `SHA256SUMS`, and the generated Homebrew formula. Archives
preserve executable permissions and include the project's license, all runtime
module licenses across the four targets, the Go license, and the fdlibm notices.
They use deterministic archive timestamps and ordering; this is an artifact
reproducibility property, not a performance claim. Release generation does not
upload anything. The existing publish workflow uploads only the JARs, archives
and SHA file, then updates the tap; Maven and npm publication paths are retained.

The Homebrew formula keeps `openjdk@17`, installs the matching native archive and
JAR into `libexec`, and writes `graphite-launcher.sh.in` with safely quoted absolute
paths. Its platform resource blocks follow Homebrew's [system configuration
DSL](https://docs.brew.sh/Formula-Cookbook#handling-different-system-configurations).
Direct `serve` calls `exec` on the native binary. Other commands use the JVM CLI;
that CLI's `serve` bridge still dispatches to the native runtime.

`--profile` remains a wrapper flag; `GRAPHITE_PROFILE` selects the report path
(default `profile.html`). A direct server request sets
`GRAPHITE_NATIVE_CPU_PROFILE=1`. Other profiled requests run the internal
`CommandRouteMainKt` read-only Picocli probe so nested argument files and global
help/version flags follow the actual CLI model. If the parsed command will run
`NativeServeCommand`, only the Go profiler is selected, via the regular JAR bridge.
Otherwise the JVM branch retains async-profiler and its existing installation
error. The probe never executes a user command or creates a data directory. The wrapper
owns the probe PID and forwards termination while waiting, so terminating a route
check does not leave an orphan child. The route output is kept in an owned private temporary directory and removed after the child exits or is terminated.
The wrapper strips `--profile` from its original argument vector, matching the
previous wrapper; it does not add another argument-file parser. Files containing
`--profile` are still handled by the normal CLI parser. Only one runtime writes
the selected profile path. The native HTML module must be present in the binary;
this release wiring does not itself implement that module.

The Docker workflow downloads the Linux archives and SHA manifest from the same
release, then stages only validated regular archive members:

```sh
python3 scripts/native/stage_docker.py --assets release-assets --version 1.2.3 --output native-runtime
docker build --platform linux/arm64 -f graphite-explore/Dockerfile -t graphite-explore:local .
python3 scripts/native/verify_docker.py --image graphite-explore:local --platform linux/arm64 --output /tmp/docker-correctness.json
```

The scratch image contains the native executable and notices, with no Java or
shell. Its entry point is `/app/graphite-server serve`, PID 1. UID/GID 1000,
`/app`, port8080, `/data` volume, and default `--id app /data` are retained. Numeric
ownership does not depend on runtime name lookup; see Docker's
[`COPY --chown` reference](https://docs.docker.com/reference/dockerfile/#copy---chown).
The standalone JVM CLI remains the supported graph-building entry point.

Further local correctness tools:

```sh
python3 -W error::ResourceWarning scripts/native/test_release.py
python3 -W error::ResourceWarning scripts/native/verify_launcher.py --jar /path/to/graphite.jar --output /tmp/launcher.json
python3 -W error::ResourceWarning scripts/native/verify_native_wrapper.py --jar /path/to/graphite.jar --native /path/to/profile-enabled/graphite-server --output /tmp/native-profile
```

`verify_launcher.py` requires an actual local async-profiler installation and
records its HTML outputs. Native wrapper profiling uses bounded functional CPU
sampling, with no elapsed-time, throughput or speedup assertion. The separate
native delivery workflow is a correctness gate; remote CI or a release is not
considered verified until it actually runs.
