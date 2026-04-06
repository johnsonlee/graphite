description = "Graphite Cypher - Cypher query engine for Graphite graphs"

plugins {
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
    id("me.champeau.jmh")
}

kover {
    reports {
        filters {
            excludes {
                classes("*Benchmark*")
            }
        }
    }
}

dependencies {
    api(project(":graphite-core"))

    jmh(libs.jmh.core)
    jmhAnnotationProcessor(libs.jmh.generator)
}
