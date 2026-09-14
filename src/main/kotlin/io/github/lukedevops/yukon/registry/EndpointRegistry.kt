package io.github.lukedevops.yukon.registry

import io.github.lukedevops.yukon.export.DisabledEndpointModule
import io.github.lukedevops.yukon.export.EndpointDelta
import io.github.lukedevops.yukon.export.EndpointDiscoverySource
import io.github.lukedevops.yukon.export.EndpointLocation
import java.lang.System.Logger.Level
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The method or object a framework invokes for an endpoint. [methodName] and [descriptor] are
 * null when only the handler object's class is known, which is what a framework hands over at
 * first dispatch for a handler whose registration API never named a method.
 */
data class HandlerRef(
    val className: String,
    val methodName: String? = null,
    val descriptor: String? = null,
)

/**
 * Tracks one probe per HTTP endpoint, counted at the point a framework matches a request to it,
 * before the handler runs. See ADR 0017 for why endpoints need their own probe: a method probe
 * alone cannot say "GET /promo was never served", since a handler can back two endpoints, be a
 * lambda the framework invokes through a hidden class, or live outside `includePackages`.
 *
 * Identity is `(verb, normalised route template)`, computed by [RouteTemplateNormalizer]. Two
 * registrations that normalise to the same identity, even from different framework objects, are
 * one endpoint with one [EndpointEntry.endpointId] and one count.
 *
 * A framework module also supplies one or more dispatch keys: arbitrary objects, compared by
 * `equals`/`hashCode`, that identify the framework's own route object (a Spring `HandlerMethod`,
 * a Ktor route node, a JDK `HttpContext`). [lookup] resolves a key to its entry on the request
 * path, so normalisation runs once per endpoint rather than once per request.
 *
 * Follows the same snapshot pattern as [ProbeRegistry]: [computeDeltas]/[advanceDeltas] and
 * [computeManifestEntries]/[advanceManifest] stage state onto the returned snapshot rather than
 * directly onto the registry, so two sends in flight at once (a scheduled flush and a shutdown
 * flush) cannot mark each other's unconfirmed work as delivered.
 *
 * Unlike a class's probe locations, which are sent once and never again, an endpoint record can
 * change after it is first delivered (its discovery source upgrades, or a handler join is
 * learned later). Each entry therefore carries a version bumped on every such change; a manifest
 * snapshot remembers the version it staged per entry, and [advanceManifest] only ever raises
 * `deliveredVersion`, never lowers it. A change landing while a send is in flight is still picked
 * up by the next compute, since the version it bumped to was never staged by that in-flight send.
 */
class EndpointRegistry {
    private val log = System.getLogger(EndpointRegistry::class.java.name)

    private data class Identity(
        val verb: String,
        val routeTemplate: String,
    )

    /**
     * One tracked endpoint. Instantiable only by [EndpointRegistry]: the constructor is
     * `internal`, the same pattern [ProbeRegistry.DeltaSnapshot] uses to keep a type public for
     * callers to read while construction stays with the registry that owns its invariants.
     */
    class EndpointEntry internal constructor(
        val endpointId: Int,
        val verb: String,
        val routeTemplate: String,
        val verbatimTemplate: String,
        val framework: String,
        discoverySource: EndpointDiscoverySource,
    ) {
        @Volatile
        var discoverySource: EndpointDiscoverySource = discoverySource
            internal set

        @Volatile
        var handlerClass: String? = null
            internal set

        @Volatile
        var handlerMethod: String? = null
            internal set

        @Volatile
        var handlerDescriptor: String? = null
            internal set

        /**
         * Hit count, incremented once per matched request. A plain, non-atomic increment: the
         * same accepted lost-update tradeoff [ProbeRegistry]'s count arrays make, since an
         * occasional lost count under race does not matter for a 30-60s delta export.
         */
        @JvmField
        var count: Long = 0

        /** Increments [count]. No synchronisation, so this never blocks the request path. */
        fun hit() {
            count++
        }

        /** Bumped on any change a collector needs to see again: creation, a discovery-source upgrade, or a new handler join. */
        @Volatile
        internal var version: Long = 1

        /** The highest [version] a confirmed manifest send has carried for this entry. */
        @Volatile
        internal var deliveredVersion: Long = 0

        /** The last cumulative [count] successfully delivered to the collector. */
        @Volatile
        internal var lastSent: Long = 0

        internal var firstSeenAt: Long = 0

        /** Tracks whether the one-time decrease warning has already been logged for this entry. */
        internal var decreaseWarned: Boolean = false

        /** Sequence number of the newest [DeltaSnapshot] applied to [lastSent]; see [advanceDeltas]. */
        internal var lastAppliedDeltaSequence: Long = 0
    }

