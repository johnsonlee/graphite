package io.johnsonlee.graphite.ir

import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.core.TypeRelation
import io.johnsonlee.graphite.ir.v1.Annotation
import io.johnsonlee.graphite.ir.v1.ArtifactDependency
import io.johnsonlee.graphite.ir.v1.ArtifactDependencyBatch
import io.johnsonlee.graphite.ir.v1.BranchComparison
import io.johnsonlee.graphite.ir.v1.CallSite
import io.johnsonlee.graphite.ir.v1.Chunk
import io.johnsonlee.graphite.ir.v1.ClassOrigin
import io.johnsonlee.graphite.ir.v1.ClassOriginBatch
import io.johnsonlee.graphite.ir.v1.EnumRef
import io.johnsonlee.graphite.ir.v1.EnumValueBatch
import io.johnsonlee.graphite.ir.v1.EnumValueEntry
import io.johnsonlee.graphite.ir.v1.Field
import io.johnsonlee.graphite.ir.v1.FieldRef
import io.johnsonlee.graphite.ir.v1.LocalVariable as IrLocalVariable
import io.johnsonlee.graphite.ir.v1.MethodBatch
import io.johnsonlee.graphite.ir.v1.NamedValue
import io.johnsonlee.graphite.ir.v1.Parameter
import io.johnsonlee.graphite.ir.v1.ResourceFile
import io.johnsonlee.graphite.ir.v1.ResourceValue
import io.johnsonlee.graphite.ir.v1.Return
import io.johnsonlee.graphite.ir.v1.TypeRelationBatch
import io.johnsonlee.graphite.ir.v1.TypeRelationEntry
import io.johnsonlee.graphite.ir.v1.Value
import io.johnsonlee.graphite.ir.v1.ValueList
import io.johnsonlee.graphite.ir.v1.ValueMap

/** Complete wire-kind fixture shared by the model roundtrip assertions. */
internal class AllKindsIrFixture {
    private val ir = IrStream().header()
    private val cart = ir.method("AcmeShop.CartService", "checkout", listOf("Swift.String"), "Swift.Bool")
    private val api = ir.method("AcmeShop.ApiClient", "get", listOf("Swift.String"), "Foundation.Data")

    fun build(): IrStream {
        addConstants()
        addMembers()
        addResources()
        addCallsAndAnnotations()
        addEdges()
        addMetadata()
        return ir
    }

    private fun v(s: String): Value = Value.newBuilder().setStringValue(s).build()

