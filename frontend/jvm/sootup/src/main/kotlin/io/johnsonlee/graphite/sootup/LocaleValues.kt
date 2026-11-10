package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.MethodDescriptor
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.StringConstant as SootStringConstant
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.jimple.common.ref.JStaticFieldRef
import sootup.core.signatures.MethodSignature
import io.johnsonlee.graphite.sootup.SootUpAdapter.Companion.CLASS_LOADER_CLASS

/** Normalizes locale constants, factories and resource paths. */
internal class LocaleValues(private val context: SootUpAdapter) {
    private val localeSpecsByLocal = context.localeSpecsByLocal
    private val stringValuesByLocal = context.stringValuesByLocal
    private val metadata get() = context.metadata

    fun extractLocaleSpec(caller: MethodDescriptor, value: Value): String? = when (value) {
        is Local -> localeSpecsByLocal[metadata.localKey(caller, value.name)]
        is JStaticFieldRef -> extractLocaleSpec(value)
        else -> null
    }

    fun extractStringValue(caller: MethodDescriptor, value: Value?): String? = when (value) {
        is SootStringConstant -> value.value
        is Local -> stringValuesByLocal[metadata.localKey(caller, value.name)]
        else -> null
    }

    fun extractLocaleSpec(fieldRef: JStaticFieldRef): String? {
        if (fieldRef.fieldSignature.declClassType.fullyQualifiedName != LOCALE_CLASS) return null
        return extractLocaleSpec(fieldRef.fieldSignature.name)
    }

    fun extractLocaleSpec(fieldName: String): String? {
        return STANDARD_LOCALES[fieldName]?.let(::normalizeLocaleSpec)
    }

    fun extractLocaleFactorySpec(calleeSignature: MethodSignature, invokeExpr: AbstractInvokeExpr): String? {
        if (calleeSignature.declClassType.fullyQualifiedName != LOCALE_CLASS) return null
        return when (calleeSignature.name) {
            "forLanguageTag" -> (invokeExpr.args.firstOrNull() as? SootStringConstant)?.value
                ?.let(::normalizeLocaleSpec)
            else -> null
        }
    }

    fun extractConstructedLocaleSpec(invokeExpr: AbstractInvokeExpr): String? {
        val language = (invokeExpr.args.getOrNull(0) as? SootStringConstant)?.value ?: return null
        val country = (invokeExpr.args.getOrNull(1) as? SootStringConstant)?.value
        val variant = (invokeExpr.args.getOrNull(2) as? SootStringConstant)?.value
        return normalizeLocaleSpec(listOfNotNull(language, country, variant).joinToString("_"))
    }

    fun normalizeLocaleSpec(spec: String): String {
        val parts = spec.split('_', '-').filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        return buildList {
            add(parts[0].lowercase())
            if (parts.size > 1) add(parts[1].uppercase())
            if (parts.size > 2) addAll(parts.drop(2))
        }.joinToString("_")
    }

    fun normalizeResourcePath(caller: MethodDescriptor, declaringClass: String, rawPath: String): String {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty() || declaringClass == CLASS_LOADER_CLASS || trimmed.startsWith("/")) {
            return trimmed.removePrefix("/")
        }
        val packagePath = caller.declaringClass.className.substringBeforeLast('.', "").replace('.', '/')
        return if (packagePath.isEmpty()) trimmed else "$packagePath/$trimmed"
    }

    fun isResourceConfig(path: String): Boolean =
        path.endsWith(PROPERTIES_FILE_SUFFIX) ||
            path.endsWith(".yml") ||
            path.endsWith(".yaml") ||
            path.endsWith(".json") ||
            path.endsWith(".xml")

    fun resourceFormat(path: String): String = when {
        path.endsWith(PROPERTIES_FILE_SUFFIX) -> "properties"
        path.endsWith(".yml") || path.endsWith(".yaml") -> "yaml"
        path.endsWith(".json") -> "json"
        path.endsWith(".xml") -> "xml"
        else -> "text"
    }

    fun resourceProfile(path: String): String? {
        val fileName = path.substringAfterLast('/')
        val match = Regex("""application-([^.]+)\.(properties|json|xml|ya?ml)""").matchEntire(fileName)
        return match?.groupValues?.getOrNull(1)
    }
    private companion object {
        private val STANDARD_LOCALES = mapOf(
            "ROOT" to "",
            "ENGLISH" to "en",
            "US" to "en_US",
            "UK" to "en_GB",
            "CANADA" to "en_CA",
            "CANADA_FRENCH" to "fr_CA",
            "FRENCH" to "fr",
            "FRANCE" to "fr_FR",
            "GERMAN" to "de",
            "GERMANY" to "de_DE",
            "ITALIAN" to "it",
            "ITALY" to "it_IT",
            "JAPANESE" to "ja",
            "JAPAN" to "ja_JP",
            "KOREAN" to "ko",
            "KOREA" to "ko_KR",
            "CHINESE" to "zh",
            "CHINA" to "zh_CN",
            "SIMPLIFIED_CHINESE" to "zh_CN",
            "TAIWAN" to "zh_TW",
            "TRADITIONAL_CHINESE" to "zh_TW",
        )
    }

}
