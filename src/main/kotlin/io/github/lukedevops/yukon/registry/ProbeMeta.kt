package io.github.lukedevops.yukon.registry

import io.github.lukedevops.yukon.export.CallEdge
import io.github.lukedevops.yukon.export.GeneratedBy
import io.github.lukedevops.yukon.export.ProbeKind

/**
 * Source-location metadata for one probe slot, used only to build the manifest payload.
 *
 * [inline] marks a probe belonging to a Kotlin inline function, or a branch inside one: a Kotlin
 * caller copies the body into the call site instead of invoking this method, so a zero hit total
 * is not evidence the code never ran. See ADR 0022.
 *
 * [parameterIndex], [parameterName], and [overridable] apply only to an [ProbeKind.OPTIONAL_ARGUMENT]
 * probe. [methodName] and [methodDescriptor] on such a probe name the target function the
 * parameter belongs to, not the synthetic `$default` method the probe actually sits in; [line] is
 * the target's first line, and [inline] is the target's own inline flag. See ADR 0021.
 *
 * [targetClassName] is set only for an [ProbeKind.OPTIONAL_ARGUMENT] probe whose target lives in a
 * different class from the probe's own, the cross-class shape a Scala constructor default getter
 * takes. Null whenever the target is in the probe's own class. See ADR 0023.
 *
 * [calls] is populated only for a [ProbeKind.METHOD] probe: the in-scope call edges read from
 * that method's own bytecode at transform time. See ADR 0024.
 *
 * [inlinedFromClassName] is set only for a [ProbeKind.BRANCH] probe that is a kept inlined copy:
 * a site inside code kotlinc copied from an inline function's body into this probe's own method,
 * whose origin class is in scope. Dotted, or null when the probe is the class's own code. See
 * ADR 0025.
 *
 * [generatedBy] is set for a [ProbeKind.METHOD] probe, and for a [ProbeKind.OPTIONAL_ARGUMENT]
 * probe as its target's mark; a branch probe never carries it. See [GeneratedBy] and ADR 0026.
 */
data class ProbeMeta(
    val kind: ProbeKind,
    val methodName: String,
    val methodDescriptor: String,
    val line: Int,
    val branchIndex: Int? = null,
    val inline: Boolean = false,
    val parameterIndex: Int? = null,
    val parameterName: String? = null,
    val overridable: Boolean = false,
    val targetClassName: String? = null,
    val calls: List<CallEdge> = emptyList(),
    val inlinedFromClassName: String? = null,
    val generatedBy: GeneratedBy = GeneratedBy.NONE,
)
