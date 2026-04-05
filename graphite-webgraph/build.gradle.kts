description = "Graphite WebGraph Store - Disk-backed graph storage using WebGraph compression"

plugins {
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
}

val testFixtures: Configuration by configurations.creating

dependencies {
    api(project(":graphite-core"))
    implementation(libs.webgraph)
    testImplementation(project(":graphite-sootup"))
    "testFixtures"("${libs.zipkin.server.get().module}:${libs.zipkin.server.get().versionConstraint.requiredVersion}:exec@jar")
}

tasks.test {
    doFirst {
        val zipkinJar = testFixtures.resolve().firstOrNull()
        if (zipkinJar != null) {
            systemProperty("zipkin.jar.path", zipkinJar.absolutePath)
        }
    }
}
