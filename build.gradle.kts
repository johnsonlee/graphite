import org.gradle.api.Project.DEFAULT_VERSION

plugins {
    kotlin("jvm") version embeddedKotlinVersion apply false
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
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

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
