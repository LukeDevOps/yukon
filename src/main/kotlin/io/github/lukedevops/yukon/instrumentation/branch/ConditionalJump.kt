package io.github.lukedevops.yukon.instrumentation.branch

import net.bytebuddy.jar.asm.Opcodes

/**
 * Identifies the bytecode jump instructions this tier tracks: every comparison with exactly two
 * outcomes, taken or fell through.
 *
 * `GOTO` and `JSR` are unconditional. Each has only one successor, so neither has a second
 * outcome to track.
 */
object ConditionalJump {
    fun isTracked(opcode: Int): Boolean =
        opcode in Opcodes.IFEQ..Opcodes.IF_ACMPNE || opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL
}
