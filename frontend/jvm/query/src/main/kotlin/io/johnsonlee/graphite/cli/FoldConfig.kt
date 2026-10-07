package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.FoldOutcome
import io.johnsonlee.graphite.input.FoldProvenance
import io.johnsonlee.graphite.input.FoldReport
import io.johnsonlee.graphite.input.FoldRule
import io.johnsonlee.graphite.input.FoldSites
import io.johnsonlee.graphite.input.LoaderConfig
import java.io.IOException
import java.io.StringReader
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException

/**
 * The `--fold` file: which calls `graphite build` folds to constants, and the report it writes
 * back in the same shape with what every rule did. A rule is a node pattern in the graph's own
 * vocabulary, the way a Cypher `MATCH` names a node; the file's keys are one contract for every
 * frontend, the labels and property names inside them are the frontend's node schema.
 *
 * ```yaml
 * version: 1
 * folds:
 *   - match:
 *       CallSite: { callee_class: com.example.Flags, callee_name: isEnabled }
 *     args:
 *       0: { StringConstant: { value: new_checkout } }
 *     value: false
 *   - match:
 *       CallSite: { callee_signature: com.example.Flags.limit() }
 *     value: 3
 * ```
 *
 * `match` holds the one `CallSite` label with the properties to match (`callee_class`,
 * `callee_name`, `callee_signature`, `caller_class`, `caller_name`, `caller_signature`; `*`
 * matches any run of characters). `args` maps an argument index to the constant node that must
 * flow into it: a scalar matches any constant with that value, a labelled map
 * (`StringConstant`, `LongConstant`, `EnumConstant {enum_type, name}`, `FieldNode {class, name}`, ...) pins the label.
 * `value` is the constant the call becomes, a scalar or a labelled map alike. `frontend` is
 * optional: a rule naming another frontend is skipped, a rule without one must parse here.
 *
 * A `select` rule, in place of `match` and `args`, is a Cypher query, run on a graph built
 * without rules, whose `CallSite` rows are the calls to fold. The graphite CLI resolves it (`graphite build` builds
 * the graph without rules, runs the query, and hands this frontend the keys of the nodes it
 * returned as `selected: [{caller_signature, caller_descriptor, callee_signature,
 * callee_descriptor, ordinal}]`, with the `provenance` they were read from: the SHA-256 of the
 * input and the version of this frontend); this frontend applies only a resolved rule, and only
 * to the input and frontend version it was resolved for.
 *
 * ```yaml
 * version: 1
 * folds:
 *   - select: >
 *       MATCH (c:IntConstant {value: 1234})-[:DATAFLOW*]->(cs:CallSite {callee_name: 'getAbTestOption'})
 *       RETURN cs
 *     value: { EnumConstant: { enum_type: com.example.ABTestOption, name: CONTROL } }
 * ```
 *
 * JSON (`.json`) and YAML (`.yml`, `.yaml`) carry the same keys. The report adds, per rule,
 * `cypher` (the query that previews the rule on an unfolded graph), `matched` (calls folded),
 * `sites` (one entry per calling method: `caller`, `calls`), `unsupported` (calls the rule
 * matched but could not fold: `caller`, `reason`) and `hints` (the near misses: calls whose call
 * site matched but whose arguments did not, with the constants seen, and calls of the named
 * callee whose other properties did not match). Statements are counted per method, not per
 * rule, beside `folds`: `statementsRemoved` and `methods` (`method`, `statementsBefore`,
 * `statementsAfter`), one entry per method any rule folded in, since the dead code two rules
 * leave in one method is removed by one pass that neither owns.
 */
object FoldConfig {
    const val VERSION = 1
    const val REPORT_FILE = "graph.folds.json"

    /** The frontend this build is; rules scoped to another one are skipped. */
    const val FRONTEND = "jvm"

    private const val VERSION_KEY = "version"
    private val INTEGER_LITERAL = Regex("-?[0-9]+")
    private const val CYPHER_KEY = "cypher"
    private const val MATCHED_KEY = "matched"
    private const val STATEMENTS_REMOVED_KEY = "statementsRemoved"
    private const val METHODS_KEY = "methods"
    private const val SITES_KEY = "sites"
    private const val UNSUPPORTED_KEY = "unsupported"
    private const val HINTS_KEY = "hints"

    /** What the report writes beside each rule; not part of the rule. */
    private val REPORT_KEYS = setOf(CYPHER_KEY, MATCHED_KEY, SITES_KEY, UNSUPPORTED_KEY, HINTS_KEY)

