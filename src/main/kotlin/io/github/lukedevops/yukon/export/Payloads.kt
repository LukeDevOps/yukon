package io.github.lukedevops.yukon.export

import io.github.lukedevops.yukon.config.AgentConfig
import java.util.UUID

/**
 * The two payload shapes the agent pushes to the collector.
 *
 * A delta batch is small and frequent, and carries hit counts. A probe
 * manifest is sent once per (service, version). It lets the collector
 * resolve probe IDs to source locations, without the agent repeating that
 * metadata on every flush.
 *
 * [OPTIONAL_ARGUMENT] counts one omission of a Kotlin optional parameter,
 * incremented in the compiler's `$default` method rather than the target
 * function itself. See ADR 0021.
 */
enum class ProbeKind { METHOD, BRANCH, OPTIONAL_ARGUMENT }

/**
 * What compiled a method into existence rather than the adopter writing its body, from bytecode
 * shape alone: an enum's `values`/`valueOf`/`getEntries`, a data class's `componentN` and `copy`
 * and whichever of `equals`/`hashCode`/`toString` the adopter did not override (the generated ones
 * have no line-number table), a `$DefaultImpls` method that only forwards to the interface's own
 * default method, a Java record's `equals`/`hashCode`/`toString`, or an overload `@JvmOverloads`
 * adds, whose body only forwards to its own class's `$default` twin, or a function of a multi-file
 * facade, whose body only forwards to the same function on a part class (ADR 0041). Set on a [ProbeKind.METHOD]
 * probe and a [DeclaredMethod], on a [ProbeKind.BRANCH] probe as the mark of the method it sits in,
 * and on a [ProbeKind.OPTIONAL_ARGUMENT] probe as its target's own mark. A collector leaves a
 * generated probe out of never-hit, stale-hit, the call graph and the two optional-parameter
 * findings by default: the compiler will emit the method again regardless of what the adopter
 * does, so a zero hit count is not a finding the adopter can act on. The hit count itself is still
 * kept and counted, since a call to a generated method, such as `copy`, is still evidence of use.
 * See ADR 0026.
 */
enum class GeneratedBy { NONE, ENUM, DATA_CLASS, DEFAULT_IMPLS, RECORD, JVM_OVERLOADS, MULTIFILE_FACADE }

/**
 * What kind of class kotlinc says a class is: the `k` element of its `kotlin.Metadata`, the one
 * int the agent reads from that annotation. The entries are in `k` order, so an entry's ordinal is
 * its `k`. A consumer names a [FILE_FACADE] or a [MULTIFILE_CLASS_PART] by its source file, not its
 * JVM name. See ADR 0041.
 */
enum class KotlinKind {
    /** The class has no `kotlin.Metadata`, such as a Java or Scala class, or its `k` is outside 1 to 5. */
    NONE,

    /** A class, interface, object, enum or annotation class, or a companion. */
    KOTLIN_CLASS,

    /** The class kotlinc makes for one source file's top-level functions and properties. */
    FILE_FACADE,

    /** A class kotlinc makes that has no Kotlin declaration of its own, such as a lambda class or a `$DefaultImpls` class. */
    SYNTHETIC_CLASS,

    /** The class `@file:JvmMultifileClass` makes. It holds only forwarders to its parts. */
    MULTIFILE_CLASS_FACADE,

    /** One file's part of a multi-file facade, which holds that file's code. */
    MULTIFILE_CLASS_PART,
    ;

    companion object {
        /**
         * The kind for a `kotlin.Metadata` whose `k` element is [k]. `kotlin.Metadata` declares
         * `k` with a default of 1, so a null [k] (an annotation without the element) is
         * [KOTLIN_CLASS]. Any value outside 1 to 5 is [NONE], since the annotation's own
         * documentation says a class file of an unlisted kind is read as not Kotlin's.
         */
        fun ofMetadataKind(k: Int?): KotlinKind =
            when (k) {
                null -> KOTLIN_CLASS
                in 1..5 -> entries[k]
                else -> NONE
            }
    }
}