    private fun addConstants() {
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
                        .setEnumType(ir.type("AcmeShop.Region"))
                        .setEnumName(ir.str("eu"))
                        .addConstructorArgs(v("EU")))
            }
    }

    private fun addMembers() {
        ir.node(8) {
                setLocalVariable(IrLocalVariable.newBuilder().setName(ir.str("path")).setType(ir.type("Swift.String")).setMethod(cart))
            }
            .node(9) {
                setField(
                    Field.newBuilder()
                        .setField(
                            FieldRef.newBuilder()
                                .setDeclaringClass(ir.type("AcmeShop.CartService"))
                                .setName(ir.str("client"))
                                .setType(ir.type("AcmeShop.ApiClient")))
                        .setIsStatic(false))
            }
            .node(10) { setParameter(Parameter.newBuilder().setIndex(0).setType(ir.type("Swift.String")).setMethod(cart)) }
            .node(11) { setReturn(Return.newBuilder().setMethod(cart).setActualType(ir.type("Swift.Bool"))) }
            .node(12) { setReturn(Return.newBuilder().setMethod(api)) }
    }

    private fun addResources() {
        ir.node(13) { setResourceFile(ResourceFile.newBuilder().setPath("Info.plist").setSource("AcmeShop").setFormat("plist")) }
            .node(14) {
                setResourceValue(
                    ResourceValue.newBuilder()
                        .setPath("Info.plist")
                        .setKey("CFBundleName")
                        .setValue(v("Acme"))
                        .setFormat("plist")
                        .setProfile("Debug"))
            }
    }

    private fun addCallsAndAnnotations() {
        ir.node(15) { setCallSite(CallSite.newBuilder().setCaller(cart).setCallee(api).setLine(12).setReceiver(9).addArguments(0)) }
            .node(16) { setCallSite(CallSite.newBuilder().setCaller(api).setCallee(api)) }
            .node(17) {
                setAnnotation(
                    Annotation.newBuilder()
                        .setName("@objc")
                        .setClassName("AcmeShop.CartService")
                        .setMemberName("checkout")
                        .addValues(NamedValue.newBuilder().setName("name").setValue(v("checkout:")))
                        .addValues(
                            NamedValue.newBuilder()
                                .setName("flags")
                                .setValue(
                                    Value.newBuilder()
                                        .setListValue(
                                            ValueList.newBuilder()
                                                .addValues(Value.newBuilder().setIntValue(3))
                                                .addValues(Value.newBuilder().setBoolValue(false)))))
                        .addValues(
                            NamedValue.newBuilder()
                                .setName("nested")
                                .setValue(
                                    Value.newBuilder()
                                        .setMapValue(
                                            ValueMap.newBuilder()
                                                .addEntries(
                                                    NamedValue.newBuilder()
                                                        .setName("d")
                                                        .setValue(Value.newBuilder().setDoubleValue(0.5))))))
                        .addValues(
                            NamedValue.newBuilder()
                                .setName("region")
                                .setValue(
                                    Value.newBuilder()
                                        .setEnumValue(EnumRef.newBuilder().setEnumClass("AcmeShop.Region").setEnumName("eu"))))
                        .addValues(NamedValue.newBuilder().setName("nothing").setValue(Value.newBuilder().setNullValue(true)))
                        .addValues(NamedValue.newBuilder().setName("unset").setValue(Value.getDefaultInstance())))
            }
    }

    private fun addEdges() {
        ir.edge(0, 15) {
                setDataFlow(
                    io.johnsonlee.graphite.ir.v1.DataFlowEdge.newBuilder()
                        .setKind(io.johnsonlee.graphite.ir.v1.DataFlowKind.PARAMETER_PASS))
            }
            .edge(15, 11) {
                setDataFlow(
                    io.johnsonlee.graphite.ir.v1.DataFlowEdge.newBuilder().setKind(io.johnsonlee.graphite.ir.v1.DataFlowKind.RETURN_VALUE))
            }
            .edge(13, 15) {
                setResource(
                    io.johnsonlee.graphite.ir.v1.ResourceEdge.newBuilder().setKind(io.johnsonlee.graphite.ir.v1.ResourceRelation.LOADS))
            }
            .edge(15, 15) { setCall(io.johnsonlee.graphite.ir.v1.CallEdge.newBuilder().setIsVirtual(true).setIsDynamic(false)) }
            .edge(9, 8) {
                setType(io.johnsonlee.graphite.ir.v1.TypeEdge.newBuilder().setKind(io.johnsonlee.graphite.ir.v1.TypeRelation.IMPLEMENTS))
            }
            .edge(15, 16) {
                setControlFlow(
                    io.johnsonlee.graphite.ir.v1.ControlFlowEdge.newBuilder()
                        .setKind(io.johnsonlee.graphite.ir.v1.ControlFlowKind.BRANCH_TRUE)
                        .setComparison(
                            BranchComparison.newBuilder().setOperator(io.johnsonlee.graphite.ir.v1.ComparisonOp.EQ).setComparand(1)))
            }
            .edge(16, 15) {
                setControlFlow(
                    io.johnsonlee.graphite.ir.v1.ControlFlowEdge.newBuilder()
                        .setKind(io.johnsonlee.graphite.ir.v1.ControlFlowKind.SEQUENTIAL))
            }
    }

    private fun addMetadata() {
        ir.chunk(Chunk.newBuilder().setMethods(MethodBatch.newBuilder().addMethods(cart).addMethods(api)).build())
            .chunk(
                Chunk.newBuilder()
                    .setTypeRelations(
                        TypeRelationBatch.newBuilder()
                            .addRelations(
                                TypeRelationEntry.newBuilder()
                                    .setSubtype(ir.type("AcmeShop.CartService"))
                                    .setSupertype(ir.type("AcmeShop.Service"))
                                    .setRelation(io.johnsonlee.graphite.ir.v1.TypeRelation.IMPLEMENTS))
                            .addRelations(
                                TypeRelationEntry.newBuilder()
                                    .setSubtype(ir.type("AcmeShop.ApiClient"))
                                    .setSupertype(ir.type("Foundation.NSObject"))
                                    .setRelation(io.johnsonlee.graphite.ir.v1.TypeRelation.EXTENDS)))
                    .build())
            .chunk(
                Chunk.newBuilder()
                    .setClassOrigins(
                        ClassOriginBatch.newBuilder()
                            .addOrigins(ClassOrigin.newBuilder().setClassName("AcmeShop.CartService").setSource("AcmeShop")))
                    .build())
            .chunk(
                Chunk.newBuilder()
                    .setEnumValues(
                        EnumValueBatch.newBuilder()
                            .addEntries(EnumValueEntry.newBuilder().setEnumClass("AcmeShop.Region").setEnumName("eu").addValues(v("EU"))))
                    .build())
            .chunk(
                Chunk.newBuilder()
                    .setArtifactDependencies(
                        ArtifactDependencyBatch.newBuilder()
                            .addDependencies(
                                ArtifactDependency.newBuilder().setFromArtifact("AcmeShop").setToArtifact("Alamofire").setWeight(3)))
                    .build())
    }
}