    /** What the report writes beside `folds`; not part of the file. */
    private val REPORT_ROOT_KEYS = setOf(STATEMENTS_REMOVED_KEY, METHODS_KEY)
    private const val FOLDS_KEY = "folds"
    private const val FRONTEND_KEY = "frontend"
    private const val CALLER_KEY = "caller"

    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    /** The rules of the file that apply to this frontend, in order. */
    fun load(path: Path): List<FoldRule> {
        val root = parse(path) as? Map<*, *>
        expect(path, root != null) { "the document must be a map with '$VERSION_KEY' and '$FOLDS_KEY'" }
        val unknown = root!!.keys.firstOrNull {
            it != VERSION_KEY && it != FOLDS_KEY && it != FoldSites.PROVENANCE_KEY && it !in REPORT_ROOT_KEYS
        }
        expect(path, unknown == null) { "unknown key '$unknown'" }
        val version = numbers(path, root[VERSION_KEY])
        expect(path, version == VERSION) { "unsupported version '$version': this build reads version $VERSION" }
        val folds = root[FOLDS_KEY] as? List<*>
        expect(path, folds != null) { "'$FOLDS_KEY' must be a list" }
        return folds!!.mapIndexedNotNull { index, entry -> fold(path, index, entry) }
    }

    /**
     * The rule at [index], or `null` when it is scoped to another frontend. The keys the report
     * adds beside a rule ([REPORT_KEYS]) are dropped first, so `graph.folds.json` reads back as the
     * fold file it describes, with the `select` rules resolved. The rule's numbers are read only
     * once it is known to be this frontend's: the file is shared between frontends, and a Swift
     * rule's `18446744073709551615` is no concern of the JVM's `long`.
     */
    private fun fold(path: Path, index: Int, entry: Any?): FoldRule? {
        val map = (entry as? Map<*, *>)?.filterKeys { it !in REPORT_KEYS }
        expect(path, map != null) {
            "folds[$index] must be a map with '${ConstantFold.MATCH_KEY}' or '${FoldSites.SELECT_KEY}' and '${ConstantFold.VALUE_KEY}'"
        }
        val frontend = map!![FRONTEND_KEY]
        expect(path, frontend == null || frontend is String) { "folds[$index]: '$FRONTEND_KEY' must be a string" }
        if (frontend != null && frontend != FRONTEND) return null
        return try {
            FoldRule.parse(numbers(path, map.filterKeys { it != FRONTEND_KEY }) as Map<*, *>)
        } catch (e: IllegalArgumentException) {
            throw FoldConfigException(path, "folds[$index]: ${e.message}", e)
        }
    }

    /**
     * The rules of [path] that apply to this frontend, validated, as one JSON document in the
     * file's shape (`version: 1`, every rule under `folds` with its `frontend`): what
     * `graphite build` reads to find the `select` rules to resolve, so the file is validated
     * once, here, whatever it was written in.
     *
     * With [input], the plan is for a build of that input: it carries the [provenance] of this
     * input and frontend at its root, for the CLI to record beside the keys it resolves, and a
     * `select` rule resolved for another input or frontend (or with no provenance at all) is
     * handed back unresolved, with a line on [warn], so the CLI runs its query again instead of
     * folding keys that may name other calls here.
     */
    fun plan(
        path: Path,
        input: Path? = null,
        config: LoaderConfig = LoaderConfig(),
        warn: (String) -> Unit = System.err::println
    ): String {
        val current = input?.let { provenance(it, config) }
        val folds = load(path).mapIndexed { index, fold ->
            val stale = (fold as? FoldSites)?.takeIf { current != null }
            val reason = stale?.let { SelectionProvenance.staleness(it, current!!) }
            if (stale == null || reason == null) {
                fold
            } else {
                warn("$path: folds[$index]: $reason; running its query again")
                // Only the cached selection, its result types and its provenance go: the rule's
                // own conditions (`args`, `receiver_args`) narrow the query's answer and stay.
                stale.copy(selected = null, resultTypes = emptyMap(), provenance = null)
            }
        }
        val document = linkedMapOf<String, Any?>(VERSION_KEY to VERSION, FOLDS_KEY to folds.map(::document))
        current?.let { document[FoldSites.PROVENANCE_KEY] = it.toDocument() }
        return gson.toJson(document)
    }

    /** The provenance a key read off a graph of [input] built by this frontend with [config] carries. */
    fun provenance(input: Path, config: LoaderConfig = LoaderConfig()): FoldProvenance = SelectionProvenance.of(input, config)