/**
 * Who sent a payload. [DeltaBatch], [ProbeManifest] and [StaticBaseline] each carry one, and one
 * process stamps the same value on all three.
 *
 * [runId] is a random id the agent makes once per process at startup, in [forNewRun]. It names one
 * run of one instance. A new process always gets a new one, even when [serviceInstanceId] is pinned
 * to a name that survives a restart, so a consumer can keep each run's class ids and totals apart.
 * See ADR 0032.
 *
 * [serviceNamespace] is the group the service belongs to, as OpenTelemetry's `service.namespace`.
 * Null means the unspecified namespace. It comes last, with a default, so that a caller that
 * passes the other values by position cannot shift them. See ADR 0045.
 */
data class ResourceAttributes(
    val serviceName: String,
    val serviceVersion: String?,
    val serviceInstanceId: String,
    val environment: String?,
    val runId: String,
    val serviceNamespace: String? = null,
) {
    companion object {
        /** [config]'s identity with a new random [runId]. The agent calls this once per process. */
        fun forNewRun(config: AgentConfig): ResourceAttributes =
            ResourceAttributes(
                serviceName = config.serviceName,
                serviceVersion = config.serviceVersion,
                serviceInstanceId = config.serviceInstanceId,
                environment = config.environment,
                runId = UUID.randomUUID().toString(),
                serviceNamespace = config.serviceNamespace,
            )
    }
}

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

/**
 * [finalFlush] is set on every delta batch the agent's shutdown hook sends, never on a scheduled
 * flush. A collector uses it to tell an instance that ended cleanly from one that went silent,
 * and may report how many instances stopped without one, since up to one flush interval of their
 * hits may be missing. See ADR 0010.
 *
 * [dependencyDeltas] carries one entry per dependency whose loaded-class total changed since the
 * last successfully delivered batch. See [DependencyDelta].
 */
data class DeltaBatch(
    val resource: ResourceAttributes,
    val deltas: List<ProbeDelta>,
    val endpointDeltas: List<EndpointDelta> = emptyList(),
    val finalFlush: Boolean = false,
    val dependencyDeltas: List<DependencyDelta> = emptyList(),
)

