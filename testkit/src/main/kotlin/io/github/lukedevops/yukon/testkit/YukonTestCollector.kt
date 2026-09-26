package io.github.lukedevops.yukon.testkit

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.lukedevops.yukon.export.BranchSite
import io.github.lukedevops.yukon.export.CallEdge
import io.github.lukedevops.yukon.export.CallEdgeKind
import io.github.lukedevops.yukon.export.DisabledEndpointModule
import io.github.lukedevops.yukon.export.EndpointDiscoverySource
import io.github.lukedevops.yukon.export.GeneratedBy
import io.github.lukedevops.yukon.export.KotlinKind
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ProbeManifest
import io.github.lukedevops.yukon.export.ProtoPayloadCodec
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.export.RoutineKind
import io.github.lukedevops.yukon.export.SkippedClass
import io.github.lukedevops.yukon.export.UnreportedClass
import io.github.lukedevops.yukon.registry.RouteTemplateNormalizer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
 * Dependency queries ([dependency], [unloadedDependencies], [unreferencedDependencies],
 * [unreachedDependencies], [absentReferences]) apply ADR 0030's rules within this one test JVM
 * and follow the same rule again: a dependency no manifest has listed throws
 * [UnknownDependencyException], and a question the data cannot answer yet, or at all without a
 * complete static baseline, throws [IllegalStateException] instead of returning an empty list.
 * The agent sends a dependency's entry only after its loaded-class counts have been delivered
 * (ADR 0036), so [dependency] answers once the entry arrives. The four list queries answer only once every instance heard from has
 * sent `dependencies_listed`; [awaitDependenciesListed] waits for that.
 *
 * Every probe, endpoint and dependency is keyed on its instance id alone, not on the run id ADR
 * 0032 adds. That is the same as keying on the run only while each instance id names one run, which
 * holds when this collector hears from the one agent in its own test JVM (ADR 0018). A payload with
 * an empty run id, or with a second run id under an instance id this collector has already heard
 * from, would break that assumption. Such a payload is answered 400, nothing from it is kept, and
 * the reason is recorded in [rejectedPayloads]. From then on [awaitSettled] and every other query
 * and wait throw [IllegalStateException] listing the recorded reasons, so a test fails even if it
 * never calls [rejectedPayloads].
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
        val referencedClasses: List<String> = emptyList(),
        val branchKey: String? = null,
        val branchSites: List<BranchSite> = emptyList(),
        val static: Boolean = false,
        val lambdaBody: Boolean = false,
        val parameterNames: List<String> = emptyList(),
        val genericSignature: String = "",
        val extensionReceiver: Boolean = false,
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
        val referencedClasses: List<String> = emptyList(),
        val static: Boolean = false,
        val parameterNames: List<String> = emptyList(),
        val genericSignature: String = "",
        val extensionReceiver: Boolean = false,
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
        val kotlinKind: KotlinKind = KotlinKind.NONE,
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

    /** One resolved call out of a method: the callee node and the guard the raw [CallEdge] carried. */
    private data class ResolvedCall(
        val callee: NodeKey,
        val guard: Int?,
    )

    /** The resolved call graph: every node and its resolved outgoing calls. */
    private class CallGraph(
        val nodes: Map<NodeKey, NodeInfo>,
        val calls: Map<NodeKey, Set<ResolvedCall>>,
    )

    /**
     * One node of the cluster graph [unreachedClusters] grows clusters over: a method, or, when
     * [branchIndex] is set, an outcome node in that method. See ADR 0039. When [isClass] is true it
     * is a class node, and [method] holds only the class name. See [classNode].
     */
    private data class ClusterNode(
        val method: NodeKey,
        val branchIndex: Int? = null,
        val isClass: Boolean = false,
    )

    /** One outcome of one instance's method, by its branch index. */
    private data class OutcomeKey(
        val serviceInstanceId: String,
        val method: NodeKey,
        val branchIndex: Int,
    )

    /**
     * A class node: a class that holds a class finding, standing for the never-hit [methods] the
     * finding covers. Those methods are never nodes of their own. See server ADR 0034.
     */
    private class ClassNode(
        val finding: ClassFinding,
        val methods: List<NodeKey>,
    )

    /**
     * One judgeable METHOD probe location merged across instances: [hits] summed, and [static] and
     * [lambdaBody] true when any instance's probe said so.
     */
    private data class JudgeableMethod(
        val key: NodeKey,
        val hits: Long,
        val static: Boolean,
        val lambdaBody: Boolean,
    )

    /**
     * What server ADR 0034's rules say about the loaded classes. [findings] holds each class that is
     * never initialised or never instantiated. [covered] names the never-hit methods a finding
     * covers, which can only run through it, lambda bodies that fold into it included.
     * [inNeverHitCode] names the lambda bodies that fold into never-hit methods that are rows of
     * their own. [constructed] holds each class one of whose constructors ran, so a never-hit
     * constructor of it is an unused overload.
     */
    private class ClassJudgement(
        val findings: Map<String, ClassFinding>,
        val covered: Map<String, List<NodeKey>>,
        val inNeverHitCode: Set<NodeKey>,
        val constructed: Set<String>,
    ) {
        val coveredMethods: Set<NodeKey> = covered.values.flatten().toSet()

        /** Every method that is not a row of its own, since a class finding or a never-hit method covers it. */
        val foldedMethods: Set<NodeKey> = coveredMethods + inNeverHitCode
    }

    /**
     * An outcome node: a judgeable outcome with no hits in a method with hits, merged across
     * instances by its method and branch index. [ref] is one instance's BRANCH probe for it. [site]
     * is the site that lists it on its method's METHOD probe, or null when no manifest listed one.
     */
    private data class OutcomeNode(
        val ref: ProbeRef,
        val site: BranchSite?,
    )

    /** The cluster graph: each node's callers and callees, over methods and outcome nodes alike. */
    private class ClusterGraph(
        val callersOf: Map<ClusterNode, Set<ClusterNode>>,
        val calleesOf: Map<ClusterNode, Set<ClusterNode>>,
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

    /** Scopes a per-instance id (`dependency_id`, `class_id`) or a class name to the instance that reported it. */
    private data class InstanceKey<T>(
        val serviceInstanceId: String,
        val id: T,
    )

    /**
     * One instance's static-baseline references for one declared class: the class-level list and
     * each declared method's. Kept per instance, since ADR 0030 splits unreferenced from unreached
     * only with a complete baseline from each instance that lists the dependency.
     */
    private data class BaselineReferences(
        val classReferences: List<String>,
        val methods: List<DeclaredMethodInfo>,
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

    /** A class's Kotlin kind, by name, from any manifest, populated the same way as [supertypesByClassName]. See ADR 0041. */
    private val kotlinKindByClassName = ConcurrentHashMap<String, KotlinKind>()

    /** Classes a sweep found loaded but unreported, by name, from any manifest. See ADR 0027. */
    private val unreportedByClassName = ConcurrentHashMap<String, UnreportedClass>()

    /** Declared classes from every complete static baseline scan, by name. See [handleStaticBaseline]. */
    private val consultedDeclaredClasses = ConcurrentHashMap<String, DeclaredClassInfo>()

    /**
     * Every class name any manifest has ever mentioned: with probes, as skipped, or as unreported.
     * All three mean the class loaded, which is what a "never loaded" claim asks about.
     */
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

    // Dependency usage (ADR 0030). Everything is per instance: dependency_id and class_id are
    // assigned by each instance's own registry.
    private val dependencyLocations = ConcurrentHashMap<InstanceKey<Int>, DependencyView>()

    private val loadedClassesTotals = ConcurrentHashMap<InstanceKey<Int>, Long>()
    private val externalClassesByName = ConcurrentHashMap<InstanceKey<String>, ExternalClassView>()
    private val classLevelReferences = ConcurrentHashMap<InstanceKey<Int>, List<String>>()
    private val classNamesByClassId = ConcurrentHashMap<InstanceKey<Int>, String>()
    private val baselineReferences = ConcurrentHashMap<InstanceKey<String>, BaselineReferences>()
    private val instancesRecordingReferences: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Every instance that sent a manifest with `dependencies_listed` set; see [awaitDependenciesListed]. */
    private val instancesWithDependenciesListed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val instanceIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Per instance, every class a manifest named as probed, skipped or unreported: every class that loaded there. */
    private val loadedClassNamesByInstance = ConcurrentHashMap<String, MutableSet<String>>()

    /** The one run id accepted per instance id; see the class doc and [rejectionFor]. */
    private val runIdByInstance = ConcurrentHashMap<String, String>()
    private val rejections = CopyOnWriteArrayList<String>()

    /** Base URL to pass as an agent's `endpoint=` option, for example `http://localhost:54321`. */
    val endpoint: String = "http://localhost:${server.address.port}"

    /**
     * Blocks until a delta batch arrives that was received after this call began, including an
     * empty one: the agent sends a delta batch on every flush tick, even when nothing changed, as
     * a liveness heartbeat. Throws [TimeoutException] if [timeout] elapses first.
     */
    fun awaitNextFlush(timeout: Duration) {
        checkNoRejections()
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
        checkNoRejections()
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
        checkNoRejections()
        awaitUntil(timeout, "no manifest ever mentioned $className#$methodName within $timeout") {
            nameIndex[className]?.any { probesByKey[it]?.methodName == methodName } == true
        }
    }

    private fun awaitUntil(
        timeout: Duration,
        timeoutMessage: String,
        predicate: () -> Boolean,
    ) = awaitUntil(timeout, { timeoutMessage }, predicate)

    private fun awaitUntil(
        timeout: Duration,
        timeoutMessage: () -> String,
        predicate: () -> Boolean,
    ) {
        val deadlineNanos = System.nanoTime() + timeout.toNanos()
        lock.withLock {
            while (!predicate()) {
                // A rejection wakes this wait, so it fails at once rather than at the timeout.
                checkNoRejections()
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0) throw TimeoutException(timeoutMessage())
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
    ): Boolean = checked { findMethodProbes(className, methodName, methodDescriptor).any { (hitsByKey[it] ?: 0L) > 0L } }

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
    ): Long = checked { findMethodProbes(className, methodName, methodDescriptor).sumOf { hitsByKey[it] ?: 0L } }

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
        checked {
            findOmissionProbes(className, methodName, methodDescriptor, "index $parameterIndex") { it.parameterIndex == parameterIndex }
                .sumOf { hitsByKey[it] ?: 0L }
        }

    /** Like [omissionCount], but selects the optional parameter by [parameterName] instead of index. */
    fun omissionCount(
        className: String,
        methodName: String,
        parameterName: String,
        methodDescriptor: String? = null,
    ): Long =
        checked {
            findOmissionProbes(className, methodName, methodDescriptor, "name \"$parameterName\"") { it.parameterName == parameterName }
                .sumOf { hitsByKey[it] ?: 0L }
        }

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
        checked {
            optionalParameterFindings { omitted, targetHits, overridable -> !overridable && omitted == targetHits }
        }

    /**
     * Every optional parameter whose combined omission total stayed at zero while its target was
     * called at least once in the same instance: the default value is dead. See [neverSupplied]
     * for why "combined" matters. Claimed for any target, overridable or not. A target with no
     * method probe at all, or an inline target, is skipped, the same as [neverSupplied].
     */
    fun alwaysSupplied(): List<OptionalParameterRef> = checked { optionalParameterFindings { omitted, _, _ -> omitted == 0L } }

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
     *
     * Server ADR 0034's rules apply as well. A `<clinit>` is never listed, since it is a class
     * state. A constructor is listed only as an unused overload, when another constructor of its
     * class ran. A method that can only run through a class finding ([neverInitialised],
     * [neverInstantiated]) is left out, and so is a never-hit lambda body whose every creator is
     * such a method or a never-hit method listed here. A BRANCH probe in a method left out this way
     * is left out too.
     *
     * A branch site inside code a row already stands for folds into that row, as server ADR 0031
     * has it: every BRANCH probe of the site is left out. A site folds when its method's METHOD
     * probe is a row here, or when its guard, the innermost outcome in the same method that must
     * run before the site is reached (ADR 0037), is a never-hit outcome that would be a row but for
     * this fold. A guard outcome whose own site folded still counts, so a site two levels under a
     * never-taken outcome folds too. A routine guard folds nothing, since a routine outcome is in no
     * finding, and neither does a guard that ran.
     *
     * A routine outcome is left out too, as server ADR 0039 has it: the agent read from the
     * bytecode that the outcome only yields a null default, only throws, or is the exception-path
     * copy of a `finally` body. [neverHitRoutineOutcomes] lists those. See ADR 0046.
     */
    fun neverHit(): List<ProbeRef> = checked { neverHitRows().filter { it.routine == RoutineKind.NONE } }

    /**
     * Every never-hit BRANCH probe that [neverHit] leaves out only because its outcome is routine,
     * each with its [ProbeRef.routine] kind, sorted as [neverHit] sorts. Server ADR 0039 counts
     * these apart and lists them only on request. See ADR 0046.
     *
     * A routine outcome of a site that folds into its method's row or its guard's row, by the rule
     * [neverHit] gives from server ADR 0031, is not listed here either: a site folds before any of
     * its outcomes can count as routine.
     */
    fun neverHitRoutineOutcomes(): List<ProbeRef> = checked { neverHitRows().filter { it.routine != RoutineKind.NONE } }

    /** Every [neverHit] row with routine outcomes still in. */
    private fun neverHitRows(): List<ProbeRef> {
        val judgement = judgeClasses()
        val routineKinds = routineKinds()
        val candidates =
            probesByKey.entries.filter { (key, probe) ->
                !probe.inline &&
                    probe.generatedBy == GeneratedBy.NONE &&
                    probe.kind != ProbeKind.OPTIONAL_ARGUMENT &&
                    (hitsByKey[key] ?: 0L) <= 0L &&
                    isNeverHitRow(probe, judgement)
            }
        val folded = foldedSiteProbes(candidates, routineKinds)
        return candidates
            .filter { (key, _) -> key !in folded }
            .map { (key, probe) -> neverHitRef(key, probe, routineOf(key, probe, routineKinds)) }
            .sortedWith(compareBy({ it.className }, { it.methodName }, { it.line }, { it.branchIndex ?: -1 }))
    }

    /**
     * The BRANCH probes among [candidates] whose site folds, under server ADR 0031, into a row that
     * already stands for it. [candidates] are every probe that is a never-hit row or routine
     * outcome by every other rule. A site folds when its method's METHOD probe in the same instance
     * is among them, or when its guard outcome is a BRANCH probe among them that is not routine.
     */
    private fun foldedSiteProbes(
        candidates: List<Map.Entry<ProbeKey, StoredProbe>>,
        routineKinds: Map<OutcomeKey, RoutineKind>,
    ): Set<ProbeKey> {
        val methodRows = HashSet<InstanceKey<NodeKey>>()
        val guardOutcomes = HashSet<OutcomeKey>()
        for ((key, probe) in candidates) {
            val method = NodeKey(probe.className, probe.methodName, probe.methodDescriptor)
            val branchIndex = probe.branchIndex
            when {
                probe.kind == ProbeKind.METHOD -> {
                    methodRows += InstanceKey(key.serviceInstanceId, method)
                }

                probe.kind == ProbeKind.BRANCH && branchIndex != null && routineOf(key, probe, routineKinds) == RoutineKind.NONE -> {
                    guardOutcomes += OutcomeKey(key.serviceInstanceId, method, branchIndex)
                }
            }
        }
        val guards = siteGuards()
        return candidates
            .filter { (key, probe) ->
                val branchIndex = probe.branchIndex
                if (probe.kind != ProbeKind.BRANCH || branchIndex == null) return@filter false
                val method = NodeKey(probe.className, probe.methodName, probe.methodDescriptor)
                val guard = guards[OutcomeKey(key.serviceInstanceId, method, branchIndex)]
                InstanceKey(key.serviceInstanceId, method) in methodRows ||
                    (guard != null && OutcomeKey(key.serviceInstanceId, method, guard) in guardOutcomes)
            }.mapTo(HashSet()) { it.key }
    }

    /**
     * The guard of each outcome's site, from the sites each instance's METHOD probes list. An
     * outcome absent from the map is in a site with no guard, or in no listed site. See ADR 0037.
     */
    private fun siteGuards(): Map<OutcomeKey, Int> {
        val guards = HashMap<OutcomeKey, Int>()
        for ((key, probe) in probesByKey) {
            if (probe.kind != ProbeKind.METHOD) continue
            val method = NodeKey(probe.className, probe.methodName, probe.methodDescriptor)
            for (site in probe.branchSites) {
                val guard = site.guard ?: continue
                for (outcome in site.outcomes) guards[OutcomeKey(key.serviceInstanceId, method, outcome.branchIndex)] = guard
            }
        }
        return guards
    }

    /**
     * The kind of every routine outcome, from the sites each instance's METHOD probes list. An
     * outcome absent from the map is not routine. See ADR 0046.
     */
    private fun routineKinds(): Map<OutcomeKey, RoutineKind> {
        val kinds = HashMap<OutcomeKey, RoutineKind>()
        for ((key, probe) in probesByKey) {
            if (probe.kind != ProbeKind.METHOD) continue
            val method = NodeKey(probe.className, probe.methodName, probe.methodDescriptor)
            for (site in probe.branchSites) {
                for (outcome in site.outcomes) {
                    if (outcome.routine == RoutineKind.NONE) continue
                    kinds[OutcomeKey(key.serviceInstanceId, method, outcome.branchIndex)] = outcome.routine
                }
            }
        }
        return kinds
    }

    /** The routine kind of [probe], a BRANCH probe of [key]'s instance, or [RoutineKind.NONE] for any other probe. */
    private fun routineOf(
        key: ProbeKey,
        probe: StoredProbe,
        routineKinds: Map<OutcomeKey, RoutineKind>,
    ): RoutineKind {
        val branchIndex = probe.branchIndex
        if (probe.kind != ProbeKind.BRANCH || branchIndex == null) return RoutineKind.NONE
        val method = NodeKey(probe.className, probe.methodName, probe.methodDescriptor)
        return routineKinds[OutcomeKey(key.serviceInstanceId, method, branchIndex)] ?: RoutineKind.NONE
    }

    /**
     * Whether a judgeable never-hit [probe] is a [neverHit] row under server ADR 0034. A `<clinit>`
     * is a class state, never a row. A constructor is a row only as an unused overload, when
     * another constructor of its class ran. A method a class finding covers is not a row, and
     * neither is a lambda body that folds into its creators. A BRANCH probe in either is not a row
     * either.
     */
    private fun isNeverHitRow(
        probe: StoredProbe,
        judgement: ClassJudgement,
    ): Boolean {
        if (NodeKey(probe.className, probe.methodName, probe.methodDescriptor) in judgement.foldedMethods) return false
        if (probe.kind != ProbeKind.METHOD) return true
        return when (probe.methodName) {
            CLASS_INIT -> false
            CONSTRUCTOR -> probe.className in judgement.constructed
            else -> true
        }
    }

    /**
     * Every class that some instance loaded, that has a judgeable static initialiser, and whose
     * static initialiser never ran, sorted by class name. Nothing used its statics and nothing
     * created an instance. A class with no static initialiser is never listed here. See server ADR
     * 0034 and CONTEXT.md, "Never initialised".
     *
     * Every method of such a class can only run through its initialiser, so [neverHit] and
     * [unreachedClusters] fold them into the class.
     */
    fun neverInitialised(): List<ClassFindingRef> = checked { classFindingRefs(ClassFinding.NEVER_INITIALISED) }

    /**
     * Every class that some instance loaded, that is not never initialised, that has a judgeable
     * constructor and a judgeable method that is neither static nor a constructor, and none of
     * whose constructors ran, sorted by class name. No instance of it or of a subclass ever existed.
     * A class with only static methods is never listed, and neither is an interface, which has no
     * constructor. See server ADR 0034 and CONTEXT.md, "Never instantiated".
     *
     * The constructors and instance methods of such a class can only run through an instance, so
     * [neverHit] and [unreachedClusters] fold them into the class. Its static methods can still run,
     * so they stay rows of their own.
     */
    fun neverInstantiated(): List<ClassFindingRef> = checked { classFindingRefs(ClassFinding.NEVER_INSTANTIATED) }

    /** The [ClassFindingRef] of each class [judgeClasses] gives [finding], sorted by class name. */
    private fun classFindingRefs(finding: ClassFinding): List<ClassFindingRef> =
        judgeClasses()
            .findings
            .filterValues { it == finding }
            .keys
            .sorted()
            .map { className ->
                ClassFindingRef(
                    className = className,
                    finding = finding,
                    methods =
                        nameIndex[className]
                            .orEmpty()
                            .mapNotNull { probesByKey[it] }
                            .filter { it.kind == ProbeKind.METHOD && it.methodName != CLASS_INIT }
                            .map { it.methodName }
                            .distinct()
                            .sorted(),
                    instancesLoading = loadedClassNamesByInstance.values.count { className in it },
                )
            }

    /**
     * Every judgeable METHOD probe location, neither inline nor generated, merged across instances
     * by class, method and descriptor.
     */
    private fun judgeableMethods(): Map<NodeKey, JudgeableMethod> =
        probesByKey.entries
            .filter { (_, probe) -> probe.kind == ProbeKind.METHOD && !probe.inline && probe.generatedBy == GeneratedBy.NONE }
            .groupBy { (_, probe) -> NodeKey(probe.className, probe.methodName, probe.methodDescriptor) }
            .mapValues { (nodeKey, entries) ->
                JudgeableMethod(
                    nodeKey,
                    entries.sumOf { (key, _) -> hitsByKey[key] ?: 0L },
                    entries.any { (_, probe) -> probe.static },
                    entries.any { (_, probe) -> probe.lambdaBody },
                )
            }

    /**
     * Every method some CREATES call edge names, with the methods whose edges name it: its
     * creators. Reads the edges of every METHOD probe and of every complete-baseline declaration.
     * A method never counts as its own creator. See ADR 0028.
     */
    private fun creatorsOf(): Map<NodeKey, Set<NodeKey>> {
        val creators = mutableMapOf<NodeKey, MutableSet<NodeKey>>()

        fun add(
            creator: NodeKey,
            edges: List<CallEdge>,
        ) {
            for (edge in edges) {
                if (edge.kind != CallEdgeKind.CREATES) continue
                val created = NodeKey(edge.className, edge.methodName, edge.methodDescriptor)
                if (created != creator) creators.getOrPut(created) { mutableSetOf() } += creator
            }
        }
        probesByKey.values
            .filter { it.kind == ProbeKind.METHOD }
            .forEach { add(NodeKey(it.className, it.methodName, it.methodDescriptor), it.calls) }
        for ((className, declared) in consultedDeclaredClasses) {
            declared.methods.forEach { add(NodeKey(className, it.methodName, it.methodDescriptor), it.calls) }
        }
        return creators
    }

    /**
     * Applies server ADR 0034's class rules to every loaded class, over [judgeableMethods]. A class
     * with a judgeable METHOD probe loaded, so none of these is never loaded.
     *
     * A class is never initialised when it has a `<clinit>` and that never ran. Otherwise it is never
     * instantiated when it has a `<init>`, none ran, and it has a method that is neither static nor
     * a constructor. A never-initialised class covers every never-hit method. A never-instantiated
     * class covers each never-hit constructor and each never-hit method that is not static.
     *
     * A never-hit lambda body then folds with its creators, since it can only run once one of them
     * ran. See [foldLambdaBodies].
     */
    private fun judgeClasses(): ClassJudgement {
        val judgeable = judgeableMethods()
        val findings = mutableMapOf<String, ClassFinding>()
        val covered = mutableMapOf<String, List<NodeKey>>()
        val constructed = mutableSetOf<String>()
        for ((className, methods) in judgeable.values.groupBy { it.key.className }) {
            val initialisers = methods.filter { it.key.methodName == CLASS_INIT }
            val constructors = methods.filter { it.key.methodName == CONSTRUCTOR }
            if (constructors.any { it.hits > 0L }) constructed += className
            val finding =
                when {
                    initialisers.isNotEmpty() && initialisers.all { it.hits == 0L } -> {
                        ClassFinding.NEVER_INITIALISED
                    }

                    constructors.isNotEmpty() &&
                        className !in constructed &&
                        methods.any { it.key.methodName != CONSTRUCTOR && it.key.methodName != CLASS_INIT && !it.static } -> {
                        ClassFinding.NEVER_INSTANTIATED
                    }

                    else -> {
                        continue
                    }
                }
            findings[className] = finding
            covered[className] =
                methods
                    .filter { method ->
                        method.hits == 0L &&
                            (
                                finding == ClassFinding.NEVER_INITIALISED ||
                                    method.key.methodName == CONSTRUCTOR ||
                                    (method.key.methodName != CLASS_INIT && !method.static)
                            )
                    }.map { it.key }
        }
        val (intoClassFindings, inNeverHitCode) = foldLambdaBodies(judgeable, findings, covered.values.flatten().toSet(), constructed)
        for (lambda in intoClassFindings) covered[lambda.className] = covered.getValue(lambda.className) + lambda
        return ClassJudgement(findings, covered, inNeverHitCode, constructed)
    }

    /**
     * Folds each judgeable never-hit lambda body whose every creator is judgeable and never hit,
     * and is itself covered by a class finding or a row of [neverHit]: a method other than
     * `<clinit>` and a lone constructor. A lambda body some method created is one or the other, so
     * nested lambda bodies fold with the outermost one. A lambda body with no known creator, or with
     * a creator that ran, never folds. See server ADR 0034.
     *
     * Returns the folded lambda bodies in two sets. The first fold into their own class's finding:
     * every creator is covered by a class finding or is in that first set. The rest fold into
     * never-hit methods that are rows of their own.
     */
    private fun foldLambdaBodies(
        judgeable: Map<NodeKey, JudgeableMethod>,
        findings: Map<String, ClassFinding>,
        covered: Set<NodeKey>,
        constructed: Set<String>,
    ): Pair<Set<NodeKey>, Set<NodeKey>> {
        val creators = creatorsOf()

        fun absorbs(creator: NodeKey): Boolean {
            if ((judgeable[creator] ?: return false).hits > 0L) return false
            if (creator in covered) return true
            return when (creator.methodName) {
                CLASS_INIT -> false
                CONSTRUCTOR -> creator.className in constructed
                else -> true
            }
        }
        val folded =
            judgeable.values
                .filter { it.lambdaBody && it.hits == 0L && it.key !in covered }
                .map { it.key }
                .filter { lambda -> creators[lambda].orEmpty().let { it.isNotEmpty() && it.all(::absorbs) } }
                .toSet()
        val intoClassFindings = mutableSetOf<NodeKey>()
        var changed = true
        while (changed) {
            changed = false
            for (lambda in folded - intoClassFindings) {
                if (lambda.className in findings && creators.getValue(lambda).all { it in covered || it in intoClassFindings }) {
                    intoClassFindings += lambda
                    changed = true
                }
            }
        }
        return intoClassFindings to (folded - intoClassFindings)
    }

    /** The [ProbeRef] [neverHit] lists for [probe]. */
    private fun neverHitRef(
        key: ProbeKey,
        probe: StoredProbe,
        routine: RoutineKind = RoutineKind.NONE,
    ): ProbeRef =
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
            branchKey = probe.branchKey,
            routine = routine,
        )

    /** Every class reported as matched but not instrumented by any manifest, distinct by class name, sorted by name. */
    fun skippedClasses(): List<SkippedClass> = checked { skippedByClassName.values.sortedBy { it.className } }

    /**
     * Whether [serviceInstanceId] has sent a delta batch with `final_flush` set, meaning its
     * shutdown hook ran. False both before that batch arrives and for an instance never seen at
     * all: this collector cannot tell the two apart, since an instance the agent never contacted
     * leaves no other trace either. See ADR 0010.
     */
    fun endedCleanly(serviceInstanceId: String): Boolean = checked { serviceInstanceId in instancesThatEndedCleanly }

    /** Every instance id that has sent a delta batch with `final_flush` set. See [endedCleanly]. */
    fun instancesEndedCleanly(): Set<String> = checked { instancesThatEndedCleanly.toSet() }

    /**
     * Class names a sweep reported as loaded but unreported, sorted. Empty until a manifest
     * carries one.
     *
     * These are classes no transformer was offered, so the agent knows only that they loaded.
     * [neverLoaded] already leaves them out; this exposes them so a test can assert the blind
     * spot itself rather than only its absence from a claim. See ADR 0027.
     */
    fun unreportedClasses(): List<String> = checked { unreportedByClassName.keys.sorted() }

    /**
     * What kind of class kotlinc says [className] is, from its manifest record or, for a class
     * that never loaded, a complete static baseline's declaration. [KotlinKind.NONE] for a class
     * with no `kotlin.Metadata`, such as a Java class. Null when no payload has named the class. A
     * report names a [KotlinKind.FILE_FACADE] or a [KotlinKind.MULTIFILE_CLASS_PART] by its
     * source file. See ADR 0041.
     */
    fun kotlinKind(className: String): KotlinKind? =
        checked { kotlinKindByClassName[className] ?: consultedDeclaredClasses[className]?.kotlinKind }

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
        checkNoRejections()
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
        checkNoRejections()
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
     * Every unreached cluster in the call graph, applying the collector's rule (ADR 0024, ADR 0039
     * and server ADR 0034) within this one test JVM. Sorted by [UnreachedCluster.membersTotal]
     * descending, then by root.
     *
     * The graph holds three kinds of node. A method node comes from a manifest METHOD probe, merged
     * across instances with hits summed, or from a non-inline declared method of a class a complete
     * static baseline declared but no manifest ever mentioned. Such a method carries
     * [ProbeRef.neverLoaded] `true`, line `-1`, and the declaring instance's id. An outcome node is
     * a BRANCH probe with no hits, merged across instances by its method and branch index, in a
     * method node with hits. A BRANCH probe that is inline or generated is never an outcome node,
     * by the rule that keeps its method out of the graph. A class node is a class that holds a
     * class finding: never loaded, [neverInitialised] or [neverInstantiated]. It stands for the
     * never-hit methods that finding covers, which are never method nodes of their own. For a
     * never-loaded class that is every method node of the class.
     *
     * A call edge whose guard names an outcome node counts as a call from that outcome node. Every
     * other edge counts as a call from its method, or from the class node that stands for it. An
     * edge into a covered method goes into its class node, and an edge between two methods one class
     * node stands for is dropped. An outcome node has one caller: the outcome node its site's guard
     * names, or else its method. Within one JVM a guard's branch index names one outcome of the
     * caller's class, so it is looked up there directly.
     *
     * A root is a never-hit node with no caller or with a caller that is a method with hits. A method
     * root with no caller is [RootKind.UNCALLED], and one with a caller that has hits is
     * [RootKind.REACHED_FROM_HIT]. An outcome root is [RootKind.UNTAKEN_OUTCOME], and a class root is
     * [RootKind.CLASS_FINDING]. A `<clinit>` is never a root. The cluster is the root plus every
     * never-hit node reachable from it whose every caller is already in the cluster. An outcome or
     * class root whose cluster holds no method or class node besides the root gives no cluster,
     * since the root's own finding already says all there is.
     *
     * Inline methods are never nodes, so an edge into one resolves to nothing. Edges are the union
     * of manifest and complete-baseline call edges. Resolution walks a callee's owner up through
     * [supertypesByClassName] and complete-baseline supertypes to the first type with a matching
     * node. For a virtual call it also walks down from the owner to every known transitive subtype
     * with one, `<init>` and `<clinit>` excepted. See [computeCallGraph].
     */
    fun unreachedClusters(): List<UnreachedCluster> {
        checkNoRejections()
        val graph = computeCallGraph()

        fun isHit(key: NodeKey) = (graph.nodes[key]?.hits ?: 0L) > 0L

        val classNodes = buildClassNodes(graph)
        val coveredBy = classNodes.flatMap { (node, info) -> info.methods.map { it to node } }.toMap()
        val outcomes = buildOutcomeNodes(::isHit)
        val clusterGraph = buildClusterGraph(graph, outcomes, coveredBy)

        fun isNeverHit(node: ClusterNode) =
            when {
                node.isClass -> node in classNodes
                node.branchIndex != null -> node in outcomes
                else -> !isHit(node.method) && node.method !in coveredBy
            }

        val neverHitNodes =
            graph.nodes.keys
                .filter { !isHit(it) && it !in coveredBy && it.methodName != CLASS_INIT }
                .map { ClusterNode(it) } + outcomes.keys + classNodes.keys
        return neverHitNodes
            .mapNotNull { node ->
                val callers = clusterGraph.callersOf[node].orEmpty()
                val hitCallers = callers.filter { it.branchIndex == null && !it.isClass && isHit(it.method) }
                if (callers.isNotEmpty() && hitCallers.isEmpty()) return@mapNotNull null
                val kind =
                    when {
                        node.isClass -> RootKind.CLASS_FINDING
                        node.branchIndex != null -> RootKind.UNTAKEN_OUTCOME
                        callers.isEmpty() -> RootKind.UNCALLED
                        else -> RootKind.REACHED_FROM_HIT
                    }
                val reachedFrom =
                    if (kind == RootKind.REACHED_FROM_HIT || kind == RootKind.CLASS_FINDING) {
                        hitCallers.map { toProbeRef(graph.nodes.getValue(it.method), it.method) }.sortedWith(probeRefComparator)
                    } else {
                        emptyList()
                    }
                buildCluster(graph, clusterGraph, outcomes, classNodes, node, kind, reachedFrom, ::isNeverHit)
            }.sortedWith(compareByDescending<UnreachedCluster> { it.membersTotal }.thenComparing({ it.root }, probeRefComparator))
    }

    /**
     * Grows [root]'s cluster by fixpoint: repeatedly add a never-hit node reachable from a current
     * member, once every one of that node's callers is itself already in the cluster. Two
     * consequences of this rule, pinned by tests: a node whose callers sit in two different
     * clusters is added to neither, and a cycle of never-hit nodes with no outside caller produces
     * no root at all, so it never reaches this method in the first place. Returns null for an
     * outcome or class root when nothing but outcome nodes joined it.
     */
    private fun buildCluster(
        graph: CallGraph,
        clusterGraph: ClusterGraph,
        outcomes: Map<ClusterNode, OutcomeNode>,
        classNodes: Map<ClusterNode, ClassNode>,
        root: ClusterNode,
        rootKind: RootKind,
        reachedFrom: List<ProbeRef>,
        isNeverHit: (ClusterNode) -> Boolean,
    ): UnreachedCluster? {
        val members = mutableSetOf(root)
        var changed = true
        while (changed) {
            changed = false
            for (member in members.toList()) {
                for (target in clusterGraph.calleesOf[member].orEmpty()) {
                    if (target in members || !isNeverHit(target)) continue
                    val callers = clusterGraph.callersOf[target].orEmpty()
                    if (callers.isNotEmpty() && members.containsAll(callers)) {
                        members += target
                        changed = true
                    }
                }
            }
        }
        if ((root.isClass || root.branchIndex != null) && members.none { it != root && it.branchIndex == null }) return null

        val methodsByClass = mutableMapOf<String, MutableList<NodeKey>>()
        for (member in members) {
            when {
                member.branchIndex != null -> {}

                member.isClass -> {
                    methodsByClass.getOrPut(member.method.className) { mutableListOf() } +=
                        classNodes.getValue(member).methods
                }

                else -> {
                    methodsByClass.getOrPut(member.method.className) { mutableListOf() } += member.method
                }
            }
        }
        val classSize =
            graph.nodes.keys
                .groupingBy { it.className }
                .eachCount()
        val wholeClasses = mutableListOf<WholeClass>()
        val memberRefs = mutableListOf<ProbeRef>()
        var neverLoadedClasses = 0
        for ((className, keys) in methodsByClass) {
            val refs =
                keys
                    .filter { it.methodName != CLASS_INIT }
                    .map { toProbeRef(graph.nodes.getValue(it), it) }
                    .sortedWith(probeRefComparator)
            if (keys.any { graph.nodes.getValue(it).neverLoaded }) neverLoadedClasses++
            if (keys.size == classSize[className]) {
                wholeClasses += WholeClass(className, classNodes[classNode(className)]?.finding, refs)
            } else {
                memberRefs += refs
            }
        }
        val rootOutcome = outcomes[root]
        val rootRef =
            when {
                root.isClass -> classRootRef(graph, root, classNodes.getValue(root))
                rootOutcome != null -> rootOutcome.ref
                else -> toProbeRef(graph.nodes.getValue(root.method), root.method)
            }
        return UnreachedCluster(
            root = rootRef,
            rootKind = rootKind,
            members = memberRefs.sortedWith(probeRefComparator),
            neverLoadedClasses = neverLoadedClasses,
            rootSite = rootOutcome?.site,
            reachedFrom = reachedFrom,
            rootFinding = classNodes[root]?.finding,
            wholeClasses = wholeClasses.sortedBy { it.className },
        )
    }

    /**
     * The [ProbeRef] a class root reports: the class alone, with an empty method name and
     * descriptor and line `0`. It is never loaded when every method its class node stands for is.
     */
    private fun classRootRef(
        graph: CallGraph,
        root: ClusterNode,
        info: ClassNode,
    ): ProbeRef {
        val methods = info.methods.map { graph.nodes.getValue(it) }
        return ProbeRef(
            serviceInstanceId = methods.first().serviceInstanceId,
            className = root.method.className,
            methodName = "",
            methodDescriptor = "",
            line = 0,
            kind = ProbeKind.METHOD,
            branchIndex = null,
            neverLoaded = methods.all { it.neverLoaded },
        )
    }

    /** The class node for [className]. */
    private fun classNode(className: String) = ClusterNode(NodeKey(className, "", ""), isClass = true)

    /**
     * Every class node, keyed by its [ClusterNode]. A never-initialised or never-instantiated class
     * stands for the methods [judgeClasses] says it covers. A class whose method nodes all come
     * from a complete baseline, since no manifest mentioned it, is never loaded and stands for all
     * of them. A class with no such method has no class node.
     */
    private fun buildClassNodes(graph: CallGraph): Map<ClusterNode, ClassNode> {
        val judgement = judgeClasses()
        val classNodes = mutableMapOf<ClusterNode, ClassNode>()
        for ((className, finding) in judgement.findings) {
            val methods = judgement.covered[className].orEmpty().filter { (graph.nodes[it]?.hits ?: -1L) == 0L }
            if (methods.isNotEmpty()) classNodes[classNode(className)] = ClassNode(finding, methods)
        }
        graph.nodes
            .filter { (key, info) -> info.neverLoaded && key.className !in judgement.findings }
            .keys
            .groupBy { it.className }
            .forEach { (className, methods) -> classNodes[classNode(className)] = ClassNode(ClassFinding.NEVER_LOADED, methods) }
        return classNodes
    }

    /**
     * Every outcome node, keyed by its [ClusterNode]: a BRANCH probe that is neither inline nor
     * generated, whose hits summed across instances are zero, in a method [isHit] says has hits. Its
     * site is looked up by branch index in the sites its method's METHOD probes list. See ADR 0039.
     *
     * A routine outcome is never a node, as server ADR 0039 has it, so a call it guards starts at
     * its method. See ADR 0046.
     */
    private fun buildOutcomeNodes(isHit: (NodeKey) -> Boolean): Map<ClusterNode, OutcomeNode> {
        val routineKinds = routineKinds()
        val sitesByMethod =
            probesByKey.values
                .filter { it.kind == ProbeKind.METHOD && it.branchSites.isNotEmpty() }
                .groupBy({ NodeKey(it.className, it.methodName, it.methodDescriptor) }, { it.branchSites })
                .mapValues { (_, lists) -> lists.flatten() }
        return probesByKey.entries
            .filter { (_, probe) ->
                probe.kind == ProbeKind.BRANCH && probe.branchIndex != null && !probe.inline && probe.generatedBy == GeneratedBy.NONE
            }.groupBy { (_, probe) -> ClusterNode(NodeKey(probe.className, probe.methodName, probe.methodDescriptor), probe.branchIndex) }
            .filter { (node, entries) ->
                isHit(node.method) &&
                    entries.sumOf { (key, _) -> hitsByKey[key] ?: 0L } == 0L &&
                    entries.all { (key, probe) -> routineOf(key, probe, routineKinds) == RoutineKind.NONE }
            }.mapValues { (node, entries) ->
                val (key, probe) = entries.first()
                val site = sitesByMethod[node.method]?.firstOrNull { site -> site.outcomes.any { it.branchIndex == node.branchIndex } }
                OutcomeNode(neverHitRef(key, probe), site)
            }
    }

    /**
     * Links every resolved call and every outcome node into the cluster graph. A call whose guard
     * names an outcome node in the caller's method starts at that outcome node, and any other call
     * starts at its method. A method [coveredBy] names is replaced by its class node at either end,
     * and a call that then starts and ends at the same node is dropped. An outcome node's one caller
     * is the outcome node its site's guard names, when that is another outcome node, and otherwise
     * its method.
     */
    private fun buildClusterGraph(
        graph: CallGraph,
        outcomes: Map<ClusterNode, OutcomeNode>,
        coveredBy: Map<NodeKey, ClusterNode>,
    ): ClusterGraph {
        val callersOf = mutableMapOf<ClusterNode, MutableSet<ClusterNode>>()
        val calleesOf = mutableMapOf<ClusterNode, MutableSet<ClusterNode>>()

        fun link(
            caller: ClusterNode,
            callee: ClusterNode,
        ) {
            callersOf.getOrPut(callee) { mutableSetOf() } += caller
            calleesOf.getOrPut(caller) { mutableSetOf() } += callee
        }

        fun nodeOf(key: NodeKey) = coveredBy[key] ?: ClusterNode(key)
        for ((caller, calls) in graph.calls) {
            for (call in calls) {
                val guardNode = call.guard?.let { ClusterNode(caller, it) }?.takeIf { it in outcomes }
                val from = guardNode ?: nodeOf(caller)
                val to = nodeOf(call.callee)
                if (from != to) link(from, to)
            }
        }
        for ((node, outcome) in outcomes) {
            val guardNode =
                outcome.site
                    ?.guard
                    ?.let { ClusterNode(node.method, it) }
                    ?.takeIf { it != node && it in outcomes }
            link(guardNode ?: ClusterNode(node.method), node)
        }
        return ClusterGraph(callersOf, calleesOf)
    }

    /**
     * Builds every [NodeKey] and resolves its edges against the known supertype graph. An edge
     * resolves to the union of two lookups, either of which may find nothing: the first node up
     * the owner's supertype chain, which is an inherited concrete declaration, and, for a virtual
     * call, every node with the same name and descriptor on a transitive subtype of the owner.
     * Widening starts at the owner, not at the declaring type, for two reasons: an abstract
     * interface method has no node anywhere, so requiring the up-walk to succeed would drop every
     * edge into a pure interface, which is the constructor-injected case supertypes exist for; and
     * a receiver typed as the owner can only be the owner or one of its subtypes, never a sibling
     * under some ancestor. Each resolved call keeps its raw edge's guard.
     * Every resolved edge into a class also implies an edge into that class's `<clinit>` node
     * when it has one, under the same guard: no bytecode ever calls `<clinit>`, the JVM runs it on
     * the class's first active use, and a resolved call into the class is exactly such a use.
     * Without this a never-initialised class's `<clinit>` would be an uncalled root of its own
     * beside the cluster that actually owns it. A call from a method to itself is dropped.
     */
    private fun computeCallGraph(): CallGraph {
        val nodes = buildNodes()
        val reverseSubtypes = buildReverseSubtypes()
        val calls = mutableMapOf<NodeKey, Set<ResolvedCall>>()
        for ((nodeKey, info) in nodes) {
            val resolved = mutableSetOf<ResolvedCall>()
            for (edge in info.edges) {
                val targets = mutableSetOf<NodeKey>()
                findDeclaringType(nodes, edge.className, edge.methodName, edge.methodDescriptor)?.let {
                    targets += NodeKey(it, edge.methodName, edge.methodDescriptor)
                }
                if (edge.virtual && edge.methodName != "<init>" && edge.methodName != "<clinit>") {
                    targets += widenToSubtypes(nodes, reverseSubtypes, edge.className, edge.methodName, edge.methodDescriptor)
                }
                for (target in targets.toList()) {
                    val typeInitializer = NodeKey(target.className, "<clinit>", "()V")
                    if (typeInitializer in nodes) targets += typeInitializer
                }
                targets -= nodeKey
                targets.mapTo(resolved) { ResolvedCall(it, edge.guard) }
            }
            calls[nodeKey] = resolved
        }
        return CallGraph(nodes, calls)
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
    ): Boolean = checked { callCount(verb, routeTemplate) > 0L }

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
        checkNoRejections()
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
        checked {
            endpointRefsByIdentity
                .filterKeys { identity -> (endpointKeysByIdentity[identity]?.sumOf { endpointHitsByKey[it] ?: 0L } ?: 0L) <= 0L }
                .values
                .sortedWith(compareBy({ it.routeTemplate }, { it.verb }))
        }

    /** Every endpoint any instance ever reported, sorted by route template then verb. */
    fun endpoints(): List<EndpointRef> = checked { endpointRefsByIdentity.values.sortedWith(compareBy({ it.routeTemplate }, { it.verb })) }

    /** Every endpoint module reported as disabled by any instance, distinct by module name, sorted by module name. */
    fun disabledEndpointModules(): List<DisabledEndpointModule> = checked { disabledEndpointModulesByName.values.sortedBy { it.module } }

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
        checkNoRejections()
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

    /**
     * What ADR 0030's rules say about the dependency carrying `groupId:artifactId`, merged across
     * every instance that listed it. A shaded jar carries several identities and matches any one of
     * them. [groupId] null or empty matches a filename-derived identity, which has no group: for
     * example `dependency(null, "commons-lang3")` for a jar with no `pom.properties`, or
     * `dependency("com.fasterxml.jackson.core", "jackson-databind")` for one with it.
     *
     * The agent sends a dependency's entry only after a confirmed delta send has carried its first
     * loaded-class count, and it holds each reference mapping to the dependency under the same
     * condition (ADR 0036). So this answers as soon as the entry has arrived: that instance's
     * counts for it, and its mappings to it, have arrived by then. [awaitDependency] waits for the
     * entry.
     *
     * Two things are not covered by that. The hits and reference sites that tell used from
     * unreached arrive like any probe data, so a test waits for them with [awaitSettled]. And a
     * dependency is judged on the instances whose entry has arrived: with several instances, one
     * that loaded the jar but whose entry has not arrived yet does not count. Call
     * [awaitDependenciesListed] first when that matters.
     *
     * Throws [UnknownDependencyException] if no manifest has listed the dependency, naming the
     * identities this collector does know.
     */
    fun dependency(
        groupId: String?,
        artifactId: String,
    ): DependencyStatus {
        checkNoRejections()
        val wanted = groupId.orEmpty() to artifactId
        val finding =
            computeDependencyReport(dependencyViews()).findings.firstOrNull { wanted in it.identities }
                ?: throw unknownDependency(wanted)
        return toDependencyStatus(finding)
    }

    /**
     * Blocks until some manifest has listed the dependency carrying `groupId:artifactId`, matched
     * the way [dependency] matches. [dependency] then answers without throwing. Throws
     * [TimeoutException] if [timeout] elapses first.
     */
    fun awaitDependency(
        groupId: String?,
        artifactId: String,
        timeout: Duration,
    ) {
        checkNoRejections()
        val wanted = groupId.orEmpty() to artifactId
        awaitUntil(timeout, "no manifest listed dependency ${wanted.first}:${wanted.second} within $timeout") {
            dependencyLocations.values.any { view -> view.identities.any { (it.groupId to it.artifactId) == wanted } }
        }
    }

    /**
     * Blocks until at least one instance has been heard from and every instance heard from has
     * sent `dependencies_listed`. The agent sets that flag once its startup listing, and every
     * reference mapping recorded before the listing ended, has reached this collector (ADR 0036).
     * [unloadedDependencies], [unreferencedDependencies], [unreachedDependencies] and
     * [absentReferences] answer only after this point. Throws [TimeoutException] if [timeout]
     * elapses first, naming the instances still waiting. An agent whose listing failed never sends
     * the flag, so this times out for it.
     */
    fun awaitDependenciesListed(timeout: Duration) {
        checkNoRejections()
        awaitUntil(timeout, { dependenciesListedTimeoutMessage(timeout) }) {
            instanceIds.isNotEmpty() && instancesWaitingForDependencyListing().isEmpty()
        }
    }

    private fun dependenciesListedTimeoutMessage(timeout: Duration): String {
        val waiting = instancesWaitingForDependencyListing()
        if (instanceIds.isEmpty()) return "no instance was heard from within $timeout"
        return "these instances did not send dependencies_listed within $timeout: ${waiting.joinToString(", ")}"
    }

    /**
     * Every dependency some instance listed from its startup classpath with no class from it
     * loaded on any instance, sorted by [DependencyStatus.identityKey]. Needs neither references
     * nor a static baseline.
     *
     * Throws [IllegalStateException] until every instance heard from has sent
     * `dependencies_listed`, and while no instance has been heard from. Before that point an empty
     * list could mean "not listed yet". Call [awaitDependenciesListed] first.
     */
    fun unloadedDependencies(): List<DependencyStatus> = checked { dependenciesWithStatus(DependencyUsage.UNLOADED, needsSplit = false) }

    /**
     * Every loaded dependency nothing in the adopter's code references, sorted by
     * [DependencyStatus.identityKey]. Often a library another library needs, or one reached only
     * through a service lookup, so this is an observation, not a verdict that the jar can go.
     *
     * Throws [IllegalStateException] unless every loaded dependency could be split into
     * unreferenced or unreached: that needs `references_recorded`, which the agent sends only with
     * `includePackages` set, and a complete static baseline (`staticBaselineEnabled=true`) from
     * every instance that lists it. An empty list without them would read as "none" when the
     * real answer is "unknown". Throws the same way as [unloadedDependencies] until every instance
     * heard from has sent `dependencies_listed`, and checks that first.
     */
    fun unreferencedDependencies(): List<DependencyStatus> =
        checked { dependenciesWithStatus(DependencyUsage.UNREFERENCED, needsSplit = true) }

    /**
     * Every dependency the adopter's code references only from methods never hit or classes never
     * loaded, sorted by [DependencyStatus.identityKey], each with those sites in
     * [DependencyStatus.sites]. Throws [IllegalStateException] under the same conditions as
     * [unreferencedDependencies].
     */
    fun unreachedDependencies(): List<DependencyStatus> = checked { dependenciesWithStatus(DependencyUsage.UNREACHED, needsSplit = true) }

    /**
     * Every referenced class no loader could find, sorted by class name, with the sites that
     * reference it: code guarded by a check for an optional library, for example.
     *
     * Throws [IllegalStateException] the same way as [unloadedDependencies] until every instance
     * heard from has sent `dependencies_listed`. The agent holds no absent reference back, but it
     * sends none until its listing ends. After that check, this throws when no instance sent
     * `references_recorded`, which the agent sends only with `includePackages` set, since an
     * empty list would then say nothing.
     */
    fun absentReferences(): List<AbsentReference> {
        checkNoRejections()
        checkDependenciesListed()
        val report = computeDependencyReport(dependencyViews())
        check(!report.referencesUnavailable) {
            "no instance sent references_recorded, so absent references are unknown: run the agent with includePackages set"
        }
        return report.absentReferences
    }

    private fun dependenciesWithStatus(
        status: DependencyUsage,
        needsSplit: Boolean,
    ): List<DependencyStatus> {
        checkDependenciesListed()
        val report = computeDependencyReport(dependencyViews())
        if (needsSplit) {
            val unsplit = report.findings.filter { it.status == DependencyUsage.NO_LIVE_REFERENCE || it.status == DependencyUsage.LOADED }
            check(!report.referencesUnavailable && unsplit.isEmpty()) {
                val which =
                    if (report.referencesUnavailable) {
                        "no instance sent references_recorded"
                    } else {
                        "these read ${DependencyUsage.NO_LIVE_REFERENCE} or ${DependencyUsage.LOADED}: " +
                            unsplit.joinToString(", ") { it.identityKey }
                    }
                "unreferenced and unreached need references_recorded (the agent's includePackages set) and a complete static " +
                    "baseline (staticBaselineEnabled=true) from every instance that lists the dependency; $which"
            }
        }
        return report.findings
            .filter { it.status == status }
            .sortedBy { it.identityKey }
            .map(::toDependencyStatus)
    }

    private fun instancesWaitingForDependencyListing(): List<String> = (instanceIds - instancesWithDependenciesListed).sorted()

    private fun checkDependenciesListed() {
        check(instanceIds.isNotEmpty()) {
            "no instance has been heard from, so its dependencies are unknown; call awaitDependenciesListed first"
        }
        val waiting = instancesWaitingForDependencyListing()
        check(waiting.isEmpty()) {
            "these instances have not sent dependencies_listed, so their dependency listing may be incomplete: " +
                "${waiting.joinToString(", ")}; call awaitDependenciesListed first"
        }
    }

    private fun unknownDependency(wanted: Pair<String, String>): UnknownDependencyException {
        val known =
            dependencyLocations.values
                .map { it.identityKey }
                .distinct()
                .sorted()
        if (known.isEmpty()) {
            return UnknownDependencyException(
                "${wanted.first}:${wanted.second}: no manifest has listed any dependency yet; the agent lists the startup " +
                    "classpath on a background thread and delivers each entry at the earliest on the flush after the listing ends; " +
                    "call awaitDependency first",
            )
        }
        return UnknownDependencyException(
            "${wanted.first}:${wanted.second}: never listed by any manifest; known dependencies: ${known.joinToString(", ")}",
        )
    }

    private fun toDependencyStatus(finding: DependencyFinding): DependencyStatus =
        DependencyStatus(
            identityKey = finding.identityKey,
            status = finding.status,
            identities =
                finding.identities.map { (groupId, artifactId) ->
                    DependencyIdentityRef(groupId, artifactId, finding.versionsByIdentity["$groupId:$artifactId"].orEmpty())
                },
            loadedClassesTotal = finding.loadedClassesTotal,
            classCount = finding.classCount,
            discoverySources = finding.discoverySources,
            sites = finding.sites,
        )

    /**
     * One [InstanceDependencyView] per instance heard from, built the way the demo's stub
     * collector builds its own, except that an unreported class counts as loaded (ADR 0027).
     */
    private fun dependencyViews(): List<InstanceDependencyView> =
        instanceIds.sorted().map { instanceId ->
            val probes = probesByKey.filterKeys { it.serviceInstanceId == instanceId }
            val methodReferences =
                probes
                    .filterValues { it.kind == ProbeKind.METHOD }
                    .map { (key, probe) ->
                        HeldReferences(
                            className = probe.className,
                            methodName = probe.methodName,
                            methodDescriptor = probe.methodDescriptor,
                            origin = ReferenceOrigin.MANIFEST_METHOD,
                            referencedClasses = probe.referencedClasses,
                            inline = probe.inline,
                            hits = hitsByKey[key] ?: 0L,
                        )
                    }
            val classReferences =
                classLevelReferences
                    .filterKeys { it.serviceInstanceId == instanceId }
                    .map { (key, referenced) ->
                        val className = classNamesByClassId[key] ?: "class_id ${key.id}"
                        HeldReferences(className, null, null, ReferenceOrigin.MANIFEST_CLASS, referenced)
                    }
            val declaredReferences =
                baselineReferences
                    .filterKeys { it.serviceInstanceId == instanceId }
                    .flatMap { (key, declared) ->
                        listOf(HeldReferences(key.id, null, null, ReferenceOrigin.BASELINE, declared.classReferences)) +
                            declared.methods.map {
                                HeldReferences(
                                    key.id,
                                    it.methodName,
                                    it.methodDescriptor,
                                    ReferenceOrigin.BASELINE,
                                    it.referencedClasses,
                                    inline = it.inline,
                                )
                            }
                    }
            val instanceScans = scans.filterKeys { it.serviceInstanceId == instanceId }.values
            InstanceDependencyView(
                instanceId = instanceId,
                referencesRecorded = instanceId in instancesRecordingReferences,
                baselineComplete = instanceScans.isNotEmpty() && instanceScans.all { it.complete },
                dependencies = dependencyLocations.filterKeys { it.serviceInstanceId == instanceId }.values.toList(),
                loadedClassesTotal = loadedClassesTotals.filterKeys { it.serviceInstanceId == instanceId }.mapKeys { it.key.id },
                externalClasses = externalClassesByName.filterKeys { it.serviceInstanceId == instanceId }.mapKeys { it.key.id },
                references = methodReferences + classReferences + declaredReferences,
                loadedClassNames = loadedClassNamesByInstance[instanceId].orEmpty().toSet(),
            )
        }

    /**
     * Why each payload this collector answered 400 on a run id was rejected, oldest first: an empty
     * run id, or a second run id under an instance id already heard from. Empty when nothing was
     * rejected. Once it is not empty, every other query and wait throws [IllegalStateException];
     * this is the one method a test that expects a rejection can still call. See the class doc.
     */
    fun rejectedPayloads(): List<String> = rejections.toList()

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
        rejectionFor("delta batch", batch.resource)?.let { return reject(exchange, it) }
        val instanceId = batch.resource.serviceInstanceId
        for (delta in batch.deltas) {
            val key = ProbeKey(instanceId, delta.classId, delta.probeIndex)
            hitsByKey.merge(key, delta.hitsTotal, ::maxOf)
        }
        for (delta in batch.endpointDeltas) {
            val key = InstanceEndpointKey(instanceId, delta.endpointId)
            endpointHitsByKey.merge(key, delta.hitsTotal, ::maxOf)
        }
        for (delta in batch.dependencyDeltas) {
            loadedClassesTotals.merge(InstanceKey(instanceId, delta.dependencyId), delta.loadedClassesTotal, ::maxOf)
        }
        if (batch.finalFlush) instancesThatEndedCleanly += instanceId
        instanceIds += instanceId
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
        rejectionFor("manifest", manifest.resource)?.let { return reject(exchange, it) }
        val instanceId = manifest.resource.serviceInstanceId
        // A class's own ClassLocation record is always staged and committed together with its
        // probe locations (see ProbeRegistry.computeManifestDeltas), so every classId this manifest
        // mentions in classLocations also has a matching probe location earlier in this same call.
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
                    location.referencedClasses,
                    location.branchKey,
                    location.branchSites,
                    location.static,
                    location.lambdaBody,
                    location.parameterNames,
                    location.genericSignature,
                    location.extensionReceiver,
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
        // An unreported class loaded and reached no transformer, so the agent has nothing to say
        // about it beyond that. Counting it as known is the whole point: without this it stays a
        // "never loaded" answer for a class that ran. See ADR 0027.
        for (unreported in manifest.unreportedClasses) {
            unreportedByClassName.putIfAbsent(unreported.className, unreported)
            dynamicallyKnownClassNames += unreported.className
        }
        for (classLocation in manifest.classLocations) {
            val className = classNamesByClassId[classLocation.classId] ?: continue
            supertypesByClassName[className] = SupertypesInfo(classLocation.superClassName, classLocation.interfaceNames)
            kotlinKindByClassName[className] = classLocation.kotlinKind
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
        storeDependencyData(manifest)
        respond(exchange, 200)
        signalAll()
    }

    /** Stores what ADR 0030's rules read from one manifest, keyed by its instance. */
    private fun storeDependencyData(manifest: ProbeManifest) {
        val instanceId = manifest.resource.serviceInstanceId
        instanceIds += instanceId
        if (manifest.referencesRecorded) instancesRecordingReferences += instanceId
        val loaded = loadedClassNamesByInstance.computeIfAbsent(instanceId) { ConcurrentHashMap.newKeySet() }
        for (location in manifest.probes) {
            loaded += location.className
            classNamesByClassId[InstanceKey(instanceId, location.classId)] = location.className
        }
        manifest.skippedClasses.forEach { loaded += it.className }
        manifest.unreportedClasses.forEach { loaded += it.className }
        for (references in manifest.classReferences) {
            classLevelReferences[InstanceKey(instanceId, references.classId)] = references.referencedClasses
        }
        for (external in manifest.externalClasses) {
            externalClassesByName[InstanceKey(instanceId, external.className)] = ExternalClassView(external.dependencyId, external.absent)
        }
        // The entries go in after the mappings and the flag goes in last. A query answers once it
        // sees an entry or the flag, and it runs outside this handler's thread, so it must never
        // see either before the rest of the same manifest.
        for (dependency in manifest.dependencies) {
            val key = InstanceKey(instanceId, dependency.dependencyId)
            dependencyLocations[key] =
                DependencyView(
                    dependencyId = dependency.dependencyId,
                    identities =
                        dependency.identities.map {
                            DependencyIdentityView(it.groupId.orEmpty(), it.artifactId, it.version.orEmpty())
                        },
                    discoverySource = dependency.discoverySource,
                    classCount = dependency.classCount,
                    location = dependency.location,
                )
        }
        if (manifest.dependenciesListed) instancesWithDependenciesListed += instanceId
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
        rejectionFor("static baseline", baseline.resource)?.let { return reject(exchange, it) }
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
                            DeclaredMethodInfo(
                                it.methodName,
                                it.methodDescriptor,
                                it.inline,
                                it.calls,
                                it.generatedBy,
                                it.referencedClasses,
                                it.static,
                                it.parameterNames,
                                it.genericSignature,
                                it.extensionReceiver,
                            )
                        },
                    superClassName = declaredClass.superClassName,
                    interfaceNames = declaredClass.interfaceNames,
                    kotlinKind = declaredClass.kotlinKind,
                )
            baselineReferences[InstanceKey(instanceId, declaredClass.className)] =
                BaselineReferences(declaredClass.referencedClasses, progress.declaredClasses.getValue(declaredClass.className).methods)
        }
        for (external in baseline.externalClasses) {
            externalClassesByName[InstanceKey(instanceId, external.className)] = ExternalClassView(external.dependencyId, external.absent)
        }
        instanceIds += instanceId
        if (!wasComplete && progress.complete) {
            consultedDeclaredNames += progress.declaredNames
            consultedAllInlineOrGeneratedNames += progress.allInlineOrGeneratedNames
            for ((className, info) in progress.declaredClasses) consultedDeclaredClasses.putIfAbsent(className, info)
            completedScans += scanKey
        }
        respond(exchange, 200)
        signalAll()
    }

    /**
     * Throws [IllegalStateException] listing every reason in [rejectedPayloads] once there is one.
     * Every public query and wait calls this first, so a test fails on a rejected payload even
     * when it never looks at [rejectedPayloads]: a query answered without that payload's data
     * could read as confirmed when it is not.
     */
    private fun checkNoRejections() {
        val reasons = rejections.toList()
        check(reasons.isEmpty()) {
            "this collector rejected ${reasons.size} payload(s), so its answers are incomplete:\n" + reasons.joinToString("\n") { "  $it" }
        }
    }

    /** Runs [query] after [checkNoRejections]. */
    private inline fun <T> checked(query: () -> T): T {
        checkNoRejections()
        return query()
    }

    /**
     * Null when a payload from [resource] may be kept, otherwise why not. The first run id heard
     * under an instance id is the only one this collector accepts for it; see the class doc.
     */
    private fun rejectionFor(
        payload: String,
        resource: ResourceAttributes,
    ): String? {
        val instanceId = resource.serviceInstanceId
        if (resource.runId.isEmpty()) return "$payload from instance $instanceId has an empty run id"
        val accepted = runIdByInstance.putIfAbsent(instanceId, resource.runId) ?: return null
        if (accepted == resource.runId) return null
        return "$payload from instance $instanceId has run id ${resource.runId}, but this collector already " +
            "accepted run id $accepted for that instance and keys on the instance alone"
    }

    /** Records [reason] in [rejectedPayloads], answers 400, and keeps nothing from the payload. */
    private fun reject(
        exchange: HttpExchange,
        reason: String,
    ) {
        rejections += reason
        respond(exchange, 400)
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
        /** A class's static initialiser, a class state and never a method row. See server ADR 0034. */
        private const val CLASS_INIT = "<clinit>"

        /** A constructor's method name. */
        private const val CONSTRUCTOR = "<init>"

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
 * for a [ProbeKind.BRANCH] probe as the mark of the method it sits in, and for an
 * [ProbeKind.OPTIONAL_ARGUMENT] probe as its target's mark; see ADR 0026.
 * [branchKey] is set only for a [ProbeKind.BRANCH] probe: an opaque lowercase hex token naming
 * this outcome across builds and instances, null when the agent could not name it safely. See
 * ADR 0031. [routine] is set only for a [ProbeKind.BRANCH] probe whose outcome the agent marked
 * routine; see ADR 0046.
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
    val branchKey: String? = null,
    val routine: RoutineKind = RoutineKind.NONE,
)

