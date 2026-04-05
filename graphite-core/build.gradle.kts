description = "Graphite Core - A graph-based static analysis framework"

plugins {
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
    id("me.champeau.jmh")
}

dependencies {
    // Fastutil for memory-efficient primitive collections
    // This is the only external dependency - justified for performance
    api(libs.fastutil)

    jmh(libs.jmh.core)
    jmhAnnotationProcessor(libs.jmh.generator)
}
