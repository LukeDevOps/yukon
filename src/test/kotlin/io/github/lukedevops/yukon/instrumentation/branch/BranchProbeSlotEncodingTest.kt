package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.advice.MethodEntryAdvice
import net.bytebuddy.ByteBuddy
import net.bytebuddy.description.modifier.Ownership
import net.bytebuddy.description.modifier.Visibility
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy
import net.bytebuddy.implementation.LoadedTypeInitializer
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.pool.TypePool
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A class with enough branch sites that its probe slot indices run past what `BIPUSH` (127) and
 * `SIPUSH` (32767) can push, so [BranchProbeMethodVisitor]'s `pushInt` has to fall through to
 * each wider encoding. Generated with ASM rather than compiled: no fixture a compiler accepts has
 * sixteen thousand conditionals. Each site is `if (x != i) {}`, taken for every `i` but the
 * argument, so one call marks exactly one not-taken slot and the rest taken.
 */
class BranchProbeSlotEncodingTest {
    private val internalName = "com/example/gen/ManySites"
    private val sitesPerMethod = 800
    private val methods = 21

    private fun generate(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        for (m in 0 until methods) {
            val run = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run$m", "(I)V", null, null)
            run.visitCode()
            for (i in 0 until sitesPerMethod) {
                val next = Label()
                run.visitVarInsn(Opcodes.ILOAD, 0)
                run.visitLdcInsn(i)
                run.visitJumpInsn(Opcodes.IF_ICMPNE, next)
                run.visitLabel(next)
            }
            run.visitInsn(Opcodes.RETURN)
            run.visitMaxs(0, 0)
            run.visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `slot indices past 127 and past 32767 are pushed with the wider encodings and land on the right slot`() {
        val className = internalName.replace('/', '.')
        val locator =
            ClassFileLocator.Compound(
                ClassFileLocator.Simple(mapOf(className to generate())),
                ClassFileLocator.ForClassLoader.ofSystemLoader(),
            )
        val typeDescription =
            TypePool.Default
                .of(locator)
                .describe(className)
                .resolve()
        val slots = 2 * sitesPerMethod * methods
        val counts = LongArray(slots)
        val loaded =
            ByteBuddy()
                .redefine<Any>(typeDescription, locator)
                .defineField(MethodEntryAdvice.PROBE_ARRAY_FIELD, LongArray::class.java, Visibility.PRIVATE, Ownership.STATIC)
                .initializer(LoadedTypeInitializer.ForStaticField(MethodEntryAdvice.PROBE_ARRAY_FIELD, counts))
                .visit(BranchProbeAsmVisitorWrapper(eligibleMethods = { _, _ -> true }, probeIndexBase = 0, branchSlotCapacity = slots))
                .make()
                .load(javaClass.classLoader, ClassLoadingStrategy.Default.WRAPPER)
                .loaded

        // run0's site 70 not taken: slot 141, past BIPUSH's reach.
        loaded.getMethod("run0", Int::class.java).invoke(null, 70)
        assertEquals(1L, counts[2 * 70 + 1], "not-taken slot of run0's site 70")
        assertEquals(0L, counts[2 * 70], "taken slot of run0's site 70")
        assertEquals(sitesPerMethod.toLong(), counts.sum(), "one slot per site in run0, nothing outside it")

        // run20's last site not taken: slot 33599, past SIPUSH's reach.
        val lastSite = (methods - 1) * sitesPerMethod + (sitesPerMethod - 1)
        loaded.getMethod("run${methods - 1}", Int::class.java).invoke(null, sitesPerMethod - 1)
        assertEquals(1L, counts[2 * lastSite + 1], "not-taken slot of the last site in the class")
        assertEquals(0L, counts[2 * lastSite], "taken slot of the last site in the class")
        assertEquals(2L * sitesPerMethod, counts.sum())
    }
}