/**
 * Which of the four root shapes ADR 0024, ADR 0039 and server ADR 0034 distinguish an
 * [UnreachedCluster] by. They call for different fixes, so they are reported apart.
 */
enum class RootKind {
    /**
     * The root is a method, and at least one of its in-scope callers is a method with hits. The
     * caller ran and its call was not behind an untaken outcome. That is mostly an override a
     * virtual call never reached, or a call an exception cut short. [UnreachedCluster.reachedFrom]
     * names the callers.
     */
    REACHED_FROM_HIT,

    /** The root is a method with no in-scope caller at all. Its caller may not exist yet, or may live outside scope. */
    UNCALLED,

    /**
     * The root is a branch outcome that was never hit, in a method with hits. Every method in the
     * cluster runs only through it, so deleting that side of the branch removes the cluster.
     */
    UNTAKEN_OUTCOME,

    /**
     * The root is a class that holds a class finding, named by [UnreachedCluster.rootFinding]. It has
     * no in-scope caller, or at least one caller is a method with hits, which
     * [UnreachedCluster.reachedFrom] names. Such a cluster is listed only when it holds more than
     * the methods the finding folds, since [YukonTestCollector.neverLoaded],
     * [YukonTestCollector.neverInitialised] or [YukonTestCollector.neverInstantiated] already lists
     * the class.
     */
    CLASS_FINDING,
}

