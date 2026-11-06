package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.ResourceFileNode
import java.util.Locale
import java.util.ResourceBundle
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.StringConstant as SootStringConstant
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.jimple.common.expr.JNewExpr
import sootup.core.jimple.common.ref.JStaticFieldRef
import sootup.core.model.SootClass
import sootup.core.signatures.MethodSignature
import sootup.java.core.JavaSootClass
import io.johnsonlee.graphite.sootup.SootUpAdapter.BundleControlSpec

/** Reads ResourceBundle.Control policy and inventories runtime fallback bundles. */
internal class BundleControl(private val context: SootUpAdapter) {
    private val localNodes = context.localNodes
    private val allocationNodes = context.allocationNodes
    private val bundleControlFormatsByLocal = context.bundleControlFormatsByLocal
    private val bundleControlSpecsByLocal = context.bundleControlSpecsByLocal
    private val resourceFilesByPath = context.resourceFilesByPath
    private val runtimeIndexedBundles = context.runtimeIndexedBundles
    private val bundleControlSpecsByClass = context.bundleControlSpecsByClass
    private val graphBuilder = context.graphBuilder
    private val bytecodeMethods get() = context.bytecodeMethods
    private val callKinds get() = context.callKinds
    private val declarations get() = context.declarations
    private val descriptors get() = context.descriptors
    private val literals get() = context.literals
    private val localeValues get() = context.localeValues
    private val metadata get() = context.metadata
    private val resources get() = context.resources

    fun extractBundleControlSpec(
        caller: MethodDescriptor,
        value: Value,
        baseName: String,
        localeSpec: String?
    ): BundleControlSpec? {
        val className = when (value) {
            is Local -> {
                val key = metadata.localKey(caller, value.name)
                bundleControlSpecsByLocal[key]?.let { return it }
                allocationNodes[key]?.type?.className ?: localNodes[key]?.type?.className
            }
            is JNewExpr -> descriptors.toTypeDescriptor(value.type).className
            else -> null
        }
        return className?.let { resolveBundleControlSpec(it) ?: reflectBundleControlSpec(it, baseName, localeSpec) }
    }

    fun extractBundleControlSpec(
        caller: MethodDescriptor,
        calleeSignature: MethodSignature,
        invokeExpr: AbstractInvokeExpr
    ): BundleControlSpec? {
        if (!callKinds.isResourceBundleControlTypeName(calleeSignature.declClassType.fullyQualifiedName)) return null
        val formatArg = extractControlFormat(caller, invokeExpr.args.firstOrNull())
        return when (calleeSignature.name) {
            "getControl" -> BundleControlSpec(formats = controlFormats(formatArg))
            "getNoFallbackControl" -> BundleControlSpec(noFallback = true, formats = controlFormats(formatArg))
            else -> null
        }
    }

    fun extractControlFormat(caller: MethodDescriptor?, value: Value?): String? {
        return when (value) {
            is SootStringConstant -> value.value
            is Local -> caller?.let { bundleControlFormatsByLocal[metadata.localKey(it, value.name)] }
            is JStaticFieldRef -> {
                if (!callKinds.isResourceBundleControlTypeName(value.fieldSignature.declClassType.fullyQualifiedName)) {
                    null
                } else {
                    when (value.fieldSignature.name) {
                        "FORMAT_PROPERTIES" -> RESOURCE_BUNDLE_FORMAT_PROPERTIES
                        "FORMAT_CLASS" -> RESOURCE_BUNDLE_FORMAT_CLASS
                        "FORMAT_DEFAULT" -> null
                        else -> null
                    }
                }
            }
            else -> null
        }
    }

    fun controlFormats(raw: String?): Set<String> = when (raw) {
        RESOURCE_BUNDLE_FORMAT_CLASS -> setOf(RESOURCE_BUNDLE_FORMAT_CLASS)
        RESOURCE_BUNDLE_FORMAT_PROPERTIES -> setOf(RESOURCE_BUNDLE_FORMAT_PROPERTIES)
        else -> setOf(RESOURCE_BUNDLE_FORMAT_PROPERTIES, RESOURCE_BUNDLE_FORMAT_CLASS)
    }

    fun resolveBundleControlSpec(className: String): BundleControlSpec? =
        bundleControlSpecsByClass.getOrPut(className) {
            val sootClass = resources.resolveClassByName(className) ?: return@getOrPut null
            if (!isResourceBundleControlClass(sootClass)) return@getOrPut null
            val javaSootClass = sootClass as? JavaSootClass ?: return@getOrPut null
            val methods = bytecodeMethods.getAsmMethodNodes(javaSootClass)
                ?: bytecodeMethods.loadMethodNodesFromResource(javaSootClass)
                ?: return@getOrPut null
            val formats = methods.firstOrNull { it.name == "getFormats" }
                ?.let(literals::extractControlFormatsFromMethod)
                ?: setOf(RESOURCE_BUNDLE_FORMAT_PROPERTIES, RESOURCE_BUNDLE_FORMAT_CLASS)
            val candidateLocales = methods.firstOrNull { it.name == "getCandidateLocales" }
                ?.let(literals::extractCandidateLocalesFromMethod)
            val noFallback = methods.firstOrNull { it.name == "getFallbackLocale" }
                ?.let(literals::returnsNullLiteral)
                ?: false
            BundleControlSpec(
                noFallback = noFallback,
                formats = formats,
                candidateLocales = candidateLocales
            )
        }