    private class DisabledModuleEntry(
        val reason: String,
        val disabledAt: Long,
    ) {
        var manifestIncluded: Boolean = false
    }

    /**
     * One computed delta batch, together with the exact per-entry counts it was built from.
     * [advanceDeltas] takes this back rather than reading staging state off the registry, so two
     * flushes can be in flight at once without one marking the other's hits as delivered.
     */
    class DeltaSnapshot internal constructor(
        val deltas: List<EndpointDelta>,
        internal val sequence: Long,
        internal val staged: List<Pair<EndpointEntry, Long>>,
    )

    /**
     * One computed manifest chunk, together with the entries and disabled modules it staged.
     * [advanceManifest] marks exactly those as delivered, each endpoint at the version this
     * snapshot staged, never at whatever version it may have reached since.
     */
    class ManifestSnapshot internal constructor(
        val endpoints: List<EndpointLocation>,
        val disabledModules: List<DisabledEndpointModule>,
        internal val stagedEndpoints: List<Pair<EndpointEntry, Long>>,
        internal val stagedModules: List<String>,
    )

    private val entriesByIdentity = ConcurrentHashMap<Identity, EndpointEntry>()
    private val keyToEntry = ConcurrentHashMap<Any, EndpointEntry>()
    private val disabledModulesByName = ConcurrentHashMap<String, DisabledModuleEntry>()
    private val nextEndpointId = AtomicInteger(0)
    private val nextDeltaSequence = AtomicLong(0)

    /**
     * Resolves a dispatch key to its entry. This is the hot-path lookup a dispatch hook calls on
     * every request: one [ConcurrentHashMap.get], no allocation, null when [key] is not bound to
     * any entry yet.
     */
    fun lookup(key: Any): EndpointEntry? = keyToEntry[key]

    /**
     * Records an endpoint from a framework's own registration hook (Spring's handler-method
     * mapping, Ktor's `handle`, `HttpServer.createContext`). Normalises [verb] and
     * [verbatimTemplate] (with [contextPath] folded in first) into an identity, finds or creates
     * the entry for that identity, binds [key] to it, and marks it discovered by registration.
     *
     * An entry already discovered by registration is left alone: nothing changed, so there is
     * nothing to re-send. An entry previously known only from a dispatch is upgraded, which bumps
     * its version so the next manifest compute includes it again with the corrected source.
     *
     * [handler], when given, is attached the same way [attachHandler] would.
     */
    fun register(
        key: Any,
        framework: String,
        verb: String?,
        verbatimTemplate: String,
        contextPath: String? = null,
        handler: HandlerRef? = null,
    ): EndpointEntry {
        val entry = findOrCreate(framework, verb, verbatimTemplate, contextPath, EndpointDiscoverySource.REGISTRATION)
        keyToEntry[key] = entry
        upgradeDiscoverySource(entry, EndpointDiscoverySource.REGISTRATION)
        if (handler != null) attachHandler(entry, handler)
        return entry
    }

    /**
     * Records an endpoint the moment a request dispatches to it, for a [key] [lookup] did not
     * resolve. Finds or creates the entry for the normalised identity, marking a newly created
     * one as discovered by dispatch, and binds [key] to it.
     *
     * [handlerClass] is attached only when the entry has no handler join yet, since a dispatch
     * only ever hands over a class, never a method; a fuller join a registration hook already
     * recorded is never replaced by this weaker one.
     *
     * The caller calls [EndpointEntry.hit] itself once this returns; this method only resolves
     * the entry.
     */
    fun recordDispatch(
        key: Any,
        framework: String,
        verb: String?,
        verbatimTemplate: String,
        contextPath: String? = null,
        handlerClass: String? = null,
    ): EndpointEntry {
        val entry = findOrCreate(framework, verb, verbatimTemplate, contextPath, EndpointDiscoverySource.DISPATCH)
        keyToEntry[key] = entry
        if (handlerClass != null) attachHandler(entry, HandlerRef(handlerClass))
        return entry
    }

