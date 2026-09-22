package io.github.lukedevops.demo.collector

import io.github.lukedevops.yukon.proto.DependencyDiscoverySource

/** One library a dependency carries, as one instance's listing named it. */
internal data class DependencyIdentityView(
    val groupId: String,
    val artifactId: String,
    val version: String,
) {
    /** `group:artifact`, the cross-instance name. The group is empty for a filename-derived identity. */
    val key: String get() = "$groupId:$artifactId"
}

/** One instance's `DependencyLocation`, with that instance's own [dependencyId]. */
internal data class DependencyView(
    val dependencyId: Int,
    val identities: List<DependencyIdentityView>,
    val discoverySource: DependencyDiscoverySource,
    val classCount: Int?,
    val location: String,
) {
    /** The sorted `group:artifact` pairs, which name the dependency across instances. See ADR 0030. */
    val identityKey: String get() =
        identities
            .map { it.key }
            .distinct()
            .sorted()
            .joinToString(",")
}

/** Where one referenced class name lives on one instance: a dependency id, or absent. */
internal data class ExternalClassView(
    val dependencyId: Int?,
    val absent: Boolean,
)

/** Which payload a [HeldReferences] came from, which decides whether it can be live. */
internal enum class ReferenceOrigin {
    /** A METHOD probe's `referenced_classes`: live when the method has hits on any instance. */
    MANIFEST_METHOD,

    /** A class's `ClassReferences`: live when the instance's manifest names the class, since it loaded. */
    MANIFEST_CLASS,

    /** A declared method or class in the static baseline: never live on its own. */
    BASELINE,
}

/**
 * The out-of-scope class names one site in the adopter's code references. [methodName] and
 * [methodDescriptor] are null for a class-level site. [inline] marks an inline method, whose own
 * references never count, since its Kotlin callers carry copies of them.
 */
internal data class HeldReferences(
    val className: String,
    val methodName: String?,
    val methodDescriptor: String?,
    val origin: ReferenceOrigin,
    val referencedClasses: List<String>,
    val inline: Boolean = false,
    val hits: Long = 0L,
)

/**
 * Everything one instance said about dependencies. [baselineComplete] is true only when the
 * instance sent at least one static scan and every chunk of it arrived. [loadedClassNames] is every
 * class the instance's manifest named, probed or skipped, so every class that loaded.
 */
internal data class InstanceDependencyView(
    val instanceId: String,
    val referencesRecorded: Boolean,
    val baselineComplete: Boolean,
    val dependencies: List<DependencyView> = emptyList(),
    val loadedClassesTotal: Map<Int, Long> = emptyMap(),
    val externalClasses: Map<String, ExternalClassView> = emptyMap(),
    val references: List<HeldReferences> = emptyList(),
    val loadedClassNames: Set<String> = emptySet(),
)

/** The statuses the report prints, in the order it prints them. See ADR 0030. */
internal enum class DependencyStatus(
    val label: String,
) {
    UNLOADED("UNLOADED"),
    UNREFERENCED("UNREFERENCED"),
    UNREACHED("UNREACHED"),
    NO_LIVE_REFERENCE("NO LIVE REFERENCE"),
    USED("USED"),

    /** Loaded, and no instance that lists it records references, so nothing further can be said. */
    LOADED("LOADED"),
}

/** One referencing site, as printed: `Class#method` or `Class (class level)`, with whether its class ever loaded. */
internal data class ReferenceSite(
    val className: String,
    val methodName: String?,
    val neverLoaded: Boolean,
) {
    override fun toString(): String {
        val where = methodName?.let { "$className#$it" } ?: "$className (class level)"
        return if (neverLoaded) "$where (never loaded)" else where
    }
}

/** One dependency merged across instances by its identity key, with its status. */
internal data class DependencyFinding(
    val identityKey: String,
    val status: DependencyStatus,
    val versionsByIdentity: Map<String, Set<String>>,
    val loadedClassesTotal: Long,
    val classCount: Int?,
    val discoverySources: Set<DependencyDiscoverySource>,
    val sites: List<ReferenceSite>,
)

