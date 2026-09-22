package io.github.lukedevops.yukon.registry

import io.github.lukedevops.yukon.export.ClassReferences
import io.github.lukedevops.yukon.export.ClassSupertypes
import io.github.lukedevops.yukon.export.DeltaBatch
import io.github.lukedevops.yukon.export.ProbeDelta
import io.github.lukedevops.yukon.export.ProbeLocation
import io.github.lukedevops.yukon.export.ProbeManifest
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.export.SkippedClass
import io.github.lukedevops.yukon.export.UnreportedClass
import java.lang.System.Logger.Level
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Stores one probe-count array per class. Each array is a plain `long[]`,
 * keyed by (class name, probe-layout hash, defining classloader). A probe
 * hit does one direct `arr[index]++`, with no shared map and no atomic
 * operations on the hot path.
 *
 * Counts are approximate under concurrency, and deliberately so. A thread
 * preempted between the load and the store writes back its own stale value
 * plus one, so any number of increments can be lost and a later read can
 * see the count *fall*. What no race can do is put it back to zero: the
 * lowest value any increment ever stores is one, written by a thread that
 * read zero. A probe that ran at least once therefore stays at one or
 * more, and the collector's "never hit" claim, which asks only whether the
 * count is zero, survives what the exact count does not. That is why an
 * unsynchronised array is enough here.
 *
 * Two caveats, neither of which the JVM memory model rules out. It permits
 * a non-volatile `long` to be written and read in two halves (JLS 17.7),
 * and a torn read can then combine an old half with a new one: a count
 * crossing `0xFFFF_FFFF` could read as zero. It also permits a JIT to keep
 * a plain array increment in a register across a loop and store once on
 * exit, so a probe in a loop that never exits could read zero while it
 * runs. Neither happens on 64-bit HotSpot, which makes aligned `long`
 * accesses atomic and does not sink these stores; the guarantee rests on
 * that, not on the specification. There is also no happens-before edge
 * between a hit and the export thread's read, so nothing orders them; a
 * flush is many milliseconds later and reads the write in practice.
 *
 * This type only manages bookkeeping for those arrays: allocation,
 * baseline/delta accounting, and manifest metadata. Instrumented bytecode
 * receives the array at class-init, and writes to it directly from there.
 *
 * The key includes the probe-layout hash, not just the class name. This
 * matters for a class reloaded by the *same* classloader identity with an
 * unchanged layout, which static attach never produces but which is cheap
 * to keep correct. If the layout is unchanged, the class keeps its existing
 * array and history. If the layout changed, old counts would not mean
 * anything against the new bytecode, so the class gets a fresh array
 * instead of a merge.
 *
 * The key also includes the defining classloader's identity, not just the
 * class name. JVM class identity is (classloader, name), not name alone: two
 * different, concurrently active classloaders can define a class with the
 * same fully-qualified name (multi-tenant app servers, OSGi, plugin hosts),
 * and those are two unrelated classes that happen to share a name, not one
 * class reloaded. Keying on name alone would silently merge their hit
 * counts into one manifest entry. Identity is tracked via
 * [System.identityHashCode] rather than holding the [ClassLoader] itself, so
 * a retired classloader (for example, a devtools-style same-JVM reload that
 * swaps in a new classloader for changed classes) can still be garbage
 * collected instead of being pinned forever by this registry.
 *
 * This registry does not, on its own, give a class continuity across an
 * actual app/JVM restart: a fresh [ProbeRegistry] is created every time
 * [io.github.lukedevops.yukon.Agent.premain] runs, so restarting the process
 * always starts every count at zero regardless of this key. Long-running,
 * cross-restart visibility is the collector's job: it aggregates deltas
 * from every `service.instance.id` a service has ever reported, over time,
 * as described in the design notes for the export payloads.
 *
 * @property confirmsDefinitions Switches the whole confirmation mechanism on: [computeManifestDeltas]
 * and [manifest] withhold a class's probe locations and its [ClassSupertypes] and [ClassReferences] records until the
 * class is confirmed defined, by a probe count above zero or a name [confirmFrom] is told the JVM
 * has loaded, and [confirmFrom] itself does the tracking. False leaves all of it inert, so a
 * caller that wires a sweep to a registry that does not withhold cannot be told a class's probes
 * were withheld when they were published. Internal wiring, not an adopter-facing option. See
 * ADR 0028.
 */
