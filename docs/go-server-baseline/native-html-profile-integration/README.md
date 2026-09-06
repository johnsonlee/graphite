# Integrated native CPU HTML profiling

The optional native profiler now starts before CLI parsing and graph loading,
flushes before process exit, and writes a standalone HTML flame graph. Exact
GRAPHITE_NATIVE_CPU_PROFILE=1 enables it; GRAPHITE_PROFILE selects its output,
defaulting to profile.html. Private temporary files and same-directory rename
preserve an existing report when parsing/rendering/writing fails. Disabled CLI
behavior is unchanged. Native profile does not depend on the JVM or a pprof
executable. The pinned google/pprof package decodes the runtime profile in-process.

`source.json` pins a clean archive of de0608f0 with the exact frozen profile source.
Eleven implementation/test files match the provider freeze; the main README also
retains the already committed Gradle distribution instructions. Independent whole
module race/vet and a fresh binary build passed, followed by all nine actual
binary scenarios. The enabled and disabled serve runs returned the same complete
result, and SIGTERM produced a nonempty actual CPU tree. Commands, SHA identities
and unmodified receipts are in verification.json and evidence/. These bounded
functional exercises establish sampling, not performance.

The report preserves int64 CPU totals, inline-frame ordering and JavaScript-safe
text handling. Provider browser checks exercised search coverage, zoom/back/reset,
empty profiles and hidden matches in 20,000-way fanout. The exact provider files
and manifest were hash-verified before integration and are retained in provider/.
Direct file:// navigation was blocked by the browser tool policy; that check was
not bypassed or represented as passing. Local HTTP browser interaction and Go
format/escaping/precision tests are distinct evidence.

Gradle inputs now include the HTML and all other current embedded JSON/gzip
resources. gradle-input/ retains a separate exact-source verification: nine HTML
phases and seven JSON phases prove missing-input failures, corrected native/JAR
rebuilds, stable unchanged builds, and restoration to the original binary SHA.
The actual Gradle task input collection contains all eleven resolved resources
from the eight production go:embed declarations. This is static coverage of
current locations, not automatic discovery of future embed paths.

A later compatibility fix must preserve main's signal exit status (143/130 rather
than the existing native 0), while retaining completed profile writes. Current
receipts intentionally preserve the pre-fix status. Full Graphite functionality,
release integration and the main-relative 10x real64 P95 goal remain open.
