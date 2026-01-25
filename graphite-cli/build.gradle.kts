description = "Graphite CLI - Command-line tool for bytecode static analysis"

plugins {
    application
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

tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.johnsonlee.graphite.cli.MainKt"
    }
}

// Create a fat jar with all dependencies
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "io.johnsonlee.graphite.cli.MainKt"
    }

    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
}