    fun reflectBundleControlSpec(
        className: String,
        baseName: String,
        localeSpec: String?
    ): BundleControlSpec? = runCatching {
        val controlClass = Class.forName(className)
        if (!ResourceBundle.Control::class.java.isAssignableFrom(controlClass)) return null
        val instance = controlClass.getDeclaredConstructor().newInstance() as ResourceBundle.Control
        val locale = localeSpec?.toLocale() ?: Locale.ROOT
        BundleControlSpec(
            noFallback = instance.getFallbackLocale(baseName, locale) == null,
            formats = instance.getFormats(baseName).toSet(),
            candidateLocales = instance.getCandidateLocales(baseName, locale)
                .map(::localeSpecOf)
                .distinct()
        )
    }.getOrNull()

    fun isResourceBundleControlClass(sootClass: SootClass): Boolean {
        var current: SootClass? = sootClass
        var found = false
        while (current != null && !found) {
            val superName = current.superclass.orElse(null)?.fullyQualifiedName
            found = superName != null && callKinds.isResourceBundleControlTypeName(superName)
            current = if (superName == null || found) null else resources.resolveClassByName(superName)
        }
        return found
    }

    fun ensureRuntimeBundleIndexed(baseName: String, localeSpec: String?, controlSpec: BundleControlSpec?) {
        val bundleKey = listOf(baseName, localeSpec.orEmpty(), controlSpec?.formats?.sorted()?.joinToString(","), controlSpec?.noFallback)
            .joinToString("|")
        if (!runtimeIndexedBundles.add(bundleKey)) return
        runCatching {
            val locale = localeSpec?.toLocale() ?: Locale.ROOT
            val classLoader = javaClass.classLoader
            val bundle = when (controlSpec) {
                null -> ResourceBundle.getBundle(baseName, locale, classLoader)
                else -> ResourceBundle.getBundle(baseName, locale, classLoader, toControl(controlSpec))
            }
            indexRuntimeBundle(bundle)
        }.onFailure {
            declarations.log { "Skipping runtime bundle resolution for $baseName: ${it.message}" }
        }
    }

    fun indexRuntimeBundle(bundle: ResourceBundle) {
        val path = bundle.javaClass.name
        if (resourceFilesByPath.containsKey(path)) return
        val format = when (bundle) {
            is java.util.ListResourceBundle -> "listbundle"
            is java.util.PropertyResourceBundle -> "propertybundle"
            else -> "bundle"
        }
        val fileNode = ResourceFileNode(
            id = metadata.nextNodeId(RESOURCE_SOURCE),
            path = path,
            source = "runtime-bundle",
            format = format,
            profile = null
        )
        graphBuilder.addNode(fileNode)
        resourceFilesByPath.getOrPut(path) { mutableListOf() }.add(fileNode)
        bundleParent(bundle)?.let(::indexRuntimeBundle)
    }

    fun toControl(controlSpec: BundleControlSpec): ResourceBundle.Control =
        if (controlSpec.noFallback) {
            ResourceBundle.Control.getNoFallbackControl(controlFormatsList(controlSpec))
        } else {
            ResourceBundle.Control.getControl(controlFormatsList(controlSpec))
        }

    fun controlFormatsList(controlSpec: BundleControlSpec): List<String> = buildList {
        if (RESOURCE_BUNDLE_FORMAT_CLASS in controlSpec.formats) add(RESOURCE_BUNDLE_FORMAT_CLASS)
        if (RESOURCE_BUNDLE_FORMAT_PROPERTIES in controlSpec.formats) add(RESOURCE_BUNDLE_FORMAT_PROPERTIES)
    }

    fun String.toLocale(): Locale {
        if (isBlank()) return Locale.ROOT
        val parts = split('_').filter { it.isNotBlank() }
        return when (parts.size) {
            1 -> Locale(parts[0])
            2 -> Locale(parts[0], parts[1])
            else -> Locale(parts[0], parts[1], parts.drop(2).joinToString("_"))
        }
    }

    fun localeSpecOf(locale: Locale): String =
        localeValues.normalizeLocaleSpec(
            listOfNotNull(
                locale.language.takeIf { it.isNotBlank() },
                locale.country.takeIf { it.isNotBlank() },
                locale.variant.takeIf { it.isNotBlank() }
            ).joinToString("_")
        )

    fun bundleParent(bundle: ResourceBundle): ResourceBundle? = runCatching {
        val field = ResourceBundle::class.java.getDeclaredField("parent")
        field.isAccessible = true
        field.get(bundle) as? ResourceBundle
    }.getOrNull()
}
