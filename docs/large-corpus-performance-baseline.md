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
