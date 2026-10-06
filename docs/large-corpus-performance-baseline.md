# Large-corpus performance baseline

This baseline replaces the Elasticsearch fixture, whose graph was too small to be a useful
large-corpus signal. Tika, Hive, and the Kotlin compiler exercise different bytecode shapes while
remaining large enough to stress graph construction, persistence, mapped loading, and Cypher
queries.

## Reproducibility

Recorded on 2026-08-27 with:

- Apple M3 Max, 64 GiB RAM, macOS 14.3 (`arm64`)
- OpenJDK 17.0.18 (Homebrew)
- JMH 1.37
- production sources identical to `origin/main` at `1d910a8`; this change adds only fixtures,
  benchmarks, tests, and documentation, so the measurements establish the main implementation's
  baseline rather than compare a production-code optimization

The fixture identity is part of the automated gate:

| Corpus | Maven coordinate | JAR bytes | Classes | SHA-256 |
| --- | --- | ---: | ---: | --- |
| Tika | `org.apache.tika:tika-app:2.9.2` | 60,900,523 | 33,128 | `87e06f88c801fcb2beae5f15e707241edb14da468a154ad78be4e31ff982c3da` |
| Hive | `org.apache.hive:hive-exec:4.0.0` | 84,163,106 | 38,999 | `232d67c5d2ff54806944bb5b7402eaf1ebb81f11dbe4fd51bc5604a8e0c0bdad` |
| Kotlin compiler | `org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21` | 58,272,093 | 24,941 | `9fa8cdd1de0dccffe154c997d423ec6b5f53cd6d9177e3a77a9b0de03fb1bc81` |

## Automated 4 GiB gate

`largeCorpusTest` runs every corpus in a fresh, single-threaded, non-Kover test worker with
`-Xmx4g`, matching the Android end-to-end resource limit. The task is deliberately untracked, so
Gradle neither skips it as up to date nor restores it from the build cache. Each test has a
four-minute timeout and verifies the artifact fingerprint, exact graph shape, mapped query
results, end-to-end time ceiling, and sampled heap usage. It is wired into `check` and fails fast.

Record timing evidence while retaining fixture, graph-shape, and mapped-round-trip assertions.
Record mode bypasses only the machine-specific absolute pipeline ceiling:

```bash
./gradlew :webgraph:largeCorpusTest -Dlarge.corpus.record=true --no-daemon
```

Validate the committed baseline:

```bash
./gradlew :webgraph:largeCorpusTest --no-daemon
```

Strict validation results:

| Corpus | Nodes | Source edges | Persisted edges | Methods | Call sites | Persisted bytes | Build | Save | Mapped load | Query | Pipeline | Peak heap | Time / heap ceiling |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Tika | 3,897,012 | 4,497,723 | 4,342,382 | 312,788 | 1,002,088 | 328,441,109 | 12.055 s | 3.963 s | 138 ms | 1.948 s | 18.104 s | 3,399,385,088 B | 120 s / 4 GiB hard cap |
| Hive | 5,986,673 | 6,378,063 | 6,161,463 | 404,016 | 1,437,647 | 506,335,478 | 21.694 s | 5.620 s | 185 ms | 3.007 s | 30.506 s | 3,993,927,680 B | 180 s / 4 GiB hard cap |
| Kotlin compiler | 3,268,537 | 3,674,711 | 3,559,500 | 249,669 | 900,366 | 289,113,024 | 11.209 s | 3.154 s | 126 ms | 1.932 s | 16.421 s | 3,088,251,984 B | 120 s / 4 GiB hard cap |

### Graph-shape baseline changes

The table above is the 2026-08-27 recording; the gate asserts the current counts in
`LargeCorpusPerformanceGateTest`. Changes to the expected graph shape since then:

| Change | Tika edges (source / persisted) | Hive edges | Kotlin compiler edges |
| --- | ---: | ---: | ---: |
| `System.getProperty` is no longer linked to every packaged configuration file by `RESOURCE_LOOKUP` | 4,405,147 / 4,249,806 (−92,576) | 6,350,854 / 6,134,254 (−27,209) | 3,672,821 / 3,557,610 (−1,890) |
| Calls on function values resolve to every lambda shape, creating methods get a call site to the methods a function object implements for a supertype, and casts carry dataflow | 4,510,016 / 4,353,588 (+104,869 / +103,782) | 6,597,267 / 6,376,682 (+246,413 / +242,428) | 3,906,617 / 3,785,858 (+233,796 / +228,248) |

The `System.getProperty` change left node, method and call-site counts unchanged.

Persisted-size changes since then, with every count unchanged:

