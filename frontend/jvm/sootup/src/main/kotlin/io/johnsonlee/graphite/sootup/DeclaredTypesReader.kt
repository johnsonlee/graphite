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
    val enclosingInstance: Boolean? = null
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
                        it.access and Opcodes.ACC_SYNTHETIC != 0 && it.name.startsWith("this\$") && it.desc == "L$enclosing;"
                    } -> true
                    else -> null // Local class: resolve enclosing method access before deciding.
                }
            )
        }
    }
}

internal data class MemberDeclaration(val name: String, val descriptor: String, val signature: String?, val access: Int = 0)

/** Parses declaration signatures without changing the erased types used by graph identity. */
internal class DeclaredTypesReader(private val declarations: Map<String, ClassDeclarations>) {
    private val types = mutableListOf<DeclaredType>()
    private val ids = HashMap<DeclaredType, Int>()
    private val heights = mutableListOf<Int>()
    private val expandedNodes = IntArrayList()
    private val expandedBytes = IntArrayList()
    private val boundsByVariable = HashMap<Pair<String, String>, List<Int>>()

    @Suppress("CyclomaticComplexMethod") // Class, field and method declarations share one rollback-safe table.
    fun build(): DeclaredTypeTable {
        val fields = linkedMapOf<MemberTypeKey, Int>()
        val methods = linkedMapOf<MemberTypeKey, MethodTypes>()
        val classes = linkedMapOf<String, ClassTypes>()
        val acceptedEnvironments = hashMapOf<String, Map<String, String>>()
        val orderedDeclarations = orderedDeclarations()
        orderedDeclarations.forEach { declaration ->
            val scope = classScope(declaration.name)
            val inheritedEnv = acceptedEnvironment(declaration, acceptedEnvironments, methods)
            val classEnv = inheritedEnv + formalNames(declaration.signature).associateWith { scope }
            val allowUnresolvedVariables = hasMissingEnclosingDeclaration(declaration)
            val classTypes = optional(declaration.signature) {
                val parser = Parser(it, scope, classEnv, allowUnresolvedVariables)
                val parameters = parser.formals()
                val superType = parser.type()
                val interfaces = mutableListOf<Int>()
                while (parser.hasNext()) interfaces.add(parser.type())
                val result = ClassTypes(parameters, superType, interfaces)
                val bounds = boundsByVariable + parameters.associate { parameter ->
                    (parameter.scope to parameter.name) to parameter.bounds
                }
                require(declaration.superName != null && erasesTo(superType, classDescriptor(declaration.superName), bounds))
                require(interfaces.size == declaration.interfaces.size)
                interfaces.zip(declaration.interfaces).forEach { (type, name) ->
                    require(erasesTo(type, classDescriptor(name), bounds))
                }
                result
            } ?: ClassTypes(emptyList(), declaration.superName?.let(::classType), declaration.interfaces.map(::classType))
            classes[declaration.name] = classTypes
            classTypes.typeParameters.forEach { boundsByVariable[it.scope to it.name] = it.bounds }
            val env = inheritedEnv + classTypes.typeParameters.associate { it.name to it.scope }
            acceptedEnvironments[declaration.name] = env
            declaration.fields.forEach { field ->
                fields[MemberTypeKey(declaration.name, field.name, field.descriptor)] = optional(field.signature) {
                    Parser(it, scope, env, allowUnresolvedVariables).completeType().also { type ->
                        require(erasesTo(type, field.descriptor, boundsByVariable, allowUnresolvedVariables))
                    }
                } ?: Parser(field.descriptor, scope, emptyMap()).completeType()
            }
            declaration.methods.forEach { method ->
                val key = MemberTypeKey(declaration.name, method.name, method.descriptor)
                val methodScope = methodScope(key)
                val methodEnv = env + formalNames(method.signature).associateWith { methodScope }
                val descriptor = Parser(method.descriptor, methodScope, emptyMap()).method()
                val methodTypes = optional(method.signature) {
                    val generic = Parser(it, methodScope, methodEnv, allowUnresolvedVariables).method()
                    // Constructors may omit synthetic enclosing-instance/enum arguments in Signature.
                    val missing = descriptor.parameterTypes.size - generic.parameterTypes.size
                    require(missing == 0 || method.name == "<init>" && missing > 0)
                    val prefix = if (missing == 0) 0 else constructorPrefix(declaration, descriptor)
                    require(prefix <= missing)
                    generic.copy(parameterTypes = descriptor.parameterTypes.take(prefix) + generic.parameterTypes +
                        descriptor.parameterTypes.drop(prefix + generic.parameterTypes.size)).also { result ->
                        val bounds = boundsByVariable + result.typeParameters.associate { parameter ->
                            (parameter.scope to parameter.name) to parameter.bounds
                        }
                        require(result.parameterTypes.size == descriptor.parameterTypes.size)
                        result.parameterTypes.zip(descriptor.parameterTypes).forEach { (declared, erased) ->
                            require(erasesTo(declared, erased, bounds, allowUnresolvedVariables))
                        }
                        require(erasesTo(result.returnType, descriptor.returnType, bounds, allowUnresolvedVariables))
                    }
                } ?: descriptor
                methods[key] = methodTypes
                methodTypes.typeParameters.forEach { boundsByVariable[it.scope to it.name] = it.bounds }
            }
        }
        // Preserve previously assigned generic IDs; key erasures are appended only after declaration parsing.
        orderedDeclarations.forEach { declaration ->
            declaration.fields.forEach { field -> Parser(field.descriptor, classScope(declaration.name), emptyMap()).completeType() }
        }
        return DeclaredTypeTable(types.toList(), fields, methods, classes).also { it.validate() }
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

    private fun hasMissingEnclosingDeclaration(declaration: ClassDeclarations): Boolean {
        val missingClass = declaration.enclosingClass != null && declaration.enclosingClass !in declarations
        val method = declaration.enclosingMethod
        val missingMethodOwner = method != null && method.owner !in declarations
        return missingClass || missingMethodOwner
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

    private fun classDescriptor(name: String): String = "L${name.replace('.', '/')};"

    private fun erasesTo(
        id: Int,
        descriptor: String,
        bounds: Map<Pair<String, String>, List<Int>>,
        allowUnknown: Boolean = false
    ): Boolean = erasureDescriptor(id, bounds)?.let { it == descriptor } ?: allowUnknown

    private fun erasesTo(
        id: Int,
        erasedId: Int,
        bounds: Map<Pair<String, String>, List<Int>>,
        allowUnknown: Boolean = false
    ): Boolean {
        val expected = requireNotNull(erasureDescriptor(erasedId, emptyMap()))
        return erasesTo(id, expected, bounds, allowUnknown)
    }

    private fun erasureDescriptor(
        id: Int,
        bounds: Map<Pair<String, String>, List<Int>>,
        visiting: Set<Pair<String, String>> = emptySet()
    ): String? {
        val type = types[id]
        return when (type.kind) {
            CLASS_TYPE_KIND -> classDescriptor(type.name)
            PRIMITIVE_TYPE_KIND -> primitiveDescriptors[type.name]
            "array" -> erasureDescriptor(requireNotNull(type.component), bounds, visiting)?.let { "[$it" }
            "variable" -> {
                val key = type.scope to type.name
                if (key in visiting) null else bounds[key]?.firstOrNull()
                    ?.let { erasureDescriptor(it, bounds, visiting + key) }
                    ?: if (bounds.containsKey(key)) "Ljava/lang/Object;" else null
            }
            else -> null
        }
    }

    private fun <T> optional(signature: String?, parse: (String) -> T): T? {
        if (signature == null) return null
        val checkpoint = types.size
        return try {
            parse(signature)
        } catch (_: IllegalArgumentException) {
            rollback(checkpoint)
            null
        } catch (_: IndexOutOfBoundsException) {
            rollback(checkpoint)
            null
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
                    require(allowUnresolvedVariables) { "Undeclared type variable: $name" }
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
