package io.johnsonlee.graphite.input

/**
 * A call-site pattern whose every match is replaced by a constant while the graph is built.
 *
 * Folding happens on the Jimple body before any node exists: the call expression becomes
 * [value], the constant propagates into the branches that test it, the branch folds, the
 * side it rules out becomes unreachable and is removed, and the assignments that only fed
 * the call go with it. A gated block whose gate folds to `false` therefore leaves nothing
 * in the graph, whatever its shape (`if (gate) { ... }`, `if (!gate) return;`,
 * `x = a && gate()`), which no post-hoc view of branch sides can promise.
 *
 * The rule is written in the graph's own vocabulary, the way a Cypher pattern names a node:
 * [callSite] holds the properties of the `CallSite` node to match (`callee_class`,
 * `callee_name`, `callee_signature`, `callee_descriptor`, `caller_class`, `caller_name`,
 * `caller_signature`, `caller_descriptor`, and `ordinal`, which with the two signatures and
 * descriptors names one call site), [arguments] the constant
 * node that must flow into the argument at each index, and
 * [value] the constant node the call becomes. Only calls whose return type can carry the
 * constant are folded; a call whose return type cannot is reported, not changed.
 *
 * ```yaml
 * match:
 *   CallSite: { callee_class: com.example.Flags, callee_name: isEnabled }
 * args:
 *   0: { StringConstant: { value: new_checkout } }
 * value: false
 * ```
 */
