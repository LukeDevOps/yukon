package dev.otherlode.testkit

import java.net.URL
import java.net.URLClassLoader

/**
 * Loads anything under [packagePrefix] itself, instead of delegating to the parent. This way, the
 * fixture is defined for the first time only after instrumentation is installed. The prefix is
 * `com.example.testkittarget.` unless a test loads the Scala fixture modules.
 *
 * Everything else, such as the JDK and the agent classes, still resolves through the parent as
 * normal. Mirrors the root project's own
 * `dev.otherlode.instrumentation.FixtureClassLoader`, scoped to this module's own
 * fixture package so the two test suites never load the same class name through different
 * classloaders in the same process.
 */
class FixtureClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
    private val packagePrefix: String = "com.example.testkittarget.",
) : URLClassLoader(urls, parent) {
    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> {
        if (!name.startsWith(packagePrefix)) return super.loadClass(name, resolve)
        synchronized(getClassLoadingLock(name)) {
            val existing = findLoadedClass(name)
            val loaded = existing ?: findClass(name)
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }
}
