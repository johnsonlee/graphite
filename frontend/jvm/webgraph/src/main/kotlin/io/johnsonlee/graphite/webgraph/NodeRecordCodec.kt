package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import java.io.DataInput
import java.io.DataOutputStream

/** Collect all unique strings from a list of nodes. */
internal fun collectGraphNodeStrings(nodes: Iterable<Node>, dest: MutableSet<String>) {
    for (node in nodes) {
        collectNodeStrings(node, dest)
    }
}

internal fun collectNodeStrings(node: Node, dest: MutableSet<String>) {
    when (node) {
        is StringConstant -> dest.add(node.value)
        is EnumConstant -> {
            dest.add(node.enumType.className)
            dest.add(node.enumName)
            collectGraphValueStrings(node.constructorArgs, dest)
        }
        is LocalVariable -> {
            dest.add(node.name)
            dest.add(node.type.className)
            collectGraphMethodStrings(node.method, dest)
        }
        is FieldNode -> {
            dest.add(node.descriptor.declaringClass.className)
            dest.add(node.descriptor.name)
            dest.add(node.descriptor.type.className)
        }
        is ParameterNode -> {
            dest.add(node.type.className)
            collectGraphMethodStrings(node.method, dest)
        }
        is ReturnNode -> {
            collectGraphMethodStrings(node.method, dest)
            node.actualType?.let { dest.add(it.className) }
        }
        is ResourceFileNode -> {
            dest.add(node.path)
            dest.add(node.source)
            dest.add(node.format)
            node.profile?.let(dest::add)
        }
        is ResourceValueNode -> {
            dest.add(node.path)
            dest.add(node.key)
            dest.add(node.format)
            node.profile?.let(dest::add)
            collectGraphValueString(node.value, dest)
        }
        is CallSiteNode -> {
            collectGraphMethodStrings(node.caller, dest)
            collectGraphMethodStrings(node.callee, dest)
        }
        is AnnotationNode -> {
            dest.add(node.name)
            dest.add(node.className)
            dest.add(node.memberName)
            for ((k, v) in node.values) {
                dest.add(k)
                collectGraphValueString(v, dest)
            }
        }
        else -> {} // IntConstant, LongConstant, FloatConstant, DoubleConstant, BooleanConstant, NullConstant
    }
}

internal fun graphNodeTagOf(node: Node): Int =
    when (node) {
        is IntConstant -> NodeSerializer.TAG_INT_CONSTANT
        is StringConstant -> NodeSerializer.TAG_STRING_CONSTANT
        is LongConstant -> NodeSerializer.TAG_LONG_CONSTANT
        is FloatConstant -> NodeSerializer.TAG_FLOAT_CONSTANT
        is DoubleConstant -> NodeSerializer.TAG_DOUBLE_CONSTANT
        is BooleanConstant -> NodeSerializer.TAG_BOOLEAN_CONSTANT
        is NullConstant -> NodeSerializer.TAG_NULL_CONSTANT
        is EnumConstant -> NodeSerializer.TAG_ENUM_CONSTANT
        is LocalVariable -> NodeSerializer.TAG_LOCAL_VARIABLE
        is FieldNode -> NodeSerializer.TAG_FIELD_NODE
        is ParameterNode -> NodeSerializer.TAG_PARAMETER_NODE
        is ReturnNode -> NodeSerializer.TAG_RETURN_NODE
        is CallSiteNode -> NodeSerializer.TAG_CALL_SITE_NODE
        is AnnotationNode -> NodeSerializer.TAG_ANNOTATION_NODE
        is ResourceFileNode -> NodeSerializer.TAG_RESOURCE_FILE_NODE
        is ResourceValueNode -> NodeSerializer.TAG_RESOURCE_VALUE_NODE
    }

internal fun writeGraphNode(dos: DataOutputStream, node: Node, strings: StringTable): Int {
    dos.writeInt(node.id.value)
    val tag = graphNodeTagOf(node)
    dos.writeByte(tag)
    // Type-specific fields
    when (node) {
        is IntConstant -> dos.writeInt(node.value)
        is StringConstant -> dos.writeInt(strings.indexOf(node.value))
        is LongConstant -> dos.writeLong(node.value)
        is FloatConstant -> dos.writeFloat(node.value)
        is DoubleConstant -> dos.writeDouble(node.value)
        is BooleanConstant -> dos.writeBoolean(node.value)
        is NullConstant -> {} // no additional data
        else -> writeStructuredNode(dos, node, strings)
    }
    return tag
}

