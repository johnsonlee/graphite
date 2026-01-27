description = "Graphite Core - A graph-based static analysis framework"

dependencies {
    // Fastutil for memory-efficient primitive collections
    // This is the only external dependency - justified for performance
    api(libs.fastutil)
}