    private fun findOrCreate(
        framework: String,
        verb: String?,
        verbatimTemplate: String,
        contextPath: String?,
        initialSource: EndpointDiscoverySource,
    ): EndpointEntry {
        val identity =
            Identity(
                RouteTemplateNormalizer.normalizeVerb(verb),
                RouteTemplateNormalizer.normalize(verbatimTemplate, contextPath),
            )
        return entriesByIdentity.computeIfAbsent(identity) {
            EndpointEntry(
                endpointId = nextEndpointId.getAndIncrement(),
                verb = identity.verb,
                routeTemplate = identity.routeTemplate,
                verbatimTemplate = verbatimTemplate,
                framework = framework,
                discoverySource = initialSource,
            )
        }
    }

    private fun upgradeDiscoverySource(
        entry: EndpointEntry,
        source: EndpointDiscoverySource,
    ) {
        if (entry.discoverySource == source) return
        synchronized(entry) {
            if (entry.discoverySource == source) return@synchronized
            entry.discoverySource = source
            entry.version++
        }
    }

    /**
     * Attaches [handler] to [entry]'s join if none is present, or upgrades a class-only join to a
     * full method join. A full join already present, meaning [EndpointEntry.handlerMethod] is
     * already set, is never overwritten: registration-time joins are trusted over anything a
     * later dispatch might offer.
     *
     * Any actual change bumps [EndpointEntry.version], so the entry is included in the next
     * manifest compute even if it was already delivered.
     */
    fun attachHandler(
        entry: EndpointEntry,
        handler: HandlerRef,
    ) {
        synchronized(entry) {
            if (entry.handlerMethod != null) return@synchronized
            val changed =
                entry.handlerClass != handler.className ||
                    entry.handlerMethod != handler.methodName ||
                    entry.handlerDescriptor != handler.descriptor
            if (!changed) return@synchronized
            entry.handlerClass = handler.className
            entry.handlerMethod = handler.methodName
            entry.handlerDescriptor = handler.descriptor
            entry.version++
        }
    }

    /**
     * Records an endpoint module (one framework's registration/dispatch hooks) that switched
     * itself off, typically on a linkage failure against an unexpected framework version.
     * Idempotent per module name: a repeat call keeps the first reason and timestamp, the same
     * as [ProbeRegistry.recordSkipped].
     */
    fun recordDisabledModule(
        module: String,
        reason: String,
    ) {
        disabledModulesByName.computeIfAbsent(module) { DisabledModuleEntry(reason, System.currentTimeMillis()) }
    }

    /** A full snapshot of every tracked endpoint, in [EndpointEntry.endpointId] order. */
    fun endpoints(): List<EndpointLocation> = entriesByIdentity.values.sortedBy { it.endpointId }.map { it.toLocation() }

    /** A full snapshot of every disabled endpoint module. */
    fun disabledModules(): List<DisabledEndpointModule> =
        disabledModulesByName.map { (module, entry) -> DisabledEndpointModule(module, entry.reason, entry.disabledAt) }

    /**
     * Compares each entry's live count against its last successfully sent value, and returns
     * batches of at most [maxPerBatch] endpoints whose count changed. Returns an empty list when
     * nothing changed: unlike [ProbeRegistry.computeDeltaBatches], an endpoint delta batch has no
     * liveness-heartbeat role, so there is no reason to send an empty one.
     *
     * Nothing is marked as sent here. Each returned [DeltaSnapshot] carries the counts it was
     * built from, and [advanceDeltas] must be called with it explicitly, only once that batch is
     * confirmed delivered.
     */
    fun computeDeltas(maxPerBatch: Int): List<DeltaSnapshot> {
        val batches = mutableListOf<DeltaSnapshot>()
        var deltas = mutableListOf<EndpointDelta>()
        var staged = mutableListOf<Pair<EndpointEntry, Long>>()

        fun seal() {
            if (deltas.isEmpty()) return
            batches += DeltaSnapshot(deltas, nextDeltaSequence.incrementAndGet(), staged)
            deltas = mutableListOf()
            staged = mutableListOf()
        }

        for (entry in entriesByIdentity.values) {
            val current = entry.count
            val lastSent = entry.lastSent
            if (current == lastSent) continue
            if (current < lastSent) warnOnceAboutDecrease(entry, lastSent, current)
            if (current > 0 && entry.firstSeenAt == 0L) entry.firstSeenAt = System.currentTimeMillis()
            if (deltas.size == maxPerBatch) seal()
            deltas += EndpointDelta(entry.endpointId, entry.firstSeenAt, current)
            staged += entry to current
        }
        seal()
        return batches
    }

