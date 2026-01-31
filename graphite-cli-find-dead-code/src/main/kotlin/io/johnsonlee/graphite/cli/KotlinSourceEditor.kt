package io.johnsonlee.graphite.cli

import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.*
import java.io.File

/**
 * Kotlin source file editor using kotlin-compiler-embeddable for parsing.
 *
 * Uses read-only PSI for precise element location, then applies modifications
 * via string operations to avoid the complexity of PSI write actions in headless mode.
 */
class KotlinSourceEditor {

    companion object {
        private val psiFileFactory: PsiFileFactory by lazy {
            PsiFileFactory.getInstance(SharedPsiEnvironment.project)
        }
    }

    /**
     * Delete a named function from a Kotlin source file.
     *
     * Handles:
     * - Regular functions: `fun foo() { ... }`
     * - Expression body: `fun foo() = expr`
     * - Extension functions: `fun String.foo() { ... }`
     * - Modifier prefixes: `private`, `internal`, `suspend`, `inline`, `override`, etc.
     * - Annotations and KDoc before the function
     *
     * @return true if the function was found and deleted
     */
    fun deleteFunction(file: File, methodName: String, paramTypes: List<String>): Boolean {
        val sourceText = file.readText()
        val ktFile = parseKtFile(sourceText, file.name) ?: return false

        // Search all functions (including nested in classes, companion objects)
        val function = findFunction(ktFile, methodName, paramTypes) ?: return false

        // Compute the deletion range (including preceding whitespace, KDoc, annotations)
        val startOffset = findDeletionStart(sourceText, function.textRange.startOffset)
        val endOffset = findDeletionEnd(sourceText, function.textRange.endOffset)

        val newText = sourceText.removeRange(startOffset, endOffset)
        file.writeText(newText)
        return true
    }

    /**
     * Get the line range of a function for reporting.
     *
     * @return pair of (startLine, endLine) or null if function not found
     */
    fun functionLineRange(file: File, methodName: String, paramTypes: List<String>): Pair<Int, Int>? {
        val sourceText = file.readText()
        val ktFile = parseKtFile(sourceText, file.name) ?: return null

        val function = findFunction(ktFile, methodName, paramTypes) ?: return null

        val startLine = sourceText.substring(0, function.textRange.startOffset).count { it == '\n' } + 1
        val endLine = sourceText.substring(0, function.textRange.endOffset).count { it == '\n' } + 1
        return startLine to endLine
    }

    /**
     * Replace an if-expression with its alive branch in a Kotlin source file.
     *
     * @param methodName the containing function name
     * @param deadBranchIsTrue if true, the then-branch is dead; if false, the else-branch is dead
     * @param deadCallSiteNames method names expected in the dead branch (for matching)
     * @return true if the if-expression was found and replaced
     */
    fun cleanupBranch(
        file: File,
        methodName: String,
        deadBranchIsTrue: Boolean,
        deadCallSiteNames: List<String>
    ): Boolean {
        val sourceText = file.readText()
        val ktFile = parseKtFile(sourceText, file.name) ?: return false

        // Find the containing function
        val function = findFunctionByName(ktFile, methodName) ?: return false

        // Find matching if-expression
        val ifExpr = findMatchingIfExpression(function, deadBranchIsTrue, deadCallSiteNames)
            ?: return false

        // Determine which branch to keep
        val aliveBranch = if (deadBranchIsTrue) {
            ifExpr.`else`
        } else {
            ifExpr.then
        }

        if (aliveBranch == null) {
            // No alive branch (e.g., `if (cond) { dead }` with no else, and true is dead)
            // Remove the entire if-expression
            val startOffset = findDeletionStart(sourceText, ifExpr.textRange.startOffset)
            val endOffset = findDeletionEnd(sourceText, ifExpr.textRange.endOffset)
            val newText = sourceText.removeRange(startOffset, endOffset)
            file.writeText(newText)
            return true
        }

        // Extract the alive branch body text
        val aliveText = extractBranchBody(aliveBranch)

        // Replace the entire if-expression with the alive branch body
        val newText = sourceText.replaceRange(
            ifExpr.textRange.startOffset,
            ifExpr.textRange.endOffset,
            aliveText
        )
        file.writeText(newText)
        return true
    }

    // ========================================================================
    // PSI parsing
    // ========================================================================

    private fun parseKtFile(text: String, fileName: String): KtFile? {
        return try {
            psiFileFactory.createFileFromText(fileName, KotlinLanguage.INSTANCE, text) as? KtFile
        } catch (e: Exception) {
            null
        }
    }

    // ========================================================================
    // Function finding
    // ========================================================================

