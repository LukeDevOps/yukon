package io.github.lukedevops.yukon.instrumentation.endpoints.api

import net.bytebuddy.asm.Advice
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.pool.TypePool

/**
 * Resolves an advice class by name, without loading it, from a combination of [agentClassLoader]
 * (where the advice class itself lives) and [targetClassLoader] (where the framework types the
 * advice references live).
 *
 * An advice class written against a framework's own types, such as Spring's `HandlerMethod` or
 * Ktor's `Route`, cannot be loaded in the classloader an [EndpointModule] implementation runs in:
 * those framework types are not on that classloader's classpath, so the advice class's own method
 * signatures would fail to resolve there. [Advice.to] only needs a
 * [net.bytebuddy.description.type.TypeDescription] and a way to read class bytes, neither of
 * which requires loading the class, so this reads the advice class's bytecode from
 * [agentClassLoader] and resolves any framework type its signature mentions from
 * [targetClassLoader] instead, through a [ClassFileLocator.Compound] of the two. Null
 * [targetClassLoader] means the bootstrap loader, resolved with [ClassFileLocator.ForClassLoader.ofBootLoader].
 * The resulting [Advice] is inlined directly into the framework class being instrumented, in the
 * classloader where the framework's own types do resolve. This is how OpenTelemetry's Java agent
 * references every advice class it ships: always by name, never as a class literal.
 */
class AdviceBinder(
    agentClassLoader: ClassLoader,
    targetClassLoader: ClassLoader?,
) {
    private val locator =
        ClassFileLocator.Compound(
            ClassFileLocator.ForClassLoader.of(agentClassLoader),
            if (targetClassLoader ==
                null
            ) {
                ClassFileLocator.ForClassLoader.ofBootLoader()
            } else {
                ClassFileLocator.ForClassLoader.of(targetClassLoader)
            },
        )
    private val typePool = TypePool.Default.of(locator)

    /** Reads and describes [adviceClassName]'s bytecode, ready to weave with [Advice.on]. */
    fun bind(adviceClassName: String): Advice = Advice.to(typePool.describe(adviceClassName).resolve(), locator)
}
