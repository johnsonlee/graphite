package io.johnsonlee.graphite.sootup

import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import sootup.core.jimple.common.stmt.BranchingStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.model.ClassModifier
import sootup.core.model.MethodModifier
import sootup.core.model.SootClass
import sootup.core.model.SootMethod

/**
 * Stable identity for compiler-numbered synthetic members.
 *
 * javac, kotlinc and D8 name lambdas, anonymous classes, accessors and outlines by position
 * (`lambda$run$0`, `Foo$1`, `run$lambda$2`, `access$000`, `Foo$$ExternalSyntheticLambda3`), so
 * inserting or removing a sibling renumbers everything after it and two builds of the same
 * source disagree on the names of members that did not change. The fingerprint computed here
 * depends on what a member is, not on where its compiler counted it:
 *
 * - the member's kind and its name with the ordinal removed (`sample.Foo.lambda$run$`);
 * - its descriptor, modifiers, and for a class its supertypes and fields;
 * - a canonical rendering of every statement in its body: locals renumbered by first use,
 *   no line numbers, branch and exception targets as statement indices;
 * - references to other numbered members replaced by their fingerprints (members that
 *   reference each other, such as a lambda class and its outer method, use each other's
 *   stripped names instead), so a stable member keeps its identity even when the lambdas it
 *   calls are renumbered.
 *
 * Members whose fingerprints still coincide (identical lambdas in one method) are told apart by
 * their order of appearance: the first keeps the shared fingerprint, the others carry an ordinal.
 *
 * Only members that match [isSyntheticClass] or [isSyntheticMethod] (the `ACC_SYNTHETIC` flag
 * or a numbered name whose ordinal is purely numeric) get a fingerprint; everything else has a
 * stable name already. Renamed or merged members (R8 horizontal class merging, minification)
 * are out of scope: lint runs on unminified builds.
 */
internal object SyntheticIdentity {

    /**
     * A purely numeric `$`-separated segment, with D8's prefix: `Foo$1`, `Foo$bar$1`, `Foo$1$Local`,
     * `Foo$$ExternalSyntheticLambda0`, `Foo$$Lambda$12`.
     */
    private val CLASS_ORDINAL = Regex("""(?<=\$)(?:""" + "ExternalSynthetic" + """[A-Za-z]+?)?(\d+)(?=\$|$)""")

    /** `lambda$run$0` (javac), `run$lambda$0` / `run$lambda-0` (kotlinc), `access$000` (javac). */
    private val METHOD_ORDINAL = Regex("""^(?:lambda\$[^$]*\$|.*\$""" + "lambda" + """[-$]|access\$)(\d+)$""")

    /** A Jimple method signature as SootUp prints it: `<cls: ret name(params)>`. */
    private val METHOD_SIGNATURE = Regex("""<([^\s<>:]+): [^\s<>]+ ([^\s<>(]+)\([^<>()]*\)>""")

    private const val FINGERPRINT_BYTES = 16

    fun isSyntheticClass(sootClass: SootClass): Boolean =
        ClassModifier.SYNTHETIC in sootClass.modifiers || hasClassOrdinal(sootClass.type.fullyQualifiedName)

    fun isSyntheticMethod(method: SootMethod): Boolean =
        MethodModifier.SYNTHETIC in method.modifiers || hasMethodOrdinal(method.name)

    fun hasClassOrdinal(className: String): Boolean = CLASS_ORDINAL.containsMatchIn(className)

    fun hasMethodOrdinal(methodName: String): Boolean = METHOD_ORDINAL.matches(methodName)

    /** `sample.Foo$1$2` -> `sample.Foo$$`: every ordinal segment removed, enclosing ones included. */
    fun stripClassOrdinals(className: String): String =
        CLASS_ORDINAL.replace(className) { it.value.removeSuffix(it.groupValues[1]) }

    /** `lambda$run$0` -> `lambda$run$`; a name without an ordinal is returned unchanged. */
    fun stripMethodOrdinal(methodName: String): String {
        val match = METHOD_ORDINAL.find(methodName) ?: return methodName
        return methodName.removeRange(match.groups[1]!!.range)
    }