    /**
     * The message for a resolved `select` rule whose keys were not read off [input] by this
     * frontend, or `null` when every resolved rule was. A key names one invoke only in the
     * bytecode it was read from, so folding it anywhere else may fold another call.
     */
    fun stale(path: Path, folds: List<FoldRule>, input: Path, config: LoaderConfig): String? {
        if (folds.none { it is FoldSites && it.resolved }) return null
        val current = provenance(input, config)
        return folds.withIndex().firstNotNullOfOrNull { (index, fold) ->
            (fold as? FoldSites)?.let { SelectionProvenance.staleness(it, current) }?.let { reason ->
                val copy = "${FoldSites.PROVENANCE_KEY}: ${GsonBuilder().create().toJson(current.toDocument())}"
                "$path: folds[$index]: $reason, where an ordinal may name another call; run `graphite build ... --fold $path`, " +
                    "which runs the query again on this input, or copy '$copy' " +
                    "beside keys you read off a graph of this very input built with these options"
            }
        }
    }

    /** A rule in the file's own shape, [FoldSites.selected] included once the query has run. */
    private fun document(fold: FoldRule): Map<String, Any?> = when (fold) {
        is ConstantFold -> linkedMapOf(
            ConstantFold.MATCH_KEY to mapOf("CallSite" to fold.callSite),
            ConstantFold.ARGS_KEY to fold.arguments.entries.sortedBy { it.key }
                .associate { (index, pattern) -> index.toString() to pattern.toDocument() },
            ConstantFold.VALUE_KEY to fold.value.toDocument(),
            FRONTEND_KEY to FRONTEND
        )
        is FoldSites -> linkedMapOf(
            FoldSites.SELECT_KEY to fold.select,
            FoldSites.ARGS_KEY to fold.arguments.entries.sortedBy { it.key }
                .associate { (index, pattern) -> index.toString() to pattern.toDocument() },
            FoldSites.RECEIVER_ARGS_KEY to fold.receiverArguments.entries.sortedBy { it.key }
                .associate { (index, pattern) -> index.toString() to pattern.toDocument() },
            FoldSites.SELECTED_KEY to fold.selectedDocument(),
            FoldSites.PROVENANCE_KEY to fold.provenance?.toDocument(),
            ConstantFold.VALUE_KEY to fold.value.toDocument(),
            FRONTEND_KEY to FRONTEND
        )
    }

    /**
     * The message for a `select` rule this frontend was handed before its query ran, or `null`
     * when every rule can be applied.
     */
    fun unresolved(path: Path, folds: List<FoldRule>): String? {
        val index = folds.indexOfFirst { it is FoldSites && !it.resolved }
        if (index < 0) return null
        return "$path: folds[$index] is a '${FoldSites.SELECT_KEY}' rule, which the graphite CLI resolves: run " +
            "`graphite build ... --fold $path` (it builds the graph without rules, runs the query on it and passes " +
            "the call sites it selected on), or list them yourself under '${FoldSites.SELECTED_KEY}'"
    }

    private fun expect(path: Path, condition: Boolean, message: () -> String) {
        if (!condition) throw FoldConfigException(path, message())
    }