    /**
     * Logs a one-time warning the first time an endpoint's count is seen to drop. Reporting the
     * lower value instead of silently dropping it means a future bug that does trigger this stays
     * visible, the same reasoning behind [ProbeRegistry]'s own decrease warning.
     */
    private fun warnOnceAboutDecrease(
        entry: EndpointEntry,
        lastSent: Long,
        current: Long,
    ) {
        if (entry.decreaseWarned) return
        entry.decreaseWarned = true
        log.log(
            Level.WARNING,
            "yukon: endpoint hit count went backward for ${entry.verb} ${entry.routeTemplate} " +
                "(was $lastSent, now $current); reporting it as-is",
        )
    }

    /**
     * Records [snapshot]'s per-entry counts as delivered. Call this only once that batch is
     * confirmed delivered; a failed send must not call it, so the next attempt naturally reports
     * the live count again.
     *
     * Snapshots apply newest-wins per entry, not last-caller-wins: an older snapshot confirmed
     * after a newer one is ignored for that entry, so a confirmed higher count is never rolled
     * back to a stale lower one.
     */
    fun advanceDeltas(snapshot: DeltaSnapshot) {
        for ((entry, count) in snapshot.staged) {
            synchronized(entry) {
                if (snapshot.sequence <= entry.lastAppliedDeltaSequence) return@synchronized
                entry.lastAppliedDeltaSequence = snapshot.sequence
                entry.lastSent = count
            }
        }
    }

    /**
     * Returns chunks of at most [maxPerChunk] entries, counting each endpoint and each disabled
     * module as one, covering every endpoint never delivered or changed since it was last
     * delivered (a discovery-source upgrade, a newly attached handler), plus every disabled
     * module not yet delivered. Returns an empty list when there is nothing to send.
     *
     * Nothing is marked as delivered here. Each returned [ManifestSnapshot] names the version it
     * staged per endpoint, and [advanceManifest] must be called with it explicitly, only once
     * that chunk is confirmed delivered.
     */
    fun computeManifestEntries(maxPerChunk: Int): List<ManifestSnapshot> {
        val chunks = mutableListOf<ManifestSnapshot>()
        var endpoints = mutableListOf<EndpointLocation>()
        var modules = mutableListOf<DisabledEndpointModule>()
        var stagedEndpoints = mutableListOf<Pair<EndpointEntry, Long>>()
        var stagedModules = mutableListOf<String>()

        fun size() = endpoints.size + modules.size

        fun seal() {
            if (size() == 0) return
            chunks += ManifestSnapshot(endpoints, modules, stagedEndpoints, stagedModules)
            endpoints = mutableListOf()
            modules = mutableListOf()
            stagedEndpoints = mutableListOf()
            stagedModules = mutableListOf()
        }

        for (entry in entriesByIdentity.values) {
            val version = entry.version
            if (version <= entry.deliveredVersion) continue
            if (size() == maxPerChunk) seal()
            endpoints += entry.toLocation()
            stagedEndpoints += entry to version
        }
        for ((module, moduleEntry) in disabledModulesByName) {
            if (moduleEntry.manifestIncluded) continue
            if (size() == maxPerChunk) seal()
            modules += DisabledEndpointModule(module, moduleEntry.reason, moduleEntry.disabledAt)
            stagedModules += module
        }
        seal()
        return chunks
    }

    /**
     * Marks every endpoint and disabled module staged by [snapshot] as delivered. An endpoint is
     * marked delivered at the version [snapshot] staged, using `max()` against whatever
     * [EndpointEntry.deliveredVersion] already holds, so a change that lands while this snapshot's
     * send was in flight is still picked up by the next compute rather than being marked as
     * delivered by a send that never carried it.
     *
     * Call this only after that chunk is confirmed delivered. A failed send must not call it, so
     * the next attempt's compute naturally includes the same entries again.
     */
    fun advanceManifest(snapshot: ManifestSnapshot) {
        for ((entry, version) in snapshot.stagedEndpoints) {
            synchronized(entry) {
                if (version > entry.deliveredVersion) entry.deliveredVersion = version
            }
        }
        for (module in snapshot.stagedModules) {
            disabledModulesByName[module]?.manifestIncluded = true
        }
    }

    private fun EndpointEntry.toLocation(): EndpointLocation =
        EndpointLocation(
            endpointId = endpointId,
            verb = verb,
            routeTemplate = routeTemplate,
            verbatimTemplate = verbatimTemplate,
            framework = framework,
            discoverySource = discoverySource,
            handlerClass = handlerClass,
            handlerMethod = handlerMethod,
            handlerDescriptor = handlerDescriptor,
        )
}
