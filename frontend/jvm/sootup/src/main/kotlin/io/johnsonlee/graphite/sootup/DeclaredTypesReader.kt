package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.graph.ClassTypes
import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeExpansionBudget
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.MethodTypes
import io.johnsonlee.graphite.graph.TypeParameter
import it.unimi.dsi.fastutil.ints.IntArrayList
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode

private const val CLASS_TYPE_KIND = "class"
private const val PRIMITIVE_TYPE_KIND = "primitive"

/** Declaration snapshots used while constructing the deduplicated type table. */
internal data class ClassDeclarations(
    val name: String,
    val signature: String?,
    val superName: String?,
    val interfaces: List<String>,
    val fields: List<MemberDeclaration>,
    val methods: List<MemberDeclaration>,
    val enclosingClass: String?,
    val enclosingMethod: MemberTypeKey?,
    val isEnum: Boolean,
    val enclosingInstance: Boolean? = null,
    val hasInnerClassMetadata: Boolean = false
) {
    companion object {
        /** Keep malformed-class rejection at parse time without retaining snapshots. */
        fun validateParsedNode(node: ClassNode) {
            requireNotNull(node.name)
            node.interfaces.forEach { requireNotNull(it) }
            node.fields.forEach {
                requireNotNull(it.name)
                requireNotNull(it.desc)
            }
            node.methods.forEach {
                requireNotNull(it.name)
                requireNotNull(it.desc)
            }
        }

        fun from(node: ClassNode): ClassDeclarations {
            val inner = node.innerClasses.firstOrNull { it.name == node.name }
            val enclosing = node.outerClass ?: inner?.takeIf { it.access and Opcodes.ACC_STATIC == 0 }?.outerName
            return ClassDeclarations(
                node.name.replace('/', '.'), node.signature, node.superName?.replace('/', '.'),
                node.interfaces.map { it.replace('/', '.') },
                node.fields.map { MemberDeclaration(it.name, it.desc, it.signature) },
                node.methods.map { MemberDeclaration(it.name, it.desc, it.signature, it.access) },
                enclosing?.replace('/', '.'),
                if (node.outerClass != null && node.outerMethod != null && node.outerMethodDesc != null)
                    MemberTypeKey(node.outerClass.replace('/', '.'), node.outerMethod, node.outerMethodDesc) else null,
                node.access and Opcodes.ACC_ENUM != 0,
                when {
                    enclosing == null -> false
                    node.outerClass == null -> true // A non-static member class.
                    node.fields.any {
                        it.access and Opcodes.ACC_SYNTHETIC != 0 && it.name.startsWith("this\$") &&
                            it.desc == "L$enclosing;"
                    } -> true
                    else -> null // Local class: resolve enclosing method access before deciding.
                },
                inner != null
            )
        }
    }
}

internal data class MemberDeclaration(val name: String, val descriptor: String, val signature: String?, val access: Int = 0)

internal fun classDescriptor(name: String): String = "L${name.replace('.', '/')};"

/** Parses declaration signatures without changing the erased types used by graph identity. */
internal class DeclaredTypesReader(private val declarations: Map<String, ClassDeclarations>) {
    private val types = mutableListOf<DeclaredType>()
    private val ids = HashMap<DeclaredType, Int>()
    private val heights = mutableListOf<Int>()
    private val expandedNodes = IntArrayList()
    private val expandedBytes = IntArrayList()
    private val boundsByVariable = HashMap<Pair<String, String>, List<Int>>()
    private val methodDeclarations = declarations.values
        .mapNotNullTo(hashSetOf()) { it.enclosingMethod }
        .mapNotNull { key ->
            declarations[key.owner]?.methods
                ?.firstOrNull { it.name == key.name && it.descriptor == key.descriptor }
                ?.let { key to it }
        }
        .toMap()

    private data class ParsedClassTypes(val types: ClassTypes, val environmentIncomplete: Boolean = false)
    private data class ClassParseAttempt(val parsed: ParsedClassTypes, val undeclaredVariable: Boolean = false)
    private data class OptionalParse<T>(val value: T? = null, val undeclaredVariable: Boolean = false)

