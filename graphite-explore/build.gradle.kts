description = "Graphite Explorer - Interactive web visualization for saved graphs"

plugins {
    application
    id("com.gradleup.shadow") version "8.3.0"
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
    id("me.champeau.jmh")
}

application {
    mainClass.set("io.johnsonlee.graphite.cli.ExploreMainKt")
}

val integrationFixtures: Configuration by configurations.creating

dependencies {
    implementation(project(":core"))
    implementation(project(":cypher"))
    implementation(project(":webgraph"))
    implementation(libs.picocli)
    implementation(libs.gson)
    implementation(libs.javalin)

    add(integrationFixtures.name, libs.android.all)

    jmh(project(":sootup"))
    jmh(libs.jmh.core)
    jmhAnnotationProcessor(libs.jmh.generator)
}

val integrationFixtureJvmArgs = providers.provider {
    integrationFixtures.resolve().mapNotNull { jar ->
        when {
            jar.name.startsWith("android-all-") && jar.name.endsWith(".jar") -> "-Dandroid.jar.path=${jar.absolutePath}"
            else -> null
        }
    }
}

val realMultiGraphJvmArgs = providers.provider {
    listOfNotNull(
        System.getProperty("graphite.multigraph.root")
            ?.takeIf { it.isNotBlank() }
            ?.let { "-Dgraphite.multigraph.root=$it" }
    )
}

val realMultiGraphRoot = providers.gradleProperty("graphite.multigraph.root")
    .orElse(providers.systemProperty("graphite.multigraph.root"))
    .orElse(providers.environmentVariable("GRAPHITE_MULTIGRAPH_ROOT"))

jmh {
    val filter = project.findProperty("jmh.filter") as String?
    if (filter != null) {
        includes.set(listOf(filter))
    }
    failOnError.set(true)
    jvmArgsAppend.addAll(integrationFixtureJvmArgs)
    jvmArgsAppend.addAll(realMultiGraphJvmArgs)
}

val jmhJarTask = tasks.named<Jar>("jmhJar")

tasks.register<JavaExec>("realMultiGraphAcceptance") {
    group = "verification"
    description = "Runs the strict 50-graph / 100M-node acceptance suite with an 8 GiB max heap"
    dependsOn(jmhJarTask)
    classpath(files(jmhJarTask.flatMap { it.archiveFile }))
    mainClass.set("org.openjdk.jmh.Main")

    val resultFile = layout.buildDirectory.file("results/jmh/real-multigraph-acceptance.json")
    outputs.file(resultFile)
    outputs.upToDateWhen { false }

    doFirst {
        val graphRoot = realMultiGraphRoot.orNull
        require(!graphRoot.isNullOrBlank()) {
            "Set -Pgraphite.multigraph.root=<root>, -Dgraphite.multigraph.root=<root>, " +
                "or GRAPHITE_MULTIGRAPH_ROOT for the real 50-graph corpus"
        }
        val report = resultFile.get().asFile
        report.parentFile.mkdirs()
        args(
            "RealMultiGraphMemoryBenchmark.*",
            "-foe", "true",
            "-rf", "json",
            "-rff", report.absolutePath,
            "-jvmArgsAppend", "-Dgraphite.multigraph.root=$graphRoot"
        )
    }
}

tasks.jar {
    archiveClassifier.set("slim")
}

tasks.shadowJar {
    archiveBaseName.set("graphite-explore")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()

    minimize {
        exclude(dependency("org.antlr:antlr4-runtime:.*"))
        exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib:.*"))
        exclude(dependency("org.jetbrains.kotlin:kotlin-reflect:.*"))
        exclude(dependency("org.slf4j:slf4j-nop:.*"))
        exclude(dependency("io.javalin:.*:.*"))
        exclude(dependency("org.eclipse.jetty:.*:.*"))
        exclude(dependency("org.eclipse.jetty.websocket:.*:.*"))
    }

    manifest {
        attributes("Main-Class" to "io.johnsonlee.graphite.cli.ExploreMainKt")
    }
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    "*Benchmark*",
                    "io.johnsonlee.graphite.cli.ExplorerMemoryCounters",
                    "io.johnsonlee.graphite.cli.MemorySample",
                    "io.johnsonlee.graphite.cli.ExploreMainKt"
                )
            }
        }
    }
}

tasks.test {
    testLogging {
        events("passed", "skipped", "failed")
        showExceptions = true
        showStackTraces = true
    }
}
