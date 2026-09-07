# Actual main string-table lookup oracles

Pinned main 4e328b0109e13c896b74004823fb049fcb19251a, Java17 ARM64.
`main-method.jsonl` preserves explicit UTF16 units for45 actual StringTable.findId
lookups. `main-query.json` contains two complete nine-source public-executor
responses on the JVM-written fixtures. Duplicate and unsorted accepted tables
make the old Go first-linear match add g8 provenance incorrectly. Main and the
binary-search candidate retain only g0..g7. These are correctness fixtures only.

The generator Java sources are retained. Compile/run them with the pinned-main
explore JAR on the classpath, writing to a fresh directory; existing binary
fixtures and captured outputs must not be overwritten. Full runtime identities,
commands, initial probes and before/after controls are archived under
`docs/go-server-baseline/native64-findid-attempt10/independent/`.
