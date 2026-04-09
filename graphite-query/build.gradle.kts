description = "Graphite Query CLI - Query and visualize saved graphs"

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
    implementation(project(":graphite-cypher"))
    implementation(project(":graphite-sootup"))
    implementation(project(":graphite-webgraph"))
    implementation(libs.picocli)
    implementation(libs.gson)
}

tasks.shadowJar {
    archiveBaseName.set("graphite-query")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()

    minimize {
        exclude(dependency("org.antlr:antlr4-runtime:.*"))
        exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib:.*"))
        exclude(dependency("org.jetbrains.kotlin:kotlin-reflect:.*"))
        exclude(dependency("org.soot-oss:.*:.*"))
    }

    manifest {
        attributes("Main-Class" to "io.johnsonlee.graphite.cli.MainKt")
    }
}

kover {
    reports {
        filters {
            excludes { classes("io.johnsonlee.graphite.cli.MainKt") }
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
