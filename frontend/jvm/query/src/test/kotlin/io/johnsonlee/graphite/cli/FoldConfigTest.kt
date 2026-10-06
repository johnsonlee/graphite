package io.johnsonlee.graphite.cli

import com.google.gson.JsonParser
import io.johnsonlee.graphite.input.CallSiteKey
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.ConstantPattern
import io.johnsonlee.graphite.input.FoldOutcome
import io.johnsonlee.graphite.input.FoldProvenance
import io.johnsonlee.graphite.input.FoldedMethod
import io.johnsonlee.graphite.input.FoldReport
import io.johnsonlee.graphite.input.FoldSite
import io.johnsonlee.graphite.input.FoldSites
import io.johnsonlee.graphite.input.UnsupportedFoldSite
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FoldConfigTest {

    private val dir: Path = Files.createTempDirectory("fold-config")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun file(name: String, text: String): Path = dir.resolve(name).also { Files.writeString(it, text) }

    private fun failure(name: String, text: String): String =
        assertFailsWith<FoldConfigException> { FoldConfig.load(file(name, text)) }.message!!

    private fun rule(site: Map<String, String>, args: Map<Int, ConstantPattern> = emptyMap(), value: ConstantPattern): ConstantFold =
        ConstantFold(site, args, value)

    private fun scalar(value: Any?): ConstantPattern =
        if (value == null) ConstantPattern("NullConstant") else ConstantPattern("Constant", mapOf("value" to value))

    @Test
    fun `yaml and json carry the same rules`() {
        val yaml = file(
            "folds.yml",
            """
            version: 1
            folds:
              - match:
                  CallSite: { callee_class: com.example.Flags, callee_name: enabled, caller_class: "com.example.*" }
                args:
                  0: new_checkout
                  1: { LongConstant: { value: 7 } }
                frontend: jvm
                value: false
              - match:
                  CallSite: { callee_signature: com.example.Flags.limit() }
                value: 3
              - match:
                  CallSite: { callee.owner: com.example.Flags, callee.name: big }
                value: 3000000000
              - match: { CallSite: { callee_name: ratio } }
                value: 0.5
              - match: { CallSite: { callee_name: label } }
                value: "live"
              - match: { CallSite: { callee_name: boxed } }
                value: null
              - match: { CallSite: { callee_name: variant } }
                args:
                  0: { EnumConstant: { enum_type: com.example.Flag, name: NEW_CHECKOUT } }
                value: { IntConstant: { value: 0 } }
              - match: { CallSite: { callee_name: isEnabled } }
                frontend: swift
                value: false
            """.trimIndent()
        )
        val json = file(
            "folds.json",
            """
            {"version": 1, "folds": [
              {"match": {"CallSite": {"callee_class": "com.example.Flags", "callee_name": "enabled", "caller_class": "com.example.*"}},
               "args": {"0": "new_checkout", "1": {"LongConstant": {"value": 7}}}, "frontend": "jvm", "value": false},
              {"match": {"CallSite": {"callee_signature": "com.example.Flags.limit()"}}, "value": 3},
              {"match": {"CallSite": {"callee.owner": "com.example.Flags", "callee.name": "big"}}, "value": 3000000000},
              {"match": {"CallSite": {"callee_name": "ratio"}}, "value": 0.5},
              {"match": {"CallSite": {"callee_name": "label"}}, "value": "live"},
              {"match": {"CallSite": {"callee_name": "boxed"}}, "value": null},
              {"match": {"CallSite": {"callee_name": "variant"}},
               "args": {"0": {"EnumConstant": {"enum_type": "com.example.Flag", "name": "NEW_CHECKOUT"}}}, "value": {"IntConstant": {"value": 0}}},
              {"match": {"Call": {"name": "isEnabled"}}, "frontend": "js", "value": false}
            ]}
            """.trimIndent()
        )
        val expected = listOf(
            rule(
                mapOf("callee_class" to "com.example.Flags", "callee_name" to "enabled", "caller_class" to "com.example.*"),
                mapOf(0 to scalar("new_checkout"), 1 to ConstantPattern("LongConstant", mapOf("value" to 7))),
                scalar(false)
            ),
            rule(mapOf("callee_signature" to "com.example.Flags.limit()"), value = scalar(3)),
            rule(mapOf("callee_class" to "com.example.Flags", "callee_name" to "big"), value = scalar(3_000_000_000L)),
            rule(mapOf("callee_name" to "ratio"), value = scalar(0.5)),
            rule(mapOf("callee_name" to "label"), value = scalar("live")),
            rule(mapOf("callee_name" to "boxed"), value = scalar(null)),
            rule(
                mapOf("callee_name" to "variant"),
                mapOf(0 to ConstantPattern("EnumConstant", mapOf("enum_type" to "com.example.Flag", "name" to "NEW_CHECKOUT"))),
                ConstantPattern("IntConstant", mapOf("value" to 0))
            )
        )
        assertEquals(expected, FoldConfig.load(yaml))
        assertEquals(expected, FoldConfig.load(json))
        assertEquals(expected, FoldConfig.load(file("folds.yaml", Files.readString(yaml))))
        val double = file("double.json", """{"version":1,"folds":[{"match":{"CallSite":{"callee_name":"c"}},"value":2.0}]}""")
        assertEquals(listOf(rule(mapOf("callee_name" to "c"), value = scalar(2.0))), FoldConfig.load(double))
    }

    @Test
    fun `every malformed document names the file and the problem`() {
        val site = "match: {CallSite: {callee_name: c}}"
        assertTrue(failure("folds.txt", "version: 1").contains("unsupported extension"))
        assertTrue(failure("list.yml", "- 1").contains("must be a map"))
        assertTrue(failure("extra.yml", "version: 1\nfolds: []\nrules: []").contains("unknown key 'rules'"))
        assertTrue(failure("old.yml", "version: 3\nfolds: []").contains("unsupported version '3': this build reads version 1"))
        val blank = failure("blank.yml", "version: 1\nfolds: [{select: ' ', value: 1}]")
        assertTrue(blank.contains("folds[0]: 'select' is blank"), blank)
        val selected = failure("selected.yml", "version: 1\nfolds: [{select: 'MATCH (cs) RETURN cs', selected: [{ordinal: 1}], value: 1}]")
        assertTrue(selected.contains("folds[0]: 'selected[0]'.caller_signature must be a method signature, got null"), selected)
        assertTrue(failure("entry2.yml", "version: 1\nfolds: [1]").contains("folds[0] must be a map with 'match' or 'select' and 'value'"))
        assertTrue(failure("missing.yml", "version: 1").contains("'folds' must be a list"))
        assertTrue(failure("entry.yml", "version: 1\nfolds: [1]").contains("folds[0] must be a map"))
        assertTrue(failure("key.yml", "version: 1\nfolds: [{$site, value: 1, when: x}]").contains("folds[0]: unknown key 'when'"))
        assertTrue(failure("symbol.yml", "version: 1\nfolds: [{symbol: a.B.c, value: 1}]").contains("folds[0]: unknown key 'symbol'"))
        val match = failure("match.yml", "version: 1\nfolds: [{value: 1}]")
        assertTrue(match.contains("'match' is missing: name the call site to fold"), match)
        assertTrue(failure("value.yml", "version: 1\nfolds: [{$site}]").contains("'value' is missing"))
        val scalar = failure("scalar.yml", "version: 1\nfolds: [{$site, value: [1]}]")
        assertTrue(scalar.contains("Constant.value must be a boolean, number or string, got a list"), scalar)
        val label = failure("label.yml", "version: 1\nfolds: [{$site, value: {Gate: {}}}]")
        assertTrue(label.contains("'value': unknown constant label 'Gate'"), label)
        assertTrue(failure("args.yml", "version: 1\nfolds: [{$site, args: [1], value: 1}]").contains("'args' must be a map"))
        val index = failure("index.yml", "version: 1\nfolds: [{$site, args: {k: 1}, value: 1}]")
        assertTrue(index.contains("'args' key 'k' must be an argument index"), index)
        val property = failure("property.yml", "version: 1\nfolds: [{match: {CallSite: {line: 3}}, value: 1}]")
        assertTrue(property.contains("'match.CallSite.line' must be a string"), property)
        val unknown = failure("unknown.yml", "version: 1\nfolds: [{match: {CallSite: {line: '3'}}, value: 1}]")
        assertTrue(unknown.contains("unknown CallSite property 'line'"), unknown)
        val frontend = failure("frontend.yml", "version: 1\nfolds: [{$site, value: 1, frontend: 2}]")
        assertTrue(frontend.contains("'frontend' must be a string"), frontend)
        val unscoped = failure("unscoped.yml", "version: 1\nfolds: [{match: {Call: {name: isEnabled}}, value: false}]")
        assertTrue(unscoped.contains("'match' names 'Call' (did you mean 'CallSite'?)"), "must parse here: $unscoped")
        assertTrue(failure("broken.json", "{").contains("not valid JSON"))
        assertTrue(failure("broken.yml", "version: [").contains("not valid YAML"))
        assertTrue(failure("trailing.json", "{\"version\":1,\"folds\":[]} x").contains("not valid JSON"))
        // The same key spelled twice: a tree parser keeps the last one silently; both readers refuse it.
        val twiceSite = """{"CallSite":{"callee_name":"a","callee_name":"b"}}"""
        val twiceJson = failure("twice.json", """{"version":1,"folds":[{"match":$twiceSite,"value":1}]}""")
        assertTrue(twiceJson.contains("object names member 'callee_name' twice; name it once"), twiceJson)
        val topJson = failure("top.json", """{"version":1,"folds":[],"folds":[{"match":{"CallSite":{}},"value":1}]}""")
        assertTrue(topJson.contains("names member 'folds' twice"), topJson)
        val twiceYaml = failure("twice.yml", "version: 1\nfolds: [{match: {CallSite: {callee_name: a, callee_name: b}}, value: 1}]")
        assertTrue(twiceYaml.contains("not valid YAML") && twiceYaml.contains("duplicate key"), twiceYaml)
        val valueYaml = failure("value.yml", "version: 1\nfolds:\n  - match: {CallSite: {callee_name: a}}\n    value: 1\n    value: 2\n")
        assertTrue(valueYaml.contains("duplicate key"), valueYaml)
        val missing = assertFailsWith<FoldConfigException> { FoldConfig.load(dir.resolve("absent.yml")) }
        assertTrue(missing.message!!.contains("cannot read"), missing.message)
    }

    @Test
    fun `select rules parse beside match rules, and plan prints them resolved or not`() {
        val yaml = file(
            "select.yml",
            """
            version: 1
            folds:
              - select: >
                  MATCH (c:IntConstant {value: 1234})-[:DATAFLOW*]->(cs:CallSite {callee_name: getAbTestOption})
                  RETURN cs
                value: { EnumConstant: { enum_type: com.example.ABTestOption, name: CONTROL } }
              - match: { CallSite: { callee_name: enabled, ordinal: 2 } }
                value: false
              - select: "MATCH (cs:CallSite {callee_name: variant}) RETURN cs"
                frontend: swift
                value: 1
              - select: >-
                  MATCH (cs:CallSite {callee_name: on}) RETURN cs
                selected:
                  - { caller_signature: a.C.run(), caller_descriptor: ()V, callee_signature: a.B.on(java.lang.String), callee_descriptor: (Ljava/lang/String;)Z, ordinal: 1 }
                provenance: { input_sha256: "${"ab".repeat(32)}", frontend_version: "1.0.0" }
                value: false
            """.trimIndent()
        )
        val rules = FoldConfig.load(yaml)
        assertEquals(3, rules.size)
        val first = rules[0] as FoldSites
        assertEquals(null, first.selected)
        assertTrue(first.select.startsWith("MATCH (c:IntConstant {value: 1234})"), first.select)
        assertEquals(ConstantPattern("EnumConstant", mapOf("enum_type" to "com.example.ABTestOption", "name" to "CONTROL")), first.value)
        assertEquals(rule(mapOf("callee_name" to "enabled", "ordinal" to "2"), value = scalar(false)), rules[1])
        val third = rules[2] as FoldSites
        assertEquals(setOf(CallSiteKey("a.C.run()", "()V", "a.B.on(java.lang.String)", "(Ljava/lang/String;)Z", 1)), third.selected)
        assertEquals("1.0.0", third.provenance!!.frontendVersion)

        val unresolved = FoldConfig.unresolved(yaml, rules)!!
        assertTrue(unresolved.startsWith("$yaml: folds[0] is a 'select' rule, which the graphite CLI resolves"), unresolved)
        assertEquals(null, FoldConfig.unresolved(yaml, rules.drop(1)))

        val plan = JsonParser.parseString(FoldConfig.plan(yaml)).asJsonObject
        assertEquals(1, plan["version"].asInt)
        val folds = plan["folds"].asJsonArray
        assertEquals(3, folds.size(), "the swift rule is not this frontend's")
        assertTrue(folds[0].asJsonObject["select"].asString.startsWith("MATCH (c:IntConstant"))
        assertTrue(folds[0].asJsonObject["selected"].isJsonNull, "unresolved: the CLI fills it in")
        assertEquals("CONTROL", folds[0].asJsonObject["value"].asJsonObject["EnumConstant"].asJsonObject["name"].asString)
        assertEquals("jvm", folds[0].asJsonObject["frontend"].asString)
        assertEquals("2", folds[1].asJsonObject["match"].asJsonObject["CallSite"].asJsonObject["ordinal"].asString)
        val key = folds[2].asJsonObject["selected"].asJsonArray.single().asJsonObject
        assertEquals("a.C.run()", key["caller_signature"].asString)
        assertEquals("(Ljava/lang/String;)Z", key["callee_descriptor"].asString)
        assertEquals(1, key["ordinal"].asInt)
        assertEquals("1.0.0", folds[2].asJsonObject["provenance"].asJsonObject["frontend_version"].asString)
        assertTrue(folds[0].asJsonObject["provenance"].isJsonNull)
        assertEquals(null, plan["provenance"], "no input, no provenance")
        // The plan is a fold file: it reads back as the same rules.
        assertEquals(rules, FoldConfig.load(file("plan.json", FoldConfig.plan(yaml))))

        val v1 = FoldConfig.plan(file("v1.yml", "version: 1\nfolds: [{match: {CallSite: {callee_name: a}}, value: 1}]"))
        assertEquals(1, JsonParser.parseString(v1).asJsonObject["version"].asInt)
    }

    @Test
    fun `the report of a select rule carries its query, keys and the sites it did not find`() {
        val key = CallSiteKey("a.C.run()", "()V", "a.B.on(java.lang.String)", "(Ljava/lang/String;)Z", 1)
        val gone = CallSiteKey("a.C.gone()", "()V", "a.B.on(java.lang.String)", "(Ljava/lang/String;)Z", 0)
        val provenance = FoldProvenance("cd".repeat(32), "2.0.0")
        val folded = FoldOutcome(
            FoldSites(
                "MATCH (cs:CallSite {callee_name: 'on'}) RETURN cs",
                setOf(key, gone),
                scalar(false),
                mapOf(key to "java.lang.Boolean"),
                provenance
            ),
            listOf(FoldSite("a.C.run()", 1)),
            hints = listOf("selected call site $gone is not in this build")
        )
        val empty = FoldOutcome(FoldSites("MATCH (cs:CallSite {callee_name: 'off'}) RETURN cs", emptySet(), scalar(false)), emptyList())
        val lost = FoldOutcome(
            FoldSites("MATCH (cs:CallSite {callee_name: 'on'}) RETURN cs", setOf(gone), scalar(false)),
            emptyList(),
            hints = listOf("selected call site $gone is not in this build")
        )
        val report = FoldReport(
            listOf(folded, empty, lost, FoldOutcome(rule(mapOf("callee_name" to "x"), value = scalar(1)), emptyList())),
            listOf(FoldedMethod("a.C.run()", 7, 4))
        )
        val rendered = JsonParser.parseString(FoldConfig.render(report)).asJsonObject
        assertEquals(1, rendered["version"].asInt)
        // The report is a fold file: it reads back as its rules, select rules resolved, the diagnostics dropped.
        assertEquals(report.outcomes.map { it.fold }, FoldConfig.load(file("graph.folds.json", FoldConfig.render(report))))
        val first = rendered["folds"].asJsonArray[0].asJsonObject
        assertEquals("MATCH (cs:CallSite {callee_name: 'on'}) RETURN cs", first["select"].asString)
        assertEquals(first["select"].asString, first["cypher"].asString)
        assertEquals(2, first["selected"].asJsonArray.size())
        assertEquals("a.C.run()", first["selected"].asJsonArray[0].asJsonObject["caller_signature"].asString)
        assertEquals("java.lang.Boolean", first["selected"].asJsonArray[0].asJsonObject["result_type"].asString)
        assertEquals("2.0.0", first["provenance"].asJsonObject["frontend_version"].asString)
        assertEquals(1, first["matched"].asInt)
        assertEquals(3, rendered["statementsRemoved"].asInt)
        assertEquals(7, rendered["methods"].asJsonArray.single().asJsonObject["statementsBefore"].asInt)
        assertEquals(false, first["value"].asBoolean)
        assertEquals(
            listOf(
                "fold select {MATCH (cs:CallSite {callee_name: 'on'}) RETURN cs} 2 selected call site(s) = false: " +
                    "1 call(s) in 1 method(s)",
                "fold select {MATCH (cs:CallSite {callee_name: 'off'}) RETURN cs} 0 selected call site(s) = false: " +
                    "0 call(s) in 0 method(s)",
                "  warning: this rule folded nothing, the query selected no call site",
                "    preview on a built graph: MATCH (cs:CallSite {callee_name: 'off'}) RETURN cs",
                "fold select {MATCH (cs:CallSite {callee_name: 'on'}) RETURN cs} 1 selected call site(s) = false: " +
                    "0 call(s) in 0 method(s)",
                "  warning: this rule folded nothing, none of the 1 selected call site(s) is in this build:",
                "    selected call site $gone is not in this build",
                "    preview on a built graph: MATCH (cs:CallSite {callee_name: 'on'}) RETURN cs",
                "folds removed 3 statement(s) in 1 method(s)"
            ),
            FoldConfig.summary(FoldReport(listOf(folded, empty, lost), report.methods))
        )
    }

    @Test
    fun `the report keeps the file's shape and adds what every rule did`() {
        val report = FoldReport(
            listOf(
                FoldOutcome(
                    rule(
                        mapOf("callee_class" to "a.B", "callee_name" to "on"),
                        mapOf(0 to scalar("key"), 1 to ConstantPattern("LongConstant", mapOf("value" to 7))),
                        scalar(false)
                    ),
                    listOf(FoldSite("a.C.run()", 1)),
                    listOf(UnsupportedFoldSite("a.C.box()", "return type java.lang.Boolean cannot carry false"))
                ),
                FoldOutcome(rule(mapOf("callee_name" to "label"), value = scalar(null)), emptyList()),
                FoldOutcome(
                    rule(mapOf("callee_name" to "near"), value = scalar(1)),
                    emptyList(),
                    hints = listOf("a.B.near(int) is called with argument 0 = 2 (3 call(s))")
                )
            ),
            listOf(FoldedMethod("a.C.run()", 7, 4))
        )
        val rendered = JsonParser.parseString(FoldConfig.render(report)).asJsonObject
        assertEquals(1, rendered["version"].asInt)
        val first = rendered["folds"].asJsonArray[0].asJsonObject
        assertEquals("a.B", first["match"].asJsonObject["CallSite"].asJsonObject["callee_class"].asString)
        assertEquals("key", first["args"].asJsonObject["0"].asString, "the shorthand stays a scalar")
        assertEquals(7, first["args"].asJsonObject["1"].asJsonObject["LongConstant"].asJsonObject["value"].asInt)
        assertEquals("jvm", first["frontend"].asString)
        assertEquals(false, first["value"].asBoolean)
        val cypher = first["cypher"].asString
        assertTrue(cypher.startsWith("MATCH (cs:CallSite {callee_class: \"a.B\", callee_name: \"on\"})"), cypher)
        assertEquals(1, first["matched"].asInt)
        assertEquals(null, first["statementsRemoved"], "statements are the method's, not the rule's")
        assertEquals(3, rendered["statementsRemoved"].asInt)
        assertEquals("a.C.run()", first["sites"].asJsonArray[0].asJsonObject["caller"].asString)
        assertEquals("a.C.box()", first["unsupported"].asJsonArray[0].asJsonObject["caller"].asString)
        val second = rendered["folds"].asJsonArray[1].asJsonObject
        assertTrue(second["value"].isJsonNull, "a null value is written, not dropped")
        assertEquals(0, second["args"].asJsonObject.size())
        assertEquals(0, second["hints"].asJsonArray.size())
        val third = rendered["folds"].asJsonArray[2].asJsonObject
        assertEquals("a.B.near(int) is called with argument 0 = 2 (3 call(s))", third["hints"].asJsonArray.single().asString)
        assertEquals(
            listOf(
                "fold CallSite {callee_class: a.B, callee_name: on} [0: \"key\", 1: LongConstant {value: 7}] = false: " +
                    "1 call(s) in 1 method(s), 1 unsupported",
                "fold CallSite {callee_name: label} = null: 0 call(s) in 0 method(s)",
                "  warning: this rule folded nothing, no call site has these properties",
                "    preview on a built graph: MATCH (cs:CallSite {callee_name: \"label\"}) " +
                    "RETURN cs.caller_signature AS caller, count(cs) AS calls",
                "fold CallSite {callee_name: near} = 1: 0 call(s) in 0 method(s)",
                "  warning: this rule folded nothing, no call matched; the nearest are:",
                "    a.B.near(int) is called with argument 0 = 2 (3 call(s))",
                "    preview on a built graph: MATCH (cs:CallSite {callee_name: \"near\"}) " +
                    "RETURN cs.caller_signature AS caller, count(cs) AS calls",
                "folds removed 3 statement(s) in 1 method(s)"
            ),
            FoldConfig.summary(report)
        )
    }

    @Test
    fun `a build in which no rule folded anything says so`() {
        val boxed = FoldOutcome(
            rule(mapOf("callee_name" to "boxed"), value = scalar(false)),
            emptyList(),
            listOf(UnsupportedFoldSite("a.C.box()", "return type java.lang.Boolean cannot carry false"))
        )
        val lines = FoldConfig.summary(FoldReport(listOf(boxed)))
        val reason = "every call it matched is unsupported: return type java.lang.Boolean cannot carry false"
        assertEquals("  warning: this rule folded nothing, $reason", lines[1])
        assertEquals("Warning: no fold rule folded any call; the graph is the same as a build without --fold", lines.last())
        assertEquals(emptyList(), FoldConfig.summary(FoldReport(emptyList())))
    }

    @Test
    fun `numbers are read exactly or refused, in JSON as in YAML`() {
        fun json(body: String) = """{"version":1,"folds":[{"match":{"CallSite":{"callee_name":"a"}},$body}]}"""
        fun yaml(body: String) = "version: 1\nfolds: [{match: {CallSite: {callee_name: a}}, $body}]"
        val fromJson = FoldConfig.load(file("n.json", json(""""args":{"0":10,"1":4294967296,"2":1e0,"3":2.5},"value":9007199254740993""")))
        val fromYaml = FoldConfig.load(file("n.yml", yaml("args: {0: 10, 1: 4294967296, 2: 1.0e0, 3: 2.5}, value: 9007199254740993")))
        assertEquals(fromJson, fromYaml, "the two spellings of one file read the same")
        val fold = fromJson.single() as ConstantFold
        assertEquals(mapOf(0 to scalar(10), 1 to scalar(4294967296L), 2 to scalar(1.0), 3 to scalar(2.5)), fold.arguments)
        assertEquals(scalar(9007199254740993L), fold.value, "a long keeps every bit")
        val wide = "integer 9223372036854775808 is outside the range of a long; no JVM constant carries it"
        assertTrue(failure("big.json", json(""""value":9223372036854775808""")).contains(wide))
        assertTrue(failure("big.yml", yaml("value: 9223372036854775808")).contains(wide))
        assertTrue(failure("inf.yml", yaml("value: .inf")).contains("is not finite"))
        assertTrue(failure("nan.yml", yaml("args: {0: .nan}, value: 1")).contains("is not finite"))
        assertTrue(failure("inf.json", json(""""value":1e400""")).contains("number 1e400 is not finite"))
        // The limits are the JVM's: a rule scoped to another frontend keeps its own numbers and is
        // skipped before they are read, so a shared file with a Swift UInt64 still loads here.
        val uint64 = "18446744073709551615"
        val swift = """{"match":{"Call":{"name":"a"}},"frontend":"swift","value":$uint64,"args":{"0":1e400}}"""
        val jvm = """{"match":{"CallSite":{"callee_name":"a"}},"value":2}"""
        val shared = FoldConfig.load(file("shared.json", """{"version":1,"folds":[$swift,$jvm]}"""))
        assertEquals(listOf(scalar(2)), shared.map { (it as ConstantFold).value })
        val swiftYaml = "{match: {Call: {name: a}}, frontend: swift, value: $uint64, args: {0: .inf}}"
        val sharedYaml = FoldConfig.load(
            file("shared.yml", "version: 1\nfolds:\n  - $swiftYaml\n  - {match: {CallSite: {callee_name: a}}, value: 2}\n")
        )
        assertEquals(shared, sharedYaml)
        val unscoped = failure("unscoped.json", """{"version":1,"folds":[{"match":{"CallSite":{}},"value":$uint64}]}""")
        assertTrue(unscoped.contains("integer $uint64 is outside the range of a long"), unscoped)
    }
}