/**
 * [inline] marks a probe belonging to a Kotlin inline function, or a branch inside one: a Kotlin
 * caller copies the body into the call site instead of invoking this method, so a zero hit total
 * is not evidence the code never ran. See ADR 0022.
 *
 * [parameterIndex], [parameterName], and [overridable] are set only for an
 * [ProbeKind.OPTIONAL_ARGUMENT] probe. A collector claims "never supplied" (every caller took the
 * default) only when [overridable] is false, since an overridable target's omissions are spread
 * across whichever override actually ran, which this location cannot relate; it claims "always
 * supplied" (the default is dead) for any target. Neither claim is made when [inline] is true. See
 * ADR 0021.
 *
 * [targetClassName] is set only for an [ProbeKind.OPTIONAL_ARGUMENT] probe whose target lives in
 * another class: a Scala constructor getter declared on a companion module class, whose target
 * constructor lives on the class the module compiles for. It is null when the target is in the
 * probe's own class, and always null for Kotlin. A collector joins an omission probe to its
 * target's METHOD probe by [targetClassName] when set, otherwise by [className]. See ADR 0023.
 *
 * [calls] is set only for a [ProbeKind.METHOD] probe: the in-scope call edges read from that
 * method's own bytecode at transform time. A pass-through's callees are attributed to whatever
 * probed method referenced it, so they never appear under the pass-through's own name. See ADR
 * 0024.
 *
 * [inlinedFromClassName] is set only for a [ProbeKind.BRANCH] probe that is a kept inlined copy:
 * a site inside code kotlinc copied from an inline function's body into this probe's own method,
 * whose origin class is in scope. Dotted, or null when the probe is the class's own code. See
 * ADR 0025.
 *
 * [generatedBy] is set for a [ProbeKind.METHOD] probe, for a [ProbeKind.BRANCH] probe as the
 * mark of the method it sits in, and for a [ProbeKind.OPTIONAL_ARGUMENT] probe as its target's
 * mark. See [GeneratedBy] and ADR 0026.
 *
 * [referencedClasses] is set only for a [ProbeKind.METHOD] probe: the out-of-scope classes this
 * method's own bytecode references, dotted. A reference is wider than a call edge, since
 * runtime-visible annotations, casts and type tests, types in a descriptor or generic signature,
 * catch types and class literals all count. Classes the
 * bootstrap or platform loader provides, and classes read from a directory on the classpath, are
 * never listed. A name's [ExternalClass] entry may arrive on a later manifest than the name itself,
 * since it is resolved only once the startup listing has finished; a name that never gets one
 * belongs to no dependency (a jar of the adopter's own, an agent jar) and a collector ignores it.
 * See ADR 0030.
 *
 * [branchKey] is set only for a [ProbeKind.BRANCH] probe: an opaque lowercase hex token naming
 * this outcome across builds and instances, compared only for equality. Null when the agent
 * cannot name the outcome safely. See ADR 0031.
 *
 * [lambdaBody] is set only for a [ProbeKind.METHOD] probe. It is true when an `invokedynamic` in
 * the method's own class names it as the `LambdaMetafactory` implementation, directly or through
 * the boxing forwarder scalac puts in between, and its name is one a compiler gives a body the
 * source never named. A named method passed by reference is not a lambda body. See
 * [io.github.lukedevops.yukon.instrumentation.TypeMatchPolicy.isLambdaBodyName] and ADR 0034.
 *
 * [branchSites] is set only for a [ProbeKind.METHOD] probe: the method's kept branch sites, in
 * [BranchSite.siteIndex] order. A dropped site is not listed, and the type initializer's probe
 * lists none. See ADR 0037.
 *
 * [siteIndex] is set only for a [ProbeKind.BRANCH] probe. It names the site this outcome belongs
 * to, which the METHOD probe of the same method lists in [branchSites]. See ADR 0037.
 *
 * [static] is set only for a [ProbeKind.METHOD] probe. It is true when the method has
 * `ACC_STATIC`, and false for a constructor and for the type initializer's probe, whose meaning a
 * consumer takes from its name. See ADR 0040.
 *
 * [parameterNames], [genericSignature] and [extensionReceiver] are set only for a
 * [ProbeKind.METHOD] probe, and are empty or false for the type initializer's probe.
 * [parameterNames] holds one name per descriptor parameter, in order, from the `MethodParameters`
 * attribute when it names every parameter, and otherwise from the LocalVariableTable. It is empty
 * when the class file names none or only some of them. [genericSignature] is the method's
 * `Signature` attribute as written, or empty. [extensionReceiver] is true when the first name
 * starts with `$this$` or is `$receiver`. See ADR 0043.
 */
