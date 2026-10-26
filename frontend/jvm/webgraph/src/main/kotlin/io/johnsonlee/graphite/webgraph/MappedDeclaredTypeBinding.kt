package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.DeclaredTypeBinding
import io.johnsonlee.graphite.graph.MethodTypes
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap

private const val LOW_WORD_MASK = 0xFFFF_FFFFL

/**
 * The member rows of a mapped GTY05 table looked up by the graph's string IDs, the way the
 * native reader does: a field is (class, name, Java type name) as its node record spells
 * them, a method (class, name, parameter type names, return type name) as its metadata row
 * does, and a row matches when its stored class and name IDs match and its erased type has the
 * same leaf name ID and dimensions as the one the type name resolves to. Nothing here decodes
 * or hashes a class name per node: the rows are indexed once by their two stored IDs, and the
 * handful of rows that share a class and name (overloads, a field and a method of one name)
 * are told apart by comparing erased shapes as integers.
 *
 * The indexes (class and name to rows, Java type name ID to erased row, erased parameter rows
 * to signature row) are built on the first lookup that needs them and shared by every query
 * thread; none of them is written after publication.
 */
internal class MappedDeclaredTypeBinding(
    private val texts: SharedDeclaredTypeTexts,
    private val erased: ErasedDeclaredTypeContext,
    private val fields: MappedMemberRows<Int>,
    private val methods: MappedMemberRows<MethodTypes>
) {
    private val raw = erased.rawTypes

    @Volatile
    private var typeRows: Int2IntOpenHashMap? = null

    @Volatile
    private var fieldIndex: MemberIndex? = null

    @Volatile
    private var methodIndex: MemberIndex? = null

    @Volatile
    private var signatures: Map<CanonicalSignature, Int>? = null

    @Volatile
    private var signatureKeys: Array<CanonicalSignature?>? = null

    /**
     * The declared type ID of the field `owner.name` whose type the graph names [typeName], or
     * [DeclaredTypeBinding.UNBOUND].
     */
    fun fieldTypeId(owner: Int, name: Int, typeName: Int): Int {
        val row = typeRowOf(typeName)
        if (row < 0) return DeclaredTypeBinding.UNBOUND
        val shape = checkNotNull(raw.canonical(row))
        val index = fieldIndex()
        var candidate = index.first(owner, name)
        while (candidate >= 0 && raw.canonical(fields.erasedAt(candidate)) != shape) candidate = index.next(candidate)
        return if (candidate < 0) DeclaredTypeBinding.UNBOUND else fields.intValueAt(candidate)
    }

    /**
     * The declared types of the method `owner.name(parameterTypes) returnType` by string IDs, or null when
     * the table has none.
     */
    fun methodTypes(owner: Int, name: Int, parameterTypes: IntArray, returnType: Int): MethodTypes? {
        val row = methodRow(owner, name, parameterTypes, returnType)
        return if (row < 0) null else methods.valueAt(row)
    }

    /**
     * One declared type ID of that method: parameter [parameterIndex], or the return type for -1;
     * [DeclaredTypeBinding.UNBOUND] when the table has no row or no such parameter. Reads one int
     * of the row's value, where [methodTypes] decodes the whole declaration.
     */
    fun memberTypeId(owner: Int, name: Int, parameterTypes: IntArray, returnType: Int, parameterIndex: Int): Int {
        val row = methodRow(owner, name, parameterTypes, returnType)
        if (row < 0) return DeclaredTypeBinding.UNBOUND
        return methods.methodTypeIdAt(row, parameterIndex).let { if (it < 0) DeclaredTypeBinding.UNBOUND else it }
    }

    /** The presence flags of that method's row, or [DeclaredTypeBinding.UNBOUND]. */
    fun methodShape(owner: Int, name: Int, parameterTypes: IntArray, returnType: Int): Int {
        val row = methodRow(owner, name, parameterTypes, returnType)
        return if (row < 0) DeclaredTypeBinding.UNBOUND else methods.methodShapeAt(row)
    }

    private fun methodRow(owner: Int, name: Int, parameterTypes: IntArray, returnType: Int): Int {
        val wanted = wantedSignature(parameterTypes, returnType) ?: return -1
        val keys = signatureIndex().let { checkNotNull(signatureKeys) }
        val index = methodIndex()
        var candidate = index.first(owner, name)
        while (candidate >= 0 && keys[methods.erasedAt(candidate)] != wanted) candidate = index.next(candidate)
        return candidate
    }

    /** The erased signature of `(parameterTypes) returnType`, or null when a type names no member's row. */
    private fun wantedSignature(parameterTypes: IntArray, returnType: Int): CanonicalSignature? {
        val rows = LongArray(parameterTypes.size + 1)
        for (index in rows.indices) {
            val row = typeRowOf(if (index < parameterTypes.size) parameterTypes[index] else returnType)
            if (row < 0) return null
            rows[index] = checkNotNull(raw.canonical(row))
        }
        return CanonicalSignature(rows)
    }

    /** The canonical erased row for the Java type name with string ID [typeName]; -1 when no member has such a type. */
    fun typeRowOf(typeName: Int): Int = typeRows().get(typeName)

    private fun typeRows(): Int2IntOpenHashMap =
        typeRows ?: synchronized(this) { typeRows ?: buildTypeRows().also { typeRows = it } }

    private fun buildTypeRows(): Int2IntOpenHashMap {
        val index = Int2IntOpenHashMap().also { it.defaultReturnValue(-1) }
        val seen = java.util.BitSet()
        fun add(row: Int) {
            if (!seen[row]) {
                seen.set(row)
                val dimensions = raw.dimensions(row)
                // A non-array's Java name is its leaf name, the very string the node record names
                // its type by. An array's (`int[]`) is a string of its own; one reverse lookup
                // per distinct array shape finds it, if any node in the graph has that type.
                val key = if (dimensions == 0) raw.leafName(row) else texts.table.findId(raw.javaName(row))
                if (key >= 0) index.putIfAbsent(key, row)
            }
        }
        for (row in 0 until fields.rowCount) add(fields.erasedAt(row))
        for (signature in 0 until erased.signatureCount) erased.signatureRows(signature).forEach(::add)
        return index
    }

    private fun fieldIndex(): MemberIndex =
        fieldIndex ?: synchronized(this) { fieldIndex ?: MemberIndex(fields).also { fieldIndex = it } }

    private fun methodIndex(): MemberIndex =
        methodIndex ?: synchronized(this) { methodIndex ?: MemberIndex(methods).also { methodIndex = it } }

    private fun signatureIndex(): Map<CanonicalSignature, Int> =
        signatures ?: synchronized(this) { signatures ?: buildSignatureIndex().also { signatures = it } }

    /** Builds the signature index and publishes [signatureKeys] before the index itself is published. */
    private fun buildSignatureIndex(): Map<CanonicalSignature, Int> {
        val keys = arrayOfNulls<CanonicalSignature>(erased.signatureCount)
        val index = HashMap<CanonicalSignature, Int>(erased.signatureCount * 2)
        for (signature in keys.indices) {
            val rows = erased.signatureRows(signature)
            val key = CanonicalSignature(LongArray(rows.size) { checkNotNull(raw.canonical(rows[it])) })
            keys[signature] = key
            index.putIfAbsent(key, signature)
        }
        signatureKeys = keys
        return index
    }

    /**
     * Rows by their stored (class, name) IDs: the first row of each pair in an open-addressing
     * map, the rest chained through [next] in row order. Built from the rows' first two ints.
     */
    private class MemberIndex(rows: MappedMemberRows<*>) {
        private val first = Long2IntOpenHashMap(rows.rowCount).also { it.defaultReturnValue(-1) }
        private val next = IntArray(rows.rowCount) { -1 }

        init {
            for (row in rows.rowCount - 1 downTo 0) {
                val previous = first.put(key(rows.ownerAt(row), rows.nameAt(row)), row)
                if (previous >= 0) next[row] = previous
            }
        }

        fun first(owner: Int, name: Int): Int = first.get(key(owner, name))
        fun next(row: Int): Int = next[row]

        private fun key(owner: Int, name: Int): Long =
            (owner.toLong() shl Int.SIZE_BITS) or (name.toLong() and LOW_WORD_MASK)
    }

    /** Erased parameter and return shapes as leaf-name IDs and dimensions; equal arrays are one signature. */
    private class CanonicalSignature(private val rows: LongArray) {
        private val hash = rows.contentHashCode()
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean = other is CanonicalSignature && rows.contentEquals(other.rows)
    }
}

/** The member section of a mapped table as rows addressable by their stored integer keys. */
internal interface MappedMemberRows<out V> {
    val rowCount: Int

    /** The first stored int of [row]'s key: the string ID of the owning class. */
    fun ownerAt(row: Int): Int

    /** The second stored int of [row]'s key: the string ID of the member name. */
    fun nameAt(row: Int): Int

    /** The third stored int of [row]'s key: an erased type row for a field, a signature row for a method. */
    fun erasedAt(row: Int): Int

    fun valueAt(row: Int): V

    /** The single int of a field row's value, its type ID. */
    fun intValueAt(row: Int): Int

    /** One type ID of a method row's value: parameter [parameterIndex], the return type for -1; -1 out of range. */
    fun methodTypeIdAt(row: Int, parameterIndex: Int): Int

    /** [DeclaredTypeBinding.HAS_PARAMETERS] and [DeclaredTypeBinding.HAS_BOUNDS] of a method row, from its ints. */
    fun methodShapeAt(row: Int): Int
}
