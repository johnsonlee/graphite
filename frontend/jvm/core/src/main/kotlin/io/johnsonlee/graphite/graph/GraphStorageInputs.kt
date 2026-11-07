package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.TypeDescriptor
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import java.nio.file.Path

/** Metadata snapshots shared by eager and mapped graph construction. */
internal class GraphMetadataInputs(
    val methodIndex: Map<String, MethodDescriptor>,
    val typeHierarchy: TypeHierarchy,
    val enumValues: Map<String, List<Any?>>,
    val classOrigins: Map<String, String>,
    val syntheticIdentities: Map<String, String>,
    val artifactDependencies: Map<String, Map<String, Int>>,
    // Keys retain the persisted "$className#$memberName" identity.
    val memberAnnotations: Map<String, Map<String, Map<String, Any?>>>,
    val declaredTypes: DeclaredTypeTable,
    val completeness: AnalysisCompleteness?
)

internal class EagerNodeInputs(val byId: Int2ObjectOpenHashMap<Node>, val byType: Map<Class<out Node>, List<Node>>)

internal class EagerEdgeInputs(
    val outgoing: Int2ObjectOpenHashMap<List<Edge>>,
    val incoming: Int2ObjectOpenHashMap<List<Edge>>,
    val count: Long
)

internal class MappedNodeInputs(
    val directory: Path,
    val offsets: LongArray,
    val byType: Map<Class<out Node>, IntArray>,
    val methods: List<MethodDescriptor>,
    val types: List<TypeDescriptor>
)

internal class MappedEdgeInputs(val outgoing: MmapGraph.EdgeOffsetIndex, val incoming: MmapGraph.EdgeOffsetIndex?)

internal class RawBranchInputs(val scopes: List<DefaultGraph.RawBranchScope>, val definitions: Map<Int, IntArray>)
