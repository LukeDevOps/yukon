package io.github.lukedevops.yukon.testkit

import java.net.URL
import java.net.URLClassLoader

/**
 * Loads anything under `com.example.testkittarget` itself, instead of delegating to the parent.
 * This way, the fixture is defined for the first time only after instrumentation is installed.
 *
 * Everything else, such as the JDK and the agent classes, still resolves through the parent as
 * normal. Mirrors the root project's own
 * `io.github.lukedevops.yukon.instrumentation.FixtureClassLoader`, scoped to this module's own
 * fixture package so the two test suites never load the same class name through different
 * classloaders in the same process.
 */
class FixtureClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
) : URLClassLoader(urls, parent) {
    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> {
        if (!name.startsWith("com.example.testkittarget.")) return super.loadClass(name, resolve)
        synchronized(getClassLoadingLock(name)) {
            val existing = findLoadedClass(name)
            val loaded = existing ?: findClass(name)
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }
}