data class ConstantFold(
    val callSite: Map<String, String>,
    val arguments: Map<Int, ConstantPattern> = emptyMap(),
    override val value: ConstantPattern
) : FoldRule {
    init {
        val unknown = callSite.keys.firstOrNull { it !in CALL_SITE_PROPERTIES }
        require(unknown == null) {
            val suggestion = Suggest.didYouMean(unknown!!, CALL_SITE_PROPERTIES + CALL_SITE_ALIASES.keys)
            "unknown CallSite property '$unknown'$suggestion; use ${Suggest.list(CALL_SITE_PROPERTIES)}"
        }
        require(callSite.keys.any { it.startsWith("callee_") }) {
            "'$MATCH_KEY.$CALL_SITE_LABEL' does not name the callee; add callee_class, callee_name or callee_signature"
        }
        val blank = callSite.entries.firstOrNull { it.value.isBlank() }?.key
        require(blank == null) { "'$MATCH_KEY.$CALL_SITE_LABEL.$blank' is blank; give it the value to match" }
        require(callSite[ORDINAL]?.let { it.toIntOrNull() != null } != false) {
            "'$MATCH_KEY.$CALL_SITE_LABEL.$ORDINAL' must be an integer, got '${callSite[ORDINAL]}'; " +
                "it counts the calls of the callee in the calling method from 0"
        }
        val negative = arguments.keys.firstOrNull { it < 0 }
        require(negative == null) { "'$ARGS_KEY' index $negative is negative; 0 is the first argument" }
        FoldRule.requireValue(value)
    }

    /** Whether a call with these `CallSite` properties matches; every listed property must, `*` matching any run of characters. */
    fun matchesCallSite(properties: Map<String, String>): Boolean = mismatch(properties) == null

    /** The first rule property a call with these `CallSite` properties fails, `null` when it matches. */
    fun mismatch(properties: Map<String, String>): String? =
        callSite.entries.firstOrNull { (key, pattern) -> properties[key]?.let { Glob.matches(pattern, it) } != true }?.key

    /** The rule in one line, for the terminal. */
    override val description: String
        get() {
            val site = callSite.entries.joinToString(", ") { (key, value) -> "$key: $value" }
            return "CallSite {$site}${describeArguments(arguments, " [")} = ${value.description}"
        }

    /**
     * The Cypher query that previews the rule on a graph built without it: the call sites it
     * would fold, with every argument constant flowing in. The fold pass accepts an argument
     * that is a constant, or a local with one definition that is a constant or a static field
     * read; the graph shows the first as `Constant -> CallSite` and the second as
     * `Constant -> Local -> CallSite`, so the preview walks one or two `DATAFLOW` hops. It stays
     * an approximation: the edge carries no argument index, so a constant pattern matches a
     * constant reaching any argument, and a local with several definitions is not told apart.
     */
    override val cypher: String
        get() {
            val sorted = arguments.entries.sortedBy { it.key }
            // The ordinal is a number in the graph, so the preview compares it as one.
            val site: Map<String, Any?> = callSite.mapValues { (key, value) -> if (key == ORDINAL) value.toInt() else value }
            val nodes = listOf(Cypher.node("cs", "CallSite", site)) +
                sorted.map { (index, pattern) -> Cypher.node("a$index", pattern.label, pattern.properties) + "-[:DATAFLOW*1..2]->(cs)" }
            val where = Cypher.globs("cs", site) + sorted.flatMap { (index, pattern) -> Cypher.globs("a$index", pattern.properties) }
            return "MATCH " + nodes.joinToString(", ") +
                (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
                " RETURN cs.caller_signature AS caller, count(cs) AS calls"
        }

    companion object {
        const val MATCH_KEY = "match"
        const val ARGS_KEY = "args"
        const val VALUE_KEY = FoldRule.VALUE_KEY
        private const val CALL_SITE_LABEL = "CallSite"
        private const val ORDINAL = CallSiteKey.ORDINAL

        /** The properties a `CallSite` node has, as queries name them. */
        val CALL_SITE_PROPERTIES: Set<String> = setOf(
            "callee_class", "callee_name", CallSiteKey.CALLEE, CallSiteKey.CALLEE_DESCRIPTOR,
            "caller_class", "caller_name", CallSiteKey.CALLER, CallSiteKey.CALLER_DESCRIPTOR, ORDINAL
        )

        /** The core names of `docs/architecture-frontend-backend.md`, accepted as aliases. */
        private val CALL_SITE_ALIASES: Map<String, String> = mapOf(
            "callee.owner" to "callee_class", "callee.name" to "callee_name", "callee.signature" to "callee_signature",
            "caller.owner" to "caller_class", "caller.name" to "caller_name", "caller.signature" to "caller_signature"
        )
        private val CALL_SITE_LABELS = setOf(CALL_SITE_LABEL, "CallSiteNode")

        /**
         * Read one rule from its plain document form (`match`, `args`, `value`; maps, lists and
         * scalars as a YAML or JSON reader yields them). Anything that is not a well-formed rule,
         * a rule written for another frontend's node schema included, fails with
         * [IllegalArgumentException]: a rule that reaches this frontend must be one it can apply.
         */
        fun parse(rule: Map<*, *>): ConstantFold {
            val keys = setOf(MATCH_KEY, ARGS_KEY, VALUE_KEY)
            val unknown = rule.keys.firstOrNull { it !in keys }
            require(unknown == null) {
                "unknown key '$unknown'${Suggest.didYouMean(unknown.toString(), keys)}; a rule is $SHAPE, optionally with 'frontend'"
            }
            require(rule.containsKey(MATCH_KEY)) { "'$MATCH_KEY' is missing: name the call site to fold, such as $MATCH_EXAMPLE" }
            require(rule.containsKey(VALUE_KEY)) { "'$VALUE_KEY' is missing: give the constant the call becomes, such as 'value: false'" }
            return ConstantFold(
                callSite = parseCallSite(rule[MATCH_KEY]),
                arguments = parseArguments(rule[ARGS_KEY]),
                value = ConstantPattern.parse(rule[VALUE_KEY], VALUE_KEY)
            )
        }

        private fun parseCallSite(match: Any?): Map<String, String> {
            val labels = match as? Map<*, *>
            require(labels != null && labels.size == 1) {
                "'$MATCH_KEY' must be one labelled node, $MATCH_EXAMPLE, got ${Suggest.kind(match)}"
            }
            val label = labels.keys.single().toString()
            require(label in CALL_SITE_LABELS) {
                "'$MATCH_KEY' names '$label'${Suggest.didYouMean(label, CALL_SITE_LABELS)}; " +
                    "the only node a rule folds is '$CALL_SITE_LABEL'"
            }
            val properties = labels.values.single() as? Map<*, *>
            require(properties != null) {
                "'$MATCH_KEY.$label' must be a map of properties such as {callee_class: com.example.Flags, callee_name: isEnabled}, " +
                    "got ${Suggest.kind(labels.values.single())}"
            }
            val canonical = LinkedHashMap<String, String>()
            val spelled = HashMap<String, Any?>()
            for ((key, value) in properties) {
                val property = CALL_SITE_ALIASES[key] ?: key.toString()
                requireOnce(property !in canonical) { "'$MATCH_KEY.$label' names '$property' twice: '${spelled[property]}' and '$key'" }
                spelled[property] = key
                if (property == ORDINAL && value is Int) {
                    canonical[property] = value.toString()
                    continue
                }
                require(value is String) {
                    "'$MATCH_KEY.$label.$key' must be a string, got ${Suggest.kind(value)}" +
                        (if (value is Boolean) " (YAML reads a bare on, off, yes or no as a boolean: quote it)" else "")
                }
                canonical[property] = value
            }
            return canonical
        }

        /** A key named twice after canonicalisation (an alias beside its name, `0` beside `"0"`) is a contradiction, not a choice. */
        private inline fun requireOnce(once: Boolean, message: () -> String) = require(once) { "${message()}; name it once" }

        /** `[0: "k", 1: 7]` after [prefix], or nothing for no arguments. */
        internal fun describeArguments(arguments: Map<Int, ConstantPattern>, prefix: String): String =
            if (arguments.isEmpty()) "" else arguments.entries.sortedBy { it.key }
                .joinToString(", ", prefix, "]") { (index, pattern) -> "$index: ${pattern.description}" }

        internal fun parseArguments(args: Any?, keyName: String = ARGS_KEY): Map<Int, ConstantPattern> {
            if (args == null) return emptyMap()
            val entries = args as? Map<*, *>
            require(entries != null) {
                "'$keyName' must be a map from argument index to constant, such as {0: new_checkout}, got ${Suggest.kind(args)}"
            }
            val arguments = LinkedHashMap<Int, ConstantPattern>()
            val spelled = HashMap<Int, Any?>()
            for ((key, value) in entries) {
                val index = (key as? Int) ?: key.toString().toIntOrNull()
                require(index != null) { "'$keyName' key '$key' must be an argument index; 0 is the first argument" }
                requireOnce(index !in arguments) { "'$keyName' names argument $index twice: '${spelled[index]}' and '$key'" }
                spelled[index] = key
                arguments[index] = ConstantPattern.parse(value, "$keyName.$key")
            }
            return arguments
        }

        private const val MATCH_EXAMPLE = "match: {CallSite: {callee_class: com.example.Flags, callee_name: isEnabled}}"
        private const val SHAPE = "{match: {CallSite: {<property>: <value>}}, args: {<index>: <constant>}, value: <constant>}"
    }
}

/**
 * One rule of a fold file: which calls become a constant and which constant. A rule is a
 * [ConstantFold], a `match` on the properties of the `CallSite` node, or a [FoldSites], the call
 * sites a Cypher `select` chose on a graph built without rules, named by their stable keys.
 */
sealed interface FoldRule {
    /** The constant the call becomes. */
    val value: ConstantPattern

    /** The rule in one line, for the terminal. */
    val description: String

    /** The Cypher query that shows, on a graph built without rules, the call sites the rule names. */
    val cypher: String

    companion object {
        const val VALUE_KEY = "value"

        private val VALUE_LABELS = setOf(
            ConstantPattern.CONSTANT, ConstantPattern.BOOLEAN, ConstantPattern.INT, ConstantPattern.LONG,
            ConstantPattern.FLOAT, ConstantPattern.DOUBLE, ConstantPattern.STRING, ConstantPattern.NULL, ConstantPattern.ENUM
        )

        internal fun requireValue(value: ConstantPattern) {
            require(value.label in VALUE_LABELS) {
                "'$VALUE_KEY' cannot be ${value.label}: a call becomes a scalar constant, " +
                    "a boolean, number, string or null, or one of ${Suggest.list(VALUE_LABELS - ConstantPattern.CONSTANT)}"
            }
            require(value.label != ConstantPattern.ENUM || value.properties.keys == ENUM_PROPERTIES) {
                "'$VALUE_KEY' as ${ConstantPattern.ENUM} must name both enum_type and name: the constant the call becomes"
            }
        }

        private val ENUM_PROPERTIES = setOf(ConstantPattern.ENUM_TYPE, ConstantPattern.NAME)

        /**
         * Read one rule from its plain document form: a map with `select` is a [FoldSites], any
         * other a [ConstantFold]. Anything that is not a well-formed rule fails with
         * [IllegalArgumentException].
         */
        fun parse(rule: Map<*, *>): FoldRule =
            if (rule.containsKey(FoldSites.SELECT_KEY)) FoldSites.parse(rule) else ConstantFold.parse(rule)
    }
}

/**
 * The stable key of one call site, see `CallSiteNode.ordinal`: the calling method and the callee,
 * each by signature and JVM descriptor, and the rank of the call among the caller's calls of that
 * callee in statement order. They are the `caller_signature`, `caller_descriptor`,
 * `callee_signature`, `callee_descriptor` and `ordinal` properties of the graph's `CallSite`
 * node. The signature leaves the return type out; the descriptor carries it, so a bridge and the
 * covariant override it forwards to, one signature between them, are two callers.
 */
data class CallSiteKey(
    val caller: String,
    val callerDescriptor: String,
    val callee: String,
    val calleeDescriptor: String,
    val ordinal: Int
) {
    /** The key as the fold file writes it. */
    fun toDocument(): Map<String, Any> = linkedMapOf(
        CALLER to caller, CALLER_DESCRIPTOR to callerDescriptor, CALLEE to callee, CALLEE_DESCRIPTOR to calleeDescriptor, ORDINAL to ordinal
    )

    override fun toString(): String = "$caller$callerDescriptor -> $callee$calleeDescriptor #$ordinal"

    companion object {
        const val CALLER = "caller_signature"
        const val CALLER_DESCRIPTOR = "caller_descriptor"
        const val CALLEE = "callee_signature"
        const val CALLEE_DESCRIPTOR = "callee_descriptor"
        const val ORDINAL = "ordinal"

        /** The effective result type a selected call may carry beside its key, see [FoldSites.resultTypes]. */
        const val RESULT_TYPE = "result_type"

        private val KEYS = listOf(CALLER, CALLER_DESCRIPTOR, CALLEE, CALLEE_DESCRIPTOR, ORDINAL)
        private val SHAPE = KEYS.joinToString(", ", "{", "}")

        /**
         * Read a key and the result type beside it, if any, from its document form, failing with
         * [IllegalArgumentException] on anything else.
         */
        fun parse(document: Any?, at: String): Pair<CallSiteKey, String?> {
            val map = document as? Map<*, *>
            require(map != null) { "$at must be a map $SHAPE, got ${Suggest.kind(document)}" }
            val unknown = map.keys.firstOrNull { it !in KEYS && it != RESULT_TYPE }
            require(unknown == null) { "$at has no key '$unknown'; a call site is $SHAPE, optionally with $RESULT_TYPE" }
            val text = listOf(CALLER, CALLER_DESCRIPTOR, CALLEE, CALLEE_DESCRIPTOR).associateWith { key ->
                val value = map[key]
                require(value is String && value.isNotBlank()) {
                    val what = if (key == CALLER || key == CALLEE) "a method signature" else "a JVM method descriptor such as ()Z"
                    "$at.$key must be $what, got ${Suggest.kind(value)}"
                }
                value
            }
            val ordinal = (map[ORDINAL] as? Int) ?: map[ORDINAL]?.toString()?.toIntOrNull()
            require(ordinal != null) { "$at.$ORDINAL must be an integer, got ${Suggest.kind(map[ORDINAL])}" }
            require(ordinal >= 0) {
                "$at.$ORDINAL is $ordinal: a derived call site (a function value's body reached from another call) " +
                    "has no bytecode invoke of its own and cannot be folded; select the call it was resolved from"
            }
            val resultType = map[RESULT_TYPE]
            require(resultType == null || (resultType is String && resultType.isNotBlank())) {
                "$at.$RESULT_TYPE must be a type name such as java.lang.Boolean, got ${Suggest.kind(resultType)}"
            }
            val key = CallSiteKey(
                text.getValue(CALLER), text.getValue(CALLER_DESCRIPTOR), text.getValue(CALLEE), text.getValue(CALLEE_DESCRIPTOR), ordinal
            )
            return key to resultType as String?
        }
    }
}

/**
 * The options and external inputs, besides the input itself, that decide which classes a build
 * reads and so which calls its graph has: the package filters, whether library jars are read and
 * which, and the Android platform jar an APK build reads (by content, not by path). Two builds
 * with the same input and frontend but different identities have different graphs, and a key read
 * off one may name another call, or no call, in the other. Lists are kept sorted so that the
 * order the options were given in does not matter.
 */
data class AnalysisIdentity(
    val includePackages: List<String> = emptyList(),
    val excludePackages: List<String> = emptyList(),
    val includeLibraries: Boolean = false,
    val libraryFilters: List<String> = emptyList(),
    val androidPlatformSha256: String? = null
) {
    init {
        require(androidPlatformSha256 == null || FoldProvenance.isSha256(androidPlatformSha256)) {
            "$ANDROID_PLATFORM_SHA256 must be 64 lowercase hexadecimal digits, got '$androidPlatformSha256'"
        }
    }

    fun toDocument(): Map<String, Any> = linkedMapOf<String, Any>(
        INCLUDE to includePackages.sorted(),
        EXCLUDE to excludePackages.sorted(),
        INCLUDE_LIBS to includeLibraries,
        LIB_FILTER to libraryFilters.sorted()
    ).also { document -> androidPlatformSha256?.let { document[ANDROID_PLATFORM_SHA256] = it } }

    /** The names of the options on which this identity and [other] differ, with both values. */
    fun differences(other: AnalysisIdentity): List<String> = buildList {
        fun lists(name: String, mine: List<String>, theirs: List<String>) {
            if (mine.sorted() != theirs.sorted()) add("$name ${mine.sorted()} vs ${theirs.sorted()}")
        }
        lists(INCLUDE, includePackages, other.includePackages)
        lists(EXCLUDE, excludePackages, other.excludePackages)
        if (includeLibraries != other.includeLibraries) add("$INCLUDE_LIBS $includeLibraries vs ${other.includeLibraries}")
        lists(LIB_FILTER, libraryFilters, other.libraryFilters)
        if (androidPlatformSha256 != other.androidPlatformSha256) {
            add("$ANDROID_PLATFORM_SHA256 ${androidPlatformSha256 ?: "none"} vs ${other.androidPlatformSha256 ?: "none"}")
        }
    }

    override fun equals(other: Any?): Boolean = other is AnalysisIdentity && differences(other).isEmpty()

    override fun hashCode(): Int = toDocument().hashCode()

    companion object {
        const val INCLUDE = "include"
        const val EXCLUDE = "exclude"
        const val INCLUDE_LIBS = "include_libs"
        const val LIB_FILTER = "lib_filter"
        const val ANDROID_PLATFORM_SHA256 = "android_platform_sha256"
        private val KEYS = listOf(INCLUDE, EXCLUDE, INCLUDE_LIBS, LIB_FILTER, ANDROID_PLATFORM_SHA256)

        fun parse(document: Any?, at: String): AnalysisIdentity {
            val map = document as? Map<*, *>
            require(map != null) { "$at must be a map {${KEYS.joinToString()}}, got ${Suggest.kind(document)}" }
            val unknown = map.keys.firstOrNull { it !in KEYS }
            require(unknown == null) { "$at has no key '$unknown'; it is {${KEYS.joinToString()}}" }
            val libs = map[INCLUDE_LIBS] ?: false
            require(libs is Boolean) { "$at.$INCLUDE_LIBS must be true or false, got ${Suggest.kind(libs)}" }
            val sha = map[ANDROID_PLATFORM_SHA256]
            require(sha == null || sha is String) { "$at.$ANDROID_PLATFORM_SHA256 must be a string, got ${Suggest.kind(sha)}" }
            val include = strings(map[INCLUDE], "$at.$INCLUDE")
            val exclude = strings(map[EXCLUDE], "$at.$EXCLUDE")
            val filters = strings(map[LIB_FILTER], "$at.$LIB_FILTER")
            return try {
                AnalysisIdentity(include, exclude, libs, filters, sha as String?)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("$at.${e.message}", e)
            }
        }

        private fun strings(value: Any?, at: String): List<String> {
            if (value == null) return emptyList()
            val list = value as? List<*>
            require(list != null) { "$at must be a list of strings, got ${Suggest.kind(value)}" }
            return list.map { item ->
                require(item is String) { "$at must be a list of strings, got ${Suggest.kind(item)} in it" }
                item
            }
        }
    }
}

/**
 * Where the keys of a resolved [FoldSites] came from: the SHA-256 of the input the query ran on,
 * the version of the frontend that built it, and the [AnalysisIdentity] of that build. A key
 * names one invoke only in the bytecode it was read from: once another call of the same callee
 * is inserted before it, its ordinal names a different invoke, and a frontend that numbers
 * invokes differently names a different one too; and a build that reads other classes (another
 * package filter, other libraries, another platform jar) may hold other calls, so a key selected
 * on the strength of a helper that build had may be applied where the helper is gone. A build
 * folds a key only on the input, frontend and identity it was resolved for; the graphite CLI
 * runs the query again on anything else.
 */
data class FoldProvenance(
    val inputSha256: String,
    val frontendVersion: String,
    val analysis: AnalysisIdentity = AnalysisIdentity()
) {
    init {
        require(isSha256(inputSha256)) { "$INPUT_SHA256 must be 64 lowercase hexadecimal digits, got '$inputSha256'" }
        require(frontendVersion.isNotBlank()) { "$FRONTEND_VERSION is blank" }
    }

    fun toDocument(): Map<String, Any> =
        linkedMapOf(INPUT_SHA256 to inputSha256, FRONTEND_VERSION to frontendVersion, ANALYSIS to analysis.toDocument())

    /** What differs between this provenance and [other], each as `what: this vs other`; empty when nothing does. */
    fun differences(other: FoldProvenance): List<String> = buildList {
        if (inputSha256 != other.inputSha256) add("input sha256 $inputSha256 vs ${other.inputSha256}")
        if (frontendVersion != other.frontendVersion) add("frontend $frontendVersion vs ${other.frontendVersion}")
        addAll(analysis.differences(other.analysis))
    }

    override fun toString(): String = "input sha256 $inputSha256, frontend $frontendVersion and ${analysis.toDocument()}"

    companion object {
        const val INPUT_SHA256 = "input_sha256"
        const val FRONTEND_VERSION = "frontend_version"
        const val ANALYSIS = "analysis"
        private val SHA256 = Regex("[0-9a-f]{64}")

        fun isSha256(value: String): Boolean = SHA256.matches(value)

        fun parse(document: Any?, at: String): FoldProvenance {
            val map = document as? Map<*, *>
            require(map != null) { "$at must be a map {$INPUT_SHA256, $FRONTEND_VERSION, $ANALYSIS}, got ${Suggest.kind(document)}" }
            val unknown = map.keys.firstOrNull { it != INPUT_SHA256 && it != FRONTEND_VERSION && it != ANALYSIS }
            require(unknown == null) { "$at has no key '$unknown'; it is {$INPUT_SHA256, $FRONTEND_VERSION, $ANALYSIS}" }
            val digest = map[INPUT_SHA256]
            val version = map[FRONTEND_VERSION]
            require(digest is String) { "$at.$INPUT_SHA256 must be a string, got ${Suggest.kind(digest)}" }
            require(version is String) { "$at.$FRONTEND_VERSION must be a string, got ${Suggest.kind(version)}" }
            val analysis = map[ANALYSIS]?.let { AnalysisIdentity.parse(it, "$at.$ANALYSIS") } ?: AnalysisIdentity()
            return try {
                FoldProvenance(digest, version, analysis)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("$at.${e.message}", e)
            }
        }
    }
}

/**
 * The call sites a Cypher query selects, folded by key. [select] is the query, written against a
 * graph built without rules and returning `CallSite` nodes; [selected] are the keys of the nodes
 * it returned, `null` while the query has not run, and [provenance] the input and frontend they
 * were read from. `graphite build` (the CLI) resolves it: it builds the graph without rules, runs
 * the query, and hands the frontend the keys. A frontend given an unresolved rule cannot apply it.
 *
 * [resultTypes] gives, for a selected call whose callee returns an erased type (`Supplier.get`,
 * `Function.apply` and Kotlin's `Function1.invoke` return `java.lang.Object`), the type the value
 * really has there: what the function value's body returns, which the CLI reads off the derived
 * call site a query returned. The constant is boxed to that type (`boolean` and
 * `java.lang.Boolean` to `Boolean.TRUE` or `FALSE`, `int` to `Integer.valueOf`), so the cast and
 * unboxing after the call fold with it.
 *
 * ```yaml
 * select: >
 *   MATCH (c:IntConstant {value: 1234})-[:DATAFLOW*]->(cs:CallSite {callee_name: 'getAbTestOption'})
 *   RETURN cs
 * args: {0: 1234}
 * value: { EnumConstant: { enum_type: com.example.ABTestOption, name: CONTROL } }
 * ```
 *
 * The query names the call sites, and every one of them folds, whoever calls the method
 * around it: a `select` rule is an assumption about those calls, not about a key. [arguments]
 * and [receiverArguments] are extra match conditions, held where the call stands: `args: {0:
 * 1234}` on the call's own argument, `receiver_args: {0: 1234}` on the argument of the call
 * that produced the receiver (`Box a = boxed(1234); if (a.isOn())`); a call they cannot be
 * shown to hold on (another constant, a parameter, a field, a call's result) is reported and
 * left alone. A query may follow a key into a helper shared by every key (`gate(key) { return
 * isEnabled(key); }`): selecting the call inside folds the helper for every caller, which the
 * report says.
 */
data class FoldSites(
    val select: String,
    val selected: Set<CallSiteKey>?,
    override val value: ConstantPattern,
    val resultTypes: Map<CallSiteKey, String> = emptyMap(),
    val provenance: FoldProvenance? = null,
    val arguments: Map<Int, ConstantPattern> = emptyMap(),
    val receiverArguments: Map<Int, ConstantPattern> = emptyMap()
) : FoldRule {
    init {
        require(select.isNotBlank()) { "'$SELECT_KEY' is blank; give it the Cypher query that returns the CallSite nodes to fold" }
        require(provenance == null || selected != null) { "'$PROVENANCE_KEY' describes '$SELECTED_KEY', which is missing" }
        val negative = arguments.keys.firstOrNull { it < 0 }
        require(negative == null) { "'$ARGS_KEY' index $negative is negative; 0 is the first argument" }
        val negativeReceiver = receiverArguments.keys.firstOrNull { it < 0 }
        require(negativeReceiver == null) { "'$RECEIVER_ARGS_KEY' index $negativeReceiver is negative; 0 is the first argument" }
        FoldRule.requireValue(value)
    }

    /** Whether the query has run and [selected] holds its call sites. */
    val resolved: Boolean get() = selected != null

    override val description: String
        get() {
            val count = selected?.let { "${it.size} selected call site(s)" } ?: "unresolved select"
            val args = ConstantFold.describeArguments(arguments, " [")
            val receiver = ConstantFold.describeArguments(receiverArguments, " [receiver ")
            return "select {${select.trim().replace(WHITESPACE, " ")}}$args$receiver $count = ${value.description}"
        }

    override val cypher: String get() = select.trim()

    /** [selected] as the file writes it, each key with its result type when it has one. */
    fun selectedDocument(): List<Map<String, Any>>? = selected?.map { key ->
        resultTypes[key]?.let { key.toDocument() + (CallSiteKey.RESULT_TYPE to it) } ?: key.toDocument()
    }

    companion object {
        const val SELECT_KEY = "select"
        const val SELECTED_KEY = "selected"
        const val PROVENANCE_KEY = "provenance"
        const val ARGS_KEY = ConstantFold.ARGS_KEY
        const val RECEIVER_ARGS_KEY = "receiver_args"
        private val WHITESPACE = Regex("\\s+")

        /** Read a rule from its document form (`select`, optional `args`, `selected` and `provenance`, `value`). */
        fun parse(rule: Map<*, *>): FoldSites {
            val keys = setOf(SELECT_KEY, ARGS_KEY, RECEIVER_ARGS_KEY, SELECTED_KEY, PROVENANCE_KEY, FoldRule.VALUE_KEY)
            val unknown = rule.keys.firstOrNull { it !in keys }
            require(unknown == null) {
                "unknown key '$unknown'${Suggest.didYouMean(unknown.toString(), keys)}; a select rule is $SHAPE, optionally with 'frontend'"
            }
            val select = rule[SELECT_KEY]
            require(select is String) { "'$SELECT_KEY' must be a Cypher query, got ${Suggest.kind(select)}" }
            require(rule.containsKey(FoldRule.VALUE_KEY)) {
                "'${FoldRule.VALUE_KEY}' is missing: give the constant the selected calls become, such as 'value: false'"
            }
            val entries = rule[SELECTED_KEY]?.let { document ->
                val list = document as? List<*>
                require(list != null) { "'$SELECTED_KEY' must be a list of call sites, got ${Suggest.kind(document)}" }
                list.mapIndexed { index, entry -> CallSiteKey.parse(entry, "'$SELECTED_KEY[$index]'") }
            }
            val resultTypes = LinkedHashMap<CallSiteKey, String>()
            entries?.forEach { (key, type) ->
                if (type == null) return@forEach
                val previous = resultTypes.put(key, type)
                require(previous == null || previous == type) {
                    "'$SELECTED_KEY' names $key twice with result types $previous and $type; one call returns one type"
                }
            }
            return FoldSites(
                select,
                entries?.mapTo(LinkedHashSet()) { it.first },
                ConstantPattern.parse(rule[FoldRule.VALUE_KEY], FoldRule.VALUE_KEY),
                resultTypes,
                rule[PROVENANCE_KEY]?.let { FoldProvenance.parse(it, "'$PROVENANCE_KEY'") },
                ConstantFold.parseArguments(rule[ARGS_KEY]),
                ConstantFold.parseArguments(rule[RECEIVER_ARGS_KEY], RECEIVER_ARGS_KEY)
            )
        }

        private const val SHAPE = "{select: <Cypher returning CallSite nodes>, args: {<index>: <constant>}, " +
            "receiver_args: {<index>: <constant>}, value: <constant>}"
    }
}

/**
 * What one load folds: the [rules], in order, and where the report of what they did goes.
 * `LoaderConfig.folding`; `null` leaves every body exactly as the frontend's default
 * interceptors do.
 */
data class FoldPlan(
    val rules: List<FoldRule>,
    /** Receives what the rules did once the graph is built. */
    val onReport: ((FoldReport) -> Unit)? = null
) {
    init {
        require(rules.isNotEmpty()) { "a fold plan needs at least one rule" }
    }
}

/**
 * A constant node as a rule names it: a label and the properties to match, the way a Cypher
 * pattern `(:StringConstant {value: "x"})` does. A scalar in the document is the shorthand
 * `Constant {value: <scalar>}`, which matches a constant of any label with that value, and
 * `null` is `NullConstant`. `EnumConstant {enum_type, name}` and `FieldNode {class, name}` (alias
 * `Field`) name a static field read, which is how an enum constant reaches a call.
 */
data class ConstantPattern(val label: String, val properties: Map<String, Any?> = emptyMap()) {
    init {
        val allowed = PROPERTIES[label]
        require(allowed != null) {
            val suggestion = Suggest.didYouMean(label, PROPERTIES.keys + LABEL_ALIASES.keys)
            "unknown constant label '$label'$suggestion; use ${Suggest.list(PROPERTIES.keys)}"
        }
        val unknown = properties.keys.firstOrNull { it !in allowed }
        require(unknown == null) {
            "$label has no property '$unknown'${Suggest.didYouMean(unknown!!, allowed!! + ALIASES.keys)}; " +
                if (allowed!!.isEmpty()) "it takes none" else "it takes ${Suggest.list(allowed)}"
        }
        properties[VALUE]?.let { requireScalar(label, it) }
        properties.filterKeys { it != VALUE }.forEach { (key, value) ->
            require(value is String && value.isNotBlank()) { "$label.$key must be a string, got ${Suggest.kind(value)}" }
        }
    }

    /** The constant [value] of the shorthand form, `null` for every other pattern. */
    val scalar: Any? get() = properties[VALUE]

    /**
     * Whether a constant with this [label] and these [properties] matches: the label, unless any,
     * and every listed property. A `FloatConstant` is compared at `float` precision, so `0.1`
     * matches `0.1f` although the two differ as doubles.
     */
    fun matches(constantLabel: String, constantProperties: Map<String, Any?>): Boolean =
        (label == CONSTANT || label == constantLabel) &&
            properties.all { (key, expected) ->
                constantProperties.containsKey(key) && equal(expected, constantProperties[key], constantLabel == FLOAT)
            }

    val description: String
        get() = when {
            label == NULL -> "null"
            label == CONSTANT && properties.containsKey(VALUE) -> literal(scalar)
            else -> "$label {${properties.entries.joinToString(", ") { (key, value) -> "$key: ${literal(value)}" }}}"
        }

    /** The document form: the scalar shorthand where it says the same, the labelled map otherwise. */
    fun toDocument(): Any? = when {
        label == NULL && properties.isEmpty() -> null
        label == CONSTANT && properties.containsKey(VALUE) -> scalar
        else -> mapOf(label to properties)
    }

    private fun literal(value: Any?): String = if (value is String) "\"$value\"" else value.toString()

    companion object {
        const val VALUE = "value"
        const val CONSTANT = "Constant"
        const val BOOLEAN = "BooleanConstant"
        const val INT = "IntConstant"
        const val LONG = "LongConstant"
        const val FLOAT = "FloatConstant"
        const val DOUBLE = "DoubleConstant"
        const val STRING = "StringConstant"
        const val NULL = "NullConstant"
        const val ENUM = "EnumConstant"
        const val FIELD = "FieldNode"
        const val ENUM_TYPE = "enum_type"
        const val NAME = "name"

        private val PROPERTIES: Map<String, Set<String>> = mapOf(
            CONSTANT to setOf(VALUE), BOOLEAN to setOf(VALUE), INT to setOf(VALUE), LONG to setOf(VALUE),
            FLOAT to setOf(VALUE), DOUBLE to setOf(VALUE), STRING to setOf(VALUE), NULL to emptySet(),
            ENUM to setOf(ENUM_TYPE, NAME), FIELD to setOf("class", NAME)
        )
        private val ALIASES: Map<String, String> = mapOf("constant_type" to ENUM_TYPE, "owner" to "class")
        private val LABEL_ALIASES: Map<String, String> = mapOf("Field" to FIELD)

        /** Read a pattern from its document form: a scalar, `null`, or a one-entry map `{Label: {properties}}`. */
        fun parse(document: Any?, at: String): ConstantPattern = when {
            document == null -> ConstantPattern(NULL)
            document !is Map<*, *> -> ConstantPattern(CONSTANT, mapOf(VALUE to document))
            else -> parseLabelled(document, at)
        }

        private fun parseLabelled(document: Map<*, *>, at: String): ConstantPattern {
            require(document.size == 1) {
                "'$at' must be a scalar, null or one labelled constant such as {StringConstant: {value: new_checkout}}, " +
                    "got a map with ${document.size} keys"
            }
            val (label, body) = document.entries.single()
            val properties = body as? Map<*, *>
            require(properties != null) {
                "'$at.$label' must be a map of properties such as {value: new_checkout}, got ${Suggest.kind(body)}"
            }
            val named = LinkedHashMap<String, Any?>()
            val spelled = HashMap<String, Any?>()
            for ((key, value) in properties) {
                val name = ALIASES[key] ?: key.toString()
                require(name !in named) { "'$at.$label' names '$name' twice: '${spelled[name]}' and '$key'; name it once" }
                spelled[name] = key
                named[name] = value
            }
            return try {
                ConstantPattern(LABEL_ALIASES[label] ?: label.toString(), named)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("'$at': ${e.message}", e)
            }
        }

        /** What each label's `value` may be: the wording for the author and the check. */
        private val SCALARS: Map<String, Pair<String, (Any) -> Boolean>> = mapOf(
            BOOLEAN to ("a boolean" to { value: Any -> value is Boolean }),
            INT to ("an integer" to ::isIntegral),
            LONG to ("an integer" to ::isIntegral),
            FLOAT to ("a number" to ::isNumber),
            DOUBLE to ("a number" to ::isNumber),
            STRING to ("a string" to { value: Any -> value is String }),
            CONSTANT to ("a boolean, number or string" to { value: Any -> value is Boolean || isNumber(value) || value is String })
        )

        private fun isIntegral(value: Any): Boolean = value is Int || value is Long

        private fun isNumber(value: Any): Boolean = isIntegral(value) || value is Double

        private fun requireScalar(label: String, value: Any) {
            val (expected, fits) = SCALARS.getValue(label)
            require(fits(value)) { "$label.$VALUE must be $expected, got ${Suggest.kind(value)}" }
        }

        /** Numbers compare as numbers whatever their width, strings as globs, everything else by equality. */
        private fun equal(expected: Any?, actual: Any?, floatPrecision: Boolean): Boolean = when {
            isNumeric(expected) && isNumeric(actual) -> numbersEqual(expected as Number, actual as Number, floatPrecision)
            expected is String && actual is String -> Glob.matches(expected, actual)
            else -> expected == actual
        }

        private fun isNumeric(value: Any?): Boolean = value is Int || value is Long || value is Float || value is Double

        /**
         * Two integers compare exactly, so a `long` keeps every bit; an integer and a floating-point
         * number are equal when the latter is that integer exactly; two floating-point numbers
         * compare as IEEE 754 does, at `float` precision when [floatPrecision] asks for it, so
         * `-0.0` equals `0.0` and `NaN` equals nothing.
         */
        private fun numbersEqual(a: Number, b: Number, floatPrecision: Boolean): Boolean {
            val aIntegral = a is Int || a is Long
            val bIntegral = b is Int || b is Long
            return when {
                aIntegral && bIntegral -> a.toLong() == b.toLong()
                aIntegral -> integerEqualsFloating(a.toLong(), b)
                bIntegral -> integerEqualsFloating(b.toLong(), a)
                floatPrecision -> floatOf(a) != null && floatOf(a) == floatOf(b)
                else -> a.toDouble() == b.toDouble()
            }
        }

        /**
         * [value] at `float` precision, or `null` when no `float` is it: a finite double past the
         * float range (`1e308`) narrows to an infinity, which would match a constant that is one.
         */
        private fun floatOf(value: Number): Float? = value.toFloat().takeIf { it.isFinite() || !value.toDouble().isFinite() }

        private fun integerEqualsFloating(integer: Long, floating: Number): Boolean {
            val value = floating.toDouble()
            return value.isFinite() && value == Math.floor(value) &&
                value >= Long.MIN_VALUE.toDouble() && value < Long.MAX_VALUE.toDouble() && value.toLong() == integer
        }
    }
}

/** Wording for the validator: what a value is, and the name the author probably meant. */
internal object Suggest {
    private const val MAX_DISTANCE = 3

    fun kind(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> "boolean $value"
        is Int, is Long -> "integer $value"
        is Number -> "number $value"
        is String -> "string '$value'"
        is Map<*, *> -> "a map"
        is List<*> -> "a list"
        else -> value.javaClass.simpleName
    }

    fun list(names: Collection<String>): String {
        val sorted = names.sorted()
        return if (sorted.size < 2) sorted.joinToString() else sorted.dropLast(1).joinToString(", ") + " or " + sorted.last()
    }

    /** ` (did you mean 'x'?)` for the closest of [candidates] within a few edits, else the empty string. */
    fun didYouMean(name: String, candidates: Collection<String>): String {
        val best = candidates.map { it to distance(name.lowercase(), it.lowercase()) }
            .filter { (candidate, distance) -> distance <= MAX_DISTANCE || candidate.lowercase().startsWith(name.lowercase()) }
            .minByOrNull { it.second }
        return best?.let { " (did you mean '${it.first}'?)" } ?: ""
    }

    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1).also { it[0] = i }
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
            }
            previous = current
        }
        return previous[b.length]
    }
}

