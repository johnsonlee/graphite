package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.jvmTypeDescriptor
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode

class DeclaredTypesReaderTest {
    private val root = Files.createTempDirectory("declared-types")

    @AfterTest
    fun cleanup() { root.toFile().deleteRecursively() }

    private fun compile(): Path {
        val source = root.resolve("Types.java")
        Files.writeString(source, """
            package fixture;
            import java.util.*;
            public class Types<T extends Number & Comparable<T>> {
                public List<String> first, second;
                public Map<String, List<? extends T[]>> nested;
                public List<? super T> lower;
                public List<?> any;
                public Types<T>.Inner<String> owner;
                public T value;
                public T[] values;
                public T[][] matrix;
                public T[][] matrix(T[][] input) { return input; }
                public List raw;
                public int primitive;
                public <T extends CharSequence> List<T> convert(List<? super T> value, T[] array) { return null; }
                public List<String> convert(int value) { return first; }
                public class Inner<U> {
                    public T outerValue;
                    public U innerValue;
                    public Inner(U value) { }
                }
                public <V> Object local(V value) {
                    class Local { public V methodValue; public T outerValue; }
                    return new Local();
                }
                public static <V> Object staticLocal(Types<?> owner, V value) {
                    class StaticLocal {
                        public V result;
                        public StaticLocal(Types<?> explicitOwner, V input) { result = value == null ? input : value; }
                    }
                    return new StaticLocal(owner, value);
                }
                public <V> Object instanceLocal(Types<?> owner, V value) {
                    class InstanceLocal {
                        public V result;
                        public InstanceLocal(Types<?> explicitOwner, V input) { result = value == null ? input : value; }
                    }
                    return new InstanceLocal(owner, value);
                }
                public static class Other<T> { public T value; }
                public static class Forward<A extends B, B extends Number> { public A first; public B second; }
                public abstract static class Contract<X extends Runnable & Comparable<X>> implements Comparable<Contract<X>> { }
                public enum Choice { ONE("one"); <V> Choice(V value) { } }
                public <E extends Exception> T checked(E problem) throws E { throw problem; }
                public static class Strings extends ArrayList<String> { }
                public static class Parent { public Object bridge() { return null; } }
                public static class Child extends Parent { public List<String> bridge() { return null; } }
            }
        """.trimIndent())
        val output = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", output.toString(), source.toString()))
        return output
    }

    private fun declarations(classes: Path): Map<String, ClassDeclarations> = Files.walk(classes).use { files ->
        files.filter { it.toString().endsWith(".class") }.map { file ->
            val node = ClassNode()
            ClassReader(Files.readAllBytes(file)).accept(node, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG)
            ClassDeclarations.from(node)
        }.toList().associateBy { it.name }
    }