    /** An identifier as printed Jimple spells it: letters, digits, `_`, `$`, qualified with `.`. */
    private val IDENTIFIER = Regex("""[\p{L}\p{N}_$][\p{L}\p{N}_$.]*""")

    /** Every numbered class name in [text] with its ordinals removed; other text is kept. */
    fun stripClassOrdinalsIn(text: String): String =
        IDENTIFIER.replace(text) { match -> stripClassOrdinals(match.value) }

    /** The ordinal a compiler assigned, for ordering members whose fingerprints coincide. */
    private fun ordinalOf(name: String): Long =
        (METHOD_ORDINAL.find(name) ?: CLASS_ORDINAL.findAll(name).lastOrNull())?.groupValues?.get(1)?.toLongOrNull() ?: -1L

    /** Hex encoding of the first [FINGERPRINT_BYTES] bytes of SHA-256 over [text]. */
    fun fingerprint(text: String): String =
        hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)), FINGERPRINT_BYTES)

    private const val HEX_DIGITS = "0123456789abcdef"
    private const val BYTE_MASK = 0xFF
    private const val NIBBLE_MASK = 0xF
    private const val NIBBLE_BITS = 4

    private fun hex(bytes: ByteArray, count: Int = bytes.size): String {
        val chars = CharArray(count * 2)
        for (index in 0 until count) {
            val value = bytes[index].toInt() and BYTE_MASK
            chars[index * 2] = HEX_DIGITS[value ushr NIBBLE_BITS]
            chars[index * 2 + 1] = HEX_DIGITS[value and NIBBLE_MASK]
        }
        return String(chars)
    }

    /**
     * A reference to another numbered member inside a rendering, resolved when fingerprints are
     * computed: [name] is the printed form that names it, [stripped] that form without ordinals,
     * used when the member is unknown (outside the loaded packages) or in the same component.
     */
    private class Reference(val name: String, val stripped: String)

    private class Member(
        /** Graph key: the class name, or the method signature as the graph indexes it. */
        val key: String,
        /** The compiler-assigned name, for ordering coinciding fingerprints. */
        val originalName: String,
        /** The name other renderings refer to this member by: class name or printed Jimple signature. */
        val referenceName: String,
        val stripped: String
    ) {
        /**
         * Running digest of the rendering's literal text, with a marker where each reference
         * sits; the references themselves are in [references], in order. Keeping a digest instead
         * of the text bounds the memory held until [Collector.resolve] to a few hundred bytes per
         * member whatever the body size.
         */
        val literal: MessageDigest = MessageDigest.getInstance("SHA-256")
        val references = ArrayList<Reference>()
        var index = -1
        var lowLink = -1
        var onStack = false
        var component = -1
        var fingerprint: String? = null
        /** Finalised once, when fingerprints are computed. */
        val literalDigestHex: String by lazy { hex(literal.digest()) }

        /** The digest so far without ending it; a method's whole rendering is in by the time this is read. */
        fun literalSnapshot(): ByteArray = (literal.clone() as MessageDigest).digest()
    }

    /**
     * Accumulates the renderings of the synthetic members met while the graph is built and
     * computes their fingerprints once every member is known. Not thread safe: graph construction
     * is sequential.
     */
    internal class Collector {
        // Everything below the queue belongs to the worker thread until resolve() joins it: rendering
        // and hashing bodies is a few seconds per large corpus, which would otherwise land on the
        // single-threaded graph build. The caller only reads the SootUp model (class headers on its
        // own thread, since class resolution is lazy) and hands immutable methods over.
        private val queue = ArrayBlockingQueue<Runnable>(QUEUE_CAPACITY)
        private var worker: Thread? = null

        /** Members whose rendering failed and that therefore have no fingerprint. */
        var skipped: Int = 0
            private set

        private val members = ArrayList<Member>()
        private val membersByReference = HashMap<String, Member>()
        private val classMembers = HashMap<String, Member>()

        /** Begin a synthetic class: header, supertypes and fields; its methods follow through [addMethod]. */
        fun addClass(sootClass: SootClass) {
            val className = sootClass.type.fullyQualifiedName
            val stripped = stripClassOrdinals(className)
            val header = StringBuilder()
                .append("class ").append(stripped)
                .append(" modifiers=").append(sootClass.modifiers.map { it.name }.sorted().joinToString(","))
                .append(" super=").append(sootClass.superclass.map { it.fullyQualifiedName }.orElse("none"))
                .append(" interfaces=").append(sootClass.interfaces.map { it.fullyQualifiedName }.sorted().joinToString(","))
            for (field in sootClass.fields.sortedBy { it.name }) {
                header.append("\nfield ").append(field.modifiers.map { it.name }.sorted().joinToString(","))
                    .append(' ').append(field.type).append(' ').append(field.name)
            }
            header.append('\n')
            submit {
                val member = Member(key = className, originalName = className, referenceName = className, stripped = stripped)
                tokenize(header.toString(), emptySet(), member)
                register(member)
                classMembers[className] = member
            }
        }

        /**
         * Render [method]. A method of a synthetic class is folded into the class rendering; a
         * synthetic method also becomes a member of its own under [key]. The body, when the method
         * has one, is rendered once and shared.
         */
        fun addMethod(method: SootMethod, key: String, syntheticMethod: Boolean) {
            // The body is materialised by the caller; the worker only reads the immutable method.
            submit { renderMethod(method, key, syntheticMethod) }
        }

        private fun renderMethod(method: SootMethod, key: String, syntheticMethod: Boolean) {
            val className = method.declaringClassType.fullyQualifiedName
            val classMember = classMembers[className]
            if (classMember == null && !syntheticMethod) return
            val stripped = stripClassOrdinals(className) + "." + stripMethodOrdinal(method.name)
            val text = StringBuilder()
                .append("method ").append(stripped)
                .append('(').append(method.parameterTypes.joinToString(",")).append(')')
                .append(method.returnType)
                .append(" modifiers=").append(method.modifiers.map { it.name }.sorted().joinToString(","))
                .append('\n')
            if (method.hasBody()) {
                renderBody(method, text)
            } else {
                text.append("no body\n")
            }
            val rendering = text.toString()
            val locals = if (method.hasBody()) method.body.locals.mapTo(HashSet()) { it.name } else emptySet()
            val member = if (syntheticMethod) {
                Member(
                    key = key,
                    originalName = method.name,
                    referenceName = method.signature.toString(),
                    stripped = stripped
                ).also(::register)
            } else {
                checkNotNull(classMember)
            }
            tokenize(rendering, locals, member)
            if (syntheticMethod && classMember != null) {
                // A synthetic method of a synthetic class is tokenized once: the class takes the
                // method's literal digest and references rather than scanning the text again.
                classMember.literal.update(member.literalSnapshot())
                classMember.references.addAll(member.references)
            }
        }

        private fun submit(task: () -> Unit) {
            if (worker == null) {
                worker = Thread(::drain, "graphite-synthetic-identity").apply {
                    isDaemon = true
                    start()
                }
            }
            queue.put(Runnable(task))
        }

        private fun drain() {
            while (true) {
                val task = queue.take()
                if (task === STOP) return
                try {
                    task.run()
                } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Throwable) {
                    // A member that cannot be rendered has no fingerprint; the graph build goes on
                    // and the adapter logs the count. Catching Throwable keeps the worker alive, or
                    // the caller would block on a full queue.
                    skipped++
                }
            }
        }

        /** Compute every fingerprint once the worker has drained; returns graph key to fingerprint. */
        fun resolve(): Map<String, String> {
            worker?.let { thread ->
                queue.put(STOP)
                thread.join()
                worker = null
            }
            if (members.isEmpty()) return emptyMap()
            // Dependencies first: a reference outside a member's component is already fingerprinted.
            val components = stronglyConnectedComponents()
            for (component in components) {
                for (member in component) {
                    member.fingerprint = fingerprint(canonicalText(member))
                }
            }
            disambiguateCoincidingFingerprints()
            return members.associate { it.key to it.fingerprint!! }
        }

        private fun register(member: Member) {
            members.add(member)
            membersByReference[member.referenceName] = member
        }

        /** The literal digest followed by every reference as resolved: fingerprint, stripped name in a cycle, or stripped text. */
        private fun canonicalText(member: Member): String {
            val text = StringBuilder(member.literalDigestHex)
            for (reference in member.references) {
                val target = membersByReference[reference.name]
                text.append('{')
                when {
                    target == null -> text.append(reference.stripped)
                    target.component == member.component -> text.append(target.stripped)
                    else -> text.append(target.fingerprint)
                }
                text.append('}')
            }
            return text.toString()
        }

        private fun disambiguateCoincidingFingerprints() {
            members.groupBy { it.fingerprint!! }.values.forEach { group ->
                if (group.size < 2) return@forEach
                group.sortedWith(compareBy<Member>({ it.stripped }, { ordinalOf(it.originalName) }, { it.originalName }))
                    .forEachIndexed { ordinal, member ->
                        if (ordinal > 0) member.fingerprint = fingerprint("${member.fingerprint}#$ordinal")
                    }
            }
        }

        /** Tarjan's algorithm; components come out with dependencies before dependents. */
        private fun stronglyConnectedComponents(): List<List<Member>> {
            val components = ArrayList<List<Member>>()
            val stack = ArrayList<Member>()
            var nextIndex = 0
            fun visit(member: Member) {
                member.index = nextIndex
                member.lowLink = nextIndex
                nextIndex++
                stack.add(member)
                member.onStack = true
                for (reference in member.references) {
                    val target = membersByReference[reference.name] ?: continue
                    if (target.index < 0) {
                        visit(target)
                        member.lowLink = minOf(member.lowLink, target.lowLink)
                    } else if (target.onStack) {
                        member.lowLink = minOf(member.lowLink, target.index)
                    }
                }
                if (member.lowLink == member.index) {
                    val component = ArrayList<Member>()
                    val componentIndex = components.size
                    while (true) {
                        val popped = stack.removeAt(stack.size - 1)
                        popped.onStack = false
                        popped.component = componentIndex
                        component.add(popped)
                        if (popped === member) break
                    }
                    components.add(component)
                }
            }
            for (member in members) {
                if (member.index < 0) visit(member)
            }
            return components
        }

        private fun renderBody(method: SootMethod, text: StringBuilder) {
            val body = method.body
            val graph = body.stmtGraph
            val statements = body.stmts
            val indices = java.util.IdentityHashMap<Stmt, Int>(statements.size)
            statements.forEachIndexed { index, stmt -> indices[stmt] = index }
            statements.forEachIndexed { index, stmt ->
                text.append(index).append(": ").append(stmt.toString())
                if (stmt is BranchingStmt) {
                    text.append(" ->")
                    for (target in graph.getBranchTargetsOf(stmt)) {
                        text.append(' ').append(indices[target] ?: -1)
                    }
                }
                val handlers = graph.exceptionalSuccessors(stmt)
                if (handlers.isNotEmpty()) {
                    text.append(" ^")
                    handlers.entries
                        .map { (type, handler) -> "${type.fullyQualifiedName}:${indices[handler] ?: -1}" }
                        .sorted()
                        .joinTo(text, ",")
                }
                text.append('\n')
            }
        }

        private val signatureMatcher = METHOD_SIGNATURE.toPattern().matcher("")

        /** One [Reference] per printed name: the same lambda or class is referenced many times. */
        private val referencesByName = HashMap<String, Reference>()

        private fun classReference(name: String): Reference =
            referencesByName.getOrPut(name) { Reference(name, stripClassOrdinals(name)) }

        /** Tokenizer state for one rendering: the literal run not yet digested and the local renames. */
        private class Scan(val member: Member, val locals: Set<String>, length: Int) {
            val literal = StringBuilder(length)
            val renames = HashMap<String, String>()

            fun reference(reference: Reference) {
                literal.append(REFERENCE_MARK)
                member.references.add(reference)
            }

            fun flush() {
                member.literal.update(literal.toString().toByteArray(Charsets.UTF_8))
                literal.setLength(0)
            }
        }

        /**
         * Split [text] into literal runs, locals renumbered by first appearance and [Reference]s
         * to numbered members, feeding the literals to the member's digest. A printed method
         * signature is matched first so a numbered method is one reference rather than a class
         * token followed by a name. Hand-rolled scanning: this runs over every statement of
         * every synthetic member, and a regex per character was a measurable share of graph build.
         */
        private fun tokenize(text: String, locals: Set<String>, member: Member) {
            val scan = Scan(member, locals, text.length)
            val matcher = signatureMatcher.reset(text)
            val length = text.length
            var position = 0
            while (position < length) {
                val char = text[position]
                position = when {
                    char == '"' -> quotedToken(scan, text, position)
                    char == '<' && matcher.region(position, length).lookingAt() -> signatureToken(scan, matcher)
                    isIdentifierChar(char) -> identifierToken(scan, text, position)
                    else -> {
                        scan.literal.append(char)
                        position + 1
                    }
                }
            }
            scan.flush()
        }

        /**
         * A quoted string constant is literal text: the characters inside it, escapes included, are
         * copied verbatim, never read as locals or member references.
         */
        private fun quotedToken(scan: Scan, text: String, position: Int): Int {
            val length = text.length
            var end = position + 1
            while (end < length) {
                val char = text[end]
                end++
                if (char == '\\' && end < length) {
                    end++
                } else if (char == '"') {
                    break
                }
            }
            scan.literal.append(text, position, end)
            return end
        }

        /**
         * The signature the matcher sits on becomes one reference when its method is numbered,
         * resolved to that method's fingerprint. Any other signature is scanned from its `<` on
         * like ordinary text, so a numbered owner or a numbered parameter or return type becomes
         * a class reference of its own and resolves to that class's fingerprint.
         */
        private fun signatureToken(scan: Scan, matcher: java.util.regex.Matcher): Int {
            val methodName = matcher.group(2)
            if (!(mayCarryOrdinal(methodName) && hasMethodOrdinal(methodName))) {
                scan.literal.append('<')
                return matcher.start() + 1
            }
            val value = matcher.group()
            scan.reference(referencesByName.getOrPut(value) {
                val offset = matcher.start()
                val methodRange = (matcher.start(2) - offset) until (matcher.end(2) - offset)
                Reference(value, stripClassOrdinalsIn(value.replaceRange(methodRange, stripMethodOrdinal(methodName))))
            })
            return matcher.end()
        }

        /** The identifier starting at [position]: a local to renumber, a numbered class to reference, or literal text. */
        private fun identifierToken(scan: Scan, text: String, position: Int): Int {
            val length = text.length
            var end = position + 1
            var digit = false
            while (end < length && continuesIdentifier(text, end)) {
                if (text[end] in '0'..'9') digit = true
                end++
            }
            if (!digit) {
                // Neither a local (`l0`, `$stack3`) nor a numbered name: most identifiers take this path.
                scan.literal.append(text, position, end)
                return end
            }
            val token = text.substring(position, end)
            when {
                token in scan.locals -> scan.literal.append(scan.renames.computeIfAbsent(token) { "v${scan.renames.size}" })
                mayCarryOrdinal(token) && hasClassOrdinal(token) -> scan.reference(classReference(token))
                else -> scan.literal.append(token)
            }
            return end
        }

        /** A `.` continues a qualified name only when an identifier character follows it. */
        private fun continuesIdentifier(text: String, index: Int): Boolean {
            val char = text[index]
            if (isIdentifierChar(char)) return true
            return char == '.' && index + 1 < text.length && isIdentifierChar(text[index + 1])
        }

        private companion object {
            const val QUEUE_CAPACITY = 1024
            val STOP = Runnable { }
            /** Stands in for a reference inside the literal text; never printed by Jimple. */
            const val REFERENCE_MARK = '\u0000'
        }

        private fun isIdentifierChar(char: Char): Boolean = char == '$' || char == '_' || Character.isLetterOrDigit(char)

        /** Cheap filter before the ordinal regexes: every numbered name has a `$` and a digit. */
        private fun mayCarryOrdinal(name: String): Boolean {
            var dollar = false
            var digit = false
            for (char in name) {
                if (char == '$') dollar = true else if (char in '0'..'9') digit = true
                if (dollar && digit) return true
            }
            return false
        }
    }
}
