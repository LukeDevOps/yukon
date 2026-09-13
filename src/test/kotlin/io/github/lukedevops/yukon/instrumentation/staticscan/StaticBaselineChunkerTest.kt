package io.github.lukedevops.yukon.instrumentation.staticscan

import io.github.lukedevops.yukon.export.DeclaredClass
import io.github.lukedevops.yukon.export.DeclaredMethod
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.export.StaticallyUnsafeClass
import io.github.lukedevops.yukon.export.UnprobedClass
import io.github.lukedevops.yukon.export.UnreadableClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StaticBaselineChunkerTest {
    private val resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "test")

    private fun declared(
        name: String,
        methods: Int,
    ) = DeclaredClass(name, (1..methods).map { DeclaredMethod("m$it", "()V") })

    @Test
    fun `packs whole classes by method count and numbers the chunks`() {
        val result =
            StaticScanResult(
                declaredClasses = listOf(declared("A", 3), declared("B", 3), declared("C", 1)),
                staticallyUnsafeClasses = emptyList(),
                unreadableClasses = emptyList(),
            )

        val chunks = StaticBaselineChunker.chunk(result, resource, scannedAt = 42L, maxEntriesPerChunk = 4)

        // A (3) alone, since B (3) would push it past 4; then B and C together at exactly 4.
        assertEquals(listOf(listOf("A"), listOf("B", "C")), chunks.map { c -> c.declaredClasses.map { it.className } })
        assertEquals(listOf(0, 1), chunks.map { it.chunkIndex })
        assertTrue(chunks.all { it.chunkCount == 2 && it.scannedAt == 42L && it.resource == resource })
    }

    @Test
    fun `a class with more methods than the cap gets its own oversized chunk`() {
        val result = StaticScanResult(listOf(declared("Big", 10)), emptyList(), emptyList())

        val chunks = StaticBaselineChunker.chunk(result, resource, scannedAt = 1L, maxEntriesPerChunk = 4)

        assertEquals(1, chunks.size)
        assertEquals(
            10,
            chunks
                .single()
                .declaredClasses
                .single()
                .methods.size,
        )
    }

    @Test
    fun `unsafe, unreadable and unprobed classes count one entry each and share chunks with declared ones`() {
        val result =
            StaticScanResult(
                declaredClasses = listOf(declared("A", 2)),
                staticallyUnsafeClasses = listOf(StaticallyUnsafeClass("U", "r")),
                unreadableClasses = listOf(UnreadableClass("R", "r")),
                unprobedClasses = listOf(UnprobedClass("P", "r")),
            )

        val chunks = StaticBaselineChunker.chunk(result, resource, scannedAt = 1L, maxEntriesPerChunk = 3)

        assertEquals(2, chunks.size)
        assertEquals(listOf("A"), chunks[0].declaredClasses.map { it.className })
        assertEquals(listOf("U"), chunks[0].staticallyUnsafeClasses.map { it.className })
        assertEquals(listOf("R"), chunks[1].unreadableClasses.map { it.className })
        assertEquals(listOf("P"), chunks[1].unprobedClasses.map { it.className })
    }

    @Test
    fun `an empty scan still produces one chunk`() {
        val chunks = StaticBaselineChunker.chunk(StaticScanResult(emptyList(), emptyList(), emptyList()), resource, 1L, 4)

        assertEquals(1, chunks.size)
        assertEquals(0, chunks.single().chunkIndex)
        assertEquals(1, chunks.single().chunkCount)
    }
}