    @Test
    fun `compiled declarations preserve structure scope bounds and shared types`() {
        val table = DeclaredTypesReader(declarations(compile())).build()
        fun field(name: String, owner: String = "fixture.Types") =
            table.fields.entries.single { it.key.owner == owner && it.key.name == name }.value
        assertEquals(field("first"), field("second"))
        assertEquals("java.util.List<java.lang.String>", table.render(field("first")))
        assertEquals("java.util.Map<java.lang.String, java.util.List<? extends T[]>>", table.render(field("nested")))
        val nested = table.types[field("nested")]
        val wildcard = table.types[table.types[nested.arguments[1]].arguments.single()]
        assertEquals("extends", wildcard.variance)
        val array = table.types[assertNotNull(wildcard.component)]
        assertEquals("array", array.kind)
        assertEquals("class:fixture.Types", table.types[assertNotNull(array.component)].scope)
        assertEquals("java.util.List<? super T>", table.render(field("lower")))
        assertEquals("java.util.List<?>", table.render(field("any")))
        assertEquals("fixture.Types<T>.Inner<java.lang.String>", table.render(field("owner")))
        assertEquals("java.util.List", table.render(field("raw")))
        assertEquals("int", table.render(field("primitive")))
        assertEquals("T[]", table.render(field("values")))
        val classParameter = table.classes.getValue("fixture.Types").typeParameters.single()
        assertEquals(listOf("java.lang.Number", "java.lang.Comparable<T>"), classParameter.bounds.map(table::render))
        val recursive = table.types[classParameter.bounds[1]].arguments.single()
        assertEquals("class:fixture.Types", table.types[recursive].scope)
        assertEquals(recursive, field("outerValue", "fixture.Types\$Inner"))
        assertNotEquals(recursive, field("value", "fixture.Types\$Other"))
        val method = table.methods.getValue(
            MemberTypeKey("fixture.Types", "convert", "(Ljava/util/List;[Ljava/lang/CharSequence;)Ljava/util/List;")
        )
        assertEquals(listOf("java.util.List<? super T>", "T[]"), method.parameterTypes.map(table::render))
        assertEquals("java.util.List<T>", table.render(method.returnType))
        val methodVariable = table.types[method.returnType].arguments.single()
        assertNotEquals(recursive, methodVariable)
        assertEquals(method.typeParameters.single().scope, table.types[methodVariable].scope)
        assertEquals(listOf("java.lang.CharSequence"), method.typeParameters.single().bounds.map(table::render))
        val constructor = table.methods.getValue(
            MemberTypeKey("fixture.Types\$Inner", "<init>", "(Lfixture/Types;Ljava/lang/Object;)V")
        )
        assertEquals(listOf("fixture.Types", "U"), constructor.parameterTypes.map(table::render))
        assertEquals("class:fixture.Types\$Inner", table.types[constructor.parameterTypes[1]].scope)
        val local = table.fields.entries.single { it.key.name == "methodValue" }.value
        assertEquals("method:fixture.Types#local(Ljava/lang/Object;)Ljava/lang/Object;", table.types[local].scope)
        assertEquals(
            "java.util.ArrayList<java.lang.String>",
            table.render(assertNotNull(table.classes.getValue("fixture.Types\$Strings").superType))
        )
        val bridges = table.methods.filterKeys { it.owner == "fixture.Types\$Child" && it.name == "bridge" }
        assertEquals(setOf("()Ljava/util/List;", "()Ljava/lang/Object;"), bridges.keys.map { it.descriptor }.toSet())
        assertEquals(
            setOf("java.util.List<java.lang.String>", "java.lang.Object"),
            bridges.values.map { table.render(it.returnType) }.toSet()
        )
        assertEquals(table.types.size, table.types.distinct().size)
        table.types.forEachIndexed { id, type ->
            (listOfNotNull(type.owner, type.component) + type.arguments).forEach {
                assertTrue(it < id, "acyclic child reference $it in $id")
            }
        }
    }

