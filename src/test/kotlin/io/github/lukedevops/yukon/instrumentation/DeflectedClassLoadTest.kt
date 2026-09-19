package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import java.io.InputStream
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.net.URL
import java.net.URLClassLoader
import java.security.ProtectionDomain
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the blind spot STATUS.md describes: a class that loads while the same thread is already
 * inside another class's transform is reported nowhere, and cannot be.
 *
 * The JVM does not run the load hook for such a class, so no transformer in the chain is handed
 * it: not ByteBuddy's, and not one registered ahead of ByteBuddy's either. The spy below stands in
 * for any such transformer and never sees the nested class, while the class itself is defined and
 * usable. That is why the agent cannot record this case the way it records a class ByteBuddy
 * declined to transform.
 *
 * This test fails if a JVM ever starts offering these classes to the chain, which is the signal
 * that the case can be recorded after all.
 */
class DeflectedClassLoadTest {
    private companion object {
        const val OUTER = "com.example.target.BranchTarget"
        const val NESTED = "com.example.target.SampleTarget"
    }

    private val instrumentation: Instrumentation = ByteBuddyAgent.install()
    private var yukon: YukonInstrumentation? = null
    private var installedTransformer: ResettableClassFileTransformer? = null
    private var watcher: ClassFileTransformer? = null

    /**
     * Loads [NESTED] the first time it is asked for a resource, which ByteBuddy does from inside
     * the transform of the class in hand. Fixture classes are defined here rather than by the
     * parent, so this test gets its own copies.
     */
    private class ReentrantLoader(
        urls: Array<URL>,
        parent: ClassLoader,
    ) : URLClassLoader(urls, parent) {
        private val triggered = AtomicBoolean(false)

        /** The nested class once it has loaded, so a test cannot pass on a case it never reached. */
        @Volatile
        var nested: Class<*>? = null
            private set

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

        override fun getResourceAsStream(name: String): InputStream? {
            if (triggered.compareAndSet(false, true)) {
                nested = Class.forName(NESTED, false, this)
            }
            return super.getResourceAsStream(name)
        }
    }

    @AfterTest
    fun tearDown() {
        installedTransformer?.let { yukon?.uninstall(instrumentation, it) }
        watcher?.let { instrumentation.removeTransformer(it) }
        installedTransformer = null
        watcher = null
        yukon = null
    }

    @Test
    fun `a class that loads inside another class's transform reaches no transformer at all`() {
        val seen = mutableListOf<String>()
        val spy =
            object : ClassFileTransformer {
                override fun transform(
                    loader: ClassLoader?,
                    className: String?,
                    classBeingRedefined: Class<*>?,
                    protectionDomain: ProtectionDomain?,
                    classfileBuffer: ByteArray,
                ): ByteArray? {
                    if (className != null && className.startsWith("com/example/target/")) {
                        synchronized(seen) { seen += className }
                    }
                    return null
                }
            }
        // Registered ahead of the agent's own transformers, so the JVM offers it every class first.
        instrumentation.addTransformer(spy, false)
        watcher = spy

        val registry = ProbeRegistry()
        val instrumented = YukonInstrumentation(AgentConfig.parse("includePackages=com.example.target"), registry)
        yukon = instrumented
        installedTransformer = instrumented.install(instrumentation)

        val loader = ReentrantLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
        Class.forName(OUTER, true, loader)

        val nested = loader.nested
        assertTrue(nested != null, "the nested class never loaded, so this test proved nothing")
        assertEquals(loader, nested.classLoader, "the nested class has to be this loader's own copy")
        assertEquals(listOf("com/example/target/BranchTarget"), synchronized(seen) { seen.toList() })

        val manifest = registry.manifest("test", null, "instance-1")
        assertTrue(manifest.probes.none { it.className == NESTED }, "a class no transformer saw cannot have probes")
        assertTrue(manifest.skippedClasses.none { it.className == NESTED }, "and cannot be recorded as skipped either")
    }
}
