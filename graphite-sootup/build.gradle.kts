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

val integrationFixtures: Configuration by configurations.creating
integrationFixtures.isTransitive = false
val asmVersion = libs.versions.asm.get()

dependencies {
    api(project(":core"))

    implementation(libs.sootup.core)
    implementation(libs.sootup.java.core)
    implementation(libs.sootup.java.bytecode.frontend)
    implementation(libs.sootup.apk.frontend)
    implementation(libs.sootup.callgraph)
    implementation(libs.asm)  // For parsing generic signatures from bytecode
    implementation(libs.gson)

    // Test dependencies - real libraries for integration testing
    testImplementation(libs.ff4j.core)
    testImplementation(libs.spring.web)
    testImplementation(libs.jackson.annotations)
    testImplementation(libs.gson)
    testImplementation(libs.guava)

    // Lombok for testing Lombok-generated code analysis
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    // JMH benchmark dependencies
    jmh(libs.jmh.core)
    jmhAnnotationProcessor(libs.jmh.generator)
    jmh("org.ow2.asm:asm:$asmVersion")
    jmh("org.ow2.asm:asm-tree:$asmVersion")
    jmh("org.ow2.asm:asm-util:$asmVersion")
    jmh("org.ow2.asm:asm-commons:$asmVersion")
    jmh("org.ow2.asm:asm-analysis:$asmVersion")
    add(integrationFixtures.name, libs.android.all)
    add(integrationFixtures.name, libs.tika.app)
    add(integrationFixtures.name, libs.hive.exec)
    add(integrationFixtures.name, libs.kotlin.compiler.embeddable)
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
    val fixtures = integrationFixtures.resolve().associateBy { it.name }
    fun fixturePath(property: String, matcher: (String) -> Boolean): String {
        return System.getProperty(property)
            ?: fixtures.entries.single { matcher(it.key) }.value.absolutePath
    }
    listOf(
        "-Dandroid.jar.path=${fixturePath("android.jar.path") { it.startsWith("android-all-") }}",
        "-Dtika.jar.path=${fixturePath("tika.jar.path") { it.startsWith("tika-app-") }}",
        "-Dhive.jar.path=${fixturePath("hive.jar.path") { it.startsWith("hive-exec-") }}",
        "-Dkotlin.compiler.jar.path=" + fixturePath("kotlin.compiler.jar.path") {
            it.startsWith("kotlin-compiler-embeddable-")
        }
    )
}

jmh {
    val filter = project.findProperty("jmh.filter") as String?
    if (filter != null) {
        includes.set(listOf(filter))
    }
    failOnError.set(true)
    jvmArgsAppend.addAll(integrationFixtureJvmArgs)
}
