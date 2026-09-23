package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.dependencies.LoadedDependencyCounter
import io.github.lukedevops.yukon.registry.ProbeRegistry
import java.lang.System.Logger.Level
import java.lang.instrument.Instrumentation

/**
 * Walks [Instrumentation.getAllLoadedClasses] to answer two questions no transformer can, one in
 * each direction. See ADR 0027 and ADR 0028.
 *
 * The forward direction ([reportUnreported]) asks which classes the agent would have instrumented,
 * had it been offered them, that the registry never heard about at all. `java.lang.instrument`
 * refuses to call a transformer while that thread is already inside another class's transform, so
 * a class loaded from inside a transform reaches no transformer, agent or otherwise, and would
 * read as never loaded next to a static baseline that declared it.
 *
 * The reverse direction ([confirm]) asks which classes the registry did hear about that the JVM
 * never actually defined: a class ByteBuddy wove and [ProbeRegistry.register] committed, where the
 * verifier then rejected the bytes or `defineClass` raised a `LinkageError` for a supertype absent
 * at load. Left alone, such a class sits in the manifest permanently at zero, which is what a
 * collector reads as dead code.
 *
 * Both directions read [Instrumentation.getAllLoadedClasses] once per [run] call and compare it
 * against the registry from opposite sides, so one sweep serves both rather than two independent
 * walks of the same array.
 *
 * The same array feeds [dependencyCounter], when one is given, which counts the distinct classes
 * loaded from each dependency. See ADR 0030.
 */
