package sootup.java.core

import java.util.concurrent.ConcurrentHashMap
import sootup.core.signatures.FieldSignature
import sootup.core.signatures.FieldSubSignature
import sootup.core.signatures.MethodSignature
import sootup.core.signatures.MethodSubSignature
import sootup.core.signatures.PackageName
import sootup.core.types.ClassType
import sootup.core.types.PrimitiveType
import sootup.core.types.Type
import sootup.core.types.VoidType
import sootup.java.core.types.JavaClassType

/**
 * Reuse class types for raw names and immutable typed sub-signatures within one view. Outer
 * signatures stay fresh and retain the supplied type objects; string signature overloads,
 * arrays and custom types retain SootUp's behavior. Strong entries live with this factory's view
 * and grow with its distinct raw names and eligible sub-signature keys.
 */
internal class GraphiteIdentifierFactory(
    private val delegate: JavaIdentifierFactory = JavaIdentifierFactory.getInstance()
) : JavaIdentifierFactory() {
    private val classTypes = ConcurrentHashMap<String, JavaClassType>()
    private val methodSubSignatures = ConcurrentHashMap<MethodKey, MethodSubSignature>()
    private val fieldSubSignatures = ConcurrentHashMap<FieldKey, FieldSubSignature>()

    override fun getMethodSignature(
        declaringClassSignature: ClassType?,
        methodName: String?,
        fqReturnType: Type?,
        parameters: List<Type>?
    ): MethodSignature = when {
        declaringClassSignature == null || fqReturnType == null ->
            super.getMethodSignature(declaringClassSignature, methodName, fqReturnType, parameters)
        methodName == null || parameters == null ->
            super.getMethodSignature(declaringClassSignature, methodName, fqReturnType, parameters)
        !isCacheableType(declaringClassSignature) || !isCacheableType(fqReturnType) || !parameters.all(::isCacheableType) ->
            super.getMethodSignature(declaringClassSignature, methodName, fqReturnType, parameters)
        else -> {
            val lookup = MethodKey(methodName, fqReturnType, parameters)
            val subSignature = methodSubSignatures[lookup] ?: run {
                val candidate = MethodSubSignature(methodName, parameters, fqReturnType)
                // Only the stock constructor's immutable copy may become a retained map key.
                val stored = MethodKey(methodName, fqReturnType, candidate.parameterTypes)
                methodSubSignatures.putIfAbsent(stored, candidate) ?: candidate
            }
            MethodSignature(declaringClassSignature, subSignature)
        }
    }

    override fun getFieldSignature(
        fieldName: String?,
        declaringClassSignature: ClassType?,
        fieldType: Type?
    ): FieldSignature = when {
        fieldName == null || declaringClassSignature == null || fieldType == null ->
            super.getFieldSignature(fieldName, declaringClassSignature, fieldType)
        !isCacheableType(declaringClassSignature) || !isCacheableType(fieldType) ->
            super.getFieldSignature(fieldName, declaringClassSignature, fieldType)
        else -> {
            val key = FieldKey(fieldName, fieldType)
            val subSignature = fieldSubSignatures[key] ?: FieldSubSignature(fieldName, fieldType).let { candidate ->
                fieldSubSignatures.putIfAbsent(key, candidate) ?: candidate
            }
            FieldSignature(declaringClassSignature, subSignature)
        }
    }

    override fun getClassType(fullyQualifiedClassName: String?): JavaClassType {
        // Preserve the delegate's handling of null rather than passing it to ConcurrentHashMap.
        if (fullyQualifiedClassName == null) return delegate.getClassType(fullyQualifiedClassName)
        return classTypes[fullyQualifiedClassName] ?: delegate.getClassType(fullyQualifiedClassName).let { type ->
            classTypes.putIfAbsent(fullyQualifiedClassName, type) ?: type
        }
    }

    override fun getClassType(className: String?, packageName: String?): JavaClassType =
        delegate.getClassType(className, packageName)

    override fun getPackageName(packageName: String): PackageName = delegate.getPackageName(packageName)

    // Sharing a cached hash or rendering is safe only for these immutable built-in types.
    // Arrays and custom types deliberately retain the superclass's fresh-signature behavior.
    private fun isCacheableType(type: Type?): Boolean {
        if (type?.javaClass == JavaClassType::class.java) {
            val packageName: PackageName? = (type as JavaClassType).packageName
            return packageName?.javaClass == PackageName::class.java
        }
        return type === PrimitiveType.getInt() || type === PrimitiveType.getLong() ||
            type === PrimitiveType.getBoolean() || type === PrimitiveType.getByte() ||
            type === PrimitiveType.getShort() || type === PrimitiveType.getChar() ||
            type === PrimitiveType.getFloat() || type === PrimitiveType.getDouble() ||
            type === VoidType.getInstance()
    }

    private class FieldKey(private val name: String, private val type: Type?) {
        override fun hashCode(): Int = HASH_MULTIPLIER * name.hashCode() + System.identityHashCode(type)

        override fun equals(other: Any?): Boolean =
            other is FieldKey && name == other.name && type === other.type
    }

    private class MethodKey(
        private val name: String,
        private val type: Type?,
        private val parameters: List<Type>
    ) {
        private val hash = parameters.fold(HASH_MULTIPLIER * name.hashCode() + System.identityHashCode(type)) { result, parameter ->
            HASH_MULTIPLIER * result + System.identityHashCode(parameter)
        }

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (other !is MethodKey || name != other.name || type !== other.type) {
                return false
            }
            if (parameters.size != other.parameters.size) return false
            val first = parameters.iterator()
            val second = other.parameters.iterator()
            while (first.hasNext()) {
                if (first.next() !== second.next()) return false
            }
            return true
        }
    }

    private companion object {
        const val HASH_MULTIPLIER = 31
    }
}
