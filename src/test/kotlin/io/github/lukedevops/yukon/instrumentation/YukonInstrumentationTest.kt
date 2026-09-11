package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Loads anything under `com.example.target` itself rather than delegating to
 * the parent, so the fixture is defined for the first time only after
 * instrumentation is installed. Everything else (the JDK, [ProbeDispatch])
 * still resolves through the parent as normal.
 */
private class FixtureClassLoader(
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

class YukonInstrumentationTest {
    private fun loadFixtureFresh(): Any {
        val classesDir = File("build/classes/java/test")
        val loader = FixtureClassLoader(arrayOf(classesDir.toURI().toURL()), javaClass.classLoader)
        val targetClass = Class.forName("com.example.target.SampleTarget", true, loader)
        return targetClass.getDeclaredConstructor().newInstance()
    }

    @Test
    fun `only the methods that were actually called show up in the next delta batch`() {
        val instrumentation = ByteBuddyAgent.install()
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")

        YukonInstrumentation(config, registry).install(instrumentation)
        val target = loadFixtureFresh()
        target.javaClass.getMethod("ping").invoke(target)

        val manifest = registry.manifest("test", null)
        val neverCalledProbeIndex = manifest.probes.single { it.methodName == "neverCalled" }.probeIndex
        val pingProbeIndex = manifest.probes.single { it.methodName == "ping" }.probeIndex

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).deltas
        val byIndex = deltas.associateBy { it.probeIndex }

        assertEquals(1L, byIndex.getValue(pingProbeIndex).hitsSinceLastFlush)
        assertTrue(neverCalledProbeIndex !in byIndex)
    }
}
