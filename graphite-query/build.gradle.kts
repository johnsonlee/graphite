description = "Graphite CLI - build, query, and serve saved graphs"

plugins {
    application
    id("com.gradleup.shadow") version "8.3.0"
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
}

application {
    mainClass.set("io.johnsonlee.graphite.cli.MainKt")
    applicationName = "graphite"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":cypher"))
    implementation(project(":explore"))
    implementation(project(":sootup"))
    implementation(project(":webgraph"))
    implementation(libs.picocli)
    implementation(libs.gson)
}

tasks.jar {
    archiveClassifier.set("slim")
    manifest {
        attributes(
            "Implementation-Title" to "Graphite",
            "Implementation-Version" to project.version.toString(),
        )
    }
}

tasks.shadowJar {
    archiveBaseName.set("graphite")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()

    minimize {
        exclude(dependency("org.antlr:antlr4-runtime:.*"))
        exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib:.*"))
        exclude(dependency("org.jetbrains.kotlin:kotlin-reflect:.*"))
        exclude(dependency("org.soot-oss:.*:.*"))
        exclude(dependency("org.slf4j:slf4j-nop:.*"))
        exclude(dependency("io.javalin:.*:.*"))
        exclude(dependency("org.eclipse.jetty:.*:.*"))
        exclude(dependency("org.eclipse.jetty.websocket:.*:.*"))
    }

    manifest {
        attributes(
            "Main-Class" to "io.johnsonlee.graphite.cli.MainKt",
            "Implementation-Title" to "Graphite",
            "Implementation-Version" to project.version.toString(),
        )
    }
}

tasks.named<JavaExec>("run") {
    systemProperty("graphite.version", project.version.toString())
}

kover {
    reports {
        filters {
            excludes { classes("io.johnsonlee.graphite.cli.MainKt") }
        }
    }
}

tasks.test {
    systemProperty("graphite.version", project.version.toString())
    testLogging {
        events("passed", "skipped", "failed")
        showExceptions = true
        showStackTraces = true
    }
}
