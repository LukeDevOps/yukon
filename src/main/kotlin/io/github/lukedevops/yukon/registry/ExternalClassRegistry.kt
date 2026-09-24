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
 * The static baseline records names it has already mapped through [recordResolved]; they go out on
 * the manifest the same way, so the baseline itself never carries a mapping and no name is sent
 * twice.
 *
 * A name that maps to a dependency is held while [isDependencySendable] is false for that id. It is
 * neither delivered nor dropped, and a later compute offers it. An absent name names no dependency
 * and is never held.
 *
 * Delivery follows [DependencyRegistry]'s snapshot pattern: [computeManifestEntries] stages entries
 * onto the snapshot it returns, and [advanceManifest] marks exactly those delivered once the send
 * carrying them is confirmed.
 */
class ExternalClassRegistry(
    private val isListingComplete: () -> Boolean = { false },
    private val resolveLocation: (String) -> Int? = { null },
    /**
     * Whether a mapping to this dependency id may go out. A mapping waits until its dependency's
     * own entry may go out; see [DependencyRegistry.isSendable] and ADR 0036.
     */
    private val isDependencySendable: (Int) -> Boolean = { true },
) {
    private class Entry(
        val className: String,
        val location: String?,
        val sequence: Long,
        val absent: Boolean,
        resolvedId: Int? = null,
    ) {
        @Volatile
        var resolved: Boolean = resolvedId != null

        @Volatile
        var dependencyId: Int? = resolvedId

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

    /** The first sequence number past the backlog; -1 until the first compute after the listing. */
    @Volatile
    private var backlogEnd: Long = -1

    @Volatile
    private var backlogDelivered = false

    /**
     * True once every name recorded before the first compute after the listing completed has
     * gone out on a confirmed send, or resolved to no dependency. A name recorded later never holds
     * it back. False before that first compute, so false for good when the listing never
     * completes. Once true, it stays true. See ADR 0036.
     */
    val isBacklogDelivered: Boolean
        get() {
            if (backlogDelivered) return true
            val end = backlogEnd
            if (end < 0) return false
            val delivered =
                entries.values.all { entry ->
                    entry.sequence >= end || entry.delivered || (entry.resolved && !entry.absent && entry.dependencyId == null)
                }
            if (delivered) backlogDelivered = true
            return delivered
        }

    /**
     * Records where [className] (dotted) was found: a code-source location string, or null when no
     * loader could find it. The first recording of a name wins.
     */
    fun record(
        className: String,
        location: String?,
    ) {
        if (entries.containsKey(className)) return
        entries.putIfAbsent(className, Entry(className, location, nextSequence.getAndIncrement(), absent = location == null))
    }

    /**
     * Records [className] (dotted) as already resolved: to dependency [dependencyId], or as absent
     * when it is null. The static baseline records this way, having mapped the name from the
     * startup listing's class index rather than from a loader. The first recording of a name wins,
     * whichever way it was made.
     */
    fun recordResolved(
        className: String,
        dependencyId: Int?,
    ) {
        if (entries.containsKey(className)) return
        entries.putIfAbsent(
            className,
            Entry(className, null, nextSequence.getAndIncrement(), absent = dependencyId == null, resolvedId = dependencyId),
        )
    }

    /**
     * Returns chunks of at most [maxPerChunk] entries, each weighing one, covering every name not yet
     * delivered that is absent or maps to a dependency [isDependencySendable] allows, in the order
     * the names were first recorded. Resolves any name not yet resolved first. Empty until the
     * dependency listing completes, and when there is nothing to send. The first call after the
     * listing completes fixes the backlog that [isBacklogDelivered] waits for. Nothing is marked
     * delivered here; see [advanceManifest].
     */
    fun computeManifestEntries(maxPerChunk: Int): List<ManifestSnapshot> {
        if (!isListingComplete()) return emptyList()
        if (backlogEnd < 0) backlogEnd = nextSequence.get()
        val sendable =
            entries.values
                .asSequence()
                .filterNot { it.delivered }
                .sortedBy { it.sequence }
                .mapNotNull { entry -> toExternalClass(entry)?.let { entry to it } }
                .filter { (_, external) -> external.dependencyId?.let(isDependencySendable) ?: true }
                .toList()
        return sendable.chunked(maxPerChunk).map { chunk -> ManifestSnapshot(chunk.map { it.second }, chunk.map { it.first }) }
    }

    private fun toExternalClass(entry: Entry): ExternalClass? {
        if (entry.absent) return ExternalClass(entry.className, null, absent = true)
        if (!entry.resolved) {
            entry.dependencyId = entry.location?.let(resolveLocation)
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
