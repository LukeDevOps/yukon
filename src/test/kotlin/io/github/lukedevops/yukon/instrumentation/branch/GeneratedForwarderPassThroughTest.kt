package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.export.CallEdge
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.Opcodes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves ADR 0041's pass-through: a call into a `JVM_OVERLOADS`, `MULTIFILE_FACADE` or
 * `DEFAULT_IMPLS` forwarder records edges to what the forwarder calls, and a cross-class one also
 * names its owner's `<clinit>`, as any cross-class pass-through does (ADR 0024). The forwarder's
 * own edges stay as its bytecode names them.
 */
class GeneratedForwarderPassThroughTest {
    private companion object {
        const val TARGET = "com.example.target"
        const val PRICE = "$TARGET.Price"
        const val FACADE = "$TARGET.MultifileText"
        const val GREETING_PART = "$TARGET.MultifileText__MultifileGreetingKt"
        const val FAREWELL_PART = "$TARGET.MultifileText__MultifileFarewellKt"
        const val STRING_TO_STRING = "(Ljava/lang/String;)Ljava/lang/String;"
    }

    private val lookup: (String) -> ByteArray? = { internalName ->
        val dottedName = internalName.replace('/', '.')
        listOf("build/classes/kotlin/test", "build/classes/java/test")
            .asSequence()
            .map { ClassFileLocator.ForFolder(File(it)).locate(dottedName) }
            .firstOrNull { it.isResolved }
            ?.resolve()
    }

    /** Leaves out the synthetic methods these fixtures hold, as the method tier does. */
    private val probedByMethodTier: (String, String) -> Boolean = { name, _ -> !name.endsWith("\$default") && !name.startsWith("access\$") }

    private fun analyze(
        directory: String,
        simpleName: String,
    ): BranchSiteAnalyzer.Analysis =
        BranchSiteAnalyzer.analyze(
            File("build/classes/$directory/test/com/example/target/$simpleName.class").readBytes(),
            lookup,
            listOf(TARGET),
            emptyList(),
            methodFilter = probedByMethodTier,
        )

    private val javaCaller by lazy { analyze("java", "GeneratedForwarderCaller") }

    private val kotlinCaller by lazy { analyze("kotlin", "KotlinKindTargetKt") }

    private fun clinit(className: String) = CallEdge(className, "<clinit>", "()V", virtual = false)

    @Test
    fun `a call to a JvmOverloads constructor reaches the full constructor through its default twin`() {
        assertEquals(
            listOf(CallEdge(PRICE, "<init>", "(ILjava/lang/String;I)V", virtual = false), clinit(PRICE)),
            javaCaller.callsOf("callsOverloadConstructor", "()L${PRICE.replace('.', '/')};"),
        )
    }

    @Test
    fun `a call to a JvmOverloads member function reaches the full function through its default twin`() {
        assertEquals(
            listOf(CallEdge(PRICE, "format", "(ILjava/lang/String;)Ljava/lang/String;", virtual = false), clinit(PRICE)),
            javaCaller.callsOf("callsOverloadMethod", "(L${PRICE.replace('.', '/')};)Ljava/lang/String;"),
        )
    }

    @Test
    fun `a call to a top-level JvmOverloads function reaches the full function`() {
        val facade = "$TARGET.GeneratedTargetKt"
        assertEquals(
            listOf(CallEdge(facade, "formatPrice", "(JLjava/lang/String;D)Ljava/lang/String;", virtual = false), clinit(facade)),
            javaCaller.callsOf("callsTopLevelOverload", "()Ljava/lang/String;"),
        )
    }

    @Test
    fun `a call to a DefaultImpls forwarder reaches the interface's default method through its accessor`() {
        val iface = "$TARGET.GeneratedInterface"
        assertEquals(
            listOf(CallEdge(iface, "withBody", "()I", virtual = false), clinit(iface), clinit("$iface\$DefaultImpls")),
            javaCaller.callsOf("callsDefaultImpls", "(L${iface.replace('.', '/')};)I"),
        )
    }

    @Test
    fun `a Java or Kotlin call to a multi-file facade function reaches the part's function`() {
        val expected = listOf(CallEdge(GREETING_PART, "multifileGreeting", STRING_TO_STRING, virtual = false), clinit(FACADE))

        assertEquals(expected, javaCaller.callsOf("callsMultifileFacade", "()Ljava/lang/String;"))
        assertEquals(expected, kotlinCaller.callsOf("callsMultifileGreeting", "()Ljava/lang/String;"))
    }

