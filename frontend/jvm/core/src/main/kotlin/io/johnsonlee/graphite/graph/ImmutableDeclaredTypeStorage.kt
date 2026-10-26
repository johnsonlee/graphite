package io.johnsonlee.graphite.graph

/**
 * Capability for type storage whose expressions and complete member bindings cannot change.
 * All exposed collections must be immutable, including nested values and type references.
 * A copied table replacing any backing collection does not inherit this guarantee.
 * Validation is still required before omitting projection work for a presence predicate.
 */
interface ImmutableDeclaredTypeStorage {
    val immutableFields: Map<MemberTypeKey, Int>
    val immutableMethods: Map<MemberTypeKey, MethodTypes>
    val immutableClasses: Map<String, ClassTypes>

    fun isImmutableTable(table: DeclaredTypeTable): Boolean = table.types === this &&
        table.fields === immutableFields && table.methods === immutableMethods && table.classes === immutableClasses
}
