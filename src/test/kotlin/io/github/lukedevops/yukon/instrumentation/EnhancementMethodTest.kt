package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.instrumentation.staticscan.StaticBaselineScanner
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves ADR 0047 through the real pipeline and the static scanner: a Hibernate enhancement method
 * gets no METHOD or BRANCH probe and is not declared, and a call to one, from its own class or
 * from another, passes through to what it calls.
 */
class EnhancementMethodTest {
    private companion object {
        const val PACKAGE = "com.example.target"
        const val ENTITY = "$PACKAGE.EnhancedEntityTarget"
        const val ASSOCIATION = "$PACKAGE.EnhancedAssociationTarget"
        const val CALLER = "$PACKAGE.EnhancedEntityCaller"
        const val PREFIX = "\$\$_hibernate_"
    }

    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedYukon: YukonInstrumentation? = null

    @AfterTest
    fun tearDown() {
        installedTransformer?.let { installedYukon?.uninstall(ByteBuddyAgent.install(), it) }
    }

    @Test
    fun `an enhancement method gets no probe, and a call to one passes through to what it calls`() {
        val registry = ProbeRegistry()
        val yukon = YukonInstrumentation(AgentConfig.parse("includePackages=$PACKAGE"), registry)
        installedYukon = yukon
        installedTransformer = yukon.install(ByteBuddyAgent.install())

        val loader = FixtureClassLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
        Class.forName(ENTITY, true, loader)
        Class.forName(CALLER, true, loader)

        val probes = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1")).probes
        val entity = probes.filter { it.className == ENTITY }
        assertTrue(entity.any { it.methodName == "getName" && it.kind == ProbeKind.METHOD }, "the entity's own methods are probed")
        assertEquals(emptyList(), entity.filter { it.methodName.startsWith(PREFIX) }.map { "${it.methodName} ${it.kind}" })

        fun callsOf(
            className: String,
            methodName: String,
        ) = probes
            .single { it.className == className && it.methodName == methodName && it.kind == ProbeKind.METHOD }
            .calls
            .map { "${it.className}.${it.methodName}" }

        assertEquals(emptyList(), callsOf(ENTITY, "getName").filter { PREFIX in it })
        assertTrue("$ASSOCIATION.addItem" in callsOf(ENTITY, "setOrder"), callsOf(ENTITY, "setOrder").toString())
        assertTrue(callsOf(ENTITY, "setOrder").none { PREFIX in it }, callsOf(ENTITY, "setOrder").toString())
        assertTrue("$ASSOCIATION.addItem" in callsOf(CALLER, "assign"), callsOf(CALLER, "assign").toString())
        assertTrue(callsOf(CALLER, "assign").none { PREFIX in it }, callsOf(CALLER, "assign").toString())
    }

    /**
     * Under runtime enhancement the class file a loader serves as a resource is the one on disk,
     * without the enhancement methods, while the class the JVM defined has them. A call into another
     * class's enhancement method then finds no declaration to pass through, and must not be kept
     * as an edge to a method nothing probes.
     */
    @Test
    fun `a call to an enhancement method the owner's class file does not declare records no edge`() {
        val registry = ProbeRegistry()
        val yukon = YukonInstrumentation(AgentConfig.parse("includePackages=$PACKAGE"), registry)
        installedYukon = yukon
        installedTransformer = yukon.install(ByteBuddyAgent.install())

        val root = File("build/classes/java/test")
        val entityPath = ENTITY.replace('.', '/') + ".class"
        val unenhanced = withoutEnhancementMethods(File(root, entityPath).readBytes())
        val loader =
            object : FixtureClassLoader(arrayOf(root.toURI().toURL()), javaClass.classLoader) {
                override fun getResourceAsStream(name: String): InputStream? =
                    if (name == entityPath) ByteArrayInputStream(unenhanced) else super.getResourceAsStream(name)

                override fun getResource(name: String): URL? = if (name == entityPath) null else super.getResource(name)
            }
        Class.forName(CALLER, true, loader)

        val assign =
            registry
                .manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
                .probes
                .single { it.className == CALLER && it.methodName == "assign" && it.kind == ProbeKind.METHOD }
        assertEquals(emptyList(), assign.calls.map { "${it.className}.${it.methodName}" }.filter { PREFIX in it })
    }

    /** [bytes] with every method whose name starts with the enhancement prefix removed. */
    private fun withoutEnhancementMethods(bytes: ByteArray): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? =
                    if (name.startsWith(PREFIX)) null else super.visitMethod(access, name, descriptor, signature, exceptions)
            },
            0,
        )
        return writer.toByteArray()
    }

    @Test
    fun `the static baseline declares no enhancement method`() {
        val root = File("build/classes/java/test")
        val result = StaticBaselineScanner(listOf(PACKAGE)).scan(listOf(root))

        val methods = result.declaredClasses.single { it.className == ENTITY }.methods
        assertTrue(methods.any { it.methodName == "getName" })
        assertEquals(emptyList(), methods.map { it.methodName }.filter { it.startsWith(PREFIX) })
        val assign =
            result.declaredClasses
                .single { it.className == CALLER }
                .methods
                .single { it.methodName == "assign" }
        val calls = assign.calls.map { "${it.className}.${it.methodName}" }
        assertTrue("$ASSOCIATION.addItem" in calls && calls.none { PREFIX in it }, calls.toString())
    }
}
