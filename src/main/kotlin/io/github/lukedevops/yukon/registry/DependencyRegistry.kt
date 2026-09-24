package io.github.lukedevops.yukon.registry

import io.github.lukedevops.yukon.export.DependencyDelta
import io.github.lukedevops.yukon.export.DependencyDiscoverySource
import io.github.lukedevops.yukon.export.DependencyIdentity
import io.github.lukedevops.yukon.export.DependencyIdentitySource
import io.github.lukedevops.yukon.export.DependencyLocation
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
 * Each record also holds the distinct class names the sweep has matched to it ([recordLoaded]).
 * Their count goes out as a cumulative total through [computeDeltas] and [advanceDeltas], the same
 * snapshot pattern, whenever it differs from the last total delivered.
 *
 * Constructed with `indexClassNames`, the registry also keeps a class index: every class name of
 * every dependency the startup listing registers, mapped to that dependency's id, the first
 * registration of a name winning as the classpath search order would. The static baseline maps its
 * references through it ([dependencyForClass]), since it has no defining loader to ask. Without the
 * flag no index is kept: it costs memory in proportion to the dependencies' class count. For the
 * same reason the baseline releases it ([releaseClassIndex]) once it has been read.
 *
 * A record is held back from the manifest until its loaded-class count has reached the collector,
 * so a collector never reads a used jar as unloaded (ADR 0036). Each counting sweep ends with
 * [markCounted], which opens a counting generation. A record takes the first generation that finds
 * it registered. Once every delta send computed after that sweep is confirmed, the export thread
 * calls [markCountsDelivered] with it. [computeManifestEntries] offers only records whose
 * generation has been delivered this way ([isSendable]).
 *
 * Safe to use from several threads: the listing thread registers, the sweep records loads and
 * registers jars found at load on the scheduler thread, and the send pool computes and advances.
 */
