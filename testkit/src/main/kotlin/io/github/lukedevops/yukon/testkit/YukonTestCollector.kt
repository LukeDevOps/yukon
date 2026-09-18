package io.github.lukedevops.yukon.testkit

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.lukedevops.yukon.export.CallEdge
import io.github.lukedevops.yukon.export.DisabledEndpointModule
import io.github.lukedevops.yukon.export.EndpointDiscoverySource
import io.github.lukedevops.yukon.export.GeneratedBy
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ProtoPayloadCodec
import io.github.lukedevops.yukon.export.SkippedClass
import io.github.lukedevops.yukon.registry.RouteTemplateNormalizer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * An embeddable collector that speaks the same wire protocol the Yukon agent sends: delta
 * batches, probe manifests, and static baselines, all protobuf over plain HTTP. An adopter's test
 * runs the real agent against [endpoint] and then asks this collector what it saw, instead of
 * querying any in-process registry.
 *
 * Every class name in this API is the dotted binary name the manifest carries: for example
 * `com.acme.OrderService`, `com.acme.OrdersKt` for a Kotlin file's top-level functions, or
 * `com.acme.Outer$Inner` for a nested class.
 *
 * A probe query for a class or method this collector has never heard of throws
 * [UnknownProbeException] rather than reading as "confirmed never hit". Collapsing "genuinely
 * dead" and "we have no idea" into the same `false` would be exactly the silent, confident, wrong
 * failure mode the rest of this agent is built to avoid.
 *
 * Endpoint queries ([wasCalled], [callCount], [neverCalled], [endpoints]) follow the same rule,
 * throwing [UnknownEndpointException] for a `(verb, route template)` no manifest has mentioned.
 * An endpoint's identity is the pair alone, never `endpoint_id`: `endpoint_id` is assigned
 * independently by each instance's own registry, so an endpoint registered by several instances
 * merges into one [EndpointRef] whose call count sums every instance's latest total, the same
 * cross-instance aggregation [hitCount] already does for method probes by class and method name.
 *
 * Close this with [close], typically from a `.use { }` block, once a test is done with it.
 */
