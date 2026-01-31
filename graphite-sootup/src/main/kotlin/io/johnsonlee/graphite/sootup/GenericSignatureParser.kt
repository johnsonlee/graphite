package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.TypeDescriptor
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import org.objectweb.asm.Opcodes

/**
 * Parses JVM generic signatures to extract type arguments.
 *
 * JVM signatures follow the format defined in JVMS §4.7.9.1.
 * Examples:
 * - `Ljava/util/List<Ljava/lang/String;>;` -> List<String>
 * - `Ljava/util/Map<Ljava/lang/String;Ljava/lang/Integer;>;` -> Map<String, Integer>
 * - `Lcom/example/ApiResponse<Lcom/example/User;>;` -> ApiResponse<User>
 */
object GenericSignatureParser {

    /**
     * Parse a field signature to extract the type with generic arguments.
     *
     * @param signature The JVM field signature (e.g., "Ljava/util/List<Ljava/lang/String;>;")
     * @return TypeDescriptor with populated typeArguments, or null if parsing fails
     */
    fun parseFieldSignature(signature: String?): TypeDescriptor? {
        if (signature.isNullOrBlank()) return null

        return try {
            val visitor = TypeSignatureVisitor()
            SignatureReader(signature).acceptType(visitor)
            visitor.toTypeDescriptor()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse a method signature to extract return type with generic arguments.
     *
     * @param signature The JVM method signature
     * @return TypeDescriptor for the return type, or null if parsing fails
     */
    fun parseMethodReturnType(signature: String?): TypeDescriptor? {
        if (signature.isNullOrBlank()) return null

        return try {
            val visitor = MethodSignatureVisitor()
            SignatureReader(signature).accept(visitor)
            visitor.finalize()
            visitor.returnType
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse a method signature to extract parameter types with generic arguments.
     *
     * @param signature The JVM method signature
     * @return List of TypeDescriptors for parameters, or empty list if parsing fails
     */
    fun parseMethodParameters(signature: String?): List<TypeDescriptor> {
        if (signature.isNullOrBlank()) return emptyList()

        return try {
            val visitor = MethodSignatureVisitor()
            SignatureReader(signature).accept(visitor)
            visitor.finalize()
            visitor.parameterTypes
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Parse a class signature to extract superclass and interface type arguments.
     *
     * @param signature The JVM class signature
     * @return Pair of (superclass, interfaces) with their type arguments
     */
    fun parseClassSignature(signature: String?): ClassSignatureInfo? {
        if (signature.isNullOrBlank()) return null

        return try {
            val visitor = ClassSignatureVisitor()
            SignatureReader(signature).accept(visitor)
            visitor.finalize()
            ClassSignatureInfo(
                typeParameters = visitor.typeParameters,
                superClass = visitor.superClass,
                interfaces = visitor.interfaces
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Visitor for parsing type signatures (fields, return types, etc.)
     */
    private class TypeSignatureVisitor : SignatureVisitor(Opcodes.ASM9) {
        private var className: String? = null
        private val typeArguments = mutableListOf<TypeDescriptor>()
        private var arrayDimension = 0
        private var currentTypeArgVisitor: TypeSignatureVisitor? = null

        override fun visitClassType(name: String) {
            className = name.replace('/', '.')
        }

        override fun visitTypeVariable(name: String) {
            // Type variable like E, T, K, V
            className = name
        }

        override fun visitTypeArgument() {
            // Finalize previous type argument visitor if any
            finalizePreviousTypeArg()
            // Wildcard without bound (?)
            typeArguments.add(TypeDescriptor("?"))
        }

        override fun visitTypeArgument(wildcard: Char): SignatureVisitor {
            // Finalize previous type argument visitor before starting new one
            finalizePreviousTypeArg()
            val visitor = TypeSignatureVisitor()
            currentTypeArgVisitor = visitor
            return visitor
        }

        private fun finalizePreviousTypeArg() {
            currentTypeArgVisitor?.let { visitor ->
                visitor.toTypeDescriptor()?.let { typeArguments.add(it) }
                currentTypeArgVisitor = null
            }
        }

        override fun visitEnd() {
            // Finalize any remaining type argument
            finalizePreviousTypeArg()
        }

        override fun visitArrayType(): SignatureVisitor {
            arrayDimension++
            return this
        }

        override fun visitBaseType(descriptor: Char) {
            className = when (descriptor) {
                'Z' -> "boolean"
                'B' -> "byte"
                'C' -> "char"
                'S' -> "short"
                'I' -> "int"
                'J' -> "long"
                'F' -> "float"
                'D' -> "double"
                'V' -> "void"
                else -> "unknown"
            }
        }

        fun toTypeDescriptor(): TypeDescriptor? {
            val name = className ?: return null
            val finalName = if (arrayDimension > 0) {
                name + "[]".repeat(arrayDimension)
            } else {
                name
            }
            return TypeDescriptor(finalName, typeArguments)
        }
    }

    /**
     * Visitor for parsing method signatures.
     */
    private class MethodSignatureVisitor : SignatureVisitor(Opcodes.ASM9) {
        val parameterTypes = mutableListOf<TypeDescriptor>()
        var returnType: TypeDescriptor? = null
        private var currentVisitor: TypeSignatureVisitor? = null

        override fun visitParameterType(): SignatureVisitor {
            // Finalize previous parameter if any
            currentVisitor?.toTypeDescriptor()?.let { parameterTypes.add(it) }
            currentVisitor = TypeSignatureVisitor()
            return currentVisitor!!
        }

        override fun visitReturnType(): SignatureVisitor {
            // Finalize last parameter if any
            currentVisitor?.toTypeDescriptor()?.let { parameterTypes.add(it) }
            currentVisitor = TypeSignatureVisitor()
            return currentVisitor!!
        }

        /**
         * Extract the return type after ASM finishes parsing.
         * ASM's SignatureReader.accept() does not call visitEnd() on the top-level
         * MethodSignatureVisitor, so we must explicitly finalize after accept() returns.
         */
        fun finalize() {
            if (returnType == null) {
                returnType = currentVisitor?.toTypeDescriptor()
            }
        }
    }

    /**
     * Visitor for parsing class signatures.
     */
    private class ClassSignatureVisitor : SignatureVisitor(Opcodes.ASM9) {
        val typeParameters = mutableListOf<String>()
        var superClass: TypeDescriptor? = null
        val interfaces = mutableListOf<TypeDescriptor>()
        private var currentVisitor: TypeSignatureVisitor? = null
        private var inSuperClass = false

        override fun visitFormalTypeParameter(name: String) {
            typeParameters.add(name)
        }

        override fun visitSuperclass(): SignatureVisitor {
            inSuperClass = true
            currentVisitor = TypeSignatureVisitor()
            return currentVisitor!!
        }

        override fun visitInterface(): SignatureVisitor {
            // Finalize superclass if not done
            if (inSuperClass) {
                superClass = currentVisitor?.toTypeDescriptor()
                inSuperClass = false
            } else {
                currentVisitor?.toTypeDescriptor()?.let { interfaces.add(it) }
            }
            currentVisitor = TypeSignatureVisitor()
            return currentVisitor!!
        }

        /**
         * Extract the last pending type after ASM finishes parsing.
         * ASM's SignatureReader.accept() does not call visitEnd() on the top-level
         * ClassSignatureVisitor, so we must explicitly finalize after accept() returns.
         */
        fun finalize() {
            if (inSuperClass) {
                superClass = currentVisitor?.toTypeDescriptor()
            } else {
                currentVisitor?.toTypeDescriptor()?.let { interfaces.add(it) }
            }
        }
    }

    /**
     * Information about a class's generic signature.
     */
    data class ClassSignatureInfo(
        val typeParameters: List<String>,
        val superClass: TypeDescriptor?,
        val interfaces: List<TypeDescriptor>
    )
}
