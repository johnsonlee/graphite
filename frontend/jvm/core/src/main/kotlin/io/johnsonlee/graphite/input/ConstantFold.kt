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
 * `callee_name`, `callee_signature`, `caller_class`, `caller_name`, `caller_signature`, and
 * `ordinal`, which with the two signatures names one call site), [arguments] the constant
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
            val args = if (arguments.isEmpty()) "" else arguments.entries.sortedBy { it.key }
                .joinToString(", ", " [", "]") { (index, pattern) -> "$index: ${pattern.description}" }
            return "CallSite {$site}$args = ${value.description}"
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
            "callee_class", "callee_name", CallSiteKey.CALLEE, "caller_class", "caller_name", CallSiteKey.CALLER, ORDINAL
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

        private fun parseArguments(args: Any?): Map<Int, ConstantPattern> {
            if (args == null) return emptyMap()
            val entries = args as? Map<*, *>
            require(entries != null) {
                "'$ARGS_KEY' must be a map from argument index to constant, such as {0: new_checkout}, got ${Suggest.kind(args)}"
            }
            val arguments = LinkedHashMap<Int, ConstantPattern>()
            val spelled = HashMap<Int, Any?>()
            for ((key, value) in entries) {
                val index = (key as? Int) ?: key.toString().toIntOrNull()
                require(index != null) { "'$ARGS_KEY' key '$key' must be an argument index; 0 is the first argument" }
                requireOnce(index !in arguments) { "'$ARGS_KEY' names argument $index twice: '${spelled[index]}' and '$key'" }
                spelled[index] = key
                arguments[index] = ConstantPattern.parse(value, "$ARGS_KEY.$key")
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
 * The stable key of one call site, see `CallSiteNode.ordinal`: the calling method's signature,
 * the callee's, and the rank of the call among the caller's calls of that callee in statement
 * order. The three are the `caller_signature`, `callee_signature` and `ordinal` properties of
 * the graph's `CallSite` node.
 */
data class CallSiteKey(val caller: String, val callee: String, val ordinal: Int) {
    /** The key as the fold file writes it. */
    fun toDocument(): Map<String, Any> = linkedMapOf(CALLER to caller, CALLEE to callee, ORDINAL to ordinal)

    override fun toString(): String = "$caller -> $callee #$ordinal"

    companion object {
        const val CALLER = "caller_signature"
        const val CALLEE = "callee_signature"
        const val ORDINAL = "ordinal"

        /** Read a key from its document form, failing with [IllegalArgumentException] on anything else. */
        fun parse(document: Any?, at: String): CallSiteKey {
            val map = document as? Map<*, *>
            require(map != null) { "$at must be a map {$CALLER, $CALLEE, $ORDINAL}, got ${Suggest.kind(document)}" }
            val unknown = map.keys.firstOrNull { it != CALLER && it != CALLEE && it != ORDINAL }
            require(unknown == null) { "$at has no key '$unknown'; a call site is {$CALLER, $CALLEE, $ORDINAL}" }
            val caller = map[CALLER]
            val callee = map[CALLEE]
            require(caller is String && caller.isNotBlank()) { "$at.$CALLER must be a method signature, got ${Suggest.kind(caller)}" }
            require(callee is String && callee.isNotBlank()) { "$at.$CALLEE must be a method signature, got ${Suggest.kind(callee)}" }
            val ordinal = (map[ORDINAL] as? Int) ?: map[ORDINAL]?.toString()?.toIntOrNull()
            require(ordinal != null) { "$at.$ORDINAL must be an integer, got ${Suggest.kind(map[ORDINAL])}" }
            require(ordinal >= 0) {
                "$at.$ORDINAL is $ordinal: a derived call site (a function value's body reached from another call) " +
                    "has no bytecode invoke of its own and cannot be folded; select the call it was resolved from"
            }
            return CallSiteKey(caller, callee, ordinal)
        }
    }
}

/**
 * The call sites a Cypher query selects, folded by key. [select] is the query, written against a
 * graph built without rules and returning `CallSite` nodes; [selected] are the keys of the nodes
 * it returned, `null` while the query has not run. `graphite build` (the CLI) resolves it: it
 * builds the graph without rules, runs the query, and hands the frontend the keys. A frontend
 * given an unresolved rule cannot apply it.
 *
 * ```yaml
 * select: >
 *   MATCH (c:IntConstant {value: 1234})-[:DATAFLOW*]->(cs:CallSite {callee_name: 'getAbTestOption'})
 *   RETURN cs
 * value: { EnumConstant: { enum_type: com.example.ABTestOption, name: CONTROL } }
 * ```
 */
data class FoldSites(
    val select: String,
    val selected: Set<CallSiteKey>?,
    override val value: ConstantPattern
) : FoldRule {
    init {
        require(select.isNotBlank()) { "'$SELECT_KEY' is blank; give it the Cypher query that returns the CallSite nodes to fold" }
        FoldRule.requireValue(value)
    }

    /** Whether the query has run and [selected] holds its call sites. */
    val resolved: Boolean get() = selected != null

    override val description: String
        get() {
            val count = selected?.let { "${it.size} selected call site(s)" } ?: "unresolved select"
            return "select {${select.trim().replace(WHITESPACE, " ")}} $count = ${value.description}"
        }

    override val cypher: String get() = select.trim()

    companion object {
        const val SELECT_KEY = "select"
        const val SELECTED_KEY = "selected"
        private val WHITESPACE = Regex("\\s+")

        /** Read a rule from its document form (`select`, optional `selected`, `value`). */
        fun parse(rule: Map<*, *>): FoldSites {
            val keys = setOf(SELECT_KEY, SELECTED_KEY, FoldRule.VALUE_KEY)
            val unknown = rule.keys.firstOrNull { it !in keys }
            require(unknown == null) {
                "unknown key '$unknown'${Suggest.didYouMean(unknown.toString(), keys)}; a select rule is $SHAPE, optionally with 'frontend'"
            }
            val select = rule[SELECT_KEY]
            require(select is String) { "'$SELECT_KEY' must be a Cypher query, got ${Suggest.kind(select)}" }
            require(rule.containsKey(FoldRule.VALUE_KEY)) {
                "'${FoldRule.VALUE_KEY}' is missing: give the constant the selected calls become, such as 'value: false'"
            }
            val selected = rule[SELECTED_KEY]?.let { document ->
                val list = document as? List<*>
                require(list != null) { "'$SELECTED_KEY' must be a list of call sites, got ${Suggest.kind(document)}" }
                list.mapIndexed { index, entry -> CallSiteKey.parse(entry, "'$SELECTED_KEY[$index]'") }.toSet()
            }
            return FoldSites(select, selected, ConstantPattern.parse(rule[FoldRule.VALUE_KEY], FoldRule.VALUE_KEY))
        }

        private const val SHAPE = "{select: <Cypher returning CallSite nodes>, value: <constant>}"
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

/** One call site a [ConstantFold] matched: the calling method and how many of its calls folded. */
data class FoldSite(
    val method: String,
    val calls: Int,
    /** Statements of the calling method before the fold and after the dead code went. */
    val statementsBefore: Int,
    val statementsAfter: Int
)

/** A call a fold matched but could not replace, with the reason. */
data class UnsupportedFoldSite(val method: String, val reason: String)

/**
 * What one [ConstantFold] did across the whole build. [hints] are the near misses, in words:
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
    val statementsRemoved: Int get() = sites.sumOf { it.statementsBefore - it.statementsAfter }
}

/** The folds of one build, in rule order. */
data class FoldReport(val outcomes: List<FoldOutcome>) {
    /** Rules that matched no call at all, the condition a strict build fails on. */
    val unmatched: List<FoldRule> get() = outcomes.filter { it.matched == 0 && it.unsupported.isEmpty() }.map { it.fold }
}
