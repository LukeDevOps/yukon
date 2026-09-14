package io.github.lukedevops.yukon.instrumentation.endpoints.api

import net.bytebuddy.asm.Advice
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.pool.TypePool

/**
 * Resolves an advice class from [classLoader]'s own bytecode, by name, without loading it.
 *
 * An advice class written against a framework's own types, such as Spring's `HandlerAdapter` or
 * Ktor's `Route`, cannot be loaded in the classloader an [EndpointModule] implementation runs in:
 * those framework types are not on that classloader's classpath, so the advice class's own method
 * signatures would fail to resolve there. [Advice.to] only needs a
 * [net.bytebuddy.description.type.TypeDescription] and a way to read class bytes, neither of
 * which requires loading the class, so this reads the advice class's bytecode with
 * [ClassFileLocator.ForClassLoader] and describes it with [TypePool.Default] instead. The
 * resulting [Advice] is inlined directly into the framework class being instrumented, in the
 * classloader where the framework's own types do resolve. This is how OpenTelemetry's Java agent
 * references every advice class it ships: always by name, never as a class literal.
 */
class AdviceBinder(
    classLoader: ClassLoader,
) {
    private val locator = ClassFileLocator.ForClassLoader.of(classLoader)
    private val typePool = TypePool.Default.of(locator)

    /** Reads and describes [adviceClassName]'s bytecode, ready to weave with [Advice.on]. */
    fun bind(adviceClassName: String): Advice = Advice.to(typePool.describe(adviceClassName).resolve(), locator)
}
