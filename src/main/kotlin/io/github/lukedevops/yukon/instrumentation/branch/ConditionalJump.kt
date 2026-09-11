package io.github.lukedevops.yukon.instrumentation.branch

import net.bytebuddy.jar.asm.Opcodes

/**
 * Identifies the bytecode jump instructions this tier tracks: every comparison that has
 * exactly two outcomes (taken or fell through). `GOTO` and `JSR` are unconditional and have
 * only one successor, so there is no second outcome to ever leave uncovered.
 */
object ConditionalJump {
    fun isTracked(opcode: Int): Boolean =
        opcode in Opcodes.IFEQ..Opcodes.IF_ACMPNE || opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL
}
