package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.analysis.DeadCodeResult
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import java.io.File
import java.nio.file.Path

// ============================================================================
// Deletion action types
// ============================================================================

/**
 * An action to perform on source code.
 * Actions are planned first, then executed (or previewed with --dry-run).
 */
sealed class DeletionAction {
    abstract val sourceFile: File
    abstract val description: String

    /**
     * Delete the entire source file (all methods in the class are dead).
     */
    data class DeleteFile(
        override val sourceFile: File,
        val className: String,
        val reason: String
    ) : DeletionAction() {
        override val description = "[DELETE] ${sourceFile.path} ($reason)"
    }

    /**
     * Delete a single method from a source file.
     */
    data class DeleteMethod(
        override val sourceFile: File,
        val className: String,
        val methodName: String,
        val parameterTypes: List<String>,
        val startLine: Int? = null,
        val endLine: Int? = null
    ) : DeletionAction() {
        override val description: String
            get() = buildString {
                append("[DELETE] ${sourceFile.path}")
                if (startLine != null && endLine != null) append(":$startLine-$endLine")
                append(" $methodName()")
            }
    }

    /**
     * Replace an if/else statement with its alive branch.
     */
    data class CleanupBranch(
        override val sourceFile: File,
        val methodName: String,
        val deadBranchKind: ControlFlowKind,
        val deadCallSiteNames: List<String>,
        val startLine: Int? = null
    ) : DeletionAction() {
        private val keptBranch =
            if (deadBranchKind == ControlFlowKind.BRANCH_TRUE) "false" else "true"

        override val description: String
            get() = buildString {
                append("[CLEANUP] ${sourceFile.path}")
                if (startLine != null) append(":$startLine")
                append(" remove ${deadBranchKind.name.lowercase().replace("branch_", "")} branch, keep $keptBranch branch")
            }
    }
}

// ============================================================================
// Source file resolver
// ============================================================================

/**
 * Resolves fully-qualified class names to source files by searching source directories.
 *
 * Resolution strategies (tried in order):
 * 1. Direct mapping: `com.example.Foo` -> `com/example/Foo.java` or `.kt`
 * 2. Kotlin file facades: `com.example.UtilsKt` -> `com/example/Utils.kt` (top-level functions)
 * 3. Inner classes: `com.example.Outer$Inner` -> `com/example/Outer.java` or `.kt`
 */
class SourceFileResolver(private val sourceDirs: List<Path>) {

    /**
     * Find the source file for a given class.
     * Checks both .java and .kt extensions across all source directories.
     * Handles inner classes by resolving to the outer class file.
     */
    fun resolve(className: String): File? {
        val relativePath = className.replace('.', File.separatorChar)

        for (sourceDir in sourceDirs) {
            val javaFile = sourceDir.resolve("$relativePath.java").toFile()
            if (javaFile.exists()) return javaFile

            val ktFile = sourceDir.resolve("$relativePath.kt").toFile()
            if (ktFile.exists()) return ktFile

            // Kotlin file-level functions: com.example.UtilsKt -> com/example/Utils.kt
            val simpleName = className.substringAfterLast('.')
            if (simpleName.endsWith("Kt") && simpleName.length > 2) {
                val originalName = simpleName.removeSuffix("Kt")
                val packagePath = className.substringBeforeLast('.', "").replace('.', File.separatorChar)
                val fileFacadePath = if (packagePath.isNotEmpty()) {
                    "$packagePath${File.separatorChar}$originalName.kt"
                } else {
                    "$originalName.kt"
                }
                val fileFacadeKt = sourceDir.resolve(fileFacadePath).toFile()
                if (fileFacadeKt.exists()) return fileFacadeKt
            }

            // Inner class: com.example.Outer$Inner -> com/example/Outer.java
            if ('$' in className) {
                val outerClassName = className.substringBefore('$')
                val outerPath = outerClassName.replace('.', File.separatorChar)

                val outerJava = sourceDir.resolve("$outerPath.java").toFile()
                if (outerJava.exists()) return outerJava

                val outerKt = sourceDir.resolve("$outerPath.kt").toFile()
                if (outerKt.exists()) return outerKt
            }
        }

        return null
    }
}

// ============================================================================
// Source code editor
// ============================================================================

/**
 * Plans and executes dead code deletions on source files.
 *
 * Uses IntelliJ Java PSI for .java files and kotlin-compiler-embeddable for .kt files.
 */