/** `*` matches any run of characters; a pattern without one is the literal. */
internal object Glob {
    fun isPattern(pattern: String): Boolean = '*' in pattern

    fun matches(pattern: String, text: String): Boolean =
        if (isPattern(pattern)) Regex(regex(pattern)).matches(text) else pattern == text

    /** A regex both this JVM and the Rust engine read: metacharacters escaped one by one, `*` as `.*`. */
    fun regex(pattern: String): String =
        pattern.split('*').joinToString(".*") { segment -> segment.replace(METACHARACTER) { "\\" + it.value } }

    private val METACHARACTER = Regex("""[\\.^$|?+()\[\]{}]""")
}

private object Cypher {
    /** `(alias:Label {exact properties})`; a globbed string property goes to [globs] instead. */
    fun node(alias: String, label: String, properties: Map<String, Any?>): String {
        val exact = properties.filterValues { it !is String || !Glob.isPattern(it) }
        val map = if (exact.isEmpty()) "" else exact.entries.joinToString(", ", " {", "}") { (key, value) -> "$key: ${literal(value)}" }
        return "($alias:$label$map)"
    }

    fun globs(alias: String, properties: Map<String, Any?>): List<String> =
        properties.mapNotNull { (key, value) ->
            (value as? String)?.takeIf(Glob::isPattern)?.let { "$alias.$key =~ ${literal(Glob.regex(it))}" }
        }