/** A referenced class no loader could find, with the sites that reference it. */
internal data class AbsentReference(
    val className: String,
    val sites: List<ReferenceSite>,
)

/**
 * The dependency report's content. [referencesUnavailable] is true when no instance recorded
 * references, so nothing past loaded or unloaded is claimed.
 */
internal data class DependencyReport(
    val findings: List<DependencyFinding>,
    val absentReferences: List<AbsentReference>,
    val referencesUnavailable: Boolean,
)

/**
 * Applies ADR 0030's rules across [instances]. A dependency is unloaded when some instance listed
 * it from the startup classpath and no instance loaded a class from it. Past that, only the
 * instances that list it and record references are consulted, and with none it reads as loaded: a
 * reference is a referenced class whose `ExternalClass` mapping on that instance names the
 * dependency, and it is live when held by a non-inline method with hits on any instance or by a
 * class that loaded. Baseline references are never live. With a complete baseline from every one
 * of those instances, no reference is unreferenced and no live one is unreached; otherwise the two
 * merge into no live reference, which is true either way.
 */
internal fun computeDependencyReport(instances: List<InstanceDependencyView>): DependencyReport {
    val recording = instances.filter { it.referencesRecorded }
    val methodHits =
        instances
            .flatMap { it.references }
            .filter { it.origin == ReferenceOrigin.MANIFEST_METHOD }
            .groupBy { Triple(it.className, it.methodName, it.methodDescriptor) }
            .mapValues { (_, held) -> held.sumOf { it.hits } }

    val referencesByDependency = mutableMapOf<String, MutableSet<Pair<ReferenceSite, Boolean>>>()
    val absentSites = mutableMapOf<String, MutableSet<ReferenceSite>>()
    for (instance in recording) {
        val identityKeys = instance.dependencies.associate { it.dependencyId to it.identityKey }
        for (held in instance.references) {
            if (held.inline) continue
            val live =
                when (held.origin) {
                    ReferenceOrigin.MANIFEST_METHOD -> {
                        (methodHits[Triple(held.className, held.methodName, held.methodDescriptor)] ?: 0L) >
                            0L
                    }

                    ReferenceOrigin.MANIFEST_CLASS -> {
                        held.className in instance.loadedClassNames
                    }

                    ReferenceOrigin.BASELINE -> {
                        false
                    }
                }
            val site =
                ReferenceSite(
                    held.className,
                    held.methodName,
                    neverLoaded = held.origin == ReferenceOrigin.BASELINE && held.className !in instance.loadedClassNames,
                )
            for (referenced in held.referencedClasses) {
                val mapping = instance.externalClasses[referenced] ?: continue
                if (mapping.absent) {
                    absentSites.getOrPut(referenced) { mutableSetOf() } += site
                    continue
                }
                // A mapping to no known dependency is a jar of the adopter's own or an agent jar,
                // which ADR 0030 tells a collector to ignore.
                val identityKey = mapping.dependencyId?.let(identityKeys::get) ?: continue
                referencesByDependency.getOrPut(identityKey) { mutableSetOf() } += site to live
            }
        }
    }

    val findings =
        instances
            .flatMap { instance -> instance.dependencies.map { instance to it } }
            .groupBy { (_, dependency) -> dependency.identityKey }
            .map { (identityKey, listings) ->
                val loaded = listings.maxOf { (instance, dependency) -> instance.loadedClassesTotal[dependency.dependencyId] ?: 0L }
                val references = referencesByDependency[identityKey].orEmpty()
                // Only an instance that lists the dependency can hold a reference to it, so only
                // those decide whether levels 2 and 3 apply and whether the baseline splits them.
                val judging = listings.map { (instance, _) -> instance }.filter { it.referencesRecorded }
                val status =
                    when {
                        loaded == 0L &&
                            listings.any { (_, dependency) ->
                                dependency.discoverySource == DependencyDiscoverySource.STARTUP_CLASSPATH
                            } -> {
                            DependencyStatus.UNLOADED
                        }

                        judging.isEmpty() -> {
                            DependencyStatus.LOADED
                        }

                        references.any { (_, live) -> live } -> {
                            DependencyStatus.USED
                        }

                        !judging.all { it.baselineComplete } -> {
                            DependencyStatus.NO_LIVE_REFERENCE
                        }

                        references.isEmpty() -> {
                            DependencyStatus.UNREFERENCED
                        }

                        else -> {
                            DependencyStatus.UNREACHED
                        }
                    }
                DependencyFinding(
                    identityKey = identityKey,
                    status = status,
                    versionsByIdentity =
                        listings
                            .flatMap { (_, dependency) -> dependency.identities }
                            .groupBy({ it.key }, { it.version })
                            .mapValues { (_, versions) -> versions.filter { it.isNotEmpty() }.toSortedSet() },
                    loadedClassesTotal = loaded,
                    classCount = listings.mapNotNull { (_, dependency) -> dependency.classCount }.maxOrNull(),
                    discoverySources = listings.map { (_, dependency) -> dependency.discoverySource }.toSortedSet(),
                    sites = references.map { (site, _) -> site }.distinct().sortedBy { it.toString() },
                )
            }.sortedWith(compareBy({ it.status.ordinal }, { it.identityKey }))

    return DependencyReport(
        findings = findings,
        absentReferences =
            absentSites.entries
                .map { (className, sites) -> AbsentReference(className, sites.sortedBy { it.toString() }) }
                .sortedBy { it.className },
        referencesUnavailable = recording.isEmpty(),
    )
}

