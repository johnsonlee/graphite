package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.input.ConstantFold
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.expr.JEqExpr
import sootup.core.jimple.common.expr.JCastExpr
import sootup.core.jimple.common.expr.JNeExpr
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JIfStmt
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.JReturnStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.model.Body
import sootup.core.model.SourceType
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation
import sootup.java.core.views.JavaView

/** The JDK's value equality, including floating-point wrapper semantics, after an explicit rule. */
class ConstantEqualsFoldTest {
    private val owner = "sample.constantequals.Shapes"
    private val temporaryRoots = mutableListOf<Path>()
    private val classes by lazy { compile() }

    private data class Box(
        val wrapper: String,
        val primitive: String,
        val literal: String,
        val value: Any,
        val different: Any,
        val constantLabel: String
    ) {
        fun ruleValue(value: Any): Any = mapOf(constantLabel to mapOf("value" to when (value) {
            is Byte -> value.toInt()
            is Short -> value.toInt()
            is Char -> value.code
            is Float -> value.toDouble()
            else -> value
        }))
    }

    private val boxes = listOf(
        Box("Boolean", "boolean", "true", true, false, "BooleanConstant"),
        Box("Byte", "byte", "(byte) 7", 7.toByte(), (-2).toByte(), "IntConstant"),
        Box("Short", "short", "(short) 300", 300.toShort(), (-1).toShort(), "IntConstant"),
        Box("Character", "char", "'a'", 'a', 'b', "IntConstant"),
        Box("Integer", "int", "1000", 1000, 1001, "IntConstant"),
        Box("Long", "long", "9007199254740993L", 9007199254740993L, 9007199254740992L, "LongConstant"),
        Box("Float", "float", "1.5f", 1.5f, -2.5f, "FloatConstant"),
        Box("Double", "double", "1.5d", 1.5, -2.5, "DoubleConstant")
    )

    @AfterTest
    fun deleteCompiledFixtures() {
        temporaryRoots.forEach { it.toFile().deleteRecursively() }
    }