class YukonTestCollector private constructor(
    private val server: HttpServer,
    private val executor: ExecutorService,
) : AutoCloseable {
    private data class ProbeKey(
        val serviceInstanceId: String,
        val classId: Int,
        val probeIndex: Int,
    )

    private data class StoredProbe(
        val className: String,
        val methodName: String,
        val methodDescriptor: String,
        val line: Int,
        val kind: ProbeKind,
        val branchIndex: Int?,
        val inline: Boolean,
        val parameterIndex: Int? = null,
        val parameterName: String? = null,
        val overridable: Boolean = false,
        val targetClassName: String? = null,
        val calls: List<CallEdge> = emptyList(),
        val inlinedFromClassName: String? = null,
        val generatedBy: GeneratedBy = GeneratedBy.NONE,
    )

    /** A class's superclass and direct interfaces, resolved to a class name. See ADR 0024. */
    private data class SupertypesInfo(
        val superClassName: String?,
        val interfaceNames: List<String>,
    )

    /** One declared method read from a complete static baseline scan. See ADR 0024. */
    private data class DeclaredMethodInfo(
        val methodName: String,
        val methodDescriptor: String,
        val inline: Boolean,
        val calls: List<CallEdge>,
        val generatedBy: GeneratedBy = GeneratedBy.NONE,
    )

    /**
     * A declared class's methods and supertypes, read from a complete static baseline scan.
     * [serviceInstanceId] names whichever instance's scan produced this record, used to label a
     * never-loaded member's [ProbeRef.serviceInstanceId].
     */
    private data class DeclaredClassInfo(
        val serviceInstanceId: String,
        val methods: List<DeclaredMethodInfo>,
        val superClassName: String?,
        val interfaceNames: List<String>,
    )

    /** One node of the call graph [unreachedClusters] resolves: a probed method, by identity alone. */
    private data class NodeKey(
        val className: String,
        val methodName: String,
        val methodDescriptor: String,
    )

    /**
     * A [NodeKey]'s reporting fields and raw, unresolved outgoing call edges. [neverLoaded] marks a
     * node that exists only because a complete static baseline declared it; such a node has
     * [hits] fixed at zero, since the dynamic tier never registered its class at all.
     */
    private data class NodeInfo(
        val serviceInstanceId: String,
        val line: Int,
        val neverLoaded: Boolean,
        val hits: Long,
        val edges: Set<CallEdge>,
    )

    /** The resolved call graph: every node, its resolved outgoing edges, and the reverse (caller) index. */
    private class CallGraph(
        val nodes: Map<NodeKey, NodeInfo>,
        val resolvedEdges: Map<NodeKey, Set<NodeKey>>,
        val callersOf: Map<NodeKey, Set<NodeKey>>,
    )

    private data class ScanKey(
        val serviceInstanceId: String,
        val scannedAt: Long,
    )

    /**
     * Groups every omission probe naming one optional parameter, within one instance: see
     * [optionalParameterFindings]. [targetClassName] is the effective target class, already
     * resolved with `targetClassName ?: className`, not the raw wire value.
     */
    private data class OmissionTargetKey(
        val serviceInstanceId: String,
        val targetClassName: String,
        val methodName: String,
        val methodDescriptor: String,
        val parameterIndex: Int?,
    )

    /** Cross-instance endpoint identity: the pair alone, never `endpoint_id`. See the class KDoc. */
    private data class EndpointIdentity(
        val verb: String,
        val routeTemplate: String,
    )

    private data class InstanceEndpointKey(
        val serviceInstanceId: String,
        val endpointId: Int,
    )

    /** Accumulates one static baseline scan's chunks. Only merged into [consultedDeclaredNames] once complete. */
    private class ScanProgress(
        val chunkCount: Int,
    ) {
        val received: MutableSet<Int> = ConcurrentHashMap.newKeySet()
        val declaredNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /**
         * Declared classes whose every declared method is inline or generated; see
         * [YukonTestCollector.neverLoaded].
         */
        val allInlineOrGeneratedNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Methods and supertypes per declared class; only merged into [consultedDeclaredClasses] once complete. */
        val declaredClasses: MutableMap<String, DeclaredClassInfo> = ConcurrentHashMap()
        val complete: Boolean get() = received.size >= chunkCount
    }

    private val lock = ReentrantLock()
    private val condition = lock.newCondition()
    private val deltaBatchSeq = AtomicLong(0)

    /** Orders a [ProbeRef] by class name, method name, line, then branch index, the same order [neverHit] sorts by. */
    private val probeRefComparator: Comparator<ProbeRef> =
        compareBy({ it.className }, { it.methodName }, { it.line }, {
            it.branchIndex
                ?: -1
        })

    private val probesByKey = ConcurrentHashMap<ProbeKey, StoredProbe>()
    private val nameIndex = ConcurrentHashMap<String, MutableSet<ProbeKey>>()

    /**
     * Omission probes indexed by their target's class, `targetClassName ?: className`, rather than
     * by the probe's own declared class. A Scala constructor default getter's own class is the
     * companion module (`Cc$`), but a query names the constructor's own class (`Cc`); see ADR 0023.
     */
    private val omissionTargetIndex = ConcurrentHashMap<String, MutableSet<ProbeKey>>()
    private val hitsByKey = ConcurrentHashMap<ProbeKey, Long>()
    private val skippedByClassName = ConcurrentHashMap<String, SkippedClass>()

    /** A class's supertypes, by name, from any manifest. Populated alongside its probes; see [handleManifest]. */
    private val supertypesByClassName = ConcurrentHashMap<String, SupertypesInfo>()

    /** Declared classes from every complete static baseline scan, by name. See [handleStaticBaseline]. */
    private val consultedDeclaredClasses = ConcurrentHashMap<String, DeclaredClassInfo>()

    /** Every class name any manifest has ever mentioned, whether it got probes or was only reported as skipped. */
    private val dynamicallyKnownClassNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Instance ids whose shutdown hook has sent a delta batch with `final_flush` set. See [endedCleanly]. */
    private val instancesThatEndedCleanly: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val scans = ConcurrentHashMap<ScanKey, ScanProgress>()
    private val completedScans: MutableSet<ScanKey> = ConcurrentHashMap.newKeySet()
    private val consultedDeclaredNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Declared classes, from a complete scan, whose every declared method is inline or generated. */
    private val consultedAllInlineOrGeneratedNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Latest delivered record per endpoint identity, across every instance; see [handleManifest]. */
    private val endpointRefsByIdentity = ConcurrentHashMap<EndpointIdentity, EndpointRef>()

    /** Every `(instance, endpoint_id)` ever reported for an identity, so [callCount] can sum each instance's latest total. */
    private val endpointKeysByIdentity = ConcurrentHashMap<EndpointIdentity, MutableSet<InstanceEndpointKey>>()
    private val endpointHitsByKey = ConcurrentHashMap<InstanceEndpointKey, Long>()
    private val disabledEndpointModulesByName = ConcurrentHashMap<String, DisabledEndpointModule>()

    /** Base URL to pass as an agent's `endpoint=` option, for example `http://localhost:54321`. */
    val endpoint: String = "http://localhost:${server.address.port}"

    /**
     * Blocks until a delta batch arrives that was received after this call began, including an
     * empty one: the agent sends a delta batch on every flush tick, even when nothing changed, as
     * a liveness heartbeat. Throws [TimeoutException] if [timeout] elapses first.
     */
    fun awaitNextFlush(timeout: Duration) {
        val start = deltaBatchSeq.get()
        awaitUntil(timeout, "no delta batch arrived within $timeout") { deltaBatchSeq.get() > start }
    }

    /**
     * Blocks until two delta batches, from any instance, have arrived after this call began.
     * Waiting for two, not one, closes two gaps a single [awaitNextFlush] leaves open: a flush
     * can already be mid-compute when the caller's last action completes, so it can land without
     * that action's hits, and the same flush tick sends its manifest and its delta batch
     * concurrently, so a probe a caller just triggered for the first time may not have a manifest
     * entry yet even once its delta batch arrives. A second batch means at least one full tick
     * started after the action, and gives that tick's concurrent manifest send a whole interval
     * to land. A test that needs the manifest entry itself, not just the count, can still call
     * [awaitProbe] or [awaitEndpoint] for that exact guarantee.
     *
     * Throws [TimeoutException] if [timeout] elapses before two batches arrive.
     */
    fun awaitSettled(timeout: Duration) {
        val start = deltaBatchSeq.get()
        awaitUntil(timeout, "fewer than two delta batches arrived within $timeout") { deltaBatchSeq.get() >= start + 2 }
    }

    /**
     * Blocks until some manifest, from any instance, has mentioned a probe for [className] and
     * [methodName]. A manifest and a delta batch from the same flush tick are sent concurrently by
     * the agent, so a newly loaded class's manifest entry can land after that tick's delta batch;
     * this lets a test wait for the manifest specifically. Throws [TimeoutException] if [timeout]
     * elapses first.
     */
    fun awaitProbe(
        className: String,
        methodName: String,
        timeout: Duration,
    ) {
        awaitUntil(timeout, "no manifest ever mentioned $className#$methodName within $timeout") {
            nameIndex[className]?.any { probesByKey[it]?.methodName == methodName } == true
        }
    }

    private fun awaitUntil(
        timeout: Duration,
        timeoutMessage: String,
        predicate: () -> Boolean,
    ) {
        val deadlineNanos = System.nanoTime() + timeout.toNanos()
        lock.withLock {
            while (!predicate()) {
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0) throw TimeoutException(timeoutMessage)
                condition.awaitNanos(remaining)
            }
        }
    }

    /**
     * True if any instance ever reported a nonzero `hits_total` for a METHOD-kind probe matching
     * [className] and [methodName]. `methodDescriptor` left null matches any overload.
     *
     * Throws [UnknownProbeException] if no such probe was ever declared; see that type's doc for
     * the four cases it distinguishes.
     */
    fun wasHit(
        className: String,
        methodName: String,
        methodDescriptor: String? = null,
    ): Boolean = findMethodProbes(className, methodName, methodDescriptor).any { (hitsByKey[it] ?: 0L) > 0L }

    /**
     * Sums, over every matching METHOD-kind probe, its latest known `hits_total` merged across
     * instances with max(). `methodDescriptor` left null sums every overload; given, it isolates
     * one.
     *
     * Throws [UnknownProbeException] if no such probe was ever declared.
     */
    fun hitCount(
        className: String,
        methodName: String,
        methodDescriptor: String? = null,
    ): Long = findMethodProbes(className, methodName, methodDescriptor).sumOf { hitsByKey[it] ?: 0L }

    /**
     * Sums, over the optional parameter at [parameterIndex] of [methodName], every omission any
     * instance ever reported, merged across instances with max() like [hitCount].
     * `methodDescriptor` left null matches any overload declaring an optional parameter at that
     * index; given, it isolates one.
     *
     * Throws [UnknownProbeException] if no such omission probe was ever declared.
     */
    fun omissionCount(
        className: String,
        methodName: String,
        parameterIndex: Int,
        methodDescriptor: String? = null,
    ): Long =
        findOmissionProbes(className, methodName, methodDescriptor, "index $parameterIndex") { it.parameterIndex == parameterIndex }
            .sumOf { hitsByKey[it] ?: 0L }

    /** Like [omissionCount], but selects the optional parameter by [parameterName] instead of index. */
    fun omissionCount(
        className: String,
        methodName: String,
        parameterName: String,
        methodDescriptor: String? = null,
    ): Long =
        findOmissionProbes(className, methodName, methodDescriptor, "name \"$parameterName\"") { it.parameterName == parameterName }
            .sumOf { hitsByKey[it] ?: 0L }

    private fun findOmissionProbes(
        className: String,
        methodName: String,
        methodDescriptor: String?,
        parameterDescription: String,
        matchesParameter: (StoredProbe) -> Boolean,
    ): List<ProbeKey> {
        val matches =
            omissionTargetIndex[className]?.filter { key ->
                val probe = probesByKey[key]
                probe != null &&
                    probe.methodName == methodName &&
                    (methodDescriptor == null || probe.methodDescriptor == methodDescriptor) &&
                    matchesParameter(probe)
            } ?: emptyList()
        if (matches.isNotEmpty()) return matches
        throw unknownOmissionProbe(className, methodName, methodDescriptor, parameterDescription)
    }

    private fun unknownOmissionProbe(
        className: String,
        methodName: String,
        methodDescriptor: String?,
        parameterDescription: String,
    ): UnknownProbeException {
        skippedByClassName[className]?.let {
            return UnknownProbeException("$className: class was matched but could not be instrumented: ${it.reason}")
        }
        if (className in consultedDeclaredNames && className !in dynamicallyKnownClassNames) {
            return UnknownProbeException("$className: class was declared by the static baseline but never loaded in any instance")
        }
        if (nameIndex.containsKey(className)) {
            val descriptorSuffix = methodDescriptor?.let { " $it" } ?: ""
            return UnknownProbeException(
                "$className: class is instrumented but has no omission probe for method " +
                    "$methodName$descriptorSuffix, parameter $parameterDescription",
            )
        }
        return UnknownProbeException(
            "$className: never mentioned by any manifest or static baseline " +
                "(not matched by includePackages, misspelled, or not loaded yet)",
        )
    }

    /**
     * Every optional parameter whose combined omission total equals its target's hit total
     * within the same instance: every caller took the default, so the parameter can go.
     * "Combined" matters because one parameter can carry more than one omission probe: a Scala
     * constructor default gets both a module getter on the companion class and that class's own
     * static forwarder, both resolving to the same target, so their omissions are summed and
     * judged once rather than each read on its own; see [omissionCount] and ADR 0023. Compared
     * per instance, one row per instance, since an omission probe and its target's method probe
     * only share a class ID within one instance. Claimed only when every probe naming the
     * parameter is non-overridable, since an overridable target's omissions are spread across
     * whichever override actually ran, which the manifest cannot relate back to one total. A
     * target with no method probe at all (an abstract interface method) is skipped, and so is an
     * inline target, the same reason [neverHit] excludes one.
     */
    fun neverSupplied(): List<OptionalParameterRef> =
        optionalParameterFindings { omitted, targetHits, overridable -> !overridable && omitted == targetHits }

    /**
     * Every optional parameter whose combined omission total stayed at zero while its target was
     * called at least once in the same instance: the default value is dead. See [neverSupplied]
     * for why "combined" matters. Claimed for any target, overridable or not. A target with no
     * method probe at all, or an inline target, is skipped, the same as [neverSupplied].
     */
    fun alwaysSupplied(): List<OptionalParameterRef> = optionalParameterFindings { omitted, _, _ -> omitted == 0L }

    /**
     * Groups every `OPTIONAL_ARGUMENT` probe whose target is neither inline nor generated by the
     * parameter it names, within one
     * instance: `(service instance, target class, target method name and descriptor, parameter
     * index)`. A group can hold more than one probe when a target has more than one omission
     * probe resolving to it, the Scala constructor case [neverSupplied] documents. [claims] sees
     * the group's summed omission total, its target's summed hit total, and its shared
     * `overridable` flag (identical across every probe naming one parameter).
     */
    private fun optionalParameterFindings(
        claims: (omitted: Long, targetHits: Long, overridable: Boolean) -> Boolean,
    ): List<OptionalParameterRef> =
        probesByKey.entries
            .filter { (_, probe) -> probe.kind == ProbeKind.OPTIONAL_ARGUMENT && !probe.inline && probe.generatedBy == GeneratedBy.NONE }
            .groupBy { (key, probe) ->
                OmissionTargetKey(
                    key.serviceInstanceId,
                    probe.targetClassName ?: probe.className,
                    probe.methodName,
                    probe.methodDescriptor,
                    probe.parameterIndex,
                )
            }.mapNotNull { (groupKey, members) ->
                val targetKeys =
                    findMethodProbesOrNull(groupKey.targetClassName, groupKey.methodName, groupKey.methodDescriptor)
                        ?.filter { it.serviceInstanceId == groupKey.serviceInstanceId }
                        ?.takeIf { it.isNotEmpty() }
                        ?: return@mapNotNull null
                val targetHits = targetKeys.sumOf { hitsByKey[it] ?: 0L }
                if (targetHits <= 0L) return@mapNotNull null
                val omitted = members.sumOf { (key, _) -> hitsByKey[key] ?: 0L }
                val representative = members.first().value
                if (!claims(omitted, targetHits, representative.overridable)) return@mapNotNull null
                OptionalParameterRef(
                    serviceInstanceId = groupKey.serviceInstanceId,
                    className = groupKey.targetClassName,
                    methodName = groupKey.methodName,
                    methodDescriptor = groupKey.methodDescriptor,
                    parameterIndex = groupKey.parameterIndex ?: -1,
                    parameterName = representative.parameterName ?: "",
                    line = representative.line,
                    targetClassName = members.firstNotNullOfOrNull { it.value.targetClassName },
                )
            }.sortedWith(compareBy({ it.className }, { it.methodName }, { it.parameterIndex }))

    private fun findMethodProbes(
        className: String,
        methodName: String,
        methodDescriptor: String?,
    ): List<ProbeKey> =
        findMethodProbesOrNull(className, methodName, methodDescriptor)
            ?: throw unknownProbe(className, methodName, methodDescriptor)

    /** Like [findMethodProbes], but returns null instead of throwing when nothing matches. */
    private fun findMethodProbesOrNull(
        className: String,
        methodName: String,
        methodDescriptor: String?,
    ): List<ProbeKey>? =
        nameIndex[className]
            ?.filter { key ->
                val probe = probesByKey[key]
                probe != null &&
                    probe.kind == ProbeKind.METHOD &&
                    probe.methodName == methodName &&
                    (methodDescriptor == null || probe.methodDescriptor == methodDescriptor)
            }?.takeIf { it.isNotEmpty() }

    private fun unknownProbe(
        className: String,
        methodName: String,
        methodDescriptor: String?,
    ): UnknownProbeException {
        skippedByClassName[className]?.let {
            return UnknownProbeException("$className: class was matched but could not be instrumented: ${it.reason}")
        }
        if (className in consultedDeclaredNames && className !in dynamicallyKnownClassNames) {
            return UnknownProbeException("$className: class was declared by the static baseline but never loaded in any instance")
        }
        if (nameIndex.containsKey(className)) {
            val descriptorSuffix = methodDescriptor?.let { " $it" } ?: ""
            return UnknownProbeException("$className: class is instrumented but has no probe for method $methodName$descriptorSuffix")
        }
        return UnknownProbeException(
            "$className: never mentioned by any manifest or static baseline " +
                "(not matched by includePackages, misspelled, or not loaded yet)",
        )
    }

    /**
     * Every manifest probe, method or branch, with no hit ever reported by any instance, sorted
     * by class name, method name, line, then branch index.
     *
     * A probe belonging to a Kotlin inline function, or a branch inside one, is left out: a
     * Kotlin caller copies the body into its own call site instead of invoking it, so a zero hit
     * total is not evidence the code never ran. See ADR 0022.
     *
     * A generated probe is left out too: the compiler will emit the method again regardless of
     * what the adopter does, so a zero hit total on a data class's `copy`, an enum's `values`, or
     * the like is not a finding the adopter can act on. See ADR 0026.
     *
     * An optional-argument probe is left out too, whatever its own count: an omission probe
     * reading zero means a parameter is never omitted, which is [alwaysSupplied], not dead code.
     * See ADR 0021.
     */
    fun neverHit(): List<ProbeRef> =
        probesByKey.entries
            .filter { (key, probe) ->
                !probe.inline &&
                    probe.generatedBy == GeneratedBy.NONE &&
                    probe.kind != ProbeKind.OPTIONAL_ARGUMENT &&
                    (hitsByKey[key] ?: 0L) <= 0L
            }.map { (key, probe) ->
                ProbeRef(
                    serviceInstanceId = key.serviceInstanceId,
                    className = probe.className,
                    methodName = probe.methodName,
                    methodDescriptor = probe.methodDescriptor,
                    line = probe.line,
                    kind = probe.kind,
                    branchIndex = probe.branchIndex,
                    inline = probe.inline,
                    inlinedFromClassName = probe.inlinedFromClassName,
                    generatedBy = probe.generatedBy,
                )
            }.sortedWith(compareBy({ it.className }, { it.methodName }, { it.line }, { it.branchIndex ?: -1 }))

    /** Every class reported as matched but not instrumented by any manifest, distinct by class name, sorted by name. */
    fun skippedClasses(): List<SkippedClass> = skippedByClassName.values.sortedBy { it.className }

    /**
     * Whether [serviceInstanceId] has sent a delta batch with `final_flush` set, meaning its
     * shutdown hook ran. False both before that batch arrives and for an instance never seen at
     * all: this collector cannot tell the two apart, since an instance the agent never contacted
     * leaves no other trace either. See ADR 0010.
     */
    fun endedCleanly(serviceInstanceId: String): Boolean = serviceInstanceId in instancesThatEndedCleanly

    /** Every instance id that has sent a delta batch with `final_flush` set. See [endedCleanly]. */
    fun instancesEndedCleanly(): Set<String> = instancesThatEndedCleanly.toSet()

    /**
     * Class names declared by a complete static baseline scan that no manifest, from any
     * instance, has ever mentioned as a probe's class or as a skipped class.
     *
     * Throws [IllegalStateException] if no static baseline scan has ever completed: an empty list
     * would read as "nothing is dead", when the real answer is "no idea yet". A class in the
     * unsafe, unreadable, or unprobed baseline buckets is never counted as declared here, so it
     * never appears in this list either. A declared class whose every declared method is inline
     * or generated is also excluded: Kotlin callers never invoke such a class's methods directly,
     * and the compiler will emit a generated method again regardless of what the adopter does, so
     * such a class never loading at all is not evidence it is dead. See ADR 0022 and ADR 0026.
     */
    fun neverLoaded(): List<String> {
        check(completedScans.isNotEmpty()) { "no complete static baseline scan has been received yet" }
        return consultedDeclaredNames.filter { it !in dynamicallyKnownClassNames && it !in consultedAllInlineOrGeneratedNames }.sorted()
    }

    /**
     * The in-scope callees [methodName] on [className] references in its own bytecode, verbatim as
     * the bytecode names them, deduplicated and sorted by callee class, method name, then
     * descriptor. Every overload of [methodName] contributes its edges. Sources both a loaded
     * class's manifest edges and a never-loaded class's complete-baseline edges, the same union
     * [unreachedClusters] resolves against. See ADR 0024.
     *
     * Throws [UnknownProbeException] if no manifest probe and no complete-baseline declaration ever
     * named [methodName] on [className]; a known method with no callees returns an empty list.
     */
    fun callEdges(
        className: String,
        methodName: String,
    ): List<CallEdge> {
        val fromManifest =
            nameIndex[className].orEmpty().mapNotNull { key ->
                probesByKey[key]?.takeIf { it.kind == ProbeKind.METHOD && it.methodName == methodName }
            }
        val fromBaseline = consultedDeclaredClasses[className]?.methods?.filter { it.methodName == methodName }.orEmpty()
        if (fromManifest.isEmpty() && fromBaseline.isEmpty()) {
            throw unknownProbe(className, methodName, null)
        }
        return (fromManifest.flatMap { it.calls } + fromBaseline.flatMap { it.calls })
            .distinct()
            .sortedWith(compareBy({ it.className }, { it.methodName }, { it.methodDescriptor }))
    }

    /**
     * Every unreached cluster in the call graph, applying the collector's rule (ADR 0024) within
     * this one test JVM: a root is a never-hit method with at least one hit caller
     * ([RootKind.REACHED_FROM_HIT]) or no in-scope caller at all ([RootKind.UNCALLED]), and its
     * cluster is the root plus every never-hit method reachable from it whose every caller is
     * itself already in the cluster. Sorted by member count descending, then by root.
     *
     * A node comes from a manifest METHOD probe, merged across instances with hits summed, or from
     * a non-inline declared method of a class a complete static baseline declared but no manifest
     * ever mentioned; such a member carries [ProbeRef.neverLoaded] `true`, line `-1`, and the
     * declaring instance's id. Inline methods are never nodes, so an edge into one resolves to
     * nothing. Edges are the union of manifest and complete-baseline call edges; resolution walks a
     * callee's owner up through [supertypesByClassName] and complete-baseline supertypes to the
     * first type with a matching node, and, for a virtual call, also down from the owner to every
     * known transitive subtype with one, `<init>` and `<clinit>` excepted. See [computeCallGraph].
     */
    fun unreachedClusters(): List<UnreachedCluster> {
        val graph = computeCallGraph()

        fun isHit(key: NodeKey) = (graph.nodes[key]?.hits ?: 0L) > 0L

        val roots =
            graph.nodes.keys.filter { !isHit(it) }.mapNotNull { key ->
                val callers = graph.callersOf[key].orEmpty()
                when {
                    callers.isEmpty() -> key to RootKind.UNCALLED
                    callers.any { isHit(it) } -> key to RootKind.REACHED_FROM_HIT
                    else -> null
                }
            }

        return roots
            .map { (rootKey, rootKind) -> buildCluster(graph, rootKey, rootKind, ::isHit) }
            .sortedWith(compareByDescending<UnreachedCluster> { it.members.size }.thenComparing({ it.root }, probeRefComparator))
    }

    /**
     * Grows [rootKey]'s cluster by fixpoint: repeatedly add a never-hit node reachable by a
     * resolved edge from a current member, once every one of that node's callers is itself already
     * in the cluster. Two consequences of this rule, pinned by tests: a node whose callers sit in
     * two different clusters is added to neither, and a cycle of never-hit nodes with no outside
     * caller produces no root at all, so it never reaches this method in the first place.
     */
    private fun buildCluster(
        graph: CallGraph,
        rootKey: NodeKey,
        rootKind: RootKind,
        isHit: (NodeKey) -> Boolean,
    ): UnreachedCluster {
        val members = mutableSetOf(rootKey)
        var changed = true
        while (changed) {
            changed = false
            for (member in members.toList()) {
                for (target in graph.resolvedEdges[member].orEmpty()) {
                    if (target in members || isHit(target)) continue
                    val callers = graph.callersOf[target].orEmpty()
                    if (callers.isNotEmpty() && members.containsAll(callers)) {
                        members += target
                        changed = true
                    }
                }
            }
        }
        val memberRefs = members.map { toProbeRef(graph.nodes.getValue(it), it) }.sortedWith(probeRefComparator)
        val neverLoadedClasses =
            memberRefs
                .filter { it.neverLoaded }
                .map { it.className }
                .distinct()
                .size
        return UnreachedCluster(toProbeRef(graph.nodes.getValue(rootKey), rootKey), rootKind, memberRefs, neverLoadedClasses)
    }

    /**
     * Builds every [NodeKey], resolves its edges against the known supertype graph, and indexes
     * callers. An edge resolves to the union of two lookups, either of which may find nothing: the
     * first node up the owner's supertype chain, which is an inherited concrete declaration, and,
     * for a virtual call, every node with the same name and descriptor on a transitive subtype of
     * the owner. Widening starts at the owner, not at the declaring type, for two reasons: an
     * abstract interface method has no node anywhere, so requiring the up-walk to succeed would
     * drop every edge into a pure interface, which is the constructor-injected case supertypes
     * exist for; and a receiver typed as the owner can only be the owner or one of its subtypes,
     * never a sibling under some ancestor.
     * Every resolved edge into a class also implies an edge into that class's `<clinit>` node
     * when it has one: no bytecode ever calls `<clinit>`, the JVM runs it on the class's first
     * active use, and a resolved call into the class is exactly such a use. Without this a
     * never-initialised class's `<clinit>` would be an uncalled root of its own beside the
     * cluster that actually owns it.
     */
    private fun computeCallGraph(): CallGraph {
        val nodes = buildNodes()
        val reverseSubtypes = buildReverseSubtypes()
        val resolvedEdges = mutableMapOf<NodeKey, Set<NodeKey>>()
        val callersOf = mutableMapOf<NodeKey, MutableSet<NodeKey>>()
        for ((nodeKey, info) in nodes) {
            val targets = mutableSetOf<NodeKey>()
            for (edge in info.edges) {
                findDeclaringType(nodes, edge.className, edge.methodName, edge.methodDescriptor)?.let {
                    targets += NodeKey(it, edge.methodName, edge.methodDescriptor)
                }
                if (edge.virtual && edge.methodName != "<init>" && edge.methodName != "<clinit>") {
                    targets += widenToSubtypes(nodes, reverseSubtypes, edge.className, edge.methodName, edge.methodDescriptor)
                }
            }
            for (target in targets.toList()) {
                val typeInitializer = NodeKey(target.className, "<clinit>", "()V")
                if (typeInitializer in nodes) targets += typeInitializer
            }
            targets -= nodeKey
            resolvedEdges[nodeKey] = targets
            for (target in targets) callersOf.getOrPut(target) { mutableSetOf() } += nodeKey
        }
        return CallGraph(nodes, resolvedEdges, callersOf)
    }

    /**
     * Every node: a manifest METHOD probe, non-inline and non-generated, merged across instances
     * by (class, method, descriptor) with hits summed and edges unioned with any matching
     * complete-baseline declaration; plus, for a class a complete scan declared that no manifest
     * ever mentioned, each of its non-inline, non-generated declared methods, with zero hits. A
     * generated method, such as a data class's `copy`, is never a node: the compiler will emit it
     * again regardless of what the adopter does, so it can never root or extend an unreached
     * cluster. See ADR 0026.
     */
    private fun buildNodes(): Map<NodeKey, NodeInfo> {
        val nodes = mutableMapOf<NodeKey, NodeInfo>()
        val manifestGroups =
            probesByKey.entries
                .filter { (_, probe) -> probe.kind == ProbeKind.METHOD && !probe.inline && probe.generatedBy == GeneratedBy.NONE }
                .groupBy { (_, probe) -> NodeKey(probe.className, probe.methodName, probe.methodDescriptor) }
        for ((nodeKey, entries) in manifestGroups) {
            val hits = entries.sumOf { (key, _) -> hitsByKey[key] ?: 0L }
            val edges = entries.flatMap { (_, probe) -> probe.calls }.toMutableSet()
            consultedDeclaredClasses[nodeKey.className]
                ?.methods
                ?.filter { it.methodName == nodeKey.methodName && it.methodDescriptor == nodeKey.methodDescriptor }
                ?.forEach { edges += it.calls }
            val representative = entries.first()
            nodes[nodeKey] =
                NodeInfo(
                    serviceInstanceId = representative.key.serviceInstanceId,
                    line = representative.value.line,
                    neverLoaded = false,
                    hits = hits,
                    edges = edges,
                )
        }
        for ((className, declared) in consultedDeclaredClasses) {
            if (className in dynamicallyKnownClassNames) continue
            for (method in declared.methods) {
                if (method.inline || method.generatedBy != GeneratedBy.NONE) continue
                val nodeKey = NodeKey(className, method.methodName, method.methodDescriptor)
                if (nodeKey in nodes) continue
                nodes[nodeKey] =
                    NodeInfo(
                        serviceInstanceId = declared.serviceInstanceId,
                        line = -1,
                        neverLoaded = true,
                        hits = 0L,
                        edges = method.calls.toSet(),
                    )
            }
        }
        return nodes
    }

    /** A class's supertypes from either source: its manifest record, or a complete baseline's declaration. */
    private fun supertypesOf(className: String): SupertypesInfo? =
        supertypesByClassName[className]
            ?: consultedDeclaredClasses[className]?.let { SupertypesInfo(it.superClassName, it.interfaceNames) }

    /** Every known class name's direct subtypes, from either supertypes source, for widening a virtual call down. */
    private fun buildReverseSubtypes(): Map<String, List<String>> {
        val reverse = mutableMapOf<String, MutableList<String>>()
        val classNames = supertypesByClassName.keys + consultedDeclaredClasses.keys
        for (className in classNames) {
            val info = supertypesOf(className) ?: continue
            info.superClassName?.let { reverse.getOrPut(it) { mutableListOf() } += className }
            info.interfaceNames.forEach { reverse.getOrPut(it) { mutableListOf() } += className }
        }
        return reverse
    }

    /**
     * Breadth-first walk from [owner] up through its supertypes to the first type with a node
     * named ([name], [desc]), [owner] itself included. Null if the whole chain, as far as it is
     * known, never reaches one.
     */
    private fun findDeclaringType(
        nodes: Map<NodeKey, NodeInfo>,
        owner: String,
        name: String,
        desc: String,
    ): String? {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue += owner
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            if (NodeKey(current, name, desc) in nodes) return current
            val info = supertypesOf(current) ?: continue
            info.superClassName?.let { queue += it }
            queue += info.interfaceNames
        }
        return null
    }

    /** Every transitive subtype of [declaringType], excluding itself, that has a matching ([name], [desc]) node. */
    private fun widenToSubtypes(
        nodes: Map<NodeKey, NodeInfo>,
        reverseSubtypes: Map<String, List<String>>,
        declaringType: String,
        name: String,
        desc: String,
    ): Set<NodeKey> {
        val result = mutableSetOf<NodeKey>()
        val visited = mutableSetOf(declaringType)
        val queue = ArrayDeque<String>()
        queue += reverseSubtypes[declaringType].orEmpty()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            val key = NodeKey(current, name, desc)
            if (key in nodes) result += key
            queue += reverseSubtypes[current].orEmpty()
        }
        return result
    }

    private fun toProbeRef(
        info: NodeInfo,
        key: NodeKey,
    ): ProbeRef =
        ProbeRef(
            serviceInstanceId = info.serviceInstanceId,
            className = key.className,
            methodName = key.methodName,
            methodDescriptor = key.methodDescriptor,
            line = info.line,
            kind = ProbeKind.METHOD,
            branchIndex = null,
            neverLoaded = info.neverLoaded,
        )

    /**
     * True if the endpoint identified by [verb] and [routeTemplate] has a summed call count above
     * zero. Both arguments are normalised with [RouteTemplateNormalizer.normalizeVerb] and
     * [RouteTemplateNormalizer.normalize] before lookup, so `wasCalled("get", "/checkout/")` finds
     * the same endpoint a manifest reported as `GET /checkout`.
     *
     * Throws [UnknownEndpointException] if no manifest ever mentioned this endpoint.
     */
    fun wasCalled(
        verb: String,
        routeTemplate: String,
    ): Boolean = callCount(verb, routeTemplate) > 0L

    /**
     * The endpoint's call count, summed across every instance that reported it: each instance's
     * own `hits_total` is already merged with max() against every redelivered value it sent, and
     * this sums one such total per instance, mirroring how [hitCount] aggregates method probes
     * across instances by class and method name. [verb] and [routeTemplate] are normalised the
     * same way [wasCalled] normalises them.
     *
     * Throws [UnknownEndpointException] if no manifest ever mentioned this endpoint.
     */
    fun callCount(
        verb: String,
        routeTemplate: String,
    ): Long {
        val identity = normalizeEndpointIdentity(verb, routeTemplate)
        val keys = endpointKeysByIdentity[identity] ?: throw unknownEndpoint(identity)
        return keys.sumOf { endpointHitsByKey[it] ?: 0L }
    }

    /**
     * Every endpoint at least one instance reported, of any [EndpointDiscoverySource], whose
     * summed call count is zero, sorted by route template then verb. An endpoint discovered by
     * dispatch is by construction called at least once, so it can never appear here.
     */
    fun neverCalled(): List<EndpointRef> =
        endpointRefsByIdentity
            .filterKeys { identity -> (endpointKeysByIdentity[identity]?.sumOf { endpointHitsByKey[it] ?: 0L } ?: 0L) <= 0L }
            .values
            .sortedWith(compareBy({ it.routeTemplate }, { it.verb }))

    /** Every endpoint any instance ever reported, sorted by route template then verb. */
    fun endpoints(): List<EndpointRef> = endpointRefsByIdentity.values.sortedWith(compareBy({ it.routeTemplate }, { it.verb }))

    /** Every endpoint module reported as disabled by any instance, distinct by module name, sorted by module name. */
    fun disabledEndpointModules(): List<DisabledEndpointModule> = disabledEndpointModulesByName.values.sortedBy { it.module }

    /**
     * Blocks until some manifest, from any instance, has mentioned the endpoint identified by
     * [verb] and [routeTemplate], normalised the same way [wasCalled] normalises its arguments.
     * Throws [java.util.concurrent.TimeoutException] if [timeout] elapses first.
     */
    fun awaitEndpoint(
        verb: String,
        routeTemplate: String,
        timeout: Duration,
    ) {
        val identity = normalizeEndpointIdentity(verb, routeTemplate)
        awaitUntil(timeout, "no manifest ever mentioned endpoint $verb $routeTemplate within $timeout") {
            endpointRefsByIdentity.containsKey(identity)
        }
    }

    private fun normalizeEndpointIdentity(
        verb: String,
        routeTemplate: String,
    ): EndpointIdentity = EndpointIdentity(RouteTemplateNormalizer.normalizeVerb(verb), RouteTemplateNormalizer.normalize(routeTemplate))

    private fun unknownEndpoint(identity: EndpointIdentity): UnknownEndpointException {
        val disabled = disabledEndpointModulesByName.values.sortedBy { it.module }
        if (disabled.isNotEmpty()) {
            val listed = disabled.joinToString(", ") { "${it.module}: ${it.reason}" }
            return UnknownEndpointException(
                "${identity.verb} ${identity.routeTemplate}: never mentioned by any manifest; " +
                    "an endpoint module reported itself disabled and may be why: $listed",
            )
        }
        return UnknownEndpointException("${identity.verb} ${identity.routeTemplate}: never mentioned by any manifest")
    }

    override fun close() {
        server.stop(0)
        executor.shutdown()
    }

    private fun handleDeltaBatch(exchange: HttpExchange) {
        val bytes = exchange.requestBody.readBytes()
        val batch =
            try {
                ProtoPayloadCodec.decodeDeltaBatch(bytes)
            } catch (e: Exception) {
                respond(exchange, 400)
                return
            }
        val instanceId = batch.resource.serviceInstanceId
        for (delta in batch.deltas) {
            val key = ProbeKey(instanceId, delta.classId, delta.probeIndex)
            hitsByKey.merge(key, delta.hitsTotal, ::maxOf)
        }
        for (delta in batch.endpointDeltas) {
            val key = InstanceEndpointKey(instanceId, delta.endpointId)
            endpointHitsByKey.merge(key, delta.hitsTotal, ::maxOf)
        }
        if (batch.finalFlush) instancesThatEndedCleanly += instanceId
        deltaBatchSeq.incrementAndGet()
        respond(exchange, 200)
        signalAll()
    }

    private fun handleManifest(exchange: HttpExchange) {
        val bytes = exchange.requestBody.readBytes()
        val manifest =
            try {
                ProtoPayloadCodec.decodeProbeManifest(bytes)
            } catch (e: Exception) {
                respond(exchange, 400)
                return
            }
        val instanceId = manifest.serviceInstanceId
        // A class's own ClassSupertypes record is always staged and committed together with its
        // probe locations (see ProbeRegistry.computeManifestDeltas), so every classId this manifest
        // mentions in classSupertypes also has a matching probe location earlier in this same call.
        val classNamesByClassId = mutableMapOf<Int, String>()
        for (location in manifest.probes) {
            val key = ProbeKey(instanceId, location.classId, location.probeIndex)
            probesByKey[key] =
                StoredProbe(
                    location.className,
                    location.methodName,
                    location.methodDescriptor,
                    location.line,
                    location.kind,
                    location.branchIndex,
                    location.inline,
                    location.parameterIndex,
                    location.parameterName,
                    location.overridable,
                    location.targetClassName,
                    location.calls,
                    location.inlinedFromClassName,
                    location.generatedBy,
                )
            nameIndex.computeIfAbsent(location.className) { ConcurrentHashMap.newKeySet() }.add(key)
            if (location.kind == ProbeKind.OPTIONAL_ARGUMENT) {
                val targetClassName = location.targetClassName ?: location.className
                omissionTargetIndex.computeIfAbsent(targetClassName) { ConcurrentHashMap.newKeySet() }.add(key)
            }
            dynamicallyKnownClassNames += location.className
            classNamesByClassId[location.classId] = location.className
        }
        for (skipped in manifest.skippedClasses) {
            skippedByClassName.putIfAbsent(skipped.className, skipped)
            dynamicallyKnownClassNames += skipped.className
        }
        for (supertypes in manifest.classSupertypes) {
            val className = classNamesByClassId[supertypes.classId] ?: continue
            supertypesByClassName[className] = SupertypesInfo(supertypes.superClassName, supertypes.interfaceNames)
        }
        for (endpointLocation in manifest.endpoints) {
            val identity = EndpointIdentity(endpointLocation.verb, endpointLocation.routeTemplate)
            val key = InstanceEndpointKey(instanceId, endpointLocation.endpointId)
            endpointKeysByIdentity.computeIfAbsent(identity) { ConcurrentHashMap.newKeySet() }.add(key)
            // Upserts unconditionally: a re-delivered record carries a newer handler join or
            // discovery source, and the latest delivery, from any instance, wins.
            endpointRefsByIdentity[identity] =
                EndpointRef(
                    verb = endpointLocation.verb,
                    routeTemplate = endpointLocation.routeTemplate,
                    verbatimTemplate = endpointLocation.verbatimTemplate,
                    framework = endpointLocation.framework,
                    discoverySource = endpointLocation.discoverySource,
                    handlerClass = endpointLocation.handlerClass,
                    handlerMethod = endpointLocation.handlerMethod,
                    handlerDescriptor = endpointLocation.handlerDescriptor,
                )
        }
        for (module in manifest.disabledEndpointModules) {
            disabledEndpointModulesByName.putIfAbsent(module.module, module)
        }
        respond(exchange, 200)
        signalAll()
    }

    private fun handleStaticBaseline(exchange: HttpExchange) {
        val bytes = exchange.requestBody.readBytes()
        val baseline =
            try {
                ProtoPayloadCodec.decodeStaticBaseline(bytes)
            } catch (e: Exception) {
                respond(exchange, 400)
                return
            }
        val instanceId = baseline.resource.serviceInstanceId
        val scanKey = ScanKey(instanceId, baseline.scannedAt)
        val progress = scans.computeIfAbsent(scanKey) { ScanProgress(baseline.chunkCount) }
        val wasComplete = progress.complete
        progress.received += baseline.chunkIndex
        progress.declaredNames += baseline.declaredClasses.map { it.className }
        progress.allInlineOrGeneratedNames +=
            baseline.declaredClasses
                .filter { it.methods.isNotEmpty() && it.methods.all { method -> method.inline || method.generatedBy != GeneratedBy.NONE } }
                .map { it.className }
        for (declaredClass in baseline.declaredClasses) {
            progress.declaredClasses[declaredClass.className] =
                DeclaredClassInfo(
                    serviceInstanceId = instanceId,
                    methods =
                        declaredClass.methods.map {
                            DeclaredMethodInfo(it.methodName, it.methodDescriptor, it.inline, it.calls, it.generatedBy)
                        },
                    superClassName = declaredClass.superClassName,
                    interfaceNames = declaredClass.interfaceNames,
                )
        }
        if (!wasComplete && progress.complete) {
            consultedDeclaredNames += progress.declaredNames
            consultedAllInlineOrGeneratedNames += progress.allInlineOrGeneratedNames
            for ((className, info) in progress.declaredClasses) consultedDeclaredClasses.putIfAbsent(className, info)
            completedScans += scanKey
        }
        respond(exchange, 200)
        signalAll()
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
    ) {
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
    }

    private fun signalAll() {
        lock.withLock { condition.signalAll() }
    }

    companion object {
        /**
         * Starts a collector bound to `localhost`. [port] `0` (the default) picks any free port,
         * read back afterwards from [endpoint].
         */
        fun start(port: Int = 0): YukonTestCollector {
            val httpServer = HttpServer.create(InetSocketAddress("localhost", port), 0)
            val executor =
                Executors.newCachedThreadPool { runnable -> Thread(runnable, "yukon-testkit-http").apply { isDaemon = true } }
            httpServer.executor = executor
            val collector = YukonTestCollector(httpServer, executor)
            httpServer.createContext("/v1/yukon/deltas", collector::handleDeltaBatch)
            httpServer.createContext("/v1/yukon/manifest", collector::handleManifest)
            httpServer.createContext("/v1/yukon/static-baseline", collector::handleStaticBaseline)
            startDaemon(httpServer)
            return collector
        }

        /**
         * A Java thread inherits daemon status from the thread that creates it, and
         * `HttpServer.start()` creates its dispatcher thread on whichever thread calls it. A
         * JUnit extension that called `httpServer.start()` directly would leave that dispatcher
         * thread non-daemon, which alone would keep the JVM from exiting even after every other
         * thread finished. Starting the server from a short-lived daemon thread, and joining it
         * before returning, makes the dispatcher thread daemon too.
         */
        private fun startDaemon(httpServer: HttpServer) {
            val starter = Thread({ httpServer.start() }, "yukon-testkit-http-starter").apply { isDaemon = true }
            starter.start()
            starter.join()
        }
    }
}

