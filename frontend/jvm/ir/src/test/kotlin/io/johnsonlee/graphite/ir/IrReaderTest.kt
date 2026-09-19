package io.johnsonlee.graphite.ir

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.EnumValueReference
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.core.TypeRelation
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.ir.v1.Annotation
import io.johnsonlee.graphite.ir.v1.ArtifactDependency
import io.johnsonlee.graphite.ir.v1.ArtifactDependencyBatch
import io.johnsonlee.graphite.ir.v1.BranchComparison
import io.johnsonlee.graphite.ir.v1.CallSite
import io.johnsonlee.graphite.ir.v1.Chunk
import io.johnsonlee.graphite.ir.v1.ClassOrigin
import io.johnsonlee.graphite.ir.v1.ClassOriginBatch
import io.johnsonlee.graphite.ir.v1.Edge
import io.johnsonlee.graphite.ir.v1.EdgeBatch
import io.johnsonlee.graphite.ir.v1.EnumRef
import io.johnsonlee.graphite.ir.v1.EnumValueBatch
import io.johnsonlee.graphite.ir.v1.EnumValueEntry
import io.johnsonlee.graphite.ir.v1.Field
import io.johnsonlee.graphite.ir.v1.FieldRef
import io.johnsonlee.graphite.ir.v1.Frontend
import io.johnsonlee.graphite.ir.v1.Header
import io.johnsonlee.graphite.ir.v1.LocalVariable as IrLocalVariable
import io.johnsonlee.graphite.ir.v1.MethodBatch
import io.johnsonlee.graphite.ir.v1.MethodRef
import io.johnsonlee.graphite.ir.v1.NamedValue
import io.johnsonlee.graphite.ir.v1.Node
import io.johnsonlee.graphite.ir.v1.NodeBatch
import io.johnsonlee.graphite.ir.v1.Parameter
import io.johnsonlee.graphite.ir.v1.ResourceFile
import io.johnsonlee.graphite.ir.v1.ResourceValue
import io.johnsonlee.graphite.ir.v1.Return
import io.johnsonlee.graphite.ir.v1.Source
import io.johnsonlee.graphite.ir.v1.StringBatch
import io.johnsonlee.graphite.ir.v1.Trailer
import io.johnsonlee.graphite.ir.v1.TypeRef
import io.johnsonlee.graphite.ir.v1.TypeRelationBatch
import io.johnsonlee.graphite.ir.v1.TypeRelationEntry
import io.johnsonlee.graphite.ir.v1.Value
import io.johnsonlee.graphite.ir.v1.ValueList
import io.johnsonlee.graphite.ir.v1.ValueMap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builds IR streams in memory, interning strings as a frontend would. */
class IrStream {
    private val strings = LinkedHashMap<String, Int>()
    val chunks = ArrayList<Chunk>()
    var nodes = 0L
    var edges = 0L

    fun str(s: String): Int = strings.getOrPut(s) { strings.size }
    fun type(name: String, vararg args: TypeRef): TypeRef =
        TypeRef.newBuilder().setName(str(name)).addAllTypeArguments(args.toList()).build()
    fun method(owner: String, name: String, params: List<String> = emptyList(), returns: String = "void"): MethodRef =
        MethodRef.newBuilder()
            .setDeclaringClass(type(owner)).setName(str(name))
            .addAllParameterTypes(params.map { type(it) }).setReturnType(type(returns)).build()

    fun header(language: String = "swift", version: Int = IR_SCHEMA_VERSION) = apply {
        chunks.add(
            Chunk.newBuilder().setHeader(
                Header.newBuilder().setSchemaVersion(version).setLanguage(language)
                    .setFrontend(Frontend.newBuilder().setName("graphite-frontend-apple").setVersion("0.1.0"))
                    .setSource(Source.newBuilder().setKind("swiftpm").setPath("/src/AcmeShop"))
                    .putOptions("target", "AcmeShop")
            ).build()
        )
    }

    fun node(id: Int, build: Node.Builder.() -> Unit) = apply {
        nodes++
        chunks.add(Chunk.newBuilder().setNodes(NodeBatch.newBuilder().addNodes(Node.newBuilder().setId(id).apply(build))).build())
    }

