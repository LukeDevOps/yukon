package dev.otherlode.instrumentation.staticscan

import dev.otherlode.export.DeltaBatch
import dev.otherlode.export.ExportFailedException
import dev.otherlode.export.Exporter
import dev.otherlode.export.ProbeManifest
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.export.StaticBaseline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticBaselineSenderTest {
    private val resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "test", "run-1")

    private fun chunks(count: Int) =
        List(count) { StaticBaseline(resource, emptyList(), scannedAt = 1L, chunkIndex = it, chunkCount = count) }

    private class CountingExporter(
        var failure: (StaticBaseline) -> Exception? = { null },
    ) : Exporter {
        val attempts = mutableListOf<Int>()

        override fun exportDeltaBatch(batch: DeltaBatch) {}

        override fun exportManifest(manifest: ProbeManifest) {}

        override fun exportStaticBaseline(baseline: StaticBaseline) {
            attempts += baseline.chunkIndex
            failure(baseline)?.let { throw it }
        }
    }

    @Test
    fun `a chunk the collector refuses for good is dropped with the rest, and never sent again`() {
        val exporter = CountingExporter { ExportFailedException("too large", 413) }
        val sender = StaticBaselineSender(exporter)
        sender.offer(chunks(2))

        assertTrue(sender.sendPending(), "nothing is left to send once the collector refuses the scan")
        repeat(4) { sender.sendPending() }

        assertEquals(listOf(0), exporter.attempts)
    }

    @Test
    fun `the first send goes through the first exporter and a retry through the retry exporter, one chunk at a time`() {
        val first = CountingExporter { if (it.chunkIndex == 1) ExportFailedException("timed out", null) else null }
        val retry = CountingExporter()
        val sender = StaticBaselineSender(first, retryExporter = retry)
        sender.offer(chunks(3))

        assertFalse(sender.sendPending())
        assertFalse(sender.retryPending())
        assertTrue(sender.retryPending())

        assertEquals(listOf(0, 1), first.attempts)
        assertEquals(listOf(1, 2), retry.attempts)
    }

    @Test
    fun `a refusal about the collector or the credentials, not the bytes, keeps the chunks for a later flush`() {
        for (status in listOf(401, 403, 404)) {
            var refusals = 1
            val exporter = CountingExporter { if (refusals-- > 0) ExportFailedException("refused", status) else null }
            val sender = StaticBaselineSender(exporter)
            sender.offer(chunks(1))

            assertFalse(sender.sendPending(), "status $status")
            assertTrue(sender.sendPending(), "status $status")
            assertEquals(listOf(0, 0), exporter.attempts, "status $status")
        }
    }

    @Test
    fun `a chunk that failed for a reason a retry can fix stays pending`() {
        val exporter = CountingExporter { ExportFailedException("unavailable", 503) }
        val sender = StaticBaselineSender(exporter)
        sender.offer(chunks(1))

        assertFalse(sender.sendPending())
        exporter.failure = { null }

        assertTrue(sender.sendPending())
        assertEquals(listOf(0, 0), exporter.attempts)
    }

    @Test
    fun `a capped send sends that many chunks and leaves the rest pending`() {
        val exporter = CountingExporter()
        val sender = StaticBaselineSender(exporter)
        sender.offer(chunks(3))

        assertFalse(sender.sendPending(maxChunks = 1))
        assertEquals(listOf(0), exporter.attempts)
        assertTrue(sender.sendPending())
        assertEquals(listOf(0, 1, 2), exporter.attempts)
    }
}