    @Suppress("CyclomaticComplexMethod") // Class, field and method declarations share one rollback-safe table.
    fun build(): DeclaredTypeTable {
        val fields = linkedMapOf<MemberTypeKey, Int>()
        val methods = linkedMapOf<MemberTypeKey, MethodTypes>()
        val classes = linkedMapOf<String, ClassTypes>()
        val acceptedEnvironments = hashMapOf<String, Map<String, String>>()
        val unresolvedEnvironments = hashSetOf<String>()
        val rejectedMethodSignatures = hashSetOf<MemberTypeKey>()
        val orderedDeclarations = orderedDeclarations()
        orderedDeclarations.forEach { declaration ->
            val scope = classScope(declaration.name)
            val inheritedEnv = acceptedEnvironment(declaration, acceptedEnvironments, methods)
            val classFormalNames = formalNames(declaration.signature)
            val classEnv = inheritedEnv + classFormalNames.associateWith { scope }
            val allowUnresolvedVariables = hasIncompleteEnclosingEnvironment(
                declaration,
                unresolvedEnvironments,
                rejectedMethodSignatures
            )
            val retryUndeclaredVariables = declaration.enclosingClass == null &&
                declaration.enclosingMethod == null && !declaration.hasInnerClassMetadata
            val parsedClassTypes = parseClassTypes(
                declaration,
                scope,
                classEnv,
                allowUnresolvedVariables,
                retryUndeclaredVariables
            )
            val classTypes = parsedClassTypes.types
            var allowUnresolvedMembers = allowUnresolvedVariables || parsedClassTypes.environmentIncomplete
            classes[declaration.name] = classTypes
            classTypes.typeParameters.forEach { boundsByVariable[it.scope to it.name] = it.bounds }
            val env = inheritedEnv + classTypes.typeParameters.associate { it.name to it.scope }
            acceptedEnvironments[declaration.name] = env
            declaration.fields.forEach { field ->
                val parseField = { allowUnresolved: Boolean -> optionalParse(field.signature) {
                    Parser(it, scope, env, allowUnresolved).completeType().also { type ->
                        require(erasesTo(
                            type,
                            field.descriptor,
                            boundsByVariable,
                            allowUnknown = allowUnresolved
                        ))
                    }
                } }
                var genericField = parseField(allowUnresolvedMembers)
                if (genericField.undeclaredVariable && retryUndeclaredVariables && !allowUnresolvedMembers) {
                    allowUnresolvedMembers = true
                    genericField = parseField(true)
                }
                fields[MemberTypeKey(declaration.name, field.name, field.descriptor)] = genericField.value
                    ?: Parser(field.descriptor, scope, emptyMap()).completeType()
            }
            declaration.methods.forEach { method ->
                val key = MemberTypeKey(declaration.name, method.name, method.descriptor)
                val methodScope = methodScope(key)
                val methodFormalNames = formalNames(method.signature)
                val methodEnv = env + methodFormalNames.associateWith { methodScope }
                val descriptor = Parser(method.descriptor, methodScope, emptyMap()).method()
                val parseMethod = { allowUnresolved: Boolean -> optionalParse(method.signature) {
                    val generic = Parser(it, methodScope, methodEnv, allowUnresolved).method()
                    // Constructors may omit synthetic enclosing-instance/enum arguments in Signature.
                    val missing = descriptor.parameterTypes.size - generic.parameterTypes.size
                    require(missing == 0 || method.name == "<init>" && missing > 0)
                    val prefix = if (missing == 0) 0 else constructorPrefix(declaration, descriptor)
                    require(prefix <= missing)
                    generic.copy(parameterTypes = descriptor.parameterTypes.take(prefix) + generic.parameterTypes +
                        descriptor.parameterTypes.drop(prefix + generic.parameterTypes.size)).also { result ->
                        val localBounds = result.typeParameters.associate { parameter ->
                            (parameter.scope to parameter.name) to parameter.bounds
                        }
                        result.parameterTypes.zip(descriptor.parameterTypes).forEach { (declared, erased) ->
                            require(erasesTo(
                                declared,
                                erased,
                                boundsByVariable,
                                localBounds,
                                allowUnresolved
                            ))
                        }
                        require(erasesTo(
                            result.returnType,
                            descriptor.returnType,
                            boundsByVariable,
                            localBounds,
                            allowUnresolved
                        ))
                    }
                } }
                var genericMethod = parseMethod(allowUnresolvedMembers)
                if (genericMethod.undeclaredVariable && retryUndeclaredVariables && !allowUnresolvedMembers) {
                    allowUnresolvedMembers = true
                    genericMethod = parseMethod(true)
                }
                val genericMethodTypes = genericMethod.value
                if (genericMethodTypes == null && method.signature != null) {
                    rejectedMethodSignatures.add(key)
                }
                val methodTypes = genericMethodTypes ?: descriptor
                methods[key] = methodTypes
                methodTypes.typeParameters.forEach { boundsByVariable[it.scope to it.name] = it.bounds }
            }
            if (allowUnresolvedMembers) unresolvedEnvironments.add(declaration.name)
        }
        // Preserve previously assigned generic IDs; key erasures are appended only after declaration parsing.
        orderedDeclarations.forEach { declaration ->
            declaration.fields.forEach { field -> Parser(field.descriptor, classScope(declaration.name), emptyMap()).completeType() }
        }
        return DeclaredTypeTable(types.toList(), fields, methods, classes).also { it.validate() }
    }

