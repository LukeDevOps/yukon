package io.github.lukedevops.yukon.export

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
 * shape alone: an enum's `values`/`valueOf`/`getEntries`, a data class's `componentN`/`copy`/
 * `equals`/`hashCode`/`toString`, every method of a `$DefaultImpls` class, or a Java record's
 * `equals`/`hashCode`/`toString`. Set on a [ProbeKind.METHOD] probe and a [DeclaredMethod], and
 * on a [ProbeKind.OPTIONAL_ARGUMENT] probe as its target's own mark; a branch probe never carries
 * this. A collector leaves a generated probe out of never-hit, stale-hit, the call graph and the
 * two optional-parameter findings by default: the compiler will emit the method again
 * regardless of what the adopter does, so a zero hit count is not a finding the adopter can act
 * on. The hit count itself is still kept and counted, since a call to a generated method, such as
 * `copy`, is still evidence of use. See ADR 0026.
 */
enum class GeneratedBy { NONE, ENUM, DATA_CLASS, DEFAULT_IMPLS, RECORD }

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
 * [generatedBy] is set for a [ProbeKind.METHOD] probe, and for a [ProbeKind.OPTIONAL_ARGUMENT]
 * probe as its target's mark; a branch probe never carries it. See [GeneratedBy] and ADR 0026.
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
 */
data class CallEdge(
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val virtual: Boolean,
)

/**
 * A class's superclass and direct interfaces, sent once per class alongside its probes so a
 * collector can widen a [CallEdge.virtual] call to every type that overrides or inherits its
 * callee. [superClassName] is null only for `java.lang.Object` itself, which this agent never
 * instruments; an interface's own [superClassName] is `java.lang.Object`, the same as any other
 * type, since that is what the class file's own super_class entry names.
 */
data class ClassSupertypes(
    val classId: Int,
    val superClassName: String?,
    val interfaceNames: List<String>,
)

/**
 * [serviceInstanceId] is required, unlike the rest of this payload's (service, version) scoping.
 * `class_id` is assigned independently by each instance's own registry, in whatever order that
 * process's own classes happen to load, so the same `class_id` can mean a different class in two
 * instances of the same (service, version). A collector correlating manifests or delta batches
 * across instances needs an instance to key on to avoid attributing one instance's probe
 * metadata, or hit count, to the wrong class from another instance.
 *
 * [dependencies], [classReferences] and [externalClasses] are delivered incrementally like the
 * rest of this payload, each entry sent once per instance. See ADR 0030.
 *
 * [referencesRecorded] is true when the instance records references at all, which it does only
 * when its include rules are set. The agent sets it on every manifest it sends, so a collector can
 * tell an instance whose code references nothing from one that records nothing, and claims
 * unreferenced or unreached only for an instance that sent it true. See ADR 0030.
 */
data class ProbeManifest(
    val serviceName: String,
    val serviceVersion: String?,
    val probes: List<ProbeLocation>,
    val skippedClasses: List<SkippedClass> = emptyList(),
    val serviceInstanceId: String = "",
    val endpoints: List<EndpointLocation> = emptyList(),
    val disabledEndpointModules: List<DisabledEndpointModule> = emptyList(),
    val classSupertypes: List<ClassSupertypes> = emptyList(),
    val unreportedClasses: List<UnreportedClass> = emptyList(),
    val dependencies: List<DependencyLocation> = emptyList(),
    val classReferences: List<ClassReferences> = emptyList(),
    val externalClasses: List<ExternalClass> = emptyList(),
    val referencesRecorded: Boolean = false,
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
 */
data class DeclaredMethod(
    val methodName: String,
    val methodDescriptor: String,
    val inline: Boolean = false,
    val calls: List<CallEdge> = emptyList(),
    val generatedBy: GeneratedBy = GeneratedBy.NONE,
    val referencedClasses: List<String> = emptyList(),
)

/**
 * [superClassName] and [interfaceNames] are the same fields [ClassSupertypes] carries for a
 * loaded class. Both are null and empty, respectively, only when the class's bytes could not be
 * read to analyse them; a class read successfully always has a superclass, since
 * `java.lang.Object` itself is never instrumented. See ADR 0024.
 *
 * [referencedClasses] holds the class's references outside any probed method: annotations on the
 * class, its supertypes, its field types, the signatures of methods without a probe, and anything
 * else not held by a probed method. Method-level references travel on
 * [DeclaredMethod.referencedClasses]. The same listing rule as [ProbeLocation.referencedClasses]
 * applies. See ADR 0030.
 */
data class DeclaredClass(
    val className: String,
    val methods: List<DeclaredMethod>,
    val superClassName: String? = null,
    val interfaceNames: List<String> = emptyList(),
    val referencedClasses: List<String> = emptyList(),
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
