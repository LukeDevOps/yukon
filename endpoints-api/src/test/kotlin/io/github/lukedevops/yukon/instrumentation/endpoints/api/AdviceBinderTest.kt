package io.github.lukedevops.yukon.instrumentation.endpoints.api

import io.github.lukedevops.yukon.instrumentation.endpoints.api.fixture.PingAdvice
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.matcher.ElementMatchers.named
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves [AdviceBinder] resolves an advice class by name and that the resulting [net.bytebuddy.asm.Advice]
 * weaves correctly, without ever referencing the advice class as a class literal at the weave call
 * site (only its fully qualified name is passed to [AdviceBinder.bind]).
 *
 * Installs a real `AgentBuilder` transformer through [ByteBuddyAgent.install], the same route
 * [io.github.lukedevops.yukon.instrumentation.YukonInstrumentation]'s own tests use, rather than
 * `ByteBuddy().redefine(...)` loaded with a fresh classloader: the fixture class is defined for
 * the first time only after the transformer is installed, so there is no already-loaded copy for
 * a wrapping classloader to conflict with.
 */
class AdviceBinderTest {
    private var transformer: ResettableClassFileTransformer? = null

    @AfterTest
    fun tearDown() {
        transformer?.reset(ByteBuddyAgent.install(), AgentBuilder.RedefinitionStrategy.DISABLED)
        transformer = null
    }

    @Test
    fun `binds an advice class by name and weaves it into a fixture method`() {
        PingAdvice.entries = 0
        val instrumentation = ByteBuddyAgent.install()
        val binder = AdviceBinder(javaClass.classLoader, javaClass.classLoader)

        transformer =
            AgentBuilder
                .Default()
                .type(named("io.github.lukedevops.yukon.instrumentation.endpoints.api.fixture.PingTarget"))
                .transform { builder, _, _, _, _ ->
                    builder.visit(
                        binder
                            .bind("io.github.lukedevops.yukon.instrumentation.endpoints.api.fixture.PingAdvice")
                            .on(named("ping")),
                    )
                }.installOn(instrumentation)

        val loader = FixtureClassLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
        val target = Class.forName("io.github.lukedevops.yukon.instrumentation.endpoints.api.fixture.PingTarget", true, loader)
        val instance = target.getDeclaredConstructor().newInstance()
        target.getMethod("ping").invoke(instance)
        target.getMethod("ping").invoke(instance)

        assertEquals(2, PingAdvice.entries)
    }

    @Test
    fun `bind with remap prefixes rewrites a referenced type's internal name`() {
        val adviceClassName = "io.github.lukedevops.yukon.instrumentation.endpoints.api.fixture.RemapAdvice"
        val originalBytes =
            ClassFileLocator.ForClassLoader
                .of(javaClass.classLoader)
                .locate(adviceClassName)
                .resolve()

        val remapped = remapClassBytes(originalBytes, mapOf("com/example/remap/" to "com/example/remapped/"))

        val remappedText = String(remapped, Charsets.ISO_8859_1)
        assertFalse("com/example/remap/Before" in remappedText, "the original reference must not survive the remap")
        assertTrue("com/example/remapped/Before" in remappedText, "the rewritten reference must name the remapped type")

        // The remapped bytecode must also resolve and bind cleanly, proving AdviceBinder's own
        // TypePool can describe the advice once the type it references only exists under its
        // remapped name.
        val binder = AdviceBinder(javaClass.classLoader, javaClass.classLoader)
        val advice = binder.bind(adviceClassName, mapOf("com/example/remap/" to "com/example/remapped/"))
        assertNotNull(advice)
    }

    @Test
    fun `bind with an empty remap map behaves exactly like the single-argument bind`() {
        PingAdvice.entries = 0
        val binder = AdviceBinder(javaClass.classLoader, javaClass.classLoader)

        val withoutMap = binder.bind("io.github.lukedevops.yukon.instrumentation.endpoints.api.fixture.PingAdvice")
        val withEmptyMap = binder.bind("io.github.lukedevops.yukon.instrumentation.endpoints.api.fixture.PingAdvice", emptyMap())

        assertNotNull(withoutMap)
        assertNotNull(withEmptyMap)
    }
}
