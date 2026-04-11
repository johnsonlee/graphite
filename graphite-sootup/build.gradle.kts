description = "Graphite SootUp Adapter - SootUp-based bytecode analysis backend"

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

val testFixtures: Configuration by configurations.creating

dependencies {
    api(project(":core"))

    implementation(libs.sootup.core)
    implementation(libs.sootup.java.core)
    implementation(libs.sootup.java.bytecode.frontend)
    implementation(libs.sootup.callgraph)
    implementation(libs.asm)  // For parsing generic signatures from bytecode

    // Test dependencies - real libraries for integration testing
    testImplementation(libs.ff4j.core)
    testImplementation(libs.spring.web)
    testImplementation(libs.jackson.annotations)
    testImplementation(libs.guava)

    // Lombok for testing Lombok-generated code analysis
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    // JMH benchmark dependencies
    jmh(libs.jmh.core)
    jmhAnnotationProcessor(libs.jmh.generator)
    testFixtures(libs.elasticsearch)
    testFixtures(libs.android.all)
}
