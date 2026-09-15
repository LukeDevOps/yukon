package io.github.lukedevops.yukon.registry

import io.github.lukedevops.yukon.export.ProbeKind

/**
 * Source-location metadata for one probe slot, used only to build the manifest payload.
 *
 * [inline] marks a probe belonging to a Kotlin inline function, or a branch inside one: a Kotlin
 * caller copies the body into the call site instead of invoking this method, so a zero hit total
 * is not evidence the code never ran. See ADR 0022.
 */
data class ProbeMeta(
    val kind: ProbeKind,
    val methodName: String,
    val methodDescriptor: String,
    val line: Int,
    val branchIndex: Int? = null,
    val inline: Boolean = false,
)