/**
 * A finding about a whole class rather than a method in it. A class holds at most one, the
 * strongest that applies, in the order listed. See server ADR 0034 and CONTEXT.md, "Class finding".
 */
enum class ClassFinding {
    /** A complete static baseline declared the class and no manifest ever mentioned it. See [YukonTestCollector.neverLoaded]. */
    NEVER_LOADED,

    /** The class loaded and its static initialiser never ran. See [YukonTestCollector.neverInitialised]. */
    NEVER_INITIALISED,

    /** The class loaded, has instance methods, and none of its constructors ran. See [YukonTestCollector.neverInstantiated]. */
    NEVER_INSTANTIATED,
}

/**
 * One class that holds a [finding], as [YukonTestCollector.neverInitialised] or
 * [YukonTestCollector.neverInstantiated] lists it. [methods] names the class's METHOD probes, one
 * entry per method name, sorted. It includes constructors as `<init>`, and inline and generated
 * methods, but never `<clinit>`, which is a class state. [instancesLoading] counts the instances
 * that loaded the class.
 */
data class ClassFindingRef(
    val className: String,
    val finding: ClassFinding,
    val methods: List<String>,
    val instancesLoading: Int,
)

/**
 * One class an [UnreachedCluster] holds whole: every method node of the class, its `<clinit>`
 * included when it has one, is in the cluster. [finding] is the class's finding when it holds one,
 * and null otherwise. [methods] lists its methods other than `<clinit>`, sorted the same way
 * [YukonTestCollector.neverHit] sorts its results.
 */
