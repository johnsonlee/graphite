# Main OpenAPI snapshot

`openapi.json` is the complete static document returned by pinned main
`4e328b0109e13c896b74004823fb049fcb19251a`. Only `info.version` is replaced at
runtime, with `unknown` for an unversioned binary (as in Kotlin). Release builds
can set `-ldflags '-X main.version=1.2.3'`.

To reproduce without starting a server, source-launch the oracle against a fat
JAR built from the pinned main checkout:

```sh
java --class-path /path/to/graphite-explore.jar scripts/JvmOpenApiOracle.java > /tmp/main-openapi.json
```

Normalize `info.version` to `unknown`, format the JSON, and compare it to this
snapshot. `manifest.json` pins the three defining Kotlin sources; the source
drift test requires an explicit snapshot review whenever those inputs change.
The document describes the target API contract. The rewrite's remaining feature
gaps are tracked in the module README and repository parity matrix.
