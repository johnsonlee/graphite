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
