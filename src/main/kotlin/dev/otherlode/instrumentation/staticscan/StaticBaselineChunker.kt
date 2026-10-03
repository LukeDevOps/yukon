package dev.otherlode.instrumentation.staticscan

import dev.otherlode.export.DeclaredClass
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.export.StaticBaseline
import dev.otherlode.export.StaticallyUnsafeClass
import dev.otherlode.export.UnprobedClass
import dev.otherlode.export.UnreadableClass

/**
 * Splits one [StaticScanResult] into [StaticBaseline] chunks of bounded size, so a large
 * classpath never produces a single POST the collector might refuse.
 *
 * Size is measured in entries: a declared class counts as its number of methods, plus the total
 * number of call edges across those methods, plus its methods' branch sites by
 * [dev.otherlode.export.BranchSite.chunkWeight], plus one for its own class record, plus one per
 * referenced class on its methods and on the class itself, mirroring the weighting
 * [dev.otherlode.registry.ProbeRegistry] gives a manifest class for the same reason; every
 * unsafe, unreadable, or unprobed class counts as one.
 * Classes are never split across chunks, so a declared class heavier than [maxEntriesPerChunk]
 * gets a chunk of its own. Every chunk carries the same resource and [scannedAt], and its own
 * `chunkIndex` out of `chunkCount`, so a collector can tell when it holds the whole scan.
 *
 * Always produces at least one chunk: an empty scan is still a statement ("nothing declared").
 */
object StaticBaselineChunker {
    fun chunk(
        result: StaticScanResult,
        resource: ResourceAttributes,
        scannedAt: Long,
        maxEntriesPerChunk: Int,
    ): List<StaticBaseline> {
        require(maxEntriesPerChunk > 0) { "maxEntriesPerChunk must be positive" }
        val chunks = mutableListOf<Chunk>()
        var current = Chunk()

        fun place(
            weight: Int,
            add: Chunk.() -> Unit,
        ) {
            if (current.size > 0 && current.size + weight > maxEntriesPerChunk) {
                chunks += current
                current = Chunk()
            }
            current.add()
            current.size += weight
        }

        result.declaredClasses.forEach { c ->
            val references = c.methods.sumOf { it.referencedClasses.size } + c.referencedClasses.size
            val sites = c.methods.sumOf { method -> method.branchSites.sumOf { it.chunkWeight } }
            val weight = (c.methods.size + c.methods.sumOf { it.calls.size } + sites + 1 + references).coerceAtLeast(1)
            place(weight) { declared += c }
        }
        result.staticallyUnsafeClasses.forEach { c -> place(1) { unsafe += c } }
        result.unreadableClasses.forEach { c -> place(1) { unreadable += c } }
        result.unprobedClasses.forEach { c -> place(1) { unprobed += c } }
        if (current.size > 0 || chunks.isEmpty()) chunks += current

        return chunks.mapIndexed { index, chunk ->
            StaticBaseline(
                resource = resource,
                declaredClasses = chunk.declared,
                staticallyUnsafeClasses = chunk.unsafe,
                unreadableClasses = chunk.unreadable,
                unprobedClasses = chunk.unprobed,
                scannedAt = scannedAt,
                chunkIndex = index,
                chunkCount = chunks.size,
            )
        }
    }

    private class Chunk {
        val declared = mutableListOf<DeclaredClass>()
        val unsafe = mutableListOf<StaticallyUnsafeClass>()
        val unreadable = mutableListOf<UnreadableClass>()
        val unprobed = mutableListOf<UnprobedClass>()
        var size = 0
    }
}
