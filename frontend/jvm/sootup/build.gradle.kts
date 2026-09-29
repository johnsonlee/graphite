import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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
                // Kotlin lambda fixtures are loaded as bytecode by tests, never executed
                classes("sample.kotlinlambda.*")
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
    includeTests.set(false)
    val filter = project.findProperty("jmh.filter") as String?
    if (filter != null) {
        includes.set(listOf(filter))
    }
    failOnError.set(true)
    jvmArgsAppend.addAll(integrationFixtureJvmArgs)
}

// Kotlin lambda fixtures, compiled once per code-generation strategy: `indy` (the Kotlin 2.x
// default, `invokedynamic` through LambdaMetafactory) and `class` (the Kotlin 1.x default, a
// synthetic class per lambda). Tests load both outputs as bytecode; neither is on a classpath.
val kotlinLambdaFixtureModes = mapOf(
    "Indy" to listOf("-Xlambdas=indy", "-Xsam-conversions=indy"),
    "Class" to listOf("-Xlambdas=class", "-Xsam-conversions=class")
)
val kotlinLambdaFixtureSets = kotlinLambdaFixtureModes.map { (mode, flags) ->
    val set = sourceSets.create("kotlinLambda${mode}Fixtures") {
        java.setSrcDirs(emptyList<String>())
        resources.setSrcDirs(emptyList<String>())
    }
    extensions.getByType(KotlinJvmProjectExtension::class.java).sourceSets.getByName(set.name)
        .kotlin.setSrcDirs(listOf("src/kotlinLambdaFixtures/kotlin"))
    dependencies.add(set.implementationConfigurationName, "org.jetbrains.kotlin:kotlin-stdlib:$embeddedKotlinVersion")
    val compile = tasks.named<KotlinCompile>(set.getCompileTaskName("kotlin")) {
        compilerOptions.freeCompilerArgs.addAll(flags)
    }
    Triple(mode, set, compile)
}

// The Kotlin `indy` fixtures desugared by D8 the way an Android build does (`--min-api 21`):
// every `invokedynamic` lambda becomes a synthetic `Outer$$ExternalSyntheticLambda<n>` class.
val d8: Configuration by configurations.creating { isTransitive = false }
dependencies { d8(libs.r8) }
val (_, indyFixtureSet, indyFixtureCompile) = kotlinLambdaFixtureSets.single { it.first == "Indy" }
val desugarKotlinLambdaFixtures by tasks.registering(JavaExec::class) {
    val input = indyFixtureCompile.flatMap { it.destinationDirectory }
    val output = layout.buildDirectory.dir("classes/kotlin/kotlinLambdaDesugaredFixtures")
    val libraries = indyFixtureSet.compileClasspath
    inputs.dir(input).withPropertyName("classes")
    inputs.files(libraries).withPropertyName("libraries")
    outputs.dir(output)
    classpath = d8
    mainClass.set("com.android.tools.r8.D8")
    argumentProviders += CommandLineArgumentProvider {
        val out = output.get().asFile
        listOf("--classfile", "--min-api", "21", "--output", out.absolutePath, "--lib", System.getProperty("java.home")) +
            libraries.files.flatMap { listOf("--classpath", it.absolutePath) } +
            input.get().asFileTree.matching { include("**/*.class") }.files.map { it.absolutePath }
    }
    doFirst { output.get().asFile.run { deleteRecursively(); mkdirs() } }
}

tasks.test {
    kotlinLambdaFixtureSets.forEach { (mode, _, compile) ->
        val classesDir = compile.flatMap { it.destinationDirectory }
        inputs.files(classesDir).withPropertyName("kotlinLambda${mode}Fixtures")
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf("-Dkotlin.lambda.${mode.lowercase()}.fixtures=${classesDir.get().asFile.absolutePath}")
        }
    }
    val desugaredDir = desugarKotlinLambdaFixtures.map { it.outputs.files.singleFile }
    inputs.files(desugarKotlinLambdaFixtures).withPropertyName("kotlinLambdaDesugaredFixtures")
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf("-Dkotlin.lambda.desugared.fixtures=${desugaredDir.get().absolutePath}")
    }
}