    private fun literal(value: Any?): String =
        if (value is String) "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" else value.toString()
}

/** The calls one rule folded in one method. */
data class FoldSite(val method: String, val calls: Int)

/**
 * A method a rule folded in, with its statements before the fold and after the dead code went.
 * The two bracket every fold in the method and the clean-up after all of them, so the difference
 * belongs to the method, not to any one rule: two rules folding in one method share one removal
 * that neither alone would have caused, and splitting it between them would be a number nobody
 * measured.
 */
data class FoldedMethod(val method: String, val statementsBefore: Int, val statementsAfter: Int) {
    val statementsRemoved: Int get() = statementsBefore - statementsAfter
}

/** A call a fold matched but could not replace, with the reason. */
data class UnsupportedFoldSite(val method: String, val reason: String)

/**
 * What one [FoldRule] did across the whole build. [hints] are the near misses, in words:
 * calls whose call site matched but whose arguments did not, with the constants they were
 * seen with, and calls of the named callee whose other properties did not match.
 */
data class FoldOutcome(
    val fold: FoldRule,
    val sites: List<FoldSite>,
    val unsupported: List<UnsupportedFoldSite> = emptyList(),
    val hints: List<String> = emptyList()
) {
    val matched: Int get() = sites.sumOf { it.calls }
}

/** The folds of one build, in rule order, and the [methods] they changed, each once. */
data class FoldReport(val outcomes: List<FoldOutcome>, val methods: List<FoldedMethod> = emptyList()) {
    /** Rules that matched no call at all, the condition a strict build fails on. */
    val unmatched: List<FoldRule> get() = outcomes.filter { it.matched == 0 && it.unsupported.isEmpty() }.map { it.fold }

    /** Statements the folds and the dead code they left removed, over every method, each counted once. */
    val statementsRemoved: Int get() = methods.sumOf { it.statementsRemoved }
}
