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

/** A class the agent matched but could not instrument. It never gets a classId or any probes. */
data class SkippedClass(
    val className: String,
    val reason: String,
    val skippedAt: Long,
)

data class ProbeManifest(
    val serviceName: String,
    val serviceVersion: String?,
    val probes: List<ProbeLocation>,
    val skippedClasses: List<SkippedClass> = emptyList(),
)