private fun writeStructuredNode(dos: DataOutputStream, node: Node, strings: StringTable) {
    when (node) {
        is EnumConstant -> {
            dos.writeInt(strings.indexOf(node.enumType.className))
            dos.writeInt(strings.indexOf(node.enumName))
            dos.writeInt(node.constructorArgs.size)
            for (arg in node.constructorArgs) writeGraphValue(dos, arg, strings)
        }
        is ResourceFileNode -> {
            dos.writeInt(strings.indexOf(node.path))
            dos.writeInt(strings.indexOf(node.source))
            dos.writeInt(strings.indexOf(node.format))
            dos.writeBoolean(node.profile != null)
            if (node.profile != null) dos.writeInt(strings.indexOf(node.profile!!))
        }
        is ResourceValueNode -> {
            dos.writeInt(strings.indexOf(node.path))
            dos.writeInt(strings.indexOf(node.key))
            writeGraphValue(dos, node.value, strings)
            dos.writeInt(strings.indexOf(node.format))
            dos.writeBoolean(node.profile != null)
            if (node.profile != null) dos.writeInt(strings.indexOf(node.profile!!))
        }
        is CallSiteNode -> {
            writeGraphMethodDescriptor(dos, node.caller, strings)
            writeGraphMethodDescriptor(dos, node.callee, strings)
            dos.writeInt(node.lineNumber ?: -1)
            dos.writeInt(node.receiver?.value ?: -1)
            dos.writeInt(node.arguments.size)
            for (arg in node.arguments) dos.writeInt(arg.value)
        }
        is AnnotationNode -> {
            dos.writeInt(strings.indexOf(node.name))
            dos.writeInt(strings.indexOf(node.className))
            dos.writeInt(strings.indexOf(node.memberName))
            dos.writeInt(node.values.size)
            for ((k, v) in node.values) {
                dos.writeInt(strings.indexOf(k))
                writeGraphValue(dos, v, strings)
            }
        }
        else -> writeMemberNode(dos, node, strings)
    }
}

private fun writeMemberNode(dos: DataOutputStream, node: Node, strings: StringTable) {
    when (node) {
        is LocalVariable -> {
            dos.writeInt(strings.indexOf(node.name))
            dos.writeInt(strings.indexOf(node.type.className))
            writeGraphMethodDescriptor(dos, node.method, strings)
        }
        is FieldNode -> {
            dos.writeInt(strings.indexOf(node.descriptor.declaringClass.className))
            dos.writeInt(strings.indexOf(node.descriptor.name))
            dos.writeInt(strings.indexOf(node.descriptor.type.className))
            dos.writeBoolean(node.isStatic)
        }
        is ParameterNode -> {
            dos.writeInt(node.index)
            dos.writeInt(strings.indexOf(node.type.className))
            writeGraphMethodDescriptor(dos, node.method, strings)
        }
        is ReturnNode -> {
            writeGraphMethodDescriptor(dos, node.method, strings)
            dos.writeBoolean(node.actualType != null)
            if (node.actualType != null) dos.writeInt(strings.indexOf(node.actualType!!.className))
        }
        else -> error("Not a member node: ${node.javaClass.name}")
    }
}

/**
 * The node at the input's position. [ordinals] is the graph's `graph.callsite-ordinals` sidecar, consulted once per call site so the
 * node is built with its ordinal rather than copied after.
 */
internal fun readGraphNode(
    dis: DataInput,
    strings: StringTable,
    formatVersion: Int = NodeSerializer.FORMAT_VERSION,
    ordinals: CallSiteOrdinals = CallSiteOrdinals.EMPTY
): Node {
    val id = NodeId(dis.readInt())
    val tag = dis.readByte().toInt()
    return when (tag) {
        NodeSerializer.TAG_INT_CONSTANT -> IntConstant(id, dis.readInt())
        NodeSerializer.TAG_STRING_CONSTANT -> StringConstant(id, strings.get(dis.readInt()))
        NodeSerializer.TAG_LONG_CONSTANT -> LongConstant(id, dis.readLong())
        NodeSerializer.TAG_FLOAT_CONSTANT -> FloatConstant(id, dis.readFloat())
        NodeSerializer.TAG_DOUBLE_CONSTANT -> DoubleConstant(id, dis.readDouble())
        NodeSerializer.TAG_BOOLEAN_CONSTANT -> BooleanConstant(id, dis.readBoolean())
        NodeSerializer.TAG_NULL_CONSTANT -> NullConstant(id)
        else -> readStructuredNode(tag, id, dis, strings, formatVersion, ordinals)
    }
}

