package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode as AsmFieldNode
import sootup.core.jimple.common.ref.JFieldRef
import sootup.core.model.SootMethod
import sootup.core.signatures.FieldSignature
import sootup.core.signatures.MethodSignature
import sootup.java.core.JavaSootClass
import sootup.core.types.ClassType
import sootup.core.types.Type

/** Canonicalizes type, field and method descriptors across inherited declarations. */
internal class DescriptorResolution(private val context: SootUpAdapter) {
    private val resolvedFields = context.resolvedFields
    private val resolvedMethodCache = context.resolvedMethodCache
    private val methodDescriptorCache = context.methodDescriptorCache
    private val typeDescriptorCache = context.typeDescriptorCache
    private val fieldGenericTypesByClass = context.fieldGenericTypesByClass
    private val derivedCallOrdinals = context.derivedCallOrdinals
    private val view = context.view
    private val signatureReader = context.signatureReader
    private val resourceAccessor = context.resourceAccessor
    private val resources get() = context.resources

    /**
     * The signature a field reference resolves to: the field of the class that declares it, by
     * the JVM's field resolution ([lookupField]), or the reference itself when nothing the view
     * holds declares it. For a static field this is the identity the graph gives the field, so
     * the store in the declaring class's initialiser and a read spelled through a subclass or
     * an implementing class (`getstatic Child.value`) meet on one node; for an instance field
     * the graph keeps the class the reference spells (see [getOrCreateField]), and the
     * resolution keys the dispatch slot only, so the function value `Base.install` stores in
     * `this.callback` is what `child.callback.get()` dispatches on. Cached per reference.
     */
    fun resolvedFieldSignature(fieldRef: JFieldRef): FieldSignature = resolvedFieldSignature(fieldRef.fieldSignature)

    fun resolvedFieldSignature(fieldSig: FieldSignature): FieldSignature = resolvedFields.getOrPut(fieldSig) {
        val declaring = lookupField(fieldSig.declClassType.fullyQualifiedName, fieldSig.name, fieldSig.type, HashSet())
        if (declaring == null || declaring == fieldSig.declClassType.fullyQualifiedName) {
            fieldSig
        } else {
            FieldSignature(view.identifierFactory.getClassType(declaring), fieldSig.subSignature)
        }
    }

    /** The key of the slot a function value stored in or loaded from [fieldRef] lives in, see [resolvedFieldSignature]. */
    fun fieldSlotKey(fieldRef: JFieldRef): String = resolvedFieldSignature(fieldRef).toString()

    /**
     * The class that declares the field [fieldName] of type [type] a reference through
     * [className] resolves to, by the JVM's field resolution (JVMS 5.4.3.2): the class itself,
     * then its direct superinterfaces, recursively, then its superclass the same way; null when
     * nothing the view holds declares it.
     */
    fun lookupField(className: String, fieldName: String, type: Type, visited: MutableSet<String>): String? {
        val sootClass = if (visited.add(className)) resources.resolveClassByName(className) else null
        return when {
            sootClass == null -> null
            sootClass.fields.any { it.name == fieldName && it.type == type } -> className
            else -> sootClass.interfaces.asSequence()
                .map { lookupField(it.fullyQualifiedName, fieldName, type, visited) }
                .firstOrNull { it != null }
                ?: sootClass.superclass.orElse(null)?.let { lookupField(it.fullyQualifiedName, fieldName, type, visited) }
        }
    }

    fun toTypeDescriptor(type: Type): TypeDescriptor {
        return typeDescriptorCache.getOrPut(type) {
            when (type) {
                is ClassType -> TypeDescriptor(
                    className = type.fullyQualifiedName,
                    typeArguments = emptyList() // Base type without generics
                )
                // One naming rule with the fold pass, whose keys must match the graph's.
                else -> TypeDescriptor(className = graphTypeName(type))
            }
        }
    }

    /**
     * Get field type with generic arguments from bytecode signature.
     */
    fun getFieldTypeWithGenerics(
        declaringClass: String,
        fieldName: String,
        fallbackType: TypeDescriptor
    ): TypeDescriptor {
        val generic = signatureReader?.getFieldType(declaringClass, fieldName)
            ?: fieldGenericTypes(declaringClass)[fieldName]
            ?: return fallbackType
        // A Signature can name T or an owner-qualified inner type; neither replaces the JVM
        // descriptor used to identify this field. Retain arguments for existing type analysis.
        return generic.copy(className = fallbackType.className)
    }

