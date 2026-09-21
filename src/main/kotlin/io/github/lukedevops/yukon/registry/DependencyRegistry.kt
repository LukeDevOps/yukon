package io.github.lukedevops.yukon.registry

import io.github.lukedevops.yukon.export.DependencyDiscoverySource
import io.github.lukedevops.yukon.export.DependencyIdentity
import io.github.lukedevops.yukon.export.DependencyIdentitySource
import io.github.lukedevops.yukon.export.DependencyLocation
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where a dependency's bytes live on this instance, kept so a loaded class can be matched back to
 * the jar it came from. Never sent: the wire carries only [DependencyLocation.location], a display
 * string.
 */
sealed interface DependencyOrigin {
    /** A jar file on the classpath, by its absolute path. */
    data class FlatJar(
        val path: Path,
    ) : DependencyOrigin

    /** A jar stored inside another, such as `BOOT-INF/lib/x.jar` inside a Spring Boot fat jar. */
    data class NestedJar(
        val outerJar: Path,
        val entryName: String,
    ) : DependencyOrigin
}

/**
 * Tracks the dependencies this instance has seen, one record per identity key, and delivers each
 * record to the collector once. See ADR 0030.
 *
 * A dependency's identity key is the sorted list of its `group:artifact` pairs ([identityKey]),
 * never its version, so two jars carrying the same libraries at different versions are one
 * dependency. Ids are per instance, assigned in registration order from 0, like
 * [EndpointRegistry]'s endpoint ids.
 *
 * Manifest delivery follows [EndpointRegistry]'s snapshot pattern: [computeManifestEntries] stages
 * records onto the snapshot it returns, and [advanceManifest] marks exactly those delivered once
 * the send carrying them is confirmed. A record never changes after registration, so unlike an
 * endpoint it needs no version, only a delivered flag.
 *
 * Safe to use from several threads: the listing thread registers while the export thread reads.
 */
class DependencyRegistry {
    /** One tracked dependency. Instantiable only by [DependencyRegistry]. */
    class DependencyEntry internal constructor(
        val dependencyId: Int,
        val identities: List<DependencyIdentity>,
        val identitySource: DependencyIdentitySource,
        val location: String,
        val discoverySource: DependencyDiscoverySource,
        val classCount: Int?,
        val origin: DependencyOrigin?,
    ) {
        @Volatile
        internal var delivered: Boolean = false

        internal fun toLocation(): DependencyLocation =
            DependencyLocation(dependencyId, identities, identitySource, location, discoverySource, classCount)
    }

    /**
     * One computed manifest chunk, together with the entries it staged. [advanceManifest] marks
     * exactly those delivered.
     */
    class ManifestSnapshot internal constructor(
        val dependencies: List<DependencyLocation>,
        internal val staged: List<DependencyEntry>,
    )

    private val entriesByKey = ConcurrentHashMap<List<String>, DependencyEntry>()
    private val nextDependencyId = AtomicInteger(0)

    @Volatile
    private var listingComplete = false

    /**
     * True once the startup listing has finished and every dependency it found is registered.
     * Stays false when the listing failed, so nothing reads the registry as the full startup
     * classpath when it is not.
     */
    val isListingComplete: Boolean
        get() = listingComplete

    /** Records that the startup listing finished. */
    fun markListingComplete() {
        listingComplete = true
    }

    /**
     * Registers a dependency and returns its id. A dependency whose identity key is already
     * registered keeps the record it has, first registration wins, and this returns that record's
     * id. [identities] must not be empty.
     */
    fun register(
        identities: List<DependencyIdentity>,
        identitySource: DependencyIdentitySource,
        location: String,
        discoverySource: DependencyDiscoverySource,
        classCount: Int? = null,
        origin: DependencyOrigin? = null,
    ): Int {
        require(identities.isNotEmpty()) { "a dependency at $location needs at least one identity" }
        return entriesByKey
            .computeIfAbsent(identityKey(identities)) {
                DependencyEntry(
                    nextDependencyId.getAndIncrement(),
                    identities,
                    identitySource,
                    location,
                    discoverySource,
                    classCount,
                    origin,
                )
            }.dependencyId
    }

    /** Every registered dependency, in id order. */
    fun entries(): List<DependencyEntry> = entriesByKey.values.sortedBy { it.dependencyId }

    /**
     * Returns chunks of at most [maxPerChunk] dependencies, each weighing one entry, covering every
     * dependency not yet delivered, in id order. Empty when there is nothing to send. Nothing is
     * marked delivered here; see [advanceManifest].
     */
    fun computeManifestEntries(maxPerChunk: Int): List<ManifestSnapshot> =
        entries()
            .filterNot { it.delivered }
            .chunked(maxPerChunk)
            .map { chunk -> ManifestSnapshot(chunk.map { it.toLocation() }, chunk) }

    /**
     * Marks every dependency [snapshot] staged as delivered. Call this only once the send carrying
     * it is confirmed; a failed send leaves them for the next compute.
     */
    fun advanceManifest(snapshot: ManifestSnapshot) {
        for (entry in snapshot.staged) entry.delivered = true
    }

    companion object {
        /**
         * The sorted, distinct `group:artifact` pairs of [identities], with an empty group for an
         * identity that has none. The version is never part of it.
         */
        fun identityKey(identities: List<DependencyIdentity>): List<String> =
            identities.map { "${it.groupId.orEmpty()}:${it.artifactId}" }.distinct().sorted()
    }
}
