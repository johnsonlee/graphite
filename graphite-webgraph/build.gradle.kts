import java.util.zip.ZipFile

description = "Graphite WebGraph Store - Disk-backed graph storage using WebGraph compression"

plugins {
    id("io.johnsonlee.sonatype-publish-plugin")
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
    id("me.champeau.jmh")
}

kover {
    currentProject {
        instrumentation {
            disabledForTestTasks.add("largeCorpusTest")
        }
    }
    reports {
        filters {
            excludes {
                classes(
                    "*Benchmark*",
                    "*Fixture64GraphPreparation*",
                    "*LargeBroadQueryPressureCounters",
                    "*GraphBuildPersist*",
                    "io.johnsonlee.graphite.webgraph.BroadQuery*",
                    "io.johnsonlee.graphite.webgraph.GcSnapshot"
                )
            }
        }
    }
}

val androidIntegrationFixture: Configuration by configurations.creating
androidIntegrationFixture.isTransitive = false
val largeCorpusFixtures: Configuration by configurations.creating
largeCorpusFixtures.isTransitive = false
val asmVersion = libs.versions.asm.get()
val graphOverrideProperties = listOf(
    "android.graph.path",
    "tika.graph.path",
    "hive.graph.path",
    "kotlin.compiler.graph.path"
)

dependencies {
    api(project(":core"))
    implementation(libs.webgraph)
    testImplementation(project(":cypher"))
    testImplementation(project(":sootup"))
    add(androidIntegrationFixture.name, libs.android.all)
    add(largeCorpusFixtures.name, libs.tika.app)
    add(largeCorpusFixtures.name, libs.hive.exec)
    add(largeCorpusFixtures.name, libs.kotlin.compiler.embeddable)

    jmh(project(":cypher"))
    jmh(project(":sootup"))
    jmh(libs.jmh.core)
    jmhAnnotationProcessor(libs.jmh.generator)
    jmh("org.ow2.asm:asm:$asmVersion")
    jmh("org.ow2.asm:asm-tree:$asmVersion")
    jmh("org.ow2.asm:asm-util:$asmVersion")
    jmh("org.ow2.asm:asm-commons:$asmVersion")
    jmh("org.ow2.asm:asm-analysis:$asmVersion")
}

configurations.matching { it.name.startsWith("jmh", ignoreCase = true) }.configureEach {
    resolutionStrategy.force(
        "org.ow2.asm:asm:$asmVersion",
        "org.ow2.asm:asm-tree:$asmVersion",
        "org.ow2.asm:asm-util:$asmVersion",
        "org.ow2.asm:asm-commons:$asmVersion",
        "org.ow2.asm:asm-analysis:$asmVersion"
    )
}

val integrationFixtureJvmArgs = providers.provider {
    val fixtures = (androidIntegrationFixture.resolve() + largeCorpusFixtures.resolve()).associateBy { it.name }
    fun fixturePath(property: String, matcher: (String) -> Boolean): String {
        return System.getProperty(property)
            ?: fixtures.entries.single { matcher(it.key) }.value.absolutePath
    }
    buildList {
        add("-Dandroid.jar.path=${fixturePath("android.jar.path") { it.startsWith("android-all-") }}")
        add("-Dtika.jar.path=${fixturePath("tika.jar.path") { it.startsWith("tika-app-") }}")
        add("-Dhive.jar.path=${fixturePath("hive.jar.path") { it.startsWith("hive-exec-") }}")
        add(
            "-Dkotlin.compiler.jar.path=" + fixturePath("kotlin.compiler.jar.path") {
                it.startsWith("kotlin-compiler-embeddable-")
            }
        )
        graphOverrideProperties.forEach { property ->
            System.getProperty(property)?.let { value -> add("-D$property=$value") }
        }
    }
}

