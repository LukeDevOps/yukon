package io.github.lukedevops.yukon.instrumentation.staticscan

import io.github.lukedevops.yukon.instrumentation.PlatformProvidedClasses
import io.github.lukedevops.yukon.registry.DependencyRegistry
import io.github.lukedevops.yukon.registry.ExternalClassRegistry
import java.lang.System.Logger.Level
import java.time.Duration

/**
 * Filters a static scan's references and maps each one kept to its dependency, without a defining
 * loader. See ADR 0030.
 *
 * At transform time a reference is looked up through the loader defining the referencing class.
 * The scan has no such loader, and in a Spring Boot fat jar the system loader cannot see
 * `BOOT-INF/lib` at all, so this works from what the agent has already read: the scan's own class
 * names and the startup listing's class index ([DependencyRegistry.dependencyForClass]). One
 * referenced name resolves, in order:
 *
 * 1. Provided by the platform or bootstrap loader: a JDK class, dropped.
 * 2. Seen by the scan in a directory root or under `BOOT-INF/classes`/`WEB-INF/classes`: the
 *    adopter's own, dropped.
 * 3. In the class index: kept, and recorded as that dependency's.
 * 4. Seen by the scan at the root of a jar the listing did not register as a dependency (the
 *    adopter's own jar, an agent jar): dropped.
 * 5. Anything else: kept, and recorded nowhere. The scan cannot tell a class that is truly absent
 *    from one in a jar only a runtime loader will open (a plugin directory, a war's `WEB-INF/lib`),
 *    and since the first recording of a name wins, an absent verdict here would stand even after
 *    the transform path found the jar. Left unmapped, the name is ignored by a collector until the
 *    transform path maps it, if it ever does.
 *
 * Step 3 comes before step 4 because on a flat classpath the scan walks every jar, dependencies
 * included, so a name seen in a jar is the adopter's only when no dependency holds it.
 *
 * Mappings are recorded into [externalClasses], which sends each once on the manifest, so the
 * baseline carries none and never disagrees with the transform path; a name the transform path
 * recorded first keeps that recording.
 *
 * [filter] first waits up to [listingWait] for the startup listing to end. If it failed or is still
 * running, every reference list is emptied: with no dependencies there is nothing to map a name to.
 * A failure is logged at INFO, a timeout at WARNING, one line either way.
 */
class BaselineReferenceFilter(
    private val dependencies: DependencyRegistry,
    private val externalClasses: ExternalClassRegistry,
    private val listingWait: Duration = DEFAULT_LISTING_WAIT,
    private val providedByPlatform: (String) -> Boolean = PlatformProvidedClasses()::provides,
) {
    private val log = System.getLogger(BaselineReferenceFilter::class.java.name)

    /**
     * [result] with JDK and adopter's-own names dropped from every reference list, every kept name
     * recorded. The class index is released afterwards, whatever the outcome, since nothing else
     * reads it.
     */
    fun filter(result: StaticScanResult): StaticScanResult {
        try {
            when (dependencies.awaitListing(listingWait)) {
                DependencyRegistry.ListingOutcome.COMPLETE -> {
                    return keepReferences(result)
                }

                DependencyRegistry.ListingOutcome.FAILED -> {
                    log.log(
                        Level.INFO,
                        "yukon: the static baseline is sent without references because the dependency listing failed, " +
                            "so there are no dependencies to map them to",
                    )
                }

                DependencyRegistry.ListingOutcome.TIMED_OUT -> {
                    log.log(
                        Level.WARNING,
                        "yukon: the dependency listing had not finished after $listingWait; the static baseline is sent " +
                            "without references",
                    )
                }
            }
            return result.withoutReferences()
        } finally {
            dependencies.releaseClassIndex()
        }
    }

    private fun keepReferences(result: StaticScanResult): StaticScanResult {
        val kept = HashMap<String, Boolean>()

        fun keep(names: List<String>): List<String> = names.filter { name -> kept.getOrPut(name) { resolve(name, result) } }

        return result.copy(
            declaredClasses =
                result.declaredClasses.map { declared ->
                    declared.copy(
                        methods = declared.methods.map { it.copy(referencedClasses = keep(it.referencedClasses)) },
                        referencedClasses = keep(declared.referencedClasses),
                    )
                },
            ownClassNames = emptySet(),
            flatJarClassNames = emptySet(),
        )
    }

    /** Whether [name] is kept; a kept name the class index maps is recorded into [externalClasses]. */
    private fun resolve(
        name: String,
        result: StaticScanResult,
    ): Boolean {
        if (providedByPlatform(name) || name in result.ownClassNames) return false
        val dependencyId = dependencies.dependencyForClass(name)
        if (dependencyId == null && name in result.flatJarClassNames) return false
        if (dependencyId != null) externalClasses.recordResolved(name, dependencyId)
        return true
    }

    companion object {
        /** A backstop only: the listing releases the wait itself when it finishes or fails. */
        val DEFAULT_LISTING_WAIT: Duration = Duration.ofMinutes(2)
    }
}
