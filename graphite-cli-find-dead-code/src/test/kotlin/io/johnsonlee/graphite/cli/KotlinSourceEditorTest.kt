package io.johnsonlee.graphite.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinSourceEditorTest {

    private val editor = KotlinSourceEditor()

    // ========================================================================
    // deleteFunction
    // ========================================================================

    @Test
    fun `deleteFunction removes simple function`() {
        val file = createTempKtFile(
            "Simple.kt",
            """
            package com.example

            fun keep() {
                println("keep")
            }

            fun dead() {
                println("dead")
            }
            """.trimIndent()
        )

        val deleted = editor.deleteFunction(file, "dead", emptyList())
        assertTrue(deleted)

        val result = file.readText()
        assertTrue(result.contains("fun keep()"))
        assertFalse(result.contains("fun dead()"))
        assertFalse(result.contains("\"dead\""))
    }

    @Test
    fun `deleteFunction matches by parameter types`() {
        val file = createTempKtFile(
            "Overloaded.kt",
            """
            package com.example

            fun process() {
                println("no args")
            }

            fun process(name: String) {
                println(name)
            }

            fun process(count: Int) {
                println(count)
            }
            """.trimIndent()
        )

        val deleted = editor.deleteFunction(file, "process", listOf("String"))
        assertTrue(deleted)

        val result = file.readText()
        assertTrue(result.contains("fun process()"), "no-arg function should remain")
        assertTrue(result.contains("fun process(count: Int)"), "Int-param function should remain")
        assertFalse(result.contains("fun process(name: String)"), "String-param function should be removed")
    }

    @Test
    fun `deleteFunction removes function with KDoc and annotations`() {
        val file = createTempKtFile(
            "Annotated.kt",
            """
            package com.example

            class Annotated {
                fun keep() {}

                /**
                 * This function is dead.
                 */
                @Deprecated("use something else")
                @Suppress("unused")
                fun dead() {
                    println("dead")
                }
            }
            """.trimIndent()
        )

        val deleted = editor.deleteFunction(file, "dead", emptyList())
        assertTrue(deleted)

        val result = file.readText()
        assertTrue(result.contains("keep"))
        assertFalse(result.contains("fun dead()"))
        assertFalse(result.contains("@Deprecated"))
    }

    @Test
    fun `deleteFunction removes extension function`() {
        val file = createTempKtFile(
            "Extension.kt",
            """
            package com.example

            fun String.keep(): String = this.uppercase()

            fun String.dead(): String = this.lowercase()
            """.trimIndent()
        )

        val deleted = editor.deleteFunction(file, "dead", emptyList())
        assertTrue(deleted)

        val result = file.readText()
        assertTrue(result.contains("fun String.keep()"))
        assertFalse(result.contains("fun String.dead()"))
    }

    @Test
    fun `deleteFunction removes expression body function`() {
        val file = createTempKtFile(
            "ExprBody.kt",
            """
            package com.example

            fun alive() = 42

            fun dead() = "remove me"
            """.trimIndent()
        )

        val deleted = editor.deleteFunction(file, "dead", emptyList())
        assertTrue(deleted)

        val result = file.readText()
        assertTrue(result.contains("fun alive() = 42"))
        assertFalse(result.contains("fun dead()"))
        assertFalse(result.contains("remove me"))
    }

    @Test
    fun `deleteFunction finds function in class`() {
        val file = createTempKtFile(
            "InClass.kt",
            """
            package com.example

            class MyClass {
                fun keep() {}

                fun dead() {
                    println("dead")
                }
            }
            """.trimIndent()
        )

        val deleted = editor.deleteFunction(file, "dead", emptyList())
        assertTrue(deleted)

        val result = file.readText()
        assertTrue(result.contains("fun keep()"))
        assertFalse(result.contains("fun dead()"))
    }

    @Test
    fun `deleteFunction finds function in companion object`() {
        val file = createTempKtFile(
            "Companion.kt",
            """
            package com.example

            class MyClass {
                companion object {
                    fun create(): MyClass = MyClass()

                    fun deadFactory(): MyClass = MyClass()
                }
            }
            """.trimIndent()
        )

        val deleted = editor.deleteFunction(file, "deadFactory", emptyList())
        assertTrue(deleted)

        val result = file.readText()
        assertTrue(result.contains("fun create()"))
        assertFalse(result.contains("deadFactory"))
    }

    @Test
    fun `deleteFunction returns false for nonexistent function`() {
        val file = createTempKtFile(
            "NoMatch.kt",
            """
            package com.example

            fun existing() {}
            """.trimIndent()
        )

        val deleted = editor.deleteFunction(file, "nonExistent", emptyList())
        assertFalse(deleted)
    }

    // ========================================================================
    // functionLineRange
    // ========================================================================

    @Test
    fun `functionLineRange returns correct lines`() {
        val file = createTempKtFile(
            "Lines.kt",
            """
            package com.example

            fun first() {
                println("first")
            }

            fun second() {
                println("second")
            }
            """.trimIndent()
        )

        val range = editor.functionLineRange(file, "second", emptyList())
        assertNotNull(range)
        val (start, end) = range
        assertTrue(start >= 6, "start=$start should be >= 6")
        assertTrue(end >= start, "end=$end should be >= start=$start")
    }

    @Test
    fun `functionLineRange returns null for missing function`() {
        val file = createTempKtFile(
            "Missing.kt",
            """
            package com.example

            fun existing() {}
            """.trimIndent()
        )

        val range = editor.functionLineRange(file, "missing", emptyList())
        assertNull(range)
    }

    // ========================================================================
    // cleanupBranch
    // ========================================================================

    @Test
    fun `cleanupBranch removes true branch and keeps else`() {
        val file = createTempKtFile(
            "BranchTrue.kt",
            """
            package com.example

            class BranchTrue {
                fun check(flag: Boolean) {
                    if (flag) {
                        deadCall()
                    } else {
                        aliveCall()
                    }
                }

                private fun deadCall() {}
                private fun aliveCall() {}
            }
            """.trimIndent()
        )

        val cleaned = editor.cleanupBranch(
            file, "check",
            deadBranchIsTrue = true,
            deadCallSiteNames = listOf("BranchTrue.deadCall()")
        )
        assertTrue(cleaned)

        val result = file.readText()
        assertTrue(result.contains("aliveCall"))
        assertFalse(result.contains("if (flag)"))
    }

    @Test
    fun `cleanupBranch removes false branch and keeps then`() {
        val file = createTempKtFile(
            "BranchFalse.kt",
            """
            package com.example

            class BranchFalse {
                fun check(flag: Boolean) {
                    if (flag) {
                        aliveCall()
                    } else {
                        deadCall()
                    }
                }

                private fun deadCall() {}
                private fun aliveCall() {}
            }
            """.trimIndent()
        )

        val cleaned = editor.cleanupBranch(
            file, "check",
            deadBranchIsTrue = false,
            deadCallSiteNames = listOf("BranchFalse.deadCall()")
        )
        assertTrue(cleaned)

        val result = file.readText()
        assertTrue(result.contains("aliveCall"))
        assertFalse(result.contains("if (flag)"))
    }

    @Test
    fun `cleanupBranch removes entire if when no alive branch`() {
        val file = createTempKtFile(
            "NoElse.kt",
            """
            package com.example

            class NoElse {
                fun check(flag: Boolean) {
                    before()
                    if (flag) {
                        deadCall()
                    }
                    after()
                }

                private fun before() {}
                private fun deadCall() {}
                private fun after() {}
            }
            """.trimIndent()
        )

        val cleaned = editor.cleanupBranch(
            file, "check",
            deadBranchIsTrue = true,
            deadCallSiteNames = listOf("NoElse.deadCall()")
        )
        assertTrue(cleaned)

        val result = file.readText()
        assertTrue(result.contains("before"))
        assertTrue(result.contains("after"))
        assertFalse(result.contains("if (flag)"))
        // Note: deadCall() method declaration still exists in the class;
        // cleanupBranch only removes the if-block, not the method definition
    }

    @Test
    fun `cleanupBranch returns false for function without if`() {
        val file = createTempKtFile(
            "NoIf.kt",
            """
            package com.example

            class NoIf {
                fun simple() {
                    println("no if")
                }
            }
            """.trimIndent()
        )

        val cleaned = editor.cleanupBranch(
            file, "simple",
            deadBranchIsTrue = true,
            deadCallSiteNames = listOf("Foo.bar()")
        )
        assertFalse(cleaned)
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Test
    fun `deleteFunction preserves other functions intact`() {
        val file = createTempKtFile(
            "Preserve.kt",
            """
            package com.example

            fun alpha(): String {
                return "alpha"
            }

            fun dead() {
                println("remove me")
            }

            fun omega(): Int {
                return 42
            }
            """.trimIndent()
        )

        editor.deleteFunction(file, "dead", emptyList())

        val result = file.readText()
        assertTrue(result.contains("fun alpha()"))
        assertTrue(result.contains("return \"alpha\""))
        assertTrue(result.contains("fun omega()"))
        assertTrue(result.contains("return 42"))
        assertFalse(result.contains("remove me"))
    }

    @Test
    fun `deleteFunction handles suspend and inline modifiers`() {
        val file = createTempKtFile(
            "Modifiers.kt",
            """
            package com.example

            suspend fun keepSuspend() {}

            inline fun deadInline() {
                println("dead")
            }
            """.trimIndent()
        )

        val deleted = editor.deleteFunction(file, "deadInline", emptyList())
        assertTrue(deleted)

        val result = file.readText()
        assertTrue(result.contains("suspend fun keepSuspend()"))
        assertFalse(result.contains("deadInline"))
    }

    @Test
    fun `cleanupBranch returns false for nonexistent function`() {
        val file = createTempKtFile(
            "NoFunc.kt",
            """
            package com.example

            fun other() {
                println("other")
            }
            """.trimIndent()
        )

        val cleaned = editor.cleanupBranch(
            file, "nonExistent",
            deadBranchIsTrue = true,
            deadCallSiteNames = listOf("Foo.bar()")
        )
        assertFalse(cleaned)
    }

    @Test
    fun `cleanupBranch with empty callsite names falls back to first if`() {
        val file = createTempKtFile(
            "FallbackIf.kt",
            """
            package com.example

            class FallbackIf {
                fun check(flag: Boolean) {
                    if (flag) {
                        deadCall()
                    } else {
                        aliveCall()
                    }
                }

                private fun deadCall() {}
                private fun aliveCall() {}
            }
            """.trimIndent()
        )

        val cleaned = editor.cleanupBranch(
            file, "check",
            deadBranchIsTrue = true,
            deadCallSiteNames = emptyList()
        )
        assertTrue(cleaned)

        val result = file.readText()
        assertTrue(result.contains("aliveCall"))
        assertFalse(result.contains("if (flag)"))
    }

    @Test
    fun `deleteFunction handles generic parameter types`() {
        val file = createTempKtFile(
            "Generics.kt",
            """
            package com.example

            fun process(items: List<String>) {
                println(items)
            }

            fun keep() {}
            """.trimIndent()
        )

        val deleted = editor.deleteFunction(file, "process", listOf("List"))
        assertTrue(deleted)

        val result = file.readText()
        assertFalse(result.contains("fun process"))
        assertTrue(result.contains("fun keep()"))
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun createTempKtFile(name: String, content: String): File {
        val file = File.createTempFile(name.removeSuffix(".kt"), ".kt")
        file.deleteOnExit()
        file.writeText(content)
        return file
    }
}