open class ProbeRegistry(
    private val confirmsDefinitions: Boolean = false,
) {
    private val log = System.getLogger(ProbeRegistry::class.java.name)

    private data class RegistryKey(
        val className: String,
        val layoutHash: Long,
        val classLoaderId: Int,
    )

    private class ClassEntry(
        val classId: Int,
        val className: String,
        val probes: List<ProbeMeta>,
        val counts: LongArray,
        val superClassName: String?,
        val interfaceNames: List<String>,
        val classLoaderRef: WeakReference<ClassLoader>?,
        val classReferences: List<String>,
    ) {
        /** The last cumulative count successfully delivered to the collector, per probe. */
        var lastSent: LongArray = LongArray(counts.size)

        /** Sequence number of the newest [DeltaSnapshot] applied to [lastSent]; see [advanceBaseline]. */
        var lastAppliedSequence: Long = 0
        val firstSeenAt: LongArray = LongArray(counts.size)

        /** Tracks which probes have already logged the one-time decrease warning. */
        val decreaseWarned: BooleanArray = BooleanArray(counts.size)
        var manifestIncluded: Boolean = false

        /**
         * Set once evidence of definition is seen; see [ProbeRegistry.isConfirmed]. Volatile
         * because [confirmFrom] runs on the flush thread while [computeManifestDeltas] reads this
         * on a send-pool thread.
         */
        @Volatile
        var confirmed: Boolean = false

        /** [confirmFrom] calls in which this class was neither confirmed nor collected. */
        var missedConfirmations: Int = 0

        /** Set once [missedConfirmations] reaches the terminal count; never confirmed after this. */
        @Volatile
        var withheldForGood: Boolean = false
    }

    private class SkippedEntry(
        val reason: String,
        val skippedAt: Long,
    ) {
        var manifestIncluded: Boolean = false
    }

    private class UnreportedEntry(
        val firstSeenUnreportedAt: Long,
    ) {
        var manifestIncluded: Boolean = false
    }

    /**
     * One computed delta batch, together with the exact per-class snapshots it was built from.
     *
     * [advanceBaseline] takes this back, rather than reading staging state off the registry. Two
     * flushes can then be in flight at once (a scheduled one and the shutdown flush) without one
     * marking the other's hits as delivered.
     */
    class DeltaSnapshot internal constructor(
        val batch: DeltaBatch,
        internal val sequence: Long,
        internal val staged: List<Pair<Any, LongArray>>,
    )

    /**
     * One computed manifest delta, together with the classes it staged. [advanceManifestBaseline]
     * marks exactly those as included, and nothing that registered after this snapshot was taken.
     */
    class ManifestSnapshot internal constructor(
        val manifest: ProbeManifest,
        internal val stagedEntries: List<Any>,
        internal val stagedSkipped: List<Any>,
        internal val stagedUnreported: List<Any>,
    )

    private val entriesByKey = ConcurrentHashMap<RegistryKey, ClassEntry>()
    private val skippedByClassName = ConcurrentHashMap<String, SkippedEntry>()
    private val unreportedByClassName = ConcurrentHashMap<String, UnreportedEntry>()
    private val nothingToProbeClassNames = ConcurrentHashMap.newKeySet<String>()
    private val nextClassId = AtomicInteger(0)
    private val nextSnapshotSequence = AtomicLong(0)

    /**
     * Called once per class transform. Returns the backing array every probe
     * in this class increments. A repeat call for an unchanged (className,
     * layoutHash, classLoader) returns the same array instance.
     *
     * [superClassName] and [interfaceNames] are the class's supertypes, dotted, read from its
     * class header. They travel with the class on the manifest as a [ClassSupertypes] record so a
     * collector can widen a virtual [io.github.lukedevops.yukon.export.CallEdge] to every override
     * it knows about. See ADR 0024. They play no part in the registry key or the probe-layout
     * hash: a class's supertypes changing what a call resolves to at the collector never changes
     * which array slot a probe hit increments.
     *
     * [classReferences] are the class's own out-of-scope references outside any probed method,
     * dotted (ADR 0030). They travel as one [ClassReferences] record, staged, withheld and committed
     * with the class exactly as its supertypes are, and like them play no part in the key or the
     * hash.
     *
     * `open` only so a test can observe what gets committed, which is how the
     * transform-failure path is pinned.
     */
    open fun register(
        className: String,
        layoutHash: Long,
        probes: List<ProbeMeta>,
        classLoader: ClassLoader? = null,
        superClassName: String? = null,
        interfaceNames: List<String> = emptyList(),
        classReferences: List<String> = emptyList(),
    ): LongArray {
        val key = RegistryKey(className, layoutHash, System.identityHashCode(classLoader))
        val entry =
            entriesByKey.computeIfAbsent(key) {
                ClassEntry(
                    classId = nextClassId.getAndIncrement(),
                    className = className,
                    probes = probes,
                    counts = LongArray(probes.size),
                    superClassName = superClassName,
                    interfaceNames = interfaceNames,
                    classLoaderRef = weakClassLoaderRef(classLoader),
                    classReferences = classReferences,
                )
            }
        return entry.counts
    }

    /**
     * Wraps [classLoader] for the entry to hold, so a collected loader can later be told apart
     * from live evidence in [confirmFrom]. Null in, null out: a null classloader means the
     * bootstrap loader, which is never collected, so there is nothing to weakly reference.
     *
     * `open` only so a test can substitute an already-cleared reference, rather than registering
     * against a throwaway loader and waiting on the garbage collector.
     */
    internal open fun weakClassLoaderRef(classLoader: ClassLoader?): WeakReference<ClassLoader>? = classLoader?.let { WeakReference(it) }

    /**
     * The array [register] handed out for this exact (className, layoutHash, classLoader), or null
     * if none is registered. This is what an instrumented class's woven `<clinit>` calls, through
     * the bootstrap holder, to pick up the array its probes then increment directly.
     */
    fun lookup(
        className: String,
        layoutHash: Long,
        classLoader: ClassLoader?,
    ): LongArray? = entriesByKey[RegistryKey(className, layoutHash, System.identityHashCode(classLoader))]?.counts

    /** Names of every class currently registered, across all classloaders. */
    fun registeredClassNames(): Set<String> = entriesByKey.keys.mapTo(HashSet()) { it.className }

    /**
     * Of [candidates], the names this registry has never heard of: not registered, not recorded
     * as skipped, and not recorded as having nothing to probe. That remainder is the definition
     * of an unreported class. See ADR 0027.
     *
     * Takes the whole candidate set rather than answering one name at a time, so the registered
     * names are collected once per sweep instead of once per loaded class. A membership test per
     * class against a live scan of the key set would cost the product of the two.
     */
    fun unaccountedFrom(candidates: Collection<String>): List<String> {
        val registered = registeredClassNames()
        return candidates.filter {
            it !in registered && !skippedByClassName.containsKey(it) && it !in nothingToProbeClassNames
        }
    }

    /**
     * Records a class the agent matched and looked at, and found nothing in to probe: no method
     * the method matcher admits, no default-argument site, no type initializer of its own. A
     * marker interface or a constants holder is the ordinary case.
     *
     * Local only. It never reaches the wire, because nothing claims anything about such a class:
     * the static scanner puts its own copy in the baseline's unprobed bucket rather than
     * declaring it, so no "never loaded" claim can name it. This exists so the sweep can tell a
     * class with nothing to probe apart from one that went unreported for a reason the agent
     * cannot see, which is the whole value of the count the sweep produces.
     */
    fun recordNothingToProbe(className: String) {
        nothingToProbeClassNames += className
    }

    /**
     * Records a class the sweep found loaded but unreported, keeping the time it was first found.
     *
     * Idempotent per class name, like [recordSkipped]: a later sweep finds the same class again
     * and must not restate when the blind spot started. Returns true the first time, so a caller
     * can count what each sweep newly found.
     */
    fun recordUnreported(className: String): Boolean {
        var added = false
        unreportedByClassName.computeIfAbsent(className) {
            added = true
            UnreportedEntry(System.currentTimeMillis())
        }
        return added
    }

    /**
     * Drops any class from the unreported set that has since become accounted for, and returns
     * how many went.
     *
     * Subtraction is by name, and a name can be held by more than one classloader. One loader's
     * copy can be deflected and recorded here while another's registers normally a moment later,
     * which would otherwise leave the same manifest carrying that name as both a probed class and
     * an unreported one, with no rule for which wins. A sweep calls this before it looks for new
     * ones.
     *
     * A row already delivered cannot be taken back: there is no tombstone on the wire, and a
     * collector counting the class as loaded is right either way, since it did load. What this
     * fixes is the contradiction and the count.
     */
    fun purgeAccountedFor(): Int {
        val names = unreportedByClassName.keys.toList()
        if (names.isEmpty()) return 0
        val stillUnaccounted = unaccountedFrom(names).toSet()
        var removed = 0
        for (name in names) {
            if (name !in stillUnaccounted && unreportedByClassName.remove(name) != null) removed++
        }
        return removed
    }

    /** How many classes the sweep has found unreported so far; for tests and logging. */
    fun unreportedClassCount(): Int = unreportedByClassName.size

    /**
     * Whether [entry] has evidence of having been defined: a probe count above zero, memoised
     * once seen so later calls do not rescan the array, or an earlier confirmation recorded by
     * [confirmFrom].
     */
    private fun isConfirmed(entry: ClassEntry): Boolean {
        if (entry.confirmed) return true
        if (entry.counts.any { it > 0L }) {
            entry.confirmed = true
        }
        return entry.confirmed
    }

    /**
     * Reconciles every class not yet confirmed defined against [loadedClassNames], the JVM's own
     * loaded-class names. A class is confirmed here when its name appears in that set, or when its
     * classloader has since been collected: a collected loader is the absence of evidence rather
     * than evidence of absence, since a loader created, used and collected inside one flush
     * interval is ordinary for a script engine or a per-test loader. A class registered with a
     * null classloader (the bootstrap loader) has no reference to collect and so is never
     * confirmed by that rule.
     *
     * A class found in neither has one more miss recorded against it. Two misses withhold it for
     * good: its name is returned, once, the first time that happens, and never returned again on
     * a later call. Nothing ever un-confirms a class, so a confirmed one leaves this loop at the
     * guard above and its miss count is never read again.
     *
     * A class already confirmed, or already withheld for good, is left alone. A registry that
     * does not withhold tracks nothing and returns nothing: the names this returns are logged as
     * having had their probes withheld, which would not be true.
     *
     * See ADR 0028.
     */
    open fun confirmFrom(loadedClassNames: Set<String>): List<String> {
        if (!confirmsDefinitions) return emptyList()
        val newlyWithheld = mutableListOf<String>()
        for (entry in entriesByKey.values) {
            if (entry.withheldForGood || isConfirmed(entry)) continue
            val loaderCollected = entry.classLoaderRef != null && entry.classLoaderRef.get() == null
            if (entry.className in loadedClassNames || loaderCollected) {
                entry.confirmed = true
                continue
            }
            entry.missedConfirmations++
            if (entry.missedConfirmations >= 2) {
                entry.withheldForGood = true
                newlyWithheld += entry.className
            }
        }
        return newlyWithheld
    }

    /**
     * How many registered classes are not yet confirmed defined and not withheld for good. Zero
     * for a registry that does not withhold, which has nothing to confirm.
     */
    fun unconfirmedClassCount(): Int =
        if (!confirmsDefinitions) 0 else entriesByKey.values.count { !it.withheldForGood && !isConfirmed(it) }

    /** How many registered classes were never confirmed defined and are withheld for good. */
    fun withheldForGoodClassCount(): Int = entriesByKey.values.count { it.withheldForGood }

    /**
     * Records a class the agent matched but could not instrument. This makes it visible on the
     * wire, not just in an agent-local log line. The class never gets a `classId` or any probes,
     * because it never reaches [register].
     *
     * Idempotent per class name. A repeat call, for example the same class loaded by a second
     * classloader, keeps the first reason and timestamp. It does not overwrite them.
     */
    fun recordSkipped(
        className: String,
        reason: String,
    ) {
        skippedByClassName.computeIfAbsent(className) {
            SkippedEntry(reason, System.currentTimeMillis())
        }
    }

    /**
     * Compares current counts against each entry's last successfully sent value, and returns
     * only the probes whose count changed.
     *
     * Each [ProbeDelta] carries the current cumulative count (`hits_total`), not the amount it
     * changed by. A collector merges cumulative counts with max(), so a batch that arrives twice
     * or out of order cannot double-count. This differs from an operation like "add 5 hits":
     * applying that twice, or out of order against a concurrent update, corrupts the total.
     *
     * Nothing is marked as sent here. The returned [DeltaSnapshot] carries the per-class
     * snapshots it was built from, and [advanceBaseline] must be called with it explicitly, only
     * after the batch is confirmed delivered.
     *
     * This is the single-batch form of [computeDeltaBatches], with no size cap.
     */
    open fun computeDeltaBatch(resource: ResourceAttributes): DeltaSnapshot = computeDeltaBatches(resource, Int.MAX_VALUE).single()

    /**
     * Like [computeDeltaBatch], but splits the changed probes into batches of at most
     * [maxDeltasPerBatch] each, so a burst of activity (a busy startup, a long collector outage
     * ending) never produces one unbounded POST.
     *
     * Classes are never split across batches: each [DeltaSnapshot] stages whole classes, so
     * [advanceBaseline] on one batch marks exactly that batch's classes as delivered and nothing
     * else. A single class whose changed probes alone exceed the cap gets a batch of its own,
     * larger than the cap. Classes with nothing changed are staged nowhere, since there is
     * nothing to advance for them.
     *
     * Always returns at least one batch. An empty one is the liveness heartbeat described in
     * [io.github.lukedevops.yukon.export.ExportScheduler.flush].
     */
    open fun computeDeltaBatches(
        resource: ResourceAttributes,
        maxDeltasPerBatch: Int,
    ): List<DeltaSnapshot> {
        val batches = mutableListOf<DeltaSnapshot>()
        var deltas = mutableListOf<ProbeDelta>()
        var staged = mutableListOf<Pair<Any, LongArray>>()

        fun seal() {
            batches += DeltaSnapshot(DeltaBatch(resource, deltas), nextSnapshotSequence.incrementAndGet(), staged)
            deltas = mutableListOf()
            staged = mutableListOf()
        }
        for (entry in entriesByKey.values) {
            val snapshot = entry.counts.copyOf()
            val entryDeltas = changedProbesOf(entry, snapshot)
            if (entryDeltas.isEmpty()) continue
            if (deltas.isNotEmpty() && deltas.size + entryDeltas.size > maxDeltasPerBatch) seal()
            deltas += entryDeltas
            staged += entry to snapshot
        }
        if (deltas.isNotEmpty() || batches.isEmpty()) seal()
        return batches
    }

    private fun changedProbesOf(
        entry: ClassEntry,
        snapshot: LongArray,
    ): List<ProbeDelta> {
        val deltas = mutableListOf<ProbeDelta>()
        for (index in snapshot.indices) {
            val current = snapshot[index]
            val lastSent = entry.lastSent[index]
            if (current == lastSent) continue
            if (current < lastSent) {
                warnOnceAboutDecrease(entry, index, lastSent, current)
            }
            if (current > 0 && entry.firstSeenAt[index] == 0L) {
                entry.firstSeenAt[index] = System.currentTimeMillis()
            }
            deltas +=
                ProbeDelta(
                    classId = entry.classId,
                    probeIndex = index,
                    kind = entry.probes[index].kind,
                    firstSeenAt = entry.firstSeenAt[index],
                    hitsTotal = current,
                )
        }
        return deltas
    }

    /**
     * Logs a one-time warning the first time a probe's count is seen to drop.
     *
     * A drop on a hot probe is expected, not a bug: a stale increment overwrites whatever landed
     * while the writer was preempted, as the class-level doc describes. How much it loses is not
     * bounded, since it depends on how long that writer was away, so the size of a drop says
     * little on its own. A drop on a probe with almost no traffic is the one worth looking at.
     * Registration is the only path that hands out a new, lower-starting array, and it takes a
     * changed probe layout hash, which static attach cannot produce since it never retransforms a
     * loaded class. Logging instead of silently sending the lower value means a bug that does
     * reach here is visible rather than hidden.
     *
     * The lower value goes out as it is. A collector that reads a falling total as a restarted
     * instance adds the whole post-drop total to what it already held, so one lost update
     * overcounts that probe from then on rather than undercounting it. The total is wrong either
     * way; what it cannot do is turn a hit probe into an unhit one, which is the only thing a
     * dead-code claim rests on.
     */
    private fun warnOnceAboutDecrease(
        entry: ClassEntry,
        index: Int,
        lastSent: Long,
        current: Long,
    ) {
        if (entry.decreaseWarned[index]) return
        entry.decreaseWarned[index] = true
        log.log(
            Level.WARNING,
            "yukon: probe count went backward for ${entry.className}#$index " +
                "(was $lastSent, now $current); reporting it as-is",
        )
    }

    /**
     * Records [snapshot]'s per-class counts as delivered. It never advances to the live counts:
     * those may have moved further ahead, for example while a POST was in flight.
     *
     * Call this only after that snapshot's batch is confirmed delivered. A failed flush must not
     * call it, so the next attempt naturally reports the live count again.
     *
     * Snapshots are applied newest-wins per class, not last-caller-wins. If a newer snapshot has
     * already been applied to a class, an older one arriving late (its send was confirmed after
     * the newer one's) is ignored for that class, so a confirmed higher count is never rolled
     * back to a stale lower one. Applying the older one first and the newer one second is the
     * ordinary case and works as expected.
     */
    fun advanceBaseline(snapshot: DeltaSnapshot) {
        for ((entryRef, counts) in snapshot.staged) {
            val entry = entryRef as ClassEntry
            synchronized(entry) {
                if (snapshot.sequence <= entry.lastAppliedSequence) return@synchronized
                entry.lastAppliedSequence = snapshot.sequence
                entry.lastSent = counts
            }
        }
    }

    /**
     * Sent once per (service, version) so the collector can resolve probe IDs to source.
     *
     * [resource] names the instance and run the manifest's `class_id` values belong to: this
     * registry assigns them in its own load order, so a collector must key on both to avoid
     * conflating two processes' unrelated classes that share a `class_id`. See the class-level doc
     * on [io.github.lukedevops.yukon.export.ProbeManifest].
     */
    fun manifest(resource: ResourceAttributes): ProbeManifest {
        // One filtered list feeds both the locations and the supertype records, so a withheld
        // class cannot appear in one and not the other.
        val published = entriesByKey.values.filter { !confirmsDefinitions || isConfirmed(it) }
        val locations =
            published.flatMap { entry ->
                entry.probes.mapIndexed { index, meta ->
                    ProbeLocation(
                        classId = entry.classId,
                        probeIndex = index,
                        kind = meta.kind,
                        className = entry.className,
                        methodName = meta.methodName,
                        methodDescriptor = meta.methodDescriptor,
                        line = meta.line,
                        branchIndex = meta.branchIndex,
                        inline = meta.inline,
                        parameterIndex = meta.parameterIndex,
                        parameterName = meta.parameterName,
                        overridable = meta.overridable,
                        targetClassName = meta.targetClassName,
                        calls = meta.calls,
                        inlinedFromClassName = meta.inlinedFromClassName,
                        generatedBy = meta.generatedBy,
                        referencedClasses = meta.referencedClasses,
                        branchKey = meta.branchKey,
                    )
                }
            }
        val skipped =
            skippedByClassName.map { (className, entry) ->
                SkippedClass(className, entry.reason, entry.skippedAt)
            }
        val supertypes = published.map { entry -> ClassSupertypes(entry.classId, entry.superClassName, entry.interfaceNames) }
        val classReferences = published.mapNotNull(::classReferencesOf)
        val unreported =
            unreportedByClassName.map { (className, entry) ->
                UnreportedClass(className, entry.firstSeenUnreportedAt)
            }
        return ProbeManifest(
            resource,
            locations,
            skipped,
            classSupertypes = supertypes,
            unreportedClasses = unreported,
            classReferences = classReferences,
        )
    }

    /** [entry]'s [ClassReferences] record, or null when it has no class-level references. */
    private fun classReferencesOf(entry: ClassEntry): ClassReferences? =
        entry.classReferences.takeIf { it.isNotEmpty() }?.let { ClassReferences(entry.classId, it) }

    /**
     * Returns only the probe locations for classes not yet included in a successfully sent
     * manifest. Nothing is marked as included here: the returned [ManifestSnapshot] names the
     * classes it staged, and [advanceManifestBaseline] must be called with it explicitly, only
     * once the manifest is confirmed delivered.
     *
     * A class that registers after an earlier successful send is picked up on a later call, not
     * left out of every manifest for the rest of the process's life.
     *
     * This is the single-chunk form of [computeManifestDeltas], with no size cap. Unlike that
     * method it always returns a snapshot, possibly with nothing in it.
     */
    open fun computeManifestDelta(resource: ResourceAttributes): ManifestSnapshot =
        computeManifestDeltas(resource, Int.MAX_VALUE).singleOrNull()
            ?: ManifestSnapshot(
                ProbeManifest(resource, emptyList()),
                emptyList(),
                emptyList(),
                emptyList(),
            )

    /**
     * Like [computeManifestDelta], but splits the not-yet-sent classes into chunks of at most
     * [maxEntriesPerChunk] entries each. A skipped class counts as one entry. A registered class
     * counts as its probe locations, plus its probes' total call-edge and referenced-class count,
     * plus one for its own [ClassSupertypes] record, plus the names in its [ClassReferences]
     * record, since all of it is staged and committed together. The first
     * manifest after a busy startup can otherwise carry every probe in the app in one POST.
     *
     * Classes are never split across chunks, so [advanceManifestBaseline] on one chunk marks
     * exactly that chunk's classes as included. A single class with more locations than the cap
     * gets a chunk of its own. Returns an empty list when there is nothing to send.
     */
    open fun computeManifestDeltas(
        resource: ResourceAttributes,
        maxEntriesPerChunk: Int,
    ): List<ManifestSnapshot> {
        val chunks = mutableListOf<ManifestSnapshot>()
        var locations = mutableListOf<ProbeLocation>()
        var skipped = mutableListOf<SkippedClass>()
        var supertypes = mutableListOf<ClassSupertypes>()
        var classReferences = mutableListOf<ClassReferences>()
        var stagedEntries = mutableListOf<Any>()
        var stagedSkipped = mutableListOf<Any>()
        var unreported = mutableListOf<UnreportedClass>()
        var stagedUnreported = mutableListOf<Any>()

        // The running chunk weight is tracked explicitly rather than derived from the staged
        // lists' sizes: a class's call edges add to its weight but never become list entries of
        // their own, since each edge nests inside its own ProbeLocation.calls rather than sitting
        // beside it. Deriving the cap check from list sizes alone would silently ignore that
        // weight the moment a chunk already held an earlier class's edges.
        var chunkWeight = 0

        fun seal() {
            chunks +=
                ManifestSnapshot(
                    ProbeManifest(
                        resource,
                        locations,
                        skipped,
                        classSupertypes = supertypes,
                        unreportedClasses = unreported,
                        classReferences = classReferences,
                    ),
                    stagedEntries,
                    stagedSkipped,
                    stagedUnreported,
                )
            locations = mutableListOf()
            skipped = mutableListOf()
            supertypes = mutableListOf()
            classReferences = mutableListOf()
            stagedEntries = mutableListOf()
            stagedSkipped = mutableListOf()
            unreported = mutableListOf()
            stagedUnreported = mutableListOf()
            chunkWeight = 0
        }
        for (entry in entriesByKey.values) {
            if (entry.manifestIncluded) continue
            if (confirmsDefinitions && !isConfirmed(entry)) continue
            // A class's weight is its probe count, plus its total call-edge and referenced-class
            // count, plus one for its own ClassSupertypes record, plus its class-level references:
            // all of it is staged and committed together, so a class with many edges or references
            // seals a chunk earlier than one without.
            val weight =
                entry.probes.size + entry.probes.sumOf { it.calls.size + it.referencedClasses.size } + 1 +
                    entry.classReferences.size
            if (chunkWeight > 0 && chunkWeight + weight > maxEntriesPerChunk) seal()
            stagedEntries += entry
            entry.probes.forEachIndexed { index, meta ->
                locations +=
                    ProbeLocation(
                        classId = entry.classId,
                        probeIndex = index,
                        kind = meta.kind,
                        className = entry.className,
                        methodName = meta.methodName,
                        methodDescriptor = meta.methodDescriptor,
                        line = meta.line,
                        branchIndex = meta.branchIndex,
                        inline = meta.inline,
                        parameterIndex = meta.parameterIndex,
                        parameterName = meta.parameterName,
                        overridable = meta.overridable,
                        targetClassName = meta.targetClassName,
                        calls = meta.calls,
                        inlinedFromClassName = meta.inlinedFromClassName,
                        generatedBy = meta.generatedBy,
                        referencedClasses = meta.referencedClasses,
                        branchKey = meta.branchKey,
                    )
            }
            supertypes += ClassSupertypes(entry.classId, entry.superClassName, entry.interfaceNames)
            classReferencesOf(entry)?.let { classReferences += it }
            chunkWeight += weight
        }
        for ((className, entry) in skippedByClassName) {
            if (entry.manifestIncluded) continue
            if (chunkWeight > 0 && chunkWeight + 1 > maxEntriesPerChunk) seal()
            stagedSkipped += entry
            skipped += SkippedClass(className, entry.reason, entry.skippedAt)
            chunkWeight += 1
        }
        // One entry each, the same weight a skipped class carries: both are a class name and a
        // couple of scalars on the wire.
        for ((className, entry) in unreportedByClassName) {
            if (entry.manifestIncluded) continue
            if (chunkWeight > 0 && chunkWeight + 1 > maxEntriesPerChunk) seal()
            stagedUnreported += entry
            unreported += UnreportedClass(className, entry.firstSeenUnreportedAt)
            chunkWeight += 1
        }
        if (chunkWeight > 0) seal()
        return chunks
    }

    /**
     * Marks every class staged by [snapshot] as included, so it is not sent again. Classes that
     * registered after that snapshot was computed are untouched, even if another, newer snapshot
     * has staged them in the meantime.
     *
     * Call this only after that manifest is confirmed delivered. A failed send must not call it,
     * so the next attempt's delta naturally includes the same classes again.
     */
    fun advanceManifestBaseline(snapshot: ManifestSnapshot) {
        for (entry in snapshot.stagedEntries) (entry as ClassEntry).manifestIncluded = true
        for (entry in snapshot.stagedSkipped) (entry as SkippedEntry).manifestIncluded = true
        for (entry in snapshot.stagedUnreported) (entry as UnreportedEntry).manifestIncluded = true
    }
}
