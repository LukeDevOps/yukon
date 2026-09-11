package io.github.lukedevops.yukon.export

/**
 * The two payload shapes the agent pushes to the collector: frequent, small
 * delta batches of hit counts, and a probe manifest sent once per (service,
 * version) so the collector can resolve probe IDs to source locations
 * without the agent repeating that metadata on every flush.
 */
enum class ProbeKind { METHOD, BRANCH }

data class ResourceAttributes(
    val serviceName: String,
    val serviceVersion: String?,
    val serviceInstanceId: String,
    val environment: String?,
)

data class ProbeDelta(
    val classId: Int,
    val probeIndex: Int,
    val kind: ProbeKind,
    val firstSeenAt: Long,
    val hitsSinceLastFlush: Long,
)

data class DeltaBatch(
    val resource: ResourceAttributes,
    val deltas: List<ProbeDelta>,
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

data class ProbeManifest(
    val serviceName: String,
    val serviceVersion: String?,
    val probes: List<ProbeLocation>,
)
