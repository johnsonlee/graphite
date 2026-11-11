import org.gradle.api.Project.DEFAULT_VERSION
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import javax.xml.parsers.DocumentBuilderFactory
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension

plugins {
    kotlin("jvm") version embeddedKotlinVersion apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("io.johnsonlee.sonatype-publish-plugin") version "2.0.1"
    id("me.champeau.jmh") version "0.7.2" apply false
}

allprojects {
    group = "io.johnsonlee.graphite"
    version = project.findProperty("version")?.takeIf { it != DEFAULT_VERSION } ?: "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
        google()
    }

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories.maven {
                name = "GitHubPackages"
                val localDirectory = providers.gradleProperty("githubPackagesLocalRepository").orNull
                if (localDirectory != null) {
                    require(localDirectory.isNotBlank() && !localDirectory.contains("://")) {
                        "githubPackagesLocalRepository must be a nonempty local filesystem path"
                    }
                    url = rootProject.file(localDirectory).toURI()
                } else {
                    url = uri("https://maven.pkg.github.com/johnsonlee/graphite")
                    credentials {
                        username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                        password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                    }
                }
            }
        }
    }
}

// Exercise the exact named repository publication tasks during a local rehearsal.
// Resolve dependencies only when this task is requested, then inspect publications during
// execution so ordinary Gradle invocations do not eagerly realize every publication.
tasks.register("verifyGitHubPackagesLocalPublication") {
    group = "verification"
    description = "Publish to a local GitHubPackages file repository and verify every Maven publication"
    if (providers.gradleProperty("githubPackagesLocalRepository").isPresent) {
        dependsOn(providers.provider {
            allprojects.map { project ->
                project.tasks.withType<PublishToMavenRepository>().matching {
                    it.repository.name == "GitHubPackages" && it.publication.name == "mavenJava"
                }
            }
        })
    }
    doFirst {
        require(providers.gradleProperty("githubPackagesLocalRepository").isPresent) {
            "Local verification requires -PgithubPackagesLocalRepository=<directory>"
        }
    }
    doLast {
        val publications = allprojects.flatMap { project ->
            project.extensions.findByType<PublishingExtension>()?.publications
                ?.withType<MavenPublication>()?.filter { it.name == "mavenJava" }
                ?.map { project to it }.orEmpty()
        }
        val repository = rootProject.file(providers.gradleProperty("githubPackagesLocalRepository").get())
        check(publications.isNotEmpty()) { "No mavenJava publications found" }
        val xml = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder()
        val verified = publications.map { (project, publication) ->
            check(publication.groupId == "io.johnsonlee.graphite")
            check(publication.artifactId == project.name)
            check(publication.version == project.version.toString())
            val directory = repository.resolve("${publication.groupId.replace('.', '/')}/${publication.artifactId}/${publication.version}")
            val snapshots = if (publication.version.endsWith("-SNAPSHOT")) {
                val entries = xml.parse(directory.resolve("maven-metadata.xml")).getElementsByTagName("snapshotVersion")
                (0 until entries.length).associate { index ->
                    val entry = entries.item(index) as org.w3c.dom.Element
                    fun field(name: String) = entry.getElementsByTagName(name).item(0)?.textContent.orEmpty()
                    (field("extension") to field("classifier")) to field("value")
                }
            } else emptyMap()
            fun artifact(extension: String, classifier: String = ""): File {
                val version = if (publication.version.endsWith("-SNAPSHOT")) {
                    snapshots[extension to classifier]?.takeIf { it.isNotBlank() }
                        ?: error("Missing snapshot metadata for ${publication.artifactId}:$extension:$classifier")
                } else publication.version
                val suffix = if (classifier.isEmpty()) "" else "-$classifier"
                return directory.resolve("${publication.artifactId}-$version$suffix.$extension")
            }
            for (file in listOf(artifact("pom"), artifact("jar"), artifact("jar", "sources"), artifact("jar", "javadoc"))) {
                check(file.isFile) { "Missing publication artifact: $file" }
                check(file.length() > 0) { "Empty publication artifact: $file" }
            }
            val pom = xml.parse(artifact("pom")).documentElement
            val coordinates = (0 until pom.childNodes.length).map { pom.childNodes.item(it) }
                .filter { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }
                .associate { it.localName to it.textContent.trim() }
            check(coordinates["groupId"] == publication.groupId)
            check(coordinates["artifactId"] == publication.artifactId)
            check(coordinates["version"] == publication.version)
            "${publication.groupId}:${publication.artifactId}:${publication.version}".also {
                logger.lifecycle("Verified GitHubPackages publication $it")
            }
        }
        repository.resolve("verified-publications.txt").writeText(verified.sorted().joinToString("\n", postfix = "\n"))
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<DetektExtension>("detekt") {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(rootProject.files("detekt.yml"))
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = "17"
        exclude("**/build/**")
        reports {
            html.required.set(true)
            xml.required.set(true)
            txt.required.set(true)
            sarif.required.set(true)
            md.required.set(false)
        }
    }

    configurations.all {
        exclude(group = "ch.qos.logback")
    }

    dependencies {
        add("implementation", kotlin("stdlib"))
        add("runtimeOnly", "org.slf4j:slf4j-nop:2.0.13")
        add("testImplementation", kotlin("test"))
        add("testImplementation", kotlin("test-junit"))
    }
}
