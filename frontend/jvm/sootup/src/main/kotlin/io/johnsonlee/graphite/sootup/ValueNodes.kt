package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.ConstantNode
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.ValueNode
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.IntConstant as SootIntConstant
import sootup.core.jimple.common.constant.LongConstant as SootLongConstant
import sootup.core.jimple.common.constant.FloatConstant as SootFloatConstant
import sootup.core.jimple.common.constant.DoubleConstant as SootDoubleConstant
import sootup.core.jimple.common.constant.NullConstant as SootNullConstant
import sootup.core.jimple.common.constant.StringConstant as SootStringConstant
import sootup.core.jimple.common.constant.Constant as SootConstant
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.jimple.common.ref.JFieldRef
import sootup.core.jimple.common.ref.JStaticFieldRef
import sootup.core.jimple.common.ref.JArrayRef

/** Interns locals, fields and constants while preserving allocation and generic types. */
internal class ValueNodes(private val context: SootUpAdapter) {
    private val localNodes = context.localNodes
    private val fieldNodes = context.fieldNodes
    private val constantNodes = context.constantNodes
    private val allocationNodes = context.allocationNodes
    private val graphBuilder = context.graphBuilder
    private val declarations get() = context.declarations
    private val descriptors get() = context.descriptors
    private val metadata get() = context.metadata
    private val reachability get() = context.reachability

    fun getOrCreateValueNode(value: Value, method: MethodDescriptor): ValueNode? {
        return when (value) {
            is Local -> getOrCreateLocal(value, method)
            is SootConstant -> getOrCreateConstant(value)
            is JFieldRef -> getOrCreateField(value, method)
            is JArrayRef -> {
                // For array references like $r1[0], return the base array's node
                // This creates edges from array elements to the array itself,
                // enabling backward tracing from arrays to their elements
                getOrCreateLocal(value.base, method)
            }
            is AbstractInvokeExpr -> null // Handled separately
            else -> null
        }
    }

    fun getOrCreateLocal(local: Local, method: MethodDescriptor): LocalVariable {
        val key = metadata.localKey(method, local.name)
        // Check if we have a typed allocation for this local
        allocationNodes[key]?.let { return it }
        return localNodes.getOrPut(key) {
            val node = LocalVariable(
                id = metadata.nextNodeId("local"),
                name = local.name,
                type = descriptors.toTypeDescriptor(local.type),
                method = method
            )
            graphBuilder.addNode(node)
            node
        }
    }

    fun getOrCreateLocalWithType(local: Local, method: MethodDescriptor, type: TypeDescriptor): LocalVariable {
        val key = metadata.localKey(method, local.name)
        return localNodes.getOrPut(key) {
            val node = LocalVariable(
                id = metadata.nextNodeId("local"),
                name = local.name,
                type = type,
                method = method
            )
            graphBuilder.addNode(node)
            node
        }
    }

    fun getOrCreateConstant(constant: SootConstant): ConstantNode {
        val value = extractConstantValue(constant)
        return constantNodes.getOrPut(value ?: constant) {
            val node = when (constant) {
                is SootIntConstant -> IntConstant(
                    id = metadata.nextNodeId(CONST_NODE_PREFIX),
                    value = constant.value
                )
                is SootLongConstant -> LongConstant(
                    id = metadata.nextNodeId(CONST_NODE_PREFIX),
                    value = constant.value
                )
                is SootFloatConstant -> FloatConstant(
                    id = metadata.nextNodeId(CONST_NODE_PREFIX),
                    value = constant.value
                )
                is SootDoubleConstant -> DoubleConstant(
                    id = metadata.nextNodeId(CONST_NODE_PREFIX),
                    value = constant.value
                )
                // Note: JVM bytecode represents boolean true/false as int constants (1/0),
                // so SootUp produces IntConstant, not BooleanConstant, for boolean values.
                is SootStringConstant -> StringConstant(
                    id = metadata.nextNodeId(CONST_NODE_PREFIX),
                    value = constant.value
                )
                is SootNullConstant -> NullConstant(
                    id = metadata.nextNodeId(CONST_NODE_PREFIX)
                )
                else -> {
                    declarations.log { "Unsupported constant type: ${constant.javaClass.simpleName} = $constant" }
                    IntConstant(
                        id = metadata.nextNodeId(CONST_NODE_PREFIX),
                        value = 0
                    )
                }
            }
            graphBuilder.addNode(node)
            node
        }
    }

    /**
     * The node of the field [fieldRef] names, read by [caller]: a static field is the one of the
     * class that declares it ([resolvedFieldSignature]); an instance field keeps the class the
     * reference spells, since javac spells every inherited instance field through the subclass
     * and resolving them is an identity change on a large share of every graph's field nodes.
     */
    fun getOrCreateField(fieldRef: JFieldRef, caller: MethodDescriptor): FieldNode {
        val fieldSig = if (fieldRef is JStaticFieldRef) descriptors.resolvedFieldSignature(fieldRef) else fieldRef.fieldSignature
        if (fieldRef is JStaticFieldRef) reachability.noteClassInitialized(fieldSig.declClassType.fullyQualifiedName, caller)
        val key = fieldSig.toString()
        return fieldNodes.getOrPut(key) {
            val declaringClassName = fieldSig.declClassType.fullyQualifiedName
            val fieldName = fieldSig.name
            val baseType = descriptors.toTypeDescriptor(fieldSig.type)

            // Try to get generic type from bytecode signature
            val fieldType = descriptors.getFieldTypeWithGenerics(declaringClassName, fieldName, baseType)

            val node = FieldNode(
                id = metadata.nextNodeId("field"),
                descriptor = FieldDescriptor(
                    declaringClass = descriptors.toTypeDescriptor(fieldSig.declClassType),
                    name = fieldName,
                    type = fieldType
                ),
                isStatic = fieldRef is JStaticFieldRef
            )
            graphBuilder.addNode(node)
            node
        }
    }

    fun extractConstantValue(constant: SootConstant): Any? {
        return when (constant) {
            is SootIntConstant -> constant.value
            is SootLongConstant -> constant.value
            is SootFloatConstant -> constant.value
            is SootDoubleConstant -> constant.value
            is SootStringConstant -> constant.value
            is SootNullConstant -> null
            else -> null
        }
    }
}
