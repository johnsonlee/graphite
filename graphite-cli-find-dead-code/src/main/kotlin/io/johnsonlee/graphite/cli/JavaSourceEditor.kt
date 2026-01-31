package io.johnsonlee.graphite.cli

import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage
import org.jetbrains.kotlin.com.intellij.psi.*
import java.io.File

/**
 * Java source file editor using IntelliJ Java PSI for parsing.
 *
 * Uses read-only PSI for precise element location, then applies modifications
 * via string operations to avoid the complexity of PSI write actions in headless mode.
 *
 * Shares the PSI environment from kotlin-compiler-embeddable (which bundles
 * shaded IntelliJ Java PSI classes) to avoid classpath conflicts.
 */
class JavaSourceEditor {

    companion object {
        private val psiFileFactory: PsiFileFactory by lazy {
            PsiFileFactory.getInstance(SharedPsiEnvironment.project)
        }
    }

    /**
     * Delete a method from a Java source file.
     *
     * Handles:
     * - Methods with Javadoc, annotations, and all modifiers
     * - Overloaded methods (matched by parameter types)
     * - Methods in inner classes
     *
     * @return true if the method was found and deleted
     */
    fun deleteMethod(file: File, methodName: String, paramTypes: List<String>): Boolean {
        val sourceText = file.readText()
        val psiFile = parseJavaFile(sourceText, file.name) ?: return false

        val method = findMethod(psiFile, methodName, paramTypes) ?: return false

        val startOffset = findDeletionStart(sourceText, method.textRange.startOffset)
        val endOffset = findDeletionEnd(sourceText, method.textRange.endOffset)

        val newText = sourceText.removeRange(startOffset, endOffset)
        file.writeText(newText)
        return true
    }

    /**
     * Get the line range of a method for reporting.
     *
     * @return pair of (startLine, endLine) or null if method not found
     */
    fun methodLineRange(file: File, methodName: String, paramTypes: List<String>): Pair<Int, Int>? {
        val sourceText = file.readText()
        val psiFile = parseJavaFile(sourceText, file.name) ?: return null

        val method = findMethod(psiFile, methodName, paramTypes) ?: return null

        val startLine = sourceText.substring(0, method.textRange.startOffset).count { it == '\n' } + 1
        val endLine = sourceText.substring(0, method.textRange.endOffset).count { it == '\n' } + 1
        return startLine to endLine
    }

    /**
     * Replace an if-statement with its alive branch in a Java source file.
     *
     * @param methodName the containing method name
     * @param deadBranchIsTrue if true, the then-branch is dead; if false, the else-branch is dead
     * @param deadCallSiteNames method names expected in the dead branch (for matching)
     * @return true if the if-statement was found and replaced
     */
    fun cleanupBranch(
        file: File,
        methodName: String,
        deadBranchIsTrue: Boolean,
        deadCallSiteNames: List<String>
    ): Boolean {
        val sourceText = file.readText()
        val psiFile = parseJavaFile(sourceText, file.name) ?: return false

        val method = findMethodByName(psiFile, methodName) ?: return false
        val ifStmt = findMatchingIfStatement(method, deadBranchIsTrue, deadCallSiteNames)
            ?: return false

        val aliveBranch = if (deadBranchIsTrue) {
            ifStmt.elseBranch
        } else {
            ifStmt.thenBranch
        }

        if (aliveBranch == null) {
            // No alive branch -- remove the entire if-statement
            val startOffset = findDeletionStart(sourceText, ifStmt.textRange.startOffset)
            val endOffset = findDeletionEnd(sourceText, ifStmt.textRange.endOffset)
            val newText = sourceText.removeRange(startOffset, endOffset)
            file.writeText(newText)
            return true
        }

        // Extract alive branch body text
        val aliveText = extractBranchBody(aliveBranch)
        val newText = sourceText.replaceRange(
            ifStmt.textRange.startOffset,
            ifStmt.textRange.endOffset,
            aliveText
        )
        file.writeText(newText)
        return true
    }

    // ========================================================================
    // PSI parsing
    // ========================================================================

    private fun parseJavaFile(text: String, fileName: String): PsiJavaFile? {
        return try {
            psiFileFactory.createFileFromText(
                fileName, JavaLanguage.INSTANCE, text
            ) as? PsiJavaFile
        } catch (e: Exception) {
            null
        }
    }

    // ========================================================================
    // Method finding
    // ========================================================================

    /**
     * Find a method by name and parameter types.
     * Searches all classes (including inner classes) in the file.
     */
    private fun findMethod(psiFile: PsiJavaFile, methodName: String, paramTypes: List<String>): PsiMethod? {
        for (psiClass in psiFile.classes) {
            val found = findMethodInClass(psiClass, methodName, paramTypes)
            if (found != null) return found
        }
        return null
    }