/**
 * One probe's identity and location, as reported by a manifest. See [YukonTestCollector] for the
 * class name format. [inline] marks a Kotlin inline function, or a branch inside one; see ADR
 * 0022. [parameterIndex], [parameterName], [overridable], and [targetClassName] are set only when
 * [kind] is [ProbeKind.OPTIONAL_ARGUMENT]; see ADR 0021 and ADR 0023. [neverLoaded] is true only
 * for an [YukonTestCollector.unreachedClusters] member that exists solely because a complete
 * static baseline declared it: its [line] is `-1`, since the static scan records no line, and its
 * [serviceInstanceId] names the instance whose scan declared it rather than one that loaded it.
 * See ADR 0024. [inlinedFromClassName] is set only for a [ProbeKind.BRANCH] probe that is a kept
 * inlined copy, dotted; see ADR 0025. [generatedBy] is set when [kind] is [ProbeKind.METHOD],
 * and for an [ProbeKind.OPTIONAL_ARGUMENT] probe as its target's mark; see ADR 0026.
 */
data class ProbeRef(
    val serviceInstanceId: String,
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val line: Int,
    val kind: ProbeKind,
    val branchIndex: Int?,
    val inline: Boolean = false,
    val parameterIndex: Int? = null,
    val parameterName: String? = null,
    val overridable: Boolean = false,
    val targetClassName: String? = null,
    val neverLoaded: Boolean = false,
    val inlinedFromClassName: String? = null,
    val generatedBy: GeneratedBy = GeneratedBy.NONE,
)