class DependencyRegistry(
    indexClassNames: Boolean = false,
) {
    /** How the startup listing ended, as [awaitListing] saw it. */
    enum class ListingOutcome {
        /** Finished, with every dependency it found registered. */
        COMPLETE,

        /** Failed; nothing it found is registered. */
        FAILED,

        /** Still running when the wait ran out. */
        TIMED_OUT,
    }

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

        /** Every distinct class name a sweep has matched to this dependency, kept for the life of the process. */
        internal val loadedClassNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Stamped by the first [computeDeltas] that sees a non-zero total; 0 until then. */
        @Volatile
        internal var firstLoadedAt: Long = 0

        /** The counting generation that first counted this dependency; 0 until a [markCounted] sees it. */
        @Volatile
        internal var countedInGeneration: Long = 0

        /** The last loaded-class total a confirmed delta send carried. */
        internal var lastDelivered: Long = 0

        /** Sequence number of the newest [DeltaSnapshot] applied to [lastDelivered]; see [advanceDeltas]. */
        internal var lastAppliedDeltaSequence: Long = 0

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

    /**
     * One computed delta batch, together with the totals it was built from. [advanceDeltas] takes
     * this back, so a snapshot confirmed late never marks totals it did not carry as delivered.
     */
    class DeltaSnapshot internal constructor(
        val deltas: List<DependencyDelta>,
        internal val sequence: Long,
        internal val staged: List<Pair<DependencyEntry, Long>>,
    )

    private val entriesByKey = ConcurrentHashMap<List<String>, DependencyEntry>()
    private val entriesById = ConcurrentHashMap<Int, DependencyEntry>()
    private val entriesByOrigin = ConcurrentHashMap<DependencyOrigin, DependencyEntry>()
    private val judgedNotDependencies: MutableSet<DependencyOrigin> = ConcurrentHashMap.newKeySet()
    private val nextDependencyId = AtomicInteger(0)
    private val nextDeltaSequence = AtomicLong(0)

    @Volatile
    private var classIndex: ConcurrentHashMap<String, Int>? = if (indexClassNames) ConcurrentHashMap() else null
    private val listingEnded = CountDownLatch(1)

    @Volatile
    private var listingComplete = false

    private val countGenerations = AtomicLong(0)
    private val deliveredGenerations = AtomicLong(0)

    @Volatile
    private var startupListingDelivered = false

    /**
     * True once the startup listing has finished and every dependency it found is registered.
     * Stays false when the listing failed, so nothing reads the registry as the full startup
     * classpath when it is not.
     */
    val isListingComplete: Boolean
        get() = listingComplete

    /** Records that the startup listing finished, releasing every [awaitListing]. */
    fun markListingComplete() {
        listingComplete = true
        listingEnded.countDown()
    }

    /**
     * Records that the startup listing failed, releasing every [awaitListing] at once rather than
     * leaving it to wait out its timeout. [isListingComplete] stays false.
     */
    fun markListingFailed() {
        listingEnded.countDown()
    }

    /** Waits up to [timeout] for the startup listing to end, and says how it ended. */
    fun awaitListing(timeout: Duration): ListingOutcome {
        val ended = listingEnded.await(timeout.toNanos(), TimeUnit.NANOSECONDS)
        return when {
            listingComplete -> ListingOutcome.COMPLETE
            ended -> ListingOutcome.FAILED
            else -> ListingOutcome.TIMED_OUT
        }
    }

    /** The id of the dependency the class index maps [className] (dotted) to, or null. Always null without the index. */
    fun dependencyForClass(className: String): Int? = classIndex?.get(className)

    /** How many class names the class index holds; 0 without the index or once it is released. */
    val classIndexSize: Int
        get() = classIndex?.size ?: 0

    /**
     * Drops the class index and stops keeping one, so its memory is not held for the life of the
     * process once the static baseline, its one reader, has been filtered.
     */
    fun releaseClassIndex() {
        classIndex = null
    }

    /**
     * Registers a dependency and returns its id. A dependency whose identity key is already
     * registered keeps the record it has, first registration wins, and this returns that record's
     * id. [identities] must not be empty.
     *
     * With the class index kept, each of [classNames] not yet indexed is mapped to the returned id.
     */
    fun register(
        identities: List<DependencyIdentity>,
        identitySource: DependencyIdentitySource,
        location: String,
        discoverySource: DependencyDiscoverySource,
        classCount: Int? = null,
        origin: DependencyOrigin? = null,
        classNames: Collection<String> = emptyList(),
    ): Int {
        require(identities.isNotEmpty()) { "a dependency at $location needs at least one identity" }
        val id = registerEntry(identities, identitySource, location, discoverySource, classCount, origin)
        classIndex?.let { index -> for (className in classNames) index.putIfAbsent(className, id) }
        return id
    }

    private fun registerEntry(
        identities: List<DependencyIdentity>,
        identitySource: DependencyIdentitySource,
        location: String,
        discoverySource: DependencyDiscoverySource,
        classCount: Int?,
        origin: DependencyOrigin?,
    ): Int =
        entriesByKey
            .computeIfAbsent(identityKey(identities)) {
                val entry =
                    DependencyEntry(
                        nextDependencyId.getAndIncrement(),
                        identities,
                        identitySource,
                        location,
                        discoverySource,
                        classCount,
                        origin,
                    )
                entriesById[entry.dependencyId] = entry
                if (origin != null) entriesByOrigin.putIfAbsent(origin.canonical(), entry)
                entry
            }.dependencyId

    /**
     * The id of the dependency registered with [origin], compared by canonical path so a symlinked
     * or relative classpath entry still matches the location the JVM reports. Null when none was.
     */
    fun idForOrigin(origin: DependencyOrigin): Int? = entriesByOrigin[origin.canonical()]?.dependencyId

    /**
     * Records that the startup listing read the jar at [origin] and judged it not a dependency (an
     * agent jar, a Spring Boot fat jar, a jar of the adopter's own), so the sweep treats it as
     * such without reading it a second time.
     */
    fun recordNotADependency(origin: DependencyOrigin) {
        judgedNotDependencies += origin.canonical()
    }

    /** Whether the startup listing judged the jar at [origin] not a dependency, compared by canonical path. */
    fun isJudgedNotADependency(origin: DependencyOrigin): Boolean = origin.canonical() in judgedNotDependencies

    /** The id of the dependency registered under [identityKey], or null. */
    fun idForKey(identityKey: List<String>): Int? = entriesByKey[identityKey]?.dependencyId

    /**
     * Adds [className] to the distinct class names seen from dependency [dependencyId]. A name
     * already seen, from any loader, adds nothing. An unknown id is ignored.
     */
    fun recordLoaded(
        dependencyId: Int,
        className: String,
    ) {
        entriesById[dependencyId]?.loadedClassNames?.add(className)
    }

    /**
     * Returns batches of at most [maxPerBatch] deltas, each weighing one entry, covering every
     * dependency whose distinct loaded-class total differs from its last delivered total. Empty
     * when nothing changed. Stamps a dependency's first-loaded time the first time a compute sees
     * its total above zero, so the time has the precision of a flush.
     *
     * Nothing is marked delivered here; see [advanceDeltas].
     */
    fun computeDeltas(maxPerBatch: Int): List<DeltaSnapshot> {
        val batches = mutableListOf<DeltaSnapshot>()
        var deltas = mutableListOf<DependencyDelta>()
        var staged = mutableListOf<Pair<DependencyEntry, Long>>()
        for (entry in entries()) {
            val total = entry.loadedClassNames.size.toLong()
            if (total == synchronized(entry) { entry.lastDelivered }) continue
            if (total > 0 && entry.firstLoadedAt == 0L) entry.firstLoadedAt = System.currentTimeMillis()
            if (deltas.size == maxPerBatch) {
                batches += DeltaSnapshot(deltas, nextDeltaSequence.incrementAndGet(), staged)
                deltas = mutableListOf()
                staged = mutableListOf()
            }
            deltas += DependencyDelta(entry.dependencyId, entry.firstLoadedAt, total)
            staged += entry to total
        }
        if (deltas.isNotEmpty()) batches += DeltaSnapshot(deltas, nextDeltaSequence.incrementAndGet(), staged)
        return batches
    }

    /**
     * Records the totals [snapshot] staged as delivered. Call this only once the send carrying it
     * is confirmed. An older snapshot confirmed after a newer one is ignored per entry, so a
     * confirmed total is never rolled back.
     */
    fun advanceDeltas(snapshot: DeltaSnapshot) {
        for ((entry, total) in snapshot.staged) {
            synchronized(entry) {
                if (snapshot.sequence <= entry.lastAppliedDeltaSequence) return@synchronized
                entry.lastAppliedDeltaSequence = snapshot.sequence
                entry.lastDelivered = total
            }
        }
    }

    /**
     * The newest counting generation, or 0 before any sweep has counted. Read it after a sweep and
     * before the delta sends that carry the sweep's counts, then pass it to [markCountsDelivered].
     */
    val countGeneration: Long
        get() = countGenerations.get()

    /** The newest counting generation whose counts a confirmed delta send carried; see [markCountsDelivered]. */
    val deliveredGeneration: Long
        get() = deliveredGenerations.get()

    /**
     * Records that a counting sweep finished. This opens the next counting generation and stamps it
     * on every record that no earlier generation has stamped.
     *
     * No lock guards registration against this. After the listing completes, a jar is registered
     * only inside a sweep's count or through `resolveLocation` on a send thread. Neither overlaps
     * this call, because each flush waits for its sends before the next sweep runs.
     */
    @Synchronized
    fun markCounted() {
        val generation = countGenerations.get() + 1
        for (entry in entriesByKey.values) {
            if (entry.countedInGeneration == 0L) entry.countedInGeneration = generation
        }
        countGenerations.set(generation)
    }

    /**
     * Records that every delta send computed after the sweep of [generation] was confirmed. The
     * delivered generation only rises: an older [generation] changes nothing.
     */
    fun markCountsDelivered(generation: Long) {
        deliveredGenerations.accumulateAndGet(generation, ::maxOf)
    }

    /**
     * Whether dependency [dependencyId] may go out on a manifest: a sweep counted it, and the
     * counts of that sweep have been delivered. False for an unknown id.
     */
    fun isSendable(dependencyId: Int): Boolean = entriesById[dependencyId]?.let(::isSendable) ?: false

    private fun isSendable(entry: DependencyEntry): Boolean {
        val generation = entry.countedInGeneration
        return generation in 1..deliveredGenerations.get()
    }

    /**
     * Whether any record is sendable ([isSendable]) and has not gone out on a confirmed manifest.
     * A mapping is held only on such a record, so false means nothing is waiting on a release.
     */
    fun hasSendableUndelivered(): Boolean = entriesByKey.values.any { !it.delivered && isSendable(it) }

    /**
     * True once the startup listing is complete and every record it registered has gone out on a
     * confirmed manifest. A record discovered by load never holds it back. It stays false after a
     * failed listing. Once true, it stays true.
     */
    val isStartupListingDelivered: Boolean
        get() {
            if (startupListingDelivered) return true
            if (!listingComplete) return false
            val delivered =
                entriesByKey.values.all { it.discoverySource != DependencyDiscoverySource.STARTUP_CLASSPATH || it.delivered }
            if (delivered) startupListingDelivered = true
            return delivered
        }

    /** Every registered dependency, in id order. */
    fun entries(): List<DependencyEntry> = entriesByKey.values.sortedBy { it.dependencyId }

    /**
     * Returns chunks of at most [maxPerChunk] dependencies, each weighing one entry, covering every
     * dependency not yet delivered whose counts have been delivered ([isSendable]), in id order.
     * Empty when there is nothing to send. Nothing is marked delivered here; see [advanceManifest].
     */
    fun computeManifestEntries(maxPerChunk: Int): List<ManifestSnapshot> =
        entries()
            .filter { !it.delivered && isSendable(it) }
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

        private fun DependencyOrigin.canonical(): DependencyOrigin =
            when (this) {
                is DependencyOrigin.FlatJar -> DependencyOrigin.FlatJar(canonicalPath(path))
                is DependencyOrigin.NestedJar -> DependencyOrigin.NestedJar(canonicalPath(outerJar), entryName)
            }

        private fun canonicalPath(path: Path): Path =
            runCatching { path.toFile().canonicalFile.toPath() }.getOrDefault(path.toAbsolutePath())
    }
}
