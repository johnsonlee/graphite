package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor

/** Shared read-only metadata lookups, independent of mapped node and edge decoding. */
abstract class IndexedGraphMetadata internal constructor(metadata: GraphMetadataInputs) : Graph, EnumValueIndexSource {
    private val methodIndex = metadata.methodIndex
    private val typeHierarchy = metadata.typeHierarchy
    private val enumValuesMap = metadata.enumValues
    private val classOriginsMap = metadata.classOrigins
    private val syntheticIdentitiesMap = metadata.syntheticIdentities
    private val artifactDependenciesMap = metadata.artifactDependencies
    private val memberAnnotationsMap = metadata.memberAnnotations
    private val declaredTypeTable = metadata.declaredTypes
    private val analysisCompleteness = metadata.completeness

    override fun declaredTypes(): DeclaredTypeTable = declaredTypeTable

    override fun analysisCompleteness(): AnalysisCompleteness? = analysisCompleteness

    override fun hasMethod(method: MethodDescriptor): Boolean = methodIndex.containsKey(method.identity)

    override fun supertypes(type: TypeDescriptor): Sequence<TypeDescriptor> = typeHierarchy.supertypes(type)

    override fun subtypes(type: TypeDescriptor): Sequence<TypeDescriptor> = typeHierarchy.subtypes(type)

    override fun methods(pattern: MethodPattern): Sequence<MethodDescriptor> =
        methodIndex.values.asSequence().filter { pattern.matches(it) }

    override fun methodCount(): Long = methodIndex.size.toLong()

    override fun methodSlice(pattern: MethodPattern, limit: Int): List<MethodDescriptor> =
        methods(pattern).take(limit.coerceAtLeast(0)).toList()

    override fun enumValues(enumClass: String, enumName: String): List<Any?>? = enumValuesMap["$enumClass#$enumName"]

    override fun enumValueIndex(): Map<String, List<Any?>> = enumValuesMap

    override fun memberAnnotations(className: String, memberName: String): Map<String, Map<String, Any?>> =
        memberAnnotationsMap["$className#$memberName"] ?: emptyMap()

    override fun memberAnnotationIndex(): Map<String, Map<String, Map<String, Any?>>> = memberAnnotationsMap

    override fun classOrigin(className: String): String? = classOriginsMap[className]

    override fun classOrigins(): Map<String, String> = classOriginsMap

    override fun syntheticIdentity(member: String): String? = syntheticIdentitiesMap[member]

    override fun syntheticIdentities(): Map<String, String> = syntheticIdentitiesMap

    override fun artifactDependencies(): Map<String, Map<String, Int>> = artifactDependenciesMap

    override fun typeHierarchyTypes(): Set<String> = typeHierarchy.allKeys()
}
