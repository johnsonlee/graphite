package io.johnsonlee.graphite.cli

import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * Shared PSI environment for both Java and Kotlin source editors.
 *
 * Uses [KotlinCoreEnvironment] which bundles shaded IntelliJ PSI classes
 * (including Java PSI) from kotlin-compiler-embeddable. This avoids
 * classpath conflicts with separate IntelliJ Java PSI dependencies.
 */
internal object SharedPsiEnvironment {

    val project: Project by lazy {
        val disposable = Disposer.newDisposable("SharedPsiEnvironment")
        val configuration = CompilerConfiguration().apply {
            put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        }
        KotlinCoreEnvironment.createForProduction(
            disposable,
            configuration,
            EnvironmentConfigFiles.JVM_CONFIG_FILES
        ).project
    }
}
