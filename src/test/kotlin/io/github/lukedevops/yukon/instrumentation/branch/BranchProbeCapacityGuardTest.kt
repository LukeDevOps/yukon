package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.advice.MethodEntryAdvice
import net.bytebuddy.ByteBuddy
import net.bytebuddy.description.modifier.Ownership
import net.bytebuddy.description.modifier.Visibility
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy
import net.bytebuddy.implementation.LoadedTypeInitializer
import net.bytebuddy.pool.TypePool
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Drives [BranchProbeAsmVisitorWrapper] directly, with a capacity that does not match the
 * class's real site count, to prove the last-resort guard: a site past capacity runs unchanged
 * rather than writing past the end of the array, and any mismatch fails the whole rewrite once,
 * at `visitEnd`, instead of leaving the class instrumented with shifted probes.
 */
class BranchProbeCapacityGuardTest {
    private fun loadWithCapacity(capacity: Int): Pair<Class<*>, LongArray> {
        val classesDir = File("build/classes/java/test")
        val locator = ClassFileLocator.Compound(ClassFileLocator.ForFolder(classesDir), ClassFileLocator.ForClassLoader.ofSystemLoader())
        val typePool = TypePool.Default.of(locator)
        val typeDescription = typePool.describe("com.example.target.BranchTarget").resolve()
        val counts = LongArray(capacity)
        val loaded =
            ByteBuddy()
                .redefine<Any>(typeDescription, locator)
                .name("com.example.guard.BranchTarget$capacity")
                .defineField(MethodEntryAdvice.PROBE_ARRAY_FIELD, LongArray::class.java, Visibility.PRIVATE, Ownership.STATIC)
                .initializer(LoadedTypeInitializer.ForStaticField(MethodEntryAdvice.PROBE_ARRAY_FIELD, counts))
                .visit(
                    BranchProbeAsmVisitorWrapper(
                        eligibleMethods = { _, _ -> true },
                        probeIndexBase = 0,
                        branchSlotCapacity = capacity,
                    ),
                ).make()
                .load(javaClass.classLoader, ClassLoadingStrategy.Default.WRAPPER)
                .loaded
        return loaded to counts
    }

    @Test
    fun `a capacity smaller than the analysed site count fails the transform naming both counts`() {
        // BranchTarget needs 2 (classify) + 4 (classifyDense) + 3 (classifySparse) = 9 slots.
        val exception =
            try {
                loadWithCapacity(capacity = 2)
                fail("expected the rewrite to fail")
            } catch (e: IllegalStateException) {
                e
            }

        assertTrue("BranchTarget" in exception.message.orEmpty(), "message names the class")
        assertTrue("2" in exception.message.orEmpty(), "message names the analysed slot count")
        assertTrue("9" in exception.message.orEmpty(), "message names the slot count wanted at rewrite")
    }

    @Test
    fun `a matching capacity throws nothing`() {
        val (loaded, counts) = loadWithCapacity(capacity = 9)
        val target = loaded.getDeclaredConstructor().newInstance()

        loaded.getMethod("classify", Int::class.java).invoke(target, 5)

        assertEquals(1L, counts.sum())
    }
}