    fun edge(from: Int, to: Int, build: Edge.Builder.() -> Unit) = apply {
        edges++
        chunks.add(Chunk.newBuilder().setEdges(EdgeBatch.newBuilder().addEdges(Edge.newBuilder().setFrom(from).setTo(to).apply(build))).build())
    }

    fun chunk(c: Chunk) = apply { chunks.add(c) }

    /** Strings first (as a frontend that interns while emitting would flush them), then the chunks, then the trailer. */
    fun bytes(trailer: Trailer? = null, withHeaderFirst: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        val ordered = ArrayList<Chunk>()
        val headers = chunks.filter { it.hasHeader() }
        val rest = chunks.filterNot { it.hasHeader() }
        if (withHeaderFirst) ordered.addAll(headers)
        ordered.add(Chunk.newBuilder().setStrings(StringBatch.newBuilder().addAllValues(strings.keys)).build())
        if (!withHeaderFirst) ordered.addAll(headers)
        ordered.addAll(rest)
        ordered.add(
            Chunk.newBuilder().setTrailer(
                trailer ?: Trailer.newBuilder().setNodeCount(nodes).setEdgeCount(edges).setStringCount(strings.size.toLong()).build()
            ).build()
        )
        ordered.forEach { it.writeDelimitedTo(out) }
        return out.toByteArray()
    }
}

class IrReaderTest {

    private fun read(bytes: ByteArray) = IrReader().read(ByteArrayInputStream(bytes))

    private fun v(s: String): Value = Value.newBuilder().setStringValue(s).build()

