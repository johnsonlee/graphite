package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.TypeDescriptor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Reads generic signatures from bytecode files.
 *
 * This class extracts generic type information that is stored in the
 * Signature attribute of class files but not typically exposed by
 * bytecode analysis frameworks.
 */
class BytecodeSignatureReader {
    // Cache of class name -> field signatures
    private val fieldSignatures = mutableMapOf<String, MutableMap<String, TypeDescriptor>>()
    // Cache of class name -> method return type signatures
    private val methodReturnSignatures = mutableMapOf<String, MutableMap<String, TypeDescriptor>>()
    // Cache of class name -> method parameter type signatures
    private val methodParamSignatures = mutableMapOf<String, MutableMap<String, List<TypeDescriptor>>>()
    // Cache of class name -> class signature info
    private val classSignatures = mutableMapOf<String, GenericSignatureParser.ClassSignatureInfo>()

    /**
     * Load signatures from a JAR file.
     */
    fun loadFromJar(jarPath: Path) {
        JarFile(jarPath.toFile()).use { jar ->
            jar.entries().asSequence()
                .filter { it.name.endsWith(".class") }
                .forEach { entry ->
                    jar.getInputStream(entry).use { inputStream ->
                        loadFromStream(inputStream)
                    }
                }
        }
    }

    /**
     * Load signatures from a directory of class files.
     */
    fun loadFromDirectory(dirPath: Path) {
        Files.walk(dirPath)
            .filter { it.toString().endsWith(".class") }
            .forEach { classFile ->
                Files.newInputStream(classFile).use { inputStream ->
                    loadFromStream(inputStream)
                }
            }
    }

    /**
     * Load signatures from an input stream containing bytecode.
     */
    fun loadFromStream(inputStream: InputStream) {
        try {
            val reader = ClassReader(inputStream)
            val visitor = SignatureCollectingVisitor()
            reader.accept(visitor, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)

            val className = visitor.className ?: return

            // Store class signature
            visitor.classSignature?.let { sig ->
                GenericSignatureParser.parseClassSignature(sig)?.let { info ->
                    classSignatures[className] = info
                }
            }

            // Store field signatures
            if (visitor.fieldSignatures.isNotEmpty()) {
                fieldSignatures[className] = visitor.fieldSignatures
            }

            // Store method signatures
            if (visitor.methodReturnSignatures.isNotEmpty()) {
                methodReturnSignatures[className] = visitor.methodReturnSignatures
            }
            if (visitor.methodParamSignatures.isNotEmpty()) {
                methodParamSignatures[className] = visitor.methodParamSignatures
            }
        } catch (e: Exception) {
            // Ignore classes that can't be parsed
        }
    }

    /**
     * Get the generic type for a field.
     *
     * @param className Fully qualified class name (e.g., "com.example.MyClass")
     * @param fieldName Field name
     * @return TypeDescriptor with generic arguments, or null if not found
     */
    fun getFieldType(className: String, fieldName: String): TypeDescriptor? {
        return fieldSignatures[className]?.get(fieldName)
    }

    /**
     * Get the generic return type for a method.
     *
     * @param className Fully qualified class name
     * @param methodKey Method key in format "methodName(paramTypes)returnType"
     * @return TypeDescriptor with generic arguments, or null if not found
     */
    fun getMethodReturnType(className: String, methodKey: String): TypeDescriptor? {
        return methodReturnSignatures[className]?.get(methodKey)
    }

    /**
     * Get the generic parameter types for a method.
     *
     * @param className Fully qualified class name
     * @param methodKey Method key in format "methodName(paramTypes)returnType"
     * @return List of TypeDescriptors with generic arguments, or empty list if not found
     */
    fun getMethodParamTypes(className: String, methodKey: String): List<TypeDescriptor> {
        return methodParamSignatures[className]?.get(methodKey) ?: emptyList()
    }

    /**
     * Get class signature info.
     *
     * @param className Fully qualified class name
     * @return ClassSignatureInfo with type parameters and superclass info, or null if not found
     */
    fun getClassSignature(className: String): GenericSignatureParser.ClassSignatureInfo? {
        return classSignatures[className]
    }

    /**
     * Check if a class has generic type parameters.
     */
    fun hasTypeParameters(className: String): Boolean {
        return classSignatures[className]?.typeParameters?.isNotEmpty() == true
    }

    /**
     * Get all field types for a class.
     */
    fun getAllFieldTypes(className: String): Map<String, TypeDescriptor> {
        return fieldSignatures[className] ?: emptyMap()
    }

    /**
     * ASM visitor that collects generic signatures.
     */
    private class SignatureCollectingVisitor : ClassVisitor(Opcodes.ASM9) {
        var className: String? = null
        var classSignature: String? = null
        val fieldSignatures = mutableMapOf<String, TypeDescriptor>()
        val methodReturnSignatures = mutableMapOf<String, TypeDescriptor>()
        val methodParamSignatures = mutableMapOf<String, List<TypeDescriptor>>()

        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?
        ) {
            className = name.replace('/', '.')
            classSignature = signature
        }

        override fun visitField(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            value: Any?
        ): FieldVisitor? {
            // Parse generic signature if present
            GenericSignatureParser.parseFieldSignature(signature)?.let { type ->
                fieldSignatures[name] = type
            }
            return null
        }

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor? {
            if (signature != null) {
                val methodKey = "$name$descriptor"

                // Parse return type
                GenericSignatureParser.parseMethodReturnType(signature)?.let { type ->
                    methodReturnSignatures[methodKey] = type
                }

                // Parse parameter types
                val paramTypes = GenericSignatureParser.parseMethodParameters(signature)
                if (paramTypes.isNotEmpty()) {
                    methodParamSignatures[methodKey] = paramTypes
                }
            }
            return null
        }
    }
}