    fun fieldGenericTypes(className: String): Map<String, TypeDescriptor> =
        fieldGenericTypesByClass.getOrPut(className) {
            val sootClass = resources.resolveClassByName(className) as? JavaSootClass ?: return@getOrPut emptyMap()
            val signatures = fieldSignatures(sootClass, className) ?: return@getOrPut emptyMap()
            val genericTypes = HashMap<String, TypeDescriptor>(signatures.size)
            for ((field, signature) in signatures) {
                val type = GenericSignatureParser.parseFieldSignature(signature) ?: continue
                genericTypes[field] = type
            }
            genericTypes
        }

    /**
     * The generic signatures of the fields of [className]: kept by the location that parsed the
     * class ([ParsedClassLocation]), else read again from the class file for a location that
     * did not (an APK, a platform jar).
     */
    @Suppress("ReturnCount")
    fun fieldSignatures(sootClass: JavaSootClass, className: String): Map<String, String>? {
        (sootClass.classSource.analysisInputLocation as? ParsedClassLocation)?.fieldSignatures(className)?.let { return it }
        val classNode = loadClassNodeFromResource(className) ?: return null
        @Suppress("UNCHECKED_CAST")
        val fields = classNode.fields as? List<AsmFieldNode> ?: return null
        return fields.filter { it.signature != null }.associate { it.name to it.signature }
    }

    fun loadClassNodeFromResource(className: String): ClassNode? =
        try {
            val resourcePath = className.replace('.', '/') + CLASS_FILE_SUFFIX
            resourceAccessor.open(resourcePath).use { input ->
                ClassNode().also { classNode ->
                    ClassReader(input).accept(classNode, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                }
            }
        } catch (_: Exception) {
            null
        }

    fun toMethodDescriptor(method: SootMethod): MethodDescriptor {
        return toMethodDescriptor(method.signature)
    }

    /**
     * The ordinal of the next call of [callee] in [caller] that the frontend derived rather than
     * read from the bytecode: `-1`, `-2`, ... per caller and callee, apart from the bytecode's own
     * calls so that those keep the ordinals a pass over the body computes.
     */
    fun nextDerivedCallOrdinal(caller: MethodDescriptor, callee: MethodDescriptor): Int =
        -derivedCallOrdinals.merge("${caller.signature}\u0000${callee.signature}", 1, Int::plus)!!

    fun toMethodDescriptor(sig: MethodSignature): MethodDescriptor {
        return methodDescriptorCache.getOrPut(sig) {
            MethodDescriptor(
                declaringClass = toTypeDescriptor(sig.declClassType),
                name = sig.name,
                parameterTypes = sig.parameterTypes.map { toTypeDescriptor(it) },
                returnType = toTypeDescriptor(sig.type)
            )
        }
    }

    /**
     * Resolve a method signature to the class that actually defines it.
     *
     * When bytecode says `invokevirtual ServiceImpl.doSomething()` but
     * `ServiceImpl` doesn't override `doSomething()` (it's defined in
     * `AbstractService` or `IService`), this method walks the type hierarchy
     * to find the actual defining class and returns a signature with that class.
     *
     * This ensures the callee in CallSiteNode matches the caller in call sites
     * inside the method body, enabling call chain traversal.
     */
    fun resolveMethodDefiningClass(sig: MethodSignature): MethodSignature {
        return resolvedMethodCache.getOrPut(sig) {
            if (resources.declaresMethod(sig.declClassType, sig.subSignature)) return@getOrPut sig

            val hierarchy = view.typeHierarchy
            val declClass = sig.declClassType

            try {
                for (superClass in hierarchy.superClassesOf(declClass)) {
                    if (resources.declaresMethod(superClass, sig.subSignature)) {
                        return@getOrPut MethodSignature(superClass, sig.subSignature)
                    }
                }
            } catch (_: Exception) {
                // class not in hierarchy
            }

            try {
                for (iface in hierarchy.implementedInterfacesOf(declClass)) {
                    if (resources.declaresMethod(iface, sig.subSignature)) {
                        return@getOrPut MethodSignature(iface, sig.subSignature)
                    }
                }
            } catch (_: Exception) {
                // class not in hierarchy
            }

            sig
        }
    }
}
