package io.johnsonlee.graphite.cli

import com.google.gson.JsonParser
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.webgraph.GraphStore
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import picocli.CommandLine

/** `graphite build --fold <file>`: the rules fold the gated code away and the report lands next to the graph. */
class BuildFoldCommandTest {

    private val root: Path = Files.createTempDirectory("build-fold")
    private val classes: Path = root.resolve("classes").also { Files.createDirectories(it) }
    private val output: Path = root.resolve("graph")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun compileFixture() {
        val sourceDir = root.resolve("src/sample/gate").also { Files.createDirectories(it) }
        val flags = sourceDir.resolve("Flags.java").also {
            Files.writeString(it, "package sample.gate; public class Flags { public static boolean on(String k) { return k.isEmpty(); } }")
        }
        val app = sourceDir.resolve("App.java").also {
            Files.writeString(
                it,
                """
                package sample.gate;
                public class App {
                    static void treatment() { }
                    static void always() { }
                    public void run() { if (Flags.on("k")) { treatment(); } always(); }
                    public void keyed() { String key = "k"; if (Flags.on(key)) { treatment(); } }
                }
                """.trimIndent()
            )
        }
        val result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), flags.toString(), app.toString())
        assertEquals(0, result, "fixture compiles")
    }

    private fun run(vararg arguments: String): Triple<String, String, Int> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val previousOut = System.out
        val previousErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        val code = try {
            CommandLine(BuildCommand()).execute(classes.toString(), "-o", output.toString(), "--include", "sample.gate", *arguments)
        } finally {
            System.setOut(previousOut)
            System.setErr(previousErr)
        }
        return Triple(out.toString(), err.toString(), code)
    }

    @Test
    fun `the preview query reaches a key held in a local, as the fold pass does`() {
        compileFixture()
        assertEquals(0, run().third)
        val match = mapOf("CallSite" to mapOf("callee_name" to "on"))
        val rule = ConstantFold.parse(mapOf("match" to match, "args" to mapOf(0 to "k"), "value" to false))
        // `run()` passes the literal, `keyed()` a local holding it: the graph shows the second as
        // Constant -> Local -> CallSite, and the preview must reach it the way the matcher does.
        val rows = CypherExecutor(GraphStore.load(output)).execute(rule.cypher).rows
            .associate { it["caller"] as String to (it["calls"] as Number).toInt() }
        assertEquals(mapOf("sample.gate.App.run()" to 1, "sample.gate.App.keyed()" to 1), rows)
    }

    @Test
    fun `fold rules remove the gated code and the report is written next to the graph`() {
        compileFixture()
        val folds = root.resolve("folds.yml").also {
            Files.writeString(
                it,
                // YAML 1.1 reads a bare `on` as a boolean; a method of that name must be quoted.
                "version: 1\nfolds:\n  - match:\n      CallSite: { callee_class: sample.gate.Flags, callee_name: \"on\" }\n" +
                    "    args: { 0: k }\n    value: false\n"
            )
        }
        val (_, err, code) = run("--fold", folds.toString())
        assertEquals(0, code, err)
        // `run()` passes the key as a literal, `keyed()` through a local: the rule folds both.
        val line = "fold CallSite {callee_class: sample.gate.Flags, callee_name: on} [0: \"k\"] = false: 2 call(s) in 2 method(s)"
        assertTrue(err.contains(line), err)

        val graph = GraphStore.load(output)
        val callees = graph.nodes<CallSiteNode>().filter { it.caller.name == "run" }.map { it.callee.name }.toList()
        assertEquals(listOf("always"), callees, "the gate call and the treatment are gone")
        assertEquals(emptyList(), graph.nodes<CallSiteNode>().filter { it.caller.name == "keyed" }.toList(), "the local key folds too")

        val report = JsonParser.parseString(Files.readString(output.resolve(FoldConfig.REPORT_FILE))).asJsonObject
        assertEquals(1, report["version"].asInt)
        val fold = report["folds"].asJsonArray.single().asJsonObject
        assertEquals("sample.gate.Flags", fold["match"].asJsonObject["CallSite"].asJsonObject["callee_class"].asString)
        assertEquals("k", fold["args"].asJsonObject["0"].asString)
        assertEquals(2, fold["matched"].asInt)
        assertEquals(
            setOf("sample.gate.App.run()", "sample.gate.App.keyed()"),
            fold["sites"].asJsonArray.map { it.asJsonObject["caller"].asString }.toSet()
        )
    }

    @Test
    fun `a build without rules removes the report an earlier build left in the output directory`() {
        compileFixture()
        val folds = root.resolve("folds.json").also {
            Files.writeString(
                it,
                """{"version": 1, "folds": [{"match": {"CallSite": {"callee_class": "sample.gate.Flags", "callee_name": "on"}},""" +
                    """ "value": false}]}"""
            )
        }
        assertEquals(0, run("--fold", folds.toString()).third)
        assertTrue(Files.exists(output.resolve(FoldConfig.REPORT_FILE)))
        val (_, err, code) = run()
        assertEquals(0, code, err)
        assertFalse(Files.exists(output.resolve(FoldConfig.REPORT_FILE)), "a report describes the build that wrote it")
        val callees = GraphStore.load(output).nodes<CallSiteNode>()
            .filter { it.caller.name == "run" }.map { it.callee.name }.sorted().toList()
        assertEquals(listOf("always", "on", "treatment"), callees, "the graph is the unfolded one")
    }

    @Test
    fun `strict mode fails before saving when a rule matches nothing`() {
        compileFixture()
        val folds = root.resolve("folds.json").also {
            Files.writeString(it, """{"version":1,"folds":[{"match":{"CallSite":{"callee_name":"off"}},"value":false}]}""")
        }
        val (_, err, code) = run("--fold", folds.toString(), "--fold-strict")
        assertEquals(1, code, err)
        assertTrue(err.contains("--fold-strict: 1 rule(s) matched no call; see the warnings above"), err)
        assertTrue(err.contains("warning: this rule folded nothing, no call site has these properties"), err)
        assertFalse(Files.exists(output), "nothing is saved")

        val (_, lenientErr, lenientCode) = run("--fold", folds.toString())
        assertEquals(0, lenientCode, lenientErr)
        assertTrue(lenientErr.contains("0 call(s) in 0 method(s)"), lenientErr)
        val warning = "Warning: no fold rule folded any call; the graph is the same as a build without --fold"
        assertTrue(lenientErr.contains(warning), lenientErr)
    }

    @Test
    fun `a malformed fold file fails with its path and the problem`() {
        compileFixture()
        val folds = root.resolve("folds.yml").also { Files.writeString(it, "version: 7\nfolds: []\n") }
        val (_, err, code) = run("--fold", folds.toString())
        assertEquals(1, code)
        assertTrue(err.contains("folds.yml: unsupported version '7'"), err)

        Files.writeString(folds, "version: 1\nfolds:\n  - match: { CallSite: { callee_clas: sample.gate.Flags } }\n    value: false\n")
        val (_, typo, typoCode) = run("--fold", folds.toString())
        assertEquals(1, typoCode)
        assertTrue(typo.contains("folds.yml: folds[0]: unknown CallSite property 'callee_clas' (did you mean 'callee_class'?)"), typo)
    }

    @Test
    fun `a select rule is applied through its resolved keys and refused before they exist`() {
        compileFixture()
        val folds = root.resolve("select.yml").also {
            Files.writeString(
                it,
                "version: 1\nfolds:\n  - select: \"MATCH (cs:CallSite {callee_name: 'on'}) RETURN cs\"\n    value: false\n"
            )
        }
        val (_, err, code) = run("--fold", folds.toString())
        assertEquals(1, code, err)
        assertTrue(err.contains("Error: $folds: folds[0] is a 'select' rule, which the graphite CLI resolves: run `graphite build"), err)
        assertFalse(Files.exists(output), "nothing is built")

        val plan = root.resolve("plan.json").also {
            Files.writeString(
                it,
                """{"version": 1, "folds": [{"select": "MATCH (cs:CallSite {callee_name: 'on'}) RETURN cs", "selected": [""" +
                    """{"caller_signature": "sample.gate.App.run()", """ +
                    """"callee_signature": "sample.gate.Flags.on(java.lang.String)", "ordinal": 0}""" +
                    """], "value": false, "frontend": "jvm"}]}"""
            )
        }
        val (_, resolvedErr, resolvedCode) = run("--fold", plan.toString(), "--fold-strict")
        assertEquals(0, resolvedCode, resolvedErr)
        assertTrue(resolvedErr.contains("1 selected call site(s) = false: 1 call(s) in 1 method(s)"), resolvedErr)
        val callees = GraphStore.load(output).nodes<CallSiteNode>().filter { it.caller.name == "run" }.map { it.callee.name }.toList()
        assertEquals(listOf("always"), callees)
        val report = JsonParser.parseString(Files.readString(output.resolve(FoldConfig.REPORT_FILE))).asJsonObject
        assertEquals(1, report["version"].asInt)
        val fold = report["folds"].asJsonArray.single().asJsonObject
        assertEquals("MATCH (cs:CallSite {callee_name: 'on'}) RETURN cs", fold["select"].asString)
        assertEquals(0, fold["selected"].asJsonArray.single().asJsonObject["ordinal"].asInt)
        assertEquals(1, fold["matched"].asInt)

        // A selection that names no call site of this build is what strict mode is for.
        Files.writeString(
            plan,
            """{"version": 1, "folds": [{"select": "MATCH (cs:CallSite {callee_name: 'off'}) RETURN cs", """ +
                """"selected": [], "value": false}]}"""
        )
        val (_, strictErr, strictCode) = run("--fold", plan.toString(), "--fold-strict")
        assertEquals(1, strictCode, strictErr)
        assertTrue(strictErr.contains("warning: this rule folded nothing, the query selected no call site"), strictErr)
    }

    @Test
    fun `fold plan validates the file and prints its rules as JSON`() {
        val folds = root.resolve("plan.yml").also {
            Files.writeString(
                it,
                "version: 1\nfolds:\n  - select: \"MATCH (cs:CallSite {callee_name: 'on'}) RETURN cs\"\n    value: false\n" +
                    "  - match: { CallSite: { callee_name: limit } }\n    value: 3\n"
            )
        }
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val previousOut = System.out
        val previousErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        val code: Int
        val broken: Int
        try {
            code = CommandLine(GraphiteCommand()).execute("fold", "plan", folds.toString())
            Files.writeString(folds, "version: 1\nfolds: [{select: 1, value: 1}]\n")
            broken = CommandLine(GraphiteCommand()).execute("fold", "plan", folds.toString())
        } finally {
            System.setOut(previousOut)
            System.setErr(previousErr)
        }
        assertEquals(0, code, err.toString())
        val plan = JsonParser.parseString(out.toString()).asJsonObject
        assertEquals(1, plan["version"].asInt)
        val rules = plan["folds"].asJsonArray
        assertEquals("MATCH (cs:CallSite {callee_name: 'on'}) RETURN cs", rules[0].asJsonObject["select"].asString)
        assertTrue(rules[0].asJsonObject["selected"].isJsonNull)
        assertEquals("limit", rules[1].asJsonObject["match"].asJsonObject["CallSite"].asJsonObject["callee_name"].asString)
        assertEquals(1, broken)
        assertTrue(err.toString().contains("Error: $folds: folds[0]: 'select' must be a Cypher query, got integer 1"), err.toString())
    }

    @Test
    fun `fold without a subcommand prints its usage and plan binds the file argument`() {
        val out = ByteArrayOutputStream()
        val previousOut = System.out
        System.setOut(PrintStream(out))
        val code = try {
            CommandLine(GraphiteCommand()).execute("fold")
        } finally {
            System.setOut(previousOut)
        }
        assertEquals(0, code)
        assertTrue(out.toString().contains("Usage: fold"), out.toString())
        assertTrue(out.toString().contains("plan"), out.toString())

        val plan = FoldPlanCommand()
        CommandLine(plan).parseArgs(root.resolve("rules.yml").toString())
        assertEquals(root.resolve("rules.yml"), plan.file)
        plan.file = root.resolve("other.yml")
        assertEquals(root.resolve("other.yml"), plan.file)
    }

    @Test
    fun `build help describes the fold file`() {
        val out = ByteArrayOutputStream()
        CommandLine(BuildCommand()).usage(PrintWriter(out, true))
        assertTrue(out.toString().contains("--fold"), out.toString())
        assertTrue(out.toString().contains("graph.folds.json"), out.toString())
    }
}
