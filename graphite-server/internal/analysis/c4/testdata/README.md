# Main-produced C4 correctness goldens

Generated on 2026-09-07 by `MainOracle.java`, using the unchanged main baseline jar:

- Main revision: `4e328b0109e13c896b74004823fb049fcb19251a`
- Jar SHA-256: `91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d`
- Source fixture: `graphite-explore/src/test/kotlin/io/johnsonlee/graphite/cli/c4/C4InferenceTest.kt`, `checkoutFixture`
- Jar: `/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar`

Regenerate from repository root:

```sh
java -Xmx256m --class-path /tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar graphite-server/internal/analysis/c4/testdata/MainOracle.java graphite-server/internal/analysis/c4/testdata
```

The harness calls main's architecture service directly, writes v3 persisted stores with GraphStore, and records all four view levels as complete JSON workspace and exact Mermaid/PlantUML/DSL output. It never invokes the native implementation to generate expected data. The persisted graphs contain methods and CallSite nodes (as the source fixture does), endpoint annotations and artifact metadata; they do not need data-flow graph edges for C4 inference.

Cases:

- `checkout`: main's four internal methods and six calls, plus a Spring HTTP endpoint.
- `artifact-families`: library-family collapse, artifact dependencies, and equal-weight references whose outer and inner insertion order differs from lexical order.
- `library`: reusable library role, runtime dependency, empty runtime/component scope.
- `checkout-limit-0`, `checkout-limit-1`: explicit inference limits, including the component-only minimum candidate pool.
- `dense`: 24 additional external targets and 24 independent internal capabilities, exceeding text readability limits while preserving the unbounded JSON model.

This was a short, isolated correctness oracle run, after the parent confirmed no performance measurement was running. No HTTP oracle process was started. Synthetic fixtures and their generation are not performance evidence.

## Unicode correctness oracle

`UnicodeOracle.java` calls ten unchanged main C4 naming helpers for 56 inputs
(560 results). It covers ROOT casing expansions/final Sigma, Unicode-version
boundaries, UTF-16 lengths and first-char behavior, isolated surrogates, Kotlin
blank/trim, supplementary characters and Java regex end-of-line semantics.
Inputs and outputs are arrays of UTF-16 units, not JSON strings subject to
surrogate replacement. The Java source uses ASCII Unicode escapes and generation
explicitly sets UTF-8; this host's default Java file encoding is US-ASCII.

`UnicodeModelOracle.java` saves a graph using main, reloads that persisted graph
with main's MAPPED loader, and records complete models and all three renderers
for all four levels. The graph contains an application, HTTP endpoint, Greek,
sharp-s, dotted-I, supplementary and isolated-surrogate names, and artifact
origins. `unicode-model/expected.json` encodes every string leaf as a `$utf16`
object so the comparison checks exact UTF-16 units. Go checks both MAPPED and
EAGER loading. This detects loss in workspace conversion and embedded property
JSON in addition to the naming helpers themselves.

Regenerate from repository root with the same unchanged main jar:

```sh
java -Dfile.encoding=UTF-8 -Xmx128m -cp /tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar graphite-server/internal/analysis/c4/testdata/UnicodeOracle.java graphite-server/internal/analysis/c4/testdata/unicode-helpers.json
java -Dfile.encoding=UTF-8 -Xmx256m -cp /tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar graphite-server/internal/analysis/c4/testdata/UnicodeModelOracle.java graphite-server/internal/analysis/c4/testdata/unicode-model
```

These small, direct JVM correctness runs did not start an HTTP server or load the
real 64-graph catalog. Host co-tenancy was possible; no timing, memory or speedup
claims are based on them. See `unicode-provenance.json` for identities, commands,
verification and artifact hashes, including the exhaustive shared Char oracle.
