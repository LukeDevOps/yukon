package io.github.lukedevops.yukon.instrumentation.endpoints

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints
import io.github.lukedevops.yukon.instrumentation.BootstrapHolder
import net.bytebuddy.agent.ByteBuddyAgent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.lang.invoke.MethodHandleInfo
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.Callable
import java.util.function.IntSupplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the seam half of hidden-handler naming (ADR 0035): what `YukonEndpoints.recordLambdaClass`
 * keeps and what `YukonEndpoints.lambdaImplementation` gives back. The lambda factory hook that
 * feeds it is proven end to end against the real JDK in the `endpoints-jdk-httpserver` module.
 * Here every input is built by hand. Another test in this JVM may have installed the real hook,
 * so each test spins its lambda classes before it installs a handler interface set, which keeps the
 * hook from recording them first.
 */
class LambdaImplementationSeamTest {
    private val lookup = MethodHandles.lookup()

    @BeforeEach
    fun installBootstrapHolder() {
        BootstrapHolder.install(ByteBuddyAgent.install())
    }

    @AfterEach
    fun clearHandlerInterfaces() {
        YukonEndpoints.installHandlerInterfaces(emptySet())
    }

    @Test
    fun `a lambda class for a handler interface is recorded under its own class`() {
        val lambdaClass = IntSupplier { 1 }.javaClass
        YukonEndpoints.installHandlerInterfaces(setOf(IntSupplier::class.java.name))

        YukonEndpoints.recordLambdaClass(lambdaClass, IntSupplier::class.java, targetInfo())

        val found = assertNotNull(YukonEndpoints.lambdaImplementation(lambdaClass))
        assertEquals("java.lang.Integer", found.className, "the owner is the dotted Class.getName() form")
        assertEquals("parseInt", found.methodName)
        assertEquals("(Ljava/lang/String;I)I", found.descriptor)
    }

    @Test
    fun `a lambda class for any other interface costs a set lookup and is not recorded`() {
        val lambdaClass = Runnable {}.javaClass
        YukonEndpoints.installHandlerInterfaces(setOf(IntSupplier::class.java.name))

        YukonEndpoints.recordLambdaClass(lambdaClass, Runnable::class.java, targetInfo())

        assertNull(YukonEndpoints.lambdaImplementation(lambdaClass))
    }

    @Test
    fun `nothing is recorded while the handler interface set is empty`() {
        YukonEndpoints.installHandlerInterfaces(emptySet())
        val lambdaClass = IntSupplier { 2 }.javaClass

        YukonEndpoints.recordLambdaClass(lambdaClass, IntSupplier::class.java, targetInfo())

        assertNull(YukonEndpoints.lambdaImplementation(lambdaClass))
    }

    @Test
    fun `an implementation whose owner is itself hidden is not recorded`() {
        val hiddenOwner = Callable { "a hidden class with a method of its own" }.javaClass
        assertTrue(hiddenOwner.isHidden)
        val hiddenInfo = lookup.revealDirect(lookup.unreflect(hiddenOwner.getDeclaredMethod("call")))
        val lambdaClass = Callable { "the lambda being recorded" }.javaClass
        YukonEndpoints.installHandlerInterfaces(setOf(Callable::class.java.name))

        YukonEndpoints.recordLambdaClass(lambdaClass, Callable::class.java, hiddenInfo)

        assertNull(YukonEndpoints.lambdaImplementation(lambdaClass))
    }

    @Test
    fun `an abstract implementation is not recorded, since the method that runs is decided later`() {
        val lambdaClass = IntSupplier { 4 }.javaClass
        val abstractInfo =
            lookup.revealDirect(
                lookup.findVirtual(IntSupplier::class.java, "getAsInt", MethodType.methodType(Int::class.javaPrimitiveType)),
            )
        YukonEndpoints.installHandlerInterfaces(setOf(IntSupplier::class.java.name))

        YukonEndpoints.recordLambdaClass(lambdaClass, IntSupplier::class.java, abstractInfo)

        assertNull(YukonEndpoints.lambdaImplementation(lambdaClass))
    }

    @Test
    fun `null inputs and a class never recorded are misses, never a throw`() {
        val lambdaClass = IntSupplier { 3 }.javaClass
        YukonEndpoints.installHandlerInterfaces(setOf(IntSupplier::class.java.name))

        YukonEndpoints.recordLambdaClass(null, IntSupplier::class.java, targetInfo())
        YukonEndpoints.recordLambdaClass(lambdaClass, null, targetInfo())
        YukonEndpoints.recordLambdaClass(lambdaClass, IntSupplier::class.java, null)

        assertNull(YukonEndpoints.lambdaImplementation(lambdaClass))
        assertNull(YukonEndpoints.lambdaImplementation(null))
        assertNull(YukonEndpoints.lambdaImplementation(String::class.java))
    }

    /** `Integer.parseInt(String, int)`, a real named method standing in for a lambda's implementation. */
    private fun targetInfo(): MethodHandleInfo {
        val type = MethodType.methodType(Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType)
        return lookup.revealDirect(lookup.findStatic(Integer::class.java, "parseInt", type))
    }
}