    /** Preserve valid formals when only the signed hierarchy disagrees with the erased class metadata. */
    @Suppress("ReturnCount")
    private fun parseClassTypes(
        declaration: ClassDeclarations,
        scope: String,
        env: Map<String, String>,
        allowUnresolvedVariables: Boolean,
        retryUndeclaredVariables: Boolean
    ): ParsedClassTypes {
        val checkpoint = types.size
        val first = parseClassTypesOnce(declaration, scope, env, allowUnresolvedVariables)
        if (!first.undeclaredVariable || allowUnresolvedVariables || !retryUndeclaredVariables) return first.parsed
        rollback(checkpoint)
        return parseClassTypesOnce(declaration, scope, env, true).parsed.copy(environmentIncomplete = true)
    }

    @Suppress("ReturnCount") // Each rejected phase must preserve its own rollback boundary.
    private fun parseClassTypesOnce(
        declaration: ClassDeclarations,
        scope: String,
        env: Map<String, String>,
        allowUnresolvedVariables: Boolean
    ): ClassParseAttempt {
        val raw = {
            ClassTypes(emptyList(), declaration.superName?.let(::classType), declaration.interfaces.map(::classType))
        }
        val signature = declaration.signature ?: return ClassParseAttempt(ParsedClassTypes(raw()))
        val checkpoint = types.size
        val parser = Parser(signature, scope, env, allowUnresolvedVariables)
        val parameterAttempt = optionalParse(signature, checkpoint) { parser.formals() }
        val parameters = parameterAttempt.value ?: return ClassParseAttempt(
            ParsedClassTypes(raw(), environmentIncomplete = true),
            parameterAttempt.undeclaredVariable
        )
        val hierarchyCheckpoint = types.size
        val hierarchyAttempt = optionalParse(signature, checkpoint) {
            val superType = parser.type()
            val interfaces = mutableListOf<Int>()
            while (parser.hasNext()) interfaces.add(parser.type())
            superType to interfaces
        }
        val hierarchy = hierarchyAttempt.value ?: return ClassParseAttempt(
            ParsedClassTypes(raw(), environmentIncomplete = true),
            hierarchyAttempt.undeclaredVariable
        )
        val parsed = optional(signature, hierarchyCheckpoint) {
            val (superType, interfaces) = hierarchy
            val localBounds = parameters.associate { parameter ->
                (parameter.scope to parameter.name) to parameter.bounds
            }
            require(declaration.superName != null && erasesTo(
                superType,
                classDescriptor(declaration.superName),
                boundsByVariable,
                localBounds
            ))
            require(interfaces.size == declaration.interfaces.size)
            interfaces.zip(declaration.interfaces).forEach { (type, name) ->
                require(erasesTo(type, classDescriptor(name), boundsByVariable, localBounds))
            }
            ClassTypes(parameters, superType, interfaces)
        } ?: ClassTypes(parameters, declaration.superName?.let(::classType), declaration.interfaces.map(::classType))
        return ClassParseAttempt(ParsedClassTypes(parsed))
    }

