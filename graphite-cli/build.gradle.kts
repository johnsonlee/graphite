description = "Graphite CLI - Command-line tool for bytecode static analysis"

plugins {
    application
    id("com.gradleup.shadow") version "8.3.0"
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

tasks.shadowJar {
    archiveClassifier.set("")  // No classifier - becomes main jar
    mergeServiceFiles()

    manifest {
        attributes["Main-Class"] = "io.johnsonlee.graphite.cli.MainKt"
    }
}