    private fun compile(): Path {
        val root = Files.createTempDirectory("constant-equals-fold").also(temporaryRoots::add)
        val source = root.resolve("Shapes.java")
        val boxedMethods = boxes.joinToString("\n") { box ->
            val (wrapper, primitive, literal) = box
            """
                public static $wrapper source$wrapper = $literal;
                static $wrapper boxed$wrapper() { return source$wrapper; }
                static $primitive primitive$wrapper() { return source$wrapper; }
                public static int typed$wrapper() { return boxed$wrapper().equals($wrapper.valueOf($literal)) ? 1 : 2; }
                public static int viaBox$wrapper() { return $wrapper.valueOf(primitive$wrapper()).equals($wrapper.valueOf($literal)) ? 1 : 2; }
                public static int mismatch$wrapper() { return boxed$wrapper().equals("wrong type") ? 1 : 2; }
                public static int nullArg$wrapper() { return boxed$wrapper().equals(null) ? 1 : 2; }
            """.trimIndent()
        }
        Files.writeString(source, """
            package sample.constantequals;
            public class Shapes {
                $boxedMethods
                public static String sourceText = "A";
                public static Object sourceErased = Integer.valueOf(1000);
                static String text() { return sourceText; }
                static Object erased() { return sourceErased; }
                static boolean inScope() { return System.nanoTime() > 0; }
                static void work() { }
                static void other() { }
                static void tail() { }
                public static int stringResult() { return "A".equals(text()) ? 1 : 2; }
                public static int stringReceiver() { return text().equals("A") ? 1 : 2; }
                public static int stringMismatch() { return text().equals(Integer.valueOf(1)) ? 1 : 2; }
                public static int stringNullArgument() { return text().equals(null) ? 1 : 2; }
                public static int erasedInteger() { return ((Integer) erased()).equals(Integer.valueOf(1000)) ? 1 : 2; }
                public static int erasedObject() { return erased().equals(Integer.valueOf(1000)) ? 1 : 2; }
                public static int erasedString() { return ((String) erased()).equals("A") ? 1 : 2; }
                public static void incompatibleCast() {
                    try { Object value = erased(); if (((Integer) value).equals(Integer.valueOf(1000))) work(); }
                    catch (ClassCastException expected) { other(); }
                }
                public static int crossNumericTypes() { return boxedInteger().equals(Long.valueOf(1000)) ? 1 : 2; }
                public static int discardedResult() { text().equals("A"); return 7; }
                public static void scopeA() { if (inScope() && "A".equals(text())) work(); tail(); }
                public static void scopeB() { if (inScope() && "B".equals(text())) work(); tail(); }
                public static void neighboringHandler() {
                    try { text().equals("A"); other(); }
                    catch (RuntimeException expected) { work(); }
                }
                public static int objectsEqual() { return java.util.Objects.equals(text(), "A") ? 1 : 2; }
                public static int objectsReverse() { return java.util.Objects.equals("A", text()) ? 1 : 2; }
                public static int objectsNull() { return java.util.Objects.equals(text(), null) ? 1 : 2; }
                public static int objectsNullReceiver() { return java.util.Objects.equals(null, text()) ? 1 : 2; }
                public static int intrinsicsEqual() { return kotlin.jvm.internal.Intrinsics.areEqual((Object) text(), (Object) "A") ? 1 : 2; }
                public static int intrinsicsNull() { return kotlin.jvm.internal.Intrinsics.areEqual((Object) text(), (Object) null) ? 1 : 2; }
                public static int intrinsicsNullReceiver() { return kotlin.jvm.internal.Intrinsics.areEqual((Object) null, (Object) text()) ? 1 : 2; }
                public static int nestedObjects() { return java.util.Objects.equals(Boolean.valueOf("A".equals(text())), Boolean.TRUE) ? 1 : 2; }
                public static int nestedAliases() {
                    Object first = Boolean.valueOf("A".equals(text()));
                    Object second = first;
                    return ((Boolean) second).equals(Boolean.TRUE) ? 1 : 2;
                }
                public static int nestedIntrinsics() {
                    return kotlin.jvm.internal.Intrinsics.areEqual((Object) Boolean.valueOf("A".equals(text())), (Object) Boolean.TRUE) ? 1 : 2;
                }
                public static int doubleNaN() { return boxedDouble().equals(Double.valueOf(Double.NaN)) ? 1 : 2; }
                public static int floatNaN() { return boxedFloat().equals(Float.valueOf(Float.NaN)) ? 1 : 2; }
                public static int doublePositiveZero() { return boxedDouble().equals(Double.valueOf(0.0d)) ? 1 : 2; }
                public static int floatPositiveZero() { return boxedFloat().equals(Float.valueOf(0.0f)) ? 1 : 2; }
                public static int doubleObjectsNaN() { return java.util.Objects.equals(boxedDouble(), Double.valueOf(Double.NaN)) ? 1 : 2; }
                public static int doubleIntrinsicsNaN() { return kotlin.jvm.internal.Intrinsics.areEqual((Object) boxedDouble(), (Object) Double.valueOf(Double.NaN)) ? 1 : 2; }
                public static int intrinsicsFloatingOverload() { return kotlin.jvm.internal.Intrinsics.areEqual(boxedDouble(), Double.valueOf(Double.NaN)) ? 1 : 2; }
                public static class Custom {
                    public boolean equals(Object value) { work(); return true; }
                }
                public static class Overloaded {
                    public boolean equals(String value) { work(); return true; }
                }
                public static void unknownReceiver(Object value) { if (value.equals(text())) work(); else other(); }
                public static void unknownArgument(Object value) { if (text().equals(value)) work(); else other(); }
                public static void customReceiver(Custom value) { if (value.equals(text())) work(); else other(); }
                public static void overloaded(Overloaded value) { if (value.equals(text())) work(); else other(); }
                public static void unknownObjects(Custom value) { if (java.util.Objects.equals(value, text())) work(); else other(); }
                public static void unknownIntrinsics(Custom value) { if (kotlin.jvm.internal.Intrinsics.areEqual(value, text())) work(); else other(); }
                public static void nullReceiver() {
                    String value = null;
                    try { if (value.equals(text())) work(); }
                    catch (NullPointerException expected) { other(); }
                }
                public static void unrelated() { text(); if ("A".equals("B")) work(); else other(); }
                public static void untouched() { if ("A".equals("B")) work(); else other(); }
            }
        """.trimIndent())
        val classes = root.resolve("classes").also { Files.createDirectories(it) }
        val stdlib = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-classpath", stdlib.toString(), "-d", classes.toString(), source.toString()
        ))
        return classes
    }

    private fun rule(lookup: String, value: Any?): ConstantFold = ConstantFold.parse(mapOf(
        "match" to mapOf("CallSite" to mapOf("callee_class" to owner, "callee_name" to lookup)), "value" to value
    ))

    private fun bodies(vararg rules: ConstantFold): Map<String, Body> {
        val view = JavaView(PathBasedAnalysisInputLocation.create(
            classes, SourceType.Application, ConstantFolding(rules.toList()).bodyInterceptors(emptyList())
        ))
        return view.getClass(view.identifierFactory.getClassType(owner)).get().methods
            .filter { it.name != "<init>" && it.name != "<clinit>" }.associate { it.name to it.body }
    }

    private fun Stmt.callee(): String? = when (this) {
        is JAssignStmt -> invokeExpr.orElse(null)?.methodSignature?.name
        is JInvokeStmt -> invokeExpr.orElse(null)?.methodSignature?.name
        else -> null
    }

    private fun Body.calls(): List<String> = stmts.mapNotNull { it.callee() }.sorted()

    private fun assertFoldedResult(body: Body, expected: Int) {
        assertTrue(body.stmts.none { it is JIfStmt }, "resolved equality has no runtime branch: $body")
        assertEquals(listOf(IntConstant.getInstance(expected)), body.stmts.filterIsInstance<JReturnStmt>().map { it.op }, body.toString())
        assertTrue(body.calls().none { it == "equals" || it == "areEqual" }, body.toString())
    }

    /** Run the original bytecode with the same lookup value as the rule, independently of SootUp. */
    private fun runtimeResult(field: String, value: Any?, method: String): Int =
        URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader).use { loader ->
            val type = loader.loadClass(owner)
            type.getField(field).set(null, value)
            type.getMethod(method).invoke(null) as Int
        }

    private fun Body.reaches(start: Stmt, target: Stmt): Boolean {
        val pending = ArrayDeque<Stmt>()
        val visited = HashSet<Stmt>()
        pending.add(start)
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (next === target) return true
            if (visited.add(next)) pending.addAll(controlFlowGraph.successors(next))
        }
        return false
    }

    @Test
    fun `all primitive wrappers fold typed lookups and primitive boxing chains using JVM equality`() {
        for (box in boxes) {
            for (value in listOf(box.value, box.different)) {
                val bodies = bodies(
                    rule("boxed${box.wrapper}", box.ruleValue(value)), rule("primitive${box.wrapper}", box.ruleValue(value))
                )
                for (prefix in listOf("typed", "viaBox", "mismatch", "nullArg")) {
                    val method = prefix + box.wrapper
                    assertFoldedResult(bodies.getValue(method), runtimeResult("source${box.wrapper}", value, method))
                }
            }
        }
    }

    @Test
    fun `string equals handles either receiver direction null arguments and different known types`() {
        for (value in listOf("A", "B")) {
            val bodies = bodies(rule("text", value))
            for (method in listOf("stringResult", "stringReceiver", "stringMismatch", "stringNullArgument", "discardedResult")) {
                assertFoldedResult(bodies.getValue(method), runtimeResult("sourceText", value, method))
            }
        }
        assertFoldedResult(bodies(rule("text", null)).getValue("stringResult"), 2)
        assertFoldedResult(bodies(rule("boxedInteger", 1000)).getValue("crossNumericTypes"), 2)
    }

    @Test
    fun `erased boxed and string lookups retain their exact equality types through casts`() {
        for ((value, methods) in listOf(
            1000 to listOf("erasedInteger", "erasedObject"),
            1001 to listOf("erasedInteger", "erasedObject"),
            "A" to listOf("erasedString"),
            "B" to listOf("erasedString")
        )) {
            val bodies = bodies(rule("erased", value))
            for (method in methods) {
                assertFoldedResult(bodies.getValue(method), runtimeResult("sourceErased", value, method))
            }
        }
    }

    @Test
    fun `Objects and Kotlin object equality are null safe and use the same known values`() {
        val methods = listOf(
            "objectsEqual", "objectsReverse", "objectsNull", "objectsNullReceiver",
            "intrinsicsEqual", "intrinsicsNull", "intrinsicsNullReceiver"
        )
        for (value in listOf("A", "B", null)) {
            val bodies = bodies(rule("text", value))
            for (method in methods) {
                assertFoldedResult(bodies.getValue(method), runtimeResult("sourceText", value, method))
            }
        }
    }

    @Test
    fun `folded equality results keep their rule origin through nested helpers aliases casts and boxing`() {
        for (value in listOf("A", "B")) {
            val bodies = bodies(rule("text", value))
            for (method in listOf("nestedObjects", "nestedAliases", "nestedIntrinsics")) {
                assertFoldedResult(bodies.getValue(method), runtimeResult("sourceText", value, method))
            }
        }
    }

    @Test
    fun `floating wrapper equality canonicalizes NaNs and distinguishes signed zero`() {
        for ((wrapper, nan, negativeZero) in listOf(
            Triple("Double", Double.fromBits(0x7ff8000000000001), -0.0),
            Triple("Float", Float.fromBits(0x7fc00001), -0.0f)
        )) {
            val box = boxes.single { it.wrapper == wrapper }
            val nanMethod = wrapper.lowercase() + "NaN"
            val nanBodies = bodies(rule("boxed$wrapper", box.ruleValue(nan)))
            assertEquals(1, runtimeResult("source$wrapper", nan, nanMethod), "all NaN payloads are equal in wrapper equals")
            assertFoldedResult(nanBodies.getValue(nanMethod), 1)
            val zeroMethod = wrapper.lowercase() + "PositiveZero"
            assertEquals(2, runtimeResult("source$wrapper", negativeZero, zeroMethod), "opposite zero signs differ in wrapper equals")
            assertFoldedResult(bodies(rule("boxed$wrapper", box.ruleValue(negativeZero))).getValue(zeroMethod), 2)
        }
        val box = boxes.single { it.wrapper == "Double" }
        val bodies = bodies(rule("boxedDouble", box.ruleValue(Double.NaN)))
        for (method in listOf("doubleObjectsNaN", "doubleIntrinsicsNaN")) {
            assertFoldedResult(bodies.getValue(method), runtimeResult("sourceDouble", Double.NaN, method))
        }
        assertEquals(2, runtimeResult("sourceDouble", Double.NaN, "intrinsicsFloatingOverload"))
        assertTrue(
            "areEqual" in bodies.getValue("intrinsicsFloatingOverload").calls(),
            "the Double overload has primitive floating-point semantics"
        )
    }

    @Test
    fun `known string equality preserves independent scope control flow`() {
        for ((value, kept, removed) in listOf(Triple("A", "scopeA", "scopeB"), Triple("B", "scopeB", "scopeA"))) {
            val bodies = bodies(rule("text", value))
            val body = bodies.getValue(kept)
            assertEquals(listOf("inScope", "tail", "work"), body.calls())
            val scope = body.stmts.filterIsInstance<JAssignStmt>().single { it.callee() == "inScope" }
            val branch = body.stmts.filterIsInstance<JIfStmt>().single()
            assertEquals<Value>(scope.leftOp, branch.condition.op1, body.toString())
            assertEquals(IntConstant.getInstance(0), branch.condition.op2, body.toString())
            assertTrue(branch.condition is JEqExpr || branch.condition is JNeExpr, body.toString())
            val target = body.controlFlowGraph.getBranchTargetsOf(branch).single()
            val fallthrough = body.controlFlowGraph.successors(branch).single { it !== target }
            val work = body.stmts.single { it.callee() == "work" }
            val whenFalse = if (branch.condition is JEqExpr) target else fallthrough
            val whenTrue = if (branch.condition is JEqExpr) fallthrough else target
            assertFalse(body.reaches(whenFalse, work), "out of scope bypasses work: $body")
            assertTrue(body.reaches(whenTrue, work), "in scope reaches work: $body")
            assertEquals(listOf("inScope", "tail"), bodies.getValue(removed).calls(), "a false RHS still evaluates scope")
        }
    }

    @Test
    fun `removing known equality leaves the following throwing calls handler intact`() {
        val body = bodies(rule("text", "A")).getValue("neighboringHandler")
        assertEquals(listOf("other", "work"), body.calls())
        val throwingCall = body.stmts.single { it.callee() == "other" }
        val handlerWork = body.stmts.single { it.callee() == "work" }
        assertTrue(body.controlFlowGraph.exceptionalSuccessors(throwingCall).values.any { body.reaches(it, handlerWork) })
    }

    @Test
    fun `incompatible erased casts retain their ClassCastException path`() {
        val body = bodies(rule("erased", "A")).getValue("incompatibleCast")
        val cast = body.stmts.filterIsInstance<JAssignStmt>().single { it.rightOp is JCastExpr }
        assertEquals("java.lang.Integer", (cast.rightOp as JCastExpr).type.toString())
        val handler = body.stmts.single { it.callee() == "other" }
        assertTrue(
            body.controlFlowGraph.exceptionalSuccessors(cast).values.any { body.reaches(it, handler) },
            "the cast still throws into its catch"
        )
        assertFalse("erased" in body.calls(), "the lookup itself still folds")
    }

    @Test
    fun `unknown receivers arguments custom equals overloads and unrelated calls remain runtime operations`() {
        val bodies = bodies(rule("text", "A"))
        val retained = listOf(
            "unknownReceiver", "unknownArgument", "customReceiver", "overloaded", "nullReceiver",
            "unrelated", "untouched", "unknownObjects", "unknownIntrinsics"
        )
        for (method in retained) {
            val body = bodies.getValue(method)
            val equality = if (method == "unknownIntrinsics") "areEqual" else "equals"
            assertEquals(listOf(equality, "other", "work").sorted(), body.calls(), method)
            assertTrue(body.stmts.any { it is JIfStmt }, "$method retains its condition")
        }
        val nullBody = bodies.getValue("nullReceiver")
        val equality = nullBody.stmts.single { it.callee() == "equals" }
        val handlerWork = nullBody.stmts.single { it.callee() == "other" }
        assertTrue(
            nullBody.controlFlowGraph.exceptionalSuccessors(equality).values.any { nullBody.reaches(it, handlerWork) },
            "null receiver still throws into its catch"
        )
        val nullLookup = bodies(rule("text", null)).getValue("stringReceiver")
        assertTrue("equals" in nullLookup.calls(), "a rule proving the receiver null cannot delete its NPE")
    }
}
