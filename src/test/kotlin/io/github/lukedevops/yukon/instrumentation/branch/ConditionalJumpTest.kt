package io.github.lukedevops.yukon.instrumentation.branch

import net.bytebuddy.jar.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConditionalJumpTest {
    @Test
    fun `every two-outcome comparison opcode is tracked`() {
        val tracked =
            listOf(
                Opcodes.IFEQ,
                Opcodes.IFNE,
                Opcodes.IFLT,
                Opcodes.IFGE,
                Opcodes.IFGT,
                Opcodes.IFLE,
                Opcodes.IF_ICMPEQ,
                Opcodes.IF_ICMPNE,
                Opcodes.IF_ICMPLT,
                Opcodes.IF_ICMPGE,
                Opcodes.IF_ICMPGT,
                Opcodes.IF_ICMPLE,
                Opcodes.IF_ACMPEQ,
                Opcodes.IF_ACMPNE,
                Opcodes.IFNULL,
                Opcodes.IFNONNULL,
            )

        tracked.forEach { assertTrue(ConditionalJump.isTracked(it), "opcode $it should be tracked") }
    }

    @Test
    fun `unconditional jumps are not tracked`() {
        assertFalse(ConditionalJump.isTracked(Opcodes.GOTO))
        assertFalse(ConditionalJump.isTracked(Opcodes.JSR))
    }
}