    /** Never confuse an explicit enclosing-class argument with the synthetic outer instance. */
    private fun constructorPrefix(declaration: ClassDeclarations, descriptor: MethodTypes): Int {
        val first = descriptor.parameterTypes.firstOrNull()?.let { types[it].name }
        return when {
            declaration.isEnum -> 2
            declaration.enclosingClass == null || first != declaration.enclosingClass -> 0
            else -> {
                val method = declaration.enclosingMethod?.let { key ->
                    declarations[key.owner]?.methods?.firstOrNull { it.name == key.name && it.descriptor == key.descriptor }
                }
                val enclosingInstance = method?.let { it.access and Opcodes.ACC_STATIC == 0 } ?: declaration.enclosingInstance
                // Missing enclosing-method or initializer evidence cannot establish which
                // leading parameter was omitted from Signature. Fall back to the descriptor.
                requireNotNull(enclosingInstance) { "Unknown constructor enclosing-instance parameter" }
                if (enclosingInstance) 1 else 0
            }
        }
    }

    private fun acceptedEnvironment(
        declaration: ClassDeclarations,
        acceptedEnvironments: Map<String, Map<String, String>>,
        methods: Map<MemberTypeKey, MethodTypes>
    ): Map<String, String> {
        val owner = declaration.enclosingClass ?: declaration.enclosingMethod?.owner
        val inherited = owner?.let { acceptedEnvironments[it] }.orEmpty().toMutableMap()
        declaration.enclosingMethod?.let { method ->
            methods[method]?.typeParameters?.forEach { inherited[it.name] = it.scope }
        }
        return inherited
    }

    private fun hasIncompleteEnclosingEnvironment(
        declaration: ClassDeclarations,
        unresolvedEnvironments: Set<String>,
        rejectedMethodSignatures: Set<MemberTypeKey>
    ): Boolean {
        val owner = declaration.enclosingClass ?: declaration.enclosingMethod?.owner
        val method = declaration.enclosingMethod
        val enclosingMethod = method?.let(methodDeclarations::get)
        val methodSignatureIncomplete = method != null && (
            method in rejectedMethodSignatures || enclosingMethod == null ||
                enclosingMethod.signature == null && enclosingMethod.access and Opcodes.ACC_SYNTHETIC != 0
        )
        return owner != null &&
            (owner !in declarations || owner in unresolvedEnvironments || methodSignatureIncomplete)
    }

    @Suppress("NestedBlockDepth", "LoopWithTooManyJumpStatements") // Explicit DFS avoids untrusted enclosure recursion.
    private fun orderedDeclarations(): List<ClassDeclarations> {
        val ordered = mutableListOf<ClassDeclarations>()
        val visiting = hashSetOf<String>()
        val visited = hashSetOf<String>()
        declarations.keys.forEach { root ->
            val pending = ArrayDeque<Pair<String, Boolean>>()
            pending.addLast(root to false)
            while (pending.isNotEmpty()) {
                val (name, expanded) = pending.removeLast()
                if (name in visited) continue
                val declaration = declarations[name] ?: continue
                if (expanded) {
                    visiting.remove(name)
                    if (visited.add(name)) ordered.add(declaration)
                } else if (visiting.add(name)) {
                    pending.addLast(name to true)
                    listOfNotNull(declaration.enclosingClass, declaration.enclosingMethod?.owner)
                        .distinct()
                        .asReversed()
                        .filter { it in declarations && it !in visited }
                        .forEach { pending.addLast(it to false) }
                }
            }
        }
        return ordered
    }

    /** Scan formal names iteratively so untrusted signatures never enter ASM's recursive reader. */
    private fun formalNames(signature: String?): List<String> {
        if (signature == null || !signature.startsWith('<')) return emptyList()
        return optional(signature) { text ->
            val names = mutableListOf<String>()
            var position = 1
            while (text[position] != '>') {
                val end = text.indexOf(':', position)
                require(end > position)
                names.add(text.substring(position, end))
                position = end + 1
                if (text[position] != ':') position = skipBound(text, position)
                while (text[position] == ':') position = skipBound(text, position + 1)
            }
            names
        }.orEmpty()
    }

