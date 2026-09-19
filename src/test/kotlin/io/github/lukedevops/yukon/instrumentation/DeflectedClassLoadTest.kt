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
 * `java.lang.instrument` refuses to call a transformer while that thread is already inside
 * another transform on the same `Instrumentation`, so no transformer the agent registers is
 * handed such a class: not ByteBuddy's, and not one registered ahead of ByteBuddy's either. The
 * spy below stands in for any of them and never sees the nested class, while the class itself is
 * defined and usable. That is why the agent cannot record this case the way it records a class
 * ByteBuddy declined to transform.
 *
 * This test fails if a JDK ever starts offering these classes to the transformers of the
 * `Instrumentation` already in a transform, which is the signal that the case can be recorded
 * after all.
 */
class DeflectedClassLoadTest {
    private companion object {
        const val OUTER = "com.example.target.BranchTarget"
        const val NESTED = "com.example.target.SampleTarget"
        const val NOTHING_TO_PROBE = "com.example.target.AbstractOnlyInterface"
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

    @Test
    fun `the sweep reports the deflected class, and does not report one with nothing to probe`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        val instrumented = YukonInstrumentation(config, registry)
        yukon = instrumented
        installedTransformer = instrumented.install(instrumentation)

        val loader = ReentrantLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
        Class.forName(OUTER, true, loader)
        // An interface with only abstract methods: the transform sees it and finds nothing to
        // probe, so it never registers either. It is loaded and unregistered like the deflected
        // class, and it is not a blind spot.
        Class.forName(NOTHING_TO_PROBE, true, loader)
        assertTrue(loader.nested != null, "the nested class never loaded, so this test proved nothing")

        UnreportedClassSweep(instrumentation, registry, config).run()

        val manifest = registry.manifest("test", null, "instance-1")
        val unreported = manifest.unreportedClasses.map { it.className }
        assertTrue(NESTED in unreported, "the deflected class is what the sweep exists to find")
        assertTrue(
            NOTHING_TO_PROBE !in unreported,
            "a class the agent looked at and found nothing in is accounted for, not a blind spot",
        )
        assertTrue(
            OUTER !in unreported,
            "a class that registered normally is accounted for",
        )
    }

    @Test
    fun `a sweep with no includePackages does not name the JDK`() {
        // The default configuration. ByteBuddy ignores the bootstrap and platform loaders before
        // any type matcher runs, so those classes reach no transformer by design and are not a
        // blind spot. A sweep that only replicated the type matcher would report every one of
        // them.
        val registry = ProbeRegistry()
        val config = AgentConfig.parse(null)
        assertTrue(config.instrumentedPackagePrefixes.isEmpty(), "this test is about the default, so it must be the default")

        UnreportedClassSweep(instrumentation, registry, config).run()

        val unreported = registry.manifest("test", null, "instance-1").unreportedClasses.map { it.className }

        // Only what ByteBuddy's ignore matcher turns away, which is what the sweep has to
        // replicate. Anything on the application's own loader is fair game here: this JVM is
        // shared with every other test and installs the agent part-way through, so Gradle's and
        // JUnit's classes really did load unreported, and a -javaagent premain has no such window.
        // That includes some JDK-looking names: jdk.attach and jdk.internal.jvmstat, which the
        // attach API pulls in, sit on the app loader, so ByteBuddy offers them to transformers
        // like anything else. java.base is the honest test of the gate, since it is always
        // bootstrap.
        assertTrue(
            unreported.none { it.startsWith("java.lang.") || it.startsWith("java.util.") || it.startsWith("java.io.") },
            "the bootstrap loader reaches no transformer by design: $unreported",
        )
        assertTrue(unreported.none { it.startsWith("net.bytebuddy.") }, "ByteBuddy ignores its own classes")
        assertTrue(unreported.none { it.startsWith("[") }, "an array class is never offered to a transformer either")
        assertTrue(unreported.none { "/0x" in it }, "a hidden class carries a name nothing could join on")
    }
}
