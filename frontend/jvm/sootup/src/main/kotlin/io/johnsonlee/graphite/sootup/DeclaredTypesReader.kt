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

/** Compact declaration snapshots retained from the existing bytecode parse. */
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
    private val environments = HashMap<String, Map<String, String>>()

    fun build(): DeclaredTypeTable {
        val fields = linkedMapOf<MemberTypeKey, Int>()
        val methods = linkedMapOf<MemberTypeKey, MethodTypes>()
        val classes = linkedMapOf<String, ClassTypes>()
        declarations.values.forEach { declaration ->
            val scope = classScope(declaration.name)
            val env = environment(declaration.name)
            classes[declaration.name] = optional(declaration.signature) {
                val parser = Parser(it, scope, env)
                val parameters = parser.formals()
                val superType = parser.type()
                val interfaces = mutableListOf<Int>()
                while (parser.hasNext()) interfaces.add(parser.type())
                ClassTypes(parameters, superType, interfaces)
            } ?: ClassTypes(emptyList(), declaration.superName?.let(::classType), declaration.interfaces.map(::classType))
            declaration.fields.forEach { field ->
                fields[MemberTypeKey(declaration.name, field.name, field.descriptor)] = optional(field.signature) {
                    Parser(it, scope, env).completeType()
                } ?: Parser(field.descriptor, scope, emptyMap()).completeType()
            }
            declaration.methods.forEach { method ->
                val key = MemberTypeKey(declaration.name, method.name, method.descriptor)
                val methodScope = methodScope(key)
                val methodEnv = env + formalNames(method.signature).associateWith { methodScope }
                val descriptor = Parser(method.descriptor, methodScope, emptyMap()).method()
                methods[key] = optional(method.signature) {
                    val generic = Parser(it, methodScope, methodEnv).method()
                    // Constructors may omit synthetic enclosing-instance/enum arguments in Signature.
                    val missing = descriptor.parameterTypes.size - generic.parameterTypes.size
                    require(missing == 0 || method.name == "<init>" && missing > 0)
                    val prefix = if (missing == 0) 0 else constructorPrefix(declaration, descriptor)
                    require(prefix <= missing)
                    generic.copy(parameterTypes = descriptor.parameterTypes.take(prefix) + generic.parameterTypes +
                        descriptor.parameterTypes.drop(prefix + generic.parameterTypes.size))
                } ?: descriptor
            }
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

    private fun environment(name: String, visiting: Set<String> = emptySet()): Map<String, String> =
        environments.getOrPut(name) {
            val declaration = declarations[name]
            if (name in visiting || declaration == null) {
                emptyMap()
            } else {
                val inherited = declaration.enclosingClass?.let { environment(it, visiting + name) }.orEmpty().toMutableMap()
                declaration.enclosingMethod?.let { method ->
                    val signature = declarations[method.owner]?.methods?.firstOrNull {
                        it.name == method.name && it.descriptor == method.descriptor
                    }?.signature
                    formalNames(signature).forEach { inherited[it] = methodScope(method) }
                }
                formalNames(declaration.signature).forEach { inherited[it] = classScope(name) }
                inherited.toMap()
            }
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
    private fun classType(name: String): Int = intern(DeclaredType("class", name))

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

    private inner class Parser(private val text: String, private val scope: String, private val env: Map<String, String>) {
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
                if (peek() != ':') bounds.add(type())
                while (peek() == ':') { position++; bounds.add(type()) }
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
            'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 'V' -> intern(DeclaredType("primitive", Type.getType(tag.toString()).className))
            '[' -> intern(DeclaredType("array", component = type()))
            'T' -> {
                val name = until(';'); position++
                intern(DeclaredType("variable", name, env[name] ?: "unresolved:$scope"))
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
                owner = intern(DeclaredType("class", binaryName, owner = owner, arguments = arguments()))
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
                        intern(DeclaredType("wildcard", component = type(), variance = variance))
                    }
                    else -> type()
                })
            }
            position++
            return arguments
        }

    }
}
