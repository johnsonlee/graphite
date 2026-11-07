package io.johnsonlee.graphite.sootup

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type as AsmType
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode

/** Interprets literal bytecode used by bundle policies without executing application code. */
internal class BytecodeLiterals(private val context: SootUpAdapter) {
    private val localeValues get() = context.localeValues

    fun extractControlFormatsFromMethod(methodNode: MethodNode): Set<String>? =
        when (val value = evaluateLiteralMethod(methodNode)) {
            is List<*> -> value.mapNotNull {
                when (it) {
                    RESOURCE_BUNDLE_FORMAT_CLASS, RESOURCE_BUNDLE_FORMAT_PROPERTIES -> it as String
                    else -> null
                }
            }.toSet().takeIf { it.isNotEmpty() }
            RESOURCE_BUNDLE_FORMAT_CLASS -> setOf(RESOURCE_BUNDLE_FORMAT_CLASS)
            RESOURCE_BUNDLE_FORMAT_PROPERTIES -> setOf(RESOURCE_BUNDLE_FORMAT_PROPERTIES)
            else -> null
        }

    fun extractCandidateLocalesFromMethod(methodNode: MethodNode): List<String>? =
        (evaluateLiteralMethod(methodNode) as? List<*>)
            ?.mapNotNull { value ->
                when (value) {
                    is String -> localeValues.normalizeLocaleSpec(value)
                    else -> null
                }
            }
            ?.takeIf { it.isNotEmpty() }

    fun returnsNullLiteral(methodNode: MethodNode): Boolean =
        evaluateLiteralMethod(methodNode) == null

    fun evaluateLiteralMethod(methodNode: MethodNode): Any? {
        val stack = ArrayDeque<Any?>()
        val locals = mutableMapOf<Int, Any?>()
        val argTypes = AsmType.getArgumentTypes(methodNode.desc)
        var localIndex = if ((methodNode.access and Opcodes.ACC_STATIC) != 0) 0 else 1
        argTypes.forEach { argType ->
            locals[localIndex] = when (argType.className) {
                LOCALE_CLASS -> "arg-locale"
                "java.lang.String" -> "arg-string"
                else -> null
            }
            localIndex += argType.size
        }
        for (insn in methodNode.instructions) {
            if (insn.opcode == Opcodes.ARETURN) return stack.removeLastOrNull()
            evaluateInstruction(insn, stack, locals)
        }
        return null
    }

    private fun evaluateInstruction(
        insn: org.objectweb.asm.tree.AbstractInsnNode,
        stack: ArrayDeque<Any?>,
        locals: MutableMap<Int, Any?>
    ) {
        when (insn) {
            is InsnNode -> evaluateSimpleInstruction(insn, stack)
            is IntInsnNode -> if (insn.opcode == Opcodes.BIPUSH || insn.opcode == Opcodes.SIPUSH) {
                stack.addLast(insn.operand)
            }
            is LdcInsnNode -> stack.addLast(insn.cst)
            is VarInsnNode -> when (insn.opcode) {
                Opcodes.ASTORE, Opcodes.ISTORE -> locals[insn.`var`] = stack.removeLastOrNull()
                Opcodes.ALOAD, Opcodes.ILOAD -> stack.addLast(locals[insn.`var`])
            }
            is FieldInsnNode -> if (insn.opcode == Opcodes.GETSTATIC) {
                stack.addLast(resolveStaticLiteral(insn.owner.replace('/', '.'), insn.name))
            }
            is MethodInsnNode -> evaluateInvocation(insn, stack)
        }
    }

    private fun evaluateSimpleInstruction(insn: InsnNode, stack: ArrayDeque<Any?>) {
        when {
            insn.opcode == Opcodes.ACONST_NULL -> stack.addLast(null)
            insn.opcode in Opcodes.ICONST_M1..Opcodes.ICONST_5 -> stack.addLast(insn.opcode - Opcodes.ICONST_0)
            insn.opcode == Opcodes.DUP -> stack.lastOrNull()?.let(stack::addLast)
        }
    }

    private fun evaluateInvocation(insn: MethodInsnNode, stack: ArrayDeque<Any?>) {
        val args = buildList {
            repeat(AsmType.getArgumentTypes(insn.desc).size) { add(0, stack.removeLastOrNull()) }
        }
        val owner = insn.owner.replace('/', '.')
        val result = if (insn.opcode == Opcodes.INVOKESTATIC) {
            when (owner to insn.name) {
                "java.util.List" to "of", "java.util.Collections" to "singletonList" -> args
                "java.util.Arrays" to "asList" -> (args.singleOrNull() as? List<*>) ?: args
                LOCALE_CLASS to "forLanguageTag" -> (args.firstOrNull() as? String)?.let(localeValues::normalizeLocaleSpec)
                else -> null
            }
        } else null
        if (AsmType.getReturnType(insn.desc).sort != AsmType.VOID) stack.addLast(result)
    }

    fun resolveStaticLiteral(owner: String, fieldName: String): Any? = when {
        owner == LOCALE_CLASS -> localeValues.extractLocaleSpec(fieldName)
        fieldName == "FORMAT_CLASS" -> listOf(RESOURCE_BUNDLE_FORMAT_CLASS)
        fieldName == "FORMAT_PROPERTIES" -> listOf(RESOURCE_BUNDLE_FORMAT_PROPERTIES)
        fieldName == "FORMAT_DEFAULT" -> listOf(RESOURCE_BUNDLE_FORMAT_CLASS, RESOURCE_BUNDLE_FORMAT_PROPERTIES)
        else -> null
    }
}
