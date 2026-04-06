description = "Graphite WebGraph Store - Disk-backed graph storage using WebGraph compression"

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

val testFixtures: Configuration by configurations.creating

dependencies {
    api(project(":graphite-core"))
    implementation(libs.webgraph)
    testImplementation(project(":graphite-cypher"))
    testImplementation(project(":graphite-sootup"))
    testFixtures(libs.elasticsearch)
    testFixtures(libs.android.all)

    jmh(project(":graphite-cypher"))
    jmh(libs.jmh.core)
    jmhAnnotationProcessor(libs.jmh.generator)
}

tasks.test {
    maxHeapSize = "2g"
    doFirst {
        testFixtures.resolve().forEach { jar ->
            when {
                jar.name.contains("elasticsearch") -> systemProperty("elasticsearch.jar.path", jar.absolutePath)
                jar.name.contains("android-all") -> systemProperty("android.jar.path", jar.absolutePath)
            }
        }
    }
}
