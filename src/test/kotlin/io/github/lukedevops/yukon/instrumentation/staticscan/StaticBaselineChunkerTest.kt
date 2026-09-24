package io.github.lukedevops.yukon.instrumentation.staticscan

import io.github.lukedevops.yukon.export.BranchOutcome
import io.github.lukedevops.yukon.export.BranchRole
import io.github.lukedevops.yukon.export.BranchSite
import io.github.lukedevops.yukon.export.CallEdge
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
    private val resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "test", "run-1")

    private fun declared(
        name: String,
        methods: Int,
    ) = DeclaredClass(name, (1..methods).map { DeclaredMethod("m$it", "()V") })

    private fun declaredWithEdges(
        name: String,
        edgesPerMethod: Int,
    ) = DeclaredClass(
        name,
        listOf(
            DeclaredMethod(
                "m",
                "()V",
                calls = (1..edgesPerMethod).map { CallEdge("com.example.Callee", "c$it", "()V", virtual = false) },
            ),
        ),
    )

    @Test
    fun `packs whole classes by method count and numbers the chunks`() {
        val result =
            StaticScanResult(
                declaredClasses = listOf(declared("A", 3), declared("B", 3), declared("C", 1)),
                staticallyUnsafeClasses = emptyList(),
                unreadableClasses = emptyList(),
            )

        val chunks = StaticBaselineChunker.chunk(result, resource, scannedAt = 42L, maxEntriesPerChunk = 7)

        // Each class weighs its method count plus one for its own class record: A and B
        // weigh 4 each, C weighs 2. A (4) alone, since B (4) would push it past 7; then B and C
        // together at exactly 6.
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

        val chunks = StaticBaselineChunker.chunk(result, resource, scannedAt = 1L, maxEntriesPerChunk = 4)

        assertEquals(2, chunks.size)
        assertEquals(listOf("A"), chunks[0].declaredClasses.map { it.className })
        assertEquals(listOf("U"), chunks[0].staticallyUnsafeClasses.map { it.className })
        assertEquals(listOf("R"), chunks[1].unreadableClasses.map { it.className })
        assertEquals(listOf("P"), chunks[1].unprobedClasses.map { it.className })
    }

    @Test
    fun `a class with many call edges seals a chunk earlier than one with none, and every chunk holds whole classes`() {
        val result =
            StaticScanResult(
                // "Heavy" weighs 1 method + 3 edges + 1 class record = 5, "Light" weighs
                // 1 method + 0 edges + 1 = 2. A cap of 4 must not let both land in one chunk.
                declaredClasses = listOf(declaredWithEdges("Heavy", edgesPerMethod = 3), declaredWithEdges("Light", edgesPerMethod = 0)),
                staticallyUnsafeClasses = emptyList(),
                unreadableClasses = emptyList(),
            )

        val chunks = StaticBaselineChunker.chunk(result, resource, scannedAt = 1L, maxEntriesPerChunk = 4)

        assertEquals(listOf(listOf("Heavy"), listOf("Light")), chunks.map { c -> c.declaredClasses.map { it.className } })
        chunks.forEach { chunk ->
            chunk.declaredClasses.forEach { assertEquals(1, it.methods.size, "a class is never split across chunks") }
        }
    }

    @Test
    fun `an empty scan still produces one chunk`() {
        val chunks = StaticBaselineChunker.chunk(StaticScanResult(emptyList(), emptyList(), emptyList()), resource, 1L, 4)

        assertEquals(1, chunks.size)
        assertEquals(0, chunks.single().chunkIndex)
        assertEquals(1, chunks.single().chunkCount)
    }

    @Test
    fun `method and class references add to a class's weight, so references alone can seal a chunk`() {
        // Each class weighs 1 method + 1 class record = 2 without references. "First" adds
        // 2 method references and 1 class reference for 5, so a cap of 6 cannot also hold "Second".
        val first =
            DeclaredClass(
                "First",
                listOf(DeclaredMethod("m", "()V", referencedClasses = listOf("org.lib.A", "org.lib.B"))),
                referencedClasses = listOf("org.lib.C"),
            )
        val second = DeclaredClass("Second", listOf(DeclaredMethod("m", "()V")))
        val result = StaticScanResult(listOf(first, second), emptyList(), emptyList())

        val withReferences = StaticBaselineChunker.chunk(result, resource, scannedAt = 1L, maxEntriesPerChunk = 6)
        val withoutReferences =
            StaticBaselineChunker.chunk(
                StaticScanResult(listOf(DeclaredClass("First", listOf(DeclaredMethod("m", "()V"))), second), emptyList(), emptyList()),
                resource,
                scannedAt = 1L,
                maxEntriesPerChunk = 6,
            )

        assertEquals(listOf(listOf("First"), listOf("Second")), withReferences.map { c -> c.declaredClasses.map { it.className } })
        assertEquals(listOf(listOf("First", "Second")), withoutReferences.map { c -> c.declaredClasses.map { it.className } })
    }

    @Test
    fun `branch sites add one for the site and one per outcome to a class's weight`() {
        // "First" weighs 1 method + 1 class record = 2, plus one site with two outcomes = 3, so 5.
        // A cap of 6 cannot also hold "Second", which weighs 2.
        val site =
            BranchSite(
                siteIndex = 0,
                siteKey = null,
                line = 1,
                outcomes = listOf(BranchOutcome(0, BranchRole.TAKEN), BranchOutcome(1, BranchRole.FALL_THROUGH)),
            )
        val first = DeclaredClass("First", listOf(DeclaredMethod("m", "()V", branchSites = listOf(site))))
        val second = DeclaredClass("Second", listOf(DeclaredMethod("m", "()V")))

        val chunks =
            StaticBaselineChunker.chunk(
                StaticScanResult(listOf(first, second), emptyList(), emptyList()),
                resource,
                scannedAt = 1L,
                maxEntriesPerChunk = 6,
            )

        assertEquals(listOf(listOf("First"), listOf("Second")), chunks.map { c -> c.declaredClasses.map { it.className } })
    }
}