    private fun skipBound(text: String, start: Int): Int {
        var position = start
        while (text[position] == '[') position++
        require(text[position] == 'L' || text[position] == 'T')
        var arguments = 0
        while (true) {
            when (text[position++]) {
                '<' -> arguments++
                '>' -> arguments--
                ';' -> if (arguments == 0) return position
            }
        }
    }

    private fun classScope(name: String) = "class:$name"
    private fun methodScope(key: MemberTypeKey) = "method:${key.owner}#${key.name}${key.descriptor}"
    private fun intern(type: DeclaredType): Int = ids.getOrPut(type) {
        val children = type.arguments + listOfNotNull(type.owner, type.component)
        val height = 1 + (children.maxOfOrNull { heights[it] } ?: 0)
        require(height <= DeclaredTypeTable.MAX_DEPTH) { "Excessive generic signature nesting" }
        var nodes = 1
        var bytes = DeclaredTypeExpansionBudget.textBytes(type)
        children.forEach { child ->
            nodes = DeclaredTypeExpansionBudget.addNodes(nodes, expandedNodes.getInt(child))
            bytes = DeclaredTypeExpansionBudget.addBytes(bytes, expandedBytes.getInt(child))
        }
        DeclaredTypeExpansionBudget.validate(nodes, bytes)
        types.add(type)
        heights.add(height)
        expandedNodes.add(nodes)
        expandedBytes.add(bytes)
        types.lastIndex
    }
    private fun classType(name: String): Int = intern(DeclaredType(CLASS_TYPE_KIND, name))

    private fun erasesTo(
        id: Int,
        descriptor: String,
        bounds: Map<Pair<String, String>, List<Int>>,
        localBounds: Map<Pair<String, String>, List<Int>> = emptyMap(),
        allowUnknown: Boolean = false
    ): Boolean = erasureDescriptor(id, bounds, localBounds)?.let { it == descriptor } ?: allowUnknown

    private fun erasesTo(
        id: Int,
        erasedId: Int,
        bounds: Map<Pair<String, String>, List<Int>>,
        localBounds: Map<Pair<String, String>, List<Int>> = emptyMap(),
        allowUnknown: Boolean = false
    ): Boolean {
        val expected = requireNotNull(erasureDescriptor(erasedId, emptyMap(), emptyMap()))
        return erasesTo(id, expected, bounds, localBounds, allowUnknown)
    }

    private fun erasureDescriptor(
        id: Int,
        bounds: Map<Pair<String, String>, List<Int>>,
        localBounds: Map<Pair<String, String>, List<Int>>,
        visiting: Set<Pair<String, String>> = emptySet()
    ): String? {
        val type = types[id]
        return when (type.kind) {
            CLASS_TYPE_KIND -> classDescriptor(type.name)
            PRIMITIVE_TYPE_KIND -> primitiveDescriptors[type.name]
            "array" -> erasureDescriptor(requireNotNull(type.component), bounds, localBounds, visiting)?.let { "[$it" }
            "variable" -> {
                val key = type.scope to type.name
                val variableBounds = when {
                    key in visiting -> null
                    key in localBounds -> localBounds.getValue(key)
                    else -> bounds[key]
                }
                when {
                    variableBounds == null -> null
                    variableBounds.isEmpty() -> "Ljava/lang/Object;"
                    else -> erasureDescriptor(variableBounds.first(), bounds, localBounds, visiting + key)
                }
            }
            else -> null
        }
    }

    private fun <T> optional(signature: String?, checkpoint: Int = types.size, parse: (String) -> T): T? {
        return optionalParse(signature, checkpoint, parse).value
    }

    private fun <T> optionalParse(
        signature: String?,
        checkpoint: Int = types.size,
        parse: (String) -> T
    ): OptionalParse<T> {
        if (signature == null) return OptionalParse()
        return try {
            OptionalParse(parse(signature))
        } catch (_: UndeclaredTypeVariableException) {
            rollback(checkpoint)
            OptionalParse(undeclaredVariable = true)
        } catch (_: IllegalArgumentException) {
            rollback(checkpoint)
            OptionalParse()
        } catch (_: IndexOutOfBoundsException) {
            rollback(checkpoint)
            OptionalParse()
        }
    }

