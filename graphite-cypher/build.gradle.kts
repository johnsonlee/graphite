description = "Graphite Cypher - Cypher query engine for Graphite graphs"

plugins {
    antlr
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
    id("me.champeau.jmh")
}

kover {
    reports {
        filters {
            excludes {
                classes("*Benchmark*", "io.johnsonlee.graphite.cypher.parser.*")
            }
        }
    }
}

dependencies {
    antlr("org.antlr:antlr4:4.13.2")
    api(project(":graphite-core"))
    implementation("org.antlr:antlr4-runtime:4.13.2")

    jmh(libs.jmh.core)
    jmhAnnotationProcessor(libs.jmh.generator)
}

tasks.generateGrammarSource {
    val outDir = file("${layout.buildDirectory.get().asFile}/generated-src/antlr/main/io/johnsonlee/graphite/cypher/parser")
    arguments = arguments + listOf("-visitor", "-package", "io.johnsonlee.graphite.cypher.parser")
    outputDirectory = outDir
}

sourceSets {
    main {
        java {
            srcDir("${layout.buildDirectory.get().asFile}/generated-src/antlr/main")
        }
    }
}

tasks.compileKotlin {
    dependsOn(tasks.generateGrammarSource)
}

tasks.compileJava {
    dependsOn(tasks.generateGrammarSource)
}

tasks.named("compileTestKotlin") {
    dependsOn(tasks.named("generateTestGrammarSource"))
}
