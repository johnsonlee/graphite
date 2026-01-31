description = "Graphite CLI - Find HTTP endpoints and analyze return type hierarchy"

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
    implementation(libs.gson)
}

kover {
    reports {
        filters {
            excludes {
                classes("io.johnsonlee.graphite.cli.MainKt")
            }
        }
    }
}

tasks.test {
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