/**
 * Which of the two root shapes ADR 0024 distinguishes an [UnreachedCluster] by.
 * [REACHED_FROM_HIT] means at least one in-scope caller has hits; [UNCALLED] means the root has no
 * in-scope caller at all. The two call for different fixes: a reached-from-hit root's caller works
 * but never takes this branch, while an uncalled root's caller may not exist yet, or may live
 * outside scope.
 */
enum class RootKind { REACHED_FROM_HIT, UNCALLED }

/**
 * A root plus every never-hit method reachable from it through call edges whose every in-scope
 * caller is itself in the cluster, as found by [YukonTestCollector.unreachedClusters]. [members]
 * includes [root] and is sorted the same way [YukonTestCollector.neverHit] sorts its results.
 * [neverLoadedClasses] counts the distinct classes among [members] that exist only because a
 * complete static baseline declared them; see [ProbeRef.neverLoaded]. Deleting [root] removes the
 * whole cluster. See ADR 0024 and CONTEXT.md, "Unreached cluster".
 */
data class UnreachedCluster(
    val root: ProbeRef,
    val rootKind: RootKind,
    val members: List<ProbeRef>,
    val neverLoadedClasses: Int,
)

/**
 * One optional parameter's identity, as found by [YukonTestCollector.neverSupplied] or
 * [YukonTestCollector.alwaysSupplied]. [className], [methodName], and [methodDescriptor] name the
 * target function the parameter belongs to, not the synthetic `$default` method its omission
 * probe actually sits in: for a Scala constructor default getter, [className] is the constructor's
 * own class, not the companion module class the getter's slot lives on. [targetClassName] carries
 * the same raw value the manifest reported, null unless the target crosses a class boundary. See
 * ADR 0021, ADR 0023, and CONTEXT.md, "Optional parameter".
 */
