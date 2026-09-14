package io.github.lukedevops.yukon.instrumentation.endpoints.api

import java.net.URL
import java.net.URLClassLoader

/**
 * Loads [TARGET_CLASS_NAME] itself, instead of delegating to the parent. This way,
 * [io.github.lukedevops.yukon.instrumentation.endpoints.api.fixture.PingTarget] is defined for
 * the first time only after the transformer under test is installed.
 *
 * Everything else, including [io.github.lukedevops.yukon.instrumentation.endpoints.api.fixture.PingAdvice],
 * still resolves through the parent as normal, which matters here specifically: the advice class
 * shares a package with the target class, and its own static field is what the test reads back
 * after invoking the target, so it must resolve to the exact same class object the test itself
 * sees, not a second copy this loader defined on its own.
 */
class FixtureClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
) : URLClassLoader(urls, parent) {
    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> {
        if (name != TARGET_CLASS_NAME) return super.loadClass(name, resolve)
        synchronized(getClassLoadingLock(name)) {
            val existing = findLoadedClass(name)
            val loaded = existing ?: findClass(name)
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }

    private companion object {
        const val TARGET_CLASS_NAME = "io.github.lukedevops.yukon.instrumentation.endpoints.api.fixture.PingTarget"
    }
}
