package dev.otherlode.instrumentation.staticscan

import dev.otherlode.export.ExportFailedException
import dev.otherlode.export.Exporter
import dev.otherlode.export.StaticBaseline
import java.lang.System.Logger.Level
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Holds the static baseline chunks the collector has not yet confirmed and sends them in order.
 *
 * The scan runs once per process, so a chunk the collector never confirmed would otherwise be
 * lost for the life of the process. [StaticBaselinePublisher] offers the chunks and makes the
 * first attempt through [exporter]; [dev.otherlode.export.ExportScheduler] calls [retryPending]
 * after a flush whose own sends the collector confirmed, which is when a retry can succeed. A retry
 * runs on the flush thread, so it sends one chunk through [retryExporter], which should make one
 * attempt: a flush then holds the scheduler for one request timeout at most, and the shutdown
 * flush, which waits for it, keeps its budget.
 * A chunk that reached the collector with its answer lost is sent twice, which a collector reads
 * as one, since it keeps received chunks by index. A chunk the collector refuses for its content
 * (a 400, 413 or 422) drops every pending chunk, since resending the same bytes would only be
 * refused again. Any other refusal (a 401 while a token rotates, a 404 before a collector serves
 * the route) keeps them, to be sent after a later flush the collector confirms.
 *
 * Only one thread sends at a time. A call that finds another sending returns at once rather than
 * wait, so the flush thread never blocks behind the scan thread's exporter backoff.
 */
class StaticBaselineSender(
    private val exporter: Exporter,
    private val retryExporter: Exporter = exporter,
) {
    private val log = System.getLogger(StaticBaselineSender::class.java.name)
    private val lock = ReentrantLock()

    /** Guarded by [lock]. */
    private val pending = ArrayDeque<StaticBaseline>()

    /** Guarded by [lock]. */
    private var failureLogged = false

    @Volatile
    private var hasPending = false

    /** Queues [chunks] behind anything still pending. */
    fun offer(chunks: List<StaticBaseline>) {
        lock.withLock {
            pending.addAll(chunks)
            hasPending = pending.isNotEmpty()
        }
    }

    /**
     * Sends up to [maxChunks] pending chunks in order, stopping at the first that fails. True when
     * nothing is left pending; false when a chunk failed, chunks are left over, or another thread
     * is sending.
     */
    fun sendPending(maxChunks: Int = Int.MAX_VALUE): Boolean = send(maxChunks, exporter)

    /** Sends the first pending chunk through [retryExporter], with the same answer as [sendPending]. */
    fun retryPending(): Boolean = send(1, retryExporter)

    private fun send(
        maxChunks: Int,
        exporter: Exporter,
    ): Boolean {
        if (!hasPending) return true
        if (!lock.tryLock()) return false
        try {
            var sent = 0
            while (pending.isNotEmpty()) {
                if (sent == maxChunks) return false
                val chunk = pending.first()
                try {
                    exporter.exportStaticBaseline(chunk)
                } catch (e: ExportFailedException) {
                    val status = e.statusCode
                    if (status == null || status !in REFUSED_CONTENT) return failed(chunk, e)
                    log.log(
                        Level.WARNING,
                        "otherlode: the collector refused static baseline chunk ${chunk.chunkIndex + 1} of ${chunk.chunkCount} " +
                            "with status $status, which resending the same bytes cannot change; the scan is not sent again",
                        e,
                    )
                    pending.clear()
                    hasPending = false
                    return true
                } catch (e: Exception) {
                    return failed(chunk, e)
                }
                pending.removeFirst()
                sent++
            }
            hasPending = false
            if (failureLogged) log.log(Level.INFO, "otherlode: the static baseline reached the collector after a retry")
            return true
        } finally {
            lock.unlock()
        }
    }

    /** Logs the first failure that keeps the chunk; it stays pending. Called holding [lock]. */
    private fun failed(
        chunk: StaticBaseline,
        e: Exception,
    ): Boolean {
        if (!failureLogged) {
            failureLogged = true
            log.log(
                Level.WARNING,
                "otherlode: failed to send static baseline chunk ${chunk.chunkIndex + 1} of ${chunk.chunkCount}; it and " +
                    "the chunks after it are sent again after a flush the collector confirms",
                e,
            )
        }
        return false
    }

    private companion object {
        /** Statuses that refuse a request's content, which the same bytes would meet again. */
        val REFUSED_CONTENT = setOf(400, 413, 422)
    }
}
