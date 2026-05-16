import org.gradle.api.Project.DEFAULT_VERSION
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
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<DetektExtension>("detekt") {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        baseline = rootProject.file("config/detekt/baselines/${project.name}.xml")
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
