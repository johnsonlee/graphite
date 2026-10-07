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
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.model.SourceType
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation
import sootup.java.core.views.JavaView

class EnumInitializationSafetyTest {
    private val temporaryRoots = mutableListOf<Path>()

    @AfterTest
    fun deleteCompiledFixtures() {
        temporaryRoots.forEach { it.toFile().deleteRecursively() }
    }

    private fun compile(): Path {
        val root = Files.createTempDirectory("enum-initialization-safety").also(temporaryRoots::add)
        val source = root.resolve("Shapes.java")
        Files.writeString(source, """
            package sample.enuminit;
            public class Shapes {
                public enum Plain {
                    A, B;
                    static Plain option() { return B; }
                    public static boolean compare() { return A.equals(option()); }
                }
                public static class ExternalInitializer {
                    static boolean result = Plain.A.equals(option());
                    static Plain option() { return Plain.B; }
                }
                public enum ConstructorCallsOut {
                    A, B;
                    ConstructorCallsOut() { External.check(); }
                }
                public enum InitializerCallsOut {
                    A, B;
                    static { External.touch(); }
                }
                public enum EntriesProviderOverload {
                    A, B;
                    static {
                        // A null provider isolates the overload without adding a lambda allocation/call.
                        kotlin.enums.EnumEntriesKt.enumEntries((kotlin.jvm.functions.Function0<EntriesProviderOverload[]>) null);
                    }
                }
                public enum FieldTriggersInitialization {
                    A(Trigger.VALUE), B(0);
                    FieldTriggersInitialization(int value) { }
                }
                public interface WithDefault {
                    int VALUE = Trigger.VALUE;
                    default int value() { return VALUE; }
                }
                public enum ImplementsDefault implements WithDefault { A, B }
                public enum ConstantSubclass {
                    A { public String toString() { return "A"; } }, B
                }
                public static class Trigger {
                    static final int VALUE = External.touch();
                }
                public static class External {
                    public static int caught;
                    static int touch() { return 1; }
                    static void work() { }
                    static void handled() { caught++; }
                    static ConstructorCallsOut option() { return ConstructorCallsOut.A; }
                    public static void check() {
                        try { if (ConstructorCallsOut.B.equals(option())) work(); }
                        catch (NullPointerException expected) { handled(); }
                    }
                }
            }
        """.trimIndent())
        val classes = root.resolve("classes").also { Files.createDirectories(it) }
        val stdlib = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-classpath", stdlib.toString(), "-d", classes.toString(), source.toString()
        ))
        return classes
    }

    @Test
    fun `only verified non-reentrant enum initialization proves fields nonnull`() {
        val view = JavaView(PathBasedAnalysisInputLocation.create(compile(), SourceType.Application))
        val safety = EnumInitializationSafety()
        val prefix = "sample.enuminit.Shapes\$"
        assertTrue(safety.isNonReentrant(view, view.identifierFactory.getClassType(prefix + "Plain")))
        val unsafe = listOf(
            "ConstructorCallsOut", "InitializerCallsOut", "EntriesProviderOverload", "FieldTriggersInitialization",
            "ImplementsDefault", "ConstantSubclass"
        )
        for (name in unsafe) {
            assertFalse(safety.isNonReentrant(view, view.identifierFactory.getClassType(prefix + name)), name)
        }
        val provider = view.getClass(view.identifierFactory.getClassType(prefix + "EntriesProviderOverload")).get()
        val providerCall = provider.methods.single { it.name == "<clinit>" }.body.stmts.filterIsInstance<JInvokeStmt>()
            .single { it.invokeExpr.get().methodSignature.name == "enumEntries" }.invokeExpr.get()
        assertEquals(listOf("kotlin.jvm.functions.Function0"), providerCall.methodSignature.parameterTypes.map { it.toString() })
        assertFalse(safety.isNonReentrant(view, view.identifierFactory.getClassType(prefix + "Missing")))
    }

    @Test
    fun `enum owner methods and class initializers retain equality even for an otherwise simple enum`() {
        val classes = compile()
        val enumName = "sample.enuminit.Shapes\$Plain"
        val targets = listOf(enumName to "compare", "sample.enuminit.Shapes\$ExternalInitializer" to "<clinit>")
        val rules = targets.map { (owner, _) ->
            ConstantFold.parse(mapOf(
                "match" to mapOf("CallSite" to mapOf("callee_class" to owner, "callee_name" to "option")),
                "value" to mapOf("EnumConstant" to mapOf("enum_type" to enumName, "name" to "A"))
            ))
        }
        val view = JavaView(PathBasedAnalysisInputLocation.create(
            classes, SourceType.Application, ConstantFolding(rules).bodyInterceptors(emptyList())
        ))
        for ((owner, method) in targets) {
            val body = view.getClass(view.identifierFactory.getClassType(owner)).get().methods.single { it.name == method }.body
            val calls = body.stmts.filterIsInstance<JAssignStmt>().mapNotNull { it.invokeExpr.orElse(null)?.methodSignature?.name }
            assertEquals(listOf("equals"), calls, "$owner.$method retains its comparison after the option call folds")
        }
    }

    @Test
    fun `an external helper called by an enum constructor retains nullable receiver equality and its handler`() {
        val classes = compile()
        val enumName = "sample.enuminit.Shapes\$ConstructorCallsOut"
        val helperName = "sample.enuminit.Shapes\$External"
        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            Class.forName(enumName, true, loader)
            assertEquals(2, loader.loadClass(helperName).getField("caught").getInt(null), "B is null in both enum constructors")
        }
        val rule = ConstantFold.parse(mapOf(
            "match" to mapOf("CallSite" to mapOf("callee_class" to helperName, "callee_name" to "option")),
            "value" to mapOf("EnumConstant" to mapOf("enum_type" to enumName, "name" to "A"))
        ))
        val view = JavaView(PathBasedAnalysisInputLocation.create(
            classes, SourceType.Application, ConstantFolding(listOf(rule)).bodyInterceptors(emptyList())
        ))
        val body = view.getClass(view.identifierFactory.getClassType(helperName)).get().methods.single { it.name == "check" }.body
        val equality = body.stmts.filterIsInstance<JAssignStmt>().single {
            it.invokeExpr.orElse(null)?.methodSignature?.name == "equals"
        }
        assertTrue(body.controlFlowGraph.exceptionalSuccessors(equality).isNotEmpty(), "initialization NPE still reaches its catch")
        assertTrue(body.stmts.filterIsInstance<JAssignStmt>().none {
            it.invokeExpr.orElse(null)?.methodSignature?.name == "option"
        }, "the configured lookup still folds; the uncertain equality is retained")
    }
}
