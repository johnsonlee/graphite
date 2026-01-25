import org.gradle.api.Project.DEFAULT_VERSION

plugins {
    kotlin("jvm") version embeddedKotlinVersion apply false
    id("io.johnsonlee.sonatype-publish-plugin") version "1.10.0" apply false
}

allprojects {
    group = "io.johnsonlee.graphite"
    version = project.findProperty("version")?.takeIf { it != DEFAULT_VERSION } ?: "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
        google()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "maven-publish")

    dependencies {
        "implementation"(kotlin("stdlib"))
        "testImplementation"(kotlin("test"))
        "testImplementation"(kotlin("test-junit"))
    }

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("mavenJava") {
                from(components["java"])

                pom {
                    name.set(project.name)
                    description.set(project.description ?: "Graphite - Graph-based static analysis framework")
                    url.set("https://github.com/johnsonlee/graphite")

                    licenses {
                        license {
                            name.set("Apache License 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        }
                    }

                    developers {
                        developer {
                            id.set("johnsonlee")
                            name.set("Johnson Lee")
                        }
                    }

                    scm {
                        url.set("https://github.com/johnsonlee/graphite")
                        connection.set("scm:git:git://github.com/johnsonlee/graphite.git")
                        developerConnection.set("scm:git:ssh://git@github.com/johnsonlee/graphite.git")
                    }
                }
            }
        }

        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/johnsonlee/graphite")
                credentials {
                    username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                    password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}
