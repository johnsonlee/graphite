# Independent Gradle native packaging integration

The ordinary query/explore JARs and application distributions now include the
same verified native server resources. Defaults build Linux/macOS AMD64/ARM64;
`graphite.nativeTargets=host` is explicit local scope and
`graphite.nativePrebuilt` verifies before copying. No binary override is needed.

## Source and independent checks

`source.json` pins the exact scoped source snapshot on bbdfae2a, with the provider
manifest and patch preserved separately. This clean archive predates Attempt7;
these are packaging correctness checks, not a latest-query performance result.
`final-verification.json` confirms all 19 scoped files still match that snapshot.

The packaging helper race tests and vet passed. Gradle built both shadow JARs and
both installDist distributions, and passed NativeServerLauncherTest and
GraphiteCommandTest. `verification.json` records commands and separate logs.
The default four-target build and a subsequent prebuilt build succeeded; all six
resource files remained byte-identical (`resource-identity.json`,
`prebuilt-verification.json`). Cross-built artifacts were inspected without being
executed; runtime checks here cover Darwin ARM64 only.

The four actual entry points each loaded the persisted four-CallSite correctness
fixture, returned the catalog and byte-exact homepage, extracted the native
binary with mode 0700, and removed its private extraction directory after SIGTERM
with JVM parent exit 143. `smoke-final.json` and its completion receipt are the
final evidence. The first smoke overlapped the prebuilt rebuild; it is preserved
as `smoke.json`, and the final smoke was rerun after that writer terminated.
Neither tiny-fixture run is a benchmark.

## Version counterexample and correction

The frozen helper initially accepted existing binaries after changing only the
VERSION sidecar to `3.0.0-false-label`. Its HTTP `/openapi.json` still reported
`3.0.0-packaging-audit`; `version-before.json` and `version-before-http.json`
preserve the counterexample. An initial expectation that Go 1.22 trimpath build
metadata retained linker flags was false, and an initial `/api/version` probe
returned 404; the corrected registered route and both stopped processes are
recorded in the receipt.

The helper now verifies the Go command package and reads the actual initial
`main.version` string through ELF/Mach-O symbols and sections without executing
cross-built code. Its own builds retain those symbols. The false label is
rejected before publishing output (`version-after.json`), while all four actual
matching binaries pass (`version-valid-all-four.json`). A regression test uses
different same-length version strings so matching length cannot satisfy it.

## Remaining scope

The pending standalone CPU profile HTML must be included in Gradle's native
inputs when that feature is integrated, with an HTML-only incremental rebuild
check. Release/Homebrew/Docker integration follows separately. Windows native,
remote CI and actual publication are not established here. Full functional parity
and the paired real-64 main-relative 10x P95 goal remain open.
