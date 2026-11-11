package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.Constant as SootConstant
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.signatures.MethodSignature
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.BOXED_PRIMITIVES
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.WRAPPER_CLASSES
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.UNBOXING_METHODS
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.RESOURCE_LOOKUP_METHODS
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.RESOURCE_PATH_METHODS
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.RESOURCE_BUNDLE_READ_METHODS
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.PROPERTIES_LOAD_METHODS
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.READER_BRIDGE_CLASSES
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.LOCALE_BUILDER_CLASS
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.LOCALE_BUILDER_CLASS_ALT
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.RESOURCE_BUNDLE_CONTROL_CLASS
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.RESOURCE_BUNDLE_CONTROL_CLASS_ALT
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.PROPERTIES_CLASS
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.CLASS_LOADER_CLASS
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.CLASS_CLASS
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.GSON_CLASS
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.DOCUMENT_BUILDER_CLASS
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.URL_CLASS
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.CHANNELS_CLASS

/** Recognizes wrapper conversions and resource APIs with specialized graph semantics. */
internal class CallClassification(private val context: SootUpAdapter) {
    private val metadata get() = context.metadata
    private val values get() = context.values

    fun argumentNodeIds(args: List<Value>, caller: MethodDescriptor): List<NodeId> {
        if (args.isEmpty()) return emptyList()
        val nodeIds = ArrayList<NodeId>(args.size)
        for (arg in args) {
            val argNode = values.getOrCreateValueNode(arg, caller)
            nodeIds += argNode?.id ?: metadata.nextNodeId("unknown")
        }
        return nodeIds
    }

    /**
     * Check if this is a boxing method like Integer.valueOf(int)
     */
    fun isBoxingMethod(signature: MethodSignature): Boolean {
        val className = signature.declClassType.fullyQualifiedName
        val methodName = signature.name
        return methodName == VALUE_OF_METHOD && className in WRAPPER_CLASSES
    }

    /**
     * True only for the primitive boxing overload, `Wrapper.valueOf(<primitive>)`, called with a constant.
     * Parsing overloads such as `Integer.valueOf(String)` share the name but compute (and may throw), so
     * their String argument is never a constant definition of the result.
     */
    fun isPrimitiveBoxingCall(signature: MethodSignature, args: List<Value>): Boolean {
        val primitive = BOXED_PRIMITIVES[signature.declClassType.fullyQualifiedName]
        return args.singleOrNull() is SootConstant &&
            signature.parameterTypes.singleOrNull()?.toString() == primitive
    }

    /**
     * Check if this is an unboxing method like Integer.intValue()
     */
    fun isUnboxingMethod(signature: MethodSignature): Boolean {
        val className = signature.declClassType.fullyQualifiedName
        val methodName = signature.name
        return className in WRAPPER_CLASSES && methodName in UNBOXING_METHODS
    }

    fun isLocaleBuilderClassName(name: String): Boolean =
        name == LOCALE_BUILDER_CLASS || name == LOCALE_BUILDER_CLASS_ALT

    fun isResourceBundleControlTypeName(name: String): Boolean =
        name == RESOURCE_BUNDLE_CONTROL_CLASS || name == RESOURCE_BUNDLE_CONTROL_CLASS_ALT

    fun isResourceRelevantCall(calleeSignature: MethodSignature): Boolean {
        val declaringClass = calleeSignature.declClassType.fullyQualifiedName
        val methodName = calleeSignature.name
        return methodName in RESOURCE_METHODS_BY_CLASS[declaringClass].orEmpty() ||
            (methodName == INIT_METHOD && declaringClass in READER_BRIDGE_CLASSES)
    }

    private companion object {
        private val RESOURCE_METHODS_BY_CLASS = mapOf(
            PROPERTIES_CLASS to RESOURCE_LOOKUP_METHODS + PROPERTIES_LOAD_METHODS,
            PROPERTY_RESOURCE_BUNDLE_CLASS to RESOURCE_LOOKUP_METHODS + setOf(INIT_METHOD, GET_KEYS_METHOD),
            RESOURCE_BUNDLE_CLASS to RESOURCE_BUNDLE_READ_METHODS + GET_BUNDLE_METHOD,
            CLASS_LOADER_CLASS to RESOURCE_PATH_METHODS,
            CLASS_CLASS to RESOURCE_PATH_METHODS,
            GSON_CLASS to setOf(FROM_JSON_METHOD),
            DOCUMENT_BUILDER_CLASS to setOf(PARSE_METHOD),
            URL_CLASS to setOf(OPEN_STREAM_METHOD),
            CHANNELS_CLASS to setOf(NEW_READER_METHOD)
        )
    }

    /**
     * Extract value from boxing method calls like Integer.valueOf(int), Long.valueOf(long), etc.
     * These are used when enum constructor parameters are wrapper types (Integer, Long, etc.)
     * instead of primitive types (int, long, etc.).
     *
     * Pattern in bytecode: $stackN = staticinvoke Integer.valueOf(1234)
     */
    fun extractBoxedValue(invokeExpr: JStaticInvokeExpr): Any? {
        val methodSig = invokeExpr.methodSignature
        val className = methodSig.declClassType.fullyQualifiedName
        val methodName = methodSig.name

        // Check for boxing methods: Integer.valueOf, Long.valueOf, etc.
        val isBoxingMethod = methodName == VALUE_OF_METHOD && className in WRAPPER_CLASSES

        if (!isBoxingMethod) return null

        // Extract the primitive value from the first argument
        val args = invokeExpr.args
        val arg = args.firstOrNull()
        return when (arg) {
            is SootConstant -> values.extractConstantValue(arg)
            else -> null
        }
    }
}
