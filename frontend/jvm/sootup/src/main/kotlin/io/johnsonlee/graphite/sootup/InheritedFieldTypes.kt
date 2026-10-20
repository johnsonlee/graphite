package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.jvmTypeDescriptor
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.MemberTypeKey

/** Bind observed symbolic owners without changing erased field identities or declaration scopes. */
internal class InheritedFieldTypes(
    private val declarations: Map<String, ClassDeclarations>,
    private val table: DeclaredTypeTable,
    private val interfaces: Set<String>
) {
    fun bind(nodes: Iterable<FieldNode>): DeclaredTypeTable {
        var fields: MutableMap<MemberTypeKey, Int>? = null
        for (node in nodes) {
            val descriptor = node.descriptor
            val key = MemberTypeKey(descriptor.declaringClass.className, descriptor.name,
                jvmTypeDescriptor(descriptor.type.className))
            if (key !in table.fields) {
                val type = resolve(key)
                if (type != null) {
                    val target = fields ?: LinkedHashMap(table.fields).also { fields = it }
                    target[key] = type
                }
            }
        }
        return fields?.let { table.copy(fields = it).also(DeclaredTypeTable::validate) } ?: table
    }

    /** Unknown earlier branches cannot establish absence and must not expose a later declaration. */
    private fun resolve(key: MemberTypeKey): Int? {
        val pending = ArrayDeque<Visit>()
        val active = HashSet<String>()
        val absent = HashSet<String>()
        var result: Int? = null
        pending.addLast(Visit(key.owner))
        while (pending.isNotEmpty() && result == null) {
            val visit = pending.removeLast()
            if (visit.leaving) {
                active.remove(visit.owner)
                absent.add(visit.owner)
            } else if (visit.owner !in absent) {
                result = visitOwner(key, visit.owner, pending, active)
            }
        }
        return result
    }

    private fun visitOwner(key: MemberTypeKey, owner: String, pending: ArrayDeque<Visit>, active: MutableSet<String>): Int? {
        val declaration = declarations[owner]
        if (declaration == null || !active.add(owner)) {
            pending.clear()
            return null
        }
        val result = table.fields[key.copy(owner = owner)]
        if (result == null) addAncestors(pending, declaration)
        return result
    }

    private fun addAncestors(pending: ArrayDeque<Visit>, declaration: ClassDeclarations) {
        pending.addLast(Visit(declaration.name, leaving = true))
        if (declaration.name !in interfaces) declaration.superName?.let { pending.addLast(Visit(it)) }
        declaration.interfaces.asReversed().forEach { pending.addLast(Visit(it)) }
    }

    private data class Visit(val owner: String, val leaving: Boolean = false)
}