    /**
     * Find a function by name and parameter types.
     * Searches top-level declarations, class members, and companion objects.
     */
    private fun findFunction(ktFile: KtFile, methodName: String, paramTypes: List<String>): KtNamedFunction? {
        return collectAllFunctions(ktFile).firstOrNull { function ->
            function.name == methodName && matchesParameterTypes(function, paramTypes)
        }
    }

    /**
     * Find a function by name only (for branch cleanup where param types aren't needed).
     */
    private fun findFunctionByName(ktFile: KtFile, methodName: String): KtNamedFunction? {
        return collectAllFunctions(ktFile).firstOrNull { it.name == methodName }
    }

    /**
     * Collect all named functions from a KtFile, including nested in classes and companions.
     */
    private fun collectAllFunctions(ktFile: KtFile): List<KtNamedFunction> {
        val functions = mutableListOf<KtNamedFunction>()

        fun visit(declarations: List<KtDeclaration>) {
            for (decl in declarations) {
                when (decl) {
                    is KtNamedFunction -> functions.add(decl)
                    is KtClassOrObject -> {
                        visit(decl.declarations)
                        // Also check companion objects
                        decl.companionObjects.forEach { companion ->
                            visit(companion.declarations)
                        }
                    }
                }
            }
        }

        visit(ktFile.declarations)
        return functions
    }

    /**
     * Check if a function's parameter types match the expected types.
     */
    private fun matchesParameterTypes(function: KtNamedFunction, paramTypes: List<String>): Boolean {
        val params = function.valueParameters
        if (params.size != paramTypes.size) return false
        if (paramTypes.isEmpty()) return true

        return params.zip(paramTypes).all { (param, expected) ->
            val typeText = param.typeReference?.text ?: return@all false
            typeText == expected ||
                typeText.substringAfterLast('.') == expected ||
                typeText.substringBefore('<') == expected // Handle generic types
        }
    }

    // ========================================================================
    // If-expression finding
    // ========================================================================

    private fun findMatchingIfExpression(
        function: KtNamedFunction,
        deadBranchIsTrue: Boolean,
        deadCallSiteNames: List<String>
    ): KtIfExpression? {
        val ifExpressions = collectIfExpressions(function)
        if (ifExpressions.isEmpty()) return null

        // Try to match by dead call site names
        if (deadCallSiteNames.isNotEmpty()) {
            for (ifExpr in ifExpressions) {
                val deadBranch = if (deadBranchIsTrue) ifExpr.then else ifExpr.`else`
                val deadText = deadBranch?.text ?: continue

                val matches = deadCallSiteNames.any { callSiteName ->
                    val name = callSiteName.substringAfter('.').removeSuffix("()")
                    deadText.contains(name)
                }
                if (matches) return ifExpr
            }
        }

        // Fallback: first if-expression
        return ifExpressions.firstOrNull()
    }

    private fun collectIfExpressions(element: KtElement): List<KtIfExpression> {
        val result = mutableListOf<KtIfExpression>()
        element.accept(object : KtTreeVisitorVoid() {
            override fun visitIfExpression(expression: KtIfExpression) {
                result.add(expression)
                super.visitIfExpression(expression)
            }
        })
        return result
    }

    // ========================================================================
    // Text range helpers
    // ========================================================================

    /**
     * Find the start offset for deletion, including preceding KDoc, annotations, and blank lines.
     */
    private fun findDeletionStart(sourceText: String, elementStart: Int): Int {
        var pos = elementStart

        // Skip backward over whitespace (but stop at double newlines to preserve paragraph breaks)
        while (pos > 0 && sourceText[pos - 1].let { it == ' ' || it == '\t' }) {
            pos--
        }

        // If we're at a newline, include it
        if (pos > 0 && sourceText[pos - 1] == '\n') {
            pos--
            // Also skip \r
            if (pos > 0 && sourceText[pos - 1] == '\r') pos--
        }

        return pos
    }

    /**
     * Find the end offset for deletion, consuming trailing whitespace and a newline.
     */
    private fun findDeletionEnd(sourceText: String, elementEnd: Int): Int {
        var pos = elementEnd

        // Skip trailing whitespace on the same line
        while (pos < sourceText.length && sourceText[pos].let { it == ' ' || it == '\t' }) {
            pos++
        }

        // Consume one trailing newline
        if (pos < sourceText.length && sourceText[pos] == '\r') pos++
        if (pos < sourceText.length && sourceText[pos] == '\n') pos++

        return pos
    }

    /**
     * Extract the body text from a branch expression.
     * If the branch is a block expression `{ ... }`, extract the inner statements.
     */
    private fun extractBranchBody(branch: KtExpression): String {
        if (branch is KtBlockExpression) {
            // Extract statements from block, preserving indentation
            val statements = branch.statements
            if (statements.isEmpty()) return ""
            return statements.joinToString("\n") { it.text }
        }
        return branch.text
    }
}
