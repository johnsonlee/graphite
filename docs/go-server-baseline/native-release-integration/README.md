# Integrated native release, Homebrew and Docker

This independent integration combines the verified de0608f0 Gradle packager with
the standalone native CPU profiler and the frozen release delta. Unlike the
provider's earlier extra-binary check, the default binary inside these JARs and
archives includes the profiler. No override binary is used by the native HTML
wrapper checks. source.json records the 27 combined source files; the subsequent
all-embed-integration.json pins the final Gradle input correction and proves its
rebuild leaves all six native resources byte-identical.

The full four-target build, two shadow JARs, two application distributions, actual
Picocli CommandRouteTest and query detekt passed. Packaging tests reject unsafe or
mismatched archives. An independent artifact check proves all four archives and
both JARs contain the same verified per-target native bytes, versions and the
new pprof dependency license (artifact-identity.json). The generated formula and
SHA manifest are retained here; its audit-version URLs have not been published.
Generated formula heredoc indentation is preserved with a path-specific trailing
blank exemption rather than changing the raw evidence.

Actual runtime checks passed:

- Four default JAR/installDist entry points loaded the persisted four-CallSite
  fixture and served the exact homepage, then removed owned extraction files.
- Nine launcher cases preserved arguments/stdin/status and selected one profile
  writer. Five actual JVM build/query/help/version/error cases wrote async-profiler
  HTML; native and nested-argument-file routes avoided starting that JVM agent.
- Two route-probe signal cases reclaimed their child and private IPC directory.
- Two native profile routes used the packaged binary/JAR, returned equal query
  responses and wrote genuine nonempty CPU HTML on termination.
- Both actual local Linux ARM64 and AMD64 Docker images loaded the fixture,
  served the complete homepage, ran native PID1 under uid/gid1000, and stopped.
  AMD64 used the local ARM64 engine's cross-architecture support.

The parent provider evidence remains in ../native-release-packaging and its
exact patch identity in delivery.json/frozen.patch. The full provider manifest was
hash-verified before applying its 14 implementation/tool files. Root additionally
verified actual binary version symbols and all embedded resource inputs in the
preceding packaging/profile commits. No frozen provider evidence was rewritten.

These initial runtime receipts retain direct-native/Docker exit0 versus JVM
bridge exit143. The separately audited signal-compatibility correction must be
integrated and rebuilt before claiming those statuses agree with main. No tiny
fixture or sampling exercise here is performance evidence. Windows native,
physical AMD64/macOS AMD64 execution, actual tap installation, remote CI/release
publication, full functionality and real64 paired 10x P95 remain unproven.
