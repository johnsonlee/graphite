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

val integrationFixtures: Configuration by configurations.creating
val asmVersion = libs.versions.asm.get()

dependencies {
    api(project(":core"))
    implementation(libs.webgraph)
    testImplementation(project(":cypher"))
    testImplementation(project(":sootup"))
    add(integrationFixtures.name, libs.elasticsearch)
    add(integrationFixtures.name, libs.android.all)

    jmh(project(":cypher"))
    jmh(project(":sootup"))
    jmh(libs.jmh.core)
    jmhAnnotationProcessor(libs.jmh.generator)
    jmh("org.ow2.asm:asm:$asmVersion")
    jmh("org.ow2.asm:asm-tree:$asmVersion")
    jmh("org.ow2.asm:asm-util:$asmVersion")
    jmh("org.ow2.asm:asm-commons:$asmVersion")
    jmh("org.ow2.asm:asm-analysis:$asmVersion")
}

configurations.matching { it.name.startsWith("jmh", ignoreCase = true) }.configureEach {
    resolutionStrategy.force(
        "org.ow2.asm:asm:$asmVersion",
        "org.ow2.asm:asm-tree:$asmVersion",
        "org.ow2.asm:asm-util:$asmVersion",
        "org.ow2.asm:asm-commons:$asmVersion",
        "org.ow2.asm:asm-analysis:$asmVersion"
    )
}

val integrationFixtureJvmArgs = providers.provider {
    val elasticsearchJar = Regex("""elasticsearch-\d+\.\d+\.\d+\.jar""")
    integrationFixtures.resolve().mapNotNull { jar ->
        when {
            elasticsearchJar.matches(jar.name) -> "-Delasticsearch.jar.path=${jar.absolutePath}"
            jar.name.startsWith("android-all-") && jar.name.endsWith(".jar") -> "-Dandroid.jar.path=${jar.absolutePath}"
            else -> null
        }
    }
}

jmh {
    val filter = project.findProperty("jmh.filter") as String?
    if (filter != null) {
        includes.set(listOf(filter))
    }
    failOnError.set(true)
    jvmArgsAppend.addAll(integrationFixtureJvmArgs)
}

tasks.test {
    maxHeapSize = "4g"
    // Large Android/ES fixtures must prove the 4G gate without inheriting heap from prior tests.
    forkEvery = 1
    doFirst {
        integrationFixtures.resolve().forEach { jar ->
            when {
                jar.name.contains("elasticsearch") -> systemProperty("elasticsearch.jar.path", jar.absolutePath)
                jar.name.contains("android-all") -> systemProperty("android.jar.path", jar.absolutePath)
            }
        }
    }
}
