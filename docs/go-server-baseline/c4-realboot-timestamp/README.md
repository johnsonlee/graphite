# Real nested Spring Boot archive: C4 correctness

The unchanged main builder parsed the publicly released
`org.springframework.cloud.task.app:timestamp-task:2.1.1.RELEASE`, saved a graph,
and produced C4 output. Native Go matched all **32/32** complete outputs:
MAPPED/EAGER × context/container/component/all × Structurizr JSON/Mermaid/PlantUML/DSL.
JSON comparisons ignore object key order but compare the complete object;
text comparisons are byte-for-byte. Catalog values, complete class origins,
ordered persisted resources and manifest metadata also match in both modes.

This is a **selected application-package graph from a real archive**, not a
full dependency graph. The explicit main `LoaderConfig` includes libraries but
restricts bytecode to `org.springframework.cloud.task.app.timestamp` and nested
library names `spring-cloud-starter-task-timestamp-*.jar`. The resulting graph
contains 450 nodes, 37 edges, 9 methods and 13 call sites. Main's class-origin
metadata nevertheless indexes 15,445 archive classes; the persisted resource
accessor contains 117 entries. These larger metadata counts do not mean that all
52 nested libraries' methods were included in the graph.

## Public artifact and provenance

Spring's [official application-starter documentation](https://docs.spring.io/spring-cloud-task-app-starters/docs/current/reference/html/overview.html)
distinguishes the executable prebuilt applications from starter libraries and
specifies these Maven coordinates. The [official timestamp documentation](https://docs.spring.io/spring-cloud-task-app-starters/docs/current/reference/html/spring-cloud-task-modules-tasks.html)
describes the timestamp application. Maven Central's actual metadata and release
listing were fetched before selecting the fixed version; their raw responses
are retained here.

- [Fixed Maven Central JAR](https://repo.maven.apache.org/maven2/org/springframework/cloud/task/app/timestamp-task/2.1.1.RELEASE/timestamp-task-2.1.1.RELEASE.jar)
- Size: 25,365,592 bytes; SHA-256: `8076696d54b72f75640859b53a5c8be135955f79c1d7666222b187a68a8f07ca`.
- Published SHA-1 also matched: `75a8757ce56b039a4ce2480f790714309e42cef1`.
- Actual layout: one application class under `BOOT-INF/classes/`, 52 JARs under
  `BOOT-INF/lib/`; Boot version `2.1.13.RELEASE`.
- Root manifest names `org.springframework.boot.loader.JarLauncher` and the
  timestamp application's Start-Class, with a real continuation line.
- `layout.json` records the complete manifest and all nested JAR names.

The 25 MB input remains at
`/tmp/graphite-realboot-c4-oracle/timestamp-task-2.1.1.RELEASE.jar`; it is not
committed. `fetch-artifact.sh` retrieves the fixed official URL and verifies its
SHA-256 and Boot layout. The downloaded application was **never executed** or
added to the oracle's Java runtime classpath. It was passed only as a bytecode
input path to main's `JavaProjectLoader`.

## Result and source finding

Both implementations produce a subject named **Task Library**. They have empty
manifest metadata even though the downloaded archive has a valid outer Boot
manifest. This is an observed main behavior, not a native mismatch:

1. Main `ArchiveResourceAccessor.create` selects `NestedJarSource` rooted at
   `BOOT-INF/classes/`, then registers the nested library JARs. It does not
   register the outer archive as a general resource source. The built graph's
   manifest entries therefore come from nested libraries, and its first selected
   manifest does not identify the timestamp application.
2. Main `PersistedResourceStore.PERSISTED_SUFFIXES` includes `.properties`, `.yml`,
   `.yaml`, `.json`, `.xml`, `.txt`; it excludes `.MF`. The persisted graph has no
   manifest resource at all. Both loaders' manifest metadata remains empty.

References at pinned main `4e328b0109e13c896b74004823fb049fcb19251a`:
`graphite-sootup/.../ArchiveResourceAccessor.kt` lines 87–95,
`graphite-webgraph/.../PersistedResourceAccessor.kt` line 54, and
`graphite-explore/.../c4/SubjectDetector.kt` lines 139–157.
No production fix was made for this source behavior. Main's two
`Unsupported constant type: ClassConstant` parser messages are preserved in the
raw run log; they did not prevent its graph or C4 output from being produced.

## Evidence and reproduction

`fixture-and-outputs.tar.gz` contains the persisted graph, main and native full
outputs, and build/load catalog data. `native-source.tar.gz` contains the exact
frozen native production source plus `cmd/realboot`; its per-file hashes and
recorded HEAD are in `native-source-manifest.json`. The snapshot includes the
C4 Unicode candidate, so its per-file hashes are authoritative rather than the
recorded HEAD alone. `identity.json` records the binary hash, versions and scope.
`jvm-classpath-manifest.json` hashes all runtime JARs; the unchanged main explore
jar is first on the classpath, followed by the already built pinned-main query
runtime dependencies (including its SootUp loader).

Replay the saved graph without a JVM or downloaded application, from this folder:

```sh
mkdir -p /tmp/graphite-realboot-c4-replay
tar -xzf fixture-and-outputs.tar.gz -C /tmp/graphite-realboot-c4-replay
tar -xzf native-source.tar.gz -C /tmp/graphite-realboot-c4-replay
cd /tmp/graphite-realboot-c4-replay/native-source
go build -o ../native-realboot ./cmd/realboot
../native-realboot ../main/store ../native
```

Then run this folder's `compare.py /tmp/graphite-realboot-c4-replay`. It checks
all output bodies and catalog values and writes a fresh `comparison.json`.
Root may replace the extracted native production source with its independently
reviewed revision while retaining the archived `cmd/realboot` harness.

For complete regeneration, first run `sh fetch-artifact.sh OUTPUT_DIRECTORY`.
The pinned clone is `/tmp/graphite-go-main-baseline-clone-4e328b0`. Resolve its
already built runtime dependencies with:

```sh
./gradlew --offline --no-daemon -Dorg.gradle.jvmargs=-Xmx512m --max-workers=1 -I /absolute/path/classpath.init.gradle realBootRuntimeClasspath
```

Take the emitted `REALBOOT_CLASSPATH`, prepend the pinned main
`graphite-explore/build/libs/graphite-explore.jar`, and invoke:

```sh
java -Dfile.encoding=UTF-8 -Xmx512m -XX:ActiveProcessorCount=2 -cp "$realboot_classpath" RealBootOracle.java timestamp-task-2.1.1.RELEASE.jar main
```

`main-command.json` preserves the exact executed argument array, including all
absolute classpath entries; `runtime-classpath.txt` preserves the actual resolved
value. `RealBootOracle.java` records the full loader configuration and calls
main's builder, GraphStore, architecture service and renderers directly. It does
not generate expected output using the native implementation.

This was a bounded correctness run with a 512 MiB Java heap and two active Java
processors. Host co-tenancy with other work was possible. No HTTP server,
64-graph JVM runtime, benchmark, latency comparison or performance claim was
involved. This evidence establishes parity for the selected graph from this
real release; it does not establish full-corpus or full-dependency C4 parity.
