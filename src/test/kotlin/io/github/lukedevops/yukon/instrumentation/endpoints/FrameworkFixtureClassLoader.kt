package io.github.lukedevops.yukon.instrumentation.endpoints

import java.net.URL
import java.net.URLClassLoader

/**
 * Loads anything under `com.example.framework` itself, instead of delegating to the parent. This
 * way, a fixture framework class is defined for the first time only after
 * [EndpointInstrumentation] is installed, mirroring
 * [io.github.lukedevops.yukon.instrumentation.FixtureClassLoader]'s reasoning for the method
 * tier's own fixtures.
 *
 * Everything else, such as the JDK and the agent classes, still resolves through the parent as
 * normal.
 */
class FrameworkFixtureClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
) : URLClassLoader(urls, parent) {
    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> {
        if (!name.startsWith("com.example.framework.")) return super.loadClass(name, resolve)
        synchronized(getClassLoadingLock(name)) {
            val existing = findLoadedClass(name)
            val loaded = existing ?: findClass(name)
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }
}
