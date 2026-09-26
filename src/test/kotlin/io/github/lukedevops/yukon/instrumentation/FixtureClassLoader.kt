package io.github.lukedevops.yukon.instrumentation

import java.net.URL
import java.net.URLClassLoader

/**
 * Loads anything under [fixturePackagePrefix] itself, instead of delegating to the parent. This
 * way, the fixture is defined for the first time only after instrumentation is installed.
 *
 * Everything else, such as the JDK and the agent classes, still resolves through the parent as
 * normal.
 */
open class FixtureClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
    private val fixturePackagePrefix: String = "com.example.target.",
) : URLClassLoader(urls, parent) {
    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> {
        if (!name.startsWith(fixturePackagePrefix)) return super.loadClass(name, resolve)
        synchronized(getClassLoadingLock(name)) {
            val existing = findLoadedClass(name)
            val loaded = existing ?: findClass(name)
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }
}
