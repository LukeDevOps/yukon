package io.github.lukedevops.yukon.instrumentation

import java.net.URL
import java.net.URLClassLoader

/**
 * Loads anything under `com.example.target` itself rather than delegating to
 * the parent, so the fixture is defined for the first time only after
 * instrumentation is installed. Everything else (the JDK, the agent classes)
 * still resolves through the parent as normal.
 */
class FixtureClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
) : URLClassLoader(urls, parent) {
    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> {
        if (!name.startsWith("com.example.target.")) return super.loadClass(name, resolve)
        synchronized(getClassLoadingLock(name)) {
            val existing = findLoadedClass(name)
            val loaded = existing ?: findClass(name)
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }
}