val prepareBenchmarkFixtures by tasks.registering(Sync::class) {
    description = "Resolves all real-corpus JARs for external benchmark harnesses"
    group = "benchmark"
    from(androidIntegrationFixture)
    from(largeCorpusFixtures)
    into(layout.buildDirectory.dir("benchmark-fixtures"))
}

jmh {
    includeTests.set(false)
    val filter = project.findProperty("jmh.filter") as String?
    if (filter != null) {
        includes.set(listOf(filter))
    }
    failOnError.set(true)
    jvmArgsAppend.addAll(integrationFixtureJvmArgs)
}

val verifyJmhJarExcludesTests by tasks.registering {
    description = "Fails if the benchmark fat JAR packages this project's test output"
    group = "verification"
    val jmhJar = tasks.named<Jar>("jmhJar")
    val testOutput = sourceSets.test.get().output
    dependsOn(jmhJar, tasks.named("testClasses"))
    inputs.file(jmhJar.flatMap { it.archiveFile })
    inputs.files(testOutput)

    doLast {
        val testEntries = testOutput.files.flatMap { root ->
            if (!root.exists()) {
                emptyList()
            } else {
                root.walkTopDown()
                    .filter(File::isFile)
                    .map { it.relativeTo(root).invariantSeparatorsPath }
                    .toList()
            }
        }.toSet()
        val archive = jmhJar.get().archiveFile.get().asFile
        val leakedEntries = ZipFile(archive).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter(testEntries::contains)
                .sorted()
                .toList()
        }
        check(leakedEntries.isEmpty()) {
            "JMH JAR contains test output:\n${leakedEntries.joinToString("\n")}"
        }
    }
}

val validateJmhGraphOverrides by tasks.registering(JavaExec::class) {
    description = "Validates explicit persisted-graph JMH overrides before benchmark forks start"
    group = "verification"
    classpath = sourceSets.named("jmh").get().runtimeClasspath
    mainClass.set("io.johnsonlee.graphite.webgraph.BenchmarkCorpusPreflight")
    graphOverrideProperties.forEach { property ->
        System.getProperty(property)?.let { value -> systemProperty(property, value) }
    }
    onlyIf { graphOverrideProperties.any { property -> System.getProperty(property) != null } }
}

tasks.named("jmh") {
    dependsOn(validateJmhGraphOverrides)
}

tasks.test {
    maxHeapSize = "4g"
    // The Android fixture must prove the 4 GiB gate without inheriting heap from prior tests.
    forkEvery = 1
    exclude("**/*CorpusPerformanceGateTest.class")
    doFirst {
        androidIntegrationFixture.resolve().forEach { jar ->
            when {
                jar.name.startsWith("android-all-") -> systemProperty("android.jar.path", jar.absolutePath)
            }
        }
    }
}

val largeCorpusTest by tasks.registering(Test::class) {
    description = "Runs Tika, Hive, and Kotlin compiler gates in isolated 4 GiB JVMs"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/*CorpusPerformanceGateTest.class")
    maxHeapSize = "4g"
    maxParallelForks = 1
    forkEvery = 1
    failFast = true
    doNotTrackState("Large-corpus timing and heap gates must execute on every invocation")
    shouldRunAfter(tasks.test)
    systemProperty("large.corpus.record", System.getProperty("large.corpus.record", "false"))
    testLogging {
        events("passed", "skipped", "failed", "standardOut")
        showStandardStreams = true
        showExceptions = true
        showStackTraces = true
    }
    doFirst {
        largeCorpusFixtures.resolve().forEach { jar ->
            when {
                jar.name.startsWith("tika-app-") -> systemProperty("tika.jar.path", jar.absolutePath)
                jar.name.startsWith("hive-exec-") -> systemProperty("hive.jar.path", jar.absolutePath)
                jar.name.startsWith("kotlin-compiler-embeddable-") ->
                    systemProperty("kotlin.compiler.jar.path", jar.absolutePath)
            }
        }
    }
}

tasks.named("check") {
    dependsOn(largeCorpusTest)
}
