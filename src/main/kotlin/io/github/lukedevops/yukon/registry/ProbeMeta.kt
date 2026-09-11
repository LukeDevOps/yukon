package io.github.lukedevops.yukon.registry

import io.github.lukedevops.yukon.export.ProbeKind

/** Source-location metadata for one probe slot, used only to build the manifest payload. */
data class ProbeMeta(
    val kind: ProbeKind,
    val methodName: String,
    val methodDescriptor: String,
    val line: Int,
    val branchIndex: Int? = null,
)