class SourceCodeEditor(
    private val resolver: SourceFileResolver,
    private val verbose: ((String) -> Unit)? = null
) {

    private val javaEditor: JavaSourceEditor by lazy { JavaSourceEditor() }
    private val kotlinEditor: KotlinSourceEditor by lazy { KotlinSourceEditor() }

    /**
     * Plan deletion actions for a set of dead code results.
     *
     * Does not modify files -- returns a list of actions that can be previewed or executed.
     *
     * @param result the dead code analysis result
     * @param graph the program graph (used to determine total methods per class)
     */
    fun planDeletions(result: DeadCodeResult, graph: Graph): List<DeletionAction> {
        val actions = mutableListOf<DeletionAction>()

        // Combine all dead methods from both unreferenced and assumption-based detection
        val allDeadMethods = result.unreferencedMethods + result.deadMethods
        val deadMethodsByClass = allDeadMethods.groupBy { it.declaringClass.className }

        for ((className, deadMethods) in deadMethodsByClass) {
            val sourceFile = resolver.resolve(className)
            if (sourceFile == null) {
                verbose?.invoke("  No source file found for $className")
                continue
            }

            if (isEntireClassDead(className, deadMethods, graph)) {
                actions.add(
                    DeletionAction.DeleteFile(
                        sourceFile = sourceFile,
                        className = className,
                        reason = "dead class, all ${deadMethods.size} methods unreachable"
                    )
                )
            } else {
                for (method in deadMethods) {
                    actions.add(
                        DeletionAction.DeleteMethod(
                            sourceFile = sourceFile,
                            className = className,
                            methodName = method.name,
                            parameterTypes = method.parameterTypes.map { it.simpleName }
                        )
                    )
                }
            }
        }

        // Dead branch cleanups
        val deletedClasses = actions
            .filterIsInstance<DeletionAction.DeleteFile>()
            .map { it.className }
            .toSet()

        for (branch in result.deadBranches) {
            val className = branch.method.declaringClass.className
            if (className in deletedClasses) continue

            val sourceFile = resolver.resolve(className) ?: continue

            actions.add(
                DeletionAction.CleanupBranch(
                    sourceFile = sourceFile,
                    methodName = branch.method.name,
                    deadBranchKind = branch.deadKind,
                    deadCallSiteNames = branch.deadCallSites.map {
                        "${it.callee.declaringClass.simpleName}.${it.callee.name}()"
                    }
                )
            )
        }

        return actions
    }

    /**
     * Execute planned deletion actions.
     *
     * @param actions the planned actions
     * @param dryRun if true, report what would happen without modifying files
     * @return list of action descriptions (for display)
     */
    fun execute(actions: List<DeletionAction>, dryRun: Boolean = false): List<String> {
        val report = mutableListOf<String>()
        val prefix = if (dryRun) "[DRY-RUN] " else ""

        // Group actions by file to batch edits
        val actionsByFile = actions.groupBy { it.sourceFile.absolutePath }

        for ((filePath, fileActions) in actionsByFile) {
            val file = File(filePath)
            if (!file.exists()) {
                report.add("${prefix}[SKIP] $filePath (file not found)")
                continue
            }

            // Check if any action deletes the whole file
            val deleteFileAction = fileActions.filterIsInstance<DeletionAction.DeleteFile>().firstOrNull()
            if (deleteFileAction != null) {
                report.add("$prefix${deleteFileAction.description}")
                if (!dryRun) {
                    file.delete()
                }
                continue
            }

            // Delegate to language-specific editor
            if (file.name.endsWith(".java")) {
                val results = editJavaFile(file, fileActions, dryRun, prefix)
                report.addAll(results)
            } else if (file.name.endsWith(".kt")) {
                val results = editKotlinFile(file, fileActions, dryRun, prefix)
                report.addAll(results)
            }
        }

        return report
    }

    // ========================================================================
    // Java source editing via IntelliJ Java PSI
    // ========================================================================

    private fun editJavaFile(
        file: File,
        actions: List<DeletionAction>,
        dryRun: Boolean,
        prefix: String
    ): List<String> {
        val report = mutableListOf<String>()

        // Process method deletions
        for (action in actions.filterIsInstance<DeletionAction.DeleteMethod>()) {
            val lineRange = javaEditor.methodLineRange(file, action.methodName, action.parameterTypes)
            if (lineRange != null) {
                val (startLine, endLine) = lineRange
                val desc = buildString {
                    append("${prefix}[DELETE] ${file.path}:$startLine-$endLine")
                    append(" ${action.methodName}(${action.parameterTypes.joinToString(", ")})")
                }
                report.add(desc)

                if (!dryRun) {
                    val deleted = javaEditor.deleteMethod(file, action.methodName, action.parameterTypes)
                    if (!deleted) {
                        report.add("${prefix}[ERROR] ${file.path} ${action.methodName}() (deletion failed)")
                    }
                }
            } else {
                report.add("${prefix}[SKIP] ${file.path} ${action.methodName}() (method not found in source)")
            }
        }

        // Process branch cleanups
        for (action in actions.filterIsInstance<DeletionAction.CleanupBranch>()) {
            val deadBranchIsTrue = action.deadBranchKind == ControlFlowKind.BRANCH_TRUE
            val desc = buildString {
                append("${prefix}[CLEANUP] ${file.path}")
                append(" in ${action.methodName}(): remove ")
                append(action.deadBranchKind.name.lowercase().replace("branch_", ""))
                append(" branch")
            }
            report.add(desc)

            if (!dryRun) {
                val cleaned = javaEditor.cleanupBranch(
                    file, action.methodName, deadBranchIsTrue, action.deadCallSiteNames
                )
                if (!cleaned) {
                    report.add("${prefix}[ERROR] ${file.path} branch in ${action.methodName}() (cleanup failed)")
                }
            }
        }

        return report
    }

    // ========================================================================
    // Kotlin source editing via kotlin-compiler-embeddable
    // ========================================================================

    private fun editKotlinFile(
        file: File,
        actions: List<DeletionAction>,
        dryRun: Boolean,
        prefix: String
    ): List<String> {
        val report = mutableListOf<String>()

        // Process method deletions
        for (action in actions.filterIsInstance<DeletionAction.DeleteMethod>()) {
            val lineRange = kotlinEditor.functionLineRange(file, action.methodName, action.parameterTypes)
            if (lineRange != null) {
                val (startLine, endLine) = lineRange
                val desc = buildString {
                    append("${prefix}[DELETE] ${file.path}:$startLine-$endLine")
                    append(" ${action.methodName}(${action.parameterTypes.joinToString(", ")})")
                }
                report.add(desc)

                if (!dryRun) {
                    val deleted = kotlinEditor.deleteFunction(file, action.methodName, action.parameterTypes)
                    if (!deleted) {
                        report.add("${prefix}[ERROR] ${file.path} ${action.methodName}() (deletion failed)")
                    }
                }
            } else {
                report.add("${prefix}[SKIP] ${file.path} ${action.methodName}() (function not found in source)")
            }
        }

        // Process branch cleanups
        for (action in actions.filterIsInstance<DeletionAction.CleanupBranch>()) {
            val deadBranchIsTrue = action.deadBranchKind == ControlFlowKind.BRANCH_TRUE
            val desc = buildString {
                append("${prefix}[CLEANUP] ${file.path}")
                append(" in ${action.methodName}(): remove ")
                append(action.deadBranchKind.name.lowercase().replace("branch_", ""))
                append(" branch")
            }
            report.add(desc)

            if (!dryRun) {
                val cleaned = kotlinEditor.cleanupBranch(
                    file, action.methodName, deadBranchIsTrue, action.deadCallSiteNames
                )
                if (!cleaned) {
                    report.add("${prefix}[ERROR] ${file.path} branch in ${action.methodName}() (cleanup failed)")
                }
            }
        }

        return report
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Determine if an entire class is dead (all non-constructor, non-synthetic methods are dead).
     */
    private fun isEntireClassDead(
        className: String,
        deadMethods: List<MethodDescriptor>,
        graph: Graph
    ): Boolean {
        val totalMethods = graph.methods(MethodPattern(declaringClass = className))
            .filter { it.name != "<init>" && it.name != "<clinit>" }
            .filter { !isSyntheticMethodName(it.name) }
            .count()

        if (totalMethods == 0) return false

        val deadCount = deadMethods
            .filter { it.name != "<init>" && it.name != "<clinit>" }
            .filter { !isSyntheticMethodName(it.name) }
            .count()

        return deadCount >= totalMethods
    }

    private fun isSyntheticMethodName(name: String): Boolean {
        return name.contains("$") ||
            name.startsWith("access$") ||
            name == "values" ||
            name == "valueOf" ||
            name.startsWith("lambda$")
    }
}