/**
 * The report as the lines [printDependencyReport] prints, headers included: counts per status
 * first, then one line per dependency, then the absent references. An unreached dependency, or one
 * with no live reference, lists the sites holding its references, so the reader sees where the dead
 * reference sits.
 */
internal fun formatDependencyReport(report: DependencyReport): List<String> {
    val lines = mutableListOf<String>()
    lines += ""
    lines += "=== yukon demo: dependency report ==="
    val counts = report.findings.groupingBy { it.status }.eachCount()
    val judged = DependencyStatus.entries.filter { it != DependencyStatus.LOADED }
    var countsLine = judged.joinToString(", ") { "${it.label.lowercase()}: ${counts[it] ?: 0}" }
    if (report.referencesUnavailable || DependencyStatus.LOADED in counts) {
        countsLine += ", loaded: ${counts[DependencyStatus.LOADED] ?: 0}"
    }
    lines += countsLine
    if (report.referencesUnavailable) {
        lines += "no instance sent references_recorded (only one with include rules set records references), " +
            "so levels 2 and 3 (unreferenced, unreached) are unavailable"
    }
    for (finding in report.findings) {
        // One identity, the usual case, needs only its versions; a bundling jar names each one.
        val versions =
            finding.versionsByIdentity.entries
                .singleOrNull()
                ?.value
                ?.joinToString(", ")
                ?: finding.versionsByIdentity.entries.joinToString(", ") { (key, versions) ->
                    if (versions.isEmpty()) key else "$key ${versions.joinToString(", ")}"
                }
        val versionLabel = versions.ifEmpty { "version unknown" }
        val classCount = finding.classCount?.toString() ?: "?"
        val sources = finding.discoverySources.joinToString(", ") { it.name.lowercase().replace('_', ' ') }
        lines +=
            "  ${finding.status.label}: ${finding.identityKey} [$versionLabel] loaded ${finding.loadedClassesTotal} of $classCount classes ($sources)"
        if (finding.status == DependencyStatus.UNREACHED || finding.status == DependencyStatus.NO_LIVE_REFERENCE) {
            finding.sites.forEach { lines += "    referenced from $it" }
        }
    }
    if (report.absentReferences.isNotEmpty()) {
        lines += "ABSENT REFERENCES: ${report.absentReferences.size}"
        for (absent in report.absentReferences) {
            lines += "  ABSENT: ${absent.className} (referenced from ${absent.sites.joinToString(", ")})"
        }
    }
    lines += "======================================"
    return lines
}