    @Test
    fun `every node and edge kind round-trips into the core model`() {
        val ir = IrStream().header()
        val cart = ir.method("AcmeShop.CartService", "checkout", listOf("Swift.String"), "Swift.Bool")
        val api = ir.method("AcmeShop.ApiClient", "get", listOf("Swift.String"), "Foundation.Data")
        ir.node(0) { setStringConstant(io.johnsonlee.graphite.ir.v1.StringConstant.newBuilder().setValue(ir.str("/orders"))) }
            .node(1) { setIntConstant(io.johnsonlee.graphite.ir.v1.IntConstant.newBuilder().setValue(42)) }
            .node(2) { setLongConstant(io.johnsonlee.graphite.ir.v1.LongConstant.newBuilder().setValue(7L)) }
            .node(3) { setFloatConstant(io.johnsonlee.graphite.ir.v1.FloatConstant.newBuilder().setValue(1.5f)) }
            .node(4) { setDoubleConstant(io.johnsonlee.graphite.ir.v1.DoubleConstant.newBuilder().setValue(2.5)) }
            .node(5) { setBooleanConstant(io.johnsonlee.graphite.ir.v1.BooleanConstant.newBuilder().setValue(true)) }
            .node(6) { setNullConstant(io.johnsonlee.graphite.ir.v1.NullConstant.getDefaultInstance()) }
            .node(7) {
                setEnumConstant(
                    io.johnsonlee.graphite.ir.v1.EnumConstant.newBuilder()
                        .setEnumType(ir.type("AcmeShop.Region")).setEnumName(ir.str("eu"))
                        .addConstructorArgs(v("EU"))
                )
            }
            .node(8) { setLocalVariable(IrLocalVariable.newBuilder().setName(ir.str("path")).setType(ir.type("Swift.String")).setMethod(cart)) }
            .node(9) {
                setField(
                    Field.newBuilder().setField(
                        FieldRef.newBuilder().setDeclaringClass(ir.type("AcmeShop.CartService")).setName(ir.str("client")).setType(ir.type("AcmeShop.ApiClient"))
                    ).setIsStatic(false)
                )
            }
            .node(10) { setParameter(Parameter.newBuilder().setIndex(0).setType(ir.type("Swift.String")).setMethod(cart)) }
            .node(11) { setReturn(Return.newBuilder().setMethod(cart).setActualType(ir.type("Swift.Bool"))) }
            .node(12) { setReturn(Return.newBuilder().setMethod(api)) }
            .node(13) { setResourceFile(ResourceFile.newBuilder().setPath("Info.plist").setSource("AcmeShop").setFormat("plist")) }
            .node(14) {
                setResourceValue(
                    ResourceValue.newBuilder().setPath("Info.plist").setKey("CFBundleName").setValue(v("Acme")).setFormat("plist").setProfile("Debug")
                )
            }
            .node(15) {
                setCallSite(CallSite.newBuilder().setCaller(cart).setCallee(api).setLine(12).setReceiver(9).addArguments(0))
            }
            .node(16) { setCallSite(CallSite.newBuilder().setCaller(api).setCallee(api)) }
            .node(17) {
                setAnnotation(
                    Annotation.newBuilder().setName("@objc").setClassName("AcmeShop.CartService").setMemberName("checkout")
                        .addValues(NamedValue.newBuilder().setName("name").setValue(v("checkout:")))
                        .addValues(NamedValue.newBuilder().setName("flags").setValue(Value.newBuilder().setListValue(ValueList.newBuilder().addValues(Value.newBuilder().setIntValue(3)).addValues(Value.newBuilder().setBoolValue(false)))))
                        .addValues(NamedValue.newBuilder().setName("nested").setValue(Value.newBuilder().setMapValue(ValueMap.newBuilder().addEntries(NamedValue.newBuilder().setName("d").setValue(Value.newBuilder().setDoubleValue(0.5))))))
                        .addValues(NamedValue.newBuilder().setName("region").setValue(Value.newBuilder().setEnumValue(EnumRef.newBuilder().setEnumClass("AcmeShop.Region").setEnumName("eu"))))
                        .addValues(NamedValue.newBuilder().setName("nothing").setValue(Value.newBuilder().setNullValue(true)))
                        .addValues(NamedValue.newBuilder().setName("unset").setValue(Value.getDefaultInstance()))
                )
            }
            .edge(0, 15) { setDataFlow(io.johnsonlee.graphite.ir.v1.DataFlowEdge.newBuilder().setKind(io.johnsonlee.graphite.ir.v1.DataFlowKind.PARAMETER_PASS)) }
            .edge(15, 11) { setDataFlow(io.johnsonlee.graphite.ir.v1.DataFlowEdge.newBuilder().setKind(io.johnsonlee.graphite.ir.v1.DataFlowKind.RETURN_VALUE)) }
            .edge(13, 15) { setResource(io.johnsonlee.graphite.ir.v1.ResourceEdge.newBuilder().setKind(io.johnsonlee.graphite.ir.v1.ResourceRelation.LOADS)) }
            .edge(15, 15) { setCall(io.johnsonlee.graphite.ir.v1.CallEdge.newBuilder().setIsVirtual(true).setIsDynamic(false)) }
            .edge(9, 8) { setType(io.johnsonlee.graphite.ir.v1.TypeEdge.newBuilder().setKind(io.johnsonlee.graphite.ir.v1.TypeRelation.IMPLEMENTS)) }
            .edge(15, 16) {
                setControlFlow(
                    io.johnsonlee.graphite.ir.v1.ControlFlowEdge.newBuilder().setKind(io.johnsonlee.graphite.ir.v1.ControlFlowKind.BRANCH_TRUE)
                        .setComparison(BranchComparison.newBuilder().setOperator(io.johnsonlee.graphite.ir.v1.ComparisonOp.EQ).setComparand(1))
                )
            }
            .edge(16, 15) { setControlFlow(io.johnsonlee.graphite.ir.v1.ControlFlowEdge.newBuilder().setKind(io.johnsonlee.graphite.ir.v1.ControlFlowKind.SEQUENTIAL)) }
            .chunk(Chunk.newBuilder().setMethods(MethodBatch.newBuilder().addMethods(cart).addMethods(api)).build())
            .chunk(
                Chunk.newBuilder().setTypeRelations(
                    TypeRelationBatch.newBuilder()
                        .addRelations(TypeRelationEntry.newBuilder().setSubtype(ir.type("AcmeShop.CartService")).setSupertype(ir.type("AcmeShop.Service")).setRelation(io.johnsonlee.graphite.ir.v1.TypeRelation.IMPLEMENTS))
                        .addRelations(TypeRelationEntry.newBuilder().setSubtype(ir.type("AcmeShop.ApiClient")).setSupertype(ir.type("Foundation.NSObject")).setRelation(io.johnsonlee.graphite.ir.v1.TypeRelation.EXTENDS))
                ).build()
            )
            .chunk(Chunk.newBuilder().setClassOrigins(ClassOriginBatch.newBuilder().addOrigins(ClassOrigin.newBuilder().setClassName("AcmeShop.CartService").setSource("AcmeShop"))).build())
            .chunk(Chunk.newBuilder().setEnumValues(EnumValueBatch.newBuilder().addEntries(EnumValueEntry.newBuilder().setEnumClass("AcmeShop.Region").setEnumName("eu").addValues(v("EU")))).build())
            .chunk(Chunk.newBuilder().setArtifactDependencies(ArtifactDependencyBatch.newBuilder().addDependencies(ArtifactDependency.newBuilder().setFromArtifact("AcmeShop").setToArtifact("Alamofire").setWeight(3))).build())

        val (graph, summary) = read(ir.bytes())

        assertEquals("swift", summary.header.language)
        assertEquals("graphite-frontend-apple", summary.header.frontend.name)
        assertEquals(18L, summary.nodeCount)
        assertEquals(7L, summary.edgeCount)
        assertTrue(summary.stringCount > 0)

        // IR id i is node i + 1.
        assertEquals("/orders", (graph.node(NodeId(1)) as StringConstant).value)
        assertEquals(42, (graph.node(NodeId(2)) as IntConstant).value)
        assertEquals(7L, (graph.node(NodeId(3)) as LongConstant).value)
        assertEquals(1.5f, (graph.node(NodeId(4)) as FloatConstant).value)
        assertEquals(2.5, (graph.node(NodeId(5)) as DoubleConstant).value)
        assertEquals(true, (graph.node(NodeId(6)) as BooleanConstant).value)
        assertTrue(graph.node(NodeId(7)) is NullConstant)
        val enum = graph.node(NodeId(8)) as EnumConstant
        assertEquals("AcmeShop.Region", enum.enumType.className)
        assertEquals("eu", enum.enumName)
        assertEquals(listOf("EU"), enum.constructorArgs)
        val local = graph.node(NodeId(9)) as LocalVariable
        assertEquals("path", local.name)
        assertEquals("AcmeShop.CartService.checkout(Swift.String)", local.method.signature)
        val field = graph.node(NodeId(10)) as FieldNode
        assertEquals("client", field.descriptor.name)
        assertEquals(false, field.isStatic)
        assertEquals(0, (graph.node(NodeId(11)) as ParameterNode).index)
        assertEquals(TypeDescriptor("Swift.Bool"), (graph.node(NodeId(12)) as ReturnNode).actualType)
        assertNull((graph.node(NodeId(13)) as ReturnNode).actualType)
        val file = graph.node(NodeId(14)) as ResourceFileNode
        assertEquals("plist", file.format)
        assertNull(file.profile)
        val value = graph.node(NodeId(15)) as ResourceValueNode
        assertEquals("Acme", value.value)
        assertEquals("Debug", value.profile)
        val call = graph.node(NodeId(16)) as CallSiteNode
        assertEquals("AcmeShop.ApiClient.get(Swift.String)", call.callee.signature)
        assertEquals(12, call.lineNumber)
        assertEquals(NodeId(10), call.receiver)
        assertEquals(listOf(NodeId(1)), call.arguments)
        val bare = graph.node(NodeId(17)) as CallSiteNode
        assertNull(bare.lineNumber)
        assertNull(bare.receiver)
        val annotation = graph.node(NodeId(18)) as AnnotationNode
        assertEquals("@objc", annotation.name)
        assertEquals("checkout:", annotation.values["name"])
        assertEquals(listOf(3L, false), annotation.values["flags"])
        assertEquals(mapOf("d" to 0.5), annotation.values["nested"])
        assertEquals(EnumValueReference("AcmeShop.Region", "eu"), annotation.values["region"])
        assertNull(annotation.values["nothing"])
        assertNull(annotation.values["unset"])
        assertEquals(
            mapOf("@objc" to annotation.values),
            graph.memberAnnotations("AcmeShop.CartService", "checkout")
        )

        val out = graph.outgoing(NodeId(16)).toList()
        assertTrue(out.any { it is DataFlowEdge && it.kind == DataFlowKind.RETURN_VALUE && it.to == NodeId(12) })
        assertTrue(out.any { it is CallEdge && it.isVirtual && !it.isDynamic })
        val branch = out.filterIsInstance<ControlFlowEdge>().single()
        assertEquals(ControlFlowKind.BRANCH_TRUE, branch.kind)
        assertEquals(ComparisonOp.EQ, branch.comparison?.operator)
        assertEquals(NodeId(2), branch.comparison?.comparandNodeId)
        val incoming = graph.incoming(NodeId(16)).toList()
        assertTrue(incoming.any { it is DataFlowEdge && it.kind == DataFlowKind.PARAMETER_PASS && it.from == NodeId(1) })
        assertTrue(incoming.any { it is ResourceEdge && it.kind == ResourceRelation.LOADS })
        assertTrue(incoming.any { it is ControlFlowEdge && it.kind == ControlFlowKind.SEQUENTIAL && it.comparison == null })
        assertTrue(graph.outgoing(NodeId(10)).any { it is TypeEdge && it.kind == TypeRelation.IMPLEMENTS })

        assertEquals(
            setOf("AcmeShop.CartService.checkout(Swift.String)", "AcmeShop.ApiClient.get(Swift.String)"),
            graph.methods(MethodPattern()).map { it.signature }.toSet()
        )
        assertEquals(listOf(TypeDescriptor("AcmeShop.Service")), graph.supertypes(TypeDescriptor("AcmeShop.CartService")).toList())
        assertEquals(listOf(TypeDescriptor("AcmeShop.ApiClient")), graph.subtypes(TypeDescriptor("Foundation.NSObject")).toList())
        assertEquals(mapOf("AcmeShop.CartService" to "AcmeShop"), graph.classOrigins())
        assertEquals(listOf("EU"), graph.enumValues("AcmeShop.Region", "eu"))
        assertEquals(mapOf("AcmeShop" to mapOf("Alamofire" to 3)), graph.artifactDependencies())
    }

