package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidDeclaredTypePersistenceTest {
    @Test
    fun `Android generic declarations survive persistence and Cypher projection`() {
        val sdk = Files.createTempDirectory("android-declared-types-sdk")
        val platform = Files.createDirectories(sdk.resolve("platforms/android-1"))
        JarOutputStream(Files.newOutputStream(platform.resolve("android.jar"))).use { }
        val apk = Path.of(requireNotNull(System.getProperty("android.declared.types.fixture")))
        val graph = JavaProjectLoader(
            LoaderConfig(
                includePackages = listOf("fixture.frontend.android"),
                includeLibraries = false,
                buildCallGraph = false,
                androidSdk = sdk
            )
        ).load(apk)
        try {
            val directory = Files.createTempDirectory("android-declared-types-graph")
            GraphStore.save(graph, directory)
            for (load in listOf<() -> Graph>({ GraphStore.load(directory) }, { GraphStore.loadMapped(directory) })) {
                val restored = load()
                try {
                    val executor = CypherExecutor(restored)
                    val method = executor.execute(
                        "MATCH (m:Method) WHERE m.name = 'convert' " +
                            "RETURN m.generic_return_type AS result, m.generic_parameter_types AS params, " +
                            "m.type_parameters AS variables"
                    ).rows.single()
                    val pair = "fixture.frontend.android.AndroidCases\$GenericPair"
                    assertEquals("$pair<T, ? super R[]>", method["result"])
                    assertEquals(listOf("$pair<? extends T, R>"), method["params"])
                    val variable = (method["variables"] as List<*>).single() as Map<*, *>
                    assertEquals("R", variable["name"])
                    assertEquals(listOf("java.lang.CharSequence"), variable["bounds"])

                    val field = executor.execute(
                        "MATCH (f:FieldNode) WHERE f.name = 'field' " +
                            "RETURN f.type AS erased, f.generic_type AS generic, f.type_info AS info"
                    ).rows.single()
                    assertEquals(pair, field["erased"])
                    assertEquals("$pair<java.lang.String, ? extends T[]>", field["generic"])
                    assertEquals("class", (field["info"] as Map<*, *>)["kind"])

                    val erasedOnly = executor.execute(
                        "MATCH (f:FieldNode) WHERE f.name = 'value' " +
                            "RETURN f.generic_type AS generic, keys(f) AS keys"
                    ).rows.single()
                    assertEquals("int", erasedOnly["generic"])
                    assertEquals(true, "generic_type" in erasedOnly["keys"] as List<*>)

                    val parameter = executor.execute(
                        "MATCH (p:ParameterNode) WHERE p.generic_type = '$pair<? extends T, R>' " +
                            "RETURN p.generic_type AS type"
                    ).rows.single()
                    assertEquals("$pair<? extends T, R>", parameter["type"])
                    val returned = executor.execute(
                        "MATCH (r:ReturnNode) WHERE r.generic_type = '$pair<T, ? super R[]>' " +
                            "RETURN r.generic_type AS type"
                    ).rows.single()
                    assertEquals("$pair<T, ? super R[]>", returned["type"])
                } finally {
                    (restored as? AutoCloseable)?.close()
                }
            }
        } finally {
            (graph as? AutoCloseable)?.close()
        }
    }
}
