description = "Graphite SootUp Adapter - SootUp-based bytecode analysis backend"

dependencies {
    api(project(":graphite-core"))

    implementation(libs.sootup.core)
    implementation(libs.sootup.java.core)
    implementation(libs.sootup.java.bytecode.frontend)
    implementation(libs.sootup.callgraph)
    implementation(libs.asm)  // For parsing generic signatures from bytecode

    // Test dependencies - real libraries for integration testing
    testImplementation(libs.ff4j.core)
    testImplementation(libs.spring.web)
    testImplementation(libs.jackson.annotations)

    // Lombok for testing Lombok-generated code analysis
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}