private fun readStructuredNode(
    tag: Int,
    id: NodeId,
    dis: DataInput,
    strings: StringTable,
    formatVersion: Int,
    ordinals: CallSiteOrdinals
): Node =
    when (tag) {
        NodeSerializer.TAG_ENUM_CONSTANT -> {
            val enumType = TypeDescriptor(strings.get(dis.readInt()))
            val enumName = strings.get(dis.readInt())
            val argCount = dis.readInt()
            val args = (0 until argCount).map { readGraphValue(dis, strings, formatVersion) }
            EnumConstant(id, enumType, enumName, args)
        }
        NodeSerializer.TAG_LOCAL_VARIABLE -> {
            val name = strings.get(dis.readInt())
            val type = TypeDescriptor(strings.get(dis.readInt()))
            val method = readGraphMethodDescriptor(dis, strings)
            LocalVariable(id, name, type, method)
        }
        NodeSerializer.TAG_FIELD_NODE -> {
            val declClass = TypeDescriptor(strings.get(dis.readInt()))
            val name = strings.get(dis.readInt())
            val fieldType = TypeDescriptor(strings.get(dis.readInt()))
            val isStatic = dis.readBoolean()
            FieldNode(id, FieldDescriptor(declClass, name, fieldType), isStatic)
        }
        NodeSerializer.TAG_PARAMETER_NODE -> {
            val index = dis.readInt()
            val type = TypeDescriptor(strings.get(dis.readInt()))
            val method = readGraphMethodDescriptor(dis, strings)
            ParameterNode(id, index, type, method)
        }
        NodeSerializer.TAG_RETURN_NODE -> {
            val method = readGraphMethodDescriptor(dis, strings)
            val hasActualType = dis.readBoolean()
            val actualType = if (hasActualType) TypeDescriptor(strings.get(dis.readInt())) else null
            ReturnNode(id, method, actualType)
        }
        NodeSerializer.TAG_RESOURCE_FILE_NODE -> {
            val path = strings.get(dis.readInt())
            val source = strings.get(dis.readInt())
            val format = strings.get(dis.readInt())
            val profile = if (dis.readBoolean()) strings.get(dis.readInt()) else null
            ResourceFileNode(id, path, source, format, profile)
        }
        NodeSerializer.TAG_RESOURCE_VALUE_NODE -> {
            val path = strings.get(dis.readInt())
            val key = strings.get(dis.readInt())
            val value = readGraphValue(dis, strings, formatVersion)
            val format = strings.get(dis.readInt())
            val profile = if (dis.readBoolean()) strings.get(dis.readInt()) else null
            ResourceValueNode(id, path, key, value, format, profile)
        }
        NodeSerializer.TAG_CALL_SITE_NODE -> readCallSite(id, dis, strings, ordinals)
        NodeSerializer.TAG_ANNOTATION_NODE -> {
            val name = strings.get(dis.readInt())
            val className = strings.get(dis.readInt())
            val memberName = strings.get(dis.readInt())
            val kvCount = dis.readInt()
            val values = mutableMapOf<String, Any?>()
            repeat(kvCount) {
                val k = strings.get(dis.readInt())
                val v = readGraphAnnotationValue(dis, strings, formatVersion)
                values[k] = v
            }
            AnnotationNode(id, name, className, memberName, values)
        }
        else -> throw IllegalArgumentException("Unknown node tag: $tag")
    }

private fun readCallSite(id: NodeId, dis: DataInput, strings: StringTable, ordinals: CallSiteOrdinals): CallSiteNode {
    val caller = readGraphMethodDescriptor(dis, strings)
    val callee = readGraphMethodDescriptor(dis, strings)
    val lineNumber = dis.readInt().let { if (it == -1) null else it }
    val receiver = dis.readInt().let { if (it == -1) null else NodeId(it) }
    val argCount = dis.readInt()
    val arguments = (0 until argCount).map { NodeId(dis.readInt()) }
    val ordinal = ordinals[id.value]
    // Only a derived call site (ordinal below zero) can have an origin.
    val origin = if (ordinal != null && ordinal < 0) ordinals.origin(id.value) else null
    return CallSiteNode(id, caller, callee, lineNumber, receiver, arguments, ordinal, origin)
}