    private fun findMethodInClass(psiClass: PsiClass, methodName: String, paramTypes: List<String>): PsiMethod? {
        for (method in psiClass.methods) {
            if (method.name == methodName && matchesParameterTypes(method, paramTypes)) {
                return method
            }
        }
        // Recurse into inner classes
        for (innerClass in psiClass.innerClasses) {
            val found = findMethodInClass(innerClass, methodName, paramTypes)
            if (found != null) return found
        }
        return null
    }

    /**
     * Find a method by name only (for branch cleanup where param types aren't needed).
     */
    private fun findMethodByName(psiFile: PsiJavaFile, methodName: String): PsiMethod? {
        for (psiClass in psiFile.classes) {
            val found = findMethodByNameInClass(psiClass, methodName)
            if (found != null) return found
        }
        return null
    }

    private fun findMethodByNameInClass(psiClass: PsiClass, methodName: String): PsiMethod? {
        for (method in psiClass.methods) {
            if (method.name == methodName) return method
        }
        for (innerClass in psiClass.innerClasses) {
            val found = findMethodByNameInClass(innerClass, methodName)
            if (found != null) return found
        }
        return null
    }

    /**
     * Check if a method's parameter types match the expected types.
     */
    private fun matchesParameterTypes(method: PsiMethod, paramTypes: List<String>): Boolean {
        val params = method.parameterList.parameters
        if (params.size != paramTypes.size) return false
        if (paramTypes.isEmpty()) return true

        return params.zip(paramTypes).all { (param, expected) ->
            val typeName = param.type.presentableText
            typeName == expected || typeName.substringAfterLast('.') == expected
        }
    }

    // ========================================================================
    // If-statement finding
    // ========================================================================

    private fun findMatchingIfStatement(
        method: PsiMethod,
        deadBranchIsTrue: Boolean,
        deadCallSiteNames: List<String>
    ): PsiIfStatement? {
        val ifStatements = collectIfStatements(method)
        if (ifStatements.isEmpty()) return null

        // Try to match by dead call site names
        if (deadCallSiteNames.isNotEmpty()) {
            for (ifStmt in ifStatements) {
                val deadBranch = if (deadBranchIsTrue) ifStmt.thenBranch else ifStmt.elseBranch
                val deadText = deadBranch?.text ?: continue

                val matches = deadCallSiteNames.any { callSiteName ->
                    val name = callSiteName.substringAfter('.').removeSuffix("()")
                    deadText.contains(name)
                }
                if (matches) return ifStmt
            }
        }

        // Fallback: first if-statement
        return ifStatements.firstOrNull()
    }

    private fun collectIfStatements(element: PsiElement): List<PsiIfStatement> {
        val result = mutableListOf<PsiIfStatement>()
        element.accept(object : JavaRecursiveElementVisitor() {
            override fun visitIfStatement(statement: PsiIfStatement) {
                result.add(statement)
                super.visitIfStatement(statement)
            }
        })
        return result
    }

    // ========================================================================
    // Text range helpers
    // ========================================================================

    /**
     * Find the start offset for deletion, including preceding blank line.
     */
    private fun findDeletionStart(sourceText: String, elementStart: Int): Int {
        var pos = elementStart

        // Skip backward over whitespace on the same line
        while (pos > 0 && sourceText[pos - 1].let { it == ' ' || it == '\t' }) {
            pos--
        }

        // Include the preceding newline
        if (pos > 0 && sourceText[pos - 1] == '\n') {
            pos--
            if (pos > 0 && sourceText[pos - 1] == '\r') pos--
        }

        return pos
    }

    /**
     * Find the end offset for deletion, consuming trailing whitespace and newline.
     */
    private fun findDeletionEnd(sourceText: String, elementEnd: Int): Int {
        var pos = elementEnd

        // Skip trailing whitespace
        while (pos < sourceText.length && sourceText[pos].let { it == ' ' || it == '\t' }) {
            pos++
        }

        // Consume one trailing newline
        if (pos < sourceText.length && sourceText[pos] == '\r') pos++
        if (pos < sourceText.length && sourceText[pos] == '\n') pos++

        return pos
    }

    /**
     * Extract the body of a branch statement.
     * If the branch is a block `{ stmts }`, extract just the statements.
     */
    private fun extractBranchBody(branch: PsiStatement): String {
        if (branch is PsiBlockStatement) {
            val block = branch.codeBlock
            val statements = block.statements
            if (statements.isEmpty()) return ""
            return statements.joinToString("\n") { it.text }
        }
        return branch.text
    }
}
