description = "Graphite WebGraph Store - Disk-backed graph storage using WebGraph compression"

plugins {
    id("io.johnsonlee.sonatype-publish-plugin")
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
    id("me.champeau.jmh")
}

kover {
    reports {
        filters {
            excludes {
                classes("*Benchmark*", "*GraphBuildPersist*")
            }
        }
    }
}

val testFixtures: Configuration by configurations.creating

dependencies {
    api(project(":core"))
    implementation(libs.webgraph)
    testImplementation(project(":cypher"))
    testImplementation(project(":sootup"))
    testFixtures(libs.elasticsearch)
    testFixtures(libs.android.all)

    jmh(project(":cypher"))
    jmh(libs.jmh.core)
    jmhAnnotationProcessor(libs.jmh.generator)
}

jmh {
    val filter = project.findProperty("jmh.filter") as String?
    if (filter != null) {
        includes.set(listOf(filter))
    }
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