data class WholeClass(
    val className: String,
    val finding: ClassFinding?,
    val methods: List<ProbeRef>,
)

/**
 * A root plus every never-hit method reachable from it through call edges whose every in-scope
 * caller is itself in the cluster, as found by [YukonTestCollector.unreachedClusters]. Deleting
 * [root] removes the whole cluster. See ADR 0024, ADR 0039, server ADR 0034 and CONTEXT.md,
 * "Unreached cluster".
 *
 * [root] is a [ProbeKind.METHOD] ref for a method root. For a [RootKind.UNTAKEN_OUTCOME] root it
 * is the outcome's [ProbeKind.BRANCH] ref, the same ref [YukonTestCollector.neverHit] lists for
 * it, whose class and method name the method that holds the outcome. For a
 * [RootKind.CLASS_FINDING] root it names only the class: its method name and descriptor are empty,
 * its line is `0`, and [ProbeRef.neverLoaded] is true for a never-loaded class. [rootFinding] is
 * then the class's finding, and it is null for every other kind.
 *
 * [wholeClasses] lists each class the cluster holds whole, sorted by class name. [members] lists
 * every other method, sorted the same way [YukonTestCollector.neverHit] sorts its results. Neither
 * lists `<clinit>`. A method root is in its own cluster, and so are a class root's methods; an
 * outcome root is not. [membersTotal] counts every method the cluster holds, and [methods] lists
 * them all. [neverLoadedClasses] counts the distinct classes with a method in the cluster that
 * exists only because a complete static baseline declared it; see [ProbeRef.neverLoaded].
 *
 * [rootSite] is set only for a [RootKind.UNTAKEN_OUTCOME] root: the site whose outcomes include
 * the root's [ProbeRef.branchIndex], with its condition and each outcome's role and guarded lines,
 * as the method's METHOD probe listed it. It is null when no manifest listed the site.
 *
 * [reachedFrom] is set for a [RootKind.REACHED_FROM_HIT] root, and for a [RootKind.CLASS_FINDING]
 * root that a method with hits calls: the methods with hits that call it, sorted the same way as
 * [members]. It is empty for every other root.
 */
data class UnreachedCluster(
    val root: ProbeRef,
    val rootKind: RootKind,
    val members: List<ProbeRef>,
    val neverLoadedClasses: Int,
    val rootSite: BranchSite? = null,
    val reachedFrom: List<ProbeRef> = emptyList(),
    val rootFinding: ClassFinding? = null,
    val wholeClasses: List<WholeClass> = emptyList(),
) {
    /** How many methods the cluster holds: [members] plus the methods of [wholeClasses]. */
    val membersTotal: Int get() = members.size + wholeClasses.sumOf { it.methods.size }

    /** Every method the cluster holds, [members] and the methods of [wholeClasses], sorted the same way as [members]. */
    val methods: List<ProbeRef>
        get() =
            (members + wholeClasses.flatMap { it.methods }).sortedWith(
                compareBy({ it.className }, { it.methodName }, { it.line }, { it.branchIndex ?: -1 }),
            )
}

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
