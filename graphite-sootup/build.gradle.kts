description = "Graphite SootUp Adapter - SootUp-based bytecode analysis backend"

dependencies {
    api(project(":graphite-core"))

    implementation(libs.sootup.core)
    implementation(libs.sootup.java.core)
    implementation(libs.sootup.java.bytecode.frontend)
    implementation(libs.sootup.callgraph)

    // Test dependencies - real libraries for integration testing
    testImplementation(libs.ff4j.core)
    testImplementation(libs.spring.web)
}
