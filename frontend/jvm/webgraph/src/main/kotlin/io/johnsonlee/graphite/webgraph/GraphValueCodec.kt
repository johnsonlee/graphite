package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.EnumValueReference
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import java.io.DataInput
import java.io.DataOutputStream

/** Shares the descriptor and heterogeneous-value wire format across node and metadata records. */
internal object GraphValueCodec {
    fun collectMethodDescriptorStrings(md: MethodDescriptor, dest: MutableSet<String>) {
        dest.add(md.declaringClass.className)
        dest.add(md.name)
        for (p in md.parameterTypes) dest.add(p.className)
        dest.add(md.returnType.className)
    }

    fun collectAnyValueStrings(values: List<Any?>, dest: MutableSet<String>) {
        for (value in values) {
            collectAnyValueString(value, dest)
        }
    }

    fun collectAnyValueString(value: Any?, dest: MutableSet<String>) {
        when (value) {
            is String -> dest.add(value)
            is EnumValueReference -> {
                dest.add(value.enumClass)
                dest.add(value.enumName)
            }
            is List<*> -> value.forEach { collectAnyValueString(it, dest) }
            is Int,
            is Long,
            is Float,
            is Double,
            is Boolean,
            null -> {}
            else -> dest.add(value.toString())
        }
    }

    fun writeMethodDescriptor(dos: DataOutputStream, md: MethodDescriptor, strings: StringTable) {
        dos.writeInt(strings.indexOf(md.declaringClass.className))
        dos.writeInt(strings.indexOf(md.name))
        dos.writeInt(md.parameterTypes.size)
        for (p in md.parameterTypes) dos.writeInt(strings.indexOf(p.className))
        dos.writeInt(strings.indexOf(md.returnType.className))
    }

    fun readMethodDescriptor(dis: DataInput, strings: StringTable): MethodDescriptor {
        val className = TypeDescriptor(strings.get(dis.readInt()))
        val name = strings.get(dis.readInt())
        val paramCount = dis.readInt()
        val params = (0 until paramCount).map { TypeDescriptor(strings.get(dis.readInt())) }
        val returnType = TypeDescriptor(strings.get(dis.readInt()))
        return MethodDescriptor(className, name, params, returnType)
    }

    fun writeAnyValue(dos: DataOutputStream, value: Any?, strings: StringTable) {
        when (value) {
            is Int -> {
                dos.writeByte(NodeSerializer.VAL_INT)
                dos.writeInt(value)
            }
            is Long -> {
                dos.writeByte(NodeSerializer.VAL_LONG)
                dos.writeLong(value)
            }
            is String -> {
                dos.writeByte(NodeSerializer.VAL_STRING)
                dos.writeInt(strings.indexOf(value))
            }
            is Float -> {
                dos.writeByte(NodeSerializer.VAL_FLOAT)
                dos.writeFloat(value)
            }
            is Double -> {
                dos.writeByte(NodeSerializer.VAL_DOUBLE)
                dos.writeDouble(value)
            }
            is Boolean -> {
                dos.writeByte(NodeSerializer.VAL_BOOLEAN)
                dos.writeBoolean(value)
            }
            null -> {
                dos.writeByte(NodeSerializer.VAL_NULL)
            }
            is EnumValueReference -> {
                dos.writeByte(NodeSerializer.VAL_ENUM_REF)
                dos.writeInt(strings.indexOf(value.enumClass))
                dos.writeInt(strings.indexOf(value.enumName))
            }
            is List<*> -> {
                dos.writeByte(NodeSerializer.VAL_LIST)
                dos.writeInt(value.size)
                value.forEach { writeAnyValue(dos, it, strings) }
            }
            else -> {
                dos.writeByte(NodeSerializer.VAL_STRING)
                dos.writeInt(strings.indexOf(value.toString()))
            }
        }
    }

    fun readAnyValue(dis: DataInput, strings: StringTable, formatVersion: Int): Any? =
        when (dis.readByte().toInt()) {
            NodeSerializer.VAL_INT -> dis.readInt()
            NodeSerializer.VAL_LONG -> dis.readLong()
            NodeSerializer.VAL_STRING -> strings.get(dis.readInt())
            NodeSerializer.VAL_FLOAT -> dis.readFloat()
            NodeSerializer.VAL_DOUBLE -> dis.readDouble()
            NodeSerializer.VAL_BOOLEAN -> dis.readBoolean()
            NodeSerializer.VAL_NULL -> null
            NodeSerializer.VAL_ENUM_REF -> EnumValueReference(strings.get(dis.readInt()), strings.get(dis.readInt()))
            NodeSerializer.VAL_LIST -> {
                require(formatVersion >= NodeSerializer.TRANSITIONAL_FORMAT_VERSION) {
                    "Encountered list value in GraphStore format version $formatVersion. Re-save the graph with a current Graphite build."
                }
                List(dis.readInt()) { readAnyValue(dis, strings, formatVersion) }
            }
            else -> strings.get(dis.readInt()) // fallback
        }

    fun readAnnotationValue(dis: DataInput, strings: StringTable, formatVersion: Int): Any? {
        return if (formatVersion == NodeSerializer.LEGACY_FORMAT_VERSION) {
            strings.get(dis.readInt()).let { it.ifEmpty { null } }
        } else {
            readAnyValue(dis, strings, formatVersion)
        }
    }
}
