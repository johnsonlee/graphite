description = "Graphite Core - A graph-based static analysis framework"

plugins {
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
}

dependencies {
    // Fastutil for memory-efficient primitive collections
    // This is the only external dependency - justified for performance
    api(libs.fastutil)
}
