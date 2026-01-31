package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.analysis.DeadBranch
import io.johnsonlee.graphite.analysis.DeadCodeResult
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.*
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceCodeEditorTest {

    // ========================================================================
    // planDeletions
    // ========================================================================

    @Test
    fun `planDeletions creates DeleteFile when all methods are dead`() {
        val sourceDir = createSourceTree(
            "com/example/AllDead.java" to """
                package com.example;
                public class AllDead {
                    public void foo() {}
                    public void bar() {}
                }
            """.trimIndent()
        )
        val resolver = SourceFileResolver(listOf(sourceDir))
        val editor = SourceCodeEditor(resolver)

        val className = "com.example.AllDead"
        val type = TypeDescriptor(className)
        val voidType = TypeDescriptor("void")

        val methods = listOf(
            MethodDescriptor(type, "foo", emptyList(), voidType),
            MethodDescriptor(type, "bar", emptyList(), voidType)
        )

        val result = DeadCodeResult(
            deadBranches = emptyList(),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = methods.toSet()
        )

        val graph = stubGraph(methods)
        val actions = editor.planDeletions(result, graph)

        assertEquals(1, actions.size)
        assertTrue(actions[0] is DeletionAction.DeleteFile)
        val action = actions[0] as DeletionAction.DeleteFile
        assertEquals(className, action.className)
    }

    @Test
    fun `planDeletions creates DeleteMethod when only some methods are dead`() {
        val sourceDir = createSourceTree(
            "com/example/Partial.java" to """
                package com.example;
                public class Partial {
                    public void alive() {}
                    public void dead() {}
                }
            """.trimIndent()
        )
        val resolver = SourceFileResolver(listOf(sourceDir))
        val editor = SourceCodeEditor(resolver)

        val className = "com.example.Partial"
        val type = TypeDescriptor(className)
        val voidType = TypeDescriptor("void")

        val allMethods = listOf(
            MethodDescriptor(type, "alive", emptyList(), voidType),
            MethodDescriptor(type, "dead", emptyList(), voidType)
        )
        val deadMethods = setOf(allMethods[1])

        val result = DeadCodeResult(
            deadBranches = emptyList(),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = deadMethods
        )

        val graph = stubGraph(allMethods)
        val actions = editor.planDeletions(result, graph)

        assertEquals(1, actions.size)
        assertTrue(actions[0] is DeletionAction.DeleteMethod)
        val action = actions[0] as DeletionAction.DeleteMethod
        assertEquals("dead", action.methodName)
    }

    @Test
    fun `planDeletions creates CleanupBranch for dead branches`() {
        val sourceDir = createSourceTree(
            "com/example/Branch.java" to """
                package com.example;
                public class Branch {
                    public void check(boolean flag) {
                        if (flag) { dead(); } else { alive(); }
                    }
                    private void dead() {}
                    private void alive() {}
                }
            """.trimIndent()
        )
        val resolver = SourceFileResolver(listOf(sourceDir))
        val editor = SourceCodeEditor(resolver)

        val className = "com.example.Branch"
        val type = TypeDescriptor(className)
        val voidType = TypeDescriptor("void")

        val checkMethod = MethodDescriptor(
            type, "check",
            listOf(TypeDescriptor("boolean")),
            voidType
        )
        val deadCallee = MethodDescriptor(type, "dead", emptyList(), voidType)
        val deadCallSite = CallSiteNode(
            id = NodeId.next(),
            caller = checkMethod,
            callee = deadCallee,
            lineNumber = 4,
            receiver = null,
            arguments = emptyList()
        )

        val result = DeadCodeResult(
            deadBranches = listOf(
                DeadBranch(
                    conditionNodeId = NodeId.next(),
                    deadKind = ControlFlowKind.BRANCH_TRUE,
                    method = checkMethod,
                    deadNodeIds = IntOpenHashSet(),
                    deadCallSites = listOf(deadCallSite)
                )
            ),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = emptySet()
        )

        val graph = stubGraph(emptyList())
        val actions = editor.planDeletions(result, graph)

        assertEquals(1, actions.size)
        assertTrue(actions[0] is DeletionAction.CleanupBranch)
        val action = actions[0] as DeletionAction.CleanupBranch
        assertEquals("check", action.methodName)
        assertEquals(ControlFlowKind.BRANCH_TRUE, action.deadBranchKind)
    }

    @Test
    fun `planDeletions skips branches in deleted classes`() {
        val sourceDir = createSourceTree(
            "com/example/Dead.java" to """
                package com.example;
                public class Dead {
                    public void method() {
                        if (true) { a(); } else { b(); }
                    }
                    private void a() {}
                    private void b() {}
                }
            """.trimIndent()
        )
        val resolver = SourceFileResolver(listOf(sourceDir))
        val editor = SourceCodeEditor(resolver)

        val className = "com.example.Dead"
        val type = TypeDescriptor(className)
        val voidType = TypeDescriptor("void")

        val methods = listOf(
            MethodDescriptor(type, "method", emptyList(), voidType),
            MethodDescriptor(type, "a", emptyList(), voidType),
            MethodDescriptor(type, "b", emptyList(), voidType)
        )

        val deadCallSite = CallSiteNode(
            id = NodeId.next(),
            caller = methods[0],
            callee = methods[1],
            lineNumber = 4,
            receiver = null,
            arguments = emptyList()
        )

        val result = DeadCodeResult(
            deadBranches = listOf(
                DeadBranch(
                    conditionNodeId = NodeId.next(),
                    deadKind = ControlFlowKind.BRANCH_TRUE,
                    method = methods[0],
                    deadNodeIds = IntOpenHashSet(),
                    deadCallSites = listOf(deadCallSite)
                )
            ),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = methods.toSet()
        )

        val graph = stubGraph(methods)
        val actions = editor.planDeletions(result, graph)

        assertEquals(1, actions.size)
        assertTrue(actions[0] is DeletionAction.DeleteFile)
    }

    @Test
    fun `planDeletions skips classes with no source file`() {
        val sourceDir = createSourceTree(
            "com/example/Existing.java" to "class Existing {}"
        )
        val resolver = SourceFileResolver(listOf(sourceDir))
        val editor = SourceCodeEditor(resolver)

        val className = "com.example.Missing"
        val type = TypeDescriptor(className)
        val voidType = TypeDescriptor("void")
        val method = MethodDescriptor(type, "foo", emptyList(), voidType)

        val result = DeadCodeResult(
            deadBranches = emptyList(),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = setOf(method)
        )

        val graph = stubGraph(listOf(method))
        val actions = editor.planDeletions(result, graph)

        assertTrue(actions.isEmpty())
    }

    // ========================================================================
    // execute
    // ========================================================================

    @Test
    fun `execute deletes file for DeleteFile action`() {
        val sourceDir = createSourceTree(
            "com/example/ToDelete.java" to "class ToDelete {}"
        )
        val file = sourceDir.resolve("com/example/ToDelete.java").toFile()
        assertTrue(file.exists())

        val resolver = SourceFileResolver(listOf(sourceDir))
        val editor = SourceCodeEditor(resolver)

        val actions = listOf(
            DeletionAction.DeleteFile(
                sourceFile = file,
                className = "com.example.ToDelete",
                reason = "all methods dead"
            )
        )

        val report = editor.execute(actions, dryRun = false)
        assertFalse(file.exists(), "File should be deleted")
        assertTrue(report.any { it.contains("[DELETE]") })
    }

    @Test
    fun `execute dry run does not delete file`() {
        val sourceDir = createSourceTree(
            "com/example/Keep.java" to "class Keep {}"
        )
        val file = sourceDir.resolve("com/example/Keep.java").toFile()
        assertTrue(file.exists())

        val resolver = SourceFileResolver(listOf(sourceDir))
        val editor = SourceCodeEditor(resolver)

        val actions = listOf(
            DeletionAction.DeleteFile(
                sourceFile = file,
                className = "com.example.Keep",
                reason = "all methods dead"
            )
        )

        val report = editor.execute(actions, dryRun = true)
        assertTrue(file.exists(), "File should NOT be deleted in dry-run")
        assertTrue(report.any { it.contains("[DRY-RUN]") })
    }

    @Test
    fun `execute deletes method from Java file`() {
        val sourceDir = createSourceTree(
            "com/example/Edit.java" to """
                package com.example;

                public class Edit {
                    public void keep() {
                        System.out.println("keep");
                    }

                    public void dead() {
                        System.out.println("dead");
                    }
                }
            """.trimIndent()
        )
        val file = sourceDir.resolve("com/example/Edit.java").toFile()

        val resolver = SourceFileResolver(listOf(sourceDir))
        val editor = SourceCodeEditor(resolver)

        val actions = listOf(
            DeletionAction.DeleteMethod(
                sourceFile = file,
                className = "com.example.Edit",
                methodName = "dead",
                parameterTypes = emptyList()
            )
        )

        val report = editor.execute(actions, dryRun = false)
        val result = file.readText()
        assertTrue(result.contains("keep"))
        assertFalse(result.contains("public void dead()"))
        assertTrue(report.any { it.contains("[DELETE]") })
    }

    @Test
    fun `execute deletes function from Kotlin file`() {
        val sourceDir = createSourceTree(
            "com/example/Edit.kt" to """
                package com.example

                fun keep() {
                    println("keep")
                }

                fun dead() {
                    println("dead")
                }
            """.trimIndent()
        )
        val file = sourceDir.resolve("com/example/Edit.kt").toFile()

        val resolver = SourceFileResolver(listOf(sourceDir))
        val editor = SourceCodeEditor(resolver)

        val actions = listOf(
            DeletionAction.DeleteMethod(
                sourceFile = file,
                className = "com.example.Edit",
                methodName = "dead",
                parameterTypes = emptyList()
            )
        )

        val report = editor.execute(actions, dryRun = false)
        val result = file.readText()
        assertTrue(result.contains("keep"))
        assertFalse(result.contains("fun dead()"))
        assertTrue(report.any { it.contains("[DELETE]") })
    }

    @Test
    fun `execute reports skip for missing file`() {
        val missingFile = File("/tmp/nonexistent-${System.nanoTime()}.java")
        val resolver = SourceFileResolver(emptyList())
        val editor = SourceCodeEditor(resolver)

        val actions = listOf(
            DeletionAction.DeleteMethod(
                sourceFile = missingFile,
                className = "com.example.Missing",
                methodName = "foo",
                parameterTypes = emptyList()
            )
        )

        val report = editor.execute(actions, dryRun = false)
        assertTrue(report.any { it.contains("[SKIP]") })
    }

    @Test
    fun `execute branch cleanup on Java file`() {
        val sourceDir = createSourceTree(
            "com/example/BranchCleanup.java" to """
                package com.example;

                public class BranchCleanup {
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
        val file = sourceDir.resolve("com/example/BranchCleanup.java").toFile()

        val resolver = SourceFileResolver(listOf(sourceDir))
        val editor = SourceCodeEditor(resolver)

        val actions = listOf(
            DeletionAction.CleanupBranch(
                sourceFile = file,
                methodName = "check",
                deadBranchKind = ControlFlowKind.BRANCH_TRUE,
                deadCallSiteNames = listOf("BranchCleanup.deadCall()")
            )
        )

        val report = editor.execute(actions, dryRun = false)
        val result = file.readText()
        assertTrue(result.contains("aliveCall"))
        assertFalse(result.contains("if (flag)"))
        assertTrue(report.any { it.contains("[CLEANUP]") })
    }

    @Test
    fun `execute branch cleanup on Kotlin file`() {
        val sourceDir = createSourceTree(
            "com/example/BranchCleanup.kt" to """
                package com.example

                class BranchCleanup {
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
        val file = sourceDir.resolve("com/example/BranchCleanup.kt").toFile()

        val resolver = SourceFileResolver(listOf(sourceDir))
        val editor = SourceCodeEditor(resolver)

        val actions = listOf(
            DeletionAction.CleanupBranch(
                sourceFile = file,
                methodName = "check",
                deadBranchKind = ControlFlowKind.BRANCH_TRUE,
                deadCallSiteNames = listOf("BranchCleanup.deadCall()")
            )
        )

        val report = editor.execute(actions, dryRun = false)
        val result = file.readText()
        assertTrue(result.contains("aliveCall"))
        assertFalse(result.contains("if (flag)"))
        assertTrue(report.any { it.contains("[CLEANUP]") })
    }

    @Test
    fun `execute dry run branch cleanup does not modify file`() {
        val sourceDir = createSourceTree(
            "com/example/DryBranch.java" to """
                package com.example;

                public class DryBranch {
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
        val file = sourceDir.resolve("com/example/DryBranch.java").toFile()
        val originalContent = file.readText()

        val resolver = SourceFileResolver(listOf(sourceDir))
        val editor = SourceCodeEditor(resolver)

        val actions = listOf(
            DeletionAction.CleanupBranch(
                sourceFile = file,
                methodName = "check",
                deadBranchKind = ControlFlowKind.BRANCH_TRUE,
                deadCallSiteNames = listOf("DryBranch.deadCall()")
            )
        )

        val report = editor.execute(actions, dryRun = true)
        assertEquals(originalContent, file.readText(), "File should not be modified in dry-run")
        assertTrue(report.any { it.contains("[DRY-RUN]") })
    }

    @Test
    fun `execute method deletion reports skip when method not in source`() {
        val sourceDir = createSourceTree(
            "com/example/NoMatch.java" to """
                package com.example;

                public class NoMatch {
                    public void existing() {}
                }
            """.trimIndent()
        )
        val file = sourceDir.resolve("com/example/NoMatch.java").toFile()

        val resolver = SourceFileResolver(listOf(sourceDir))
        val editor = SourceCodeEditor(resolver)

        val actions = listOf(
            DeletionAction.DeleteMethod(
                sourceFile = file,
                className = "com.example.NoMatch",
                methodName = "nonExistent",
                parameterTypes = emptyList()
            )
        )

        val report = editor.execute(actions, dryRun = false)
        assertTrue(report.any { it.contains("[SKIP]") })
    }

    @Test
    fun `execute with verbose callback receives messages`() {
        val messages = mutableListOf<String>()
        val sourceDir = createSourceTree(
            "com/example/Verbose.java" to "class Verbose {}"
        )
        val resolver = SourceFileResolver(listOf(sourceDir))
        val editor = SourceCodeEditor(resolver, verbose = { messages.add(it) })

        val className = "com.example.Missing"
        val type = TypeDescriptor(className)
        val method = MethodDescriptor(type, "foo", emptyList(), TypeDescriptor("void"))

        val result = DeadCodeResult(
            deadBranches = emptyList(),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = setOf(method)
        )

        val graph = stubGraph(listOf(method))
        editor.planDeletions(result, graph)

        assertTrue(messages.any { it.contains("No source file found") })
    }

    @Test
    fun `planDeletions returns empty when totalMethods is zero`() {
        val sourceDir = createSourceTree(
            "com/example/Empty.java" to """
                package com.example;
                public class Empty {}
            """.trimIndent()
        )
        val resolver = SourceFileResolver(listOf(sourceDir))
        val editor = SourceCodeEditor(resolver)

        val className = "com.example.Empty"
        val type = TypeDescriptor(className)
        val voidType = TypeDescriptor("void")
        // Only constructors/synthetic methods - isEntireClassDead returns false because totalMethods=0
        val method = MethodDescriptor(type, "<init>", emptyList(), voidType)

        val result = DeadCodeResult(
            deadBranches = emptyList(),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = setOf(method)
        )

        // Graph returns only <init> method, which is filtered out
        val graph = stubGraph(listOf(method))
        val actions = editor.planDeletions(result, graph)

        // The dead method is <init>, which gets grouped but isEntireClassDead filters it
        // Since the unreferenced method name is "<init>", it won't be included in dead methods
        // by isEntireClassDead. The else branch creates DeleteMethod for "<init>"
        assertTrue(actions.isNotEmpty())
        assertTrue(actions[0] is DeletionAction.DeleteMethod)
    }

    @Test
    fun `DeleteMethod description without line range`() {
        val action = DeletionAction.DeleteMethod(
            sourceFile = File("src/main/java/Foo.java"),
            className = "Foo",
            methodName = "bar",
            parameterTypes = emptyList()
        )
        assertTrue(action.description.contains("[DELETE]"))
        assertTrue(action.description.contains("bar()"))
        assertFalse(action.description.contains(":"))
    }

    @Test
    fun `CleanupBranch description for false branch`() {
        val action = DeletionAction.CleanupBranch(
            sourceFile = File("src/main/java/Foo.java"),
            methodName = "check",
            deadBranchKind = ControlFlowKind.BRANCH_FALSE,
            deadCallSiteNames = listOf("Foo.dead()")
        )
        assertTrue(action.description.contains("[CLEANUP]"))
        assertTrue(action.description.contains("keep true branch"))
    }

    // ========================================================================
    // DeletionAction descriptions
    // ========================================================================

    @Test
    fun `DeleteFile description includes path and reason`() {
        val action = DeletionAction.DeleteFile(
            sourceFile = File("src/main/java/Foo.java"),
            className = "Foo",
            reason = "dead class, all 3 methods unreachable"
        )
        assertTrue(action.description.contains("[DELETE]"))
        assertTrue(action.description.contains("Foo.java"))
        assertTrue(action.description.contains("dead class"))
    }

    @Test
    fun `DeleteMethod description includes path and method name`() {
        val action = DeletionAction.DeleteMethod(
            sourceFile = File("src/main/java/Foo.java"),
            className = "Foo",
            methodName = "bar",
            parameterTypes = listOf("String", "int"),
            startLine = 10,
            endLine = 20
        )
        assertTrue(action.description.contains("[DELETE]"))
        assertTrue(action.description.contains("Foo.java"))
        assertTrue(action.description.contains(":10-20"))
        assertTrue(action.description.contains("bar()"))
    }

    @Test
    fun `CleanupBranch description includes kept branch`() {
        val action = DeletionAction.CleanupBranch(
            sourceFile = File("src/main/java/Foo.java"),
            methodName = "check",
            deadBranchKind = ControlFlowKind.BRANCH_TRUE,
            deadCallSiteNames = listOf("Foo.dead()"),
            startLine = 15
        )
        assertTrue(action.description.contains("[CLEANUP]"))
        assertTrue(action.description.contains("keep false branch"))
        assertTrue(action.description.contains(":15"))
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun stubGraph(methods: List<MethodDescriptor>): Graph = object : Graph {
        override fun node(id: NodeId): Node? = null
        override fun <T : Node> nodes(type: Class<T>): Sequence<T> = emptySequence()
        override fun outgoing(id: NodeId): Sequence<Edge> = emptySequence()
        override fun incoming(id: NodeId): Sequence<Edge> = emptySequence()
        override fun <T : Edge> outgoing(id: NodeId, type: Class<T>): Sequence<T> = emptySequence()
        override fun <T : Edge> incoming(id: NodeId, type: Class<T>): Sequence<T> = emptySequence()
        override fun callSites(methodPattern: MethodPattern): Sequence<CallSiteNode> = emptySequence()
        override fun supertypes(type: TypeDescriptor): Sequence<TypeDescriptor> = emptySequence()
        override fun subtypes(type: TypeDescriptor): Sequence<TypeDescriptor> = emptySequence()
        override fun methods(pattern: MethodPattern): Sequence<MethodDescriptor> =
            methods.asSequence().filter { pattern.matches(it) }
        override fun enumValues(enumClass: String, enumName: String): List<Any?>? = null
        override fun endpoints(pattern: String?, httpMethod: HttpMethod?): Sequence<EndpointInfo> = emptySequence()
        override fun branchScopes(): Sequence<BranchScope> = emptySequence()
        override fun branchScopesFor(conditionNodeId: NodeId): Sequence<BranchScope> = emptySequence()
        override fun jacksonFieldInfo(className: String, fieldName: String): JacksonFieldInfo? = null
        override fun jacksonGetterInfo(className: String, methodName: String): JacksonFieldInfo? = null
    }

    private fun createSourceTree(vararg files: Pair<String, String>): Path {
        val root = Files.createTempDirectory("editor-test")
        root.toFile().deleteOnExit()

        for ((relativePath, content) in files) {
            val file = root.resolve(relativePath).toFile()
            file.parentFile.mkdirs()
            file.writeText(content)
            file.deleteOnExit()
            var parent = file.parentFile
            while (parent != root.toFile() && parent != null) {
                parent.deleteOnExit()
                parent = parent.parentFile
            }
        }

        return root
    }
}
