package dev.otherlode.instrumentation.staticscan

import dev.otherlode.export.Exporter
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.registry.ProbeRegistry
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
 * References are passed through [filterReferences] before chunking, so the chunk weights count
 * only what is sent; in the agent that is [BaselineReferenceFilter], which waits for the dependency
 * listing. Without one, or when it throws, every reference list is emptied, since unfiltered lists
 * would carry JDK names and names nothing maps, and losing the references is better than losing
 * the baseline.
 *
 * Chunks are sent in order through [sender], and the first failure stops the send. The chunks
 * left are sent again after a later flush the collector confirms; until they arrive the
 * collector holds a partial scan, which it can recognise from `chunk_count` and must not diff as
 * if it were complete.
 */
class StaticBaselinePublisher(
    private val scan: () -> StaticScanResult,
    exporter: Exporter,
    private val registry: ProbeRegistry,
    private val mismatchDetector: StaticBaselineMismatchDetector,
    private val maxEntriesPerChunk: Int = DEFAULT_MAX_ENTRIES_PER_CHUNK,
    private val filterReferences: (StaticScanResult) -> StaticScanResult = StaticScanResult::withoutReferences,
    private val sender: StaticBaselineSender = StaticBaselineSender(exporter),
) {
    private val log = System.getLogger(StaticBaselinePublisher::class.java.name)

    fun run(resource: ResourceAttributes) {
        val result = scan()
        mismatchDetector.knownClassNames = result.allClassNames()
        for (className in registry.registeredClassNames()) {
            if (mismatchDetector.shouldWarnAbout(className)) {
                log.log(
                    Level.WARNING,
                    "otherlode: $className registered dynamically before the static baseline scan finished and was not " +
                        "found by it; the static scan cannot see classes a server or plugin loader finds at run time, " +
                        "such as a deployed WAR, so this deployment may have such a blind spot",
                )
            }
        }

        val filtered =
            try {
                filterReferences(result)
            } catch (e: Exception) {
                log.log(Level.WARNING, "otherlode: could not filter the static baseline's references; it is sent without them", e)
                result.withoutReferences()
            }
        sender.offer(StaticBaselineChunker.chunk(filtered, resource, System.currentTimeMillis(), maxEntriesPerChunk))
        sender.sendPending()
    }

    companion object {
        /** Entries are mostly declared methods; at their wire size this is well under a megabyte per POST. */
        const val DEFAULT_MAX_ENTRIES_PER_CHUNK: Int = 20_000
    }
}
