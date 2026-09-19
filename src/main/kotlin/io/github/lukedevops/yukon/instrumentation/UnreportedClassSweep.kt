package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.registry.ProbeRegistry
import java.lang.System.Logger.Level
import java.lang.instrument.Instrumentation

/**
 * Finds classes the JVM has loaded that reached no manifest, neither as a probed class nor as a
 * skipped one, and records them so a collector stops calling them never loaded. See ADR 0027.
 *
 * Every other way the agent learns about a class runs from a transformer, and there are classes no
 * transformer is ever offered: one loaded while the same thread is already inside another class's
 * transform, which `java.lang.instrument` refuses to hand to any transformer on that
 * `Instrumentation`. Asking the JVM what it holds is the only way to see those.
 *
 * The comparison runs one way only, loaded against accounted for. That direction is free of races
 * by construction: a class is registered at `onTransformation`, which runs before `getBytes()`
 * returns, and the JVM defines the class only after that, so a class cannot appear in
 * [Instrumentation.getAllLoadedClasses] before it appears in the registry. The other direction,
 * registered but never loaded, would catch a class the verifier rejected after the agent had
 * already committed its probes, and is not attempted here: it has its own false positives, a class
 * defined microseconds ago and a class whose loader has since been collected.
 */
class UnreportedClassSweep(
    private val instrumentation: Instrumentation,
    private val registry: ProbeRegistry,
    private val config: AgentConfig,
) {
    private val log = System.getLogger(UnreportedClassSweep::class.java.name)
    private var everFound = false

    /**
     * Runs one sweep and records what it finds. Returns how many classes this sweep newly found
     * unreported, which is zero on every sweep after the blind spot stops growing.
     */
    fun run(): Int {
        val candidates = instrumentation.allLoadedClasses.filter(::isCandidate).map { it.name }
        val unaccounted = registry.unaccountedFrom(candidates)
        val newlyFound = unaccounted.count { registry.recordUnreported(it) }
        if (newlyFound > 0) logFirstFinding(newlyFound)
        return newlyFound
    }

    /**
     * Logs one INFO line the first time a sweep finds anything, naming what it means rather than
     * only the count. Later sweeps add to the total silently; the wire carries the classes
     * themselves, and a line per sweep would say the same thing every few minutes.
     */
    private fun logFirstFinding(newlyFound: Int) {
        if (everFound) return
        everFound = true
        log.log(
            Level.INFO,
            "yukon: $newlyFound loaded classes reached no transformer and are reported as unreported rather than " +
                "instrumented; a second agent in the chain is the usual cause",
        )
    }

    /**
     * Whether a loaded class is one the agent would have instrumented, had it been offered it.
     *
     * Three of the four checks the type matcher runs, evaluated against the loaded class itself:
     * the name rules, the synthetic flag, and the coroutine-continuation check, which needs only
     * the direct superclass's name. The fourth, whether the class carries an annotation that is
     * illegal on a type, is deliberately left out. Reading a class's annotations resolves each
     * annotation's own type and can load classes, and a sweep must not load anything to look;
     * a class turned away for that reason is recorded as skipped anyway, so it never reaches
     * this test.
     */
    private fun isCandidate(loaded: Class<*>): Boolean {
        if (loaded.isSynthetic) return false
        if (!TypeMatchPolicy.isIncluded(loaded.name, config.instrumentedPackagePrefixes, config.excludedPackagePrefixes)) {
            return false
        }
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
        return CONTINUATION_SUPERCLASS_SUFFIXES.any { superclassName.endsWith(it) }
    }

    private companion object {
        /** Kept in step with `TypeMatchPolicy`'s own list, matched by suffix for the same shading reason. */
        val CONTINUATION_SUPERCLASS_SUFFIXES =
            listOf(
                ".coroutines.jvm.internal.ContinuationImpl",
                ".coroutines.jvm.internal.RestrictedContinuationImpl",
            )
    }
}
