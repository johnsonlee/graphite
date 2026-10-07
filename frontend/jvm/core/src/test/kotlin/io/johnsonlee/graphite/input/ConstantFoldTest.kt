package io.johnsonlee.graphite.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConstantFoldTest {

    private val site = mapOf("callee_class" to "com.example.Flags", "callee_name" to "enabled")
    private val valid = mapOf("match" to mapOf("CallSite" to site), "value" to 1)

    private fun pattern(label: String, vararg properties: Pair<String, Any?>) = ConstantPattern(label, properties.toMap())

    private fun labelled(label: String, vararg properties: Pair<String, Any?>) = mapOf(label to properties.toMap())

    private fun rule(match: Map<String, Any?>, value: Any?, args: Map<Any, Any?>? = null): Map<String, Any?> =
        mapOf("match" to mapOf("CallSite" to match), "value" to value) + (args?.let { mapOf("args" to it) } ?: emptyMap())

    private fun failure(rule: Map<*, *>): String = assertFailsWith<IllegalArgumentException> { ConstantFold.parse(rule) }.message!!

    private fun assertFails(rule: Map<*, *>, message: String) {
        val actual = failure(rule)
        assertTrue(actual.contains(message), "expected '$message' in '$actual'")
    }

    @Test
    fun `parse reads the call-site pattern, the argument constants and the value`() {
        val rule = ConstantFold.parse(
            rule(
                mapOf("callee.owner" to "com.example.Flags", "callee_name" to "enabled", "caller.owner" to "com.example.*"),
                value = false,
                args = mapOf(
                    0 to "key",
                    "1" to labelled("LongConstant", "value" to 7),
                    2 to null,
                    3 to labelled("EnumConstant", "constant_type" to "a.Flag", "name" to "X")
                )
            )
        )
        assertEquals(site + ("caller_class" to "com.example.*"), rule.callSite, "core names are aliases of the queryable names")
        assertEquals(pattern("Constant", "value" to "key"), rule.arguments[0], "a scalar is a Constant with that value")
        assertEquals(pattern("LongConstant", "value" to 7), rule.arguments[1])
        assertEquals(pattern("NullConstant"), rule.arguments[2])
        assertEquals(pattern("EnumConstant", "enum_type" to "a.Flag", "name" to "X"), rule.arguments[3])
        assertEquals(pattern("Constant", "value" to false), rule.value)
        assertEquals(false, rule.value.scalar)

        val typed = ConstantFold.parse(mapOf("match" to mapOf("CallSiteNode" to site), "value" to labelled("IntConstant", "value" to 0)))
        assertEquals(pattern("IntConstant", "value" to 0), typed.value)
        assertEquals(emptyMap(), typed.arguments)
        assertEquals(pattern("NullConstant"), ConstantFold.parse(rule(site, value = null)).value)
    }

    @Test
    fun `parse rejects every malformed rule with the place and the problem`() {
        assertFails(valid + ("symbol" to "x"), "unknown key 'symbol'")
        assertFails(mapOf("match" to mapOf("CallSite" to site)), "'value' is missing")
        assertFails(mapOf("value" to 1), "'match' is missing: name the call site to fold, such as match: {CallSite: {callee_class")
        assertFails(
            mapOf("match" to mapOf("Method" to site), "value" to 1),
            "'match' names 'Method'; the only node a rule folds is 'CallSite'"
        )
        assertFails(mapOf("match" to mapOf("callsite" to site), "value" to 1), "'match' names 'callsite' (did you mean 'CallSite'?)")
        assertFails(mapOf("match" to mapOf("CallSite" to site, "Constant" to site), "value" to 1), "'match' must be one labelled node")
        assertFails(
            mapOf("match" to "x", "value" to 1),
            "must be one labelled node, match: {CallSite: {callee_class: com.example.Flags, callee_name: isEnabled}}, got string 'x'"
        )
        assertFails(mapOf("match" to mapOf("CallSite" to "x"), "value" to 1), "'match.CallSite' must be a map")
        assertFails(rule(mapOf("callee_name" to 1), value = 1), "'match.CallSite.callee_name' must be a string, got integer 1")
        assertFails(
            rule(mapOf("callee_name" to true), value = 1),
            "got boolean true (YAML reads a bare on, off, yes or no as a boolean: quote it)"
        )
        assertFails(
            rule(mapOf("line" to "3"), value = 1),
            "unknown CallSite property 'line'; use callee_class, callee_descriptor, callee_name, callee_signature, " +
                "caller_class, caller_descriptor"
        )
        assertFails(rule(mapOf("callee" to "x"), value = 1), "unknown CallSite property 'callee' (did you mean 'callee_name'?)")
        assertFails(
            rule(mapOf("caller_name" to "run"), value = 1),
            "does not name the callee; add callee_class, callee_name or callee_signature"
        )
        assertFails(rule(mapOf("callee_name" to " "), value = 1), "'match.CallSite.callee_name' is blank")
        assertFails(
            rule(site + ("ordinal" to "second"), value = 1),
            "'match.CallSite.ordinal' must be an integer, got 'second'; it counts the calls of the callee in the calling method from 0"
        )
        assertFails(rule(site + ("ordinal" to 1.5), value = 1), "'match.CallSite.ordinal' must be a string, got number 1.5")
        assertFails(
            valid + ("args" to listOf(1)),
            "'args' must be a map from argument index to constant, such as {0: new_checkout}, got a list"
        )
        assertFails(rule(site, 1, mapOf("x" to 1)), "'args' key 'x' must be an argument index; 0 is the first argument")
        assertFails(rule(site, 1, mapOf(-1 to 1)), "'args' index -1 is negative; 0 is the first argument")
        assertFails(
            valid + ("symbol" to "x"),
            "a rule is {match: {CallSite: {<property>: <value>}}, args: {<index>: <constant>}, value: <constant>}"
        )
        assertFails(valid + ("matc" to "x"), "unknown key 'matc' (did you mean 'match'?)")
        val two = labelled("StringConstant", "value" to "a") + labelled("IntConstant", "value" to 1)
        assertFails(
            rule(site, 1, mapOf(0 to two)),
            "'args.0' must be a scalar, null or one labelled constant such as {StringConstant: {value: new_checkout}}, " +
                "got a map with 2 keys"
        )
        assertFails(
            rule(site, 1, mapOf(0 to mapOf("StringConstant" to "a"))),
            "'args.0.StringConstant' must be a map of properties such as {value: new_checkout}, got string 'a'"
        )
        assertFails(
            rule(site, 1, mapOf(0 to labelled("Gate"))),
            "'args.0': unknown constant label 'Gate'; use BooleanConstant, Constant, DoubleConstant"
        )
        assertFails(
            rule(site, 1, mapOf(0 to labelled("String", "value" to "a"))),
            "unknown constant label 'String' (did you mean 'StringConstant'?)"
        )
        assertFails(
            rule(site, 1, mapOf(0 to labelled("StringConstant", "name" to "a"))),
            "StringConstant has no property 'name' (did you mean 'value'?); it takes value"
        )
        assertFails(
            rule(site, 1, mapOf(0 to labelled("NullConstant", "value" to "a"))),
            "NullConstant has no property 'value'; it takes none"
        )
        assertFails(
            rule(site, 1, mapOf(0 to labelled("EnumConstant", "enumtype" to "a"))),
            "EnumConstant has no property 'enumtype' (did you mean 'enum_type'?); it takes enum_type or name"
        )
        assertFails(
            rule(site, 1, mapOf(0 to labelled("StringConstant", "value" to 1))),
            "StringConstant.value must be a string, got integer 1"
        )
        assertFails(
            rule(site, 1, mapOf(0 to labelled("LongConstant", "value" to "7"))),
            "LongConstant.value must be an integer, got string '7'"
        )
        assertFails(
            rule(site, 1, mapOf(0 to labelled("DoubleConstant", "value" to true))),
            "DoubleConstant.value must be a number, got boolean true"
        )
        assertFails(
            rule(site, 1, mapOf(0 to labelled("BooleanConstant", "value" to 1))),
            "BooleanConstant.value must be a boolean, got integer 1"
        )
        assertFails(rule(site, 1, mapOf(0 to labelled("EnumConstant", "name" to 1))), "EnumConstant.name must be a string, got integer 1")
        assertFails(rule(site, 1, mapOf(0 to listOf(1))), "Constant.value must be a boolean, number or string, got a list")
        assertFails(
            rule(site, value = labelled("FieldNode", "class" to "a.B", "name" to "X")),
            "'value' cannot be FieldNode: a call becomes a scalar constant"
        )
        assertFails(rule(site, value = 1.5f), "Constant.value must be a boolean, number or string, got number 1.5")
    }

    @Test
    fun `numbers match exactly as integers and as IEEE 754 floating point`() {
        val wide = pattern("Constant", "value" to 9007199254740992L)
        assertTrue(wide.matches("LongConstant", mapOf("value" to 9007199254740992L)))
        assertFalse(wide.matches("LongConstant", mapOf("value" to 9007199254740993L)), "2^53 + 1 is not 2^53")
        assertTrue(wide.matches("DoubleConstant", mapOf("value" to 9007199254740992.0)), "an integer matches the double that is it")
        assertFalse(pattern("Constant", "value" to 7).matches("DoubleConstant", mapOf("value" to 7.5)))
        assertFalse(pattern("Constant", "value" to 0).matches("DoubleConstant", mapOf("value" to Double.NaN)))
        assertTrue(pattern("Constant", "value" to -0.0).matches("DoubleConstant", mapOf("value" to 0.0)), "-0.0 == 0.0")
        val nan = pattern("Constant", "value" to Double.NaN)
        assertFalse(nan.matches("DoubleConstant", mapOf("value" to Double.NaN)), "NaN matches nothing")
        val tenth = pattern("Constant", "value" to 0.1)
        assertTrue(tenth.matches("FloatConstant", mapOf("value" to 0.1f.toDouble())), "a float is compared at float precision")
        assertFalse(pattern("Constant", "value" to 0.1).matches("DoubleConstant", mapOf("value" to 0.1f.toDouble())))
        assertTrue(pattern("FloatConstant", "value" to 1.5).matches("FloatConstant", mapOf("value" to 1.5)))
        // A finite double past the float range is no float: it must not match the infinity it narrows to.
        val infinity = mapOf("value" to Float.POSITIVE_INFINITY.toDouble())
        assertFalse(pattern("FloatConstant", "value" to 1e308).matches("FloatConstant", infinity), "1e308 is not a float")
        assertFalse(pattern("Constant", "value" to 1e308).matches("FloatConstant", infinity), "nor as a bare scalar")
        assertFalse(pattern("Constant", "value" to 1e308).matches("FloatConstant", mapOf("value" to 1e308)))
    }

    @Test
    fun `constant patterns match by label and property, numbers by value and strings by glob`() {
        val any = pattern("Constant", "value" to 7)
        assertTrue(any.matches("IntConstant", mapOf("value" to 7)))
        assertTrue(any.matches("LongConstant", mapOf("value" to 7L)), "a scalar does not pin the width")
        assertTrue(any.matches("DoubleConstant", mapOf("value" to 7.0)))
        assertFalse(any.matches("IntConstant", mapOf("value" to 8)))
        assertFalse(any.matches("StringConstant", mapOf("value" to "7")))

        val long = pattern("LongConstant", "value" to 7)
        assertTrue(long.matches("LongConstant", mapOf("value" to 7L)))
        assertFalse(long.matches("IntConstant", mapOf("value" to 7)), "a label pins the width")
        assertTrue(pattern("StringConstant").matches("StringConstant", mapOf("value" to "anything")))
        assertFalse(pattern("StringConstant").matches("IntConstant", mapOf("value" to 1)))
        val glob = pattern("StringConstant", "value" to "checkout_*")
        assertTrue(glob.matches("StringConstant", mapOf("value" to "checkout_v2")))
        assertFalse(glob.matches("StringConstant", mapOf("value" to "cart")))
        val on = pattern("BooleanConstant", "value" to true)
        assertTrue(on.matches("BooleanConstant", mapOf("value" to true)))
        assertFalse(on.matches("BooleanConstant", mapOf("value" to false)))
        assertTrue(pattern("NullConstant").matches("NullConstant", emptyMap()))
        assertTrue(pattern("Constant").matches("NullConstant", emptyMap()), "a bare Constant matches every constant")
        val enum = pattern("EnumConstant", "enum_type" to "a.Flag", "name" to "X")
        assertTrue(enum.matches("EnumConstant", mapOf("enum_type" to "a.Flag", "name" to "X")))
        assertFalse(enum.matches("EnumConstant", mapOf("enum_type" to "a.Flag", "name" to "Y")))
        assertFalse(enum.matches("FieldNode", mapOf("class" to "a.Flag", "name" to "X")))
        val field = ConstantPattern.parse(labelled("Field", "owner" to "a.Flag", "name" to "X"), "x")
        assertEquals(pattern("FieldNode", "class" to "a.Flag", "name" to "X"), field, "Field and owner are aliases")
        val named = pattern("EnumConstant", "name" to "X")
        assertFalse(named.matches("EnumConstant", mapOf("enum_type" to "a.Flag")), "a missing property does not match")
    }

    @Test
    fun `call sites match every listed property, with a glob where the rule has one`() {
        val globbed = mapOf("callee_name" to "is*Enabled", "caller_class" to "com.example.checkout.*")
        val rule = ConstantFold(globbed, value = pattern("NullConstant"))
        val call = mapOf("callee_class" to "a.Flags", "callee_name" to "isCheckoutEnabled", "caller_class" to "com.example.checkout.Cart")
        assertTrue(rule.matchesCallSite(call))
        assertFalse(rule.matchesCallSite(call + ("caller_class" to "com.example.cart.Cart")))
        assertFalse(rule.matchesCallSite(call + ("callee_name" to "enabled")))
        assertFalse(rule.matchesCallSite(call - "caller_class"), "a property the call lacks does not match")
        val exact = ConstantFold(mapOf("callee_signature" to "a.Flags.on(int)"), value = pattern("NullConstant"))
        assertTrue(exact.matchesCallSite(mapOf("callee_signature" to "a.Flags.on(int)")))
    }

    @Test
    fun `a rule describes itself for the terminal, renders its document form and its preview query`() {
        val rule = ConstantFold.parse(
            rule(
                mapOf("callee_class" to "com.example.Flags", "callee_name" to "enabled", "caller_class" to "com.example.*"),
                value = false,
                args = mapOf(1 to labelled("StringConstant", "value" to "new_*"), 0 to "key")
            )
        )
        assertEquals(
            "CallSite {callee_class: com.example.Flags, callee_name: enabled, caller_class: com.example.*} " +
                "[0: \"key\", 1: StringConstant {value: \"new_*\"}] = false",
            rule.description
        )
        assertEquals(
            "MATCH (cs:CallSite {callee_class: \"com.example.Flags\", callee_name: \"enabled\"}), " +
                "(a0:Constant {value: \"key\"})-[:DATAFLOW*1..2]->(cs), (a1:StringConstant)-[:DATAFLOW*1..2]->(cs) " +
                "WHERE cs.caller_class =~ \"com\\\\.example\\\\..*\" AND a1.value =~ \"new_.*\" " +
                "RETURN cs.caller_signature AS caller, count(cs) AS calls",
            rule.cypher
        )
        assertEquals("key", rule.arguments[0]!!.toDocument())
        assertEquals(labelled("StringConstant", "value" to "new_*"), rule.arguments[1]!!.toDocument())
        assertEquals(false, rule.value.toDocument())
        assertEquals(null, pattern("NullConstant").toDocument())
        assertEquals("null", pattern("NullConstant").description)
        assertEquals("Constant {}", pattern("Constant").description)

        val plain = ConstantFold(site, value = pattern("IntConstant", "value" to 3))
        assertEquals("CallSite {callee_class: com.example.Flags, callee_name: enabled} = IntConstant {value: 3}", plain.description)
        assertEquals(
            "MATCH (cs:CallSite {callee_class: \"com.example.Flags\", callee_name: \"enabled\"}) " +
                "RETURN cs.caller_signature AS caller, count(cs) AS calls",
            plain.cypher
        )
        val quoted = ConstantFold(mapOf("callee_name" to "say\"hi\""), value = pattern("NullConstant"))
        assertTrue(quoted.cypher.contains("callee_name: \"say\\\"hi\\\"\""), quoted.cypher)
    }

    @Test
    fun `a select rule carries its query, the keys it resolved to and the value`() {
        fun key(caller: String, ordinal: Any) = mapOf(
            "caller_signature" to caller, "caller_descriptor" to "()V",
            "callee_signature" to "a.B.on(java.lang.String)", "callee_descriptor" to "(Ljava/lang/String;)Z", "ordinal" to ordinal
        )
        val provenance = mapOf("input_sha256" to "0f".repeat(32), "frontend_version" to "1.2.3")
        val document = mapOf(
            "select" to "MATCH (cs:CallSite {callee_name: 'on'}) RETURN cs",
            "selected" to listOf(key("a.C.run()", 1), key("a.C.run()", "1"), key("a.C.walk()", 2) + ("result_type" to "java.lang.Boolean")),
            "provenance" to provenance,
            "value" to false
        )
        val rule = FoldRule.parse(document)
        assertTrue(rule is FoldSites)
        rule as FoldSites
        assertTrue(rule.resolved)
        val run = CallSiteKey("a.C.run()", "()V", "a.B.on(java.lang.String)", "(Ljava/lang/String;)Z", 1)
        val walk = CallSiteKey("a.C.walk()", "()V", "a.B.on(java.lang.String)", "(Ljava/lang/String;)Z", 2)
        assertEquals(setOf(run, walk), rule.selected)
        assertEquals(mapOf(walk to "java.lang.Boolean"), rule.resultTypes)
        assertEquals(FoldProvenance("0f".repeat(32), "1.2.3"), rule.provenance)
        assertEquals(provenance + ("analysis" to AnalysisIdentity().toDocument()), rule.provenance!!.toDocument())
        assertEquals(
            "input sha256 ${"0f".repeat(32)}, frontend 1.2.3 and {include=[], exclude=[], include_libs=false, lib_filter=[]}",
            rule.provenance.toString()
        )
        assertEquals(listOf(key("a.C.run()", 1), key("a.C.walk()", 2) + ("result_type" to "java.lang.Boolean")), rule.selectedDocument())
        assertEquals("MATCH (cs:CallSite {callee_name: 'on'}) RETURN cs", rule.cypher)
        assertEquals("select {MATCH (cs:CallSite {callee_name: 'on'}) RETURN cs} 2 selected call site(s) = false", rule.description)
        assertEquals("a.C.run()()V -> a.B.on(java.lang.String)(Ljava/lang/String;)Z #1", run.toString())
        assertEquals(key("a.C.run()", 1), run.toDocument())

        val keyed = FoldRule.parse(
            mapOf("select" to "MATCH (cs:CallSite) RETURN cs", "args" to mapOf(0 to "k", "1" to 7), "value" to 1)
        ) as FoldSites
        assertEquals(mapOf(0 to ConstantPattern.parse("k", "a"), 1 to ConstantPattern.parse(7, "a")), keyed.arguments)
        assertEquals("select {MATCH (cs:CallSite) RETURN cs} [0: \"k\", 1: 7] unresolved select = 1", keyed.description)
        assertEquals(
            "'args' index -1 is negative; 0 is the first argument",
            assertFailsWith<IllegalArgumentException> { keyed.copy(arguments = mapOf(-1 to ConstantPattern.parse(1, "a"))) }.message
        )
        val unresolved = FoldRule.parse(mapOf("select" to "  MATCH (cs:CallSite)\n  RETURN cs  ", "value" to 1)) as FoldSites
        assertFalse(unresolved.resolved)
        assertEquals("MATCH (cs:CallSite)\n  RETURN cs", unresolved.cypher)
        assertEquals("select {MATCH (cs:CallSite) RETURN cs} unresolved select = 1", unresolved.description)
        assertTrue(FoldRule.parse(valid) is ConstantFold)

        assertFailsWith<IllegalArgumentException> { FoldPlan(emptyList()) }
        assertEquals(listOf(unresolved), FoldPlan(listOf(unresolved)).rules)
    }

    @Test
    fun `a provenance carries the build's analysis identity and names what differs`() {
        val sha = "0f".repeat(32)
        val plain = FoldProvenance(sha, "1.2.3")
        val filtered = FoldProvenance(
            sha, "1.2.3", AnalysisIdentity(listOf("b", "a"), listOf("a.x"), true, listOf("*.jar"), "ab".repeat(32))
        )
        // Lists compare as sets: the order the options were given in is not an identity.
        val reordered = AnalysisIdentity(listOf("a", "b"), listOf("a.x"), true, listOf("*.jar"), "ab".repeat(32))
        assertEquals(filtered, FoldProvenance(sha, "1.2.3", reordered))
        assertEquals(
            mapOf(
                "input_sha256" to sha, "frontend_version" to "1.2.3",
                "analysis" to mapOf(
                    "include" to listOf("a", "b"), "exclude" to listOf("a.x"), "include_libs" to true,
                    "lib_filter" to listOf("*.jar"), "android_platform_sha256" to "ab".repeat(32)
                )
            ),
            filtered.toDocument()
        )
        assertEquals(filtered, FoldProvenance.parse(filtered.toDocument(), "p"), "round trip")
        assertEquals(plain, FoldProvenance.parse(mapOf("input_sha256" to sha, "frontend_version" to "1.2.3"), "p"), "no analysis is none")
        assertEquals(
            listOf(
                "include [] vs [a, b]", "exclude [] vs [a.x]", "include_libs false vs true", "lib_filter [] vs [*.jar]",
                "android_platform_sha256 none vs ${"ab".repeat(32)}"
            ),
            plain.differences(filtered)
        )
        assertEquals(listOf("frontend 1.2.3 vs 2.0.0"), plain.differences(FoldProvenance(sha, "2.0.0")))
        assertEquals(emptyList(), filtered.differences(filtered))
        fun malformed(document: Any?) = assertFailsWith<IllegalArgumentException> { FoldProvenance.parse(document, "p") }.message
        assertEquals("p.analysis has no key 'inclde'; it is {include, exclude, include_libs, lib_filter, android_platform_sha256}",
            malformed(mapOf("input_sha256" to sha, "frontend_version" to "1", "analysis" to mapOf("inclde" to listOf("a")))))
        assertEquals("p.analysis.include must be a list of strings, got string 'a'",
            malformed(mapOf("input_sha256" to sha, "frontend_version" to "1", "analysis" to mapOf("include" to "a"))))
        assertEquals("p.analysis.include_libs must be true or false, got string 'yes'",
            malformed(mapOf("input_sha256" to sha, "frontend_version" to "1", "analysis" to mapOf("include_libs" to "yes"))))
        assertEquals("p.analysis.android_platform_sha256 must be 64 lowercase hexadecimal digits, got 'x'",
            malformed(mapOf("input_sha256" to sha, "frontend_version" to "1", "analysis" to mapOf("android_platform_sha256" to "x"))))
        assertEquals("p.analysis must be a map {include, exclude, include_libs, lib_filter, android_platform_sha256}, got integer 1",
            malformed(mapOf("input_sha256" to sha, "frontend_version" to "1", "analysis" to 1)))
        assertEquals("p has no key 'x'; it is {input_sha256, frontend_version, analysis}",
            malformed(mapOf("input_sha256" to sha, "frontend_version" to "1", "x" to 1)))
    }

    @Test
    fun `a malformed select rule says what is wrong`() {
        fun fails(rule: Map<String, Any?>, message: String) {
            val error = assertFailsWith<IllegalArgumentException> { FoldRule.parse(rule) }
            assertTrue(error.message!!.contains(message), "expected '$message' in '${error.message}'")
        }
        fails(mapOf("select" to 1, "value" to 1), "'select' must be a Cypher query, got integer 1")
        fails(mapOf("select" to " ", "value" to 1), "'select' is blank")
        fails(mapOf("select" to "MATCH (cs) RETURN cs"), "'value' is missing: give the constant the selected calls become")
        fails(
            mapOf("select" to "MATCH (cs) RETURN cs", "value" to 1, "args" to listOf(1)),
            "'args' must be a map from argument index to constant"
        )
        fails(mapOf("select" to "MATCH (cs) RETURN cs", "value" to 1, "arg" to mapOf(0 to 1)), "unknown key 'arg' (did you mean 'args'?)")
        val receiver = FoldRule.parse(
            mapOf("select" to "MATCH (cs:CallSite) RETURN cs", "receiver_args" to mapOf(0 to 1234), "value" to false)
        ) as FoldSites
        assertEquals(mapOf(0 to ConstantPattern.parse(1234, "r")), receiver.receiverArguments)
        assertEquals("select {MATCH (cs:CallSite) RETURN cs} [receiver 0: 1234] unresolved select = false", receiver.description)
        fails(
            mapOf("select" to "MATCH (cs) RETURN cs", "value" to 1, "receiver_args" to listOf(1)),
            "'receiver_args' must be a map from argument index to constant"
        )
        fails(
            mapOf("select" to "MATCH (cs) RETURN cs", "value" to 1, "receiver_args" to mapOf(-1 to 1)),
            "'receiver_args' index -1 is negative"
        )
        val typo = mapOf("select" to "MATCH (cs) RETURN cs", "value" to 1, "selectd" to listOf<Any>())
        fails(typo, "unknown key 'selectd' (did you mean 'select'?)")
        val field = mapOf("FieldNode" to mapOf("class" to "a.E", "name" to "A"))
        fails(mapOf("select" to "x", "value" to field), "'value' cannot be FieldNode")
        val partial = mapOf("EnumConstant" to mapOf("name" to "A"))
        fails(mapOf("select" to "x", "value" to partial), "'value' as EnumConstant must name both enum_type and name")
        val enum = mapOf("EnumConstant" to mapOf("enum_type" to "a.E", "name" to "A"))
        val parsed = FoldRule.parse(mapOf("select" to "x", "value" to enum)).value
        assertEquals(ConstantPattern("EnumConstant", mapOf("enum_type" to "a.E", "name" to "A")), parsed)
        fun selected(selected: Any?) = mapOf("select" to "x", "value" to 1, "selected" to selected)
        fails(selected(3), "'selected' must be a list of call sites, got integer 3")
        fails(
            selected(listOf(1)),
            "'selected[0]' must be a map {caller_signature, caller_descriptor, callee_signature, callee_descriptor, ordinal}, got integer 1"
        )
        val key = mapOf(
            "caller_signature" to "a.C.run()", "caller_descriptor" to "()V",
            "callee_signature" to "a.B.on()", "callee_descriptor" to "()Z", "ordinal" to 1
        )
        fails(selected(listOf(key + ("line" to 1))), "'selected[0]' has no key 'line'")
        fails(selected(listOf(key - "caller_signature")), "'selected[0]'.caller_signature must be a method signature, got null")
        val blankCallee = selected(listOf(key + ("callee_signature" to "")))
        fails(blankCallee, "'selected[0]'.callee_signature must be a method signature, got string ''")
        fails(selected(listOf(key - "callee_descriptor")), "'selected[0]'.callee_descriptor must be a JVM method descriptor")
        fails(selected(listOf(key + ("ordinal" to "two"))), "'selected[0]'.ordinal must be an integer, got string 'two'")
        fails(selected(listOf(key + ("result_type" to 3))), "'selected[0]'.result_type must be a type name such as java.lang.Boolean")
        val twoTypes = listOf(key + ("result_type" to "boolean"), key + ("result_type" to "int"))
        fails(selected(twoTypes), "names a.C.run()()V -> a.B.on()()Z #1 twice with result types boolean and int")
        val provenance = mapOf("input_sha256" to "ab".repeat(32), "frontend_version" to "1")
        fails(mapOf("select" to "x", "value" to 1, "provenance" to provenance), "'provenance' describes 'selected', which is missing")
        fun provenance(value: Any?) = selected(listOf(key)) + ("provenance" to value)
        fails(provenance(1), "'provenance' must be a map {input_sha256, frontend_version, analysis}, got integer 1")
        fails(provenance(provenance + ("input" to "x")), "'provenance' has no key 'input'")
        fails(provenance(provenance + ("input_sha256" to 1)), "'provenance'.input_sha256 must be a string, got integer 1")
        fails(provenance(provenance + ("frontend_version" to 1)), "'provenance'.frontend_version must be a string, got integer 1")
        fails(provenance(provenance + ("input_sha256" to "ABC")), "'provenance'.input_sha256 must be 64 lowercase hexadecimal digits")
        fails(provenance(provenance + ("frontend_version" to " ")), "'provenance'.frontend_version is blank")
        // A derived call site (ordinal below zero) is a function value's body resolved from another
        // call: no bytecode invoke carries it, so a plan naming one would fold nothing, silently.
        fails(selected(listOf(key + ("ordinal" to -1))), "'selected[0]'.ordinal is -1: a derived call site")
    }

    @Test
    fun `an ordinal names one call site and is a number in the preview`() {
        val triple = mapOf(
            "caller_signature" to "com.example.Cart.run()",
            "callee_signature" to "com.example.Flags.enabled(java.lang.String)",
            "ordinal" to 2
        )
        val rule = ConstantFold.parse(mapOf("match" to mapOf("CallSite" to triple), "value" to false))
        assertEquals(triple.mapValues { it.value.toString() }, rule.callSite)
        val spelled = ConstantFold.parse(mapOf("match" to mapOf("CallSite" to triple + ("ordinal" to "2")), "value" to false))
        assertEquals(rule.callSite, spelled.callSite)
        assertTrue(rule.matchesCallSite(triple.mapValues { it.value.toString() }))
        assertEquals("ordinal", rule.mismatch(triple.mapValues { it.value.toString() } + ("ordinal" to "3")))
        assertEquals(
            "MATCH (cs:CallSite {caller_signature: \"com.example.Cart.run()\", " +
                "callee_signature: \"com.example.Flags.enabled(java.lang.String)\", ordinal: 2}) " +
                "RETURN cs.caller_signature AS caller, count(cs) AS calls",
            rule.cypher
        )
        val derived = ConstantFold.parse(mapOf("match" to mapOf("CallSite" to (triple + ("ordinal" to -1))), "value" to false))
        assertEquals("-1", derived.callSite["ordinal"])
    }

    @Test
    fun `a report totals its sites and lists the rules nothing matched`() {
        val matched = ConstantFold(site, value = pattern("Constant", "value" to true))
        val unmatched = ConstantFold(mapOf("callee_name" to "off"), value = pattern("Constant", "value" to false))
        val unsupported = ConstantFold(mapOf("callee_name" to "boxed"), value = pattern("Constant", "value" to false))
        val report = FoldReport(
            listOf(
                FoldOutcome(matched, listOf(FoldSite("a.C.run()", 2), FoldSite("a.C.walk()", 1))),
                FoldOutcome(unmatched, emptyList()),
                FoldOutcome(
                    unsupported,
                    emptyList(),
                    listOf(UnsupportedFoldSite("a.C.box()", "return type java.lang.Boolean cannot carry false"))
                )
            ),
            listOf(FoldedMethod("a.C.run()", 10, 4), FoldedMethod("a.C.walk()", 6, 6))
        )
        assertEquals(3, report.outcomes[0].matched)
        // Statements belong to the method: one removal, whatever the number of rules that folded there.
        assertEquals(6, report.methods[0].statementsRemoved)
        assertEquals(6, report.statementsRemoved)
        assertEquals(0, FoldReport(emptyList()).statementsRemoved)
        assertEquals(0, report.outcomes[1].matched)
        assertEquals(listOf(unmatched), report.unmatched, "a rule that matched but could not fold is not unmatched")
        assertEquals(emptyList(), report.outcomes[1].hints)
        assertEquals(listOf("hint"), FoldOutcome(unmatched, emptyList(), hints = listOf("hint")).hints)
    }

    @Test
    fun `suggestions name the closest known word and the kind of a wrong value`() {
        assertEquals(" (did you mean 'callee_class'?)", Suggest.didYouMean("callee_clas", listOf("callee_class", "callee_name")))
        val prefix = Suggest.didYouMean("callee", listOf("callee_class", "callee_name"))
        assertEquals(" (did you mean 'callee_name'?)", prefix, "a prefix counts, the shortest completion first")
        assertEquals("", Suggest.didYouMean("symbol", listOf("match", "args", "value")))
        assertEquals("a or b", Suggest.list(listOf("b", "a")))
        assertEquals("a", Suggest.list(listOf("a")))
        assertEquals("a map", Suggest.kind(emptyMap<String, String>()))
        assertEquals("number 1.5", Suggest.kind(1.5))
        assertEquals("Object", Suggest.kind(Any()))
    }

    @Test
    fun `a key named twice after canonicalisation is refused`() {
        val site = mapOf("callee_name" to "a")
        val aliased = mapOf("CallSite" to mapOf("callee_class" to "a.A", "callee.owner" to "a.B"))
        val twice = failure(mapOf("match" to aliased, "value" to 1))
        assertTrue(twice.contains("'match.CallSite' names 'callee_class' twice: 'callee_class' and 'callee.owner'; name it once"), twice)
        val index = failure(mapOf("match" to mapOf("CallSite" to site), "args" to mapOf(0 to "x", "0" to "y"), "value" to 1))
        assertTrue(index.contains("'args' names argument 0 twice: '0' and '0'; name it once"), index)
        val enum = mapOf("EnumConstant" to mapOf("enum_type" to "a.E", "constant_type" to "a.F", "name" to "X"))
        val property = failure(mapOf("match" to mapOf("CallSite" to site), "value" to enum))
        val expected = "'value.EnumConstant' names 'enum_type' twice: 'enum_type' and 'constant_type'; name it once"
        assertTrue(property.contains(expected), property)
    }
}
