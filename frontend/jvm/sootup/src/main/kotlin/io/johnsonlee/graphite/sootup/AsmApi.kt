package io.johnsonlee.graphite.sootup

import org.objectweb.asm.Opcodes

/**
 * ASM visitor API version used by bytecode readers in this module.
 *
 * Keep this centralized so ASM upgrades are explicit and do not require
 * chasing hardcoded visitor constructor arguments across the codebase.
 */
internal val ASM_API_VERSION: Int = Opcodes.ASM9
