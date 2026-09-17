package io.github.lukedevops.yukon.instrumentation.branch

import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe totals of branch sites dropped across every class this agent instruments, broken
 * down by [BranchDropReason], plus how many classes dropped at least one site.
 *
 * [io.github.lukedevops.yukon.instrumentation.YukonInstrumentation] calls [record] once per
 * transformed class. [io.github.lukedevops.yukon.export.ExportScheduler] reads [total],
 * [countOf], and [classesWithDrops] to log one summary line the first time a flush finds the
 * total above zero. See ADR 0025.
 */
class BranchDropCounts {
    private val countsByReason: Map<BranchDropReason, AtomicLong> = BranchDropReason.entries.associateWith { AtomicLong(0) }
    private val classesWithDrops = AtomicLong(0)

    /** Adds one class's drop counts, keyed by reason. A no-op for an empty map. */
    fun record(dropsByReason: Map<BranchDropReason, Int>) {
        if (dropsByReason.isEmpty()) return
        for ((reason, count) in dropsByReason) countsByReason.getValue(reason).addAndGet(count.toLong())
        classesWithDrops.incrementAndGet()
    }

    /** The total number of dropped sites, every reason included. */
    fun total(): Long = countsByReason.values.sumOf { it.get() }

    /** How many sites were dropped for [reason]. */
    fun countOf(reason: BranchDropReason): Long = countsByReason.getValue(reason).get()

    /** How many classes dropped at least one site. */
    fun classesWithDrops(): Long = classesWithDrops.get()
}