open class LoadedClassSweep(
    private val instrumentation: Instrumentation,
    private val registry: ProbeRegistry,
    private val config: AgentConfig,
    private val dependencyCounter: LoadedDependencyCounter? = null,
) {
    private val log = System.getLogger(LoadedClassSweep::class.java.name)
    private var everFoundUnreported = false

    /**
     * Runs the confirmation pass over every loaded class, unfiltered, and the unreported-class
     * pass over the same array, filtered through [isCandidate], only when [runForwardPass] is
     * true. Every call hands the array to [dependencyCounter] as well. [final] additionally logs a
     * one-line summary of how many classes were withheld for good, when that count is above zero.
     *
     * The confirmation pass must see every loaded name, not the forward direction's filtered
     * candidate list. The two ask opposite questions: the forward direction asks which classes
     * the agent *would have* instrumented that the registry never heard about, which needs the
     * filter; the confirmation pass asks which classes the registry *did* hear about that the JVM
     * never defined, which needs the raw set. A registered class the filter happens to drop would
     * otherwise be counted as missing and, after two calls, withheld for good: a live class
     * silently removed from the manifest by the very fix meant to prevent that outcome. So both
     * passes are derived from the one array taken here, rather than the forward direction's own
     * filtered list.
     */
    open fun run(
        runForwardPass: Boolean,
        final: Boolean = false,
    ) {
        val loaded = instrumentation.allLoadedClasses
        confirm(loaded)
        if (runForwardPass) reportUnreported(loaded)
        dependencyCounter?.count(loaded)
        if (final) logShutdownSummary()
    }

    /**
     * Reconciles every registry entry not yet confirmed defined against [loaded]'s names, taken
     * unfiltered. Logs one `WARNING` per class name [ProbeRegistry.confirmFrom] returns.
     * `confirmFrom` returns a name once and never again, so this cannot repeat for the same class.
     */
    private fun confirm(loaded: Array<Class<*>>) {
        // The walk runs on every flush once dependencies are counted; skip building the name set when nothing awaits it.
        if (registry.unconfirmedClassCount() == 0) return
        val loadedNames = loaded.mapTo(mutableSetOf()) { it.name }
        for (name in registry.confirmFrom(loadedNames)) {
            log.log(
                Level.WARNING,
                "yukon: $name was woven and registered but the JVM never defined it; its probes are withheld for good",
            )
        }
    }

    /**
     * Logs the running total of classes withheld for good, when it is above zero. Called only on
     * the shutdown flush, so an instance that ends cleanly always gives a final answer about
     * classes that were woven and never defined.
     */
    private fun logShutdownSummary() {
        val withheld = registry.withheldForGoodClassCount()
        if (withheld > 0) {
            log.log(Level.WARNING, "yukon: $withheld classes were woven and registered but never confirmed defined")
        }
    }

    /**
     * Finds classes the JVM has loaded that reached no manifest, neither as a probed class nor as
     * a skipped one, and records them so a collector stops calling them never loaded.
     *
     * A name recorded by an earlier sweep can have become accounted for since, when a second
     * classloader's copy of it registered normally. Dropping those first keeps one manifest from
     * carrying the same name as both a probed class and an unreported one.
     */
    private fun reportUnreported(loaded: Array<Class<*>>) {
        registry.purgeAccountedFor()
        val candidates = loaded.filter(::isCandidate).map { it.name }
        val unaccounted = registry.unaccountedFrom(candidates)
        val newlyFound = unaccounted.count { registry.recordUnreported(it) }
        if (newlyFound > 0) logFirstUnreportedFinding(newlyFound)
    }

    /**
     * Logs one INFO line the first time a sweep finds anything unreported, naming what it means
     * rather than only the count. Later sweeps add to the total silently; the wire carries the
     * classes themselves, and a line per sweep would say the same thing every few minutes.
     */
    private fun logFirstUnreportedFinding(newlyFound: Int) {
        if (everFoundUnreported) return
        everFoundUnreported = true
        log.log(
            Level.INFO,
            "yukon: $newlyFound loaded classes reached no transformer and are reported as unreported rather than " +
                "instrumented; a second agent in the chain is the usual cause",
        )
    }

    /**
     * Whether a loaded class is one the agent would have instrumented, had it been offered it.
     *
     * ByteBuddy applies its own ignore matcher before any `.type(...)` matcher runs, and a class
     * it ignores reaches no transform callback and no registry bucket. An include prefix can reach
     * into the JDK's own packages (`includePackages=com` covers `com.sun.*`), and a sweep that only
     * replicated the type matcher would then call every such JDK class a blind spot, since the gate
     * turned it away before the type matcher ever saw it. The first three checks here are that
     * gate: a class on the bootstrap or platform loader, and one named under ByteBuddy's own
     * package or the reflection internals, is ignored rather than instrumented.
     *
     * The rest mirror `TypeMatchPolicy`: the synthetic flag, the name rules, the
     * runtime-generated proxy names, and the coroutine-continuation check, which needs only the
     * direct superclass's name. An array class
     * and a hidden class are dropped too, neither of which a transformer is ever offered. A hidden
     * class carries its own `/0x...` suffix in its name, so it could not be joined to anything a
     * collector holds even if it were a blind spot.
     *
     * One check from the type matcher is left out on purpose: whether the class carries an
     * annotation that is illegal on a type. Reading a class's annotations resolves each
     * annotation's own type and can load classes, and a sweep must not load anything in order to
     * look. A class turned away for that reason is recorded as skipped, so it never reaches here.
     */
    private fun isCandidate(loaded: Class<*>): Boolean {
        if (loaded.isArray || loaded.isPrimitive || loaded.isHidden) return false
        if (loaded.isSynthetic) return false
        val loader = loaded.classLoader ?: return false
        if (loader === ClassLoader.getPlatformClassLoader()) return false
        val name = loaded.name
        if (IGNORED_NAME_PREFIXES.any { name.startsWith(it) }) return false
        if (!TypeMatchPolicy.isIncluded(name, config.instrumentedPackagePrefixes, config.excludedPackagePrefixes)) {
            return false
        }
        if (TypeMatchPolicy.isRuntimeGenerated(name)) return false
        return !isCoroutineContinuation(loaded)
    }

    /**
     * Whether [loaded] is a suspend function's own continuation class, by the same direct
     * superclass suffix [TypeMatchPolicy] matches on. A continuation class holds no code the
     * adopter wrote and is excluded from instrumentation, so it is not a blind spot when it never
     * reaches a transformer. `getSuperclass` reads a name the JVM already resolved and loads
     * nothing.
     */
    private fun isCoroutineContinuation(loaded: Class<*>): Boolean {
        val superclassName = loaded.superclass?.name ?: return false
        return TypeMatchPolicy.CONTINUATION_SUPERCLASS_SUFFIXES.any { superclassName.endsWith(it) }
    }

    private companion object {
        /**
         * Names ByteBuddy's own ignore matcher turns away, beyond the classloader gate. Kept as
         * literal prefixes rather than read from ByteBuddy, which exposes the matcher only as a
         * composed predicate over its own type descriptions.
         */
        val IGNORED_NAME_PREFIXES = listOf("net.bytebuddy.", "sun.reflect.", "jdk.internal.reflect.")
    }
}
