package dev.otherlode.registry

import dev.otherlode.export.BranchSite
import dev.otherlode.export.CallEdge
import dev.otherlode.export.GeneratedBy
import dev.otherlode.export.ProbeKind

/**
 * Source-location metadata for one probe slot, used only to build the manifest payload.
 *
 * [inline] marks a probe belonging to a Kotlin inline function, or a branch inside one: a Kotlin
 * caller copies the body into the call site instead of invoking this method, so a zero hit total
 * is not evidence the code never ran.
 *
 * [parameterIndex], [parameterName], and [overridable] apply only to an [ProbeKind.OPTIONAL_ARGUMENT]
 * probe. [methodName] and [methodDescriptor] on such a probe name the target function the
 * parameter belongs to, not the synthetic `$default` method or the Scala default getter the probe
 * actually sits in, and [inline] is the target's own inline flag. [line] on such a probe is the
 * line of the parameter's default value, not the target's line.
 *
 * [targetClassName] is set only for an [ProbeKind.OPTIONAL_ARGUMENT] probe whose target lives in a
 * different class from the probe's own, the cross-class shape a Scala constructor default getter
 * takes. Null whenever the target is in the probe's own class.
 *
 * [calls] is populated only for a [ProbeKind.METHOD] probe: the in-scope call edges read from
 * that method's own bytecode at transform time.
 *
 * [inlinedFromClassName] is set only for a [ProbeKind.BRANCH] probe that is a kept inlined copy:
 * a site inside code kotlinc copied from an inline function's body into this probe's own method,
 * whose origin class is in scope. Dotted, or null when the probe is the class's own code.
 *
 * [generatedBy] is set for a [ProbeKind.METHOD] probe, for a [ProbeKind.BRANCH] probe as the
 * mark of the method it sits in, and for a [ProbeKind.OPTIONAL_ARGUMENT] probe as its target's
 * mark. See [GeneratedBy].
 *
 * [referencedClasses] is populated only for a [ProbeKind.METHOD] probe: the out-of-scope classes
 * that method's bytecode references, dotted, with JDK classes and classes read from a classpath
 * directory already dropped.
 *
 * [branchKey] is set only for a [ProbeKind.BRANCH] probe: an opaque lowercase hex token naming
 * this outcome across builds and instances, compared only for equality. Null when the agent
 * cannot name the outcome safely.
 *
 * [lambdaBody] is set only for a [ProbeKind.METHOD] probe whose method is a lambda body. See
 * [dev.otherlode.export.ProbeLocation.lambdaBody].
 *
 * [branchSites] is set only for a [ProbeKind.METHOD] probe: the method's kept branch sites, in
 * site index order. [siteIndex] is set only for a [ProbeKind.BRANCH] probe and names its site. See
 * [dev.otherlode.export.ProbeLocation.branchSites].
 *
 * [static] is set only for a [ProbeKind.METHOD] probe whose method has `ACC_STATIC`. It is false
 * for a constructor and for the type initializer's probe. See
 * [dev.otherlode.export.ProbeLocation.static].
 *
 * [parameterNames], [genericSignature] and [extensionReceiver] are set only for a
 * [ProbeKind.METHOD] probe, and are empty or false for the type initializer's probe. See
 * [dev.otherlode.export.ProbeLocation.parameterNames].
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
    val referencedClasses: List<String> = emptyList(),
    val branchKey: String? = null,
    val lambdaBody: Boolean = false,
    val branchSites: List<BranchSite> = emptyList(),
    val siteIndex: Int? = null,
    val static: Boolean = false,
    val parameterNames: List<String> = emptyList(),
    val genericSignature: String = "",
    val extensionReceiver: Boolean = false,
)
