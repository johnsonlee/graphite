package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.ValueNode
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.signatures.MethodSignature
import io.johnsonlee.graphite.sootup.SootUpAdapter.LocaleBuilderSpec

/** Tracks fluent Locale.Builder calls and the locale values their results carry. */
internal class LocaleTracking(private val context: SootUpAdapter) {
    private val localeSpecsByLocal = context.localeSpecsByLocal
    private val localeBuilderSpecsByLocal = context.localeBuilderSpecsByLocal
    private val callKinds get() = context.callKinds
    private val descriptors get() = context.descriptors
    private val localeValues get() = context.localeValues
    private val metadata get() = context.metadata

    fun updateLocaleBuilderState(
        caller: MethodDescriptor,
        calleeSignature: MethodSignature,
        invokeExpr: AbstractInvokeExpr,
        resultNode: ValueNode?
    ) {
        val receiverLocal = (invokeExpr as? AbstractInstanceInvokeExpr)?.base as? Local
        if (!callKinds.isLocaleBuilderClassName(calleeSignature.declClassType.fullyQualifiedName) || receiverLocal == null) return
        val receiverKey = metadata.localKey(caller, receiverLocal.name)
        val current = localeBuilderSpecsByLocal[receiverKey] ?: LocaleBuilderSpec()
        val updated = when (calleeSignature.name) {
            INIT_METHOD -> LocaleBuilderSpec()
            "setLanguage" -> current.copy(language = localeValues.extractStringValue(caller, invokeExpr.args.getOrNull(0)))
            "setRegion" -> current.copy(country = localeValues.extractStringValue(caller, invokeExpr.args.getOrNull(0)))
            "setVariant" -> current.copy(variant = localeValues.extractStringValue(caller, invokeExpr.args.getOrNull(0)))
            "setLanguageTag" -> current.copy(languageTag = localeValues.extractStringValue(caller,
                invokeExpr.args.getOrNull(0))?.let(localeValues::normalizeLocaleSpec))
            "setLocale" -> readBuilderLocale(caller, invokeExpr.args.getOrNull(0), current) ?: return
            "clear", "clearExtensions" -> LocaleBuilderSpec()
            else -> current
        }
        localeBuilderSpecsByLocal[receiverKey] = updated

        propagateBuilderResult(caller, calleeSignature, resultNode, updated)
    }

    private fun readBuilderLocale(caller: MethodDescriptor, value: sootup.core.jimple.common.Value?,
        current: LocaleBuilderSpec): LocaleBuilderSpec? {
        if (value == null) return null
        return localeValues.extractLocaleSpec(caller, value)?.split('_')?.let { parts ->
            LocaleBuilderSpec(
                language = parts.getOrNull(0),
                country = parts.getOrNull(1),
                variant = parts.drop(2).takeIf { it.isNotEmpty() }?.joinToString("_"),
                languageTag = null
            )
        } ?: current
    }

    private fun propagateBuilderResult(
        caller: MethodDescriptor,
        calleeSignature: MethodSignature,
        resultNode: ValueNode?,
        updated: LocaleBuilderSpec
    ) {
        if (resultNode is LocalVariable) {
            val resultKey = metadata.localKey(caller, resultNode.name)
            if (callKinds.isLocaleBuilderClassName(descriptors.toTypeDescriptor(calleeSignature.type).className)) {
                localeBuilderSpecsByLocal[resultKey] = updated
            }
            if (calleeSignature.name == "build") {
                updated.toLocaleSpec()?.let { localeSpecsByLocal[resultKey] = localeValues.normalizeLocaleSpec(it) }
            }
        }
    }
}