    /** A rejected declaration must not leave unreachable expressions in the shared table. */
    private fun rollback(checkpoint: Int) {
        while (types.size > checkpoint) {
            ids.remove(types.removeAt(types.lastIndex))
            heights.removeAt(heights.lastIndex)
            expandedNodes.removeInt(expandedNodes.size - 1)
            expandedBytes.removeInt(expandedBytes.size - 1)
        }
    }

    private val primitiveDescriptors = mapOf(
        "boolean" to "Z", "byte" to "B", "char" to "C", "short" to "S", "int" to "I",
        "long" to "J", "float" to "F", "double" to "D", "void" to "V"
    )

    private class UndeclaredTypeVariableException(name: String) :
        IllegalArgumentException("Undeclared type variable: $name")

    private inner class Parser(
        private val text: String,
        private val scope: String,
        private val env: Map<String, String>,
        private val allowUnresolvedVariables: Boolean = false
    ) {
        private var position = 0
        private var depth = 0
        fun hasNext() = position < text.length
        private fun peek(): Char = text[position]
        private fun consume(expected: Char) { require(text[position++] == expected) }
        private fun until(delimiter: Char): String {
            val start = position
            while (peek() != delimiter) position++
            return text.substring(start, position)
        }

        fun formals(): List<TypeParameter> {
            if (!hasNext() || peek() != '<') return emptyList()
            position++
            val parameters = mutableListOf<TypeParameter>()
            while (peek() != '>') {
                val name = until(':')
                consume(':')
                val bounds = mutableListOf<Int>()
                if (peek() != ':') bounds.add(referenceType())
                while (peek() == ':') { position++; bounds.add(referenceType()) }
                parameters.add(TypeParameter(name, scope, bounds))
            }
            position++
            return parameters
        }

        fun completeType(): Int = type().also { require(!hasNext()) }

        fun method(): MethodTypes {
            val formals = formals()
            consume('(')
            val parameters = mutableListOf<Int>()
            while (peek() != ')') parameters.add(type())
            position++
            val result = type()
            while (hasNext()) { consume('^'); type() }
            return MethodTypes(parameters, result, formals)
        }

        fun type(): Int {
            require(depth < DeclaredTypeTable.MAX_DEPTH) { "Excessive generic signature nesting" }
            depth++
            return try {
                parseType()
            } finally {
                depth--
            }
        }

        private fun parseType(): Int = when (val tag = text[position++]) {
            'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 'V' ->
                intern(DeclaredType(PRIMITIVE_TYPE_KIND, Type.getType(tag.toString()).className))
            '[' -> {
                val component = type()
                require(types[component].let { it.kind != PRIMITIVE_TYPE_KIND || it.name != "void" })
                intern(DeclaredType("array", component = component))
            }
            'T' -> {
                val name = until(';'); position++
                val variableScope = env[name] ?: run {
                    if (!allowUnresolvedVariables) throw UndeclaredTypeVariableException(name)
                    "unresolved:$scope"
                }
                intern(DeclaredType("variable", name, variableScope))
            }
            'L' -> classExpression()
            else -> throw IllegalArgumentException("Invalid type signature tag: $tag")
        }

        private fun classExpression(): Int {
            var owner: Int? = null
            var binaryName = ""
            while (true) {
                val start = position
                while (peek() != '<' && peek() != '.' && peek() != ';') position++
                val segment = text.substring(start, position).replace('/', '.')
                require(segment.isNotEmpty())
                binaryName = if (owner == null) segment else "$binaryName\$$segment"
                owner = intern(DeclaredType(CLASS_TYPE_KIND, binaryName, owner = owner, arguments = arguments()))
                if (text[position++] == ';') return owner
            }
        }

        private fun arguments(): List<Int> {
            if (peek() != '<') return emptyList()
            position++
            val arguments = mutableListOf<Int>()
            while (peek() != '>') {
                arguments.add(when (peek()) {
                    '*' -> { position++; intern(DeclaredType("wildcard", variance = "unbounded")) }
                    '+', '-' -> {
                        val variance = if (text[position++] == '+') "extends" else "super"
                        intern(DeclaredType("wildcard", component = referenceType(), variance = variance))
                    }
                    else -> referenceType()
                })
            }
            position++
            return arguments
        }

        private fun referenceType(): Int = type().also { require(types[it].kind != PRIMITIVE_TYPE_KIND) }

    }
}
