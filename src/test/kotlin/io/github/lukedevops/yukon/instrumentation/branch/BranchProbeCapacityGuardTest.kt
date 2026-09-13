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

/**
 * Drives [BranchProbeAsmVisitorWrapper] directly, with a capacity smaller than the class's real
 * site count, to prove the last-resort guard: sites past capacity run unchanged rather than
 * writing past the end of the array, and the mismatch is reported once.
 */
class BranchProbeCapacityGuardTest {
    private fun loadWithCapacity(
        capacity: Int,
        onMismatch: (Int, Int) -> Unit,
    ): Pair<Class<*>, LongArray> {
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
                        onSiteCountMismatch = onMismatch,
                    ),
                ).make()
                .load(javaClass.classLoader, ClassLoadingStrategy.Default.WRAPPER)
                .loaded
        return loaded to counts
    }

    @Test
    fun `sites past capacity run unchanged and the shortfall is reported once`() {
        // BranchTarget needs 2 (classify) + 4 (classifyDense) + 3 (classifySparse) = 9 slots.
        val mismatches = mutableListOf<Pair<Int, Int>>()
        val (loaded, counts) = loadWithCapacity(capacity = 2) { expected, actual -> mismatches += expected to actual }
        val target = loaded.getDeclaredConstructor().newInstance()

        assertEquals("positive", loaded.getMethod("classify", Int::class.java).invoke(target, 5))
        assertEquals(101, loaded.getMethod("classifyDense", Int::class.java).invoke(target, 1))
        assertEquals(201, loaded.getMethod("classifySparse", Int::class.java).invoke(target, 1000))

        assertEquals(listOf(2 to 9), mismatches)
        // classify(5) falls through its `value > 0` jump, which is the site's second slot.
        assertEquals(listOf(0L, 1L), counts.toList(), "only the first site, which fit, was instrumented")
    }

    @Test
    fun `a matching capacity reports nothing`() {
        val mismatches = mutableListOf<Pair<Int, Int>>()
        val (loaded, counts) = loadWithCapacity(capacity = 9) { expected, actual -> mismatches += expected to actual }
        val target = loaded.getDeclaredConstructor().newInstance()

        loaded.getMethod("classify", Int::class.java).invoke(target, 5)

        assertTrue(mismatches.isEmpty())
        assertEquals(1L, counts.sum())
    }
}