    @Test
    fun `real jar graph contains table and keeps erased method identity`() {
        val classes = compile()
        val jar = root.resolve("fixture.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            Files.walk(classes).use { files ->
                files.filter(Files::isRegularFile).forEach { file ->
                    out.putNextEntry(JarEntry(classes.relativize(file).toString()))
                    out.write(Files.readAllBytes(file))
                    out.closeEntry()
                }
            }
        }
        val graph = JavaProjectLoader(
            LoaderConfig(buildCallGraph = false, includePackages = listOf("fixture")), useMmapBuilder = false
        ).load(jar)
        val table = graph.declaredTypes()
        val method = table.methods.getValue(MemberTypeKey("fixture.Types", "convert", "(I)Ljava/util/List;"))
        assertEquals("java.util.List<java.lang.String>", table.render(method.returnType))
        assertEquals(table.fields.getValue(MemberTypeKey("fixture.Types", "first", "Ljava/util/List;")), method.returnType)
        val graphMethod = graph.methods(MethodPattern()).single {
            it.declaringClass.className == "fixture.Types" && it.name == "convert" &&
                it.parameterTypes.singleOrNull()?.className == "int"
        }
        assertEquals("java.util.List", graphMethod.returnType.className)
        assertEquals(emptyList(), graphMethod.returnType.typeArguments)
        val fields = graph.nodes<FieldNode>().filter { it.descriptor.declaringClass.className == "fixture.Types" }
            .associateBy { it.descriptor.name }
        for ((name, erased, generic) in listOf(
            Triple("value", "java.lang.Number", "T"),
            Triple("values", "java.lang.Number[]", "T[]"),
            Triple("matrix", "java.lang.Number[][]", "T[][]"),
            Triple("owner", "fixture.Types\$Inner", "fixture.Types<T>.Inner<java.lang.String>")
        )) {
            val field = fields.getValue(name).descriptor
            assertEquals(erased, field.type.className)
            val id = table.fields.getValue(MemberTypeKey(field.declaringClass.className, field.name,
                jvmTypeDescriptor(field.type.className)))
            assertEquals(generic, table.render(id))
        }
        assertEquals(listOf("java.lang.String"), fields.getValue("first").descriptor.type.typeArguments.map { it.className })
        val matrixMethod = graph.methods(MethodPattern(declaringClass = "fixture.Types", name = "matrix")).single()
        assertEquals(listOf("java.lang.Number[][]"), matrixMethod.parameterTypes.map { it.className })
        assertEquals("java.lang.Number[][]", matrixMethod.returnType.className)
        val matrix = table.methods.getValue(MemberTypeKey("fixture.Types", "matrix", matrixMethod.descriptor))
        assertEquals(listOf("T[][]"), matrix.parameterTypes.map(table::render))
        assertEquals("T[][]", table.render(matrix.returnType))
    }

    @Test
    fun `local constructors distinguish explicit owner arguments from synthetic enclosing instances`() {
        val declarations = declarations(compile())
        val table = DeclaredTypesReader(declarations).build()
        val static = table.methods.entries.single { it.key.owner.endsWith("StaticLocal") && it.key.name == "<init>" }
        assertEquals("(Lfixture/Types;Ljava/lang/Object;Ljava/lang/Object;)V", static.key.descriptor)
        assertEquals(listOf("fixture.Types<?>", "V", "java.lang.Object"), static.value.parameterTypes.map(table::render))
        assertEquals("method:fixture.Types#staticLocal(Lfixture/Types;Ljava/lang/Object;)Ljava/lang/Object;",
            table.types[static.value.parameterTypes[1]].scope)
        val instance = table.methods.entries.single { it.key.owner.endsWith("InstanceLocal") && it.key.name == "<init>" }
        assertEquals("(Lfixture/Types;Lfixture/Types;Ljava/lang/Object;Ljava/lang/Object;)V", instance.key.descriptor)
        assertEquals(listOf("fixture.Types", "fixture.Types<?>", "V", "java.lang.Object"),
            instance.value.parameterTypes.map(table::render))
        val isolated = DeclaredTypesReader(declarations.filterKeys { it.endsWith("StaticLocal") }).build()
        assertEquals(listOf("fixture.Types", "java.lang.Object", "java.lang.Object"),
            isolated.methods.getValue(static.key).parameterTypes.map(isolated::render))
    }

    @Test
    fun `compiled forward interface exception and enum declarations resolve exact scopes`() {
        val table = DeclaredTypesReader(declarations(compile())).build()
        val forward = table.classes.getValue("fixture.Types\$Forward").typeParameters
        assertEquals(listOf("A", "B"), forward.map { it.name })
        assertEquals(listOf("B"), forward[0].bounds.map(table::render))
        assertEquals(listOf("java.lang.Number"), forward[1].bounds.map(table::render))
        val second = table.fields.getValue(MemberTypeKey("fixture.Types\$Forward", "second", "Ljava/lang/Number;"))
        assertEquals(second, forward[0].bounds.single())
        assertEquals("class:fixture.Types\$Forward", table.types[second].scope)
        val contract = table.classes.getValue("fixture.Types\$Contract")
        assertEquals(listOf("java.lang.Runnable", "java.lang.Comparable<X>"),
            contract.typeParameters.single().bounds.map(table::render))
        assertEquals(listOf("java.lang.Comparable<fixture.Types\$Contract<X>>"), contract.interfaces.map(table::render))
        val choiceKey = MemberTypeKey("fixture.Types\$Choice", "<init>", "(Ljava/lang/String;ILjava/lang/Object;)V")
        val choice = table.methods.getValue(choiceKey)
        assertEquals(listOf("java.lang.String", "int", "V"), choice.parameterTypes.map(table::render))
        assertEquals(choice.typeParameters.single().scope, table.types[choice.parameterTypes[2]].scope)
        val checked = table.methods.getValue(MemberTypeKey("fixture.Types", "checked", "(Ljava/lang/Exception;)Ljava/lang/Number;"))
        assertEquals("T", table.render(checked.returnType))
        assertEquals("class:fixture.Types", table.types[checked.returnType].scope)
        assertEquals(listOf("E"), checked.parameterTypes.map(table::render))
        assertEquals(listOf("java.lang.Exception"), checked.typeParameters.single().bounds.map(table::render))
        assertEquals(checked.typeParameters.single().scope, table.types[checked.parameterTypes.single()].scope)
    }

    @Test
    fun `missing enclosing declaration remains explicitly unresolved`() {
        val snapshots = declarations(compile()).filterKeys { it == "fixture.Types\$Inner" }
        val table = DeclaredTypesReader(snapshots).build()
        val outer = table.fields.entries.single { it.key.name == "outerValue" }.value
        assertEquals("unresolved:class:fixture.Types\$Inner", table.types[outer].scope)
        assertEquals("T", table.render(outer))
    }

    @Test
    fun `excessively nested signatures fall back to descriptors without recursive overflow`() {
        val deep = "Ljava/util/List<".repeat(2_000) + "Ljava/lang/String;" + ">;".repeat(2_000)
        val declaration = ClassDeclarations(
            "example.Deep", "<T:$deep>Ljava/lang/Object;", "java.lang.Object", emptyList(),
            listOf(MemberDeclaration("deep", "Ljava/util/List;", deep),
                MemberDeclaration("array", "Ljava/lang/Object;", "[".repeat(2_000) + "Ljava/lang/String;")),
            listOf(MemberDeclaration("read", "()Ljava/util/List;", "<T:$deep>()$deep")),
            null, null, false
        )
        val table = DeclaredTypesReader(mapOf(declaration.name to declaration)).build()
        assertEquals("java.util.List", table.render(table.fields.getValue(MemberTypeKey("example.Deep", "deep", "Ljava/util/List;"))))
        assertEquals("java.lang.Object", table.render(table.fields.getValue(MemberTypeKey("example.Deep", "array", "Ljava/lang/Object;"))))
        val method = table.methods.getValue(MemberTypeKey("example.Deep", "read", "()Ljava/util/List;"))
        assertEquals("java.util.List", table.render(method.returnType))
        assertEquals(emptyList(), method.typeParameters)
        assertEquals(emptyList(), table.classes.getValue("example.Deep").typeParameters)
    }


    @Test
    fun `rejected signatures roll back their interned rows before descriptor fallback`() {
        val declaration = ClassDeclarations(
            "example.Bad", "<T:Lbad/Bound;>Ljava/lang/Object;X", "java.lang.Object", emptyList(),
            listOf(
                MemberDeclaration("bad", "Ljava/util/List;", "Ljava/util/List<Lbad/Field;>;X"),
                MemberDeclaration("good", "Ljava/util/List;", "Ljava/util/List<Ljava/lang/String;>;")
            ),
            listOf(MemberDeclaration("read", "()Ljava/lang/Object;", "<U:Lbad/Method;>()Ljava/util/List<Lbad/Argument;>;^")),
            null, null, false
        )
        val table = DeclaredTypesReader(mapOf(declaration.name to declaration)).build()
        assertEquals(listOf("java.lang.Object", "java.util.List", "java.lang.String", "java.util.List"),
            table.types.map { it.name })
        assertEquals(listOf(emptyList(), emptyList(), emptyList(), listOf(2)), table.types.map { it.arguments })
        assertEquals("java.util.List", table.render(table.fields.getValue(MemberTypeKey("example.Bad", "bad", "Ljava/util/List;"))))
        assertEquals("java.util.List<java.lang.String>",
            table.render(table.fields.getValue(MemberTypeKey("example.Bad", "good", "Ljava/util/List;"))))
        assertEquals(0, table.methods.getValue(MemberTypeKey("example.Bad", "read", "()Ljava/lang/Object;")).returnType)
    }

}
