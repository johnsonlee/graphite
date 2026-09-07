# Independent ordinary projection integration regression

Pinned main: `4e328b0109e13c896b74004823fb049fcb19251a`, Java 17.0.18 ARM64. `CacheUnicodeOracle.java` runs the public Cypher executor on the actual main-written `../candidate-index/clean` fixture. Reflection reads retained index bytes, which determine subsequent query execution strategy; these are logical Java accounting values, not a native memory or performance measurement.

Eight sequential calls assert complete columns/rows and retained bytes. Canonical supplementary UTF8 and the equivalent WTF8 surrogate pair must share all three query caches: the first two calls retain 1650 bytes each. Before the local fix the second call retained 2110 bytes. Isolated high and low surrogates remain separate keys (2110 and 2570 bytes). Aliases do not affect cache identity, while repeated projected fields retain their ordered multiplicity (2770 bytes).

The fix normalizes only cache predicate terms to Java UTF16 identity. Other reachable key constituents are constrained to ASCII properties (`caller_class`, `caller_name`, `callee_class`, `callee_name`), fixed ASCII operators, booleans and integer limits. Projection storage properties use the same gate; `graphId` is omitted from storage properties. Aliases are absent from keys. Stored values, output strings, predicate evaluation, global key/comparison helpers, and UTF16 byte estimates are unchanged.

Reproduce from graphite-server with JDK 17 and the pinned main JAR:

```sh
javac -encoding UTF-8 -cp "$MAIN_JAR" -d "$ORACLE_CLASSES" internal/query/testdata/ordinary-integration/CacheUnicodeOracle.java
java -Dfile.encoding=UTF-8 -Xmx256m -XX:ActiveProcessorCount=2 -cp "$ORACLE_CLASSES:$MAIN_JAR" CacheUnicodeOracle internal/query/testdata/candidate-index/clean "$ORACLE_OUTPUT"
go test -race ./internal/query -run '^TestOrdinaryIntegrationJavaUTF16CacheIdentity$' -count=1 -v
```

The independent integration receipt preserves the failing pre-fix run, exact command arguments, final full-module race/vet runs, the original full 1048-case denominator, and the separate Store.Close/owned-value regression. No performance experiment was performed.
