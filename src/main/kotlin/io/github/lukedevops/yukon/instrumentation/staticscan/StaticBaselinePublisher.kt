package io.github.lukedevops.yukon.instrumentation.staticscan

import io.github.lukedevops.yukon.export.Exporter
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.registry.ProbeRegistry
import java.lang.System.Logger.Level

/**
 * Runs one static baseline scan and publishes the result: primes the
 * [StaticBaselineMismatchDetector], sweeps the classes that already registered dynamically before
 * the scan finished, and sends the scan to the collector in chunks.
 *
 * The sweep exists because the detector can only compare a registration against a scan that has
 * completed. Every class loaded during startup, which is exactly when an app server deploys its
 * WAR, registers before this scan finishes and would otherwise never be checked at all.
 *
 * Chunks are sent in order and the first failure stops the send. There is no next flush to
 * retry the static baseline on, so what the collector has at that point is a partial scan, which
 * it can recognise from `chunk_count` and must not diff as if it were complete.
 */
class StaticBaselinePublisher(
    private val scan: () -> StaticScanResult,
    private val exporter: Exporter,
    private val registry: ProbeRegistry,
    private val mismatchDetector: StaticBaselineMismatchDetector,
    private val maxEntriesPerChunk: Int = DEFAULT_MAX_ENTRIES_PER_CHUNK,
) {
    private val log = System.getLogger(StaticBaselinePublisher::class.java.name)

    fun run(resource: ResourceAttributes) {
        val result = scan()
        mismatchDetector.knownClassNames = result.allClassNames()
        for (className in registry.registeredClassNames()) {
            if (mismatchDetector.shouldWarnAbout(className)) {
                log.log(
                    Level.WARNING,
                    "yukon: $className registered dynamically before the static baseline scan finished and was not " +
                        "found by it - the static scan may have a blind spot for this deployment " +
                        "(see \"Static baseline\" in this project's CLAUDE.md)",
                )
            }
        }

        val chunks = StaticBaselineChunker.chunk(result, resource, System.currentTimeMillis(), maxEntriesPerChunk)
        for (chunk in chunks) {
            try {
                exporter.exportStaticBaseline(chunk)
            } catch (e: Exception) {
                log.log(
                    Level.WARNING,
                    "yukon: failed to send static baseline chunk ${chunk.chunkIndex + 1} of ${chunk.chunkCount}; the " +
                        "remaining chunks are not sent and the scan is not retried until the next process start",
                    e,
                )
                return
            }
        }
    }

    companion object {
        /** Entries are mostly declared methods; at their wire size this is well under a megabyte per POST. */
        const val DEFAULT_MAX_ENTRIES_PER_CHUNK: Int = 20_000
    }
}
