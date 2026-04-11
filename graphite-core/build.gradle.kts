description = "Graphite Core - A graph-based static analysis framework"

plugins {
    id("io.johnsonlee.sonatype-publish-plugin")
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
    // Fastutil for memory-efficient primitive collections
    // This is the only external dependency - justified for performance
    api(libs.fastutil)

    jmh(libs.jmh.core)
    jmhAnnotationProcessor(libs.jmh.generator)
}
