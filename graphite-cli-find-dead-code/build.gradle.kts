description = "Graphite CLI - Find and remove dead code via bytecode analysis"

plugins {
    application
    id("com.gradleup.shadow") version "8.3.0"
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
}

application {
    mainClass.set("io.johnsonlee.graphite.cli.MainKt")
}

dependencies {
    implementation(project(":graphite-core"))
    implementation(project(":graphite-sootup"))

    implementation(libs.picocli)
    implementation(libs.snakeyaml)
    implementation(libs.gson)

    // IntelliJ PSI for source code manipulation
    implementation(kotlin("compiler-embeddable"))  // PSI for both Java and Kotlin source analysis
}

kover {
    reports {
        filters {
            excludes {
                // MainKt contains exitProcess() which terminates the JVM — untestable
                classes("io.johnsonlee.graphite.cli.MainKt")
            }
        }
    }
}

tasks.test {
    // IntelliJ PSI uses reflection to access JDK internals (Java 17+)
    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.io=ALL-UNNAMED"
    )

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()

    manifest {
        attributes["Main-Class"] = "io.johnsonlee.graphite.cli.MainKt"
    }
}
