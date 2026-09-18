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

/**
 * Drives [BranchProbeAsmVisitorWrapper] directly with a dropped ordinal, on
 * `BranchTargetWithExtraBranches`'s `classify`, which has two conditionals in one method. Proves
 * the rewriter emits a dropped site's original instruction unchanged, allocating no slot for it,
 * so [BranchSiteAnalyzer]'s kept-slot count and what the wrapper actually wrote agree. See ADR
 * 0025.
 */
class BranchDropRewriterTest {
    private fun loadWithDrop(
        capacity: Int,
        droppedOrdinalsByMethod: (String, String) -> Set<Int>,
    ): Pair<Class<*>, LongArray> {
        val classesDir = File("build/classes/java/test")
        val locator = ClassFileLocator.Compound(ClassFileLocator.ForFolder(classesDir), ClassFileLocator.ForClassLoader.ofSystemLoader())
        val typePool = TypePool.Default.of(locator)
        val typeDescription = typePool.describe("com.example.target.BranchTargetWithExtraBranches").resolve()
        val counts = LongArray(capacity)
        val loaded =
            ByteBuddy()
                .redefine<Any>(typeDescription, locator)
                .name("com.example.dropguard.BranchTargetWithExtraBranches$capacity")
                .defineField(MethodEntryAdvice.PROBE_ARRAY_FIELD, LongArray::class.java, Visibility.PRIVATE, Ownership.STATIC)
                .initializer(LoadedTypeInitializer.ForStaticField(MethodEntryAdvice.PROBE_ARRAY_FIELD, counts))
                .visit(
                    BranchProbeAsmVisitorWrapper(
                        eligibleMethods = { name, _ -> name == "classify" },
                        probeIndexBase = 0,
                        branchSlotCapacity = capacity,
                        droppedOrdinalsByMethod = droppedOrdinalsByMethod,
                    ),
                ).make()
                .load(javaClass.classLoader, ClassLoadingStrategy.Default.WRAPPER)
                .loaded
        return loaded to counts
    }

    @Test
    fun `a dropped ordinal is emitted unchanged and allocates no slot, so the kept site still lands on slot zero`() {
        val (loaded, counts) =
            loadWithDrop(
                capacity = 2,
                droppedOrdinalsByMethod = { name, _ -> if (name == "classify") setOf(0) else emptySet() },
            )
        val target = loaded.getDeclaredConstructor().newInstance()
        val classify = loaded.getMethod("classify", Int::class.java)

        assertEquals("large", classify.invoke(target, 500), "the dropped first jump still runs, just with no probe")
        assertEquals("non-positive", classify.invoke(target, -5))
        assertEquals("positive", classify.invoke(target, 50))

        // classify(-5) takes the kept second jump's taken edge (slot 0); classify(50) takes its
        // not-taken edge (slot 1). The dropped first jump's own two outcomes wrote nothing, and
        // capacity matches the kept slot count, so the rewrite does not throw.
        assertEquals(listOf(1L, 1L), counts.toList())
    }
}
