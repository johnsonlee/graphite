import org.gradle.api.tasks.testing.Test

description = "Graphite Cypher - Cypher query engine for Graphite graphs"

plugins {
    id("io.johnsonlee.sonatype-publish-plugin")
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
    api(project(":core"))
    implementation("org.antlr:antlr4-runtime:4.13.2")

    jmh(libs.jmh.core)
    jmhAnnotationProcessor(libs.jmh.generator)
}

jmh {
    val filter = project.findProperty("jmh.filter") as String?
    if (filter != null) {
        includes.set(listOf(filter))
    }
    failOnError.set(true)
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

plugins.withId("org.jetbrains.dokka") {
    tasks.named("dokkaHtml") {
        dependsOn(tasks.generateGrammarSource)
    }
}

tasks.withType<Jar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named("compileTestKotlin") {
    dependsOn(tasks.named("generateTestGrammarSource"))
}

tasks.named("compileJmhKotlin") {
    dependsOn(tasks.named("generateJmhGrammarSource"))
}

val filteredRelationshipMemoryTest by tasks.registering(Test::class) {
    description = "Verifies filtered relationship queries stay within a bounded heap"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter {
        includeTestsMatching("io.johnsonlee.graphite.cypher.FilteredRelationshipMemoryTest")
    }
    maxHeapSize = "256m"
    forkEvery = 1
    doNotTrackState("The heap-bound relationship regression must execute on every invocation")
}

tasks.named<Test>("test") {
    exclude("**/FilteredRelationshipMemoryTest.class")
}

tasks.named("check") {
    dependsOn(filteredRelationshipMemoryTest)
}