data class OptionalParameterRef(
    val serviceInstanceId: String,
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val parameterIndex: Int,
    val parameterName: String,
    val line: Int,
    val targetClassName: String? = null,
)

/**
 * Thrown when a query names a class or method [YukonTestCollector] has no probe for, instead of
 * reading as "confirmed never hit". The message names one of four cases, checked in this order:
 *
 * 1. The class was matched by `includePackages` but ByteBuddy could not instrument it, so it was
 *    reported as skipped.
 * 2. The class was declared by a complete static baseline scan, but no manifest from any instance
 *    ever mentioned it: it never loaded during the observation window.
 * 3. The class is instrumented and has manifest probes, but none match the requested method name
 *    or descriptor.
 * 4. The class was never mentioned anywhere at all: not matched by `includePackages`, misspelled,
 *    or simply not loaded yet.
 */
class UnknownProbeException(
    message: String,
) : RuntimeException(message)

/**
 * One endpoint's identity and display fields, as reported by a manifest. [verb] and
 * [routeTemplate] are the normalised identity; [verbatimTemplate] keeps the framework's own
 * spelling for display. [handlerClass], [handlerMethod], and [handlerDescriptor] are null until a
 * framework hook joins a handler to the endpoint; see CONTEXT.md, "Handler".
 */
data class EndpointRef(
    val verb: String,
    val routeTemplate: String,
    val verbatimTemplate: String,
    val framework: String,
    val discoverySource: EndpointDiscoverySource,
    val handlerClass: String?,
    val handlerMethod: String?,
    val handlerDescriptor: String?,
)

/**
 * Thrown when a query names a `(verb, route template)` [YukonTestCollector] has no endpoint for,
 * instead of reading as "confirmed never called". The message names one of two cases, checked in
 * this order:
 *
 * 1. At least one endpoint module reported itself disabled. The message names every disabled
 *    module and its reason, since the unmentioned endpoint may belong to one of them.
 * 2. No manifest, from any instance, ever mentioned this endpoint.
 */
class UnknownEndpointException(
    message: String,
) : RuntimeException(message)