    private fun parse(path: Path): Any? {
        val text = try {
            Files.readString(path)
        } catch (e: IOException) {
            throw FoldConfigException(path, "cannot read: ${e.message}", e)
        }
        return when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
            "json" -> parseJson(path, text)
            "yml", "yaml" -> parseYaml(path, text)
            else -> throw FoldConfigException(path, "unsupported extension: use .json, .yml or .yaml")
        }
    }

    /**
     * The JSON document as plain maps, lists and scalars, read token by token: a tree parser keeps
     * the last of two members with one name and the duplicate is gone before any check can see
     * it, so an object naming a member twice is refused here, where both are still visible.
     */
    private fun parseJson(path: Path, text: String): Any? = try {
        JsonReader(StringReader(text)).use { reader ->
            val document = plain(path, reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) throw FoldConfigException(path, "not valid JSON: text after the document")
            document
        }
    } catch (e: IOException) {
        throw FoldConfigException(path, "not valid JSON: ${e.message}", e)
    } catch (e: IllegalStateException) {
        throw FoldConfigException(path, "not valid JSON: ${e.message}", e)
    }

    private fun parseYaml(path: Path, text: String): Any? = try {
        Yaml(LoaderOptions().apply { isAllowDuplicateKeys = false }).load<Any?>(text)
    } catch (e: YAMLException) {
        throw FoldConfigException(path, "not valid YAML: ${e.message}", e)
    }

    private fun plain(path: Path, reader: JsonReader): Any? = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val members = LinkedHashMap<String, Any?>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name in members) throw FoldConfigException(path, "not valid JSON: object names member '$name' twice; name it once")
                members[name] = plain(path, reader)
            }
            reader.endObject()
            members
        }
        JsonToken.BEGIN_ARRAY -> {
            val items = ArrayList<Any?>()
            reader.beginArray()
            while (reader.hasNext()) items.add(plain(path, reader))
            reader.endArray()
            items
        }
        JsonToken.NULL -> null.also { reader.nextNull() }
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> Numeral(reader.nextString())
        else -> throw FoldConfigException(path, "not valid JSON: unexpected ${reader.peek()} at ${reader.path}")
    }

    /** A JSON number as the file spells it, read by [number] once the rule holding it is known to be this frontend's. */
    private class Numeral(val text: String)

    /**
     * A number as the file spells it: an integer literal is an `Int` or a `Long`, exactly or not
     * at all (no JVM constant is wider than a `long`, and a value that silently wrapped would fold
     * a call to a constant nobody wrote); a literal with a fraction or an exponent is a `Double`,
     * finite or not at all, in JSON as in YAML, so the two spellings of one file read the same.
     */
    private fun number(path: Path, text: String): Any =
        if (INTEGER_LITERAL.matches(text)) integer(path, BigInteger(text)) else finite(path, text.toDouble(), text)

    private fun integer(path: Path, value: BigInteger): Any = when {
        value.bitLength() < Int.SIZE_BITS -> value.toInt()
        value.bitLength() < Long.SIZE_BITS -> value.toLong()
        else -> throw FoldConfigException(path, "integer $value is outside the range of a long; no JVM constant carries it")
    }

    private fun finite(path: Path, value: Double, text: String): Double =
        if (value.isFinite()) value else throw FoldConfigException(path, "number $text is not finite; no JVM constant carries it")

    /**
     * [value] with every number read: a JSON [Numeral] as [number] reads it, a YAML number (SnakeYAML
     * has already typed it) checked the same way. Applied per rule, after the rule's frontend is known.
     */
    private fun numbers(path: Path, value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { (key, entry) -> numbers(path, key) to numbers(path, entry) }
        is List<*> -> value.map { numbers(path, it) }
        is Numeral -> number(path, value.text)
        is BigInteger -> integer(path, value)
        is Double -> finite(path, value, value.toString())
        is Float -> finite(path, value.toDouble(), value.toString())
        else -> value
    }

    /** The report in the file's own shape, see the class comment. */
    fun render(report: FoldReport): String {
        val folds = report.outcomes.map { outcome ->
            val fold = outcome.fold
            document(fold) + linkedMapOf(
                CYPHER_KEY to fold.cypher,
                MATCHED_KEY to outcome.matched,
                SITES_KEY to outcome.sites.map { linkedMapOf(CALLER_KEY to it.method, "calls" to it.calls) },
                UNSUPPORTED_KEY to outcome.unsupported.map { linkedMapOf(CALLER_KEY to it.method, "reason" to it.reason) },
                HINTS_KEY to outcome.hints
            )
        }
        val methods = report.methods.map {
            linkedMapOf("method" to it.method, "statementsBefore" to it.statementsBefore, "statementsAfter" to it.statementsAfter)
        }
        return gson.toJson(
            linkedMapOf(
                VERSION_KEY to VERSION,
                FOLDS_KEY to folds,
                STATEMENTS_REMOVED_KEY to report.statementsRemoved,
                METHODS_KEY to methods
            )
        )
    }

    /**
     * The terminal account: one line per rule and, under a rule that folded nothing, a warning
     * with the near misses and the query that shows what the rule would need to match; then the
     * statements removed, once for the build, over the methods the rules folded in.
     */
    fun summary(report: FoldReport): List<String> = report.outcomes.flatMap { outcome ->
        val unsupported = if (outcome.unsupported.isEmpty()) "" else ", ${outcome.unsupported.size} unsupported"
        val line = "fold ${outcome.fold.description}: ${outcome.matched} call(s) in ${outcome.sites.size} method(s)$unsupported"
        if (outcome.matched > 0) listOf(line) else listOf(line) + warnings(outcome)
    } + when {
        report.outcomes.isEmpty() -> emptyList()
        report.outcomes.all { it.matched == 0 } ->
            listOf("Warning: no fold rule folded any call; the graph is the same as a build without --fold")
        else -> listOf("folds removed ${report.statementsRemoved} statement(s) in ${report.methods.size} method(s)")
    }

    private fun warnings(outcome: FoldOutcome): List<String> {
        val selected = (outcome.fold as? FoldSites)?.selected
        val why = when {
            outcome.unsupported.isNotEmpty() -> "every call it matched is unsupported: " + outcome.unsupported.first().reason
            selected != null && selected.isEmpty() -> "the query selected no call site"
            selected != null -> "none of the ${selected.size} selected call site(s) is in this build:"
            outcome.hints.isEmpty() -> "no call site has these properties"
            else -> "no call matched; the nearest are:"
        }
        return listOf("  warning: this rule folded nothing, $why") +
            outcome.hints.map { "    $it" } +
            "    preview on a built graph: ${outcome.fold.cypher}"
    }
}

class FoldConfigException(path: Path, message: String, cause: Throwable? = null) :
    IllegalArgumentException("$path: $message", cause)