    @Test
    fun `strings may arrive in several batches and type arguments nest`() {
        val ir = IrStream().header()
        val list = ir.type("Swift.Array", ir.type("Swift.String"))
        ir.node(0) { setLocalVariable(IrLocalVariable.newBuilder().setName(ir.str("names")).setType(list).setMethod(ir.method("A", "f"))) }
        // A second string batch appended after the first: ids continue.
        val extra = Chunk.newBuilder().setStrings(StringBatch.newBuilder().addValues("late")).build()
        val late = ir.str("late") // interned in the first table in this helper; the batch below is a duplicate that extends the table
        ir.chunk(extra)
        val bytes = ir.bytes(
            Trailer.newBuilder().setNodeCount(1).setEdgeCount(0).setStringCount((late + 1 + 1).toLong()).build()
        )
        val (graph, summary) = read(bytes)
        val local = graph.node(NodeId(1)) as LocalVariable
        assertEquals(TypeDescriptor("Swift.Array", listOf(TypeDescriptor("Swift.String"))), local.type)
        assertEquals((late + 2).toLong(), summary.stringCount)
    }

    @Test
    fun `a file is read the same as a stream`() {
        val ir = IrStream().header()
        ir.node(0) { setIntConstant(io.johnsonlee.graphite.ir.v1.IntConstant.newBuilder().setValue(1)) }
        val file = Files.createTempFile("graphite-ir", ".graphite-ir")
        try {
            Files.write(file, ir.bytes())
            val (graph, summary) = IrReader().read(file)
            assertNotNull(graph.node(NodeId(1)))
            assertEquals(1L, summary.nodeCount)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun failure(bytes: ByteArray): String = assertFailsWith<IrFormatException> { read(bytes) }.message!!

    @Test
    fun `a stream must start with a header of the known version and name a language`() {
        assertEquals("IR stream is empty", failure(ByteArray(0)))
        assertTrue(failure(IrStream().header().bytes(withHeaderFirst = false)).contains("does not start with a header"))
        assertTrue(failure(IrStream().header(version = 2).bytes()).contains("schema version 2 is not 1"))
        assertTrue(failure(IrStream().header().header().bytes()).contains("two headers"))
        assertTrue(failure(IrStream().header(language = "").bytes()).contains("names no language"))
    }

    @Test
    fun `a stream without a trailer is truncated and the trailer must agree with the stream`() {
        val ir = IrStream().header()
        ir.node(0) { setIntConstant(io.johnsonlee.graphite.ir.v1.IntConstant.newBuilder().setValue(1)) }
        val full = ir.bytes()
        // Drop the trailer: the last delimited message.
        val chunks = ArrayList<ByteArray>()
        val input = ByteArrayInputStream(full)
        while (true) {
            val c = Chunk.parseDelimitedFrom(input) ?: break
            chunks.add(ByteArrayOutputStream().also { c.writeDelimitedTo(it) }.toByteArray())
        }
        val truncated = ByteArrayOutputStream().also { out -> chunks.dropLast(1).forEach { out.write(it) } }.toByteArray()
        assertTrue(failure(truncated).contains("no trailer"))
        assertTrue(failure(ir.bytes(Trailer.newBuilder().setNodeCount(5).setEdgeCount(0).setStringCount(0).build())).contains("declares 5 nodes"))
        assertTrue(failure(ir.bytes(Trailer.newBuilder().setNodeCount(1).setEdgeCount(9).setStringCount(0).build())).contains("declares 9 edges"))
        assertTrue(failure(ir.bytes(Trailer.newBuilder().setNodeCount(1).setEdgeCount(0).setStringCount(4).build())).contains("declares 4 strings"))
    }

    @Test
    fun `references must resolve and nodes must be declared once with a kind`() {
        val dangling = IrStream().header()
        dangling.node(0) { setCallSite(CallSite.newBuilder().setCaller(dangling.method("A", "f")).setCallee(dangling.method("B", "g")).addArguments(9)) }
        assertTrue(failure(dangling.bytes()).contains("never declared: [9]"))

        val twice = IrStream().header()
        twice.node(3) { setIntConstant(io.johnsonlee.graphite.ir.v1.IntConstant.newBuilder().setValue(1)) }
        twice.node(3) { setIntConstant(io.johnsonlee.graphite.ir.v1.IntConstant.newBuilder().setValue(2)) }
        assertTrue(failure(twice.bytes()).contains("node 3 twice"))

        val kindless = IrStream().header()
        kindless.node(0) { }
        assertTrue(failure(kindless.bytes()).contains("node 0 has no kind"))

        val emptyChunk = IrStream().header().chunk(Chunk.getDefaultInstance())
        assertTrue(failure(emptyChunk.bytes()).contains("carries nothing"))

        val badString = IrStream().header()
        badString.node(0) { setStringConstant(io.johnsonlee.graphite.ir.v1.StringConstant.newBuilder().setValue(77)) }
        assertTrue(failure(badString.bytes()).contains("string 77 is not in the table"))
    }

    @Test
    fun `edges must carry a kind and enum kinds must be specified`() {
        fun two(): IrStream {
            val ir = IrStream().header()
            ir.node(0) { setIntConstant(io.johnsonlee.graphite.ir.v1.IntConstant.newBuilder().setValue(1)) }
            ir.node(1) { setIntConstant(io.johnsonlee.graphite.ir.v1.IntConstant.newBuilder().setValue(2)) }
            return ir
        }
        assertTrue(failure(two().edge(0, 1) { }.bytes()).contains("edge 0 -> 1 has no kind"))
        assertTrue(failure(two().edge(0, 1) { setDataFlow(io.johnsonlee.graphite.ir.v1.DataFlowEdge.getDefaultInstance()) }.bytes()).contains("data-flow edge has no kind"))
        assertTrue(failure(two().edge(0, 1) { setResource(io.johnsonlee.graphite.ir.v1.ResourceEdge.getDefaultInstance()) }.bytes()).contains("resource edge has no kind"))
        assertTrue(failure(two().edge(0, 1) { setType(io.johnsonlee.graphite.ir.v1.TypeEdge.getDefaultInstance()) }.bytes()).contains("type relation has no kind"))
        assertTrue(failure(two().edge(0, 1) { setControlFlow(io.johnsonlee.graphite.ir.v1.ControlFlowEdge.getDefaultInstance()) }.bytes()).contains("control-flow edge has no kind"))
        assertTrue(
            failure(
                two().edge(0, 1) {
                    setControlFlow(
                        io.johnsonlee.graphite.ir.v1.ControlFlowEdge.newBuilder().setKind(io.johnsonlee.graphite.ir.v1.ControlFlowKind.BRANCH_FALSE)
                            .setComparison(BranchComparison.newBuilder().setComparand(0))
                    )
                }.bytes()
            ).contains("branch comparison has no operator")
        )
        val relation = IrStream().header()
        relation.chunk(
            Chunk.newBuilder().setTypeRelations(
                TypeRelationBatch.newBuilder().addRelations(
                    TypeRelationEntry.newBuilder().setSubtype(relation.type("A")).setSupertype(relation.type("B"))
                )
            ).build()
        )
        assertTrue(failure(relation.bytes()).contains("type relation has no kind"))
    }

    @Test
    fun `every enumerated kind maps to its core counterpart`() {
        fun graphWith(build: Edge.Builder.() -> Unit) = IrStream().header().also { ir ->
            ir.node(0) { setIntConstant(io.johnsonlee.graphite.ir.v1.IntConstant.newBuilder().setValue(1)) }
            ir.node(1) { setIntConstant(io.johnsonlee.graphite.ir.v1.IntConstant.newBuilder().setValue(2)) }
            ir.edge(0, 1, build)
        }.let { read(it.bytes()).first.outgoing(NodeId(1)).single() }

        for (k in io.johnsonlee.graphite.ir.v1.DataFlowKind.values().filter { it.name != "UNRECOGNIZED" && it.number > 0 }) {
            val e = graphWith { setDataFlow(io.johnsonlee.graphite.ir.v1.DataFlowEdge.newBuilder().setKind(k)) } as DataFlowEdge
            assertEquals(k.name, e.kind.name)
        }
        for (k in io.johnsonlee.graphite.ir.v1.ResourceRelation.values().filter { it.name != "UNRECOGNIZED" && it.number > 0 }) {
            val e = graphWith { setResource(io.johnsonlee.graphite.ir.v1.ResourceEdge.newBuilder().setKind(k)) } as ResourceEdge
            assertEquals(k.name, e.kind.name)
        }
        for (k in io.johnsonlee.graphite.ir.v1.TypeRelation.values().filter { it.name != "UNRECOGNIZED" && it.number > 0 }) {
            val e = graphWith { setType(io.johnsonlee.graphite.ir.v1.TypeEdge.newBuilder().setKind(k)) } as TypeEdge
            assertEquals(k.name, e.kind.name)
        }
        for (k in io.johnsonlee.graphite.ir.v1.ControlFlowKind.values().filter { it.name != "UNRECOGNIZED" && it.number > 0 }) {
            val e = graphWith { setControlFlow(io.johnsonlee.graphite.ir.v1.ControlFlowEdge.newBuilder().setKind(k)) } as ControlFlowEdge
            assertEquals(k.name, e.kind.name)
        }
        for (op in io.johnsonlee.graphite.ir.v1.ComparisonOp.values().filter { it.name != "UNRECOGNIZED" && it.number > 0 }) {
            val e = graphWith {
                setControlFlow(
                    io.johnsonlee.graphite.ir.v1.ControlFlowEdge.newBuilder().setKind(io.johnsonlee.graphite.ir.v1.ControlFlowKind.BRANCH_TRUE)
                        .setComparison(BranchComparison.newBuilder().setOperator(op).setComparand(0))
                )
            } as ControlFlowEdge
            assertEquals(op.name, e.comparison?.operator?.name)
        }
    }
}
