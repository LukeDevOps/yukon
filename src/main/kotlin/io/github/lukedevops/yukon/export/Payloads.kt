package io.github.lukedevops.yukon.export

/**
 * The two payload shapes the agent pushes to the collector.
 *
 * A delta batch is small and frequent, and carries hit counts. A probe
 * manifest is sent once per (service, version). It lets the collector
 * resolve probe IDs to source locations, without the agent repeating that
 * metadata on every flush.
 */
enum class ProbeKind { METHOD, BRANCH }

data class ResourceAttributes(
    val serviceName: String,
    val serviceVersion: String?,
    val serviceInstanceId: String,
    val environment: String?,
)

/**
 * [hitsTotal] is a cumulative count from process start, not the count since
 * the last flush. A collector merges it with max() across retries and
 * reordering, so a re-delivered or reordered value cannot double-count.
 *
 * [firstSeenAt] is stamped by the first flush that observed a non-zero count,
 * not by the hit itself; the hot path reads no clock. Its precision is one
 * flush interval.
 */
data class ProbeDelta(
    val classId: Int,
    val probeIndex: Int,
    val kind: ProbeKind,
    val firstSeenAt: Long,
    val hitsTotal: Long,
)

data class DeltaBatch(
    val resource: ResourceAttributes,
    val deltas: List<ProbeDelta>,
    val endpointDeltas: List<EndpointDelta> = emptyList(),
)

data class ProbeLocation(
    val classId: Int,
    val probeIndex: Int,
    val kind: ProbeKind,
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val line: Int,
    val branchIndex: Int?,
)

/** A class the agent matched but could not instrument. It never gets a classId or any probes. */
data class SkippedClass(
    val className: String,
    val reason: String,
    val skippedAt: Long,
)

/**
 * [serviceInstanceId] is required, unlike the rest of this payload's (service, version) scoping.
 * `class_id` is assigned independently by each instance's own registry, in whatever order that
 * process's own classes happen to load, so the same `class_id` can mean a different class in two
 * instances of the same (service, version). A collector correlating manifests or delta batches
 * across instances needs an instance to key on to avoid attributing one instance's probe
 * metadata, or hit count, to the wrong class from another instance.
 */
data class ProbeManifest(
    val serviceName: String,
    val serviceVersion: String?,
    val probes: List<ProbeLocation>,
    val skippedClasses: List<SkippedClass> = emptyList(),
    val serviceInstanceId: String = "",
    val endpoints: List<EndpointLocation> = emptyList(),
    val disabledEndpointModules: List<DisabledEndpointModule> = emptyList(),
)

/** No line field, unlike [ProbeLocation]: method-level probes never carry a real line number today. */
data class DeclaredMethod(
    val methodName: String,
    val methodDescriptor: String,
)

data class DeclaredClass(
    val className: String,
    val methods: List<DeclaredMethod>,
)

/**
 * A class the static scanner found on the classpath but judged unsafe to instrument without
 * loading it. Kept separate from [DeclaredClass] so it is never conflated with a confidently-dead
 * class.
 */
data class StaticallyUnsafeClass(
    val className: String,
    val reason: String,
)

/**
 * A class file the scanner found but could not read. [className] is best-effort, derived from its
 * path within the classpath root rather than from parsed bytecode.
 */
data class UnreadableClass(
    val className: String,
    val reason: String,
)

/**
 * An in-scope class with no concrete method to put a probe in: an interface with only abstract
 * methods, an annotation type. The agent never registers such a class dynamically, so it can
 * never appear in a [ProbeManifest]; a collector must not read that absence as "never loaded".
 */
data class UnprobedClass(
    val className: String,
    val reason: String,
)

/**
 * Sent once per process, independent of [ProbeManifest]: a load-independent inventory of what
 * exists on the classpath under `includePackages`, built by reading bytecode directly rather than
 * waiting for the JVM to load it.
 *
 * One scan may be delivered as several of these. Every chunk of the same scan carries the same
 * [resource] and [scannedAt]; [chunkIndex] (0-based) and [chunkCount] say which part this is and
 * how many to expect. A collector should only diff a scan once it holds every chunk.
 */
data class StaticBaseline(
    val resource: ResourceAttributes,
    val declaredClasses: List<DeclaredClass>,
    val staticallyUnsafeClasses: List<StaticallyUnsafeClass> = emptyList(),
    val unreadableClasses: List<UnreadableClass> = emptyList(),
    val unprobedClasses: List<UnprobedClass> = emptyList(),
    val scannedAt: Long,
    val chunkIndex: Int = 0,
    val chunkCount: Int = 1,
)

/** How the agent learned of an endpoint. See CONTEXT.md, "Discovery source". */
enum class EndpointDiscoverySource { REGISTRATION, DISPATCH }

/**
 * One endpoint the framework serves. [endpointId] is per instance, like `classId`; cross-instance
 * identity is ([verb], [routeTemplate]), never [endpointId].
 *
 * A record may be re-sent when [handlerClass]/[handlerMethod]/[handlerDescriptor] are learned or
 * change after the endpoint was first reported; a collector upserts by (service instance,
 * [endpointId]). [handlerClass] alone is set when only the handler object's class is known; all
 * three are set when the framework hands over a method.
 */
data class EndpointLocation(
    val endpointId: Int,
    val verb: String,
    val routeTemplate: String,
    val verbatimTemplate: String,
    val framework: String,
    val discoverySource: EndpointDiscoverySource,
    val handlerClass: String? = null,
    val handlerMethod: String? = null,
    val handlerDescriptor: String? = null,
)

/**
 * [hitsTotal] and [firstSeenAt] carry the same cumulative, max()-merged semantics as
 * [ProbeDelta.hitsTotal] and [ProbeDelta.firstSeenAt].
 */
data class EndpointDelta(
    val endpointId: Int,
    val firstSeenAt: Long,
    val hitsTotal: Long,
)

/**
 * An endpoint module that switched itself off, typically on a linkage failure against an
 * unexpected framework version. Reported so a collector can tell "no endpoints" from "endpoints
 * not instrumented", the same reason [SkippedClass] exists for classes.
 */
data class DisabledEndpointModule(
    val module: String,
    val reason: String,
    val disabledAt: Long,
)
