package io.github.lukedevops.yukon.registry

import io.github.lukedevops.yukon.export.ExternalClass
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The out-of-scope classes the adopter's code references, each with where the transform found it,
 * resolved to a dependency and delivered once per name. See ADR 0030.
 *
 * [record] runs on transforming threads and only remembers a location string. Resolution waits for
 * the export thread, since it can read a jar, and for [isListingComplete], since before the startup
 * listing has finished a jar on the classpath would be registered as discovered by load. A name
 * whose location [resolveLocation] maps to no dependency (a jar of the adopter's own, an agent jar,
 * an unsupported scheme) is resolved once and never sent. A name no loader could find is sent as
 * absent.
 *
 * Delivery follows [DependencyRegistry]'s snapshot pattern: [computeManifestEntries] stages entries
 * onto the snapshot it returns, and [advanceManifest] marks exactly those delivered once the send
 * carrying them is confirmed.
 */
class ExternalClassRegistry(
    private val isListingComplete: () -> Boolean = { false },
    private val resolveLocation: (String) -> Int? = { null },
) {
    private class Entry(
        val className: String,
        val location: String?,
        val sequence: Long,
    ) {
        @Volatile
        var resolved: Boolean = false

        @Volatile
        var dependencyId: Int? = null

        @Volatile
        var delivered: Boolean = false
    }

    /**
     * One computed manifest chunk, together with the entries it staged. [advanceManifest] marks
     * exactly those delivered.
     */
    class ManifestSnapshot internal constructor(
        val externalClasses: List<ExternalClass>,
        internal val staged: List<Any>,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val nextSequence = AtomicLong(0)

    /**
     * Records where [className] (dotted) was found: a code-source location string, or null when no
     * loader could find it. The first recording of a name wins.
     */
    fun record(
        className: String,
        location: String?,
    ) {
        if (entries.containsKey(className)) return
        entries.putIfAbsent(className, Entry(className, location, nextSequence.getAndIncrement()))
    }

    /**
     * Returns chunks of at most [maxPerChunk] entries, each weighing one, covering every name not yet
     * delivered that maps to a dependency or is absent, in the order the names were first recorded.
     * Resolves any name not yet resolved first. Empty until the dependency listing completes, and
     * when there is nothing to send. Nothing is marked delivered here; see [advanceManifest].
     */
    fun computeManifestEntries(maxPerChunk: Int): List<ManifestSnapshot> {
        if (!isListingComplete()) return emptyList()
        val sendable =
            entries.values
                .asSequence()
                .filterNot { it.delivered }
                .sortedBy { it.sequence }
                .mapNotNull { entry -> toExternalClass(entry)?.let { entry to it } }
                .toList()
        return sendable.chunked(maxPerChunk).map { chunk -> ManifestSnapshot(chunk.map { it.second }, chunk.map { it.first }) }
    }

    private fun toExternalClass(entry: Entry): ExternalClass? {
        val location = entry.location ?: return ExternalClass(entry.className, null, absent = true)
        if (!entry.resolved) {
            entry.dependencyId = resolveLocation(location)
            entry.resolved = true
        }
        return entry.dependencyId?.let { ExternalClass(entry.className, it) }
    }

    /**
     * Marks every entry [snapshot] staged as delivered. Call this only once the send carrying it is
     * confirmed; a failed send leaves them for the next compute.
     */
    fun advanceManifest(snapshot: ManifestSnapshot) {
        for (entry in snapshot.staged) (entry as Entry).delivered = true
    }
}