    @Test
    fun `a call to a multi-file facade's default twin reaches the part's function through the facade's forwarder`() {
        assertEquals(
            listOf(CallEdge(FAREWELL_PART, "multifileFarewell", "(Ljava/lang/String;I)Ljava/lang/String;", virtual = false), clinit(FACADE)),
            kotlinCaller.callsOf("callsMultifileFarewellWithDefault", "()Ljava/lang/String;"),
        )
    }

    @Test
    fun `a read of a multi-file property reaches the part's getter`() {
        assertEquals(
            listOf(CallEdge(FAREWELL_PART, "getMultifileSuffix", "()Ljava/lang/String;", virtual = false), clinit(FACADE)),
            kotlinCaller.callsOf("readsMultifileSuffix", "()Ljava/lang/String;"),
        )
    }

    @Test
    fun `a call to an ordinary top-level function in another file stays an edge to it`() {
        assertEquals(
            listOf(CallEdge("$TARGET.KotlinKindOtherKt", "callsIntoOtherFacade", "()I", virtual = false)),
            kotlinCaller.callsOf("callsAnotherFile", "()I"),
        )
    }

    @Test
    fun `a forwarder's own edges name its callees as its bytecode does`() {
        assertEquals(
            listOf(CallEdge(GREETING_PART, "multifileGreeting", STRING_TO_STRING, virtual = false)),
            analyze("kotlin", "MultifileText").callsOf("multifileGreeting", STRING_TO_STRING),
        )
        assertEquals(
            listOf(CallEdge(PRICE, "format", "(ILjava/lang/String;)Ljava/lang/String;", virtual = false)),
            analyze("kotlin", "Price").callsOf("format", "(I)Ljava/lang/String;"),
        )
        val iface = "$TARGET.GeneratedInterface"
        assertEquals(
            listOf(CallEdge(iface, "withBody", "()I", virtual = false), clinit(iface)),
            analyze("kotlin", "GeneratedInterface\$DefaultImpls").callsOf("withBody", "(L${iface.replace('.', '/')};)I"),
        )
    }

    @Test
    fun `an unreadable facade leaves the call a verbatim edge to the forwarder`() {
        val analysis =
            BranchSiteAnalyzer.analyze(
                File("build/classes/kotlin/test/com/example/target/KotlinKindTargetKt.class").readBytes(),
                { null },
                listOf(TARGET),
                emptyList(),
                methodFilter = probedByMethodTier,
            )

        assertEquals(
            listOf(CallEdge(FACADE, "multifileGreeting", STRING_TO_STRING, virtual = false)),
            analysis.callsOf("callsMultifileGreeting", "()Ljava/lang/String;"),
        )
    }

    @Test
    fun `a same-class call to a JvmOverloads forwarder passes through it and its default twin to the full function`() {
        val owner = "com/example/target/AsmSameClassOverloads"
        val bytes = sameClassOverloads(owner)

        assertEquals(
            listOf(CallEdge(owner.replace('/', '.'), "f", "(ILjava/lang/String;)Ljava/lang/String;", virtual = false)),
            BranchSiteAnalyzer.analyze(bytes, lookup, listOf(TARGET), emptyList(), methodFilter = probedByMethodTier).callsOf("g", "()Ljava/lang/String;"),
        )
    }

    /**
     * A class shaped as kotlinc compiles `@JvmOverloads fun f(a: Int, b: String = "x")` plus a
     * method `g` that calls the overload `f(1)`, as Java code would: the full `f`, its synthetic
     * `f$default` twin with one mask test, and the overload forwarding to the twin.
     */
    private fun sameClassOverloads(owner: String): ByteArray {
        val full = "(ILjava/lang/String;)Ljava/lang/String;"
        val twinDescriptor = "(L$owner;ILjava/lang/String;ILjava/lang/Object;)Ljava/lang/String;"
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, owner, null, "java/lang/Object", null)

        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "f", full, null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 2)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC, "f\$default", twinDescriptor, null, null).apply {
            visitCode()
            val skip = Label()
            visitVarInsn(Opcodes.ILOAD, 3)
            visitInsn(Opcodes.ICONST_2)
            visitInsn(Opcodes.IAND)
            visitJumpInsn(Opcodes.IFEQ, skip)
            visitLdcInsn("x")
            visitVarInsn(Opcodes.ASTORE, 2)
            visitLabel(skip)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ILOAD, 1)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "f", full, false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "f", "(I)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ILOAD, 1)
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ICONST_2)
            visitInsn(Opcodes.ACONST_NULL)
            visitMethodInsn(Opcodes.INVOKESTATIC, owner, "f\$default", twinDescriptor, false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "g", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitInsn(Opcodes.ICONST_1)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "f", "(I)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