| Change | Tika | Hive | Kotlin compiler |
| --- | ---: | ---: | ---: |
| Every save writes the `graph.branchdefs` sidecar (branch-side local definitions and per-local definition tables) and the 36-byte `graph.metadata` trailer that binds it, both counted in the persisted size | +6,079,344 B | +10,505,164 B | +9,344,528 B |
| `graph.metadata` ends with the synthetic identity section (a 128-bit fingerprint per compiler-numbered synthetic member, keyed by class name or method signature) and the string table carries the method keys | +1,818,775 B | +10,577,222 B | +9,958,925 B |
| SootUp 3.0.1 types every local (2.0.0 left `unknown`), so the string table carries more type names | +6,166 B | +3,870 B | +10,705 B |
| The `graph.callsite-ordinals` sidecar: node id and ordinal per call site, 36 bytes of index per block of 256 call sites (the block's first node id and the SHA-256 of its entries), its 44-byte header and the 36-byte binding at the end of `graph.metadata` | +8,190,900 B | +11,754,172 B | +7,512,796 B |

The lambda change leaves method counts unchanged and adds nodes and call sites, on top of a base that
already writes the sidecar (the sidecar itself grows with the new locals: Tika +4,516 B, Hive +6,920 B,
Kotlin compiler +18,544 B, included in the persisted-size delta):

| Corpus | Nodes | Call sites | Persisted bytes |
| --- | ---: | ---: | ---: |
| Tika | 3,901,103 (+4,091) | 1,006,172 (+4,084) | +566,248 |
| Hive | 5,992,914 (+6,241) | 1,443,886 (+6,239) | +1,145,094 |
| Kotlin compiler | 3,292,214 (+23,677) | 922,876 (+22,510) | +2,650,922 |

The gate also decodes the sidecar against the trailer in `graph.metadata`, compares the mapped graph's
branch scopes and definition tables with the source graph's, records the sidecar size as
`branchDefinitionBytes` and the first mapped branch-definition access as `branchDefinitionsMs` (outside the
pipeline sum; the candidate must report it, held to a 5,000 ms absolute budget while the base harness does not
report it and to 30% plus 100 ms afterwards) in the audit marker.

The source graph can contain multiple outgoing edges to the same target. `GraphStore` is a simple
graph and preserves the last such edge, so the gate records both the source's logical edge count
and the unique `(from, to)` count expected after persistence. It then compares mapped node, method,
call-site, and persisted-edge counts exactly, and compares deterministic property and relationship
query rows between the source and mapped graphs.

The reported pipeline time is the sum of the separately timed production build, save, mapped-load,
and query phases. Save starts immediately after build; fixture checks and source/mapped validation
scans run outside that production phase sequence and timer. The time
ceilings leave ample room for shared-CI variance. Sampling Java used heap every 10 ms covers the
whole gate, including validation, and is reported for diagnosis only: it can miss a brief peak,
varies with GC and runner behavior, and does not include native or memory-mapped storage. It is
therefore not treated as a portable pass/fail metric. The fixed `-Xmx4g` worker limit and
out-of-memory failure are the hard memory gate.

## JMH baseline

Method-level construction uses the same 8 GiB `GraphBuildBenchmark` protocol as Android:

```bash
./gradlew :sootup:jmh \
  -Pjmh.filter='GraphBuildBenchmark.build(Tika|Hive|KotlinCompiler)GraphEndToEndConfig$' \
  --no-daemon
```

| Benchmark | Mode | Score |
| --- | --- | ---: |
| `buildTikaGraphEndToEndConfig` | Single shot | 11,878.662 ms/op |
| `buildHiveGraphEndToEndConfig` | Single shot | 21,389.999 ms/op |
| `buildKotlinCompilerGraphEndToEndConfig` | Single shot | 11,150.302 ms/op |

End to end uses the Android 4 GiB protocol and covers JAR build, save, mapped load, and Cypher query:

```bash
./gradlew :webgraph:jmh \
  -Pjmh.filter='GraphEndToEndBenchmark.(tika|hive|kotlinCompiler)_build_save_load_query$' \
  --no-daemon
```

| Benchmark | Mode | Score |
| --- | --- | ---: |
| `tika_build_save_load_query` | Single shot | 15,445.314 ms/op |
| `hive_build_save_load_query` | Single shot | 27,400.797 ms/op |
| `kotlinCompiler_build_save_load_query` | Single shot | 17,673.737 ms/op |

Load measurements use the same 8 GiB eager-versus-mapped protocol as Android:

```bash
./gradlew :webgraph:jmh -Pjmh.filter='LargeCorpusLoadBenchmark' --no-daemon
```

| Corpus | Eager load | Mapped load |
| --- | ---: | ---: |
| Tika | 3,394.974 ms/op | 86.478 ms/op |
| Hive | 5,505.411 ms/op | 129.515 ms/op |
| Kotlin compiler | 3,258.401 ms/op | 72.894 ms/op |

Mapped query measurements use two 1-second warmups and three 1-second measurements under 8 GiB:

```bash
./gradlew :webgraph:jmh \
  -Pjmh.filter='LargeCorpusQueryBenchmark.mapped_.*' \
  --no-daemon
```

| Query | Tika | Hive | Kotlin compiler |
| --- | ---: | ---: | ---: |
| `countStar` | 0.003 ms/op | 0.002 ms/op | 0.002 ms/op |
| `intConstantFilter` | 0.047 ms/op | 0.049 ms/op | 0.056 ms/op |
| `returnDistinct` | 0.185 ms/op | 0.110 ms/op | 0.114 ms/op |
| `simpleNodeMatch` | 0.073 ms/op | 0.088 ms/op | 0.064 ms/op |
| `singleHopRelationship` | 0.041 ms/op | 0.696 ms/op | 0.298 ms/op |

## Regression conclusion

There is no production-code difference from the referenced main commit, so neither the
method-level nor end-to-end data indicates a regression. These values are the initial comparison
point for future changes. Future PRs should run the same named benchmarks on main and the candidate
branch on the same machine and report the delta.