data class ProbeLocation(
    val classId: Int,
    val probeIndex: Int,
    val kind: ProbeKind,
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val line: Int,
    val branchIndex: Int?,
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

/** What one outcome of a [BranchSite] is within its site. See ADR 0037. */
enum class BranchRole {
    /** A conditional's taken jump. */
    TAKEN,

    /** A conditional's fall-through: the jump's own test was false. */
    FALL_THROUGH,

    /** One case entry of a switch. */
    CASE,

    /** A switch's default, which a value with no case entry of its own also reaches. */
    DEFAULT,
}

/**
 * A run of consecutive source lines in one file, both ends inclusive. [sourceFile] is a file name
 * as the class's `SourceFile` attribute or its SMAP names it, never a path, and empty when the
 * class has no `SourceFile`. See ADR 0037.
 */
data class LineRange(
    val sourceFile: String,
    val firstLine: Int,
    val lastLine: Int,
)

/**
 * One outcome of a [BranchSite]. [branchIndex] is the same value the outcome's BRANCH probe carries
 * in a manifest. [caseKey] is set only for [BranchRole.CASE]: the case key value as the switch
 * instruction names it. It is null for a case when the agent could not read the switch's case keys.
 *
 * [guardedLines] are the lines that run only through this outcome: the outcome's edge dominates
 * every instruction the method places on them. [partlyGuardedLines] are the lines where it
 * dominates some of those instructions and not all. A line inside an in-scope inlined copy is
 * named at its origin line in the origin's own file, and a line copied from out-of-scope code is
 * left out. Both lists are empty when the outcome guards nothing. See ADR 0037.
 *
 * [caseLabel] is set only for a [BranchRole.CASE] of a switch the agent read back from a string or
 * enum lowering: the label the source names, as one part. An enum constant, a class pattern, an
 * integer or `null` is one [ConditionPartKind.CODE] part, and a string is one
 * [ConditionPartKind.STRING_LITERAL] part. Such a case has no [caseKey]. See ADR 0038.
 */
data class BranchOutcome(
    val branchIndex: Int,
    val role: BranchRole,
    val caseKey: Int? = null,
    val guardedLines: List<LineRange> = emptyList(),
    val partlyGuardedLines: List<LineRange> = emptyList(),
    val caseLabel: List<ConditionPart> = emptyList(),
)

/**
 * One kept conditional jump or switch in a method, with its outcomes listed inside it. See ADR 0037.
 *
 * [siteIndex] is the site's ordinal within its class, in bytecode order across every method,
 * dropped sites counted. It names the site within one build only.
 *
 * [siteKey] is an opaque lowercase hex token naming this site across builds and instances, compared
 * only for equality. It is made the way a branch key is, without the outcome, and it is null in
 * exactly the cases the site's branch keys are null. See ADR 0031.
 *
 * [line] is the same line the site's BRANCH probes carry, including the origin line for a kept
 * inlined copy.
 *
 * [outcomes] lists a conditional's [BranchRole.TAKEN] then [BranchRole.FALL_THROUGH] outcome, or a
 * switch's [BranchRole.CASE] outcomes in the order its instruction names them, then
 * [BranchRole.DEFAULT] last. A switch read back from a lowering lists no default when its default
 * only throws an exception the compiler added (ADR 0038).
 *
 * [guard] is the [BranchOutcome.branchIndex] of the innermost kept outcome that dominates this
 * site's jump or switch, or null when nothing in the method stands between its entry and the site.
 * A dropped site has no outcomes to be a guard, so the guard is the next kept outcome above it.
 *
 * [condition] is the expression the site tests, in the class's source language, as the
 * fall-through side reads it. A switch's condition is its subject. It is empty when the agent
 * could not write the expression in source terms.
 */
data class BranchSite(
    val siteIndex: Int,
    val siteKey: String?,
    val line: Int,
    val outcomes: List<BranchOutcome>,
    val guard: Int? = null,
    val condition: List<ConditionPart> = emptyList(),
) {
    /**
     * What this site adds to a manifest or baseline chunk's weight: one entry for the site, one per
     * outcome, one per line range in either of an outcome's lists, one per case label part and one
     * per condition part. The chunkers count entries to keep each payload under the collector's
     * size limit.
     */
    val chunkWeight: Int
        get() = 1 + condition.size + outcomes.sumOf { 1 + it.guardedLines.size + it.partlyGuardedLines.size + it.caseLabel.size }
}

/** What one [ConditionPart] holds. See ADR 0037. */
enum class ConditionPartKind {
    /** Source text, such as `discounted > ` or `System.getenv(`. */
    CODE,

    /**
     * A string constant from the class's bytecode. The text is the string's value, not quoted and
     * not escaped. A consumer quotes it for display, and a collector can redact it.
     */
    STRING_LITERAL,

    /** One sub-expression the agent could not write in source terms. The text is empty. */
    PLACEHOLDER,
}

/** One part of a [BranchSite.condition]. Parts render one after the other with nothing between them. See ADR 0037. */
data class ConditionPart(
    val kind: ConditionPartKind,
    val text: String = "",
)

/**
 * A class the JVM has loaded that reached no manifest, neither as a probed class nor as a skipped
 * one. Found by the sweep rather than by a transform, so the agent has no reason to give and this
 * carries none. See ADR 0027.
 *
 * [firstSeenUnreportedAt] is when a sweep first found it, which tells a blind spot that existed
 * from startup apart from one that appeared later.
 */
data class UnreportedClass(
    val className: String,
    val firstSeenUnreportedAt: Long,
)

/** A class the agent matched but could not instrument. It never gets a classId or any probes. */
data class SkippedClass(
    val className: String,
    val reason: String,
    val skippedAt: Long,
)

/**
 * What a [CallEdge] records. A collector reaches the target of either kind the same way; the kind
 * only changes how the edge is named and drawn. See ADR 0034.
 */
enum class CallEdgeKind {
    /** The caller runs the callee: an invoke instruction, a `new`, or a static field use that runs the owner's initializer. */
    CALL,

    /**
     * The caller hands a body to someone else to run, so the body can only run after the caller
     * ran. It is the edge to the implementation method of a `LambdaMetafactory` `invokedynamic`,
     * whether a lambda body or a named method passed by reference. It is also the edge to each
     * probed method of a body class the caller creates with `new` or reads with `getstatic
     * INSTANCE`. The constructor edge of that `new`, and the initializer edge of that `getstatic`,
     * stay [CALL] edges, since the caller runs them itself.
     */
    CREATES,
}

/**
 * One caller method's static reference to one callee method, read from the caller's bytecode at
 * transform time. See ADR 0024.
 *
 * [className], [methodName], and [methodDescriptor] name the callee verbatim, as the caller's own
 * bytecode names it: the agent never resolves a virtual call, since one class's transform has no
 * view of the type hierarchy. [virtual] is true for an `invokevirtual` or `invokeinterface` call,
 * false for `invokestatic` or `invokespecial`, except that a same-class call to a private, static,
 * or final target is reported as non-virtual even when the raw instruction is `invokevirtual`,
 * since such a target can never be overridden. A collector widens a virtual edge to every override
 * it knows about; a non-virtual one names its one real target exactly.
 *
 * [kind] says whether the caller runs the callee or hands it off; see [CallEdgeKind]. An edge that
 * takes the place of a pass-through keeps the kind of the edge it replaces, and an edge from inside
 * a [CallEdgeKind.CREATES] target is a [CallEdgeKind.CREATES] edge too, since it can only run once
 * the created body runs.
 *
 * [capturedCount] is set only on a [CallEdgeKind.CREATES] edge from an `invokedynamic`: how many
 * of the target descriptor's leading parameters take values captured at the call site rather than
 * the functional interface's own arguments. It is read from the call site's `invokedType`. A
 * receiver bound by a reference to an instance method is not counted, since it is not in the
 * target's parameter list. 0 on every other edge. See ADR 0034.
 *
 * [guard] is the [BranchOutcome.branchIndex] of the innermost kept outcome that dominates the
 * instruction recording this edge, or null when nothing in the method stands between its entry and
 * that instruction. That instruction is the invoke, the `invokedynamic`, the `new` of a body class,
 * or the `getstatic` or `putstatic` that stands for an initializer. An edge that takes the place of
 * a pass-through keeps the guard of the call to the pass-through. Edges are distinct by every
 * field, so one callee reached under two guards gives two edges. See ADR 0037.
 *
 * [implementedInterface] is set only on a [CallEdgeKind.CREATES] edge from an `invokedynamic`: the
 * dotted name of the interface the call site returns, the return type of its `invokedType`, such as
 * `java.lang.Runnable`. Kotlin's function types are sent too. It travels with the kind, so an edge
 * that takes the place of a pass-through keeps the interface of the edge it takes its kind from.
 * Null on a [CallEdgeKind.CALL] edge and on a creation edge from a body class's `new` or
 * `getstatic INSTANCE`. One body created for two interfaces gives two edges. See ADR 0042.
 */
data class CallEdge(
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val virtual: Boolean,
    val kind: CallEdgeKind = CallEdgeKind.CALL,
    val capturedCount: Int = 0,
    val guard: Int? = null,
    val implementedInterface: String? = null,
)

/**
 * What a loaded class's own header says about it, sent once per class alongside its probes.
 *
 * [superClassName] and [interfaceNames] let a collector widen a [CallEdge.virtual] call to every
 * type that overrides or inherits its callee. [superClassName] is null only for `java.lang.Object`
 * itself, which this agent never instruments; an interface's own [superClassName] is
 * `java.lang.Object`, the same as any other type, since that is what the class file's own
 * super_class entry names. See ADR 0024.
 *
 * [sourceFile] is the class file's `SourceFile` attribute exactly as it appears, such as
 * `DemoServerMain.kt`: a file name, never a path. Null when the class has none. The agent does not
 * clean it, so a value such as `<generated>` is sent as it is. See ADR 0034.
 *
 * [bodyKind] says what kind of body class this is, or [BodyKind.NONE] when it is not one.
 * [sourceName] is the name the source gave a [BodyKind.LOCAL_CLASS], such as `Local` for
 * `Foo$1Local`, and null for every other kind. See [BodyKind] and ADR 0034.
 *
 * [kotlinKind] is what kind of class kotlinc says this is, or [KotlinKind.NONE] when it carries no
 * `kotlin.Metadata`. See ADR 0041.
 */
data class ClassLocation(
    val classId: Int,
    val superClassName: String?,
    val interfaceNames: List<String>,
    val sourceFile: String? = null,
    val bodyKind: BodyKind = BodyKind.NONE,
    val sourceName: String? = null,
    val kotlinKind: KotlinKind = KotlinKind.NONE,
)

/**
 * What kind of body class a class is, read from its own class file. A body class exists only to
 * carry a body its creator hands to someone else. A class is one only when it has an
 * `EnclosingMethod` attribute. The rule checks [LAMBDA_CLASS] first, then the class's own
 * `InnerClasses` entry. A Kotlin function or property reference class has no kind here: kotlinc
 * marks it synthetic, so it never reaches the wire, and its creator's edges pass through it to
 * the function it names. See ADR 0034.
 */
enum class BodyKind {
    /** The class has no `EnclosingMethod` attribute, so it is not a body class. */
    NONE,

    /**
     * An anonymous class, such as javac's `new Runnable() { ... }`: the class's own `InnerClasses`
     * entry has no name and it carries no `kotlin.Metadata`, or it has no entry for itself at all.
     */
    ANONYMOUS_CLASS,

    /** A Kotlin object expression (`object : Runnable { ... }`): its own `InnerClasses` entry has no name, and it carries `kotlin.Metadata`. */
    OBJECT_EXPRESSION,

    /** A class declared inside a method: its own `InnerClasses` entry has a name, which is [ClassLocation.sourceName]. */
    LOCAL_CLASS,

    /** A Kotlin lambda compiled to a class, or any suspend lambda: its superclass is one of the Kotlin runtime's lambda base classes. */
    LAMBDA_CLASS,
}

/**
 * [resource] is the same one the process stamps on its delta batches and static baseline.
 * `class_id` is assigned by each process's own registry, in whatever order that process's classes
 * happen to load, so the same `class_id` can mean a different class in two instances of the same
 * (service, version), or in two runs of one pinned instance. A collector keys every `class_id` on
 * the resource's instance and run, the same way it keys a delta batch's. See ADRs 0011 and 0032.
 *
 * [dependencies], [classReferences] and [externalClasses] are delivered incrementally like the
 * rest of this payload, each entry sent once per instance. See ADR 0030.
 *
 * [referencesRecorded] is true when the instance records references at all, which it does only
 * when its include rules are set. The agent sets it on every manifest it sends, so a collector can
 * tell an instance whose code references nothing from one that records nothing, and claims
 * unreferenced or unreached only for an instance that sent it true. See ADR 0030.
 *
 * [dependenciesListed] is true once every dependency from the startup listing, and every reference
 * mapping recorded before the listing ended, has gone out on a confirmed manifest. Until then, an
 * empty dependency list or no absent references means "not listed yet", not "none". See ADR 0036.
 */
data class ProbeManifest(
    val resource: ResourceAttributes,
    val probes: List<ProbeLocation>,
    val skippedClasses: List<SkippedClass> = emptyList(),
    val endpoints: List<EndpointLocation> = emptyList(),
    val disabledEndpointModules: List<DisabledEndpointModule> = emptyList(),
    val classLocations: List<ClassLocation> = emptyList(),
    val unreportedClasses: List<UnreportedClass> = emptyList(),
    val dependencies: List<DependencyLocation> = emptyList(),
    val classReferences: List<ClassReferences> = emptyList(),
    val externalClasses: List<ExternalClass> = emptyList(),
    val referencesRecorded: Boolean = false,
    val dependenciesListed: Boolean = false,
)

/**
 * No line field, unlike [ProbeLocation]: the static scan reads a class's bytecode only for the
 * inline marker and records no line. [inline] is read from the LocalVariableTable by the same
 * rule [ProbeLocation.inline] uses; see ADR 0022.
 *
 * [calls] is the same in-scope call-edge list [ProbeLocation.calls] carries for a loaded method,
 * read from the same analysis pass. A collector treats a baseline edge and a manifest edge as one
 * graph. See ADR 0024.
 *
 * [generatedBy] is read from the same bytecode shape [ProbeLocation.generatedBy] uses; see
 * [GeneratedBy] and ADR 0026. Always [GeneratedBy.NONE] for the class's own `<clinit>` entry.
 *
 * [referencedClasses] follows the same rule as [ProbeLocation.referencedClasses]. See ADR 0030.
 *
 * [lambdaBody] follows the same rule as [ProbeLocation.lambdaBody]. Always false for the class's
 * own `<clinit>` entry. See ADR 0034.
 *
 * [branchSites] follows the same rule as [ProbeLocation.branchSites], read from the same class.
 * Always empty for the class's own `<clinit>` entry. See ADR 0037.
 *
 * [static] follows the same rule as [ProbeLocation.static]. Always false for a constructor and for
 * the class's own `<clinit>` entry. See ADR 0040.
 *
 * [parameterNames], [genericSignature] and [extensionReceiver] follow the same rules as the
 * [ProbeLocation] fields of the same names, read from the same class. Empty or false for the
 * class's own `<clinit>` entry. See ADR 0043.
 */
data class DeclaredMethod(
    val methodName: String,
    val methodDescriptor: String,
    val inline: Boolean = false,
    val calls: List<CallEdge> = emptyList(),
    val generatedBy: GeneratedBy = GeneratedBy.NONE,
    val referencedClasses: List<String> = emptyList(),
    val lambdaBody: Boolean = false,
    val branchSites: List<BranchSite> = emptyList(),
    val static: Boolean = false,
    val parameterNames: List<String> = emptyList(),
    val genericSignature: String = "",
    val extensionReceiver: Boolean = false,
)

/**
 * [superClassName] and [interfaceNames] are the same fields [ClassLocation] carries for a
 * loaded class. Both are null and empty, respectively, only when the class's bytes could not be
 * read to analyse them; a class read successfully always has a superclass, since
 * `java.lang.Object` itself is never instrumented. See ADR 0024.
 *
 * [referencedClasses] holds the class's references outside any probed method: annotations on the
 * class, its supertypes, its field types, the signatures of methods without a probe, and anything
 * else not held by a probed method. Method-level references travel on
 * [DeclaredMethod.referencedClasses]. The same listing rule as [ProbeLocation.referencedClasses]
 * applies. See ADR 0030.
 *
 * [sourceFile], [bodyKind] and [sourceName] are the same fields [ClassLocation] carries for a
 * loaded class, read the same way. They are null, [BodyKind.NONE] and null when the class's bytes
 * could not be read. See ADR 0034.
 *
 * [kotlinKind] is the same field [ClassLocation] carries, read the same way. It is
 * [KotlinKind.NONE] when the class's bytes could not be read. See ADR 0041.
 */
data class DeclaredClass(
    val className: String,
    val methods: List<DeclaredMethod>,
    val superClassName: String? = null,
    val interfaceNames: List<String> = emptyList(),
    val referencedClasses: List<String> = emptyList(),
    val sourceFile: String? = null,
    val bodyKind: BodyKind = BodyKind.NONE,
    val sourceName: String? = null,
    val kotlinKind: KotlinKind = KotlinKind.NONE,
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
 *
 * [externalClasses] maps referenced class names to their dependency, or to absent. Each name is
 * sent once per instance, in this payload or the manifest, so a chunk need not carry an entry for
 * every name it references. See [ExternalClass].
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
    val externalClasses: List<ExternalClass> = emptyList(),
)

/** How the agent learned of an endpoint. See CONTEXT.md, "Discovery source". */
enum class EndpointDiscoverySource { REGISTRATION, DISPATCH }

/**
 * One endpoint the framework serves. [endpointId] is per instance, like `classId`; cross-instance
 * identity is ([verb], [routeTemplate]), never [endpointId].
 *
 * A record may be re-sent when [handlerClass]/[handlerMethod]/[handlerDescriptor] are learned or
 * change after the endpoint was first reported; a collector upserts by (service instance, run,
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

/** How the agent learned of a dependency. */
enum class DependencyDiscoverySource {
    /** Listed from the startup classpath in `premain`: `java.class.path`, or a fat jar's classpath index. */
    STARTUP_CLASSPATH,

    /**
     * Seen only when a class from it loaded, such as a jar in a WAR's `WEB-INF/lib` that the app
     * server opened after `premain`. A dependency found this way can never read as unloaded, since
     * a class from it has loaded.
     */
    LOAD,
}

/** Where a dependency's identity was read from, most to least reliable. */
enum class DependencyIdentitySource {
    /** `META-INF/maven/<group>/<artifact>/pom.properties` inside the jar. */
    POM_PROPERTIES,

    /**
     * Never produced: a manifest's `Implementation-Title` is a display name, not an artifact ID,
     * and several jars can share one. Kept so the wire number is not reused. See ADR 0030.
     */
    JAR_MANIFEST,

    /**
     * The jar's filename with its version suffix stripped; no group. The version comes from the
     * filename or, when it carries none, from the manifest's `Implementation-Version`.
     */
    FILENAME,
}

/**
 * One library a dependency carries. [groupId] is null when the identity source carries none, and
 * [version] is null when unknown. The version is an attribute beside the identity, not part of it.
 */
data class DependencyIdentity(
    val groupId: String?,
    val artifactId: String,
    val version: String?,
)

/**
 * One dependency this instance has seen: a jar that is not the adopter's own code.
 *
 * [dependencyId] is per instance, like `classId` and `endpointId`. Cross-instance identity is the
 * sorted set of `groupId:artifactId` pairs in [identities] (for an ordinary jar, the one pair),
 * never [dependencyId]; version is an attribute, not identity. A shaded jar that bundles several
 * libraries carries one identity per library, since it cannot be half-removed. See ADR 0030.
 *
 * Sent once, delivered incrementally like classes: a dependency discovered by load arrives on the
 * flush after it was first seen. [location] is for display only. [classCount] is the jar's total
 * class count, when the listing knows it.
 */
data class DependencyLocation(
    val dependencyId: Int,
    val identities: List<DependencyIdentity>,
    val identitySource: DependencyIdentitySource,
    val location: String,
    val discoverySource: DependencyDiscoverySource,
    val classCount: Int? = null,
) {
    init {
        require(identities.isNotEmpty()) { "dependency $dependencyId at $location has no identities" }
    }
}

/**
 * [loadedClassesTotal] counts distinct class names ever seen loaded from the dependency. It is
 * cumulative from process start and merged with max(), like [ProbeDelta.hitsTotal].
 * [firstLoadedAt] has the same "first observed by a flush" precision as [ProbeDelta.firstSeenAt].
 */
data class DependencyDelta(
    val dependencyId: Int,
    val firstLoadedAt: Long,
    val loadedClassesTotal: Long,
)

/**
 * A loaded class's own references outside any probed method: annotations on the class, its
 * supertypes, its field types, the signatures of methods without a probe (abstract, native), and
 * anything else not held by a probed method. The same listing rule as
 * [ProbeLocation.referencedClasses] applies.
 */
data class ClassReferences(
    val classId: Int,
    val referencedClasses: List<String>,
)

/**
 * Where one referenced class name lives, sent once per class name per instance. [dependencyId]
 * refers to that instance's [DependencyLocation] records, whichever payload carries the mapping.
 *
 * Exactly one of two things holds: the class maps to a dependency, so [dependencyId] is set, or no
 * loader could find it, so [absent] is true. A class with both would claim a dependency for a
 * class that is not there, and one with neither would be a reference that leads nowhere.
 */
data class ExternalClass(
    val className: String,
    val dependencyId: Int?,
    val absent: Boolean = false,
) {
    init {
        require((dependencyId == null) == absent) {
            "external class $className must be either mapped to a dependency or absent, not both or neither"
        }
    }
}
