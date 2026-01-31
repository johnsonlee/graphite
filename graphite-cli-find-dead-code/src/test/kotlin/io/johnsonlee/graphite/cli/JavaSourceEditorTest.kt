package io.johnsonlee.graphite.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaSourceEditorTest {

    private val editor = JavaSourceEditor()

    // ========================================================================
    // deleteMethod
    // ========================================================================

    @Test
    fun `deleteMethod removes simple method`() {
        val file = createTempJavaFile(
            "Simple.java",
            """
            package com.example;

            public class Simple {
                public void keep() {
                    System.out.println("keep");
                }

                public void dead() {
                    System.out.println("dead");
                }
            }
            """.trimIndent()
        )

        val deleted = editor.deleteMethod(file, "dead", emptyList())
        assertTrue(deleted)

        val result = file.readText()
        assertTrue(result.contains("keep"))
        assertFalse(result.contains("public void dead()"))
        assertFalse(result.contains("\"dead\""))
    }

    @Test
    fun `deleteMethod matches by parameter types`() {
        val file = createTempJavaFile(
            "Overloaded.java",
            """
            package com.example;

            public class Overloaded {
                public void process() {
                    System.out.println("no args");
                }

                public void process(String name) {
                    System.out.println(name);
                }

                public void process(int count) {
                    System.out.println(count);
                }
            }
            """.trimIndent()
        )

        val deleted = editor.deleteMethod(file, "process", listOf("String"))
        assertTrue(deleted)

        val result = file.readText()
        assertTrue(result.contains("public void process()"), "no-arg method should remain")
        assertTrue(result.contains("public void process(int count)"), "int-param method should remain")
        assertFalse(result.contains("public void process(String name)"), "String-param method should be removed")
    }

    @Test
    fun `deleteMethod removes method with Javadoc and annotations`() {
        val file = createTempJavaFile(
            "Annotated.java",
            """
            package com.example;

            public class Annotated {
                public void keep() {}

                /**
                 * This method is dead.
                 * @deprecated use something else
                 */
                @Deprecated
                @SuppressWarnings("unused")
                public void dead() {
                    System.out.println("dead");
                }
            }
            """.trimIndent()
        )

        val deleted = editor.deleteMethod(file, "dead", emptyList())
        assertTrue(deleted)

        val result = file.readText()
        assertTrue(result.contains("keep"))
        assertFalse(result.contains("dead"))
        assertFalse(result.contains("@Deprecated"))
        assertFalse(result.contains("@SuppressWarnings"))
    }

    @Test
    fun `deleteMethod finds method in inner class`() {
        val file = createTempJavaFile(
            "Outer.java",
            """
            package com.example;

            public class Outer {
                public void outerMethod() {}

                public static class Inner {
                    public void innerDead() {
                        System.out.println("inner dead");
                    }
                }
            }
            """.trimIndent()
        )

        val deleted = editor.deleteMethod(file, "innerDead", emptyList())
        assertTrue(deleted)

        val result = file.readText()
        assertTrue(result.contains("outerMethod"))
        assertFalse(result.contains("innerDead"))
    }

    @Test
    fun `deleteMethod returns false for nonexistent method`() {
        val file = createTempJavaFile(
            "NoMatch.java",
            """
            package com.example;

            public class NoMatch {
                public void existing() {}
            }
            """.trimIndent()
        )

        val deleted = editor.deleteMethod(file, "nonExistent", emptyList())
        assertFalse(deleted)
    }

    // ========================================================================
    // methodLineRange
    // ========================================================================

    @Test
    fun `methodLineRange returns correct lines`() {
        val file = createTempJavaFile(
            "Lines.java",
            """
            package com.example;

            public class Lines {
                public void first() {
                    System.out.println("first");
                }

                public void second() {
                    System.out.println("second");
                }
            }
            """.trimIndent()
        )

        val range = editor.methodLineRange(file, "second", emptyList())
        assertNotNull(range)
        val (start, end) = range
        assertTrue(start >= 7, "start=$start should be >= 7")
        assertTrue(end >= start, "end=$end should be >= start=$start")
    }

    @Test
    fun `methodLineRange returns null for missing method`() {
        val file = createTempJavaFile(
            "Missing.java",
            """
            package com.example;

            public class Missing {
                public void existing() {}
            }
            """.trimIndent()
        )

        val range = editor.methodLineRange(file, "missing", emptyList())
        assertNull(range)
    }

    // ========================================================================
    // cleanupBranch
    // ========================================================================

    @Test
    fun `cleanupBranch removes true branch and keeps else`() {
        val file = createTempJavaFile(
            "BranchTrue.java",
            """
            package com.example;

            public class BranchTrue {
                public void check(boolean flag) {
                    if (flag) {
                        deadCall();
                    } else {
                        aliveCall();
                    }
                }

                private void deadCall() {}
                private void aliveCall() {}
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
        val file = createTempJavaFile(
            "BranchFalse.java",
            """
            package com.example;

            public class BranchFalse {
                public void check(boolean flag) {
                    if (flag) {
                        aliveCall();
                    } else {
                        deadCall();
                    }
                }

                private void deadCall() {}
                private void aliveCall() {}
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
        val file = createTempJavaFile(
            "NoElse.java",
            """
            package com.example;

            public class NoElse {
                public void check(boolean flag) {
                    before();
                    if (flag) {
                        deadCall();
                    }
                    after();
                }

                private void before() {}
                private void deadCall() {}
                private void after() {}
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
    fun `cleanupBranch returns false for method without if`() {
        val file = createTempJavaFile(
            "NoIf.java",
            """
            package com.example;

            public class NoIf {
                public void simple() {
                    System.out.println("no if");
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
    fun `deleteMethod preserves other methods intact`() {
        val file = createTempJavaFile(
            "Preserve.java",
            """
            package com.example;

            public class Preserve {
                public String alpha() {
                    return "alpha";
                }

                public void dead() {
                    System.out.println("remove me");
                }

                public int omega() {
                    return 42;
                }
            }
            """.trimIndent()
        )

        editor.deleteMethod(file, "dead", emptyList())

        val result = file.readText()
        assertTrue(result.contains("public String alpha()"))
        assertTrue(result.contains("return \"alpha\""))
        assertTrue(result.contains("public int omega()"))
        assertTrue(result.contains("return 42"))
        assertFalse(result.contains("remove me"))
    }

    @Test
    fun `cleanupBranch returns false for nonexistent method`() {
        val file = createTempJavaFile(
            "NoMethod.java",
            """
            package com.example;

            public class NoMethod {
                public void other() {
                    System.out.println("other");
                }
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
        val file = createTempJavaFile(
            "FallbackIf.java",
            """
            package com.example;

            public class FallbackIf {
                public void check(boolean flag) {
                    if (flag) {
                        deadCall();
                    } else {
                        aliveCall();
                    }
                }

                private void deadCall() {}
                private void aliveCall() {}
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
    fun `deleteMethod with fully qualified parameter type`() {
        val file = createTempJavaFile(
            "FqnParam.java",
            """
            package com.example;

            public class FqnParam {
                public void process(String name) {
                    System.out.println(name);
                }

                public void keep() {}
            }
            """.trimIndent()
        )

        val deleted = editor.deleteMethod(file, "process", listOf("String"))
        assertTrue(deleted)

        val result = file.readText()
        assertFalse(result.contains("public void process"))
        assertTrue(result.contains("public void keep()"))
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun createTempJavaFile(name: String, content: String): File {
        val file = File.createTempFile(name.removeSuffix(".java"), ".java")
        file.deleteOnExit()
        file.writeText(content)
        return file
    }
}
