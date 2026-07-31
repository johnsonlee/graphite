package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ConstantNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeRelation
import io.johnsonlee.graphite.graph.FullGraphBuilder
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Bytecode-only graph builder for explicitly requested fast builds.
 *
 * This path intentionally avoids SootUp/Jimple body materialization. It keeps
 * core graph evidence useful for large-corpus exploration: type hierarchy,
 * methods, fields, parameters, return nodes, call sites, basic operand
 * dataflow, class origins, artifact dependencies, and resources.
 */
@Suppress("TooManyFunctions")
internal class AsmFastGraphAdapter(
    private val config: LoaderConfig,
    private val resourceAccessor: ResourceAccessor,
    private val loadedSources: Set<String>,
    private val graphBuilder: FullGraphBuilder
) {
    private val typeDescriptors = mutableMapOf<String, TypeDescriptor>()
    private val methodDescriptors = mutableMapOf<String, MethodDescriptor>()
    private val fieldNodes = mutableMapOf<String, FieldNode>()
    private val constantNodes = mutableMapOf<Any, ConstantNode>()
    private val classOriginsByName = mutableMapOf<String, String>()
    private val classOriginSourceCounts = mutableMapOf<String, Int>()
    private val artifactDependenciesByArtifact = mutableMapOf<String, MutableMap<String, Int>>()

    fun buildGraph(): Graph {
        indexClassOrigins()
        indexResourceValues()

        val collectDependencies = classOriginSourceCounts.size > 1
        classEntries()
            .filter { isLoadedSource(it.source) }
            .forEach { entry ->
                resourceAccessor.open(entry.path).use { input ->
                    runCatching {
                        ClassReader(input).accept(
                            ClassGraphVisitor(entry.source, collectDependencies),
                            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES
                        )
                    }.onFailure {
                        log("Skipping class ${entry.source}!/${entry.path}: ${it.message}")
                    }
                }
            }

        if (classOriginSourceCounts.size > 1) {
            classOriginsByName.forEach { (className, source) ->
                graphBuilder.addClassOrigin(className, source)
            }
            artifactDependenciesByArtifact.forEach { (fromArtifact, dependencies) ->
                dependencies.forEach { (toArtifact, weight) ->
                    graphBuilder.addArtifactDependency(fromArtifact, toArtifact, weight)
                }
            }
        }

        graphBuilder.setResources(resourceAccessor)
        return graphBuilder.build()
    }

    private fun indexClassOrigins() {
        classEntries().forEach { entry ->
            val className = classResourcePathToName(entry.path) ?: return@forEach
            classOriginsByName.putIfAbsent(className, entry.source)
            classOriginSourceCounts[entry.source] = (classOriginSourceCounts[entry.source] ?: 0) + 1
        }
    }

    private fun indexResourceValues() {
        resourceAccessor.list("**").forEach { entry ->
            if (entry.path.endsWith(CLASS_FILE_SUFFIX, ignoreCase = true)) return@forEach
            val fileNode = ResourceFileNode(
                id = nextNodeId(),
                path = entry.path,
                source = entry.source,
                format = resourceFormat(entry.path),
                profile = resourceProfile(entry.path)
            )
            graphBuilder.addNode(fileNode)
        }
    }

    private fun classEntries(): Sequence<ResourceEntry> =
        resourceAccessor.list("**/*.class")

    private fun isLoadedSource(source: String): Boolean =
        loadedSources.isEmpty() || source in loadedSources

    private fun shouldIncludeClass(className: String): Boolean =
        config.excludePackages.none { className.startsWith(it) } &&
            (config.includePackages.isEmpty() || config.includePackages.any { className.startsWith(it) })

    private inner class ClassGraphVisitor(
        private val source: String,
        private val collectDependencies: Boolean
    ) : ClassVisitor(ASM_API_VERSION) {
        private var className = ""
        private var includeMembers = false
        private var references: MutableSet<String>? = null

        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?
        ) {
            className = name.replace('/', '.')
            includeMembers = shouldIncludeClass(className)
            if (collectDependencies) {
                references = linkedSetOf()
            }

            val classType = typeDescriptor(className)
            superName?.replace('/', '.')?.let { superClass ->
                graphBuilder.addTypeRelation(classType, typeDescriptor(superClass), TypeRelation.EXTENDS)
                addReference(superClass)
            }
            interfaces.orEmpty().forEach { iface ->
                val interfaceName = iface.replace('/', '.')
                graphBuilder.addTypeRelation(classType, typeDescriptor(interfaceName), TypeRelation.IMPLEMENTS)
                addReference(interfaceName)
            }
        }

        override fun visitField(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            value: Any?
        ): FieldVisitor? {
            addReferencedType(Type.getType(descriptor))
            if (!includeMembers || name.startsWith("$") || name.startsWith("this$")) return null

            val fieldType = GenericSignatureParser.parseFieldSignature(signature) ?: typeDescriptor(Type.getType(descriptor))
            getOrCreateField(
                owner = className,
                name = name,
                descriptor = descriptor,
                type = fieldType,
                isStatic = access and Opcodes.ACC_STATIC != 0
            )
            if (access and Opcodes.ACC_ENUM != 0) {
                graphBuilder.addEnumValues(className, name, emptyList())
            }
            return null
        }

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor? {
            addReferencedType(Type.getMethodType(descriptor))
            exceptions.orEmpty().forEach { addReference(it.replace('/', '.')) }
            if (!includeMembers) return null

            val method = methodDescriptor(className, name, descriptor)
            graphBuilder.addMethod(method)
            val returnNode = ReturnNode(nextNodeId(), method)
            graphBuilder.addNode(returnNode)

            return MethodGraphVisitor(
                owner = className,
                access = access,
                method = method,
                methodDescriptor = descriptor,
                returnNode = returnNode
            )
        }

        override fun visitEnd() {
            val refs = references ?: return
            val fromArtifact = artifactKey(source) ?: return
            refs.forEach { referencedClass ->
                val targetArtifact = classOriginsByName[referencedClass]?.let(::artifactKey) ?: return@forEach
                if (targetArtifact == fromArtifact) return@forEach
                artifactDependenciesByArtifact
                    .getOrPut(fromArtifact) { mutableMapOf() }
                    .merge(targetArtifact, 1, Int::plus)
            }
        }

        private fun addReference(className: String) {
            references?.add(className)
        }

        private fun addReferencedType(type: Type?) {
            when (type?.sort) {
                Type.ARRAY -> addReferencedType(type.elementType)
                Type.OBJECT -> addReference(type.className)
                Type.METHOD -> {
                    addReferencedType(type.returnType)
                    type.argumentTypes.forEach(::addReferencedType)
                }
            }
        }
    }

    private inner class MethodGraphVisitor(
        private val owner: String,
        access: Int,
        private val method: MethodDescriptor,
        methodDescriptor: String,
        private val returnNode: ReturnNode
    ) : MethodVisitor(ASM_API_VERSION) {
        private val stack = ArrayList<NodeId?>()
        private val localNodes = HashMap<Int, LocalVariable>()
        private val localTypes = HashMap<Int, TypeDescriptor>()
        private val parameterIdsBySlot = HashMap<Int, NodeId>()
        private val parameterNamesBySlot = HashMap<Int, String>()

        init {
            var slot = 0
            if (access and Opcodes.ACC_STATIC == 0) {
                localTypes[slot] = typeDescriptor(owner)
                parameterNamesBySlot[slot] = "this"
                slot++
            }
            Type.getArgumentTypes(methodDescriptor).forEachIndexed { index, argType ->
                val type = typeDescriptor(argType)
                val paramNode = ParameterNode(nextNodeId(), index, type, method)
                graphBuilder.addNode(paramNode)
                parameterIdsBySlot[slot] = paramNode.id
                parameterNamesBySlot[slot] = "p$index"
                localTypes[slot] = type
                slot += argType.size
            }
        }

        @Suppress("CyclomaticComplexMethod")
        override fun visitInsn(opcode: Int) {
            when (opcode) {
                Opcodes.NOP -> Unit
                Opcodes.ACONST_NULL -> push(constantNode(null).id)
                in Opcodes.ICONST_M1..Opcodes.ICONST_5 -> push(constantNode(opcode - Opcodes.ICONST_0).id)
                Opcodes.LCONST_0, Opcodes.LCONST_1 -> push(constantNode((opcode - Opcodes.LCONST_0).toLong()).id)
                Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 -> push(constantNode((opcode - Opcodes.FCONST_0).toFloat()).id)
                Opcodes.DCONST_0, Opcodes.DCONST_1 -> push(constantNode((opcode - Opcodes.DCONST_0).toDouble()).id)
                Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD,
                Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD -> {
                    pop()
                    push(pop())
                }
                Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE,
                Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE -> {
                    val value = pop()
                    pop()
                    val array = pop()
                    addEdge(value, array, DataFlowKind.ARRAY_STORE)
                }
                Opcodes.POP -> pop()
                Opcodes.POP2 -> {
                    pop()
                    pop()
                }
                Opcodes.DUP -> stack.lastOrNull()?.let { push(it) }
                Opcodes.DUP_X1 -> dupX1()
                Opcodes.DUP_X2 -> dupX2()
                Opcodes.DUP2 -> dup2()
                Opcodes.DUP2_X1 -> dup2X1()
                Opcodes.DUP2_X2 -> dup2X2()
                Opcodes.SWAP -> swap()
                Opcodes.IADD, Opcodes.LADD, Opcodes.FADD, Opcodes.DADD,
                Opcodes.ISUB, Opcodes.LSUB, Opcodes.FSUB, Opcodes.DSUB,
                Opcodes.IMUL, Opcodes.LMUL, Opcodes.FMUL, Opcodes.DMUL,
                Opcodes.IDIV, Opcodes.LDIV, Opcodes.FDIV, Opcodes.DDIV,
                Opcodes.IREM, Opcodes.LREM, Opcodes.FREM, Opcodes.DREM,
                Opcodes.ISHL, Opcodes.LSHL, Opcodes.ISHR, Opcodes.LSHR,
                Opcodes.IUSHR, Opcodes.LUSHR, Opcodes.IAND, Opcodes.LAND,
                Opcodes.IOR, Opcodes.LOR, Opcodes.IXOR, Opcodes.LXOR,
                Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG -> {
                    pop()
                    pop()
                    push(null)
                }
                Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG,
                Opcodes.I2L, Opcodes.I2F, Opcodes.I2D, Opcodes.L2I, Opcodes.L2F, Opcodes.L2D,
                Opcodes.F2I, Opcodes.F2L, Opcodes.F2D, Opcodes.D2I, Opcodes.D2L, Opcodes.D2F,
                Opcodes.I2B, Opcodes.I2C, Opcodes.I2S -> Unit
                Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN ->
                    addEdge(pop(), returnNode.id, DataFlowKind.RETURN_VALUE)
                Opcodes.RETURN -> Unit
                Opcodes.ARRAYLENGTH -> {
                    pop()
                    push(null)
                }
                Opcodes.ATHROW, Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> pop()
                else -> Unit
            }
        }

        override fun visitIntInsn(opcode: Int, operand: Int) {
            when (opcode) {
                Opcodes.BIPUSH, Opcodes.SIPUSH -> push(constantNode(operand).id)
                Opcodes.NEWARRAY -> {
                    pop()
                    push(null)
                }
            }
        }

        override fun visitVarInsn(opcode: Int, variable: Int) {
            when (opcode) {
                Opcodes.ILOAD -> push(localNode(variable, localName(variable), typeDescriptor("int")).id)
                Opcodes.LLOAD -> push(localNode(variable, localName(variable), typeDescriptor("long")).id)
                Opcodes.FLOAD -> push(localNode(variable, localName(variable), typeDescriptor("float")).id)
                Opcodes.DLOAD -> push(localNode(variable, localName(variable), typeDescriptor("double")).id)
                Opcodes.ALOAD -> push(
                    localNode(variable, localName(variable), localTypes[variable] ?: typeDescriptor("java.lang.Object")).id
                )
                Opcodes.ISTORE -> store(variable, typeDescriptor("int"))
                Opcodes.LSTORE -> store(variable, typeDescriptor("long"))
                Opcodes.FSTORE -> store(variable, typeDescriptor("float"))
                Opcodes.DSTORE -> store(variable, typeDescriptor("double"))
                Opcodes.ASTORE -> store(variable, typeDescriptor("java.lang.Object"))
            }
        }

        override fun visitTypeInsn(opcode: Int, type: String) {
            val className = type.replace('/', '.')
            when (opcode) {
                Opcodes.NEW -> push(null)
                Opcodes.ANEWARRAY -> {
                    pop()
                    push(null)
                }
                Opcodes.CHECKCAST -> Unit
                Opcodes.INSTANCEOF -> {
                    pop()
                    push(null)
                }
            }
        }

        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
            val field = getOrCreateField(
                owner = owner.replace('/', '.'),
                name = name,
                descriptor = descriptor,
                type = typeDescriptor(Type.getType(descriptor)),
                isStatic = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC
            )
            when (opcode) {
                Opcodes.GETSTATIC -> push(field.id)
                Opcodes.GETFIELD -> {
                    val receiver = pop()
                    addEdge(receiver, field.id, DataFlowKind.ASSIGN)
                    push(field.id)
                }
                Opcodes.PUTSTATIC -> addEdge(pop(), field.id, DataFlowKind.FIELD_STORE)
                Opcodes.PUTFIELD -> {
                    val value = pop()
                    pop()
                    addEdge(value, field.id, DataFlowKind.FIELD_STORE)
                }
            }
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean
        ) {
            val argTypes = Type.getArgumentTypes(descriptor)
            val args = popArguments(argTypes.size)
            val receiver = if (opcode == Opcodes.INVOKESTATIC) null else pop()
            val callSite = addCallSite(
                callee = methodDescriptor(owner.replace('/', '.'), name, descriptor),
                receiver = receiver,
                arguments = args
            )
            if (Type.getReturnType(descriptor).sort != Type.VOID) {
                push(callSite.id)
            }
        }

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any
        ) {
            val argTypes = Type.getArgumentTypes(descriptor)
            val args = popArguments(argTypes.size)
            val targetHandle = bootstrapMethodArguments.filterIsInstance<Handle>().firstOrNull()
            val callee = targetHandle?.let(::methodDescriptor)
                ?: methodDescriptor(owner, name, descriptor)
            val callSite = addCallSite(callee, receiver = null, arguments = args)
            if (Type.getReturnType(descriptor).sort != Type.VOID) {
                push(callSite.id)
            }
        }

        override fun visitJumpInsn(opcode: Int, label: Label) {
            when (opcode) {
                Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
                Opcodes.IFNULL, Opcodes.IFNONNULL -> pop()
                Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
                Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> {
                    pop()
                    pop()
                }
            }
        }

        override fun visitLdcInsn(value: Any) {
            when (value) {
                is Int, is Long, is Float, is Double, is String -> push(constantNode(value).id)
                else -> push(null)
            }
        }

        override fun visitIincInsn(variable: Int, increment: Int) {
            localNode(variable, "v$variable", typeDescriptor("int"))
        }

        override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, vararg labels: Label) {
            pop()
        }

        override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray, labels: Array<out Label>) {
            pop()
        }

        override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
            repeat(numDimensions) { pop() }
            push(null)
        }

        private fun addCallSite(
            callee: MethodDescriptor,
            receiver: NodeId?,
            arguments: List<NodeId>
        ): CallSiteNode {
            val callSite = CallSiteNode(
                id = nextNodeId(),
                caller = method,
                callee = callee,
                lineNumber = null,
                receiver = receiver,
                arguments = arguments
            )
            graphBuilder.addNode(callSite)
            addEdge(receiver, callSite.id, DataFlowKind.ASSIGN)
            arguments.forEach { arg ->
                graphBuilder.addEdge(DataFlowEdge(arg, callSite.id, DataFlowKind.PARAMETER_PASS))
            }
            return callSite
        }

        private fun store(slot: Int, type: TypeDescriptor) {
            val value = pop()
            localTypes[slot] = type
            val target = localNode(slot, localName(slot), type)
            addEdge(value, target.id, DataFlowKind.ASSIGN)
        }

        private fun localNode(slot: Int, name: String, type: TypeDescriptor): LocalVariable {
            localTypes.putIfAbsent(slot, type)
            return localNodes.getOrPut(slot) {
                val local = LocalVariable(
                    nextNodeId(),
                    parameterNamesBySlot[slot] ?: name,
                    localTypes[slot] ?: type,
                    method
                )
                graphBuilder.addNode(local)
                parameterIdsBySlot[slot]?.let { parameterId ->
                    graphBuilder.addEdge(DataFlowEdge(parameterId, local.id, DataFlowKind.ASSIGN))
                }
                local
            }
        }

        private fun popArguments(count: Int): List<NodeId> {
            if (count == 0) return emptyList()
            val args = ArrayList<NodeId>(count)
            repeat(count) {
                pop()?.let { args.add(0, it) }
            }
            return args
        }

        private fun push(nodeId: NodeId?) {
            stack.add(nodeId)
        }

        private fun pop(): NodeId? =
            if (stack.isEmpty()) null else stack.removeAt(stack.lastIndex)

        private fun addEdge(from: NodeId?, to: NodeId?, kind: DataFlowKind) {
            if (from != null && to != null) {
                graphBuilder.addEdge(DataFlowEdge(from, to, kind))
            }
        }

        private fun dupX1() {
            if (stack.size < 2) return
            val value1 = stack.removeAt(stack.lastIndex)
            val value2 = stack.removeAt(stack.lastIndex)
            stack.add(value1)
            stack.add(value2)
            stack.add(value1)
        }

        private fun dupX2() {
            if (stack.size < MIN_STACK_FOR_DUP_X2) return
            val value1 = stack.removeAt(stack.lastIndex)
            val value2 = stack.removeAt(stack.lastIndex)
            val value3 = stack.removeAt(stack.lastIndex)
            stack.add(value1)
            stack.add(value3)
            stack.add(value2)
            stack.add(value1)
        }

        private fun dup2() {
            if (stack.size < 2) return
            stack.add(stack[stack.lastIndex - 1])
            stack.add(stack[stack.lastIndex - 1])
        }

        private fun dup2X1() {
            if (stack.size < MIN_STACK_FOR_DUP2_X1) return
            val value1 = stack.removeAt(stack.lastIndex)
            val value2 = stack.removeAt(stack.lastIndex)
            val value3 = stack.removeAt(stack.lastIndex)
            stack.add(value2)
            stack.add(value1)
            stack.add(value3)
            stack.add(value2)
            stack.add(value1)
        }

        private fun dup2X2() {
            if (stack.size < MIN_STACK_FOR_DUP2_X2) return
            val value1 = stack.removeAt(stack.lastIndex)
            val value2 = stack.removeAt(stack.lastIndex)
            val value3 = stack.removeAt(stack.lastIndex)
            val value4 = stack.removeAt(stack.lastIndex)
            stack.add(value2)
            stack.add(value1)
            stack.add(value4)
            stack.add(value3)
            stack.add(value2)
            stack.add(value1)
        }

        private fun localName(slot: Int): String = "$LOCAL_VARIABLE_PREFIX$slot"

        private fun swap() {
            if (stack.size < 2) return
            val value1 = stack.removeAt(stack.lastIndex)
            val value2 = stack.removeAt(stack.lastIndex)
            stack.add(value1)
            stack.add(value2)
        }
    }

    private fun getOrCreateField(
        owner: String,
        name: String,
        descriptor: String,
        type: TypeDescriptor,
        isStatic: Boolean
    ): FieldNode {
        val key = "$owner#$name#$descriptor"
        return fieldNodes.getOrPut(key) {
            FieldNode(
                id = nextNodeId(),
                descriptor = FieldDescriptor(
                    declaringClass = typeDescriptor(owner),
                    name = name,
                    type = type
                ),
                isStatic = isStatic
            ).also(graphBuilder::addNode)
        }
    }

    private fun constantNode(value: Any?): ConstantNode {
        val key = value ?: NULL_CONSTANT_KEY
        return constantNodes.getOrPut(key) {
            when (value) {
                null -> NullConstant(nextNodeId())
                is Int -> IntConstant(nextNodeId(), value)
                is Long -> LongConstant(nextNodeId(), value)
                is Float -> FloatConstant(nextNodeId(), value)
                is Double -> DoubleConstant(nextNodeId(), value)
                is String -> StringConstant(nextNodeId(), value)
                else -> StringConstant(nextNodeId(), value.toString())
            }.also(graphBuilder::addNode)
        }
    }

    private fun methodDescriptor(handle: Handle): MethodDescriptor =
        methodDescriptor(handle.owner.replace('/', '.'), handle.name, handle.desc)

    private fun methodDescriptor(owner: String, name: String, descriptor: String): MethodDescriptor {
        val key = "$owner#$name$descriptor"
        return methodDescriptors.getOrPut(key) {
            val methodType = Type.getMethodType(descriptor)
            MethodDescriptor(
                declaringClass = typeDescriptor(owner),
                name = name,
                parameterTypes = methodType.argumentTypes.map(::typeDescriptor),
                returnType = typeDescriptor(methodType.returnType)
            )
        }
    }

    private fun typeDescriptor(type: Type): TypeDescriptor =
        typeDescriptor(type.className)

    private fun typeDescriptor(className: String): TypeDescriptor =
        typeDescriptors.getOrPut(className) { TypeDescriptor(className) }

    private fun classResourcePathToName(path: String): String? {
        if (!path.endsWith(CLASS_FILE_SUFFIX, ignoreCase = true)) return null
        val className = path.removeSuffix(CLASS_FILE_SUFFIX).replace('/', '.')
        return if (className.endsWith(".package-info") || className.endsWith(".module-info")) null else className
    }

    private fun resourceFormat(path: String): String = when {
        path.endsWith(".properties") -> "properties"
        path.endsWith(".yml") || path.endsWith(".yaml") -> "yaml"
        path.endsWith(".json") -> "json"
        path.endsWith(".xml") -> "xml"
        else -> "text"
    }

    private fun resourceProfile(path: String): String? {
        val fileName = path.substringAfterLast('/')
        val match = Regex("""application-([^.]+)\.(properties|json|xml|ya?ml)""").matchEntire(fileName)
        return match?.groupValues?.getOrNull(1)
    }

    private fun artifactKey(origin: String): String? =
        origin.trim().trimEnd('/').substringAfterLast('/').removeSuffix(".jar").takeIf { it.isNotBlank() }

    private fun nextNodeId(): NodeId = NodeId.next()

    private fun log(message: String) {
        config.verbose?.invoke(message)
    }

    private companion object {
        private const val CLASS_FILE_SUFFIX = ".class"
        private const val LOCAL_VARIABLE_PREFIX = "v"
        private const val MIN_STACK_FOR_DUP_X2 = 3
        private const val MIN_STACK_FOR_DUP2_X1 = 3
        private const val MIN_STACK_FOR_DUP2_X2 = 4
        private val NULL_CONSTANT_KEY = Any()
    }
}
